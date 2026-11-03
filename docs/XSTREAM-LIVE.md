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
