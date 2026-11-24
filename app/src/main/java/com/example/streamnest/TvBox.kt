package com.example.streamnest

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer

/**
 * StreamX TV 0.8.3 — written for the TV only:
 *  - [setupOnce]: on the first start, reads the box (model, memory, what its chips decode, what the TV can show) and sets
 *    sensible defaults for it (each one can be changed in Settings). Logged, so a box's log says what it is.
 *  - [badges]: what is really playing — resolution, HDR / Dolby Vision, 50/60 fps, the sound format and channels, and
 *    whether the sound goes untouched to the soundbar / receiver or is decoded on the TV.
 *  - [matchFrameRate] / [restore]: the TV switches to the film's frame rate (24 / 25 / 50 / 60) while it plays.
 */
object TvBox {
    data class Info(val name: String, val ramGb: Double, val hevc: Boolean, val hevc10: Boolean, val av1: Boolean, val dv: Boolean,
                    val hdr10: Boolean, val rates: List<Float>, val passthrough: List<String>)

    @Volatile private var cached: Info? = null

    fun info(ctx: Context): Info {
        cached?.let { return it }
        val ram = runCatching {
            val mi = android.app.ActivityManager.MemoryInfo()
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
            mi.totalMem / (1024.0 * 1024 * 1024)
        }.getOrDefault(1.0)
        val codecs = runCatching { android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }.getOrDefault(emptyList())
        fun hw(mime: String) = codecs.any { c ->
            c.supportedTypes.any { it.equals(mime, true) } && !c.name.startsWith("OMX.google.") && !c.name.startsWith("c2.android.")
        }
        val hevc10 = runCatching {
            codecs.filter { c -> c.supportedTypes.any { it.equals(MimeTypes.VIDEO_H265, true) } && !c.name.startsWith("OMX.google.") && !c.name.startsWith("c2.android.") }
                .any { c -> c.getCapabilitiesForType(MimeTypes.VIDEO_H265).profileLevels.any { it.profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 } }
        }.getOrDefault(false)
        @Suppress("DEPRECATION")
        val display = runCatching { (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay }.getOrNull()
        val hdrTypes = runCatching { display?.hdrCapabilities?.supportedHdrTypes?.toList().orEmpty() }.getOrDefault(emptyList())
        val rates = runCatching { display?.supportedModes?.map { it.refreshRate }?.distinct()?.sorted().orEmpty() }.getOrDefault(emptyList())
        val caps = runCatching { androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(ctx) }.getOrNull()
        val pass = listOf(C.ENCODING_AC3 to "Dolby Digital", C.ENCODING_E_AC3 to "Dolby Digital Plus", C.ENCODING_E_AC3_JOC to "Atmos (DD+)",
            C.ENCODING_DTS to "DTS", C.ENCODING_DTS_HD to "DTS-HD", C.ENCODING_DOLBY_TRUEHD to "TrueHD")
            .filter { (e, _) -> runCatching { caps?.supportsEncoding(e) == true }.getOrDefault(false) }.map { it.second }
        return Info(ActLog.tvName().removeSuffix(" (TV)"), ram, hw(MimeTypes.VIDEO_H265), hevc10, hw(MimeTypes.VIDEO_AV1), hw(MimeTypes.VIDEO_DOLBY_VISION),
            Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes, rates, pass).also { cached = it }
    }

    /** First start on this box: defaults that suit it (Settings can change each one). */
    fun setupOnce(ctx: Context, vm: AppViewModel) {
        if (!BuildConfig.IS_TV) return
        val i = runCatching { info(ctx) }.getOrNull() ?: return
        TvWeak.on = i.ramGb < 1.5
        // TV 0.8.5: 4K only on a TV that shows 4K, decodes HEVC 10-bit in hardware and has memory to spare.
        @Suppress("DEPRECATION")
        val uhd = runCatching { (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.supportedModes.any { it.physicalWidth >= 3800 } }.getOrDefault(false)
        TvWeak.k4 = uhd && i.hevc10 && i.ramGb >= 1.5
        // 60 fps is tried on every box; one that drops frames on it is remembered and gets plain 1080p after that.
        TvWeak.no60 = vm.store("tvNo60") == "1"
        // TV 0.8.6: our own FFmpeg audio build (LGPL) — logged so a box's log says whether it loaded.
        ActLog.add("APP", "FFmpeg audio: " + runCatching { val c = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
            if (c.getMethod("isAvailable").invoke(null) == true) "ready " + (c.getMethod("getVersion").invoke(null) ?: "") else "not loaded" }.getOrElse { "missing" })
        ActLog.add("APP", "TV can play: ${if (TvWeak.k4) "4K" else "up to 1080p"}${if (TvWeak.no60) ", no 60 fps" else ", 60 fps"}")
        ActLog.add("APP", "TV box: ${i.name} · %.1f GB · HEVC ${if (i.hevc) "yes" else "no"}${if (i.hevc10) " (10-bit)" else ""} · AV1 ${if (i.av1) "yes" else "no"} · DV ${if (i.dv) "yes" else "no"} · HDR10 ${if (i.hdr10) "yes" else "no"} · screen ${i.rates.joinToString("/") { "%.2f".format(it).trimEnd('0').trimEnd('.') }} Hz · to the soundbar: ${i.passthrough.ifEmpty { listOf("stereo only") }.joinToString(", ")}".format(i.ramGb))
        // TV 0.8.8: after every update the app refreshes its own leftovers once by itself — temporary files, the picture
        // cache, the search screen's memory — so nothing from the older version needs "Clear data". Sign-in, profiles,
        // settings, your progress and recent searches are kept.
        val ver = BuildConfig.VERSION_CODE.toString()
        val was = vm.store("tvLastVersion")
        if (was != ver) {
            // TV 0.9.15: the version is marked first, and the file clean-up no longer runs on the screen's thread at start
            // (deleting the old temporary files there froze Who's watching on the first open after an update, and Android
            // closed the app). It runs once, 30 s later, in the background while the remote is quiet; if the app is closed
            // before that, the next start is a normal one and the old files simply stay.
            vm.storePut("tvLastVersion", ver)
            runCatching { SearchState.hits.value = emptyList(); SearchState.query.value = ""; SearchState.failed.value = false }
            val app = ctx.applicationContext
            Thread {
                runCatching {
                    Thread.sleep(30_000)
                    var n = 0
                    while (android.os.SystemClock.elapsedRealtime() - TvIdle.lastKeyAt < 3_000 && n++ < 40) Thread.sleep(500)
                    app.cacheDir.listFiles()?.forEach { f -> if (f.name !in setOf("vcache", "logs", "image_cache", "posters")) runCatching { f.deleteRecursively() } }
                    ActLog.add("APP", "TV updated${if (was != null) " from $was" else ""} to $ver: temporary files refreshed in the background (sign-in and settings kept)")
                }
            }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
        }
        // TV 0.9.10: frame-rate matching is off by default (every switch makes the TV re-sync: 1–3 s of black when a
        // video starts and when you leave it — CloudStream / Nuvio never switch). Turned off once on this update;
        // Settings › Player › Match frame rate turns it back on.
        // TV 0.9.12: frame-rate matching has a new setting ("tvFps2"), off for every profile; the old per-profile value
        // (still "on" in some profiles) is no longer read.
        if (vm.store("fpsOff0912") != "1") { vm.storePut("fpsOff0912", "1"); ActLog.add("APP", "TV: frame-rate matching off for every profile (new setting)") }
        if (vm.store("tvSetupDone") == "1") return
        // Frame-rate matching: on when the TV offers more than one refresh rate (films only — no switching for 60 fps TV).
        if (vm.texts.value["tvFps2"] == null) vm.setText("tvFps2", "off")
        // Trailers stay off on every box (Settings › Layout turns them on to try).
        if (vm.texts.value["tvTrailers"] == null) vm.setText("tvTrailers", "no")
        vm.storePut("tvSetupDone", "1")
        ActLog.add("APP", "TV setup: frame-rate matching ${vm.texts.value["tvFps2"] ?: "off"}, trailers off")
    }

    /** Small tags for the player: "1080p · HDR10 · 60 fps", "DD+ 5.1 · to your speakers". */
    fun badges(ctx: Context, player: ExoPlayer): Pair<List<String>, String?> {
        val v = player.videoFormat
        val a = player.audioFormat
        val out = ArrayList<String>()
        if (v != null && v.height > 0) {
            out += when { v.height >= 2000 -> "4K"; v.height >= 1000 -> "1080p"; v.height >= 700 -> "720p"; else -> "${v.height}p" }
            val tr = v.colorInfo?.colorTransfer
            when {
                v.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || v.codecs?.startsWith("dv") == true -> out += "Dolby Vision"
                tr == C.COLOR_TRANSFER_ST2084 -> out += "HDR10"
                tr == C.COLOR_TRANSFER_HLG -> out += "HLG"
            }
            if (v.frameRate >= 47f) out += "${Math.round(v.frameRate)} fps"
        }
        val sound = a?.let { f ->
            val name = when (f.sampleMimeType) {
                MimeTypes.AUDIO_E_AC3_JOC -> "Atmos"; MimeTypes.AUDIO_E_AC3 -> "DD+"; MimeTypes.AUDIO_AC3 -> "Dolby Digital"
                MimeTypes.AUDIO_TRUEHD -> "TrueHD"; MimeTypes.AUDIO_DTS_HD -> "DTS-HD"; MimeTypes.AUDIO_DTS -> "DTS"
                MimeTypes.AUDIO_AAC -> "AAC"; MimeTypes.AUDIO_OPUS -> "Opus"; MimeTypes.AUDIO_MPEG -> "MP3"; else -> f.sampleMimeType?.substringAfter('/')?.uppercase() ?: "Audio"
            }
            val ch = when (f.channelCount) { 1 -> "1.0"; 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"; in 3..5 -> "${f.channelCount} ch"; else -> "" }
            val enc = when (f.sampleMimeType) {
                MimeTypes.AUDIO_E_AC3_JOC -> C.ENCODING_E_AC3_JOC; MimeTypes.AUDIO_E_AC3 -> C.ENCODING_E_AC3; MimeTypes.AUDIO_AC3 -> C.ENCODING_AC3
                MimeTypes.AUDIO_TRUEHD -> C.ENCODING_DOLBY_TRUEHD; MimeTypes.AUDIO_DTS_HD -> C.ENCODING_DTS_HD; MimeTypes.AUDIO_DTS -> C.ENCODING_DTS; else -> null
            }
            val pass = enc != null && runCatching { androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(ctx).supportsEncoding(enc) }.getOrDefault(false)
            "$name ${ch}".trim() + if (enc == null) "" else if (pass) " · to your speakers" else " · decoded on the TV"
        }
        return out to sound
    }

    private var savedMode: Int? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingRestore: Runnable? = null

    /** When the TV was last asked for another refresh rate (its picture and sound re-sync for 1–3 s). */
    @Volatile var switchedAt = 0L

    /**
     * Asks the TV for the screen mode that fits [fps] (same size, a refresh rate that is a whole multiple of it).
     * TV 0.9.9: a mode kept from the last video (see [restore]) is simply kept when it fits — next episode, no re-sync.
     */
    fun matchFrameRate(act: Activity, fps: Float, mode: String): String? {
        val waiting = pendingRestore
        pendingRestore?.let { main.removeCallbacks(it) }; pendingRestore = null
        fun noSwitch(): String? { if (waiting != null) restoreNow(act); return null }
        if (Build.VERSION.SDK_INT < 23 || fps <= 0f || mode == "off") return noSwitch()
        val film = fps in 23f..25.5f
        if (mode == "films" && !film) return noSwitch()
        @Suppress("DEPRECATION")
        val d = act.windowManager.defaultDisplay
        val cur = d.mode
        val same = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
        fun fits(r: Float): Boolean { val k = Math.round(r / fps); return k >= 1 && kotlin.math.abs(r - k * fps) < 0.05f * k }
        val best = same.filter { fits(it.refreshRate) }.minByOrNull { it.refreshRate } ?: return noSwitch()
        if (best.modeId == cur.modeId) return null   // already right (kept from the last episode): nothing to do
        if (savedMode == null) savedMode = cur.modeId
        act.window.attributes = act.window.attributes.also { it.preferredDisplayModeId = best.modeId }
        switchedAt = android.os.SystemClock.elapsedRealtime()
        return "%.3f".format(best.refreshRate).trimEnd('0').trimEnd('.') + " Hz"
    }

    /**
     * The player closed. TV 0.9.9: the TV goes back to its own mode only after 6 s — if the next episode opens before
     * that and needs the same mode, nothing changes (every change makes the TV re-sync: 1–3 s of black, twice per
     * episode before).
     */
    fun restore(act: Activity) {
        if (savedMode == null) return
        pendingRestore?.let { main.removeCallbacks(it) }
        val r = Runnable { pendingRestore = null; restoreNow(act) }
        pendingRestore = r
        main.postDelayed(r, 6_000)
    }

    private fun restoreNow(act: Activity) {
        val m = savedMode ?: return
        savedMode = null
        // 0 = no wish: the TV goes back to its own mode.
        if (Build.VERSION.SDK_INT >= 23 && m >= 0) runCatching { act.window.attributes = act.window.attributes.also { it.preferredDisplayModeId = 0 } }
    }
}
