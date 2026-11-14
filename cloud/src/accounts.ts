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
 *   DELETE /me                                       deletes the account and its data
 */
import { neon, type NeonQueryFunction } from "@neondatabase/serverless";

export interface AccountsEnv {
  DATABASE_URL?: string;
}

type Sql = NeonQueryFunction<false, false>;
type Json = (body: unknown, status?: number) => Response;

const USERNAME = /^[a-z0-9][a-z0-9._-]{2,23}$/;
const SONG_ID = /^[a-f0-9]{16,64}$/;
const PBKDF2_ITERATIONS = 100_000; // the most Workers' WebCrypto allows
const MAX_OPS = 1000;
const MAX_PLAYS_RETURNED = 5000;
const MAX_PLAYLISTS_BYTES = 512 * 1024;

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
  if (!path.startsWith("/auth/") && path !== "/me" && !path.startsWith("/me/")) return null;
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

  if (path === "/auth/logout" && method === "POST") {
    await sql`DELETE FROM vp_sessions WHERE token_hash = ${tokenHash}`;
    return json({ ok: true });
  }

  if (path === "/me/sync" && method === "GET") {
    const [likes, plays, user] = await Promise.all([
      sql`SELECT song_id, liked_at FROM vp_likes WHERE user_id = ${userId} ORDER BY liked_at DESC`,
      sql`SELECT song_id, played_at FROM vp_plays WHERE user_id = ${userId} ORDER BY played_at DESC LIMIT ${MAX_PLAYS_RETURNED}`,
      sql`SELECT playlists FROM vp_users WHERE id = ${userId}`,
    ]);
    return json({
      user: { id: userId, username: session[0].display_name },
      likes: likes.map((r) => ({ songId: r.song_id, at: Number(r.liked_at) })),
      plays: plays.map((r) => ({ songId: r.song_id, at: Number(r.played_at) })),
      playlists: user[0]?.playlists ?? [],
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

  if (path === "/me" && method === "DELETE") {
    await sql`DELETE FROM vp_users WHERE id = ${userId}`; // likes, plays and sessions go with it
    return json({ ok: true });
  }

  return json({ error: "not_found" }, 404);
}
