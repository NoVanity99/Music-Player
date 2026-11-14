# Notes for Claude

## Always publish a release after a change

The owner wants every finished change shipped without being asked. After committing and pushing a
user-facing change (and running the tests):

1. Bump the patch version in `gradle.properties`: `app.versionName` (e.g. 1.3.1 → 1.3.2) and
   `app.versionCode` (+1).
2. Add `release-notes/<versionName>.md` with a short, user-facing "What's new" list.
3. Commit and push, then run the **Release** workflow (`release.yml`, `workflow_dispatch`) on the
   current branch. Pushing tags isn't possible from cloud sessions; the workflow creates the tag.
4. Check the run succeeded and the release has the APK, Setup.exe, MSI, `latest.json` and
   `latest.json.sig`, then give the owner the release link.

See RELEASING.md for the details.

## Handoff: what a new session needs to know

Read this instead of exploring. README.md (features, layout), RELEASING.md (release steps) and
cloud/README.md (Worker, upload script) have the rest; open them only when the task touches them.

**State:** 1.8.0 is released. Work happens on the branch the session is given; no pull requests
unless asked. Nothing has been tried on a real phone or Windows PC yet, only automated tests.

**Standing rules from the owner**
- Ship every finished user-facing change (section above). Docs-only changes need no release.
- The app is cloud-only: it plays the owner's library from the Cloudflare Worker, with the server
  address and access key built in (`gradle.properties`; the owner accepted that risk). Never put
  R2 keys in the app. No local file scanning.
- Sign-in is required; likes, playlists and history are per account (Neon Postgres via the Worker).
- Never show the Discord app ID in the UI. Never look up album covers online.
- Artist page rows, top to bottom: Custom albums, Unreleased songs, Released albums, EPs, Singles;
  newest on the left; empty rows say so. No Songs list, no MusicBrainz.

**Where things are**
- All UI and data code: `shared/src/commonMain/kotlin/com/novanity/musicplayer/` (`ui/screens`,
  `ui/components`, `ui/theme`, `data`, `update`). Platform code: `app/` (Android), `desktopApp/`.
- Online installers: `installer/` (Android) and `installer-windows/Installer.cs` (C# 5, built in CI).
- Worker: `cloud/src/index.ts`, `cloud/src/accounts.ts`. Upload: `cloud/scripts/upload.mjs`
  (the owner runs it on their PC; cloud sessions can't reach their music folder).

**Testing**
- `./gradlew :updatecore:test :desktopApp:test` then `./gradlew :app:testDebugUnitTest` as a
  separate run: together they overload the machine and `ScreenshotTest` times out.
- Then `./gradlew :app:assembleRelease :installer:assembleRelease`.
- Live tests against the real Worker are opt-in (`CLOUD_TEST_URL`, `CLOUD_TEST_KEY`); delete any
  test accounts they create.

**Known gaps (told to the owner):** no password reset, open sign-up without rate limiting,
playlists sync is last-write-wins, the Windows installer trusts HTTPS rather than the signature,
installers are not code-signed (SmartScreen / "unknown apps" prompts).
