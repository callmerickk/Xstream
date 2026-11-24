# How the Feature switches work

One source of truth: the **Xstream repo** holds the shared code. StreamX is built from the very same code with
StreamX's brand values (its `app/build.gradle.kts`: name, update repo, `IS_BETA = false` …). Code flows
Xstream → StreamX, never the other way.

## The rule

`app/src/main/java/com/example/streamnest/Feature.kt`:

- Xstream (`IS_BETA = true`): every switch is **on**.
- StreamX (`IS_BETA = false`): a switch is on **only** if its name is in `Feature.STABLE`.
- A switch that is off leaves StreamX's own code path exactly as it was: no network call, no screen, nothing saved.

## Add a switch

1. In `Feature.kt`: `val mySwitch: Boolean = on("mySwitch")` with a one-line comment (what it does).
2. Check it where the feature starts: `if (Feature.mySwitch) …`. Keep the old line as the `else` when it changes an
   existing path, so "off" is the old code word for word.
3. Anything that needs the manifest (a launcher entry, a service) goes into a brand file, not `src/main`:
   Xstream's is `app/src/brand/phone/AndroidManifest.xml` (Music).
4. Build Xstream (check build), try it in Xstream.

## Promote a switch to StreamX

1. In the Xstream repo, add the name to `Feature.STABLE`, e.g. `setOf("pluginGuard")`. Commit.
2. Sync into the StreamX source tree: `tools/sync-to-streamx.sh /path/to/StreamX-tree`
   (copies `app/src/main`, `app/src/phone`, `app/src/tv`, `app/libs`, `proguard-rules.pro`; keeps StreamX's
   `build.gradle.kts`, `Changelog.kt` and everything else).
3. In the StreamX tree: bump `versionName` / `versionCode` in `app/build.gradle.kts`, add the Changelog entry and
   `RELEASE_NOTES.md`.
4. Zip: `tools/zip-streamx.sh /path/to/StreamX-tree /home/claude/streamnest/StreamX-<ver>-TV-<tv>.zip`, commit only
   the new zip, check build (`gh workflow run build.yml -R callmerickk/StreamNest --ref <branch> -f publish=false`),
   then publish.

The StreamNest workflow builds the zip added or changed by the **last commit** (`git log -1 … -- '*.zip'`), so each
release commit must add exactly one new zip.

Music is a special case: besides `"music"` in `STABLE`, StreamX would also need `HAS_MUSIC = true` and the Music
manifest entries in its own brand manifest.
