package com.example.streamnest

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 0.9.77: app-wide memory lines. For 60 s after every screen change, every 5 s:
 * "memory · <screen> · app X / Y MB · native · players · downloads · searches". When the app's own memory passes 75 %,
 * the extras are let go: the title page's paused player, the pictures kept in memory, and source answers for other
 * titles. Phones only.
 */
object MemWatch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var lastRelief = 0L
    @Volatile var currentId: String? = null

    fun onScreen(ctx: Context, screen: String) {
        if (BuildConfig.IS_TV) return
        val app = ctx.applicationContext
        job?.cancel()
        job = scope.launch {
            repeat(12) {
                delay(5_000)
                val rt = Runtime.getRuntime()
                val used = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
                val max = rt.maxMemory() / 1_048_576
                val native = android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576
                // 0.9.79: light logs — the first reading per screen, then only when memory runs high.
                if (ActLog.detailed || it == 0 || used.toDouble() / max > 0.6) ActLog.add("APP", "memory · $screen · app $used / $max MB · native $native MB · players ${PlayerCount.alive()}" +
                    " · downloads ${runCatching { DlEngine.running() }.getOrDefault(0)} · searches ${PluginSources.running()}")
                if (used.toDouble() / max > 0.75) relieve(app, "$used / $max MB")
            }
        }
    }

    /** Over 75 %: free what can be made again later. At most every 10 s. */
    fun relieve(ctx: Context, why: String) {
        val now = System.currentTimeMillis()
        if (now - lastRelief < 10_000) return
        lastRelief = now
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { Preloader.release() }
            runCatching { coil.Coil.imageLoader(ctx).memoryCache?.clear() }
            runCatching { PluginSources.trimExcept(currentId) }
            ActLog.add("APP", "memory high ($why) — let go of the paused player, pictures in memory and other titles' source answers")
        }
    }
}

/**
 * 0.9.79 (phone): frames drawn and frames that came late, per screen — one line when you leave a screen.
 * 0.9.2: "late" follows the screen's real speed. A frame is late when its work (a busy main thread before it starts,
 * then main thread + render thread; not the GPU queue behind earlier frames) took more than 1.5 × the display's frame interval
 * at that moment (Android 12+: the frame's own deadline; older: the refresh rate, read again every second, so a 120 Hz
 * phone that drops to 60 Hz is judged at 60 Hz). Before, the whole time from vsync to the screen counted, which is
 * always over one 120 Hz interval with buffered drawing, so every screen showed 74–100 % late.
 * Line: "frames · Home · 1840 drawn · 23 late (1.3 %) · 120 Hz · avg 4.1 ms · worst 38 ms".
 */
object FrameStats {
    private val frames = java.util.concurrent.atomic.AtomicInteger(0)
    private val late = java.util.concurrent.atomic.AtomicInteger(0)
    private val workNs = java.util.concurrent.atomic.AtomicLong(0)
    private val worstNs = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var screen = "Start"
    @Volatile private var intervalNs = 16_666_667L
    @Volatile private var hzReadAt = 0L
    @Volatile private var attached = false
    private val handler by lazy { android.os.Handler(android.os.HandlerThread("frames").apply { start() }.looper) }
    private var win: java.lang.ref.WeakReference<android.view.Window>? = null

    private fun readHz(w: android.view.Window) {
        val hz = runCatching { (if (android.os.Build.VERSION.SDK_INT >= 30) w.decorView.display else null)?.refreshRate
            ?: w.windowManager.defaultDisplay.refreshRate }.getOrNull() ?: 60f
        intervalNs = (1_000_000_000L / hz.coerceIn(24f, 165f)).toLong()
    }

    /** Called from each new activity (a recreated one gets its own listener). */
    fun attach(a: android.app.Activity) {
        if (BuildConfig.IS_TV || android.os.Build.VERSION.SDK_INT < 24 || win?.get() === a.window) return
        val w = a.window
        win = java.lang.ref.WeakReference(w)
        attached = true
        runCatching {
            w.addOnFrameMetricsAvailableListener({ _, m, _ ->
                if (android.os.Build.VERSION.SDK_INT >= 26 && m.getMetric(android.view.FrameMetrics.FIRST_DRAW_FRAME) != 0L) return@addOnFrameMetricsAvailableListener
                val now = android.os.SystemClock.uptimeMillis()
                if (now - hzReadAt > 1000) { hzReadAt = now; readHz(w) }
                val deadline = if (android.os.Build.VERSION.SDK_INT >= 31) m.getMetric(android.view.FrameMetrics.DEADLINE) else 0L
                val interval = if (deadline in 4_000_000L..50_000_000L) deadline else intervalNs
                val work = m.getMetric(android.view.FrameMetrics.UNKNOWN_DELAY_DURATION) + m.getMetric(android.view.FrameMetrics.INPUT_HANDLING_DURATION) + m.getMetric(android.view.FrameMetrics.ANIMATION_DURATION) +
                    m.getMetric(android.view.FrameMetrics.LAYOUT_MEASURE_DURATION) + m.getMetric(android.view.FrameMetrics.DRAW_DURATION) +
                    m.getMetric(android.view.FrameMetrics.SYNC_DURATION) + m.getMetric(android.view.FrameMetrics.COMMAND_ISSUE_DURATION) +
                    m.getMetric(android.view.FrameMetrics.SWAP_BUFFERS_DURATION)
                frames.incrementAndGet()
                workNs.addAndGet(work)
                if (work > worstNs.get()) worstNs.set(work)
                if (work > interval * 3 / 2) late.incrementAndGet()
            }, handler)
        }
    }

    fun onScreen(next: String) {
        if (BuildConfig.IS_TV || !attached) return
        val f = frames.getAndSet(0); val l = late.getAndSet(0); val sum = workNs.getAndSet(0); val worst = worstNs.getAndSet(0)
        if (f >= 30) ActLog.add("APP", "frames · $screen · $f drawn · $l late (${"%.1f".format(l * 100f / f)} %) · ${Math.round(1e9 / intervalNs)} Hz · " +
            "avg ${"%.1f".format(sum / f / 1e6)} ms · worst ${worst / 1_000_000} ms")
        screen = next
    }
}
