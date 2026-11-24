# Merge inventory: Xstream 0.8.20 vs StreamX 0.9.79 (24 Nov 2026)

> **Step 2 (Xstream 0.9.1) status** — see the table "Port status" at the end. Direction is now one way: the Xstream repo
> is the single source of the shared code; `tools/sync-to-streamx.sh` copies it into the StreamX tree
> (`tools/sync-shared.sh`, the old StreamX → Xstream copy, is removed). How switches work: `docs/HOW-SWITCHES-WORK.md`.

Decision: StreamX's code, as it is, is the one shared base. Xstream = StreamX's code + Xstream's identity (brand layer)
+ Xstream-only extras behind `Feature` switches (Step 2). This file lists what Xstream 0.8.20 has that StreamX lacks,
and what behaves differently. Branding (names, repo, package) is listed only under "Where branding lives".

Sources: the 11 Xstream-only files, `app/src/full`, per-file diffs of the 149 shared files (120 differ),
`FEATURES-FOR-STREAMX.md`, `STREAMX-MIGRATION.md`, Xstream Changelog 0.8.x. Xstream paths are from branch `main`
(0.8.20, also `backup-xstream-0820`); StreamX paths are the shared base in `shared-base`.

Legend — **Have?**: StreamX already has an equivalent (and which is better). **Switch**: suggested `Feature` name.
Items marked **[0.8.20]** are the newest Xstream work, not yet ported anywhere.

---

## A. The Xstream 0.8.20 items (port first)

| # | Item | What it does | Xstream files | Have? | Switch |
|---|------|--------------|---------------|-------|--------|
| A1 | **[0.8.20] CloudFind, cancellable** | One POST `/find` to the streamx-home Worker asks every addon at once (optional D1 memory ≤3 h). 0.8.20: `enqueue()` inside `suspendCancellableCoroutine` (call cancelled when the title is left), 5 MB answer cap, `directOnly`, `cloudUrls`. Phone only. | CloudFind.kt, AppViewModel.addonStreamsFlow | No. StreamX asks each addon directly. | `cloudFind` |
| A2 | **[0.8.20] Ask once (addonStreamsFlow)** | Cloud finder first with a 1.5 s head start; an addon the cloud answered with ≥1 usable link skips its direct call (Quick Play addons, CNCVerse, `CloudFind.directOnly` always asked). A cloud link refused with 401/403/410 → `askAddonDirect` for that addon, merged into `live` (Prepare waits ≤8 s). NET summary line per title. | AppViewModel.kt (`askAddonDirect`, `mustAsk`, `startDirect`), Prepare.kt | No (needs A1). | `cloudFind` (same switch) |
| A3 | **[0.8.20] PluginGuard** | Outermost interceptor on the CloudStream plugin client: video/audio, octet-stream big/unknown, >16 MB → empty body + `X-SX-Cut: 1`; chunked text/JSON capped at 12 MB; ranged reads and HEAD pass. Fixes the HubCloud t3dl.php 1.75 GB OOM. | CsPlugins.kt (`PluginGuard`) | Partly: StreamX has `cappedString` (16 MB, Api.kt) and `capPage` (5 MB html/json, ActLog.Net) but nothing on the plugin client itself. Xstream's is better for this crash. | `pluginGuard` (good stable candidate) |
| A4 | **[0.8.20] saveParts / racedProbe** | `StreamCache.saveParts`: 4 pieces default (8 after two clean 4-piece saves), pixeldrain 2 (1 after a refusal), hosts that refuse extra connections learned and kept on disk 7 days (max 50), head saved + 403/429 on extra parts = success. `race()` keeps each good probe 60 s (`racedProbe`), runner-up probed in the background; save poll 80 → 25 ms. | StreamCache.kt (`loadSingleHosts`, `rememberSingle`, `noteParallelOk`, `racedProbe`), Prepare.kt | No. StreamX has its own save path (0.9.79 dropped the jump test). | `smartParts` |
| A5 | **[0.8.20] DetailTiming breakdown** | Title-page timing log: `TITLE page open`, first link after X s (N blinks), "link ready" (p.ready) vs "light green" (ready && playerReady, what the user sees), a "green breakdown" line (job start → links in → link checked). | Prepare.kt (`DetailTiming`), Detail.kt | No (StreamX logs its own PLAY stages only). Diagnostic only. | `detailTiming` |

## B. Xstream-only files

| # | File | What it does | Have? | Switch |
|---|------|--------------|-------|--------|
| B1 | Access.kt | Invite-only access: `AccessGate` over the app (update / signin / request / waiting / denied); Settings › Access requests (developer: approve / deny / remove, "Approval required" = `access/config/required`, off by default). Also "Update required" from versions.json `minPhone` (min-version.txt in build.yml). | No. StreamX's Updater ignores `minPhone`. | `accessGate` (example already in Feature.kt) |
| B2 | CloudFind.kt | See A1. | No | `cloudFind` (example already in Feature.kt) |
| B3 | Donate.kt | "Support Xstream": developer fills UPI / PayPal / Ko-fi + monthly goal (Developer's Corner › Payments & donations, `xshared/donate`); after 1 h of use a bubble asks once every few days (UPI in India, links elsewhere). | No | `donate` |
| B4 | LinkMemory.kt | Links that started fast are kept 4 h on disk (`cacheDir/linkmem`), so a title opened again is green at once; `lookAhead` (Semaphore 1, waits while a title searches) for Continue watching; 0.8.18 `refreshCw`: top 3 Continue watching re-looked-up when idle 10 s on unmetered Wi-Fi. | Partly: StreamX 0.9.79 "Home on intent" (CW top 3 one by one, held poster, hero dwell) covers look-ahead; no disk link memory. StreamX's green light was already faster (0.2–0.9 s vs 1.6–4.3 s). | `linkMemory` |
| B5 | LiveBar.kt | Orbit bottom bar (glass capsule, avatar Me, hold Me = profile switcher with water rings). | Yes: `PhoneOrbitBar.kt` (copied from it in 0.9.75). Same. | none |
| B6 | MicHold.kt | `MicHold.n`: the wake word stops while voice search uses the mic. | Yes: defined in StreamX `WakeWord.kt`. Same. | none |
| B7 | MusicLog.kt | "MUSIC" activity-log lines for Xstream Music (song start + why, start time, buffering, skips, pauses, quality, taps). | No (music is off in StreamX). | `music` |
| B8 | ProfileHorizon.kt | Horizon profile page (night sky, glowing ground, last profile's Continue watching as a poster fan, "Picked for you", 10 s auto-open). | Yes: `PhoneHorizon.kt` (ported 0.9.74, "no lift" pill). StreamX's is newer. | none |
| B9 | SxDeck.kt | Home style "StreamX" = a copy of StreamX's Deck inside Xstream. | Yes: it IS StreamX's own `Deck` (Home style "Stack"). StreamX's is the original. | none |
| B10 | Taste.kt | Per-profile taste (movies / series / anime / K-drama + languages in order): "New for you" mixing your languages, a pill per language opening that language's page, Anime / K-drama rows. | No | `tasteRows` |
| B11 | TastePick.kt | "What do you love watching?" once per profile after the language step (`tasteTitles`): genre chips, TMDB discover by language (`tmdbDiscover`), search; picks → liked titles for the AI and "Picked for you". `HorizonGround`. | No | `tastePick` |

`app/src/full` (Speaker.kt, WakeWord.kt): offline wake word (Vosk) and face detection, only in Xstream's phone /
universal / tv flavors. StreamX has the same two files in `src/main` (MicHold moved into WakeWord.kt) and Vosk + ML Kit
in every build. **Equivalent; no switch.** The `full` source set is gone in 0.9.0.

## C. Xstream-only behaviour inside shared files

### Playback and player (PlayerScreen.kt, Prepare.kt, StreamCache.kt)
| # | Item | Have? | Switch |
|---|------|-------|--------|
| C1 | `memTight()` "Phone memory is full" prompt (Do it: clear Coil, thumbs, Preloader, other players, gc, retry same second / Deny: not asked again for that title) | No. StreamX has `MemWatch` (75 % → let go of paused player, Coil, other answers) + `HiddenCap`. StreamX's is quieter; Xstream's asks. | `memPrompt` |
| C2 | `PlayerCount.room()` rule (one live player and heap < 70 %) gating auto `ottSwapTo`, ladder, next-episode preload; hidden players 10 % heap | Yes (0.9.76 PlayerCount + 0.9.77 HiddenCap). StreamX's is newer. | none |
| C3 | Next episode prepared at 75 % (`Prepare.start` → `Preloader.take`) and Watch next corner card waiting for `playerReady` (`wnTries`, 25 s / 60 s) | Yes (0.9.69). | none |
| C4 | Quality sheet with fixed rows + `qChosen` blocking auto swaps | StreamX has `PhoneQualityBar` (0.9.70/0.9.79: all rows tappable, nearest option). StreamX's is newer. | none |
| C5 | `picOrder` / `reject` (fake-video rejection order) | StreamX has `rejectFake` + ≥2-source length rule (0.9.68/0.9.69). Equivalent. | none |
| C6 | Prepare "remembered links checked first" (LinkMemory seed into `live`) | No (part of B4). | `linkMemory` |
| C7 | Live server green light (paused player on the local server, no save step), `LiveServer.linksBody/streamsNow` | No; `HAS_LIVE` is false in both apps today (Xstream Live is a separate branch). | `liveServer` (later) |

### Network and logs (Api.kt, ActLog.kt, Cloud.kt, CsPlugins.kt)
| # | Item | Have? | Switch |
|---|------|-------|--------|
| C8 | `HostDown`: skip a host after 2–3 connection failures (never local/private; ≥3 hosts in 5 s = network drop; LAN hosts per network via `netHandle`; resets/407 rest 10 min; UnknownHost ignored) | No (migration doc: "touches every network request; needs its own test build"). | `hostDown` |
| C9 | DNS: Xstream 0.8.15 "private DNS only" chain (chosen DoH → Cloudflare → Google → Quad9 → AdGuard; system DNS only for .local/IP) + 10-min "no such name" memory | StreamX has `ChainDns` too but phone DNS first, private DNS when it fails (agreed fix 5, faster green light). StreamX's is the agreed one. | none (keep StreamX) |
| C10 | Cloud sync waits quietly offline (`online()`: NET_CAPABILITY_VALIDATED, up to 1 h) instead of retrying every 60 s | No. | `syncWaitOnline` (stable candidate) |
| C11 | Watch together: host's room remembered 2 min (`resumeIfRecent`/`saveRoom`), guests wait 2 min for the host ("reconnecting"), TOGETHER log lines (created / joined / left / ended), "This room has ended" message | No (StreamX ends after 45 s, fewer logs). StreamX has the newer call relay (TURN, `Call.status`), which Xstream lacks. | `roomRejoin` |
| C12 | Log upload path via `XNs.url("logs/…")` | StreamX writes `xshared/logs` directly (`LOG_ROOT`) — same place. Equivalent. | none |

### Home, title page, search (Home.kt, Detail.kt, Screens.kt, Search.kt, YtDirect.kt, Tmdb.kt)
| # | Item | Have? | Switch |
|---|------|-------|--------|
| C13 | Home: Taste rows (`tasteKey`, "New for you", language pills), row order Continue → Trending → Aiden's picks → languages → collections | No (part of B10). | `tasteRows` |
| C14 | Home: long-press any poster lifts it with its trailer; Live hero red tick on row titles, `liveEdge` card edge | StreamX has Live hero (`PhoneLiveShape`) and its own card trailers + TrailerInCard (0.9.78). Mostly equivalent; lift-with-trailer missing. | `posterLift` |
| C15 | Screens: dock hides after 4 s without a touch | No. | `dockAutoHide` |
| C16 | Screens: `TmdbOpening` — a `tmdb:` title resolves to its IMDb id first (art + story shown meanwhile) | Partly: StreamX resolves collection items via `realTitles` (0.9.71); no TmdbOpening screen. | `tmdbOpening` |
| C17 | Screens/Startup: "Sign in to Xstream" first start, wait for the account before Who's watching, no "no profiles" screen | StreamX has its own sign-in gate (CloudScreens) and first profile from the account name. Equivalent. | none |
| C18 | Startup: "Box office" ticket profile picker and the Live round-avatar picker | No (StreamX uses Horizon on phones). Look only. | `ticketPicker` |
| C19 | Detail: "Xstream phone: the Play button lifts out like a poster" layout; every episode's own saved place (not only Continue's) | Partly. StreamX has saved shows (`savedShow`), finished-last → next. Per-episode places missing. | `episodePlaces` |
| C20 | YtDirect: trailer quality by network (`limitHeight`: 720p Wi-Fi / 480p data, `metered`, `score`, `barShare`) | No. StreamX has `TvTrailerPlayer` and its own pick. | `trailerByNetwork` |
| C21 | Tmdb: `tmdbDiscover` by language, saved TMDB tall posters (`tmdbPosters`) | No (needed by B11; StreamX Top 10 uses landscape Metahub art on purpose). | `tastePick` |
| C22 | AppViewModel: `isPostersPlus` addon kept in Live-only mode; per-profile "Picked for you" reads for the profile page | Live-only part n/a. StreamX has `pickedOf/savePicked` (0.9.74). | none |

### Settings (SettingsHub.kt, Settings.kt)
| # | Item | Have? | Switch |
|---|------|-------|--------|
| C23 | Toggles: "Adult content" (18+ in search), "Trailers on cards", "Auto-play Next Episode" row on the Playback page; tap your picture in My Space = edit | Not as these rows in StreamX. | `extraToggles` |
| C24 | Settings › Access requests (developer) | No (B1). | `accessGate` |
| C25 | Bottom bar / Look picker / Midnight / FirstLook | Yes: ported 0.9.72–0.9.76 (LookPicker, Glass added). StreamX's is newer. | none |

### Updates (Updates.kt)
| # | Item | Have? | Switch |
|---|------|-------|--------|
| C26 | Update downloads in the background with `UpdatePill` (progress + speed), then "Xstream X is ready · Install"; kept APK (`apkCode`) reused; after the unknown-apps screen the installer opens on return (`installWaiting` + `canRequestPackageInstalls`) | Partly: StreamX reuses a finished download (`dlCode`/`dlLen`, TV 0.9.12), has the TV dialog and the relay; no pill / ready dialog. | `updatePill` |
| C27 | Update client uses the phone's DNS first (nearest GitHub file server) | No; StreamX uses its Cloudflare relay instead (Xstream has no relay, see brand `UPDATE_CDN`). | `updatePhoneDns` |
| C28 | `minPhone` → "Update required" | No (B1). | `accessGate` |

### Music (Music.kt, MusicApp.kt, MusicActivity.kt, phone manifest)
| # | Item | Have? | Switch |
|---|------|-------|--------|
| C29 | Xstream Music: own launcher entry (`app/src/phone/AndroidManifest.xml`, MusicActivity, taskAffinity `com.example.xstream.music`), `MusicService` (media notification, lock screen), `MusicWidget` + `MusicWidgetControl`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission, MusicLog | Code is in StreamX too but `HAS_MUSIC = false` and the manifest entries are missing. | `music` (needs manifest entries in a flavor/brand manifest, not a runtime switch) |

## D. Same feature, different behaviour (StreamX wins in 0.9.0)

- **AI off once**: StreamX key `aiWakeOff0974` (aiWake → no), Xstream `aiOff0819` (aiWake + wakeOn → no). Xstream users get
  StreamX's one-time reset once more on 0.9.0.
- **Looks on first start**: StreamX `allLive0975` (All Live once per profile) and FirstLook `look0976`; Xstream used
  `lookChosen`. Xstream users will see "Choose your look" once more.
- **Phone keeps look keys on sync** (`LookDeviceKeys`, StreamX 0.9.78) — new for Xstream users.
- **Detailed logs off by default on phones** (StreamX 0.9.79 `ActLog.detailed`); Xstream logged everything.
- **Quiet start** (TvIdle touches, 0.9.79), **Glass** theme, **TrailerInCard**, **late plugin answers** (`sharedK`),
  **PluginSources.focus** (stop other titles' searches), **seek index / audioWeak** — all StreamX, now in Xstream too.
- **Startup timing**: StreamX's splash timing kept; only the letter order is branded (X first for Xstream).
- **Invite parsing**: only this app's scheme/host (`Brand.INVITE_*`). Xstream 0.8.x also accepted `streamx://join`.
- **NO_PUBLISH**: kept `true` in Xstream's phone build (as before), `false` in StreamX.
- **Flavors**: Xstream's `universal` flavor and the `full` source set are gone; the phone APK has Vosk + ML Kit like StreamX.
- **TV**: the tv flavor is StreamX TV 0.9.15 code (Xstream TV was 0.7.2 beta); Xstream's workflow does not ship TV.

## E. Where branding lives (after Step 1)

All in the brand layer; the shared code reads `Brand` (Brand.kt) / manifest placeholders / resValues.

| What | StreamX | Xstream | Set in |
|------|---------|---------|--------|
| App name (`BRAND_NAME`, `app_name`, `brand_name`) | StreamX / StreamX TV | Xstream / Xstream TV | app/build.gradle.kts (`brand` map, resValue per flavor) |
| `IS_BETA` | false | true | app/build.gradle.kts (`brandBeta`) |
| Update repo (`UPDATE_REPO`, `Brand.REPO_URL`) | callmerickk/StreamNest | callmerickk/Xstream | app/build.gradle.kts |
| Update relay (`UPDATE_CDN`) | https://streamx-dl.callmerick093.workers.dev | "" (GitHub only) | app/build.gradle.kts |
| Release assets (`APK_PHONE`, `APK_TV`) | StreamX-phone.apk / StreamX-TV.apk | Xstream-phone.apk / Xstream-TV.apk | app/build.gradle.kts + .github/workflows/build.yml |
| Cloud shelf (`SHARED_ROOT`, XNs.ROOT, Official PATH) | shared | xshared (reads fall back to shared) | app/build.gradle.kts |
| Invites (`INVITE_SCHEME/HOST/PATH`, manifest `${inviteScheme}` `${inviteHost}` `${invitePath}`) | streamx://join, streamx-watch.pages.dev/join | xstream://join, streamx-home.callmerick093.workers.dev/xjoin | app/build.gradle.kts |
| Widget label / text (`${brandName}`, `widget_start`) | StreamX | Xstream | app/build.gradle.kts |
| Logo colours (`logo_signal`, `ic_fg_signal`) | white X, red waves (src/main) | red X, white waves | app/src/brand/res (Xstream only) |
| Wordmark / splash letter order | "Stream" + red X | red X + "stream" | `Brand.X_FIRST` (from the name) |
| Log app name (`ActLog.appName`) | StreamX Mobile / TV | Xstream Mobile / TV | from `Brand.NAME` |
| applicationId, versions, signing | com.example.streamnest, 0.9.79/89 | com.example.xstream, 0.9.0/60 | app/build.gradle.kts |
| Release notes | Changelog.kt, RELEASE_NOTES.md | own Changelog.kt, RELEASE_NOTES.md | brand files (not synced) |
| Project name, workflow, min-version.txt, docs | StreamNest | Xstream | brand files (not synced) |

Not branded on purpose (shared protocol / storage keys, identical in both): Firebase project `streamx-d976a`, Worker
`streamx-home` routes (`/bundle`, `/p`, `/turn`, `/find`), `xshared/logs` and `xshared/tvlogs`, `streamx://tvlink` QR
payload, prefs names (`streamx_cloud`, trailer keys), profile PIN salt, theme style names `Theme.StreamX*`, the
"streamx" home-style key, the log browser's app list (knows all four apps).

## F. Open points for Step 2

1. Done (0.9.1): the StreamX tree gets the shared code from `tools/sync-to-streamx.sh`; its Gradle file carries the
   StreamX brand map (`BRAND_NAME "StreamX"`, `brandBeta = false` …). Proof: StreamX 0.9.79 built from the shared code.
2. Done (0.9.1): Music's manifest entries are in `app/src/brand/phone/AndroidManifest.xml` (Xstream only).
3. Done (0.9.1): `accessGate` reads versions.json `minPhone` (Xstream's workflow writes it from `min-version.txt`).

---

## Port status (Xstream 0.9.1, shared-base)

"Ported" = in the shared code behind its switch, on in Xstream, off in StreamX unless in `Feature.STABLE` (0.9.1: only `updatePill`).
"Kept StreamX's" = StreamX's own equivalent stays, Xstream's version was not brought back. "Not ported" = left out
for now (listed so it isn't forgotten).

| # | Item | Status | Switch | Notes |
|---|------|--------|--------|-------|
| A1/B2 | CloudFind, cancellable | Ported | `cloudFind` | CloudFind.kt; phone only |
| A2 | Ask once (addonStreamsFlow) + refused cloud link → direct ask | Ported | `cloudFind` | `addonStreamsFlow` → `cloudStreamsFlow` (on) / `directStreamsFlow` (off, StreamX's body unchanged); Prepare extraL / ≤8 s wait |
| A3 | PluginGuard | Ported | `pluginGuard` | good first candidate for STABLE |
| A4 | saveParts 4/8, pixeldrain 2, single hosts 7 days, racedProbe 60 s, runner-up probe, 25 ms poll | Ported | `smartParts` | |
| A5 | DetailTiming + green breakdown | Ported | `detailTiming` | logging only; good STABLE candidate |
| B1/C24/C28 | Access gate, Access requests (Settings › Advanced, developer), minPhone "Update required" | Ported | `accessGate` | approval stays off via `access/config/required` |
| B3 | Donate bubble / payments | Not ported | (`donate`) | 244 lines + Developer's Corner page; not cheap |
| B4/C6 | LinkMemory (disk, 4 h) + Prepare seed + CW idle refresh | Ported | `linkMemory` | Xstream's Home `lookAhead` not brought back: kept StreamX's 0.9.79 "work on intent" |
| B5 | LiveBar | Kept StreamX's | none | PhoneOrbitBar |
| B6 | MicHold | Kept StreamX's | none | in WakeWord.kt |
| B7 | MusicLog ("MUSIC" log lines) | Not ported | (`music`) | logging only, touches many Music files |
| B8 | ProfileHorizon | Kept StreamX's | none | PhoneHorizon |
| B9 | SxDeck | Kept StreamX's | none | Deck |
| B10/C13 | Taste: "What do you like to watch?" + Anime / K-Drama rows | Ported | `tasteRows` | "New for you" / language pills code is there but (as in 0.8.20) not on Home |
| B11/C21 | TastePick title picker + tmdbDiscover | Ported | `tastePick` | uses StreamX's PhoneHorizonGround; TMDB tall-poster memory (`tmdbPosters` on disk) not ported |
| C1 | memTight "Phone memory is full" prompt | Kept StreamX's | (`memPrompt`) | StreamX's MemWatch + HiddenCap are quieter and newer |
| C2–C5 | PlayerCount room, next-episode 75 %, quality sheet, fake-video order | Kept StreamX's | none | |
| C7 | Live server green light | Not ported | (`liveServer`) | HAS_LIVE is false in both apps |
| C8 | HostDown | Ported | `hostDown` | only inside ActLog.Net when on; every request untouched when off |
| C9 | DNS "private only" chain | Kept StreamX's | none | phone DNS first (agreed fix 5) |
| C10 | Cloud sync waits quietly offline | Not ported | (`syncWaitOnline`) | not in the 0.9.1 list |
| C11 | Watch together rejoin (2 min), guests wait 2 min, TOGETHER logs, "This room has ended" | Ported | `roomRejoin` | StreamX's call relay (TURN, Call.status) kept |
| C12 | Log upload path | Kept StreamX's | none | same place |
| C14 | Long-press poster lift with trailer | Kept StreamX's | none | StreamX has its own lift + TrailerInCard |
| C15 | Dock hides after 4 s | Not ported | (`dockAutoHide`) | StreamX's bar is a different component; not cheap/safe blind |
| C16 | TmdbOpening | Not ported | (`tmdbOpening`) | StreamX resolves collection items (realTitles) |
| C17 | First start sign-in | Kept StreamX's | none | |
| C18 | Ticket / round-avatar profile picker | Not ported | (`ticketPicker`) | look only; Horizon kept |
| C19 | Per-episode saved places, Play-button layout | Not ported | (`episodePlaces`) | |
| C20 | Trailer quality by network | Not ported | (`trailerByNetwork`) | YtDirect differs; StreamX has its own pick |
| C22 | Posters+ in Live-only / Picked for you | Kept StreamX's | none | |
| C23 | Adult / Trailers on cards / Auto-play toggles | Not ported | (`extraToggles`) | |
| C25 | Bottom bar / Look picker | Kept StreamX's | none | |
| C26/C27 | Update pill + ready/Install dialog + installer after allowing unknown apps + phone DNS first | Ported · **approved for stable 24 Nov** (in `Feature.STABLE`) | `updatePill` | StreamX's own finished-download reuse (dlCode/dlLen) and relay kept |
| C29 | Xstream Music (launcher, service, widget) | Ported | `music` + `HAS_MUSIC` | manifest in `app/src/brand/phone` (Xstream only) |
| D | Invite links of both apps | Ported | `inviteBoth` | Xstream also registers `streamx://join` (brand manifest) |

Not switched (wording for all apps, 0.9.1): "saved video" / "start saved" → "cached video" / "cached" in user-visible text.

### Step 3 (Xstream 0.9.2 / StreamX 0.9.80)

| Item | Status | Switch | Notes |
|------|--------|--------|-------|
| Glass never black, off under the player, lighter list blur | Fix, both apps | none | Theme.kt (GlassBg, GlassBackdrop, SoftImage), Screens.kt, MainActivity.kt, AppViewModel.applyPalette |
| Frame lines by real refresh rate | Fix, both apps | none | MemWatch.kt FrameStats |
| Continue Watching 2-column grid page | Fix, both apps | none | Home.kt ContinueAllPage / ContinueCard "grid" |
| Wrong film behind the right name (URL file name) | Beta only | `fileNameCheck` | StreamFilter.kt TitleMatch.fileNameProblem |
| pixeldrain 403 = busy | Fix, both apps | none | StreamCache.probe (LinkProbe.busy), Prepare.kt, PlayerScreen.tryNextSource |
| Source Test: seek re-check, ready time, wrong film, pixeldrain pacing | Fix, both apps | none | SourceTest.kt |
| Firebase Crashlytics + Performance | Both apps, phone + TV | none | top build.gradle.kts (synced), app/google-services.json (synced), CrashReports.kt |
