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
