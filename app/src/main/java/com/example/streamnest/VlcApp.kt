package com.example.streamnest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle

/**
 * 0.9.6 (noVlc): the very last resort. When no link of a title plays in ExoPlayer, and the user has the VLC app
 * (org.videolan.vlc), the link is opened there. Automatic, no button, nothing on the loading screen.
 * Only links that need no headers (an Intent can't carry them), never our own 127.0.0.1 relay.
 */
object VlcApp {
    private const val PKG = "org.videolan.vlc"
    private val handed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Needs <queries><package android:name="org.videolan.vlc"/></queries> in the manifest (Android 11+ package visibility). */
    fun installed(ctx: Context): Boolean = runCatching { ctx.packageManager.getLaunchIntentForPackage(PKG) != null }.getOrDefault(false)

    /** A plain http(s) link with no required headers (so VLC would get exactly what ExoPlayer would have sent). */
    fun plain(url: String): Boolean {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return false
        if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) return false
        return StreamHeaders.forUrl(url).isEmpty()
    }

    /** Hands the first suitable link to VLC. Returns true when VLC was opened. Safe to call more than once. */
    fun handOffFirst(ctx: Context, urls: List<String>, title: String, posMs: Long): Boolean {
        if (!Feature.noVlc) return false
        // Android blocks starting apps from the background: only while our screen is in front.
        if (ctx is androidx.activity.ComponentActivity && !ctx.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return false
        if (!installed(ctx)) return false
        val url = urls.firstOrNull { plain(it) && it !in handed } ?: return false
        handed.add(url)   // once per link per run: coming Back from VLC must not bounce straight into it again
        return runCatching {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndTypeAndNormalize(Uri.parse(url), "video/*")
                .setPackage(PKG)
                .putExtra("title", title)                        // VLC extras: title, position (ms), from_start
                .putExtra("position", posMs.coerceAtLeast(0L))
                .putExtra("from_start", posMs < 1_000L)
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            ActLog.add("PLAY", "handed to the VLC app · " + (Uri.parse(url).host ?: "?"))
            true
        }.getOrElse { handed.remove(url); false }
    }
}

