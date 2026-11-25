# Xcoder AI — AI worker app — plan saved 26 Nov 2026 (name chosen; launch animation: X zoom-in like Xstream)
Goal: Claude (head) plans + writes prompts → workers (Arena + other playground sites + API keys from the owner's OmniRouter)
answer → Claude checks, fixes, builds. No manual copy-paste.
- Bridge = private GitHub repo (only thing Claude's session can reach): jobs/<id>/prompt.md (+code, code words only) → status
  waiting → worker writes answer.md → done. Big jobs split into parts; several jobs run at once on different workers.
- Workers: (1) Arena in a headless browser (email/password login, owner handles site changes + policy);
  (2) "+" add any playground website (teach mode: input, send, stop, reply area); (3) API keys via OmniRouter (OpenAI-compatible),
  Gemini; router with fallback + alias table; parallel: different prompts to different workers.
- Runs in the cloud, phone not needed: always-on free VM (Oracle Cloud Always Free ARM) with headless Chrome (Playwright) for
  websites + API calls; GitHub Actions as backup for API-only jobs. Phone/Windows app = viewer + manual chat (Claude-like UI,
  own name/logo), shows every job, worker, model, status.
- Never send personal data: code words (tools/cw.py), no keys/emails.
- Order: after the Music prompts. Step 1 jobs repo + format, 2 API worker (GitHub Action), 3 cloud browser worker (Arena),
  4 phone/Windows app.
- Files: attach any file (txt, zip, code, PDF) → app reads it and pastes the text into the prompt, split into parts.
- Several accounts per site (separate browser profile per account), auto-switch when one hits its limit.
  ChatGPT + Gemini websites as Arena backups. Better/official: Codex CLI (ChatGPT login) and Gemini CLI (Google login)
  as workers on the cloud VM.

## 26 Nov — owner decisions
- Claude = AI manager (plans, splits, reviews). Workers: Arena, other Claude site account (free Sonnet), ChatGPT (2 accounts, consent),
  Gemini, OmniRouter API keys, Codex/Gemini CLI. As many as possible, in parallel.
- Start without cloud: always-on Windows PC / spare phone = worker host; GitHub schedule later. Phone keep-alive like the
  PencariMovie server app: foreground service + permanent notification + battery-optimisation exemption + auto-start prompt (realme).
- Pipeline idea: job → worker → answer auto-applied on a branch → GitHub build → compile errors auto-sent back to the same worker
  (max 3 rounds) → only a green build reaches Claude for review. Same job to 2 workers for important parts; Claude picks the better.
