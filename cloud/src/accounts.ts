/**
 * Accounts: sign-up, sign-in, and each account's own likes, play history and playlists, stored
 * in Neon Postgres. The Worker is the only thing that holds the database connection string
 * (the DATABASE_URL secret); the app talks to these endpoints with a session token.
 *
 *   POST   /auth/signup   {username, password}      -> {token, user}
 *   POST   /auth/login    {username, password}      -> {token, user}
 *   POST   /auth/logout
 *   GET    /me/sync                                  -> {user, likes, plays, playlists}
 *   POST   /me/ops        {ops: [{type, songId, at}]}   type: like | unlike | play
 *   PUT    /me/playlists  {playlists: [...]}
 *   PUT    /me/artists    {artists: [names]}   favourite artists
 *   PUT    /me/profile    {bio, private: {bio, likes, playlists, artists, stats}}
 *   GET    /users/:username                     -> that account's public profile
 *   DELETE /me                                       deletes the account and its data
 */
import { neon, type NeonQueryFunction } from "@neondatabase/serverless";

export interface AccountsEnv {
  DATABASE_URL?: string;
  /** Profile pictures are kept in the bucket under avatars/<user id>. */
  MUSIC: R2Bucket;
}

type Sql = NeonQueryFunction<false, false>;
type Json = (body: unknown, status?: number) => Response;

const USERNAME = /^[a-z0-9][a-z0-9._-]{2,23}$/;
const SONG_ID = /^[a-f0-9]{16,64}$/;
const PBKDF2_ITERATIONS = 100_000; // the most Workers' WebCrypto allows
const MAX_OPS = 1000;
const MAX_PLAYS_RETURNED = 5000;
const MAX_PLAYLISTS_BYTES = 512 * 1024;

/** Which parts of a profile are private; anything not set is public. */
function privacy(raw: any): { bio: boolean; likes: boolean; playlists: boolean; artists: boolean; stats: boolean } {
  // Listening stats are the exception: private until the owner makes them public.
  return { bio: raw?.bio === true, likes: raw?.likes === true, playlists: raw?.playlists === true, artists: raw?.artists === true, stats: raw?.stats !== false };
}

/** How long "what they're playing" stays shown after the last report. */
const PRESENCE_MS = 45_000;
/** A session ends for its guests when the host hasn't reported for this long. */
const SESSION_STALE_MS = 45_000;
/** A request to listen along is offered for this long. */
const LISTEN_REQUEST_MS = 60_000;

/** What the app appends to /avatar/ to show someone's picture (null when they have none). */
function avatarToken(id: unknown, version: unknown): string | null {
  return typeof version === "string" && version ? `${id}?v=${version}` : null;
}

/** The owner's admin list (config.json in the bucket: {"admins": ["username"]}), re-read every minute. */
let adminCache: { at: number; names: Set<string> } | null = null;
async function adminNames(env: AccountsEnv): Promise<Set<string>> {
  if (adminCache && Date.now() - adminCache.at < 60_000) return adminCache.names;
  let names = new Set<string>();
  try {
    const object = await env.MUSIC.get("config.json");
    const parsed: any = object ? await object.json() : null;
    if (Array.isArray(parsed?.admins)) names = new Set(parsed.admins.filter((a: unknown) => typeof a === "string").map((a: string) => a.trim().toLowerCase()));
  } catch {
    // no admins if the file can't be read
  }
  adminCache = { at: Date.now(), names };
  return names;
}

let schemaReady: Promise<void> | null = null;

/** Creates the tables on first use (once per Worker instance; cheap when they exist). */
function ensureSchema(sql: Sql): Promise<void> {
  schemaReady ??= (async () => {
    await sql`CREATE TABLE IF NOT EXISTS vp_users (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      username TEXT NOT NULL UNIQUE,
      display_name TEXT NOT NULL,
      pass_hash TEXT NOT NULL,
      pass_salt TEXT NOT NULL,
      playlists JSONB NOT NULL DEFAULT '[]'::jsonb,
      created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    )`;
    // Favourite artists were added later.
    await sql`ALTER TABLE vp_users ADD COLUMN IF NOT EXISTS artists JSONB NOT NULL DEFAULT '[]'::jsonb`;
    // Profiles: an optional bio, and which parts of the profile the owner keeps private.
    await sql`ALTER TABLE vp_users ADD COLUMN IF NOT EXISTS bio TEXT NOT NULL DEFAULT ''`;
    await sql`ALTER TABLE vp_users ADD COLUMN IF NOT EXISTS private JSONB NOT NULL DEFAULT '{}'::jsonb`;
    // Friends, what each person is playing, and listening together.
    await sql`ALTER TABLE vp_users ADD COLUMN IF NOT EXISTS avatar TEXT`;
    await sql`CREATE TABLE IF NOT EXISTS vp_reports (
      id BIGSERIAL PRIMARY KEY,
      user_id UUID REFERENCES vp_users(id) ON DELETE SET NULL,
      username TEXT NOT NULL,
      message TEXT NOT NULL,
      app TEXT NOT NULL DEFAULT '',
      created_at BIGINT NOT NULL
    )`;
    await sql`ALTER TABLE vp_users ADD COLUMN IF NOT EXISTS now_song TEXT`;
    await sql`ALTER TABLE vp_users ADD COLUMN IF NOT EXISTS now_next TEXT`;
    await sql`ALTER TABLE vp_users ADD COLUMN IF NOT EXISTS now_pos BIGINT NOT NULL DEFAULT 0`;
    await sql`ALTER TABLE vp_users ADD COLUMN IF NOT EXISTS now_playing BOOLEAN NOT NULL DEFAULT false`;
    await sql`ALTER TABLE vp_users ADD COLUMN IF NOT EXISTS now_at BIGINT NOT NULL DEFAULT 0`;
    await sql`ALTER TABLE vp_users ADD COLUMN IF NOT EXISTS seen_at BIGINT NOT NULL DEFAULT 0`;
    await sql`ALTER TABLE vp_users ADD COLUMN IF NOT EXISTS listen_host UUID`;
    await sql`CREATE TABLE IF NOT EXISTS vp_friends (
      a UUID NOT NULL REFERENCES vp_users(id) ON DELETE CASCADE,
      b UUID NOT NULL REFERENCES vp_users(id) ON DELETE CASCADE,
      status TEXT NOT NULL,
      created_at BIGINT NOT NULL,
      PRIMARY KEY (a, b)
    )`;
    await sql`CREATE TABLE IF NOT EXISTS vp_listen_requests (
      id BIGSERIAL PRIMARY KEY,
      from_id UUID NOT NULL REFERENCES vp_users(id) ON DELETE CASCADE,
      to_id UUID NOT NULL REFERENCES vp_users(id) ON DELETE CASCADE,
      created_at BIGINT NOT NULL
    )`;
    await sql`ALTER TABLE vp_listen_requests ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'ask'`;
    await sql`CREATE TABLE IF NOT EXISTS vp_listen_adds (
      id BIGSERIAL PRIMARY KEY,
      host_id UUID NOT NULL REFERENCES vp_users(id) ON DELETE CASCADE,
      from_id UUID NOT NULL REFERENCES vp_users(id) ON DELETE CASCADE,
      song_id TEXT NOT NULL
    )`;
    await sql`CREATE TABLE IF NOT EXISTS vp_sessions (
      token_hash TEXT PRIMARY KEY,
      user_id UUID NOT NULL REFERENCES vp_users(id) ON DELETE CASCADE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    )`;
    await sql`CREATE TABLE IF NOT EXISTS vp_likes (
      user_id UUID NOT NULL REFERENCES vp_users(id) ON DELETE CASCADE,
      song_id TEXT NOT NULL,
      liked_at BIGINT NOT NULL,
      PRIMARY KEY (user_id, song_id)
    )`;
    await sql`CREATE TABLE IF NOT EXISTS vp_plays (
      user_id UUID NOT NULL REFERENCES vp_users(id) ON DELETE CASCADE,
      song_id TEXT NOT NULL,
      played_at BIGINT NOT NULL,
      PRIMARY KEY (user_id, song_id, played_at)
    )`;
    await sql`CREATE INDEX IF NOT EXISTS vp_plays_recent ON vp_plays (user_id, played_at DESC)`;
  })().catch((e) => {
    schemaReady = null;
    throw e;
  });
  return schemaReady;
}

const b64 = (bytes: ArrayBuffer | Uint8Array) => btoa(String.fromCharCode(...new Uint8Array(bytes as ArrayBuffer)));
const unb64 = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

async function hashPassword(password: string, salt: Uint8Array): Promise<string> {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt, iterations: PBKDF2_ITERATIONS }, key, 256);
  return b64(bits);
}

async function sha256(text: string): Promise<string> {
  return b64(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)));
}

function sameString(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

async function newSession(sql: Sql, userId: string): Promise<string> {
  const token = b64(crypto.getRandomValues(new Uint8Array(32))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  await sql`INSERT INTO vp_sessions (token_hash, user_id) VALUES (${await sha256(token)}, ${userId})`;
  return token;
}

async function readJson(request: Request, maxBytes: number): Promise<any | null> {
  const text = await request.text();
  if (text.length > maxBytes) return null;
  try {
    const value = JSON.parse(text);
    return value && typeof value === "object" ? value : null;
  } catch {
    return null;
  }
}

/** Handles /auth/* and /me*; returns null for any other path. The app key was already checked. */
export async function accounts(request: Request, env: AccountsEnv, path: string, json: Json): Promise<Response | null> {
  if (!path.startsWith("/auth/") && path !== "/me" && !path.startsWith("/me/") && !path.startsWith("/users/")) return null;
  if (!env.DATABASE_URL) return json({ error: "accounts_not_configured", message: "Accounts aren't set up on this server" }, 503);
  const sql = neon(env.DATABASE_URL);
  await ensureSchema(sql);
  const method = request.method;

  if (path === "/auth/signup" && method === "POST") {
    const body = await readJson(request, 4096);
    const display = typeof body?.username === "string" ? body.username.trim() : "";
    const username = display.toLowerCase();
    const password = typeof body?.password === "string" ? body.password : "";
    if (!USERNAME.test(username)) {
      return json({ error: "bad_username", message: "Usernames are 3 to 24 letters, numbers, dots, dashes or underscores." }, 400);
    }
    if (password.length < 8 || password.length > 200) {
      return json({ error: "bad_password", message: "Passwords need at least 8 characters." }, 400);
    }
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const hash = await hashPassword(password, salt);
    const rows = await sql`
      INSERT INTO vp_users (username, display_name, pass_hash, pass_salt)
      VALUES (${username}, ${display}, ${hash}, ${b64(salt)})
      ON CONFLICT (username) DO NOTHING
      RETURNING id, display_name`;
    if (rows.length === 0) return json({ error: "username_taken", message: "That username is already taken." }, 409);
    const token = await newSession(sql, rows[0].id);
    return json({ token, user: { id: rows[0].id, username: rows[0].display_name } }, 201);
  }

  if (path === "/auth/login" && method === "POST") {
    const body = await readJson(request, 4096);
    const username = typeof body?.username === "string" ? body.username.trim().toLowerCase() : "";
    const password = typeof body?.password === "string" ? body.password : "";
    const rows = await sql`SELECT id, display_name, pass_hash, pass_salt FROM vp_users WHERE username = ${username}`;
    // Hash even when the user doesn't exist, so the answer takes the same time either way.
    const salt = rows.length ? unb64(rows[0].pass_salt) : new Uint8Array(16);
    const hash = await hashPassword(password.slice(0, 200), salt);
    if (!rows.length || !sameString(hash, rows[0].pass_hash)) {
      return json({ error: "bad_login", message: "Wrong username or password." }, 401);
    }
    const token = await newSession(sql, rows[0].id);
    return json({ token, user: { id: rows[0].id, username: rows[0].display_name } });
  }

  // Everything else needs a session.
  const bearer = (request.headers.get("Authorization") ?? "").match(/^Bearer (.+)$/)?.[1] ?? "";
  const tokenHash = bearer ? await sha256(bearer) : "";
  const session = tokenHash
    ? await sql`SELECT u.id, u.display_name FROM vp_sessions s JOIN vp_users u ON u.id = s.user_id WHERE s.token_hash = ${tokenHash}`
    : [];
  if (!session.length) return json({ error: "signed_out", message: "Please sign in again." }, 401);
  const userId: string = session[0].id;
  const admins = await adminNames(env);

  if (path === "/auth/logout" && method === "POST") {
    await sql`DELETE FROM vp_sessions WHERE token_hash = ${tokenHash}`;
    return json({ ok: true });
  }

  if (path === "/me/sync" && method === "GET") {
    const [likes, plays, user] = await Promise.all([
      sql`SELECT song_id, liked_at FROM vp_likes WHERE user_id = ${userId} ORDER BY liked_at DESC`,
      sql`SELECT song_id, played_at FROM vp_plays WHERE user_id = ${userId} ORDER BY played_at DESC LIMIT ${MAX_PLAYS_RETURNED}`,
      sql`SELECT playlists, artists, bio, private, avatar FROM vp_users WHERE id = ${userId}`,
    ]);
    return json({
      user: { id: userId, username: session[0].display_name },
      admin: admins.has(String(session[0].display_name).toLowerCase()),
      likes: likes.map((r) => ({ songId: r.song_id, at: Number(r.liked_at) })),
      plays: plays.map((r) => ({ songId: r.song_id, at: Number(r.played_at) })),
      playlists: user[0]?.playlists ?? [],
      artists: user[0]?.artists ?? [],
      profile: { bio: user[0]?.bio ?? "", private: privacy(user[0]?.private), avatar: avatarToken(userId, user[0]?.avatar) },
    });
  }

  if (path === "/me/ops" && method === "POST") {
    const body = await readJson(request, 256 * 1024);
    const ops: unknown[] = Array.isArray(body?.ops) ? body.ops : [];
    if (ops.length > MAX_OPS) return json({ error: "too_many", message: "Too many changes at once." }, 413);
    const like: [string, number][] = [];
    const unlike: string[] = [];
    const play: [string, number][] = [];
    // Later operations on the same song win, so apply likes/unlikes in order.
    const liked = new Map<string, number | null>();
    for (const raw of ops) {
      const op = raw as { type?: string; songId?: string; at?: number };
      if (typeof op?.songId !== "string" || !SONG_ID.test(op.songId)) continue;
      const at = Number.isFinite(op.at) ? Math.trunc(op.at as number) : Date.now();
      if (op.type === "like") liked.set(op.songId, at);
      else if (op.type === "unlike") liked.set(op.songId, null);
      else if (op.type === "play") play.push([op.songId, at]);
    }
    for (const [songId, at] of liked) at === null ? unlike.push(songId) : like.push([songId, at]);
    if (like.length) {
      await sql`INSERT INTO vp_likes (user_id, song_id, liked_at)
        SELECT ${userId}::uuid, s, a FROM unnest(${like.map((l) => l[0])}::text[], ${like.map((l) => l[1])}::bigint[]) AS t(s, a)
        ON CONFLICT (user_id, song_id) DO UPDATE SET liked_at = EXCLUDED.liked_at`;
    }
    if (unlike.length) await sql`DELETE FROM vp_likes WHERE user_id = ${userId} AND song_id = ANY(${unlike}::text[])`;
    if (play.length) {
      await sql`INSERT INTO vp_plays (user_id, song_id, played_at)
        SELECT ${userId}::uuid, s, a FROM unnest(${play.map((p) => p[0])}::text[], ${play.map((p) => p[1])}::bigint[]) AS t(s, a)
        ON CONFLICT DO NOTHING`;
    }
    return json({ ok: true, applied: like.length + unlike.length + play.length });
  }

  if (path === "/me/playlists" && method === "PUT") {
    const body = await readJson(request, MAX_PLAYLISTS_BYTES);
    if (!Array.isArray(body?.playlists)) return json({ error: "bad_request", message: "Playlists are missing or too large." }, 400);
    const clean = body.playlists.slice(0, 500).map((p: any) => ({
      name: String(p?.name ?? "").slice(0, 200),
      createdAt: Number.isFinite(p?.createdAt) ? Math.trunc(p.createdAt) : Date.now(),
      songs: (Array.isArray(p?.songs) ? p.songs : []).filter((s: unknown) => typeof s === "string" && SONG_ID.test(s)).slice(0, 5000),
    }));
    await sql`UPDATE vp_users SET playlists = ${JSON.stringify(clean)}::jsonb WHERE id = ${userId}`;
    return json({ ok: true });
  }

  if (path === "/me/artists" && method === "PUT") {
    const body = await readJson(request, 64 * 1024);
    if (!Array.isArray(body?.artists)) return json({ error: "bad_request", message: "Artists are missing or too large." }, 400);
    const clean = [...new Set(body.artists.filter((a: unknown) => typeof a === "string" && a.trim()).map((a: string) => a.trim().slice(0, 200)))].slice(0, 1000);
    await sql`UPDATE vp_users SET artists = ${JSON.stringify(clean)}::jsonb WHERE id = ${userId}`;
    return json({ ok: true });
  }

  // ---- Friends and listening together ----

  /** Another account's id by username, or null. */
  const idOf = async (name: unknown): Promise<string | null> => {
    if (typeof name !== "string") return null;
    const rows = await sql`SELECT id FROM vp_users WHERE username = ${name.trim().toLowerCase()}`;
    return rows[0]?.id ?? null;
  };
  const areFriends = async (other: string): Promise<boolean> =>
    (await sql`SELECT 1 FROM vp_friends WHERE status = 'accepted' AND ((a = ${userId} AND b = ${other}) OR (a = ${other} AND b = ${userId}))`).length > 0;

  // The app calls this every few seconds: it reports what this account is playing and gets back
  // everything social in one go (friends and what they're playing, requests, the shared session).
  if (path === "/me/pulse" && method === "POST") {
    const body = await readJson(request, 8 * 1024);
    const now = Date.now();
    const song = typeof body?.songId === "string" && SONG_ID.test(body.songId) ? body.songId : null;
    const next = typeof body?.nextSongId === "string" && SONG_ID.test(body.nextSongId) ? body.nextSongId : null;
    const pos = Number.isFinite(body?.positionMs) ? Math.max(0, Math.trunc(body.positionMs)) : 0;
    const playing = body?.playing === true && song !== null;
    const share = body?.share !== false;
    const mine = await sql`UPDATE vp_users SET now_song = ${song}, now_next = ${next}, now_pos = ${pos}, now_playing = ${playing}, now_at = ${share ? now : 0}, seen_at = ${now}
      WHERE id = ${userId} RETURNING listen_host`;
    const hostId: string | null = mine[0]?.listen_host ?? null;
    const [links, asks] = await Promise.all([
      sql`SELECT u.id, u.avatar, u.display_name, u.now_song, u.now_playing, u.now_pos, u.now_at, u.seen_at, f.status, (f.a = ${userId}) AS sent
        FROM vp_friends f JOIN vp_users u ON u.id = CASE WHEN f.a = ${userId} THEN f.b ELSE f.a END
        WHERE f.a = ${userId} OR f.b = ${userId} ORDER BY lower(u.display_name)`,
      sql`SELECT r.id, r.kind, u.display_name FROM vp_listen_requests r JOIN vp_users u ON u.id = r.from_id
        WHERE r.to_id = ${userId} AND r.created_at > ${now - LISTEN_REQUEST_MS} ORDER BY r.id`,
    ]);
    let session: unknown = null;
    let adds: unknown[] = [];
    if (hostId) {
      const host = await sql`SELECT display_name, now_song, now_next, now_playing, now_pos, seen_at FROM vp_users WHERE id = ${hostId}`;
      if (host.length === 0 || now - Number(host[0].seen_at) > SESSION_STALE_MS) {
        await sql`UPDATE vp_users SET listen_host = NULL WHERE id = ${userId}`; // the host went away
      } else {
        session = {
          role: "guest", host: host[0].display_name, songId: host[0].now_song, nextSongId: host[0].now_next, playing: host[0].now_playing,
          positionMs: Number(host[0].now_pos), ageMs: Math.max(0, now - Number(host[0].seen_at)),
        };
      }
    } else {
      const members = await sql`SELECT display_name FROM vp_users WHERE listen_host = ${userId} AND seen_at > ${now - SESSION_STALE_MS} ORDER BY lower(display_name)`;
      if (members.length) {
        session = { role: "host", members: members.map((m) => m.display_name) };
        const queued = await sql`DELETE FROM vp_listen_adds a USING vp_users u WHERE a.host_id = ${userId} AND u.id = a.from_id RETURNING a.id, a.song_id, u.display_name`;
        adds = queued.sort((x, y) => Number(x.id) - Number(y.id)).map((q) => ({ songId: q.song_id, from: q.display_name }));
      }
    }
    const fresh = (at: unknown) => now - Number(at) < PRESENCE_MS;
    return json({
      friends: links.filter((l) => l.status === "accepted").map((l) => ({
        username: l.display_name,
        admin: admins.has(String(l.display_name).toLowerCase()),
        avatar: avatarToken(l.id, l.avatar),
        online: fresh(l.seen_at),
        songId: fresh(l.now_at) ? l.now_song : null,
        playing: fresh(l.now_at) && l.now_playing === true,
      })),
      requests: links.filter((l) => l.status === "pending" && !l.sent).map((l) => l.display_name),
      sent: links.filter((l) => l.status === "pending" && l.sent).map((l) => l.display_name),
      listenRequests: asks.map((a) => ({ id: Number(a.id), from: a.display_name, kind: a.kind })),
      session,
      adds,
    });
  }

  if (path === "/me/friends/request" && method === "POST") {
    const body = await readJson(request, 4096);
    const other = await idOf(body?.username);
    if (!other) return json({ error: "no_such_user", message: "No account with that username." }, 404);
    if (other === userId) return json({ error: "bad_request", message: "That's you." }, 400);
    // If they already asked us, this accepts it.
    const theirs = await sql`UPDATE vp_friends SET status = 'accepted' WHERE a = ${other} AND b = ${userId} RETURNING status`;
    if (theirs.length === 0) {
      await sql`INSERT INTO vp_friends (a, b, status, created_at) VALUES (${userId}, ${other}, 'pending', ${Date.now()}) ON CONFLICT DO NOTHING`;
    }
    return json({ ok: true, friends: theirs.length > 0 });
  }

  if (path === "/me/friends/respond" && method === "POST") {
    const body = await readJson(request, 4096);
    const other = await idOf(body?.username);
    if (!other) return json({ error: "no_such_user", message: "No account with that username." }, 404);
    if (body?.accept === true) await sql`UPDATE vp_friends SET status = 'accepted' WHERE a = ${other} AND b = ${userId}`;
    else await sql`DELETE FROM vp_friends WHERE a = ${other} AND b = ${userId} AND status = 'pending'`;
    return json({ ok: true });
  }

  if (path === "/me/friends/remove" && method === "POST") {
    const body = await readJson(request, 4096);
    const other = await idOf(body?.username);
    if (other) {
      await sql`DELETE FROM vp_friends WHERE (a = ${userId} AND b = ${other}) OR (a = ${other} AND b = ${userId})`;
      await sql`UPDATE vp_users SET listen_host = NULL WHERE (id = ${userId} AND listen_host = ${other}) OR (id = ${other} AND listen_host = ${userId})`;
    }
    return json({ ok: true });
  }

  // Ask a friend to let you listen along with them.
  if (path === "/me/listen/request" && method === "POST") {
    const body = await readJson(request, 4096);
    const other = await idOf(body?.username);
    if (!other || !(await areFriends(other))) return json({ error: "not_friends", message: "You can only listen along with friends." }, 403);
    await sql`DELETE FROM vp_listen_requests WHERE from_id = ${userId} AND kind = 'ask'`;
    await sql`INSERT INTO vp_listen_requests (from_id, to_id, created_at) VALUES (${userId}, ${other}, ${Date.now()})`;
    return json({ ok: true });
  }

  // Invite a friend to listen along with you (they become a guest in your session if they accept).
  if (path === "/me/listen/invite" && method === "POST") {
    const body = await readJson(request, 4096);
    const other = await idOf(body?.username);
    if (!other || !(await areFriends(other))) return json({ error: "not_friends", message: "You can only invite friends." }, 403);
    const me = await sql`SELECT listen_host FROM vp_users WHERE id = ${userId}`;
    if (me[0]?.listen_host) return json({ error: "guest", message: "Only the host can invite people. Leave the session to start your own." }, 409);
    await sql`DELETE FROM vp_listen_requests WHERE from_id = ${userId} AND to_id = ${other}`;
    await sql`INSERT INTO vp_listen_requests (from_id, to_id, created_at, kind) VALUES (${userId}, ${other}, ${Date.now()}, 'invite')`;
    return json({ ok: true });
  }

  if (path === "/me/listen/respond" && method === "POST") {
    const body = await readJson(request, 4096);
    const id = Number.isFinite(body?.id) ? Math.trunc(body.id) : -1;
    const asked = await sql`DELETE FROM vp_listen_requests WHERE id = ${id} AND to_id = ${userId} RETURNING from_id, kind`;
    if (asked.length && body?.accept === true) {
      // "ask": they join me. "invite": I join them. Either way the host can't also be a guest.
      const host = asked[0].kind === "invite" ? asked[0].from_id : userId;
      const guest = asked[0].kind === "invite" ? userId : asked[0].from_id;
      await sql`UPDATE vp_users SET listen_host = NULL WHERE id = ${host}`;
      await sql`UPDATE vp_users SET listen_host = ${host} WHERE id = ${guest}`;
    }
    return json({ ok: true });
  }

  if (path === "/me/listen/leave" && method === "POST") {
    await sql`UPDATE vp_users SET listen_host = NULL WHERE id = ${userId}`;
    return json({ ok: true });
  }

  // A guest adds a song to the host's queue; the host's app picks it up on its next pulse.
  if (path === "/me/listen/add" && method === "POST") {
    const body = await readJson(request, 4096);
    const me = await sql`SELECT listen_host FROM vp_users WHERE id = ${userId}`;
    const host = me[0]?.listen_host;
    if (!host) return json({ error: "no_session", message: "You're not in a listening session." }, 409);
    if (typeof body?.songId !== "string" || !SONG_ID.test(body.songId)) return json({ error: "bad_request" }, 400);
    const waiting = await sql`SELECT count(*)::int AS n FROM vp_listen_adds WHERE host_id = ${host}`;
    if (waiting[0].n >= 100) return json({ error: "too_many", message: "The queue is full for now." }, 429);
    await sql`INSERT INTO vp_listen_adds (host_id, from_id, song_id) VALUES (${host}, ${userId}, ${body.songId})`;
    return json({ ok: true });
  }

  // Bug reports: anyone signed in can send one; only admins can read them.
  if (path === "/me/report" && method === "POST") {
    const body = await readJson(request, 16 * 1024);
    const message = typeof body?.message === "string" ? body.message.trim().slice(0, 4000) : "";
    if (message.length < 5) return json({ error: "bad_request", message: "Describe what went wrong in a few words." }, 400);
    const recent = await sql`SELECT count(*)::int AS n FROM vp_reports WHERE user_id = ${userId} AND created_at > ${Date.now() - 3_600_000}`;
    if (recent[0].n >= 10) return json({ error: "too_many", message: "That's a lot of reports in an hour. Try again later." }, 429);
    const app = typeof body?.app === "string" ? body.app.slice(0, 120) : "";
    await sql`INSERT INTO vp_reports (user_id, username, message, app, created_at) VALUES (${userId}, ${session[0].display_name}, ${message}, ${app}, ${Date.now()})`;
    return json({ ok: true });
  }

  if (path === "/me/reports" && method === "GET") {
    if (!admins.has(String(session[0].display_name).toLowerCase())) return json({ error: "forbidden", message: "Only the admin can read bug reports." }, 403);
    const rows = await sql`SELECT id, username, message, app, created_at FROM vp_reports ORDER BY id DESC LIMIT 200`;
    return json({ reports: rows.map((r) => ({ id: Number(r.id), username: r.username, message: r.message, app: r.app, at: Number(r.created_at) })) });
  }

  if (path === "/me/reports/delete" && method === "POST") {
    if (!admins.has(String(session[0].display_name).toLowerCase())) return json({ error: "forbidden" }, 403);
    const body = await readJson(request, 4096);
    if (Number.isFinite(body?.id)) await sql`DELETE FROM vp_reports WHERE id = ${Math.trunc(body.id)}`;
    return json({ ok: true });
  }

  // A profile picture: a small JPEG or PNG the app has already cropped and shrunk.
  if (path === "/me/avatar" && method === "PUT") {
    const body = await readJson(request, 400 * 1024);
    let bytes: Uint8Array;
    try {
      bytes = Uint8Array.from(atob(String(body?.image ?? "")), (c) => c.charCodeAt(0));
    } catch {
      return json({ error: "bad_request", message: "That picture couldn't be read." }, 400);
    }
    const jpeg = bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
    const png = bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47;
    if (bytes.length < 100 || bytes.length > 300 * 1024 || !(jpeg || png)) {
      return json({ error: "bad_request", message: "Use a JPEG or PNG picture." }, 400);
    }
    await env.MUSIC.put(`avatars/${userId}`, bytes, { httpMetadata: { contentType: jpeg ? "image/jpeg" : "image/png" } });
    const version = Date.now().toString(36);
    await sql`UPDATE vp_users SET avatar = ${version} WHERE id = ${userId}`;
    return json({ ok: true, avatar: avatarToken(userId, version) });
  }

  if (path === "/me/avatar" && method === "DELETE") {
    await env.MUSIC.delete(`avatars/${userId}`);
    await sql`UPDATE vp_users SET avatar = NULL WHERE id = ${userId}`;
    return json({ ok: true });
  }

  if (path === "/me/profile" && method === "PUT") {
    const body = await readJson(request, 16 * 1024);
    const bio = typeof body?.bio === "string" ? body.bio.trim().slice(0, 300) : "";
    await sql`UPDATE vp_users SET bio = ${bio}, private = ${JSON.stringify(privacy(body?.private))}::jsonb WHERE id = ${userId}`;
    return json({ ok: true });
  }

  // Someone else's profile: only the parts they haven't made private.
  const other = path.match(/^\/users\/([^/]{1,40})$/);
  if (other && method === "GET") {
    const rows = await sql`SELECT id, display_name, bio, private, playlists, artists, avatar FROM vp_users WHERE username = ${decodeURIComponent(other[1]).trim().toLowerCase()}`;
    if (rows.length === 0) return json({ error: "no_such_user", message: "No account with that username." }, 404);
    const u = rows[0];
    const hidden = privacy(u.private);
    const likes = hidden.likes ? null : await sql`SELECT song_id FROM vp_likes WHERE user_id = ${u.id} ORDER BY liked_at DESC LIMIT 2000`;
    // Stats: how often each song was played; the app works out top songs and artists from it.
    let stats: unknown = null;
    if (!hidden.stats) {
      const [total, top] = await Promise.all([
        sql`SELECT count(*)::int AS n FROM vp_plays WHERE user_id = ${u.id}`,
        sql`SELECT song_id, count(*)::int AS n FROM vp_plays WHERE user_id = ${u.id} GROUP BY song_id ORDER BY n DESC LIMIT 300`,
      ]);
      stats = { plays: total[0].n, songs: top.map((r) => ({ songId: r.song_id, plays: r.n })) };
    }
    return json({
      username: u.display_name,
      admin: admins.has(String(u.display_name).toLowerCase()),
      avatar: avatarToken(u.id, u.avatar),
      stats,
      bio: hidden.bio ? null : u.bio,
      likes: likes ? likes.map((r) => r.song_id) : null,
      playlists: hidden.playlists ? null : u.playlists,
      artists: hidden.artists ? null : u.artists,
    });
  }

  if (path === "/me" && method === "DELETE") {
    await env.MUSIC.delete(`avatars/${userId}`);
    await sql`DELETE FROM vp_users WHERE id = ${userId}`; // likes, plays and sessions go with it
    return json({ ok: true });
  }

  return json({ error: "not_found" }, 404);
}
