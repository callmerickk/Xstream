# Next build (after 0.6.0) — user requests 27 Oct 15:10
1. Numbered rows: keep design, but give numbers their own space (Netflix-style: number fully visible beside poster, not hidden behind it). Mix Bollywood/Hindi titles into the trending rows.
2. Search: default view = Discover mosaic (like 0.6.0 Discover) WITH a Surprise me button; moods row can go/compact. Recent searches only shown when the search bar is focused/tapped.
3. Search results: collection card (e.g. Harry Potter series deck) not showing — fix SearchState.groups for "Harry Potter".
4. Mosaic: year/type line shown only on landscape small tiles — also show on big portrait tiles.
Plus leftover 30-point items: smart downloads, same-name OTT plugin separation, Vivo fallback engine.

## 28 Oct — done in StreamX 0.9.55 (build-114) and back-ported to Xstream (pushed, not published)
Spaced numbers + Hindi mix, Discover search + Surprise me + recents on focus, year on big tiles, AI card keyboard,
plugin warm-up delay, VLC state thread, vivo safe decoders + device report, quality menu, typed keys kept, bell size,
downloads bubble, bottom bar hidden in Library calendar/chat, wake model Wi-Fi only.
Still open: collection cards check (needs working TMDB key), smart downloads, same-name OTT plugins, subtitle sync confirmation.

## Reminders for Rakesh (saved 28 Oct 2026, 17:22)
- FIREBASE RULES NOT UPDATED YET: shared/logs (write for signed-in users), shared/devDefaults (owner write, signed-in read), vpnPeers. Remind him every release.
- XSTREAM MOBILE MUSIC (idea, not final): Music pill on Home, YouTube-Music-like player (Song/Video, Up next filters, synced lyrics, Related, full ⋮ menu),
  Library Videos/Music switch with imported playlists, Developer's Corner › Music sources with user-facing names, Listen together.
  He will provide a CloudStream-style music repository/plugin link; no Spotify/YouTube APIs. Discuss when he sends the link.
- VPN: leave as is (no developer cloud servers).

## Discussion parked (29 Oct 2026, 05:19) — remind Rakesh
- Speed/light ideas (StreamX): measure PlayTiming per device first; Lite mode (auto on low-RAM / TV < 2 GB: no card trailers, hero video, blur,
  seek thumbs, Elite prefetch; load only Quick Play plugins; 3 parallel searches; smaller image cache); Baseline Profile (profileinstaller is in,
  but no baseline-prof.txt); shared "which provider worked" hints in Firebase; play-first-then-upgrade (2.5 s budget); preconnect to likely hosts
  on title open; skip probes for known types; lower Exo start buffer; VLC optional on TV (APK ~57/55 MB).
- Merge plugins + addons: one pool, one ranking, tiers (fast 3–5 first, then backups), concurrency by device; behind a Developer's Corner switch first.
- PencariMovie: bot-only login (no personal cloud), files indexed by pencarimovie.com (WordPress) + leased guest bots; MIT; Rust (axum + grammers),
  ~30k lines + 19 MB dashboard. Discussing: Telegram source inside the C app (xstream-tv), TV only, with CloudStream on the Android side.

## NEXT VERSION — Smooth trailers like Nuvio Enhanced (saved 29 Oct 2026, 07:52) — DO NOT BUILD until Rakesh says "build"
Source studied: github.com/yesnt10/NuvioMobile-Enhanced (InAppYouTubeExtractor.kt, TrailerExtractionPlatform.android.kt,
YoutubeChunkedDataSourceFactory.kt, HeroTrailerPlayerSurface.android.kt, HomeHeroSection.kt).
Applies to StreamX Android (YtDirect.kt / Trailer.kt) and web StreamX (hero cards, title page background, Trailers tab, which use the YouTube iframe today).
1. Watch-page first step: read INNERTUBE_API_KEY + VISITOR_DATA, send x-goog-visitor-id (stops "sign in / not a bot" refusals).
2. Clients: VISIONOS first, then ANDROID 20.10.35, IOS 20.10.1 (update ours: ANDROID 19.44.38 is old); ask ALL, pool the answers.
3. Pick best: HLS manifest best variant vs progressive vs separate video+audio; prefer the original (default) audio track over dubs; mp4 over webm.
4. Link check: probe googlevideo URL with Range bytes=0-0; try the backup servers in the "mn" param in parallel, first that answers wins (2 s).
5. Chunked download: add YouTube's &range=start-end in 10 MB pieces (no throttling / stalls).
6. IPv4-first DNS for YouTube hosts (IPv6 to googlevideo slow on Jio etc.).
7. Reveal: trailer invisible over the backdrop, 420 ms fade-in only at READY / first frame; on error or end fade back to backdrop. Never show a black box or YouTube bars.
8. Embedded YouTube player only as the last fallback, and kept hidden until it is actually playing.
9. Zoom & full screen: our own video surface fills the card edge to edge (cover-crop, no black bars), optional zoom (e.g. 1.35x hero option B);
   full screen continues the same playback (no reload).
10. Web StreamX: server endpoint /api/trailer/:id doing 1-6 (cache 3 h), play via our proxy (HLS with hls.js, or the muxed mp4), hidden <video> + fade-in
    in hero cards, title page and Trailers tab; proxy turns Range into &range= pieces for googlevideo.
11. Android StreamX: port 1-8 into YtDirect.kt + a chunked OkHttp DataSource; keep Piped/Invidious mirrors as fallback before the WebView.
12. Test: YouTube is blocked in the Claude workspace, so Rakesh tests on PC / phone. Clients need refreshing every few months.
