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
