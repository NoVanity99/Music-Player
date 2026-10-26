# Vanity Player

A local music player for **Android** and **Windows**, built from one Kotlin Multiplatform codebase:
Compose Multiplatform (Material 3) UI, Room database, Media3 playback on Android, FFmpeg (via
JavaCV) playback on desktop. No ads and no analytics. The only network access is checking GitHub
for updates and, with Discord Rich Presence on, looking up album covers on the iTunes and Deezer
catalogues (Discord can only show images from the web).

## Features

- Streaming-app style UI: Home, Search and Library, with pages tinted by the album art. Phones get
  bottom tabs and a mini player; wide windows (840 dp and up) get a sidebar, a player bar and a
  queue/lyrics side panel (1180 dp and up)
- You pick the music folders yourself (nothing is auto-detected), can ignore subfolders, and the
  folders are checked for new songs every time the app starts
- Playlists, Liked Songs, Most Played, history, and sorting by title, artist, album, date added,
  duration or play count, plus a "Liked only" filter
- Shuffle, repeat, an Up Next queue with drag-to-reorder, a sleep timer that fades out, playback
  speed, crossfade between songs, and synced lyrics from `.lrc` files next to each song
- Equalizer on Android (system effect with presets)
- Desktop: keyboard shortcuts (press `?` or F1 to list them), media keys that work even when the window
  isn't focused, right-click menus, hover states and scroll wheel support
- Discord Rich Presence on desktop ("Listening to" with song, artist, album cover and time left),
  with a toggle in Settings
- Self-updating from GitHub Releases, with signed and checksummed downloads (see [RELEASING.md](RELEASING.md))

## Project layout

```
shared/       Common code: data layer, Room, ViewModels, the whole Compose UI, updater logic
app/          Android app: Media3 playback service, MediaStore scanning, equalizer, APK updates
desktopApp/   Windows/desktop app: FFmpeg audio engine, file scanner, media keys, Discord, MSI updates
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
