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
