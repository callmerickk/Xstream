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
 * 0.9.79 (phone): frames drawn and frames that came late, per screen — one line when you leave a screen
 * ("frames · Home · 1840 drawn · 23 late (1.3 %)"). Read off the screen thread; costs almost nothing.
 */
object FrameStats {
    private val frames = java.util.concurrent.atomic.AtomicInteger(0)
    private val late = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var screen = "Start"
    @Volatile private var budgetNs = 25_000_000L
    @Volatile private var attached = false
    private val handler by lazy { android.os.Handler(android.os.HandlerThread("frames").apply { start() }.looper) }
    private var win: java.lang.ref.WeakReference<android.view.Window>? = null

    /** Called from each new activity (a recreated one gets its own listener). */
    fun attach(a: android.app.Activity) {
        if (BuildConfig.IS_TV || android.os.Build.VERSION.SDK_INT < 24 || win?.get() === a.window) return
        win = java.lang.ref.WeakReference(a.window)
        attached = true
        val hz = runCatching { a.windowManager.defaultDisplay.refreshRate }.getOrDefault(60f).coerceIn(30f, 144f)
        budgetNs = (1_000_000_000L / hz * 1.5f).toLong()
        runCatching {
            a.window.addOnFrameMetricsAvailableListener({ _, m, _ ->
                frames.incrementAndGet()
                if ((android.os.Build.VERSION.SDK_INT < 26 || m.getMetric(android.view.FrameMetrics.FIRST_DRAW_FRAME) == 0L) && m.getMetric(android.view.FrameMetrics.TOTAL_DURATION) > budgetNs) late.incrementAndGet()
            }, handler)
        }
    }

    fun onScreen(next: String) {
        if (BuildConfig.IS_TV || !attached) return
        val f = frames.getAndSet(0); val l = late.getAndSet(0)
        if (f >= 30) ActLog.add("APP", "frames · $screen · $f drawn · $l late (${"%.1f".format(l * 100f / f)} %)")
        screen = next
    }
}
