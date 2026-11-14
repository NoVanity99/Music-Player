# Plan: cloud streaming and offline downloads

## What the project is today

- **Framework:** Kotlin Multiplatform with Compose Multiplatform. `shared/` holds the data layer,
  ViewModels and all UI; `app/` is the Android app; `desktopApp/` is the Windows/desktop app;
  `updatecore/` is a small JVM library for HTTP downloads and signature checks used by both.
- **Playback:** Android uses Media3 (ExoPlayer inside a `MediaSessionService`, which provides the
  notification, lock-screen controls and background playback). Desktop uses an FFmpeg engine
  (JavaCV). Both sit behind the shared `PlayerController` interface.
- **Library:** `LibrarySource.scan()` returns `Song`s: MediaStore on Android, a folder walk with tag
  reading (cached in `library.json`) on desktop. `MusicRepository` filters them to the folders the
  user chose. Playlists, history and likes are in a Room database (`music.db`); settings are in a
  key-value store.

## Design

### Cloud (`/cloud`)
- Cloudflare Worker (TypeScript, Wrangler) bound to the R2 bucket `vanity-music`.
- R2 layout: `library.json`, `audio/<id>`, `covers/<coverId>`.
- `GET /library`, `GET /stream/:id` (Range, 206/416, Content-Type, Content-Length), `GET /cover/:id`.
- Every request needs the `X-App-Key` header, compared in constant time against the `APP_KEY`
  secret. CORS allowed. Audio and covers are immutable (ids are content hashes), so they get
  long-lived cache headers and go through the edge cache after the key check.
- `scripts/upload.mjs`: reads tags, ids = SHA-256 of the file, uploads only new files with
  `wrangler r2 object put`, rewrites `library.json`. `npm run upload -- "<folder>"`.

### App
- `Song.source`: `LOCAL | CLOUD | DOWNLOADED`, plus `cloudId`. Cloud songs get negative ids so they
  can't collide with local ones.
- `CloudRepository` (shared): server URL + key (stored in a platform secure store), fetches
  `/library`, caches it, exposes a status (not set up / loading / online / offline / bad key).
- `MusicRepository` merges local + cloud songs; local scanning is untouched.
- **Android streaming:** ExoPlayer with an HTTP data source that adds the key header, wrapped in a
  disk cache (`SimpleCache`). Same service and session, so notification, lock screen and background
  playback keep working.
- **Desktop streaming:** FFmpeg opens the HTTPS URL with the key header.
- **Downloads:** Room table `downloads` (id, file path, size, date, status, song metadata). A shared
  queue processor downloads into the app's private storage. Android runs it in a WorkManager
  foreground job (progress notification, cancel, survives the app closing); desktop runs it in the
  app. A downloaded song's `uri` is the local file, so play always prefers it.
- **Offline:** if the server can't be reached, the library shows local + downloaded songs only.
- **UI:** Cloud section in Settings (URL, key, test, storage used), status icon per song, download
  buttons (song / album / all), "Downloaded" filter, delete download.

## Steps
0. This plan.
1. Worker + local tests (wrangler dev).
2. Upload script.
3. Streaming in the app.
4. Downloads.
5. UI polish.
6. Build, test, deploy (needs Cloudflare access from the owner).
