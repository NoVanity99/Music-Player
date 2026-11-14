#!/usr/bin/env node
/**
 * Uploads a folder of music to the R2 bucket and rewrites library.json.
 *
 *   npm run upload -- "<path to music folder>" [--order "A,B,C"] [--prune] [--local] [--dry-run]
 *
 * - Each song's id is the SHA-256 of its file, so re-running only uploads new or changed files.
 * - A changed file (same path, new contents) replaces its old upload.
 * - --prune    also removes songs from the cloud whose files are no longer in the folder.
 * - --local    writes to Wrangler's local R2 simulator (for `npm run dev`), not Cloudflare.
 * - --dry-run  shows what would happen without uploading anything.
 * - --order    album names, oldest first, comma-separated. Sets their order on the artist page
 *              (matched against the album tag or a folder name). It's saved in library.json, so
 *              later uploads keep it; pass it again to change it.
 *
 * Uses `wrangler r2 object`, so it needs `npx wrangler login` (or CLOUDFLARE_API_TOKEN) first.
 */
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { mkdtemp, readdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseFile } from "music-metadata";

const BUCKET = "vanity-music";
const CLOUD_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const CACHE_FILE = path.join(CLOUD_DIR, ".upload-cache.json");
const AUDIO = { ".mp3": "audio/mpeg", ".flac": "audio/flac", ".m4a": "audio/mp4", ".ogg": "audio/ogg", ".wav": "audio/wav" };
/**
 * Which row of the artist page an album goes in. Put a release inside a folder with one of these
 * names (anywhere above the files) to file it there; everything else is a "custom" album.
 */
const ALBUM_TYPES = [
  [/^(released|released albums?|albums?|studio albums?)$/i, "released"],
  [/^(unreleased|unreleased songs?|leaks?)$/i, "unreleased"],
  [/^(eps?|e\.p\.s?)$/i, "ep"],
  [/^(singles?)$/i, "single"],
];
function albumTypeFor(rel) {
  for (const part of rel.split("/").slice(0, -1)) {
    for (const [pattern, type] of ALBUM_TYPES) if (pattern.test(part.trim())) return type;
  }
  return "custom";
}

const COVER_NAMES = ["cover", "folder", "front", "album", "albumart"];
const CONCURRENCY = 4;

const args = process.argv.slice(2);
const orderAt = args.indexOf("--order");
const ORDER_ARG = orderAt >= 0 ? args.splice(orderAt, 2)[1] : undefined;
if (orderAt >= 0 && !ORDER_ARG) {
  console.error('--order needs a list of album names, oldest first: --order "First album,Second album"');
  process.exit(1);
}
const flags = new Set(args.filter((a) => a.startsWith("--")));
const folderArg = args.find((a) => !a.startsWith("--"));
const LOCAL = flags.has("--local");
const DRY = flags.has("--dry-run");
const PRUNE = flags.has("--prune");

if (!folderArg) {
  console.error('Usage: npm run upload -- "<path to music folder>" [--order "A,B,C"] [--prune] [--local] [--dry-run]');
  process.exit(1);
}
const ROOT = path.resolve(folderArg);

/** Runs wrangler and resolves with its exit code and output. */
function wrangler(wranglerArgs) {
  return new Promise((resolve) => {
    const bin = path.join(CLOUD_DIR, "node_modules", "wrangler", "bin", "wrangler.js");
    const child = spawn(process.execPath, [bin, ...wranglerArgs, LOCAL ? "--local" : "--remote"], {
      cwd: CLOUD_DIR,
      env: { ...process.env, WRANGLER_SEND_METRICS: "false" },
    });
    let out = "";
    child.stdout.on("data", (d) => (out += d));
    child.stderr.on("data", (d) => (out += d));
    child.on("close", (code) => resolve({ code, out }));
  });
}

async function put(key, file, contentType) {
  if (DRY) return;
  for (let attempt = 1; ; attempt++) {
    const r = await wrangler(["r2", "object", "put", `${BUCKET}/${key}`, "--file", file, "--content-type", contentType]);
    if (r.code === 0) return;
    if (attempt === 3) throw new Error(`Upload of ${key} failed:\n${r.out.trim().split("\n").slice(-6).join("\n")}`);
    await new Promise((res) => setTimeout(res, 1500 * attempt));
  }
}

async function remove(key) {
  if (DRY) return;
  await wrangler(["r2", "object", "delete", `${BUCKET}/${key}`]);
}

/** The library.json currently in the bucket, or an empty one. */
async function currentLibrary(tmp) {
  const file = path.join(tmp, "library.json");
  const r = await wrangler(["r2", "object", "get", `${BUCKET}/library.json`, "--file", file]);
  if (r.code !== 0) {
    if (/not exist|not found|404|10007/i.test(r.out)) return { version: 1, songs: [] };
    throw new Error(`Couldn't read library.json from the bucket:\n${r.out.trim().split("\n").slice(-8).join("\n")}`);
  }
  try {
    return JSON.parse(await readFile(file, "utf8"));
  } catch {
    return { version: 1, songs: [] };
  }
}

/** "Gaia 2!" and "gaia_2" are the same name. */
const norm = (name) => name.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, "");

/**
 * Puts the albums in `order` (oldest first) in that order on the artist page. The app sorts by
 * year tag, then by when a song was added, so the albums get added times a minute apart. Their year
 * tags are kept only if every one has a year and the years agree with the order; otherwise the years
 * would override the order, so they're cleared.
 */
function applyOrder(songs, order, now) {
  const matched = order.map(() => []);
  for (const s of songs) {
    const names = [s.album, ...s.path.split("/").slice(0, -1)].map(norm);
    const i = order.findIndex((o) => names.includes(norm(o)));
    if (i >= 0) matched[i].push(s);
  }
  const missing = order.filter((_, i) => matched[i].length === 0);
  if (missing.length) {
    const albums = [...new Set(songs.map((s) => s.album))].sort().join(", ");
    console.warn(`  --order: no songs found for ${missing.map((m) => `"${m}"`).join(", ")}. Albums in the folder: ${albums}`);
  }
  const all = matched.flat();
  if (!all.length) return;
  const years = matched.filter((m) => m.length).map((m) => Math.max(...m.map((s) => s.year || 0)));
  const yearsAgree = years.every((y, i) => y > 0 && (i === 0 || y >= years[i - 1]));
  if (!yearsAgree) console.log("  --order: the year tags are missing or out of order, so they're ignored for these albums.");
  const MINUTE = 60_000;
  // Stays put on later runs, so re-uploading doesn't shift the albums.
  const base = Math.min(now - order.length * MINUTE, ...all.map((s) => s.addedAt));
  matched.forEach((m, i) => {
    for (const s of m) {
      s.addedAt = base + i * MINUTE;
      if (!yearsAgree) s.year = 0;
    }
  });
  console.log(`  Album order (oldest first): ${order.filter((_, i) => matched[i].length).join(" → ")}`);
}

async function walk(dir, out = []) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    if (entry.name.startsWith(".")) continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) await walk(full, out);
    else if (AUDIO[path.extname(entry.name).toLowerCase()]) out.push(full);
  }
  return out;
}

function sha256File(file) {
  return new Promise((resolve, reject) => {
    const hash = createHash("sha256");
    createReadStream(file).on("data", (d) => hash.update(d)).on("end", () => resolve(hash.digest("hex"))).on("error", reject);
  });
}

/** A cover as a JPEG of at most 800 px: embedded art first, then cover.jpg and friends. */
async function coverFor(file, pictures) {
  let bytes = pictures?.[0]?.data ? Buffer.from(pictures[0].data) : null;
  if (!bytes) {
    const dir = path.dirname(file);
    const names = await readdir(dir);
    const match = COVER_NAMES.flatMap((n) => [".jpg", ".jpeg", ".png", ".webp"].map((e) => n + e))
      .map((want) => names.find((n) => n.toLowerCase() === want))
      .find(Boolean);
    if (match) bytes = await readFile(path.join(dir, match));
  }
  if (!bytes) return null;
  try {
    const sharp = (await import("sharp")).default;
    bytes = await sharp(bytes).rotate().resize(800, 800, { fit: "inside", withoutEnlargement: true }).jpeg({ quality: 85 }).toBuffer();
    return { bytes, type: "image/jpeg" };
  } catch {
    // sharp not available or image unreadable: upload the original bytes as they are.
    return { bytes, type: pictures?.[0]?.format ?? "image/jpeg" };
  }
}

async function pool(items, worker) {
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(CONCURRENCY, items.length) }, async () => {
      while (next < items.length) {
        const i = next++;
        await worker(items[i], i);
      }
    }),
  );
}

async function main() {
  const rootStat = await stat(ROOT).catch(() => null);
  if (!rootStat?.isDirectory()) {
    console.error(`Not a folder: ${ROOT}`);
    process.exit(1);
  }
  const tmp = await mkdtemp(path.join(tmpdir(), "vanity-upload-"));
  try {
    console.log(`Music folder: ${ROOT}${LOCAL ? "  (local simulator)" : ""}${DRY ? "  (dry run)" : ""}`);
    const files = (await walk(ROOT)).sort();
    console.log(`Found ${files.length} audio file${files.length === 1 ? "" : "s"}. Reading the cloud library...`);
    const library = await currentLibrary(tmp);
    const existing = new Map(library.songs.map((s) => [s.id, s]));
    const knownCovers = new Set(library.songs.map((s) => s.coverId).filter(Boolean));

    // Hashing and tag reading are cached by path + size + modified time.
    const cache = await readFile(CACHE_FILE, "utf8").then(JSON.parse).catch(() => ({}));
    const now = Date.now();
    const songs = [];
    const coverFiles = new Map(); // coverId -> temp file to upload
    let failed = 0;

    await pool(files, async (file) => {
      try {
        const st = await stat(file);
        const stamp = `${st.size}:${Math.round(st.mtimeMs)}`;
        const rel = path.relative(ROOT, file).split(path.sep).join("/");
        let entry = cache[file]?.stamp === stamp && cache[file].song.year !== undefined ? cache[file].song : null;
        if (!entry || (entry.coverId && !knownCovers.has(entry.coverId))) {
          const id = (await sha256File(file)).slice(0, 32);
          const meta = await parseFile(file, { duration: true });
          const c = meta.common;
          const cover = await coverFor(file, c.picture);
          let coverId = null;
          if (cover) {
            coverId = createHash("sha256").update(cover.bytes).digest("hex").slice(0, 32);
            if (!knownCovers.has(coverId) && !coverFiles.has(coverId)) {
              const coverPath = path.join(tmp, `${coverId}.img`);
              await writeFile(coverPath, cover.bytes);
              coverFiles.set(coverId, { path: coverPath, type: cover.type });
            }
          }
          const ext = path.extname(file).toLowerCase();
          entry = {
            id,
            title: c.title?.trim() || path.basename(file, ext),
            artist: c.artist?.trim() || c.albumartist?.trim() || "Unknown artist",
            albumArtist: c.albumartist?.trim() || "",
            album: c.album?.trim() || "Unknown album",
            track: c.track?.no ?? 0,
            disc: c.disk?.no ?? 1,
            durationMs: Math.round((meta.format.duration ?? 0) * 1000),
            size: st.size,
            mime: AUDIO[ext],
            ext: ext.slice(1),
            coverId,
            year: c.year ?? 0,
            path: rel,
          };
          cache[file] = { stamp, song: entry };
        }
        // Keep the original "added" date for songs that are already in the cloud.
        songs.push({ ...entry, path: rel, albumType: albumTypeFor(rel), addedAt: existing.get(entry.id)?.addedAt ?? now, file });
      } catch (e) {
        failed++;
        console.warn(`  Skipped (unreadable): ${file}\n    ${e.message}`);
      }
    });

    const order = ORDER_ARG !== undefined
      ? ORDER_ARG.split(",").map((a) => a.trim()).filter(Boolean)
      : (library.albumOrder ?? []);
    if (order.length) applyOrder(songs, order, now);

    // The same file twice in the folder is one song.
    const byId = new Map();
    for (const s of songs) if (!byId.has(s.id)) byId.set(s.id, s);
    const local = [...byId.values()];

    const toUpload = local.filter((s) => !existing.has(s.id));
    const localPaths = new Map(local.map((s) => [s.path, s.id]));
    // Old uploads replaced by a changed file at the same path, plus (with --prune) vanished files.
    const replaced = library.songs.filter((s) => !byId.has(s.id) && s.path && localPaths.has(s.path));
    const vanished = library.songs.filter((s) => !byId.has(s.id) && !(s.path && localPaths.has(s.path)));
    const toDelete = PRUNE ? [...replaced, ...vanished] : replaced;
    const kept = PRUNE ? [] : vanished;

    console.log(
      `To upload: ${toUpload.length} song${toUpload.length === 1 ? "" : "s"}, ${coverFiles.size} cover${coverFiles.size === 1 ? "" : "s"}.` +
        ` Already in the cloud: ${local.length - toUpload.length}.` +
        (toDelete.length ? ` Removing: ${toDelete.length}.` : ""),
    );

    let done = 0;
    await pool([...coverFiles.entries()], async ([coverId, c]) => put(`covers/${coverId}`, c.path, c.type));
    await pool(toUpload, async (s) => {
      await put(`audio/${s.id}`, s.file, s.mime);
      done++;
      console.log(`  [${done}/${toUpload.length}] ${s.artist} - ${s.title}`);
    });

    const finalSongs = [...local, ...kept]
      .map(({ file, ...s }) => s)
      .sort((a, b) => (a.albumArtist || a.artist).localeCompare(b.albumArtist || b.artist) || a.album.localeCompare(b.album) || a.disc - b.disc || a.track - b.track || a.title.localeCompare(b.title));
    const out = path.join(tmp, "library.out.json");
    await writeFile(out, JSON.stringify({ version: 1, updatedAt: new Date().toISOString(), albumOrder: order, songs: finalSongs }));
    await put("library.json", out, "application/json");

    // Delete old objects only after the new library is live. Covers still in use are kept.
    const coversInUse = new Set(finalSongs.map((s) => s.coverId).filter(Boolean));
    for (const s of toDelete) {
      await remove(`audio/${s.id}`);
      if (s.coverId && !coversInUse.has(s.coverId)) await remove(`covers/${s.coverId}`);
    }

    if (!DRY) await writeFile(CACHE_FILE, JSON.stringify(cache));
    const bytes = finalSongs.reduce((n, s) => n + (s.size ?? 0), 0);
    console.log(
      `${DRY ? "Dry run finished" : "Done"}. The cloud library has ${finalSongs.length} song${finalSongs.length === 1 ? "" : "s"} (${(bytes / 1e9).toFixed(2)} GB).` +
        (failed ? ` ${failed} file${failed === 1 ? "" : "s"} skipped.` : ""),
    );
  } finally {
    await rm(tmp, { recursive: true, force: true });
  }
}

main().catch((e) => {
  console.error(`\nUpload failed: ${e.message}`);
  process.exit(1);
});
