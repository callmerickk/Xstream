package com.example.streamnest

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder

/** A FragmentActivity (still a ComponentActivity) so the fingerprint prompt can be shown. */
// AppCompat (still a FragmentActivity underneath): CloudStream plugins expect the app's screen to be one.
class MainActivity : androidx.appcompat.app.AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.lagradost.cloudstream3.CommonActivity.activity = this
        CrashLog.install(this)
        TouchInput.on = !BuildConfig.IS_TV || packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
        ActLog.init(this)
        runCatching { FrameStats.attach(this) }
        // 0.9.2: crash reports (Crashlytics): keys, account code and log lines; set up on a background thread.
        CrashReports.start(this)
        if (BuildConfig.IS_TV && savedInstanceState == null) { TvSmall.startedAt = android.os.SystemClock.elapsedRealtime(); TvSmall.checkLastRun(this) }
        // The phone rebuilt the screen (a display change the app doesn't handle itself): say what changed.
        if (savedInstanceState != null) ActLog.add("APP", "screen rebuilt by Android" + (lastCfg?.let { " · " + cfgDiff(resources.configuration.diff(it)) } ?: ""))
        lastCfg = android.content.res.Configuration(resources.configuration)
        CrashLog.logExitReasons(this)
        // App icon picked with "Later": apply it now that the app was fully closed and opened again.
        if (!started) { started = true; AppIcons.signalOnce(this); AppIcons.applyPendingOnColdStart(this); AppIcons.keepOne(this) }
        // Image loader that can also play GIFs and show video thumbnails (for profile pictures).
        Coil.setImageLoader(
            ImageLoader.Builder(this).components {
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory(enforceMinimumFrameDelay = true)) else add(GifDecoder.Factory(enforceMinimumFrameDelay = true))
                add(VideoFrameDecoder.Factory())
                if (!BuildConfig.IS_TV) add(HdPoster)
                // TV 0.9.13: pictures fetched at the size they are shown (a card never downloads a 1280-px backdrop).
                add(TvImgSize)  // 0.9.75: phones too (as Xstream 0.8.17 ImgSize)
            }.okHttpClient { Posters.client() }.crossfade(if (BuildConfig.IS_TV) 0 else 120).allowRgb565(true)
                // TV 0.8.4: a 1 GB Fire TV stick keeps fewer pictures in memory (Android was closing the app in search).
                .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(if (BuildConfig.IS_TV && TvSmall.on(this)) 0.12 else 0.25).build() }
                .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("posters")).maxSizeBytes(400L * 1024 * 1024).build() }
                .respectCacheHeaders(false)
                .build()
        )
        // Live server (64-bit app): started a few seconds after the app opens, so it's ready before a title page asks.
        LiveServer.start(this, 2_500)
        // "Xstream Music" when the app icon is held.
        MusicShortcut.publish(this)
        // The music notification was tapped: open Now playing.
        if (intent?.getBooleanExtra("openMusic", false) == true) MusicPlayer.openNow.value = true
        if (intent?.getBooleanExtra("musicHome", false) == true) MusicPlayer.splash.value = true
        WidgetOpen.take(intent)
        JoinIntent.take(intent)
        DlEngine.take(intent)
        if (intent.getBooleanExtra("openMusic", false)) MusicPlayer.openNow.value = true
        if (intent.getBooleanExtra("musicHome", false)) MusicPlayer.splash.value = true
        SocialIntent.take(intent)
        enableEdgeToEdge()
        // StreamX TV (tv flavor) gets the remote-friendly TV interface; the phone app keeps its own.
        // TV 0.9.14: the start logo (window background) is only for the first moments; then plain black again behind the
        // video and every page.
        if (BuildConfig.IS_TV) window.decorView.postDelayed({ runCatching { window.setBackgroundDrawableResource(android.R.color.black) } }, 4_000)
        setContent {
            StreamNestTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    // 0.9.76: Glass theme (phones) — one blurred picture behind every page.
                    // 0.9.2: not drawn at all while the video player is open (it only cost frames under the video).
                    if (!BuildConfig.IS_TV && Palette.glass && !GlassBg.paused) GlassBackdrop(GlassBg.url)
                    if (BuildConfig.IS_TV) TvApp() else App()
                    // 0.9.1 (accessGate): approval-only access + "Update required" (approval off until turned on in Access requests).
                    if (Feature.accessGate && !BuildConfig.IS_TV) AccessGate(androidx.lifecycle.viewmodel.compose.viewModel<AppViewModel>())
                    UpdatePrompt()
                    ProfileLockDialog()
                    // Source Test running: the screen stays awake; afterwards your normal screen timeout applies again.
                    val srcTesting by SourceTester.running.collectAsState()
                    val labRunning by RefreshLab.running.collectAsState()
                    // Plugin tests (CloudStream / Nuvio) too: a sleeping phone froze their timers (a "60 s" limit took 165 s).
                    val csTesting by CsTester.running.collectAsState()
                    val nuvioTesting by NuvioTester.running.collectAsState()
                    val testing = srcTesting || labRunning || csTesting || nuvioTesting
                    val view = androidx.compose.ui.platform.LocalView.current
                    DisposableEffect(testing) { view.keepScreenOn = testing; onDispose { view.keepScreenOn = false } }
                    CrashNotice()
                    PermissionIntro()
                }
            }
        }
        // vivo (Android 16) takes the app's screen off the window for a moment when the player turns sideways; by default
        // that throws the whole screen away (0.9.63: the player closed; 0.9.64: it restarted and lost its ready stream).
        // Kept until the app itself closes instead; logged when it happens.
        runCatching {
            val cv = (window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0) as androidx.compose.ui.platform.ComposeView
            cv.setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this))
            cv.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: android.view.View) { if (detachedOnce) ActLog.add("APP", "screen detached and re-attached by the phone (kept)") }
                override fun onViewDetachedFromWindow(v: android.view.View) { if (!isFinishing) detachedOnce = true }
            })
        }
    }
    private var detachedOnce = false

    // Activity log: every tap and remote key, with the button it hit.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // 0.9.79: the phone's start-up work waits while you touch the screen (as the TV waits for the remote).
        if (!BuildConfig.IS_TV) TvIdle.lastKeyAt = android.os.SystemClock.elapsedRealtime()
        if (Feature.linkMemory) UserIdle.lastTouch = System.currentTimeMillis()   // 0.9.1: idle Continue watching refresh
        val r = super.dispatchTouchEvent(ev)
        runCatching { ActLog.onTouch(window.decorView, ev) }
        return r
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        TvIdle.lastKeyAt = android.os.SystemClock.elapsedRealtime()
        val r = super.dispatchKeyEvent(event)
        runCatching { ActLog.onKey(window.decorView, event) }
        return r
    }

    override fun onPause() { super.onPause(); ActLog.add("APP", "to background"); if (BuildConfig.IS_TV) TvSmall.front(this, false) }

    /** TV 0.8.4: Android says memory is short → drop the pictures kept in memory (they reload from disk). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // TV 0.9.11: "running low" (10) only trims (the picture loader halves its own memory); the pictures on screen are
        // cleared only when it's critical (15+), so posters no longer vanish and reload at every small squeeze.
        // TV 0.9.15: Fire TV sends "critical" within the first second of every start even with 300 MB free; obeying it threw
        // away every poster, so each one loaded twice. Pictures are cleared only when memory is really short.
        if (BuildConfig.IS_TV && level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            val early = android.os.SystemClock.elapsedRealtime() - TvSmall.startedAt < 20_000
            if (!early && TvSmall.reallyShort(this)) {
                runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
                ActLog.add("APP", "memory short (level $level) — pictures cleared · ${TvSmall.mem(this)}")
            } else ActLog.add("APP", "memory signal (level $level) ignored${if (early) " (start)" else ""} · ${TvSmall.mem(this)}")
        }
    }

    /** Display changes handled without rebuilding the screen; anything beyond turning the phone is logged. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        runCatching {
            val d = lastCfg?.let { newConfig.diff(it) } ?: 0
            val turn = android.content.pm.ActivityInfo.CONFIG_ORIENTATION or android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE or
                android.content.pm.ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or android.content.pm.ActivityInfo.CONFIG_SCREEN_LAYOUT
            if (d and turn.inv() != 0) ActLog.add("APP", "display change handled: " + cfgDiff(d))
            lastCfg = android.content.res.Configuration(newConfig)
        }
    }
    override fun onResume() { super.onResume(); com.lagradost.cloudstream3.CommonActivity.activity = this; ActLog.add("APP", "in front"); if (BuildConfig.IS_TV) TvSmall.front(this, true); runCatching { Cloud.onAppForeground() }; LiveServer.start(this, 2_500) }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        WidgetOpen.take(intent)
        JoinIntent.take(intent)
        DlEngine.take(intent)
        SocialIntent.take(intent)
    }

    /** Going Home while a video plays: keep watching in a small window (Playback › Picture-in-picture). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!BuildConfig.IS_TV && PipState.playerOpen && PipState.playing && PipState.supported(this) &&
            AutoPip.on(this)) PipState.enter(this)
    }

    companion object {
        /** false only on the first Activity of a fresh process (a "cold start"). */
        private var started = false
        /** The display settings last seen (kept across a rebuilt screen, to say what changed). */
        private var lastCfg: android.content.res.Configuration? = null
        private fun cfgDiff(d: Int): String {
            val I = android.content.pm.ActivityInfo::class.java
            val names = listOf("ORIENTATION", "SCREEN_SIZE", "SMALLEST_SCREEN_SIZE", "SCREEN_LAYOUT", "DENSITY", "FONT_SCALE", "UI_MODE",
                "LOCALE", "LAYOUT_DIRECTION", "NAVIGATION", "KEYBOARD", "KEYBOARD_HIDDEN", "TOUCHSCREEN", "COLOR_MODE", "MCC", "MNC", "FONT_WEIGHT_ADJUSTMENT")
            val hit = names.filter { n -> runCatching { (I.getField("CONFIG_$n").getInt(null) and d) != 0 }.getOrDefault(false) }
            val known = names.sumOf { n -> runCatching { I.getField("CONFIG_$n").getInt(null) }.getOrDefault(0) }
            val rest = d and known.inv()
            return (hit.joinToString(", ") { it.lowercase().replace('_', ' ') } + if (rest != 0) " + other 0x${Integer.toHexString(rest)}" else "").ifBlank { "nothing listed (0x${Integer.toHexString(d)})" }
        }
    }
}

/** "Picture-in-picture when I go Home" (Help & Settings › Playback), on by default. */
object AutoPip {
    fun on(ctx: android.content.Context): Boolean =
        ctx.getSharedPreferences("player_misc", android.content.Context.MODE_PRIVATE).getBoolean("autoPip", true)
    fun set(ctx: android.content.Context, v: Boolean) =
        ctx.getSharedPreferences("player_misc", android.content.Context.MODE_PRIVATE).edit().putBoolean("autoPip", v).apply()
}

/** Phone: posters on Home and the title page load in HD (metahub large, TMDB w780) instead of the small sizes. */
object HdPoster : coil.map.Mapper<String, String> {
    private val tmdb = Regex("(image\\.tmdb\\.org/t/p/)w(92|154|185|200|300|500)(/)")
    override fun map(data: String, options: coil.request.Options): String? {
        if (!data.startsWith("http")) return null
        // Phone-sized (≈ 340 px wide, ~30 KB) instead of large (≈ 1000 px, ~200 KB): a screen of posters loads at once.
        // The big cards ask for the sharp copy themselves (hiResArt).
        return when {
            "images.metahub.space/poster/small/" in data -> data.replace("/poster/small/", "/poster/medium/")
            "image.tmdb.org/t/p/w" in data -> data.replace(tmdb, "$1w342$3")
            else -> null
        }.takeIf { it != data }
    }
}


/**
 * Posters: their own connection (no VPN retry or activity log on every picture, more pictures at once from one server)
 * and a quiet download ahead of what's about to scroll into view, so they show the moment a row or search appears.
 */
object Posters {
    @Volatile private var c: okhttp3.OkHttpClient? = null
    fun client(): okhttp3.OkHttpClient = c ?: okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(12, 5, java.util.concurrent.TimeUnit.MINUTES))
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 48; maxRequestsPerHost = 16 })
        .dns(Api.http.dns)
        .build().also { c = it }

    private val asked = java.util.Collections.newSetFromMap(object : java.util.LinkedHashMap<String, Boolean>(512, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?) = size > 2000
    })

    /** Downloads these pictures to the disk cache now (not decoded), so drawing them later is instant. */
    fun warm(ctx: android.content.Context, urls: List<String?>) {
        val loader = Coil.imageLoader(ctx)
        urls.filterNotNull().filter { it.startsWith("http") && asked.add(it) }.take(80).forEach { u ->
            loader.enqueue(coil.request.ImageRequest.Builder(ctx).data(u).memoryCachePolicy(coil.request.CachePolicy.DISABLED).size(1, 1).build())
        }
    }
}


/**
 * TV 0.8.4 — small boxes (1 GB Fire TV sticks): is this one of them, a memory line for the log, and whether Android closed
 * the app while it was on screen (Android 9 never says why; the "on screen" mark left behind tells us).
 */
/** TV 0.9.13: when the remote was last pressed; background starters wait while you move around. */
object TvIdle {
    @Volatile var lastKeyAt = 0L
    /** Waits until no key for [quietMs] (at most [maxMs]). */
    suspend fun waitQuiet(quietMs: Long = 1500, maxMs: Long = 20_000) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - lastKeyAt < quietMs && android.os.SystemClock.elapsedRealtime() - t0 < maxMs) kotlinx.coroutines.delay(300)
    }
}

/** TV 0.9.13: picks the smallest TMDB / Metahub picture that still fills the size it's drawn at. */
object TvImgSize : coil.intercept.Interceptor {
    private val tmdb = Regex("(image\\.tmdb\\.org/t/p/)(w\\d+|original)(/)")
    override suspend fun intercept(chain: coil.intercept.Interceptor.Chain): coil.request.ImageResult {
        val req = chain.request
        val url = req.data as? String ?: return chain.proceed(req)
        val w = (chain.size.width as? coil.size.Dimension.Pixels)?.px ?: return chain.proceed(req)
        val out = when {
            url.contains("image.tmdb.org/t/p/") -> {
                tmdb.replace(url) { m ->
                    val cur = m.groupValues[2]
                    val curW = if (cur == "original") Int.MAX_VALUE else cur.drop(1).toIntOrNull() ?: Int.MAX_VALUE
                    // Only sizes TMDB has for that kind of picture: backdrops (asked as w1280 / original) w300 · w780,
                    // posters w185 · w342 · w500.
                    val backdrop = cur == "original" || curW >= 1280
                    val want = if (backdrop) when { w <= 300 -> 300; w <= 780 -> 780; else -> 0 }
                        else when { w <= 185 -> 185; w <= 342 -> 342; w <= 500 -> 500; else -> 0 }
                    if (want in 1 until curW) m.groupValues[1] + "w" + want + m.groupValues[3] else m.value
                }
            }
            url.contains("images.metahub.space/") && w <= 400 -> url.replace("/poster/large/", "/poster/medium/").replace("/background/large/", "/background/medium/")
            else -> url
        }
        return if (out == url) chain.proceed(req) else chain.proceed(req.newBuilder().data(out).build())
    }
}

object TvSmall {
    // TV 0.9.13: asked once (it was asked again on every check).
    @Volatile private var small: Boolean? = null
    fun on(ctx: android.content.Context): Boolean = small ?: runCatching {
        val mi = android.app.ActivityManager.MemoryInfo()
        (ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        mi.totalMem < 1_600L * 1024 * 1024
    }.getOrDefault(false).also { small = it }

    fun mem(ctx: android.content.Context): String = runCatching {
        val rt = Runtime.getRuntime()
        val mi = android.app.ActivityManager.MemoryInfo()
        (ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        "app ${(rt.totalMemory() - rt.freeMemory()) / 1048576}/${rt.maxMemory() / 1048576} MB · native ${android.os.Debug.getNativeHeapAllocatedSize() / 1048576} MB · box free ${mi.availMem / 1048576} MB" + if (mi.lowMemory) " · LOW" else ""
    }.getOrDefault("?")

    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("tvsmall", android.content.Context.MODE_PRIVATE)
    @Volatile var startedAt = 0L
    /** TV 0.9.15: the last screen and key, written only with the existing minute mark (no extra work per key). */
    @Volatile var lastScreen = ""
    @Volatile var lastKey = ""
    /** The app's own memory is really short: its heap above 60 % or the box under 120 MB free. */
    fun reallyShort(ctx: android.content.Context): Boolean = runCatching {
        val rt = Runtime.getRuntime()
        val mi = android.app.ActivityManager.MemoryInfo()
        (ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        (rt.totalMemory() - rt.freeMemory()) > rt.maxMemory() * 0.6 || mi.availMem < 120L * 1024 * 1024
    }.getOrDefault(false)
    fun front(ctx: android.content.Context, on: Boolean) { runCatching { prefs(ctx).edit().putBoolean("front", on).putLong("at", System.currentTimeMillis()).putString("scr", lastScreen).putString("key", lastKey).apply() } }
    /** Called once at start: the last run ended while on screen (no "to background") → Android closed it. */
    fun checkLastRun(ctx: android.content.Context) {
        runCatching {
            val p = prefs(ctx)
            if (p.getBoolean("front", false)) ActLog.add("ERROR", "Android closed the app while you were using it (likely memory) · last seen ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(p.getLong("at", 0L)))} · ${p.getString("mem", "")} · screen: ${p.getString("scr", "")} · key: ${p.getString("key", "")}")
            // TV 0.9.15: a crash saved last time goes into the log too (it used to show only on screen).
            CrashLog.read(ctx)?.let { t -> ActLog.add("CRASH", t.lines().take(40).joinToString(" | ").take(4000)) }
        }
    }
    fun beat(ctx: android.content.Context) { runCatching { prefs(ctx).edit().putLong("at", System.currentTimeMillis()).putString("mem", mem(ctx)).putString("scr", lastScreen).putString("key", lastKey).apply() } }
}
