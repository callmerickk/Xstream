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
        ActLog.init(this)
        CrashLog.logExitReasons(this)
        // App icon picked with "Later": apply it now that the app was fully closed and opened again.
        if (!started) { started = true; AppIcons.signalOnce(this); AppIcons.applyPendingOnColdStart(this); AppIcons.keepOne(this) }
        // Image loader that can also play GIFs and show video thumbnails (for profile pictures).
        Coil.setImageLoader(
            ImageLoader.Builder(this).components {
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory(enforceMinimumFrameDelay = true)) else add(GifDecoder.Factory(enforceMinimumFrameDelay = true))
                add(VideoFrameDecoder.Factory())
                if (!BuildConfig.IS_TV) add(HdPoster)
            }.okHttpClient { Posters.client() }.crossfade(if (BuildConfig.IS_TV) 0 else 120).allowRgb565(true)
                .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.25).build() }
                .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("posters")).maxSizeBytes(400L * 1024 * 1024).build() }
                .respectCacheHeaders(false)
                .build()
        )
        WidgetOpen.take(intent)
        JoinIntent.take(intent)
        SocialIntent.take(intent)
        enableEdgeToEdge()
        // Xstream TV (tv flavor) gets the remote-friendly TV interface; the phone app keeps its own.
        setContent {
            StreamNestTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    if (BuildConfig.IS_TV) TvApp() else App()
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
    }

    // Activity log: every tap and remote key, with the button it hit.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val r = super.dispatchTouchEvent(ev)
        runCatching { ActLog.onTouch(window.decorView, ev) }
        return r
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val r = super.dispatchKeyEvent(event)
        runCatching { ActLog.onKey(window.decorView, event) }
        return r
    }

    override fun onPause() { super.onPause(); ActLog.add("APP", "to background") }
    override fun onResume() { super.onResume(); com.lagradost.cloudstream3.CommonActivity.activity = this; ActLog.add("APP", "in front"); runCatching { Cloud.onAppForeground() } }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        WidgetOpen.take(intent)
        JoinIntent.take(intent)
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
