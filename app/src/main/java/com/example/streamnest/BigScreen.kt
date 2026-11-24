package com.example.streamnest

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 0.9.5 (switch `bigScreen`): the phone app shows the TV interface (TV Home, side bar, TV settings, title page, search)
 * on big screens — a tablet in landscape, a laptop / PC emulator (MuMu), a wide window, or keys / mouse with no touch.
 * Worked out at start and again on every display change; while the player is open (or in a small window) the choice
 * waits until the player closes, so turning the screen never closes the video. Switch off = always the phone layout.
 */
object BigScreen {
    private val state = mutableStateOf(false)
    /** 0.9.5: the phone app is in big-screen mode now (read in composition: the screen follows it). */
    val on: Boolean get() = state.value
    /** 0.9.5: the TV interface is shown: the TV app always, the phone app in big-screen mode. */
    val tvUi: Boolean get() = BuildConfig.IS_TV || state.value
    @Volatile private var pending: Boolean? = null
    @Volatile private var lastWhy = ""
    private var logged = false

    /** 0.9.5: whether this display counts as a big screen, and why (for the log). */
    fun detect(ctx: Context, c: Configuration): Pair<Boolean, String> {
        if (BuildConfig.IS_TV || !Feature.bigScreen) return false to "off"
        val land = c.orientation == Configuration.ORIENTATION_LANDSCAPE
        val sw = c.smallestScreenWidthDp
        val noTouch = !ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN) ||
            c.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH
        val keys = (c.keyboard == Configuration.KEYBOARD_QWERTY && c.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES) || hasMouse()
        return when {
            land && sw >= 600 -> true to "landscape, ${sw} dp"
            noTouch && keys -> true to "keyboard / mouse, no touch"
            // 0.9.5: wide AND at least 480 dp tall — a phone turned sideways is ~900 dp wide but only ~400 dp tall.
            c.screenWidthDp >= 840 && c.screenHeightDp >= 480 -> true to "wide window ${c.screenWidthDp}×${c.screenHeightDp} dp"
            else -> false to "${if (land) "landscape" else "portrait"}, ${c.screenWidthDp}×${c.screenHeightDp} dp"
        }
    }

    private fun hasMouse(): Boolean = runCatching {
        android.view.InputDevice.getDeviceIds().any { id ->
            val d = android.view.InputDevice.getDevice(id) ?: return@any false
            !d.isVirtual && (d.supportsSource(android.view.InputDevice.SOURCE_MOUSE) || d.supportsSource(android.view.InputDevice.SOURCE_TOUCHPAD))
        }
    }.getOrDefault(false)

    /** 0.9.5: called at start and on every display change. */
    fun update(act: Activity, c: Configuration) {
        if (BuildConfig.IS_TV || !Feature.bigScreen) return
        val (want, why) = runCatching { detect(act, c) }.getOrDefault(false to "?")
        lastWhy = why
        val pip = android.os.Build.VERSION.SDK_INT >= 24 && act.isInPictureInPictureMode
        if (PipState.playerOpen || pip) { pending = want; return }
        set(want, why)
    }

    /** 0.9.5: the player closed — a display change that waited is applied now. */
    fun applyPending() {
        if (pending == null) return
        // A moment later, and only if no player opened again meanwhile (next episode / another link replaces the player).
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val p = pending
            if (p != null && !PipState.playerOpen) set(p, lastWhy)
        }, 400)
    }

    private fun set(v: Boolean, why: String) {
        pending = null
        if (state.value == v && logged) return
        logged = true
        // The phone app plays 4K itself: the TV title page must not say "4K (not on this TV)".
        if (v) TvWeak.k4 = true
        state.value = v
        ActLog.add("APP", "big screen ${if (v) "on" else "off"} ($why)")
    }
}

/**
 * 0.9.5: how strong this device is, worked out once (memory, the app's own memory, decoders, an x86 host).
 * "top" = a laptop / PC emulator or a big tablet: 4K allowed and bigger video buffers. Only with `bigScreen` on.
 */
object DeviceTier {
    @Volatile private var tier: String? = null
    @Volatile private var line = ""

    /** 0.9.5: true on a laptop / emulator / big tablet with memory to spare (switch `bigScreen`). */
    fun top(ctx: Context): Boolean = get(ctx) == "top"

    fun get(ctx: Context): String = tier ?: synchronized(this) { tier ?: work(ctx.applicationContext).also { tier = it } }

    /** 0.9.5: worked out in the background a few seconds after start, and logged once. */
    fun logLater(ctx: Context) {
        if (BuildConfig.IS_TV || !Feature.bigScreen) return
        val app = ctx.applicationContext
        Thread({ runCatching { Thread.sleep(3_000); get(app) } }, "device-tier").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun work(ctx: Context): String {
        if (BuildConfig.IS_TV || !Feature.bigScreen) return "default"
        return runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val gb = mi.totalMem / 1_073_741_824.0
            val heapMb = (Runtime.getRuntime().maxMemory() / 1_048_576).toInt()
            val cores = Runtime.getRuntime().availableProcessors()
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            val hw = (android.os.Build.HARDWARE + " " + android.os.Build.FINGERPRINT + " " + android.os.Build.MANUFACTURER).lowercase()
            // x86 host (MuMu, BlueStacks, the Android emulator, Chromebooks) or a PC-type device.
            val pc = abi.startsWith("x86") || ctx.packageManager.hasSystemFeature("android.hardware.type.pc") ||
                listOf("ranchu", "goldfish", "vbox", "netease", "mumu", "nox", "bluestacks", "generic_x86").any { it in hw }
            val hevc4k = decodes4k()
            val t = when {
                pc && gb >= 3.5 && heapMb >= 256 -> "top"
                gb >= 7.5 && heapMb >= 512 && hevc4k && cores >= 8 -> "top"
                am.isLowRamDevice || gb < 2.5 || cores <= 4 -> "low"
                gb < 5.5 -> "mid"
                else -> "high"
            }
            line = "device tier $t · ${"%.1f".format(gb)} GB · app memory $heapMb MB · $cores cores · $abi${if (pc) " (PC / emulator)" else ""} · 4K HEVC decoder ${if (hevc4k) "yes" else "no"}"
            ActLog.add("APP", line)
            t
        }.getOrDefault("default")
    }

    private fun decodes4k(): Boolean = runCatching {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
            !ci.isEncoder && ci.supportedTypes.any { it.equals("video/hevc", true) } &&
                ci.getCapabilitiesForType("video/hevc").videoCapabilities?.isSizeSupported(3840, 2160) == true
        }
    }.getOrDefault(false)
}

/**
 * 0.9.5: mouse on a sideways row in big-screen mode — the wheel with Shift (or a sideways wheel / touchpad) and a
 * mouse drag move the row. A drag is never taken as a click. TV app: unchanged.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.bigScreenRowMouse(state: ScrollableState): Modifier = if (BuildConfig.IS_TV) this else pointerInput(state) {
    val slop = viewConfiguration.touchSlop
    val wheel = 64.dp.toPx()
    awaitPointerEventScope {
        var last: Offset? = null
        var moved = 0f
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            val ch = e.changes.firstOrNull() ?: continue
            if (ch.type != PointerType.Mouse) { last = null; continue }
            when (e.type) {
                PointerEventType.Scroll -> {
                    val d = ch.scrollDelta
                    val dx = if (abs(d.x) > 0f) d.x else if (e.keyboardModifiers.isShiftPressed) d.y else 0f
                    if (dx != 0f) { state.dispatchRawDelta(dx * wheel); ch.consume() }
                }
                PointerEventType.Press -> { last = ch.position; moved = 0f }
                PointerEventType.Move -> {
                    val l = last
                    if (l != null && ch.pressed) {
                        val dx = ch.position.x - l.x
                        moved += abs(dx)
                        if (moved > slop) { state.dispatchRawDelta(-dx); ch.consume() }
                        last = ch.position
                    }
                }
                PointerEventType.Release -> { if (moved > slop) ch.consume(); last = null; moved = 0f }
                else -> Unit
            }
        }
    }
}
