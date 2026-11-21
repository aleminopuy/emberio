# Releasing Emberio

A release is three steps: a GitHub release (which builds the files), then the two appstores.
Everything the stores ask for is in [`store/`](store/), with the text in
[`store/listing.md`](store/listing.md).

## Once: the signing key

Android only installs an update over the old app if both are signed with the same key. Add the
four repository secrets (Settings → Secrets and variables → Actions → New repository secret):
`EMBERIO_KEYSTORE_BASE64`, `EMBERIO_KEYSTORE_PASSWORD`, `EMBERIO_KEY_ALIAS`,
`EMBERIO_KEY_PASSWORD`. Keep a backup of the key: without it, no future update can install over
the old app. Copies installed before the key was added need uninstalling once.

## 1. GitHub release

1. Bump the version: `version` in `watch/package.json`, and `versionCode` (+1) and `versionName`
   in `android/app/build.gradle.kts`. Change the minor number (1.1.0 → 1.2.0): the watch only
   stores major.minor, so a patch-only bump (1.1.1) looks like the same version to it.
2. Merge to `main`.
3. On GitHub: **Releases → Draft a new release**, type the version as a new tag (`1.2.0`),
   target `main`, add notes, **Publish release**.
4. A few minutes later the Build workflow attaches `emberio.apk` and `emberio.pbw`.

## 2. Pebble appstore (Core's Pebble app)

Works from a phone browser. Apps go live as soon as they're submitted.

**First time**: open the [Developer Dashboard](https://developer.repebble.com/dashboard), sign
in (always use the same method: Google, GitHub or Apple), and click **New**.

1. Upload `emberio.pbw` from the GitHub release. Check the version and add the release notes.
2. App name, description and category from `store/listing.md` (the "Pebble appstore"
   description).
3. Small icon `store/icon-80.png`, large icon `store/icon-144.png`.
4. Screenshots: for each platform, the five `store/screenshots/<platform>_*.png`. Banner:
   `store/banner.png` (the same one for each platform).
5. Website and source code: `https://github.com/aleminopuy/emberio`. Leave the companion app
   empty (it wants a Google Play link).
6. Visibility **Listed**, then **Submit App**.

**Updates**: open the app in the dashboard, **New Release**, upload the new `emberio.pbw`, add
release notes, **Create Release**.

From a computer you can do the same with the `pebble` tool, from `watch/`:
`pebble login`, then `pebble publish --release-notes "..."` and choose *Select local
screenshot/GIF files* (the files in `store/screenshots/` are named the way it expects).

Leave **Sync** (in the dashboard's Rebble import) off for Emberio: it would make the Rebble
listing the source of truth and overwrite this one.

## 3. Rebble appstore

1. Open the [Rebble Developer Portal](https://dev-portal.rebble.io) and sign in with your
   Rebble account.
2. Submit a new app: upload `emberio.pbw`, the release notes (required the first time), name,
   the "Rebble appstore" description from `store/listing.md`, category, icons (small
   `store/icon-48.png`, large `store/icon-144.png`), banner, and the screenshots for every platform
   (required for apps).
3. **Updates**: add a new release with the new `emberio.pbw`. Its version must be higher than
   the last one.

## Taking new screenshots

With the Pebble SDK installed: `watch/tools/store-screenshots.sh` (about 10 minutes; it runs
every emulator), then `python3 store/make_art.py` to redraw the icons and the banner.
