#!/usr/bin/env node
/**
 * Checks a running Worker: `BASE=https://... KEY=... npm test` (defaults to `npm run dev`
 * on localhost with the key from .dev.vars).
 */
const BASE = (process.env.BASE ?? "http://127.0.0.1:8787").replace(/\/+$/, "");
const KEY = process.env.KEY ?? "dev-key";
const auth = { "X-App-Key": KEY };
let failures = 0;

function check(name, ok, detail = "") {
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? `  (${detail})` : ""}`);
  if (!ok) failures++;
}
const get = (p, headers = {}, method = "GET") => fetch(BASE + p, { method, headers });

let r = await get("/library");
check("no key -> 401", r.status === 401, `status ${r.status}`);
r = await get("/library", { "X-App-Key": "wrong" });
check("wrong key -> 401", r.status === 401 && (await r.json()).error === "unauthorized");
r = await get("/stream/0123456789abcdef0123456789abcdef", { "X-App-Key": "wrong" });
check("wrong key on /stream -> 401", r.status === 401);
r = await fetch(BASE + "/library", { method: "OPTIONS", headers: { Origin: "https://example.com", "Access-Control-Request-Headers": "x-app-key" } });
check("CORS preflight", r.status === 204 && r.headers.get("access-control-allow-origin") === "*" && /x-app-key/i.test(r.headers.get("access-control-allow-headers") ?? ""));

r = await get("/library", auth);
const lib = await r.json();
check("/library -> 200 JSON", r.status === 200 && Array.isArray(lib.songs), `${lib.songs?.length} songs`);
const etag = r.headers.get("etag");
if (etag) {
  r = await get("/library", { ...auth, "If-None-Match": etag });
  check("/library If-None-Match -> 304", r.status === 304);
}
r = await get("/nope", auth);
check("unknown path -> 404", r.status === 404);
r = await get("/stream/ffffffffffffffffffffffffffffffff", auth);
check("unknown song -> 404", r.status === 404);
r = await get("/stream/..%2Flibrary.json", auth);
check("bad id -> 404", r.status === 404);
r = await fetch(BASE + "/library", { method: "POST", headers: auth });
check("POST -> 405", r.status === 405);

const song = lib.songs?.[0];
if (!song) {
  console.log("SKIP  stream tests (library is empty)");
} else {
  const p = `/stream/${song.id}`;
  r = await get(p, auth);
  const full = new Uint8Array(await r.arrayBuffer());
  check("full stream -> 200", r.status === 200 && full.length === song.size, `${full.length} bytes`);
  check("Content-Type", r.headers.get("content-type") === song.mime, r.headers.get("content-type"));
  check("Content-Length", r.headers.get("content-length") === String(song.size), r.headers.get("content-length"));
  check("Accept-Ranges: bytes", r.headers.get("accept-ranges") === "bytes");
  check("Cache-Control immutable", /immutable/.test(r.headers.get("cache-control") ?? ""), r.headers.get("cache-control"));
  check("CORS on audio", r.headers.get("access-control-allow-origin") === "*");

  r = await get(p, auth, "HEAD");
  check("HEAD -> 200 with length", r.status === 200 && r.headers.get("content-length") === String(song.size));

  const same = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);
  r = await get(p, { ...auth, Range: "bytes=100-199" });
  let part = new Uint8Array(await r.arrayBuffer());
  check("Range 100-199 -> 206", r.status === 206 && part.length === 100, `status ${r.status}, ${part.length} bytes`);
  check("Content-Range", r.headers.get("content-range") === `bytes 100-199/${song.size}`, r.headers.get("content-range"));
  check("range bytes match the file", same(part, full.slice(100, 200)));

  const mid = Math.floor(song.size / 2);
  r = await get(p, { ...auth, Range: `bytes=${mid}-` });
  part = new Uint8Array(await r.arrayBuffer());
  check("open-ended range (seek) -> 206", r.status === 206 && same(part, full.slice(mid)), r.headers.get("content-range"));

  r = await get(p, { ...auth, Range: "bytes=-500" });
  part = new Uint8Array(await r.arrayBuffer());
  check("suffix range -> last 500 bytes", r.status === 206 && same(part, full.slice(-500)));

  r = await get(p, { ...auth, Range: `bytes=${song.size + 10}-` });
  check("range past the end -> 416", r.status === 416 && r.headers.get("content-range") === `bytes */${song.size}`, `status ${r.status}`);
  await r.arrayBuffer();

  if (song.coverId) {
    r = await get(`/cover/${song.coverId}`, auth);
    const img = new Uint8Array(await r.arrayBuffer());
    check("/cover -> image", r.status === 200 && /^image\//.test(r.headers.get("content-type") ?? "") && img.length > 100, `${r.headers.get("content-type")}, ${img.length} bytes`);
    r = await get(`/cover/${song.coverId}`);
    check("/cover without key -> 401", r.status === 401);
  } else {
    console.log("SKIP  cover test (first song has no cover)");
  }
}

console.log(failures ? `\n${failures} check(s) failed` : "\nAll checks passed");
process.exit(failures ? 1 : 0);
