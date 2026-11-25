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

## Switch new in 0.9.5 (step 6)

On in Xstream, off in StreamX. Off = the 0.9.4 phone layout everywhere and the 0.9.4 buffers.

- `bigScreen` — big-screen mode (BigScreen.kt). The phone app shows the TV interface (`TvApp`: TV Home, side bar, TV
  settings, TV title page, TV search; the player stays the phone player, which already takes keys, mouse and touch) when:
  landscape and smallest width ≥ 600 dp, or no touchscreen with a hardware keyboard / mouse, or a window ≥ 840 dp wide
  **and** ≥ 480 dp tall (the height guard keeps phones turned sideways on the phone layout). Checked at start and on
  every display change; while a video plays the change waits until the player closes. Esc = Back. TV components get
  hover highlight and clicks, rows scroll with Shift + wheel or a mouse drag. `DeviceTier` (same file) logs the device
  tier once; "top" (laptop / PC emulator such as MuMu, or a big tablet) gets up to 400 MB / 120 s of video buffer.
  Everything added inside the TV files is under `!BuildConfig.IS_TV`, so the TV app is unchanged.

## The slim build (0.9.6, step 7)

Not named switches: `Feature.noVlc`, `noVosk`, `mlkitUnbundled` and `webrtcOnDemand` all follow
`BuildConfig.SLIM_LIBS`. That is `brandBeta && phone flavor` (Xstream phone only; the TV flavor and StreamX set it to
`false`). The same value leaves the native libraries out of the APK (`androidComponents { onVariants(phone) }` in
`app/build.gradle.kts`), so a switch can never be on in an APK that still needs its library, or off in one that lacks it.
They are **not** in `Feature.STABLE` and must never be: **to promote the slim build to StreamX, flip `SLIM_LIBS` (and
the `androidComponents` excludes, the face-detection dependency, `APK_PHONE_ARM32`, the brand manifest's `<queries>` /
ML Kit meta-data and `src/brand/phone/java/FaceModelGms.kt`) in StreamX's brand file `app/build.gradle.kts`**, not
`Feature.STABLE`. StreamX's brand file must define `SLIM_LIBS` (false) and `APK_PHONE_ARM32` ("") or the shared code
won't compile; `tools/sync-to-streamx.sh` checks this.

Off (StreamX, TV) = the 0.9.5 code path word for word. On (Xstream phone):

- `noVlc` — ExoPlayer with the phone's decoders → ExoPlayer with FFmpeg video first (a new player,
  `EXTENSION_RENDERER_MODE_PREFER`) → the next link; the VLC app (if installed) only after "nothing played", only
  header-free links, never 127.0.0.1. Settings › Player engine hidden; a stored "vlc" is ignored, never rewritten.
- `noVosk` — "Hey Aiden" uses Android's on-device recogniser (WakeWordSr.kt; the row itself is still off by `WakeWordOff`).
- `mlkitUnbundled` — face model from Google Play services (play-services-mlkit-face-detection). Play-services classes are
  named only in `app/src/brand/phone/java/…/FaceModelGms.kt` (Xstream phone source folder, never synced).
- `webrtcOnDemand` — `libjingle_peerconnection_so.so` downloaded on the first call from the `assets` branch
  (`webrtc/<abi>/`), SHA-256 checked against `WebRtcLib.SHA`. The workflow (`tools/webrtc-assets.sh`) extracts the files
  from the AAR, pushes them to `assets` when missing and fails when `WebRtcLib.SHA` differs. Bump `LIB_VERSION` + SHA
  together with the `stream-webrtc-android` version.

Two phone APKs with one versionCode: `Xstream-phone.apk` (arm64-v8a) and `Xstream-phone-arm32.apk` (armeabi-v7a,
`gradle assemblePhoneDebug -PphoneAbi=arm32`); the updater picks the 32-bit one when the phone has no 64-bit ABI.

## Switches new in 0.9.7 / 0.9.8 (one build: 0.9.8, versionCode 68)

All on in Xstream, off in StreamX. Off = the 0.9.6 code path.

- `dataDiet` — DataDiet.kt: phone buffer 20–40 s / 60 MB, data line per play, no seek-thumb prefetch, Quality-menu files
  only after the sheet opens, gated ladder, BgSaveGuard on the Booster, MovieBoxCache (30 min).
- `addonFirst` (needs `cloudFind`) — Quick Play default `OTT_ANY` + `CNC_ANY` (only when a CNCVerse addon is on),
  quality lane (QualityLane.kt), backup tie-break, PageRelay 24 h memory.
- `smartFinish` — SmartFinish.kt + one-time Continue-watching pass.
- `smartCache` — CacheKeeper (WorkManager), AppStore keep folder, KeepPosters, PicksStore.
- `glassPlus` — HeroTint, GlassSheet, GlassUpdate, GlassHealth (backdrop health log lines).
- `fastManual` — ManualFast (head saved while the player opens, title-page Sources).
- `wt2` — Watch together 2 (Call.kt, Together.kt, TogetherUi.kt; title-page rooms; info card off in a room).

Not a new switch: Music logs (MusicLog.kt, Settings › Account › Music logs, cloud `…/music/days/<day>`) follow `music`.
MainActivity `singleTask` lives in Xstream's brand manifest only.

## 0.9.9 (versionCode 69): fixes, no new switch

- `webrtcOnDemand` is now **off everywhere** (`Feature.webrtcOnDemand = false`): loading the downloaded WebRTC library
  crashed in native code. `libjingle_peerconnection_so.so` is in the phone APK again (no jniLibs exclude; the workflow now
  checks it IS there); a copy downloaded by 0.9.6–0.9.8 is deleted at start (`WebRtcLib.forgetDownloaded`). WebRtcLib.kt
  and `tools/webrtc-assets.sh` stay for a later try (the workflow step is `continue-on-error`).
- Watch together (bug fix, both apps): no automatic copy switch in a room (switchTo / ottSwapTo / ladder / quality lane /
  HDR remembered); the room state carries `lang` (host's audio language) and guests select it.
- `wt2`: "You" bubble off by default (prefs key `selfView2`), 👤 chip on the first friend's bubble; chat hides the controls.
- `addonFirst`: quality lane never 4K (unless Quality › 4K) and never HDR (unless HDR mode); CNCVerse Rocky copies are
  labelled VegaMovies / RogMovies; one merged pipeline: no Turbo / Classic (pluginMode() true, no first-start chooser).
- `glassPlus`: the saved backdrop is decoded in MainActivity.onCreate (GlassBg.preload) on every start.
- Bug fixes (both apps): PageRelay relays numbered VegaMovies / RogMovies mirrors and, for those sites, timeouts / aborts /
  DNS failures; plugin search retries a non-Latin title once with TMDB's English name.
- Code names (both apps, owner rule): every source name — plugins, providers and addons — is a code name except on the
  developer's account (`Public.codeName`, `addonLabel`); new sources get a random star name (AutoCodes) and the developer
  confirms them in Developer's Corner (NewSourcesDialog → codeNames → publish).
