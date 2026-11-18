# Xstream — parked plans and next build

## Parked — talk about later (user asked to remind)

### Calls from chat (audio + video)
- Reuse Call.kt (WebRTC, stream-webrtc-android, BSD) from Watch together; 📞 / 🎥 in chat, full-screen ringing,
  mute / camera / switch / speaker, picture-in-picture, "turn into Watch together", missed-call notification, history in chat.
- Ringing with the app closed needs FCM push sent by a small server holding the key. Options discussed:
  Firebase Cloud Functions (Blaze plan, card, free quota) — or Vercel / Deno Deploy with GitHub login (no card).
  Not OneSignal (key in app), not a background socket (realme/vivo kill it), not GitHub Actions (30–60 s).
- TURN relay for mobile data: Metered Open Relay / ExpressTURN free tier, or coturn on Oracle Always Free, or none at first.
- 1-to-1 first, groups later. Ask: Xstream only or StreamX too.

### Rich notifications (like Netflix's)
- BigPicture + poster thumbnail + 3 buttons. Kinds: new episode, Picked for you, finish what you started,
  cinema → now online (Remind me), new this week. Max 1 "picked"/day, quiet 22:00–09:00, none for Kids, per-kind toggles.
- Links prepared before posting so Watch is green. Builds on EpisodeAlerts (6-hour worker).
- Mockup image only when the user asks.

## REMIND THE USER right after the next Xstream mobile build is done
"Integrate the new features into **StreamX TV** (not a new Xstream TV) — StreamX TV already has good options and runs
buttery smooth; how to bring in: new Home looks (Live / Cascade / Classic) with a side bar, Look settings with live
preview, first-run Choose your look (OK = animated preview, OK again = keep, Back = choices), Horizon profile page,
quick start with little data, seek preview thumbnails, Watch next corner, Next episode ready — and how to make it better."
The TV look plan canvas (claude.ai artifact "Xstream TV look plan") has the boards; adapt them to StreamX TV.

## Next Xstream mobile build (0.8.7) — merged list, build only when the user says "build"
Fixes from logs
1. OOM crashes: buffer budget from Runtime.maxMemory() (≈35 % of heap), split when a 2nd player (swap / preload / Watch next) runs.
2. HostDown: never 127.0.0.1 / private IPs; ignore when ≥3 hosts fail within 5 s (network drop); log once per host.
3. Watch next card: hand over as soon as a link works (≤6 s); smaller landscape card further in the corner with a progress line; tap = normal loading screen.
4. Continue watching: last episode / movie ≥90 % (or credits) → out of the row and into Watched (VisionQuest S1E8 stayed).
5. Search: hide addon error items (ids "aiostreamserror", names with [❌]); collections + posters/years from TMDB / Cinemeta.
6. Back = one step back (search results, collection), keep query + scroll.
Home / look
7. Live: smaller IMDb badge; More Info replaced by round ▶ Trailer + ＋ My List (labels under); no automatic trailers; tap = 720p/1080p in the card, pause/mute while playing.
8. Rename the "StreamX" home style (Cascade?) — user to confirm the name.
9. Look pickers + first-run "Choose your look" with moving previews copied from the real three styles (screenshots 19 Nov).
Player (keep the current layout)
10. Remove the source icon. Watch together + VR + ⋯ go inside a ‹ button that slides them out leftwards for ~3 s at start, then folds back.
11. Red dot on the progress bar. While seeking hide everything except the bar + preview thumbnail.
12. Quality sheet: Auto, 4K 60, 4K, 1080p 60, 1080p, 720p, 480p… playing row always marked, 60 fps yellow badge (StreamX TV), "Other streams" folded; list prebuilt so it opens instantly (as fixed on StreamX TV).
13. Quality changes by fade (frame-matched swap); a freeze + small ring only when unavoidable — never the loading screen.
14. Slow internet on file sources (Vega / 4KHDHub): fade to HLS Auto; back to the file after ~3 min stable fast; menu keeps the chosen quality.
15. Touch zones copied from StreamX: dead top/bottom strips, narrower brightness/volume, middle swipe-up = Watch next (one-hand).
16. VR: new headset icon (A unless the user picks another); "Fixed screen" → "Look around" (360° icon, on by default?).
17. Post-credit scene button (TMDB stinger keywords + credits detection) — not built before.
18. Seek preview thumbnails per title (HLS first, MKV/MP4 anchor-aligned) — not built before.
Logs
19. Log browser apps: Xstream Mobile, Xstream Live, StreamX Mobile (needs StreamX to send to the shared logs), StreamX TV (now shown as "Xstream TV").

### Updates to the 0.8.7 plan (19 Nov, 05:04)
- 10 → the ‹ button sits between the clock and the lock; ICONS ONLY (Watch together, VR headset) slide out leftwards ~3 s at start and back in. No ⋯ there.
- 12 → keep today's quality sheet frame but narrower (fits content); no source name in the main list; only the red tick marks what plays; badge text "60fps" in full; Other streams (folded) shows sources with the same red tick on the playing one.
- 13/14 + HLS itself: within HLS, drop 1080p → 720p → 480p and back automatically with bandwidth (like Netflix/Hotstar), never a loading screen.
- 20. Poster cache: posters/backdrops kept on disk; at launch Home shows from cache at once (no shimmer), refresh in background, cached set shuffled for next launch.
- 8 → "Cascade" rejected — offer new names.
- 16 → VR icon = headset (A). Look around OFF by default (user turns it on).

### Quality sheet — final (19 Nov, 05:21)
- Main list always: Auto · 4K 60fps · 4K · 1080p 60fps · 1080p · 720p · 480p. Tick colour on the playing row: RED = HLS,
  YELLOW = MovieBox / other plugin streams, GREEN = high-quality files (VegaMovies, RogMovies, 4KHDHub).
- Other streams: UNCHANGED from today (groups + long-press to list links); only rename groups "1080p" → "Full HD",
  "Unlabelled" → "Mid HD".
- Chip next to the lock = the playing quality (4K, 4K 60fps, 1080p 60fps, 1080p, 720p, 480p); "Auto" while it keeps
  switching on unstable internet; the real quality again once playback is steady.
- Home style "StreamX" renamed to **Stack** (user approved).
