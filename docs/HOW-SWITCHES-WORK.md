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
   (copies `app/src/main`, `app/src/phone`, `app/src/tv`, `app/libs`, `proguard-rules.pro`, and since 0.9.2 the top
   `build.gradle.kts` (shared Firebase part) and `app/google-services.json`; keeps StreamX's
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

## Switches (0.9.3)

All on in Xstream, off in StreamX (`Feature.STABLE` = `updatePill` only):
`cloudFind`, `pluginGuard`, `smartParts`, `detailTiming`, `accessGate`, `linkMemory`, `music`, `tasteRows`, `tastePick`,
`hostDown`, `roomRejoin`, `inviteBoth`, `fileNameCheck` (0.9.2), and new in 0.9.3:

- `devRules` — Developer's Corner › Rules (Rules.kt). Off: every rule is its built-in default (the 0.9.2 behaviour) and
  the published copy (`xshared/devconfig/rules`) is not read. Promoting it to StreamX makes StreamX read the published
  rules at start too (both apps use the same path).
- `devAsk` — "Ask about this" boxes in Developer's Corner (DevAsk.kt, Worker `/ai`).
- `comboTest` — Source Test › Combined (ComboTest.kt).

## Switches new in 0.9.4 (step 5)

All on in Xstream, off in StreamX. Off = the 0.9.3 code path: nothing read, written or sent.

- `homeSteady` — Netflix-style Home (HomeSteady.kt): shown rows never change during the session (HomeFreeze appends new
  rows at the end; fresh rows are saved for the next open), the Top 10 order is fixed per session and shown at once from
  `filesDir/homesteady_<pid>.json`, tall posters are fetched small (TMDB w342) while the profile page shows, and each
  card shows its own colour instead of grey.
- `cloudPrep` (needs `cloudFind`) — the Worker's Cron prepares links (D1); `/find` answers carry `checked`, and Prepare
  uses a cloud check under 10 min old instead of its own one-byte request; `/good` after a real first picture (hashes
  only), `/cw` with Continue-watching ids (type + IMDb id).
- `lightPlugins` — plugin mode runs only Rules › Plugins on the phone (`lightPlugins`, default MovieBox + Netflix-type);
  the rest run once, quietly, after `quickWaitPluginsSec` if no light link is still usable (HeavyGate, PluginSources.kt).
- `hdrMode` — Quality › HDR finds an HDR copy and fades to it at the same frame (ottSwapTo: same length, same audio
  language); off fades back to the regular copy. The remembered choice starts SDR first, then fades (not on metered /
  slow internet unless clearly fast). Screens that can't show HDR: the row says so and is disabled.

Not switched (both apps): the `x-sx-ver` header on every Worker call — only a version number. The Worker's version /
approval gate does nothing until the owner sets `MIN_VER`; approval needs `access/config/required` = true and only apps
with `accessGate` on send the sign-in token (never the developer's account).
