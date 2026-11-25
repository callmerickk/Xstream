package com.example.streamnest

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 0.9.7 (glassPlus): in Glass, full pages (Settings, Notifications, Library, Watchlist, collections, Search) sit on a
 * smoky sheet: a ~60 % dark tint over the backdrop (already drawn blurred behind every page) and a thin light edge at
 * the top, so text stays readable. Off / not Glass / TV / big screen / video playing = a plain Box.
 */
@Composable
fun GlassSheet(modifier: Modifier = Modifier.fillMaxSize(), content: @Composable BoxScope.() -> Unit) {
    val on = Feature.glassPlus && !BuildConfig.IS_TV && !BigScreen.on && Palette.glass && !GlassBg.paused
    if (!on) { Box(modifier, content = content); return }
    Box(modifier) {
        Box(Modifier.matchParentSize().background(Color(0xFF05060C).copy(alpha = 0.60f)))
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(1.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.30f), Color.Transparent))))
        content()
    }
}

/** 0.9.7 (glassPlus): first start after an update with Glass on = Glass drawn again once; the backdrop address comes back from disk. */
object GlassUpdate {
    @Volatile private var lastSaved: String? = null
    private fun f(ctx: Context) = AppStore.file(ctx, "glass_url.txt")

    fun saveUrl(u: String) {
        if (u == lastSaved || BuildConfig.IS_TV) return
        lastSaved = u
        val ctx = Public.vm?.appContext ?: return
        Thread { runCatching { f(ctx).writeText(u) } }.start()
    }
    private fun restoreUrl(ctx: Context): String? = runCatching { f(ctx).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() } }.getOrNull()

    /** Once from App(). */
    fun run(vm: AppViewModel) {
        if (!Feature.glassPlus || BuildConfig.IS_TV) return
        val ctx = vm.appContext
        if (GlassBg.url == null) restoreUrl(ctx)?.let { lastSaved = it; GlassBg.url = it }   // never a black start
        val p = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)
        if (p.getInt("appliedVer", -1) == BuildConfig.VERSION_CODE) return
        p.edit().putInt("appliedVer", BuildConfig.VERSION_CODE).apply()
        if (!Palette.glass) return
        // Glass off and on once: everything that reads it is drawn again (the backdrop from disk, then the new picture).
        GlassBg.diskTried = false
        runCatching { TvGlass.blurred.clear() }
        Palette.glass = false
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Palette.glass = true }, 60)
        ActLog.add("APP", "glass · re-applied once after the update · look · Glass off and on · by update re-apply")
    }
}

/**
 * 0.9.7 (glassPlus): Glass backdrop health in the activity log (uploaded with the other APP lines). One line per start
 * (look, where the first backdrop came from, how long it took); then, checked every 5 s, "black" when Glass is the
 * look but no backdrop is drawn (no address, picture failed, still paused after the player closed, reset) — once per
 * reason a minute — and "back" when it shows again. Look changes say who made them (AppViewModel.applyPalette).
 */
object GlassHealth {
    private val t0 = android.os.SystemClock.elapsedRealtime()
    @Volatile private var startLogged = false
    @Volatile private var firstFrom: String? = null
    @Volatile private var failedUrl: String? = null
    @Volatile var screen = "Home"
    /** The player or a full-screen trailer is open (the backdrop is meant to be off). */
    @Volatile var fullVideo = false
    private val lastReason = HashMap<String, Long>()
    private var blackSince = 0L

    private fun short(u: String?): String = u?.let { runCatching { val x = android.net.Uri.parse(it); (x.host ?: "?") + "/…" + (x.lastPathSegment ?: "").takeLast(12) }.getOrNull() } ?: "none"

    /** GlassBackdrop drew a picture: from the disk copy or from its address. */
    fun shown(from: String) {
        if (!Feature.glassPlus || BuildConfig.IS_TV) return
        failedUrl = null
        if (firstFrom == null) firstFrom = from
        logStart()
    }
    fun failed(url: String?) { if (Feature.glassPlus && !BuildConfig.IS_TV) failedUrl = url ?: "?" }

    private fun logStart(missing: Boolean = false) {
        if (startLogged) return
        startLogged = true
        val look = if (Palette.glass) "Glass" else "other"
        val bd = if (missing || firstFrom == null) "missing" else "shown from ${firstFrom}"
        ActLog.add("APP", "glass · start · look=$look · backdrop $bd · url ${short(GlassBg.url)} · ${android.os.SystemClock.elapsedRealtime() - t0} ms to first backdrop")
    }

    /** Runs from App() while the app is open: one look every 5 s, no work per frame. */
    suspend fun watch() {
        if (!Feature.glassPlus || BuildConfig.IS_TV) return
        var waited = 0L
        while (true) {
            kotlinx.coroutines.delay(5_000); waited += 5_000
            if (!startLogged && (!Palette.glass || waited >= 15_000)) logStart(missing = Palette.glass)
            if (!Palette.glass || BigScreen.on) { blackSince = 0L; continue }
            val reason = when {
                GlassBg.paused && !fullVideo -> "still paused after the player / trailer closed"
                GlassBg.paused -> null
                GlassBg.shown.value != null -> null
                GlassBg.url == null -> "no backdrop address"
                failedUrl != null -> "picture failed to load (${short(failedUrl)})"
                else -> "backdrop reset (nothing drawn yet)"
            }
            val now = System.currentTimeMillis()
            if (reason == null) {
                if (blackSince > 0) ActLog.add("APP", "glass · back · after ${(now - blackSince) / 1000} s")
                blackSince = 0L; continue
            }
            if (blackSince == 0L) blackSince = now
            val key = reason.substringBefore(" (")
            if (now - (lastReason[key] ?: 0L) >= 60_000) { lastReason[key] = now; ActLog.add("APP", "glass · black · reason $reason · screen $screen") }
        }
    }
}
