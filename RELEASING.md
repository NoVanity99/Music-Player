# Releasing a new version

Both apps update themselves from **GitHub Releases** on `NoVanity99/Music-Player`. On launch (and
from *Settings → Updates → Check for updates*) they download:

```
https://github.com/NoVanity99/Music-Player/releases/latest/download/latest.json
https://github.com/NoVanity99/Music-Player/releases/latest/download/latest.json.sig
```

`latest.json` says which version is newest and where its files are; `latest.json.sig` is an
Ed25519 signature over it. The apps refuse a manifest whose signature doesn't match the public key
built into them, and refuse a download whose SHA-256 doesn't match the manifest. Android also
refuses an APK with a different package name, a lower version code, or a different signing
certificate.

The `Release` workflow (`.github/workflows/release.yml`) does all of this for you.

## Steps

1. **Bump the version** in `gradle.properties` (the only place it lives):
   ```properties
   app.versionName=1.4.0   # shown to users, compared by the desktop app
   app.versionCode=5       # Android: must be higher than every previous release
   ```
   Use `MAJOR.MINOR.PATCH`. Always raise `versionCode` by at least 1.
2. **Write the release notes** in `release-notes/<versionName>.md`, e.g. `release-notes/1.4.0.md`.
   This Markdown is what users see in the "Update available" dialog before they install.
3. Commit, then **tag and push**:
   ```sh
   git commit -am "Release 1.4.0"
   git tag v1.4.0
   git push origin main v1.4.0
   ```
   The tag must be `v` + `app.versionName`, or the workflow stops. (Or skip the tag: run the
   **Release** workflow from the Actions tab on your branch, and it creates the tag itself.)
4. Wait for the **Release** workflow to finish (about 15 minutes). It builds and publishes
   release `v1.4.0` with:

   | File | What it is |
   |---|---|
   | `MusicPlayer-1.4.0.apk` | Signed Android app (sideload, and what Android updates install) |
   | `MusicPlayer-1.4.0-Setup.exe` | Windows installer for first-time installs |
   | `MusicPlayer-1.4.0.msi` | Windows installer that desktop updates install silently |
   | `latest.json` | Update manifest (version, notes, URLs, SHA-256, sizes) |
   | `latest.json.sig` | Ed25519 signature of `latest.json` (base64) |
   | `SHA256SUMS.txt` | Checksums of everything, for manual checks |

   The release is marked **Latest**, so the `/releases/latest/download/` URLs above now point at
   it, and every installed app will offer the update on its next launch.

Don't rename these files or publish a release by hand without `latest.json` and its signature.
Pre-releases and drafts are ignored by the apps (GitHub's "latest" skips them), so you can use
those for testing.

## One-time setup: repository secrets

*Settings → Secrets and variables → Actions → New repository secret*:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 keystore/musicplayer-release.jks` |
| `KEYSTORE_PASSWORD` | The store/key password from `keystore.properties` |
| `UPDATE_SIGNING_KEY` | The full contents of `keystore/update-signing-key.pem`, including the `BEGIN`/`END` lines |

**Back up both keys somewhere safe.** If you lose the Android keystore, installed phones can't
update; they'd have to uninstall (losing playlists) and reinstall. If you lose the update signing
key, you'd have to ship a new public key in an app update, which no installed copy could verify.
Never commit either one; `/keystore/` is gitignored.

The public half of the update key is `keystore/update-public-key.b64` and is compiled into the app
as `UpdateConfig.PUBLIC_KEY` (`shared/.../update/Updates.kt`).

## Building locally

```sh
# Android (signed if keystore.properties exists)
./gradlew :app:assembleRelease          # app/build/outputs/apk/release/app-release.apk

# Windows: must run on Windows (jpackage can't cross-build); needs JDK 17+
gradlew.bat :desktopApp:packageReleaseExe :desktopApp:packageReleaseMsi
# desktopApp/build/compose/binaries/main-release/{exe,msi}/

# Run the desktop app without packaging (any OS)
./gradlew :desktopApp:run
```

To sign a manifest by hand: `openssl pkeyutl -sign -inkey keystore/update-signing-key.pem -rawin -in latest.json | base64 -w0 > latest.json.sig`

## Notes

- The Windows installer isn't code-signed, so SmartScreen shows "Windows protected your PC" on
  first install (*More info → Run anyway*). Updates installed by the app don't show this prompt.
  To remove it, buy a code-signing certificate and add a `signtool` step to the Windows job.
- Never change `upgradeUuid` in `desktopApp/build.gradle.kts`. It's what makes a new MSI replace
  the installed version instead of installing a second copy.
- On Android, the first update asks the user to allow "Install unknown apps" for Music Player.
  The app sends them to that setting and picks the update back up when they return.
