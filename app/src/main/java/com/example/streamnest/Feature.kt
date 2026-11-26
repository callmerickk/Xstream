package com.example.streamnest

/**
 * Named on/off switches for features that are new or Xstream-only. In the beta app (Xstream) every switch is on;
 * in the stable app (StreamX) a switch is on only when its name is in [STABLE]. Add a switch here, check it where the
 * feature starts, and add its name to [STABLE] once it is approved for StreamX (see docs/HOW-SWITCHES-WORK.md).
 * A switch that is off must leave StreamX's own code path exactly as it was: no network call, no screen, nothing kept.
 */
object Feature {
    /** The switches approved for the stable app. The only place to turn a feature on in StreamX.
     *  0.9.1: updatePill approved for StreamX by the owner (24 Nov 2026). */
    private val STABLE: Set<String> = setOf("updatePill")

    private fun on(name: String): Boolean = Brand.IS_BETA || name in STABLE

    /** 0.9.1: Cloud link finder (CloudFind.kt): one cloud request asks every addon at once, an addon the cloud answered
     *  isn't asked again, and a refused cloud link makes its addon be asked directly. Phone only. */
    val cloudFind: Boolean = on("cloudFind")
    /** 0.9.1: CloudStream plugins can't download whole videos into memory (PluginGuard, CsPlugins.kt): the big-file crash. */
    val pluginGuard: Boolean = on("pluginGuard")
    /** 0.9.1: smarter saving (StreamCache.kt / Prepare.kt): 4 or 8 pieces, pixeldrain 2, hosts that refuse extra
     *  connections remembered 7 days, race answers reused 60 s, runner-up checked in the background. */
    val smartParts: Boolean = on("smartParts")
    /** 0.9.1: title-page timing lines in the activity log (DetailTiming): link ready, light green, green breakdown. */
    val detailTiming: Boolean = on("detailTiming")
    /** 0.9.1: invite-only access (Access.kt) and "Update required" from versions.json minPhone. */
    val accessGate: Boolean = on("accessGate")
    /** 0.9.1: links that started fast are remembered 4 h (LinkMemory.kt); Continue watching re-looked-up when idle on Wi-Fi. */
    val linkMemory: Boolean = on("linkMemory")
    /** 0.9.1: Music (its own launcher entry, background playback, widget). Also needs HAS_MUSIC and the brand manifest. */
    val music: Boolean = on("music") && BuildConfig.HAS_MUSIC
    /** 0.9.1: "What do you like to watch?" once per profile + Anime / K-Drama rows on Home (Taste.kt). */
    val tasteRows: Boolean = on("tasteRows")
    /** 0.9.1: "What do you love watching?" title picker once per profile, after the taste step (TastePick.kt). */
    val tastePick: Boolean = on("tastePick") && tasteRows
    /** 0.9.1: a server that can't be reached twice in a row is skipped for 10 minutes (HostDown, ActLog.kt). */
    val hostDown: Boolean = on("hostDown")
    /** 0.9.1: Watch together: the host's room is rejoined after a restart (2 min), guests wait 2 min, TOGETHER log lines. */
    val roomRejoin: Boolean = on("roomRejoin")
    /** 0.9.1: phone update downloads as a small progress pill, then "Ready · Install"; installer opens after allowing
     *  unknown apps; the update file uses the phone's DNS first. */
    val updatePill: Boolean = on("updatePill")
    /** 0.9.1: invite links of both apps are accepted (streamx:// and xstream://, both web addresses), as in Xstream 0.8.x. */
    val inviteBoth: Boolean = on("inviteBoth")
    /** 0.9.2: a film link whose real file name (in its web address, or the addon's file-name hint) shows another film —
     *  another year (±1 allowed) or clearly another title — is dropped ("wrong title (file name)" in the log). */
    val fileNameCheck: Boolean = on("fileNameCheck")
    /** 0.9.3: every rule that decides play in one place (Rules.kt) — Developer's Corner › Rules: try on this phone,
     *  publish to everyone (xshared/devconfig/rules, read at start), history with Undo. Off = built-in defaults only,
     *  nothing read or written. */
    val devRules: Boolean = on("devRules")
    /** 0.9.3: "Ask about this" boxes in Developer's Corner (DevAsk.kt): the AI gets the section's logic book entry, its
     *  values and this phone's recent log lines; "What if?" re-ranks the recorded test results. */
    val devAsk: Boolean = on("devAsk")
    /** 0.9.3: Source Test › Combined: every addon, CloudStream and Nuvio plugin asked at once for ~10 titles, then
     *  Quick Play strategies replayed from the recording (ComboTest.kt). */
    val comboTest: Boolean = on("comboTest")
    /** 0.9.4: Netflix-style Home (HomeSteady.kt): what is on screen never changes while you look at it — fresh rows wait
     *  for the next open, the Top 10 order is fixed for the session, posters are fetched small while the profile page
     *  shows and each card shows its own colour instead of grey. */
    val homeSteady: Boolean = on("homeSteady")
    /** 0.9.4: links prepared in the cloud (Worker Cron + D1): the cloud's check of a link (size, seekable, < 10 min old)
     *  counts as the phone's own check; Continue-watching titles (/cw) and the link that really played (/good) are sent
     *  to the Worker — type + IMDb id and hashes only, never an account. Needs cloudFind. */
    val cloudPrep: Boolean = on("cloudPrep") && cloudFind
    /** 0.9.4: plugin mode runs only the light plugins (Rules › Plugins on the phone: MovieBox + the HLS / OTT ones);
     *  the heavy site scrapers run once, quietly, only when nothing playable came within "wait before backup". */
    val lightPlugins: Boolean = on("lightPlugins")
    /** 0.9.4: HDR mode (Quality › HDR): on = an HDR copy is found and faded in at the same moment (SDR first, so the start
     *  stays instant); off = back to the regular copy. Only on screens that can show HDR. */
    val hdrMode: Boolean = on("hdrMode")
    /** 0.9.5: big-screen mode (BigScreen.kt): tablets in landscape, laptops / PC emulators (MuMu), wide windows and
     *  keyboard-or-mouse devices get the TV interface (TV Home, side bar, TV settings, title page, search) with keys,
     *  mouse and touch at once; laptops / emulators with memory to spare get the top device tier (bigger video buffer).
     *  Off = the phone layout everywhere, buffers as before. */
    val bigScreen: Boolean = on("bigScreen")

    /** 0.9.7: data diet (DataDiet.kt): phone buffer 20–40 s / 60 MB, no background seek-thumb prefetch, Quality-menu
     *  files only after the sheet opens, gated "climbing", paced background saving, MovieBox answers kept 30 min,
     *  one data line per play. */
    val dataDiet: Boolean = on("dataDiet")
    /** 0.9.7: addon-first sources: Quick Play default = OTT plugins + CNCVerse; a quiet quality lane (CNCVerse Rocky,
     *  HdHub through the cloud finder) fades in a clearly better copy; relayed hosts remembered 24 h. Needs cloudFind. */
    val addonFirst: Boolean = on("addonFirst") && cloudFind
    /** 0.9.7: finished titles leave Continue watching by the credits / 92 % / minutes-left rule, plus a one-time pass. */
    val smartFinish: Boolean = on("smartFinish")
    /** 0.9.7: automatic video-cache cleaning (WorkManager + a start pass) and Home data kept in app storage. */
    val smartCache: Boolean = on("smartCache")
    /** 0.9.7: Glass: hero colour per slide, smoky sheet behind full pages, one-time re-apply after an update. */
    val glassPlus: Boolean = on("glassPlus")
    /** 0.9.7: a stream tapped by hand starts saving its first seconds while the player opens. */
    val fastManual: Boolean = on("fastManual")
    /** 0.9.8: Watch together 2 (Call.kt / Together.kt / TogetherUi.kt): free bubbles with pinch, framing rule, per-friend
     *  mute, "You" bubble, data saver, chat panel with emojis, movable react button, raise hand, joined / sync lines,
     *  rooms started from the title page, no info card in a room. */
    val wt2: Boolean = on("wt2")
    /** 0.9.10 */
    val askAhead: Boolean = on("askAhead") && cloudPrep
    val firstGood: Boolean = on("firstGood")
    val burstPlan: Boolean = on("burstPlan")
    val prepRescue: Boolean = on("prepRescue")
    val bgCap: Boolean = on("bgCap")
    /** Music prompt 1: Roman lyrics, red seek dot, sharp covers, one-step Back, shuffled Up next, no Samples, rounded mini player. */
    val musicFix1: Boolean = on("musicFix1")
    /** Music prompt 4: new Music logo (ribbon X + headphones + MUSIC pill), shortcut icon, Xstream-style opening. */
    val musicLogo2: Boolean = on("musicLogo2")
    /** Music prompt 3: YouTube Music style search (recent covers, live suggestions, top result, mic), album and artist pages. */
    val musicSearch2: Boolean = on("musicSearch2")
    /** Music prompt 2: orbit pill bar, profile page with stats, profile switcher, YouTube Music sign-in, Home top right = Search + Downloads. */
    val musicOrbit: Boolean = on("musicOrbit")
    /** Music prompt 5: premium Home (hero card, section titles with a reason line), Music look setting per profile (5 colour looks,
     *  Glass, Follow the song), Now playing glow / backdrop / canvas clip, lit lyrics, tinted pill. */
    val musicLook2: Boolean = on("musicLook2")

    // 0.9.6: the slim build. These four are NOT named switches: they follow BuildConfig.SLIM_LIBS (Xstream phone only),
    // the same value that leaves the native libraries out of the APK (app/build.gradle.kts), so a switch can never be on
    // in an APK that still needs the library, or off in one that lacks it. StreamX: SLIM_LIBS false in its brand file.
    /** 0.9.6: no VLC engine: ExoPlayer (phone decoders, then FFmpeg software video), then the next link; the VLC app
     *  (if installed) only as the very last resort. */
    val noVlc: Boolean get() = BuildConfig.SLIM_LIBS
    /** 0.9.6: "Hey Aiden" uses Android's on-device speech recogniser instead of Vosk (WakeWordSr.kt). */
    val noVosk: Boolean get() = BuildConfig.SLIM_LIBS
    /** 0.9.6: the face model comes from Google Play services ("Follow the speaker" hidden without them, FaceModelGms.kt). */
    val mlkitUnbundled: Boolean get() = BuildConfig.SLIM_LIBS
    /** 0.9.6: Watch together's native library is downloaded the first time a call starts (WebRtcLib.kt).
     *  0.9.9: OFF everywhere — loading the downloaded copy crashed in native code (owner's log 25 Nov 13:01). The library
     *  is in the APK again (app/build.gradle.kts no longer excludes it); WebRtcLib.kt stays for a later try. */
    val webrtcOnDemand: Boolean get() = false

    /** 0.9.6: the engine setting as the player sees it. A stored "vlc" is ignored with noVlc (never rewritten, so cloud
     *  sync is untouched). */
    fun engine(raw: String): String = if (noVlc) "exo" else raw
}
