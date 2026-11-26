package com.example.streamnest

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Colours taken from one cover (no library; same idea as the Now playing colour in MusicApp.kt):
 *  deep   = the cover's main colour at value 0.42 (same as Now playing today), for gradients and sheets
 *  bg     = the same hue much darker (0.16), for backgrounds and scrims
 *  accent = the same hue bright (value 0.95), for buttons, rings, the lit lyric line
 *  found  = false when the cover is grey / black / white (then callers keep the chosen look's colours).
 */
data class MusicCoverColors(val deep: Color, val bg: Color, val accent: Color, val found: Boolean) {
    companion object {
        val None = MusicCoverColors(Color(0xFF2A2A2A), Color(0xFF111111), Color(0xFFE0E0E0), false)
    }
}

object MusicCover {
    private val cache = ConcurrentHashMap<String, MusicCoverColors>()

    /** Already sampled? (no work, safe on the main thread) */
    fun cached(url: String?): MusicCoverColors? = if (url.isNullOrBlank()) null else cache[url]

    suspend fun of(ctx: Context, url: String?): MusicCoverColors = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext MusicCoverColors.None
        cache[url]?.let { return@withContext it }
        val c = runCatching { sample(ctx, url) }
            .onFailure { MusicLog.add("Music look: cover colour failed (${it.javaClass.simpleName})") }
            .getOrNull() ?: return@withContext MusicCoverColors.None   // not cached: the picture may load next time
        if (cache.size > 60) cache.clear()
        cache[url] = c
        MusicLog.add("Music look: cover colour " + if (c.found) "found" else "none (grey cover), the look's own colours stay")
        c
    }

    private suspend fun sample(ctx: Context, url: String): MusicCoverColors? {
        val app = ctx.applicationContext
        val req = coil.request.ImageRequest.Builder(app).data(url).size(48).allowHardware(false).build()
        val bmp = (coil.Coil.imageLoader(app).execute(req).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            ?: return null
        val buckets = DoubleArray(24)
        val sums = Array(24) { DoubleArray(3) }
        val hsv = FloatArray(3)
        for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) {
            val p = bmp.getPixel(x, y)
            android.graphics.Color.colorToHSV(p, hsv)
            if (hsv[2] < 0.12f || (hsv[1] < 0.15f && hsv[2] > 0.9f)) continue
            val w = (hsv[1] * hsv[2]).toDouble() + 0.02
            val b = (hsv[0] / 15f).toInt().coerceIn(0, 23)
            buckets[b] += w
            sums[b][0] += android.graphics.Color.red(p) * w
            sums[b][1] += android.graphics.Color.green(p) * w
            sums[b][2] += android.graphics.Color.blue(p) * w
        }
        val best = buckets.indices.maxByOrNull { buckets[it] } ?: return MusicCoverColors.None
        if (buckets[best] <= 0) return MusicCoverColors.None
        val avg = android.graphics.Color.rgb(
            (sums[best][0] / buckets[best]).toInt(),
            (sums[best][1] / buckets[best]).toInt(),
            (sums[best][2] / buckets[best]).toInt(),
        )
        android.graphics.Color.colorToHSV(avg, hsv)
        val h = hsv[0]
        val s = hsv[1]
        fun make(sat: Float, v: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, sat, v)))
        return MusicCoverColors(
            deep = make(s.coerceAtMost(0.75f), 0.42f),
            bg = make(s.coerceIn(0.35f, 0.70f), 0.16f),
            accent = make(s.coerceIn(0.55f, 0.90f), 0.95f),
            found = true,
        )
    }
}

/** The colours of a cover; [MusicCoverColors.None] until sampled (a cached cover answers at once). Animate at the use site. */
@Composable
fun rememberMusicCover(url: String?): State<MusicCoverColors> {
    val ctx = LocalContext.current
    return produceState(MusicCover.cached(url) ?: MusicCoverColors.None, url) {
        value = MusicCover.of(ctx, url)
    }
}
