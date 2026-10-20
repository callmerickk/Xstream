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
        // App icon picked with "Later": apply it now that the app was fully closed and opened again.
        if (!started) { started = true; AppIcons.signalOnce(this); AppIcons.applyPendingOnColdStart(this); AppIcons.keepOne(this) }
        // Image loader that can also play GIFs and show video thumbnails (for profile pictures).
        Coil.setImageLoader(
            ImageLoader.Builder(this).components {
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
                add(VideoFrameDecoder.Factory())
            }.okHttpClient { Api.http }.crossfade(!BuildConfig.IS_TV).allowRgb565(BuildConfig.IS_TV).build()
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
                    val testing = srcTesting || labRunning
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
