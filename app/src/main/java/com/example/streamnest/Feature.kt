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
}
