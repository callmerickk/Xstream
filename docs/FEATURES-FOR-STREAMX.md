# Xstream features to bring into StreamX — running notes

Keep adding to this file with every Xstream / Xstream Live change. When the user says "put the new Xstream features into
StreamX mobile", work through it top to bottom, skipping items StreamX already has.
Source paths are in the Xstream repo, under `app/src/main/java/com/example/streamnest/`.

## Home and look
- **Home styles: Live / Classic / StreamX** (0.8.5). `Quick.kt` `homeStyleOf(texts)` (key `homeStyle`) chooses the style;
  `liveLook()` and `liveLookNow()` read it. `Home.kt` decides on the style: `"live" -> LiveHero`, `"streamx" -> SxDeck` (SxDeck.kt, a copy of
  StreamX's Deck), else `Deck`. Live: endless pager (n×2000), curved `LiveHeroShape`, BlurMaskFilter red glow under the curve,
  IMDb badge (`HeroRatings`), speaker button, `trimBars` (zoom past baked-in black bars), red tick on section titles
  (`SectionTitle`), `liveEdge()`. Pushed rows: `AppViewModel.pushedRows()` uses StreamX's `homeRowsSx` unless the style is classic.
- **Pick each part separately** (0.8.6). `LookPicker.kt`: `LookSection` with Home style, Bottom bar and Theme sheets; `MiniHome`
  canvas previews; Quick looks (All Live / All StreamX / All Classic). Key `navBar` = orbit|streamx|capsule
  (`barKindOf`), independent of the Home style. Theme "Midnight" (bg #0F1014, tint #16204A, red) added to `Themes`
  (SettingsHub.kt); `applyThemeOpt`. Key `themePicked` keeps the Live colours forced until a theme is picked.
- **Orbit bar** (Live, `LiveBar.kt`): a clear glass pill with a red glow line; Me shows the avatar (GIFs animate); hold Me =
  `SxProfileSwitcher` with water rings (`SxWaterRipple`).
- **Trailers**: `YtDirect.kt` picks the best copy (720p on Wi-Fi, 480p on data, `limitHeight`), `trimBars`, a lifecycle gate (stops
  when the app is not RESUMED), muted while scrolling, stops when off-screen. PLANNED: no automatic trailers; ▶ Trailer button
  plays 720p/1080p on tap.
- **Watched list**: `addWatched`, `loadWatched`, `removeWatched`; `XLibrary.kt` Watched tile and `WatchedPage`.

## Profiles and taste
- **Horizon profile page** (0.8.6, `ProfileHorizon.kt`): night sky, glowing curved ground (`HorizonGround` in TastePick.kt); the
  last profile already chosen; its Continue watching as a poster fan (`progressOf(pid)`); "Picked for you" when empty
  (`pickedOf(pid)` saved by `refreshRecommended` → `savePicked`; fallback genre or Cinemeta top); a 10 s ring, touch stops it,
  tap = preview, tap again = open; a poster plays after `selectProfile` (series → first episode in `viewModelScope`); locked
  profiles hide their posters; main profile bottom-right (`ProfileGround`). Hooked from `WhoIsWatchingScreen` on phones.
- **Taste titles** (0.8.6, `TastePick.kt` `TasteTitlesScreen`): shown once after the language step (`tasteTitlesDue`, key
  `tasteTitles`); genre chips, TMDB discover by language (`tmdbDiscover` in Tmdb.kt), search (`searchNow`); picks → `vm.vote(meta,1)`;
  `tasteGenres` / `tasteSearched` feed `tasteSummary` (Ai.kt) and `buildRecommendations` (Vote.kt). New profiles don't copy
  `taste*` keys (`addProfile`).
- **No "no profiles" screen**: the first profile is made from the account name (Startup.kt).

## Playback speed and data
- **LinkMemory** (`LinkMemory.kt`): links that started fast are kept on disk for 4 h (`cacheDir/linkmem`); `Prepare` seeds
  `live.value` from it, so the light turns green at once. `lookAhead(vm,type,id)` (Semaphore 2) is run by Home for the first 4
  cards and 2 Continue items after 5 s, and by the Watch next sheet for its first 4 titles.
- **Start/seek**: the player starts after 1 s of video and resumes 2 s after a rebuffer (phones); seeks jump to the nearest
  keyframe (`SeekParameters.CLOSEST_SYNC`); thumbnails are off for 127.0.0.1 links.
- **Next episode ready at 75 %**: `Prepare.start` for the next episode → `Preloader` paused player → `Preloader.take(key)`.
- **Watch next in the corner** (0.8.6, from StreamX): `WnLoad`, `wnBegin`/`wnOpen`, `WatchNextLoadingCard` "Preparing next…",
  also used for episodes. PLANNED: smaller landscape card further into the corner; tap → normal loading screen; open as soon
  as a link works (≤6 s).
- **Dead servers** (0.8.6, `HostDown` in ActLog.kt): skip a host after 2 connection failures. Must be FIXED before porting:
  never skip 127.0.0.1 or private IPs, and ignore many hosts failing at once (that is the network).
- **Spellings** (`PluginSources.kt`): `deaccent`, "aa"→"a" in queries and matching (Bāhubali / Baahubali / Bahubali).
- **Memory**: PLANNED FIX — the buffer budget must come from `Runtime.maxMemory()`, not device RAM (12 GB phones got 300 MB per
  player inside a 384 MB heap → OOM crashes during swaps).

## Player
- **VR headset** (`VrHeadset.kt`): a GL curved cinema screen (cylinder r 1.6), seats, lamps, per-eye IPD, Fixed screen with head
  yaw/pitch (TYPE_GAME_ROTATION_VECTOR), SBS/OU 3D, sharpen, lens barrel; a VrLive holder so clicks read live head values;
  a hold-to-click ring after 1.5 s; subtitles as white text with a shadow; 4K on Wi-Fi.
- **Pilot / Season Finale / Series Finale tags** in red (Detail.kt).
- **Cinema copies**: "In cinemas only" dialog with Remind me / Play cinema copy / Close (`isCinemaCopy`).
- **Trailer inside the title card** (`TrailerInCard`), with a tip bubble that grows to the left.
- **PlayTiming.activeFor**: a closing player doesn't end the next title's timing.

## Logs
- **One device id per phone** (ANDROID_ID), `sendBeforeSignOut`, `uploadImportant` (leftover lines from earlier days).
- **Log browser** (0.8.6, `LogBrowser.kt`, Activity log › All accounts): app › version › device › log, with Copy and Share;
  `info.app` written with every upload. PLANNED: apps = Xstream Mobile, Xstream Live, StreamX Mobile, StreamX TV.

## Fixes
- vivo: `navStack` kept in the ViewModel, ComposeView `DisposeOnLifecycleDestroyed`.
- No double next-episode posters (the pill is hidden while Watch next is open).

## 0.8.7 (19 Nov 2026)
- **Memory**: `buildPlayer` budget = min(RAM table, 30 % of `Runtime.maxMemory()`), halved while another player lives (`PlayerCount`).
- **HostDown fixed**: never local/private hosts; ≥3 hosts failing within 5 s = network drop, ignored.
- **Watch next card**: 214×68 dp landscape in the corner, progress line, tap = open now; hand over after 2.5 s with a link, max 6 s.
- **Finished shows**: `sweepFinished()` — last episode done (≥90 %) with no next → Watched; movies done at 90 %.
- **Addon error titles hidden**: `CatalogResponse.metas` filters `isAddonError()` (aiostreamserror / ❌).
- **Search Back**: `SearchState.text` / `SearchState.openGroup` survive opening a title.
- **Home cache**: cached shelves saved shuffled (first 12 of each row; pinned/top-10 kept), posters warmed for those 12.
- **Live hero**: no auto trailers; Play + round Trailer (Pause/Resume) + My List (Mute while playing); `YtDirect.tapHd` = 1080p
  Wi-Fi / 720p data; `TrailerVideo(paused=)`; smaller IMDb badge.
- **Names**: home style "streamx" shown as **Stack**, its bar as **Horizon**, Crimson note "deep black, crimson".
- **MiniHome** redrawn after the real styles, `animate = true` slides cards and rows; **FirstLook.kt** "Choose your look" once
  per profile (key `lookChosen`): style → moving preview → Use this/Back → colours → done; Skip = Live + Midnight.
- **Player top bar**: round ‹ between clock and lock; Watch together + `VrHeadsetIcon` slide out for 3 s at start
  (`extrasOpen`); Sources removed from the phone More bubble.
- **Seek bar**: red dot always on phones; `scrubHide` fades everything but the bar + preview while dragging.
- **Quality sheet** (`PluginQualityBar`): fixed rows Auto · 4K 60fps · 4K · 1080p 60fps · 1080p · 720p · 480p; white frame =
  `qChosen`; tick = playing row coloured by `qualityTick()` (red HLS, yellow MovieBox/plugin, green file); "60fps" badge;
  236 dp wide; Other streams groups renamed 1080p / Unlabelled; choosing Auto clears `byHand`/`userPicked`.
- **Chip**: playing quality (4K, 4K 60fps, 1080p 60fps, 1080p, 720p, 480p), "Auto" for 20 s after a height change in Auto.
- **Slow internet**: file → `ottSwapTo(basePl)` after 2 stalls / 5 s stall; back to the file after 3 min smooth + 45 s buffer.
- **Gestures**: `edgeStart` (top/bottom 13 %) ignores swipes; side strips 20 % (middle 0.6).
- **VR**: "Fixed screen" → **Look around** (ThreeSixty icon), off by default.
- **Post-credit pill**: `stingerOf()` (TMDB keywords aftercreditsstinger / duringcreditsstinger) → "★ Scene after the credits".
- **Seek previews** now also for 127.0.0.1 plugin links (only the Live server is skipped).
- **Log browser**: blank app + "(TV)" model = StreamX TV; order Xstream Mobile, Xstream Live, StreamX Mobile, StreamX TV.

## 0.8.8 (19 Nov 2026)
- **Exact short seeks**: `seekAbs` uses `SeekParameters.EXACT` for jumps ≤60 s (CLOSEST_SYNC snapped HLS ±10 s back), CLOSEST_SYNC for longer.
- **Quality sheet speed**: `qCols` not recomputed on each open; PluginQualityBar groups `remember`ed; tick colour remembered per stream.
- **60fps**: rows only when a 60fps copy exists; "60fps available" glass note top-left 8 s once per title (StreamX TV style).
- **Tick colours**: vega / rogmovies / 4khdhub / hdhub4u = green (checked before the plugin rule).
- **Memory**: Coil memory 12 %, cleared when the player opens; ExoPlayer 22 % of heap; SeekThumbs 160 frames + `trim()`;
  heap logged every 60 s in the player, >85 % → drop posters and frames.
- **Title details**: `fullMeta` asks Cinemeta first for tt ids and skips addon error metas.
- **Relevance**: universes keep movies with ≥40 votes / series ≥15, drop documentary/talk/reality/news, `isJunkTitle()`
  (One-Shot, Assembled, making of…); search hits filtered the same way.
- **Collections**: GroupPage dialog without window animation; tab and scroll kept (`SearchState.groupTab/groupScroll`).
- **Home pictures**: `warmHome()` warms row posters + tall posters/backdrops of the first 3 rows, also from the cached rows
  while the profile page shows; `Posters.warm` up to 260.
- **Silent plugin links**: `startBudgetMs` 127.0.0.1 = 7 s unless LIVE_ONLY.
- **AIDEN**: always listens with SpeechRecognizer; `quickCommand()` runs play / open / search / my list without the AI.

## 0.8.9 (19 Nov 2026)
- **Never back to loading/error after the picture** (PlayerScreen): `sawPicture` (set on first frame, per title); `startWay` captures the frame
  (`frozen`) on every reload; `loadingAlpha` 0 once `sawPicture`; overlay = held frame + white ring (was SwitchCapsule).
- **keepTrying()**: every link failed after the picture → waits for `NET_CAPABILITY_VALIDATED` (≤2 min), clears `tried/badFiles/skipHosts`,
  retries the same link (odd rounds) or the ranked known links; fresh `allStreams` every 3rd round; backoff 1.5 s × round (max 12 s).
- **Resume source**: `Progress.source/addon` (Models.kt) from `PlayingSrc` (set when `now` changes); Continue passes them to
  `Screen.Player`; `itemFor()` matches a resumed link to its fresh copy by addon + stream name → dev label + quality tick.
- **Collections = real titles, IMDb ids** (Search.kt `realTitles`): each collection / universe / studio item checked once with
  `tmdbDetail` (imdb_id; film ≥60 min; series episodes ≥20 min; no genres 99/10767/10764/10763; `isJunkTitle`); kept items get the tt id
  (`rememberColImdb`). Screens.kt: a `tmdb:` Detail resolves first (`TmdbOpening` shows art + story). LinkMemory refuses tmdb ids
  (Detail used "tmdb" as the IMDb key — shared remembered links = wrong film).
- **fullMeta**: one in-flight request per title + 10 min cache; tmdb ids → `resolveForOpen`; Cinemeta (6 s); addons in parallel,
  first in your order within 4 s (PenguPlay used to block 20 s × 2). `modeStreams` never searches error metas / tmdb ids.
- **Quality menu**: sources asked 4 s after the first picture (not on open); 130 ms enter; log `QUALITY menu shown in N ms`.
- **Posters**: `warmHome` warms `hiResArt` (the size drawn) + first card of every row; TMDB tall posters (Top 10) looked up ahead and kept in
  prefs `tmdbPosters`.
- **Memory full prompt**: `memTight()` (heap >85 % or `lowMemory`) on a stream error → "Phone memory is full" Do it (clear Coil memory,
  thumbs, Preloader, other prepared players, gc, retry same second) / Deny (carry on; not asked again for that title).
- **Update nudge** (Updates.kt): `ActLog.add` PLAYFAIL / player ERROR / failed SWITCH → `Updater.onTrouble`; `latestKnown()` (saved
  kCode/kName/kUrl, works offline) → "This is fixed in the latest Xstream · Update now / Later", once a day, waits while `PlayingSrc.open > 0`.
- **Cloud sync**: offline → waits quietly for the internet (was retrying every 60 s for hours).

## 0.8.10 (19 Nov 2026)
- **HLS on weak internet** (PlayerScreen `hlsCap`): 2 stalls in 90 s or a stall >4 s (not right after a seek) → `setMaxVideoSize(∞, next lower height)`
  — same stream, changes at the next segment, no reload; one step up after 2 min smooth with 30 s buffered; a one-quality HLS → `ottSwapTo`
  a lighter copy. Only in Auto (not when a quality was picked). Log: `QUALITY stream qualities: …`, `slow internet → 720p inside the stream`.
- **Playing stream always listed**: `qCols` keyed on `now.url`/`firstFrame`; unknown playing link → synthesised `StreamItem` (source + height).
- **No "Find another source"**: before the picture, a failed link → up to 3 quiet fresh look-ups (`autoFinds`, forced `findRequest`);
  then the phone shows a ring + one line and looks again every 30 s (waits for the internet first). Both buttons removed.
- **Loading-screen ⌄** (and over the held frame): opens `OtherStreamsList` (the Quality menu's Other streams: 4K / 1080p / Unlabelled / Auto,
  hold for all); a pick `load()`s before the picture, `fadeTo` after.
- **Per-profile search**: `SearchState.forProfile(pid)` keeps each profile's text, results, groups, open collection, tabs/scroll.
- **Universe card**: added when ready (`lateUni`), never cut by a timer; by alias (mcu) or from the top results' keywords (avengers → MCU).
  `realTitles` uses a light TMDB call (external_ids) 12 at a time; verdicts saved in prefs `colCheck`.
- **Logs**: uploaded logs now include QUALITY / SWITCH / memory / retry lines.

## 0.8.11 (19 Nov 2026)
- **Quality that follows the internet, no loading** (PlayerScreen, replaces the 0.8.10 block): `bwNow` = smoothed
  `AnalyticsListener.onBandwidthEstimate`; rungs + bitrates from the HLS video track group. Down **before** a stall when speed
  < 1.2× the current rung and < 10 s loaded ahead (or a 1.5 s stall) → `setMaxVideoSize` to the next rung that fits (loaded part
  keeps playing, next segment lighter). Up one rung when speed > 1.4× the next rung with 20 s ahead for 12 s; a stall within 60 s
  of a climb doubles that wait (max 2 min). Auto only. Logs `QUALITY speed … → 720p inside the stream (no reload)`.
- **Ladder upgrades guarded**: no VegaMovies/RogMovies upgrade while capped, 3 min after a slow moment, or under 8 Mb/s; 3 tries per title.
- **60fps note**: its own 8 s timer, top-right.

## 0.8.12 (20 Nov 2026)
- **Memory** (OOM crashes in OkHttp threads, o-kills at 440–625 MB): `PlayerCount.room()` = one live player and heap < 70 % — required for
  auto `ottSwapTo` (ladder, slow-internet copy), next-episode preload, ladder loop; Prepare skips opening ahead above 70 %; VR "best picture"
  only < 60 % after `Preloader.release()` + `Prepare.stopOthers()`. Hidden players get 10 % of the heap (playing one 22 %). User picks free
  hidden players first. `ladderNo`: an addon whose copy had another length isn't retried. `MainActivity.onTrimMemory(UI_HIDDEN)` frees
  Preloader / Prepare / Coil memory. `cappedString()` refuses replies over 16 MB. "player made · buffer · memory" logged.
- **Fast fallbacks**: one-link start → `allStreams` backups after 2.5 s; a TIMEOUT with no data → next source / quiet fresh look-up (no
  other ways of the same link); last-link wait 20 s (127.0.0.1 8 s). `HostDown`: home-network servers (10.x/192.168.x) skipped after 2
  failures until the network changes (`ActLog.netHandle()`); connection-reset / 407 hosts rest 10 min after 3.
- **Fake videos**: expected length from saved progress (else meta, else `metaFor` ≤3 s); file < 20 MB (Range probe) or very short
  (unknown length: < 5 min episode / < 30 min film) → rejected + `markDeadUrl`. `Progress.toPlayer()`: saved link older than 12 h →
  `find = true` from the saved second.
- **Series**: `fullMeta` accepts shows only with `videos` (Cinemeta 12 s, addons up to 12 s), saves them (prefs `shows`), falls back to the
  saved one; `seriesMeta` never caches empty. Detail shows saved episodes at once; a finished last episode → the next one; never plays a
  bare show id. Episode progress saved from 1 s.
- **Next episode card**: phone `NextEpisodePill` is the same 214×68 corner card as `WatchNextLoadingCard`.

## 0.8.13 (20 Nov 2026)
- **Watch next opens into the picture**: the corner card waits for `Prepare.state.playerReady` (the hidden player's first frame), no
  2.5 s / 6 s handover; a failed prepare is retried in the corner twice (`wnTries`); a link without a hidden player (memory) opens after
  25 s; 60 s max. Card progress runs over 20 s.
- **Faster fallbacks**: before the picture a TIMEOUT always goes to the next source / quiet fresh look-up (no other ways of that link).
- **Continue**: the saved link plays at once again (the 0.8.12 12-hour rule made every resume a full search); fake / dead links are
  caught by the size + length checks and the backups (fetched after 6 s now, not 2.5 s, so they don't slow the starting link).
- **Logs**: uploads keep the `PLAY +x s` stage lines and Watch next lines.

## 0.8.14 (20 Nov 2026)
- **Look-ahead only where it pays**: Home looks ahead for Continue watching (first 2) only — Home posters and the Watch next sheet are no
  longer searched before a tap (memory + data). Next episode at 75 % stays.
- **Your tap first**: `LinkMemory.lookAhead` runs one at a time (Semaphore 1) and waits while `Prepare` is searching.
- **Page cap** (`ActLog.Net.capPage`): text/html and application/json answers over 5 MB are refused or stop reading at 5 MB (normal plugin
  pages are under 1 MB); video, playlists, subtitles, pictures untouched.
- **Relay** (127.0.0.1 header relay) copies in 64 KB pieces instead of 256 KB per connection.
- App motto noted: fastest start first — nothing new may run before the first picture; the auto quality switch stays as in 0.8.11.

## 0.8.15 (20 Nov 2026)
- **Private DNS only** (`Api.ChainDns`): the chosen DNS-over-HTTPS, then Cloudflare → Google → Quad9 → AdGuard; the phone's DNS only for
  `.local` / IP names or when "System" is chosen. The old `FallbackDns` quietly used the provider's DNS when Cloudflare was slow — that DNS
  blocks `aoneroom.com`, so MovieBox / MovieBoxIN got "Unable to resolve host" while CloudStream (DoH only) worked.
  Log: `DNS: host found by google (cloudflare didn't answer)` / `DNS: host not found — …`.
- **HostDown** ignores UnknownHostException (a failed lookup marked api3.aoneroom.com down for 10 min).
- **Plugins** (`CsPlugins` baseClient) look up through `Api.http.dns` each time, so a DNS change applies without a restart.
- Planned next (after the log confirms MovieBox sources answer): DASH (.mpd, multi-quality) treated like HLS and kept as the first backup — from every MovieBox provider (built-in MovieBox, CloudStream MovieBox, MovieBoxIN, others), not only MovieBoxIN.

## 0.8.16 (20 Nov 2026)
- **Title page speed**: `PluginSources.noteMeta(meta)` before `Prepare.start` — `modeStreams` used to fetch Cinemeta for the name/year
  first (~1.4 s before Netflix / Prime / Hotstar / Disney even searched; HDHub ones started at 0 s). `DetailTiming` logs `TITLE page open`,
  `first link after X s (N blinks) · source`, `light green after X s`.
- **Shows**: no episode list yet → Prepare starts with the Continue episode (unfinished) or S1E1 at once.
- **Links as found**: `linksFrom(onLink=…)` in `modeStreams` — each CloudStream link is passed on immediately (MovieBox spent 6–55 s on all
  links + 40–50 subtitles before passing any); final copies (with subtitles) replace early ones. Nuvio (Purstream) waits ≤3 s for the
  CloudStream plugins (backup).
- **Late links**: the player merges `Prepare` list updates (every 4 s, 2 min) and one `allStreams` at 40 s into `known` (Other streams + backups).
- **4K HDHUB quality**: `noSourceName()` strips "4K HDHUB" before reading quality (text(), resIn(), allText()); "4K" elsewhere still = 4K.
- **Warm-up**: `warmAll` warms MovieBox / cncverse / OTT entries first and pauses while a title is searching; 3-minute re-warm stays.
- **DNS**: `ChainDns` remembers "no such name" 10 min (vegamovies.mq asked 20+ times, 1.5 s each).
- **Updates**: Update = background download with `UpdatePill` (progress + speed), then "Xstream X is ready · Install"; the APK is kept
  (`apkCode`) and reused; after the unknown-apps screen the installer opens on return (`installWaiting`); the update client uses the phone's
  DNS first (nearest GitHub file server), private DNS as fallback.
- **Downloads**: saved to public Movies/Xstream (scanned into the gallery), app folder only if not writable; video extensions only.
- Not done yet: start searching on finger-down; 1 s wait for your language's copy; folder picker for downloads; DASH-first start.

## Xstream 0.8.18 (23 Nov)
- Cloud link finder (CloudFind.kt, Worker /find, optional D1 memory) — addon answers sooner; direct answers replace them.
- Continue watching links kept fresh while idle on Wi-Fi (LinkMemory.refreshCw).
- Invite-only access (Access.kt: AccessGate + Settings › Access requests; Firebase access/…; off by default).
- Minimum version (versions.json minPhone from min-version.txt) → "Update required".
