#!/usr/bin/env node
/**
 * Uploads a folder of music to the R2 bucket and rewrites library.json.
 *
 *   npm run upload -- "<path to music folder>" [--prune] [--local] [--dry-run]
 *
 * - Each song's id is the SHA-256 of its file, so re-running only uploads new or changed files.
 * - A changed file (same path, new contents) replaces its old upload.
 * - --prune    also removes songs from the cloud whose files are no longer in the folder.
 * - --local    writes to Wrangler's local R2 simulator (for `npm run dev`), not Cloudflare.
 * - --dry-run  shows what would happen without uploading anything.
 * - --type=released|unreleased|ep|single|custom  files everything in this folder in that
 *                artist-page row, whatever the folders are called.
 * - --order="oldest album, next album, ..., newest album"  sets the order of those albums on the
 *                artist page (matched by album name or folder name, ignoring case).
 * - --artist="Name"  the artist for files whose tags don't name one.
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
const flags = new Set(args.filter((a) => a.startsWith("--")).map((a) => a.split("=")[0]));
const option = (name) => args.find((a) => a.startsWith(`--${name}=`))?.slice(name.length + 3).trim() || null;
const TYPE = option("type")?.toLowerCase() ?? null;
const ORDER = (option("order") ?? "").split(",").map((a) => a.trim().toLowerCase()).filter(Boolean);
const ARTIST = option("artist");
if (TYPE && !["released", "unreleased", "ep", "single", "custom"].includes(TYPE)) {
  console.error(`Unknown --type "${TYPE}". Use released, unreleased, ep, single or custom.`);
  process.exit(1);
}
const folderArg = args.find((a) => !a.startsWith("--"));
const LOCAL = flags.has("--local");
const DRY = flags.has("--dry-run");
const PRUNE = flags.has("--prune");

if (!folderArg) {
  console.error('Usage: npm run upload -- "<path to music folder>" [--prune] [--local] [--dry-run]');
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

/**
 * --order: the app shows an artist's albums newest first, by year and then by date added. Make
 * both follow the given order (oldest first), keeping the years from the tags where they agree.
 */
function applyOrder(songs, now) {
  if (!ORDER.length) return;
  const position = (s) => {
    const names = [s.album, ...s.path.split("/").slice(0, -1)].map((n) => n.trim().toLowerCase());
    return ORDER.findIndex((name) => names.includes(name));
  };
  const groups = ORDER.map(() => []);
  for (const s of songs) {
    const i = position(s);
    if (i >= 0) groups[i].push(s);
  }
  const years = groups.map((g) => Math.max(0, ...g.map((s) => s.year || 0)));
  for (let i = years.length - 2; i >= 0; i--) if (!years[i]) years[i] = years[i + 1]; // unknown: as the next one
  for (let i = 0; i < years.length; i++) {
    if (!years[i]) years[i] = i ? years[i - 1] : new Date(now).getFullYear();
    if (i && years[i] < years[i - 1]) years[i] = years[i - 1];
  }
  groups.forEach((g, i) => {
    if (!g.length) console.warn(`  --order: no songs found for "${ORDER[i]}"`);
    for (const s of g) {
      s.year = years[i];
      s.addedAt = now - (ORDER.length - i) * 60_000;
    }
  });
  console.log(`Order (oldest first): ${ORDER.map((name, i) => `${name} [${groups[i].length} songs, ${years[i]}]`).join(", ")}`);
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
        const song = { ...entry, path: rel, albumType: TYPE ?? albumTypeFor(rel), addedAt: existing.get(entry.id)?.addedAt ?? now, file };
        if (ARTIST && song.artist === "Unknown artist") song.artist = ARTIST;
        songs.push(song);
      } catch (e) {
        failed++;
        console.warn(`  Skipped (unreadable): ${file}\n    ${e.message}`);
      }
    });

    applyOrder(songs, now);

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
    await writeFile(out, JSON.stringify({ version: 1, updatedAt: new Date().toISOString(), songs: finalSongs }));
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
