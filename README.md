# Vanity Player

A music player for your own cloud library, for **Android** and **Windows**, built from one Kotlin Multiplatform codebase:
Compose Multiplatform (Material 3) UI, Room database, Media3 playback on Android, FFmpeg (via
JavaCV) playback on desktop. No ads and no analytics. The only network access is checking GitHub
for updates and, with Discord Rich Presence on, uploading the playing song's own cover art (from the
file's tags or folder) to Litterbox, a temporary image host that deletes it after 72 hours, because
Discord can only show images from the web. Artist pages look up a short bio on English Wikipedia by artist name,
and that artist's studio albums on MusicBrainz (both cached, and can be turned off in Settings).

## Features

- Streaming-app style UI: Home, Search and Library, with pages tinted by the album art. Phones get
  bottom tabs and a mini player; wide windows (840 dp and up) get a sidebar, a player bar and a
  queue/lyrics side panel (1180 dp and up)
- Plays your own cloud library only: songs are streamed from your server and can be downloaded
  for offline play. The app doesn't scan the device for music and needs no storage permission
- Playlists, Liked Songs, Most Played, history, and sorting by title, artist, album, date added,
  duration or play count, plus a "Liked only" filter
- Collaborations: credits like "Drake & Future" or "A feat. B" link to each artist separately (or
  say the artist isn't in your library); band names like "Mumford & Sons" stay whole. Artist pages
  show their songs, the songs they appear on, their discography, a short Wikipedia bio and their real
  studio albums (from MusicBrainz, with the ones you own marked)
- Your stats: time listened, plays, streak, a listening personality with an hour-of-day chart, and
  top artists, songs and albums for 7 days, 30 days or all time
- Made for you: daily mixes (Forgotten favorites, Deep cuts, Time machine, Never played)
- Desktop: a spectrum visualizer in the full-screen player and an always-on-top mini player (Ctrl+M)
- Shuffle, repeat, an Up Next queue with drag-to-reorder, a sleep timer that fades out, playback
  speed, crossfade between songs, and synced lyrics from `.lrc` files next to each song
- Equalizer on Android (system effect with presets)
- Desktop: keyboard shortcuts (press `?` or F1 to list them), media keys that work even when the window
  isn't focused, right-click menus, hover states and scroll wheel support
- Discord Rich Presence on desktop ("Listening to" with song, artist, the file's own cover art and time left),
  with a toggle in Settings
- Self-updating from GitHub Releases, with signed and checksummed downloads (see [RELEASING.md](RELEASING.md))

## Cloud library

Your own music can live in Cloudflare R2 behind a small Worker (`cloud/`). The app streams it,
shows it in the same library as local songs, and can download songs for offline play. See
[cloud/README.md](cloud/README.md) for setup and uploading. The Worker URL and access key are built
into the apps from `cloud.serverUrl` and `cloud.appKey` in `gradle.properties`, so users have nothing
to set up. That key is therefore public (it's in this repository and in every copy of the app); the
apps never hold R2 credentials. To change it: `npx wrangler secret put APP_KEY`, update
`gradle.properties`, and publish a release.

## Accounts

The app is used signed in. Each account has its own liked songs, playlists and play history (which
the stats and mixes are built from), stored in Neon Postgres and mirrored on the device; the song
library is shared. The app talks only to the Worker (`/auth/*`, `/me/*`), which alone holds the
database connection string (`npx wrangler secret put DATABASE_URL`). Passwords are stored as
salted PBKDF2 hashes and sessions as hashed tokens.

## Project layout

```
shared/       Common code: data layer, Room, ViewModels, the whole Compose UI, updater logic
app/          Android app: Media3 playback service, streaming, background downloads, equalizer, APK updates
desktopApp/   Windows/desktop app: FFmpeg audio engine, file scanner, media keys, Discord, MSI updates
cloud/        Cloudflare Worker (TypeScript) + upload script for the optional cloud library
updatecore/   Small JVM library for the downloads and Ed25519 signature checks (used by both apps)
branding/     App icon sources (SVG) and exports (PNG, ICO)
```

The version (`app.versionName`, `app.versionCode`) lives only in `gradle.properties`.

## Building

You need JDK 17 or newer. For Android you also need the Android SDK (platform 37): point
`local.properties` at it (`sdk.dir=/path/to/Android/sdk`) or set `ANDROID_HOME`.

| Target | Command | Output |
|---|---|---|
| Android APK | `./gradlew :app:assembleRelease` | `app/build/outputs/apk/release/app-release.apk` |
| Windows installers (run on Windows) | `gradlew.bat :desktopApp:packageReleaseExe :desktopApp:packageReleaseMsi` | `desktopApp/build/compose/binaries/main-release/{exe,msi}/` |
| Run the desktop app (any OS) | `./gradlew :desktopApp:run` | |
| Tests | `./gradlew :app:testDebugUnitTest :desktopApp:test :updatecore:test` | screenshots in `app/screenshots/`, `desktopApp/screenshots/` |

The Windows installers have to be built on Windows because jpackage can't cross-compile. The
`Build` workflow builds them on a Windows runner for every push, and the `Release` workflow publishes
them. See [RELEASING.md](RELEASING.md).

### Release signing (Android)

The release APK is signed when `keystore.properties` exists in the project root:

```
storeFile=keystore/musicplayer-release.jks
storePassword=...
keyAlias=musicplayer
keyPassword=...
```

`keystore.properties` and `keystore/` are gitignored. **Back them up.** Android only installs an
update over an existing install if it's signed with the same key. Without `keystore.properties`,
`assembleRelease` produces an unsigned APK, which Android won't install.

### Discord

The Discord application ID is set by `discord.clientId` in `gradle.properties` and passed to the
desktop app at build time. For the logo to show, upload an image named **`logo`** under the
application's *Rich Presence → Art Assets* in the Discord Developer Portal.
