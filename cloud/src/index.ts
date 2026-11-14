/**
 * Vanity Player cloud: serves a music library from the R2 bucket `vanity-music`.
 *
 *   GET /library      the song list (library.json)
 *   GET /stream/:id   a song's audio, with HTTP Range support
 *   GET /cover/:id    cover art
 *
 * Every request must carry the access key in the `X-App-Key` header. The key is the Worker
 * secret APP_KEY (`wrangler secret put APP_KEY`); the app never sees any R2 credentials.
 */

export interface Env {
  MUSIC: R2Bucket;
  APP_KEY: string;
}

const KEY_HEADER = "X-App-Key";

const CORS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
  "Access-Control-Allow-Headers": `${KEY_HEADER}, Range, If-None-Match`,
  "Access-Control-Expose-Headers": "Content-Length, Content-Range, Accept-Ranges, ETag",
  "Access-Control-Max-Age": "86400",
};

/** Audio and covers are stored under their content hash, so they never change. */
const IMMUTABLE = "public, max-age=31536000, immutable";

/** Ids are hex content hashes; anything else can't be an object we stored. */
const ID = /^[a-f0-9]{16,64}$/;

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    if (request.method !== "GET" && request.method !== "HEAD") {
      return json({ error: "method_not_allowed" }, 405, { Allow: "GET, HEAD, OPTIONS" });
    }

    if (!env.APP_KEY) return json({ error: "server_not_configured", message: "APP_KEY secret is not set" }, 500);
    const given = request.headers.get(KEY_HEADER) ?? "";
    if (!(await sameKey(given, env.APP_KEY))) {
      return json({ error: "unauthorized", message: "Missing or wrong access key" }, 401);
    }

    const path = new URL(request.url).pathname.replace(/\/+$/, "") || "/";
    if (path === "/library") return library(request, env);

    const stream = path.match(/^\/stream\/([^/]+)$/);
    if (stream) return media(request, env, ctx, `audio/${stream[1]}`, stream[1], "application/octet-stream");

    const cover = path.match(/^\/cover\/([^/]+)$/);
    if (cover) return media(request, env, ctx, `covers/${cover[1]}`, cover[1], "image/jpeg");

    return json({ error: "not_found" }, 404);
  },
} satisfies ExportedHandler<Env>;

/** Compares the keys' SHA-256 digests in constant time, so timing doesn't leak the key. */
async function sameKey(given: string, expected: string): Promise<boolean> {
  const enc = new TextEncoder();
  const [a, b] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(given)),
    crypto.subtle.digest("SHA-256", enc.encode(expected)),
  ]);
  const x = new Uint8Array(a);
  const y = new Uint8Array(b);
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

function json(body: unknown, status = 200, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", ...CORS, ...extra },
  });
}

/** The song list. Small and changes on every upload, so it's revalidated with its ETag. */
async function library(request: Request, env: Env): Promise<Response> {
  const object = await env.MUSIC.get("library.json");
  if (!object) return json({ version: 1, songs: [] });
  const headers = new Headers({
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "private, no-cache",
    ETag: object.httpEtag,
    ...CORS,
  });
  // Cloudflare may turn the ETag into a weak one ("W/...") when it compresses the response.
  const strip = (tag: string | null) => (tag ?? "").replace(/^W\//, "");
  if (request.headers.has("If-None-Match") && strip(request.headers.get("If-None-Match")) === strip(object.httpEtag)) {
    return new Response(null, { status: 304, headers });
  }
  headers.set("Content-Length", String(object.size));
  return new Response(request.method === "HEAD" ? null : object.body, { headers });
}

/** Audio or a cover: from the edge cache when it's there, otherwise from R2. */
async function media(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  key: string,
  id: string,
  fallbackType: string,
): Promise<Response> {
  if (!ID.test(id)) return json({ error: "not_found" }, 404);

  // The access key was already checked, so the cache entry is keyed by the URL alone. The cache
  // answers Range requests itself from a stored full response.
  const cache = caches.default;
  const cacheKey = new Request(new URL(request.url).toString(), { method: "GET" });
  if (request.method === "GET") {
    const hit = await cache.match(new Request(cacheKey, { headers: rangeOnly(request) }));
    if (hit) {
      const res = new Response(hit.body, hit);
      for (const [k, v] of Object.entries(CORS)) res.headers.set(k, v);
      res.headers.set("X-Cache", "HIT");
      return res;
    }
  }

  const head = await env.MUSIC.head(key);
  if (!head) return json({ error: "not_found", message: "No such song or cover" }, 404);

  const size = head.size;
  const base = new Headers({
    "Content-Type": head.httpMetadata?.contentType ?? fallbackType,
    "Accept-Ranges": "bytes",
    "Cache-Control": IMMUTABLE,
    ETag: head.httpEtag,
    "X-Cache": "MISS",
    ...CORS,
  });

  const rangeHeader = request.headers.get("Range");
  const range = rangeHeader ? parseRange(rangeHeader, size) : null;
  if (range === "invalid") {
    base.set("Content-Range", `bytes */${size}`);
    return new Response(null, { status: 416, headers: base });
  }

  // Fill the edge cache with the whole object in the background, so the next request (from
  // anyone with the key, at this location) doesn't touch R2.
  if (request.method === "GET" && size <= 500 * 1024 * 1024) {
    ctx.waitUntil(
      (async () => {
        const full = await env.MUSIC.get(key);
        if (!full) return;
        const headers = new Headers(base);
        headers.delete("X-Cache");
        headers.set("Content-Length", String(size));
        await cache.put(cacheKey, new Response(full.body, { headers }));
      })().catch(() => {}),
    );
  }

  if (request.method === "HEAD") {
    base.set("Content-Length", String(size));
    return new Response(null, { headers: base });
  }

  if (range) {
    const length = range.end - range.start + 1;
    const part = await env.MUSIC.get(key, { range: { offset: range.start, length } });
    if (!part) return json({ error: "not_found" }, 404);
    base.set("Content-Range", `bytes ${range.start}-${range.end}/${size}`);
    base.set("Content-Length", String(length));
    return new Response(part.body, { status: 206, headers: base });
  }

  const object = await env.MUSIC.get(key);
  if (!object) return json({ error: "not_found" }, 404);
  base.set("Content-Length", String(size));
  return new Response(object.body, { headers: base });
}

function rangeOnly(request: Request): Headers {
  const h = new Headers();
  const range = request.headers.get("Range");
  if (range) h.set("Range", range);
  return h;
}

/**
 * Parses a single-range `Range` header: "bytes=0-99", "bytes=100-" or "bytes=-500" (the last 500
 * bytes). Returns null when the header should be ignored (other units, multiple ranges).
 */
export function parseRange(header: string, size: number): { start: number; end: number } | "invalid" | null {
  const m = header.trim().match(/^bytes=(\d*)-(\d*)$/);
  if (!m) return null;
  const [, a, b] = m;
  if (a === "" && b === "") return "invalid";
  if (size === 0) return "invalid";
  if (a === "") {
    const suffix = Number(b);
    if (suffix === 0) return "invalid";
    return { start: Math.max(0, size - suffix), end: size - 1 };
  }
  const start = Number(a);
  const end = b === "" ? size - 1 : Math.min(Number(b), size - 1);
  if (start >= size || start > end) return "invalid";
  return { start, end };
}
