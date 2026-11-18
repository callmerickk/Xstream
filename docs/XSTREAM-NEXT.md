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

## Next build — TV gets the new Xstream + "Choose your look" on first run (phone too)
See the plan discussed on 19 Nov 2026 (TV look plan artifact).
