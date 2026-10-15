# Prompt for building a Xstream plugin (paste into a new chat, together with PLUGINS.md)

You are writing a plugin for Xstream, an Android streaming app. A Xstream plugin is ONE JavaScript file that the app
runs with the Rhino engine (ES5 style; arrow functions and let/const are OK; no classes, template strings,
destructuring, spread, async/await, fetch, setTimeout, DOM, Node modules or Java access).

The full format is in PLUGINS.md (attached). The essentials:

1. Define `var plugin = { id, name, version, api: 1, description, author, types: ["movie","series"] }`.
2. Define `function getStreams(req)` that returns an ARRAY of stream objects (empty array when nothing is found).
   `req` = { type: "movie"|"series", id: "tt…" or "tt…:season:episode", imdbId, season, episode, title?, year? }.
3. Use only these helpers: `http.get(url, headers)` and `http.post(url, body, headers)` — synchronous, they return
   `{ status, url, body, headers }`; `log(message)`; `atob` / `btoa`; plus standard JSON / RegExp / string functions.
4. Each stream object: `{ url, name, title, headers?, behaviorHints?: { filename, videoSize, bingeGroup }, subtitles? }`.
   - `url` must be a direct video file or an HLS (.m3u8) / DASH (.mpd) link that plays as-is (never a web page).
   - Put the quality (2160p/1080p/720p/480p) and audio languages (Hindi, English…) in `name` / `title` / filename.
   - `headers` = request headers the video needs (Referer, Origin, User-Agent, Cookie…) — they are sent for the
     video and for every HLS/DASH piece.
   - `bingeGroup` = a stable label for the server/provider (used to rank providers).
5. Finish within 25 seconds; ask as few pages as possible. Catch errors and `log()` them; return [] on failure.

Write the complete plugin file for: [DESCRIBE THE SOURCE YOU HAVE THE RIGHT TO USE, e.g. "my own Jellyfin server at
https://… with API key …"], with clear comments, and explain how to test it in Xstream › Settings › Content & Discovery ›
Plugins (Install by pasting the code, then Test).
