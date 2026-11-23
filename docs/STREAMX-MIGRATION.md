# StreamX ← Xstream: migration plan and open decisions (22 Nov 2026)

Xstream (mobile) is the testing app, StreamX the stable one. Everything proven in Xstream moves to StreamX in batches,
one StreamX phone version each, with a check build every time. Motto stays: fastest start; nothing new runs before the
first picture. For every item moved, note here: Xstream file → StreamX file, and any difference.
Feature list with Xstream versions: `FEATURES-FOR-STREAMX.md`. This file = the plan + what logs taught us.

## What the logs showed (22 Nov)

### StreamX phone OOM crash (realme RMX3840, 0.9.67, 14:59:39)
- `java.lang.OutOfMemoryError … growth limit 402653184` (384 MB heap) 15 s after Jailer 2 started.
- Cause: `buildPlayer` budget from **device RAM** (12 GB → 300 MB buffer) inside a 384 MB heap, plus Coil memory 25 %.
  Exactly the Xstream "Odyssey" crash, fixed in Xstream 0.8.7 / 0.8.8 / 0.8.12. StreamX never got it.
- Also in that session: ~1,500 requests in 2.5 min (MovieBox ~800: `sniff/config` ×262, captions ×190; TMDB ~200),
  5 title pages each starting ~15 plugins, searches kept running after leaving the page, the activity log loaded whole.

### Samsung (SM-S931B, Android 17): the first-run tour can't be skipped
- Log: app reopened 5 times in 6 min, no crash; uploaded logs carry no taps.
- `Tour.kt` root `pointerInput` consumes every pointer change; the card's Skip/Next rely on getting the touch first.
  No other way out (Back does nothing). Xstream has the identical code → fix both.

### Green light: StreamX ~0.2–0.9 s, Xstream 1.6–4.3 s (median 2.2 s)
1. Xstream 0.8.15 "private DNS only" (`ChainDns`): a DoH lookup per new host before every first request; StreamX phone
   uses the phone's cached DNS.
2. Xstream's Continue look-ahead (Big Bang Theory S4E7) kept running when another title opened → Prime busy 6 s.
3. `DetailTiming` keeps counting after Play (Iron Man 3 "87.61 s" while it played at 4 s).

### VegaMovies
- Search works; every new title fails at **vcloud.fit** ("Connection reset" within ~1 s) — the Indian ISP block
  pattern (SNI reset; works on VPN). vegamovies.chat also timed out.
- StreamX keeps retrying (fails each time); it played VegaMovies only for Guardians Vol. 2 because the **final file link**
  (`…r2.cloudflarestorage.com/hub2/…`) was saved from 18 Nov → no vcloud.fit needed.
- Xstream marks vcloud.fit down 10 min after 3 resets (`HostDown`) → VegaMovies disappears from Sources.

## Agreed fixes (build later, both apps unless noted)

1. **Memory (StreamX 0.9.68 first)**: player budget from `Runtime.maxMemory()` (22 % playing / 10 % hidden, `PlayerCount`),
   Coil 12 % on phones + cleared when the player opens, heap log every 60 s and >85 % → drop pictures/frames, "memory full"
   prompt, `room()` rule (one live player, heap < 70 %) for swaps/preload, `onTrimMemory(UI_HIDDEN)`, `cappedString` 16 MB
   and `capPage` 5 MB on phones (StreamX has them on TV only), look-ahead only for Continue (one at a time),
   warm-up pauses while a title searches.
2. **Plugin searches**: stop a title's searches when its page is left (unless it plays); one MovieBox search per title
   shared by its 4 plugins; keep MovieBox's config instead of asking again; at most 4–5 plugins at a time.
3. **Activity log**: load the latest part, more on scroll, freed when leaving; memory line every 30 s + on screen change.
4. **Tour**: no whole-screen consume (only the dim blocks the app); Skip/Next ≥ 48 dp; Back = Skip; ✕ in the card;
   TOUR lines (shown / next / skip / back) logged as important.
5. **DNS (instead of copying Xstream's "private only")**: phone DNS first, private DNS when the phone's fails or is blocked
   (aoneroom), answer remembered per host.
6. **Look-ahead** cancelled when another title opens.
7. **HostDown**: a "down" host is retried once per new title (not blocked 10 min); with the relay below it goes through
   the relay instead of hiding the source.
8. **DetailTiming** stops at Play.
9. **Blocked pages relay** (VegaMovies): Worker `streamx-dl.callmerick093.workers.dev` gets a `/p?u=` route with an
   ALLOWLIST (vcloud.fit, hubcloud.*, vegamovies.*, rogmovies.*); the app retries a connection-reset page there. Only
   the small pages go through it; the video stays direct (r2.cloudflarestorage). New Worker code to paste when built.
10. **Final file links kept for days** (while they still answer), so a title watched once plays without the blocked step.

## Migration batches (StreamX phone)
1. 0.9.68 stability: fixes 1–4, 5, 9, 10 + Cloudflare updater (already in shared code).
2. 0.9.69 start/play: LinkMemory, never back to loading (`sawPicture`), `keepTrying`, fast fallbacks, fake-video check,
   links as found on phones (`onLink`), late links, Watch next corner (`wnTries`), resume same source (`PlayingSrc`).
3. 0.9.70 player: quality sheet + chip, red seek dot + `scrubHide`, top bar ‹, `edgeStart`, post-credit pill on phones,
   Pilot/Finale tags, cinema-copy dialog, trailer in card.
4. 0.9.71 search: `deaccent`, `isAddonError`, `realTitles` + universe, per-profile search, saved episode lists,
   `noSourceName`, `noteMeta` order.
5. 0.9.72 looks: Home styles + pickers + FirstLook, Horizon profile page, taste titles, Watched + `sweepFinished`, `warmHome`.
6. 0.9.73 extras: VR, Log browser, AIDEN `quickCommand`, update nudge (`onTrouble`), public downloads.

## Under discussion (22 Nov) — not decided
- **Sources in layers**: Quick Play = chosen plugins + addons race together, first good link plays. Plugins search by
  title (TMDB facts used to check); Stremio addons ask by IMDb id; Nuvio providers by TMDB id. A title the plugins
  can't match by name is still found by id. Only when both find nothing → Live server.
- **Live server on demand**: today it starts a few seconds after the app opens and keeps running. Plan: start only when
  layers 1–2 found nothing (or pre-start once a title page has had nothing for ~3 s), keep it while the player is open,
  stop it when the player closes. It answers `/stream/<type>/<tt id>.json` (IMDb id in; inside it runs Nuvio-style
  providers, TMDB-based).
- **Live server for 32-bit phones**: it is Rust (PencariMovie Server, MIT) built only for arm64 by
  `tools/build-live-server.sh`. Add `cargo ndk -t armeabi-v7a` → `jniLibs/armeabi-v7a/libxlive.so`; check its memory
  on 2–3 GB phones; StreamX's 32-bit build already packages armeabi-v7a.
- **Cloudflare (free, no card) for calls**: Worker + Durable Objects = signaling (WebSocket) and the ringing server
  (sends FCM pushes with the key kept as a Worker secret — no key in the app). Media stays peer-to-peer WebRTC
  (Workers can't carry call audio/video); TURN for mobile data from Cloudflare Realtime TURN (check free quota / card)
  or Metered / ExpressTURN free tiers.

## Decisions (22 Nov, 16:45)
1. Every new, untested feature goes into **Xstream mobile first**; only after approval does it land in StreamX.
2. 32-bit Live server: build it in the next Xstream Live build; user tests on a realme 3 Pro (Snapdragon 710 — a 64-bit
   chip; whether its Android is 32- or 64-bit must be checked, e.g. Settings › About › or the app log's ABI line).
3. Calls: Xstream first. Before that, fix **Watch together in Xstream** (a friend says it doesn't work; StreamX played a
   whole film in a room fine). Code check: Together.kt / Call.kt / Social*.kt are the same in both apps (only names);
   differences are in Xstream's player (quality swaps, keepTrying, held frame, exact seeks) and both apps claiming the
   same invite link (`streamx-watch.pages.dev/join`, `streamx://join`) — the web page offers StreamX. Need a log from
   the friend's phone in a room.

## Load and limits (22 Nov)
- Firebase Realtime Database (Spark, free): **100 connections at the same time**. Every open app (Xstream, StreamX
  phone and TV — all share `streamx-d976a`) keeps one live sync stream (`Cloud.streamLoop`), Watch together a second.
  Ideas: live stream only while the app is in front and something needs it (else a quick read on open / on change);
  move live things (rooms, presence, chat, calls) to Cloudflare Durable Objects (no connection cap; 100k requests/day free).
  A 2nd Firebase account (split users by uid) is possible but rooms / friends / chat between the two halves break and
  sign-in is per project — not recommended.
- Kaggle / Colab / similar: notebooks, not servers (sessions end, no public address, terms) → only for batch jobs
  (e.g. nightly title lists, recommendations), not for live streaming work.

## Next Xstream mobile build — the list (23 Nov)
Promised earlier (reminders):
1. **Logs in one organised place**: in the app and in sent files, every log in one stack (app › version › device).
2. **Profile page "no lift"**: the selected profile no longer lifts up, and its text ("opening in N s · ◀ to change")
   moves into a small opening pill. **Image first; build only after approval.**
3. **Zoom in the QR scanner** (phone signing in a TV).
Agreed 22 Nov:
4. Tour: no whole-screen touch swallowing, Skip/Next ≥ 48 dp, Back = Skip, ✕ in the card, TOUR lines logged (Samsung).
5. DNS: the phone's DNS first, private DNS when it fails or is blocked (aoneroom), answer remembered per site.
6. Continue look-ahead stops when another title opens.
7. Down-server rule: a "down" host is retried once per new title; with the relay it goes through the relay instead.
8. DetailTiming stops at Play (the "87 s" green light).
9. Blocked-page relay through the Cloudflare Worker (allowlist: vcloud.fit, hubcloud.*, vegamovies.*, rogmovies.*) +
   final file links kept for days → VegaMovies back.
10. Watch together: fix after the friend's log (rooms are the same code as StreamX; suspects: invite link opens StreamX,
    player swaps/retries fighting the sync).
11. Then calls (Cloudflare Worker + Durable Objects + Firebase push + WebRTC, Cloudflare TURN).
12. Live server for 32-bit (armeabi-v7a) and on demand (wakes only when plugins + addons find nothing, sleeps ~1 min
    after the player closes).
13. Layered sources: Quick Play = chosen plugins + addons race; then the rest by name and by id; then the Live server.
From StreamX TV, worth bringing to Xstream mobile too: Skip intro **learned corrections only** (`IntroFix.kt`; the
25 fps speed scaling of TV 0.9.9 made it worse and was removed in TV 0.9.10, keys `introOff2_`, cloud `xshared/intro2/`);
smart search for MovieBox "Name S7"; log newest first.

## Learned on StreamX TV (0.9.10 – 0.9.12, 23 Nov) — check Xstream for the same bugs
- Episode menu (hold OK): the menu opens during the hold, so its release must be ignored (`TvEpMenu.kt` `armed`).
- Panel focus flag: never reset "panel has focus" when a panel opens — only when it closes (presses were eaten).
- Update: every update wiped the picture disk cache (`TvBox` cleanup) → keep `posters`; RUNNING_LOW only trims pictures.
- Update installer: start it from the Activity (not app context + NEW_TASK, it opened behind the app → downloaded twice);
  reuse a finished download of the same version.
- Next episode: link + file start/index saved to storage + subtitles ahead (no second player on 1 GB); handoff never
  waits for subtitles; PlayTiming records belong to one player (`session`).
- Per-profile settings: a one-time change must cover every profile (frame-rate matching came back on the 2nd profile).
- An episode saved spot under 60 s = start from the beginning.
Asked, not decided: 3D copies (tag + 3D group + VR stereo / "watch in 2D").

## TV 0.9.13 lightness (23 Nov) — candidates for Xstream / StreamX phone
- `TvImgSize` Coil interceptor (MainActivity.kt): TMDB/Metahub URL rewritten to the smallest size that fills the drawn
  size (backdrops w300/w780, posters w185/w342/w500; metahub large→medium ≤400 px). Phones have `HdPoster` (similar idea).
- `TvIdle` (MainActivity.kt): last key time; 4K HDHUB prewarm (TvApp), log uploads (ActLog), first update check
  (Updates.kt, 8 s + quiet) wait until the remote is quiet.
- `TvSmall.on` cached (was a system call each time).
- `app/src/main/baseline-prof.txt` (wildcards for app, Compose, Coil). NOTE: CI builds `assembleDebug` → the APK is
  debuggable, so ART ignores the profile and Compose runs slower than a release build. The biggest remaining smoothness
  win: non-debuggable builds signed with the same key (decision needed; same signature keeps in-app updates working).
- Glass background kept as is (already 192×108 blurred once, cached; now fetched as a small picture).

## TV 0.9.14 (23 Nov) — what and where (check before bringing to Xstream mobile)
- No automatic stream switch after the first picture on TV (`PlayerScreen.switchTo` guard: `IS_TV && auto && everPictured`,
  except "the upgraded file failed"). Better copies only via the Upgrade button.
- Upgrade pool also reads every finished plugin answer (`PluginSources.answered(id)`); 4K HDHUB / UHDMovies / HDHub4u offered
  after 25 s without VegaMovies/RogMovies; audio language kept (`langOk`).
- User changes never show the loading screen: Upgrade / Quality / Sources → `fadeTo` (background `ottSwapTo`, freeze fade on
  TV); Watch next titles and picked episodes → `wnBegin` corner prepare on TV too (`playEpisode` `tvCorner`).
- UHDMovies + HDHub4u back on TV (`CsPlugins.tvLeanSkip`, one-time `seededTv0914`).
- Page relay: `PageRelay.kt` (OkHttp interceptor on the plugins' client) → Worker `streamx-home…/p?u=` (allowlisted file
  sites, pages only). Home catalogs in one request: `HomeBundle.kt` → Worker `/bundle`.
- Wrong-title copy stops at once + link forgotten; HTTP-refused saved link dropped, other links raced (no VLC retry).
- Next-episode prep: only the playing source (`streamsFlow(only = setOf(addon))`), file start saved in 512 KB pieces while
  playback is calm (`tvDropAt`, buffering); other sources fetched only when Quality / Audio / Sources opens (`tvNeedOthers`).
- Skip intro: `vm.skipDb(key, durationMs)` (SkipDB with our file's duration) overrides introdb times on TV; introdb 4 s max.
- Start: TV theme window background = logo (`src/tv/res/values/themes.xml`, `tv_launch.xml`), splash cross-fades into the
  gate (`onFading`), profile pick = short zoom + Home fade-in (`homeIn` in TvApp); hero auto-slide waits for the next
  picture; next two rows' posters prefetched on row focus.

## TV 0.9.15 — steadier and lighter (23 Nov)
- Exit pop-up (`TvExtras.kt` TvExitDialog): the cache size was read on the main thread via `StreamCache.usedBytes`, which
  opens the whole video cache (SQLite + folder scan) when nothing played yet → frozen screen, Android closed the app on Back.
  Now `usedBytesIfOpen()` on IO. Check Xstream's exit dialog for the same.
- First open after an update: the old-file clean-up (`TvBox.setupOnce`) ran on the main thread at Who's watching →
  frozen, closed. Version is marked first; clean-up runs 30 s later on a low-priority thread when the remote is quiet.
- `onTrimMemory`: Fire TV sends level 15 at every start with 300 MB free; pictures are cleared only after 20 s and when
  the heap is >60 % or the box has <120 MB free (`TvSmall.reallyShort`).
- Home refresh waits for a quiet remote when saved rows are shown; Worker bundle cap 5 s; direct fetches max 3 at once;
  the log names the hosts the bundle missed.
- Crash text and "Android closed the app" lines now carry last screen + key; a saved crash is logged at next start.

## Under review — Desiflix (Nuvio plugin, CB provider), added by the user 23 Nov
Fast in the user's use. Watch it in the next logs (answer time, links found, which play, quality, language) and give it
a rank inside the backup layer — expected near 4K HDHUB, below VegaMovies / RogMovies. Not used for auto-switching on TV.

## Plan after the 23 Nov logs (TV 0.9.14/15, StreamX phone 0.9.67, Xstream 0.8.16)
Findings: Xstream 0.8.16 ALSO crashes out of memory (OnePlus 256 MB 18:05:47, realme 384 MB 22:30:55, native crash
22:58:52) — +200 MB in the first ~20 s of a CNCVerse stream; the memory fix is NOT proven yet. StreamX phone: OOM on
realme during a wrong-title storm (Dhindora S2E1, 35 vs 24 min, every source rejected). Watch together: rooms vanish
when the creator's app dies; invite link https://streamx-watch.pages.dev/join is verified only for StreamX, and both apps
share streamx://join.
1. StreamX phone 0.9.68 (proven fixes only): wrong-title = trust ≥2 sources agreeing on length; logs out of users/<uid>;
   posters kept after update; false memory signals ignored; installer from Activity + reuse download + current notes;
   dead saved link dropped; rejected link never retried; crash text in log; first-run-after-update light.
2. Xstream 0.8.17 (test): memory tracker (first 30 s of play) + fixes (subtitles lazily, no repeated answers after start);
   Watch together logging + creator rejoin within 2 min + clear "room ended"; own invite link via Worker /xjoin + assetlinks
   + xstream://join; invite page with both apps; TV lessons (SkipDB/length, relay, Home bundle, right-sized pictures, idle
   start, hero/poster preload, Upgrade offers HD plugins incl. Desiflix); logs in one place; QR zoom; profile "no lift"
   (image first).
3. Then the memory fix + new features move to StreamX phone once Xstream logs are clean; then the 5 batches.

## Rollback point 23 Nov
If a later build goes wrong, go back to: branch `backup-2026-11-23` in callmerickk/StreamNest and callmerickk/Xstream;
release build-198 (StreamX 0.9.67 · TV 0.9.15); TV working tree archive `/home/claude/backup-tv-tree-2026-11-23.tgz`.

## Done 23 Nov: StreamX phone 0.9.68 (build-201) and Xstream 0.8.17 (build-144)
- StreamX 0.9.68: ≥2-source length rule (phone+TV, PlayerScreen `offLens`/`trustedLen`); 403/rejected links never retried
  on phone; old TV logs dropped from users/<uid> by the phone too (ActLog); tour: no whole-screen swallower, 48 dp
  Skip/Next, ✕, Back = Skip, TOUR log lines. Phone keeps auto-switching (TV-only rule).
- Xstream 0.8.17: `SubCap` (only your languages' subtitles to ExoPlayer, 6 max) + 5 s memory lines for 30 s; Relay
  open-connection count + no >2 MB playlist reads; length rule; Together logs/2-min host grace/host rejoin
  (`Together.resumeIfRecent`); invite link `/xjoin` on the Worker + `xstream://join`; SkipDB; PageRelay; HomeBundle;
  `ImgSize`; upgrade pool adds UHDMovies/Desiflix; log newest first.
- Not ported yet: idle-gated start and hero/poster preload (TV-specific), stopping repeated cached plugin answers.

## Migrated in StreamX 0.9.69 (build-203, 23 Nov) — batch "Start & play" (phone only; TV unchanged)
CHANGED
- Never back to loading after the picture (`sawPicture`, frame held on every reload in `startWay`, loading layer hidden) → PlayerScreen.kt
- `keepTrying` (all links failed after the picture: wait for the internet ≤2 min, retry same / ranked links, fresh look-up every 3rd round) → PlayerScreen.kt
- Quiet fresh look-ups before the picture (`autoFinds` ≤3) instead of the error → PlayerScreen.kt
- Late links merged into Other streams / backups (title page list every 4 s for 2 min + one `allStreams` at 40 s) → PlayerScreen.kt
- One-link start gets backups after 6 s → PlayerScreen.kt
- Fast fallbacks: TIMEOUT before the picture → next source (no other ways); last link 20 s, 127.0.0.1 8 s → PlayerScreen.kt
- Fake videos: file < 20 MB rejected, expected length from saved progress first, unknown length < 5 min ep / < 30 min film → PlayerScreen.kt
- Resume same source: `Progress.source/addon`, `PlayingSrc`, Continue passes them, `itemFor` matches the resumed link → Models.kt, Screens.kt, Home.kt, MySpace.kt, PlayerScreen.kt
- Watch next corner: `wnTries` (2 quiet retries), opens with a link after 25 s, 60 s max → PlayerScreen.kt
NOT CHANGED
- LinkMemory (4 h disk cache + look-ahead): StreamX's start is already faster (green light 0.2–0.9 s vs Xstream 1.6–4.3 s in the 22 Nov logs); look-ahead adds memory/data.
- Links as found (`onLink` in `modeStreams`): StreamX already has the `onLink` plumbing and a faster green light; changing the search path risks the stable start.
- Memory / subtitle cap: waiting for Xstream 0.8.17 logs (rule).

## Migrated in StreamX 0.9.70 (build-206, phone only — TV stays 0.9.15)
CHANGED
- Quality sheet (`PhoneQualityBar` = Xstream's PluginQualityBar: fixed rows Auto/4K/1080p/720p/480p + 60fps rows, white
  frame = `qChosen`, tick = playing row coloured by `qualityTick()`, `OtherStreamsList` with 1080p/Unlabelled groups);
  TV keeps the old PluginQualityBar → PlayerScreen.kt
- Quality chip: 4K / 1080p (60fps) / 720p / 480p, "Auto" for 20 s after a height change (`qLastChange`, `qLastH`);
  StreamX's "· 4K READY" suffix kept → PlayerScreen.kt
- Red seek dot always on phones + red dot with halo while dragging; `scrubHide` fades title, top buttons, time row and
  button rail while dragging → PlayerScreen.kt (SeekBar + controls)
- Exact short seeks on phones (EXACT ≤60 s, else StreamX's SeekParameters(300 s, 0)) → PlayerScreen.kt `seekAbs`
- `edgeStart` (top/bottom 13 % swipes ignored) → PlayerScreen.kt gestures
- Post-credit pill on phones (`stingerOf`, tap to hide) → PlayerScreen.kt
- Pilot / Season Finale / Series Finale tags → Detail.kt
- Cinema-copy dialog ("In cinemas only", Remind me · Play cinema copy · Close, `isCinemaCopy`, clean ready copy plays) → Detail.kt
NOT CHANGED
- Trailer in card (`TrailerInCard`): StreamX auto-plays card trailers (with pause) — a different, deliberate behaviour; kept.
- Top bar ‹ with Watch together + VR slide-out: VR isn't in StreamX yet (extras batch 0.9.73); comes with VR.
- `qChosen` blocking automatic quality swaps: phone auto-switch stays as StreamX does it today (user's choice).

## Migrated in StreamX 0.9.71 (Search batch)
CHANGED:
- Spelling: `PluginSources.deaccent`, `norm`/`core` without marks and "aa"→"a", extra name variants (Bāhubali/Baahubali) — shared (TV gets it at its next build).
- Addon error titles hidden: `Models.kt` `CatalogResponse.metas` filters `Meta.isAddonError()`; search results drop them + `isJunkTitle` (phone).
- Collections / universes / studios: `Search.kt` `realTitles` (TMDB check once: IMDb id, film ≥ 60 min, series episodes ≥ 20 min, no documentary/talk/reality/news; remembered in prefs `colCheck`, IMDb id kept via `Collections.rememberColImdb`); universe discover filtered (votes, genres, junk names); universe card added when ready (no 2.5 s drop).
- Per-profile search: `SearchState.forProfile` (called from `AppViewModel` on profile switch, phone), search text / open collection / tab / scroll survive a title page; collection stays open behind the title (Back returns); no dialog flash.
- Saved episode lists: `AppViewModel.fullMeta` phone path (one request per title, 10 min memo, Cinemeta first, addons raced, series only with episodes, saved in prefs `shows`), `savedShow` shown at once in `Detail.kt`; `Continue.seriesMeta` keeps only lists with episodes. TV keeps the old path (`fullMetaTv`).
- `noSourceName` ("4K HDHUB" not read as quality) in `Streams.text`, `Ranker.allText`, `Quick.resIn` — shared.
- `noteMeta` before `Prepare.start` in `Detail.kt` (plugins get name+year at once).
NOT CHANGED:
- StreamX's smart search (`FoundPages`, `laterSeries`, `tailSeason` "Name S7") kept — already better than Xstream's.
- TV search path untouched apart from the shared matching helpers above.

## Agreed plan — cloud + access (23 Nov evening)
1. Cloudflare Worker "link finder": asks every Stremio addon in parallel, ranks, returns one list; shared per-title
   memory of found pages/links (D1 if bound, else cache), checked and dropped when expired. Plugins stay on device and
   keep searching as backups. No video through Cloudflare or any storage cloud (IP-locked links, ToS/account risk).
2. Device: quietly refresh links of the top 3 Continue Watching items when idle / on wifi → instant Play.
3. Access: "Request access" after sign-in (name, email, device model) → admin Requests screen (Approve / Deny / remove);
   enforced by Firebase rules (approved uids only); "minimum version" in versions.json → older versions must update.
4. LATER, when the user confirms an Oracle Cloud Always Free ARM server: Redroid + app "finder mode" runs the CloudStream
   plugins (server does the slow search/page finding, device does the final quick link step), and the Live server (arm64).
Order: StreamX 0.9.72 → 0.9.73 → Xstream 0.8.18 (1–3) → Oracle part after confirmation.
Legal note given to the user: a notice doesn't make it legal; server-side finding raises exposure; hosts' rules forbid it;
suggest an IP lawyer before growing.
