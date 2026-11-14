# Vanity Player cloud

A Cloudflare Worker that serves your music from the R2 bucket `vanity-music` to the app.

| Endpoint | Returns |
|---|---|
| `GET /library` | The song list (`library.json`) |
| `GET /stream/:id` | A song's audio. Supports `Range`, so seeking and fast starts work |
| `GET /cover/:id` | Cover art |

Every request needs the access key in the `X-App-Key` header. The key is the Worker secret
`APP_KEY`. The app never holds R2 credentials. Audio and covers are stored under their content
hash, so they're served with long-lived cache headers and from Cloudflare's edge cache.

## One-time setup

```sh
cd cloud
npm install
npx wrangler login                      # opens a browser
npx wrangler r2 bucket create vanity-music
npx wrangler deploy                     # prints the Worker URL
npx wrangler secret put APP_KEY         # choose a long random key; the app asks for the same one
```

Then put the Worker URL and the key into the app: Settings → Cloud library.

## Accounts (Neon)

`/auth/signup`, `/auth/login`, `/auth/logout`, `/me/sync`, `/me/ops`, `/me/playlists` and
`DELETE /me` keep each account's likes, plays and playlists in Neon Postgres. Create a Neon
project, copy its connection string, and store it as a secret; the tables are created on first use:

```sh
npx wrangler secret put DATABASE_URL
```

## Uploading music

```sh
npm run upload -- "C:\Users\you\Music"
```

- Reads tags (title, artist, album, track, duration, embedded cover or `cover.jpg`).
- Each song's id is the SHA-256 of its file. Re-running uploads only new or changed files.
- Artist pages have four rows: custom albums, released albums, EPs and singles. A release goes
  in a row when it's inside a folder named `Released Albums` (or `Albums`), `EPs` or `Singles`,
  at any level; everything else is a custom album. Re-run the upload after moving folders.
- Add `--prune` to also remove songs from the cloud that are no longer in the folder.
- Add `--dry-run` to see what would happen.

## Developing and testing locally

```sh
echo "APP_KEY=dev-key" > .dev.vars
npm run upload -- "<folder>" --local    # fills the local R2 simulator
npm run dev                             # http://127.0.0.1:8787
npm test                                # endpoint checks; BASE=... KEY=... for a deployed Worker
```
