# Xstream Music — plan (saved 25 Nov 2026, keep reminding the owner)
- Today: catalogue/search/home/up next/lyrics/related = YouTube Music web API directly (YtMusic.kt, music.youtube.com/youtubei/v1);
  audio = YtDirect (direct YouTube); SimpMusic CloudStream plugin = backup only (MusicUi.kt).
- 0.9.8: every Music action logged (MUSIC lines: song start + source + reason + start time + format, finish/leave, skips, seeks, pauses,
  buffering, errors, quality, taps, pages, library, widget/lock-screen buttons, sign-in state); separate "Xstream Music" log section in
  Accounts (share music logs alone); cloud copy in streamx-logs under logs/<day>/music/.
- Next Music build: YouTube Music sign-in inside the app (built-in Google page, session kept on the phone only, never sent to our servers):
  library, liked songs, playlists, history, mixes, subscriptions; likes/adds sync back. Warn: unofficial; suggest a secondary Google account.
- Then: owner lists Music problems (or from a day of MUSIC logs) → fix list here.

- Xstream 0.9.9 (25 Nov 2026): no Music changes (Music untouched in this build).

## Big Music redesign — agreed 26 Nov 2026 (prototype: claude.ai artifact "Xstream Music prototype")
Workflow until Tue 1 Dec 1:30 am: I write one prompt at a time → owner runs other AIs → I check, add what's missing,
build in GitHub Actions, publish, tell owner. Big fixes go back as prompts. Xstream first, behind Feature switches.

### Prompt 1 — quick fixes
- Lyrics: Roman script by default (Android ICU "Devanagari-Latin"/"Any-Latin" + cleanup: drop final schwa "tuma"→"tum",
  strip accents), options Roman / original script / English translation (existing).
- Heartbeat seek bar: moving dot = accent red.
- HD covers everywhere: pick the LARGEST thumbnail (thumb() takes the last one today), handle webp/i9.ytimg, video frame
  fallback maxres→hq720→sd→hq; sharpen saved Library / Speed dial / first-song URLs at display time (ytHd in Art()).
- Back = one step (Up next/Lyrics/Related → Now playing → Home).
- Up next different every time: keep the tapped song first, shuffle the rest, sometimes another mix flavour
  (Popular / Discover / Deep cuts params).
- Remove Samples tab completely.

### Prompt 2 — orbit pill + profiles + Google sign-in
- Xstream mobile orbit bottom bar, same style: Home, Explore, Library, live GIF profile picture.
- Playing song tucks into the pill (swipe Now playing down / back); swipe the pill sideways → tabs (Dynamic Island feel).
- Profile tap → profile page with analytics like Xstream's (time listened, songs finished, liked, streak, "your week" chart,
  most played, share my week, taste chips). Press-and-hold → switch profile; each profile opens its own Music home.
- Google sign-in in this build (after the splash, skippable): each profile its own Google account or Xstream's own library.
  Home rows exactly as YouTube Music (Quick picks, Listen again, Mixed for you, videos, charts, new releases, albums,
  from your library, artists, long listens, community playlists, covers/remixes…). Warn: unofficial, second account safer.
- Top right: Search + Downloads (profile moved to the pill).

### Prompt 3 — search exactly like YouTube Music (owner's screen recording 26 Nov)
- Empty: "Recent searches" as a row of covers; "You may also like" suggestion rows (🔍 text, ↖ fills the box).
- Typing: live suggestions; top 2 text suggestions, then songs/albums/videos with cover + "Song · artist · plays" + ⋮,
  then more text suggestions. Mic button + song recognition button. Clear ×.
- Album/artist page like YT Music (big art, description …More, download / save / play / comments / ⋮, numbered tracks with plays).

### Prompt 4 — new logo + opening animation
- Logo approved? (preview sent 26 Nov: glossy 3D red ribbon eighth note + signal waves + red "● MUSIC" pill, dark tile).
  Files: /home/claude/out/logo/icon.svg, splash.svg. One logo only (old icon had two). Use everywhere a logo shows.
- Animation = exactly Xstream Live's: icon appears, X zooms in, signal waves pulse, "MUSIC" pill pops in where LIVE was.
  (The prototype zoomed OUT — wrong.)

### Prompt 5 — Home look + themes
- Hero "For you now" card coloured from the cover; separate Music look setting: Elegant/Midnight/Forest/Plum/Sunset,
  Glass (frosted over the blurred cover), Follow the song. Not shared with Xstream's theme.
- Premium touches: cover glow, cross-fades, mini player in cover colour, muted looping video on Now playing, full-screen lyrics.

### Prompt 6 — Listen together
- Like Watch together (rooms, invite, ring a friend, chat, bubbles), synced play/pause/seek/queue, everyone can add to the queue,
  host controls, reactions. Reuse Together/room code; same Firebase rooms rules.
