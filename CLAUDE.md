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

## This project is separate from the Vanity launcher

Vanity Player (this repository) and the Vanity launcher (`NoVanity99/Vanity`) are separate
projects. Work here must never change anything in the launcher's repository, even when both are
attached to the session and a request sounds like it could be for either. Requests about
"the launcher", scanning launchers, Trending or Most hyped belong to the launcher's own sessions:
say so instead of acting on them.
