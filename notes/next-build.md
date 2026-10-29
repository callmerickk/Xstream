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
