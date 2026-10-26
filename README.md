# Music Player

A local-only Android music player: Kotlin, Jetpack Compose (Material 3), Media3 (ExoPlayer +
MediaSessionService) and Room. No ads, no analytics, no internet permission.

## Features

- Scans MediaStore for all audio (skips ringtones, notifications, alarms and files under 30 s)
- Tabs for Songs, Albums, Artists, Playlists and Folders, sorting by title / artist / date added, and a
  search bar that filters every tab
- Play/pause, next/previous, seek, shuffle, repeat (off/all/one), and an Up Next queue with
  drag-to-reorder and swipe-to-remove
- Background playback with a media notification, lock-screen and Bluetooth/headset controls
- Audio focus: pauses for calls, ducks for notifications, pauses when headphones are unplugged
- Playlists (create, rename, delete, add/remove, reorder) and play history in Room
- Remembers the last queue, song and position
- Dark theme by default, light/system options, dynamic color on Android 12+
- Unplayable files are skipped with a toast

## Project layout

```
app/src/main/java/com/novanity/musicplayer/
  MusicApp.kt              Application + manual DI container
  MainActivity.kt          Single activity
  data/                    MediaStore scanner, repositories, Room database, settings
  playback/                PlaybackService (MediaSessionService), queue persistence
  ui/                      ViewModels, theme, Compose screens and components
```

## Building

Requirements: JDK 17+ and the Android SDK (platform 36). Point `local.properties` at the SDK
(`sdk.dir=/path/to/Android/sdk`) or set `ANDROID_HOME`.

```
./gradlew assembleRelease
```

### Release signing

The release build is signed when `keystore.properties` exists in the project root:

```
storeFile=keystore/musicplayer-release.jks
storePassword=...
keyAlias=musicplayer
keyPassword=...
```

Both `keystore.properties` and `keystore/` are gitignored. **Back them up.** Android only installs an
update over an existing install if it is signed with the same key.

Without `keystore.properties`, `assembleRelease` produces an unsigned APK, which Android won't install.

### CI

`.github/workflows/build.yml` builds the release APK on every push. To have CI sign it with your
key, add two repository secrets: `KEYSTORE_BASE64` (output of `base64 -w0 keystore/musicplayer-release.jks`)
and `KEYSTORE_PASSWORD`.
