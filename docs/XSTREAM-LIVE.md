# Xstream Live (planned separate app)

Removed from Xstream in 0.8.0 because plugins start streams faster. Kept here as the plan for a later app.

- Name: **Xstream Live**. 64-bit Android only (arm64-v8a).
- Streams come only from the Live server (PencariMovie Server, MIT, Rust), no plugins, no addons.
- Build it from the old `phone` flavor setup: `tools/build-live-server.sh` builds `libxlive.so` into
  `app/src/phone/jniLibs/arm64-v8a/`, version in `tools/live-server.tag`; `LiveServer.kt` starts it with
  `--workers 8 --inflight 32`, `PM_PIPELINE=32` on 127.0.0.1:8088+; `LiveServerUi.kt` hides the update pill and tunnel tab.
- Daily tag watcher: `docs-live-server-watch.yml.disabled` (move back to `.github/workflows/` in the new repo).
- Known: starts in ~3–13 s, seeks 7–26 s. Ideas: bigger read-ahead, measure per-file speed, prefer lighter files.
- Never mention "Telegram" in any text the user can see.

## Next for Xstream Live (agreed 17 Nov)
- VR headset mode (JioDive / Cardboard-style): player button "VR headset" → side-by-side picture for the two lenses on a
  big virtual screen (OpenGL, lens distortion), landscape locked, screen kept on, controls by tap or a Bluetooth remote.
- Size: optional removal of offline voice (Vosk 8.5 MB) and face detection (7.9 MB) → ~40 MB.

## Locked 17 Nov — Xstream Live 1.0.2 (data and seek fix)
- Live server started like Nuvio's: workers × inflight = 4 × 8 on Wi-Fi, 2 × 4 on mobile data (was 8 × 32).
- Live server links: no seek-thumbnail prefetch, no whole-file (no-range) request, no hidden swap player.
- Player buffer ~60 s ahead, minimal back buffer.
- Trailers only on the home card; 360p on mobile data, 720p on Wi-Fi.
- Plugin warm-up at start fully blocked in Live-only mode.
