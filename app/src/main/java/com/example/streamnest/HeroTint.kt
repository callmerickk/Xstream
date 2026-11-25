package com.example.streamnest

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** 0.9.7 (glassPlus): one lively colour per Home slide from its tiny poster; worked out once, kept in app storage. */
object HeroTint {
    val color = mutableStateOf(Color(0xFF3A3F5C))
    val has = mutableStateOf(false)
    private val mem = ConcurrentHashMap<String, Int>()
    @Volatile private var loaded = false

    private fun thumb(u: String) = u.replace(Regex("/t/p/(original|w\\d+)/"), "/t/p/w92/")
    private fun key(u: String) = Integer.toHexString(u.hashCode())
    private fun store(ctx: Context) = AppStore.file(ctx, "hero-colors.txt")

    private fun load(ctx: Context) {
        if (loaded) return; loaded = true
        runCatching { store(ctx).takeIf { it.exists() }?.readLines()?.forEach { l -> val p = l.split(','); if (p.size == 2) p[1].toIntOrNull()?.let { mem[p[0]] = it } } }
    }

    /** The tint for the slide whose poster is [poster] (call from a LaunchedEffect). */
    suspend fun show(ctx: Context, poster: String?) {
        if (!Feature.glassPlus || poster.isNullOrBlank()) return
        val t = thumb(poster); val k = key(t)
        val c = withContext(Dispatchers.Default) {
            load(ctx)
            mem[k] ?: runCatching {
                val r = coil.request.ImageRequest.Builder(ctx).data(t).size(46, 69).allowHardware(false).build()
                val bm = ((coil.Coil.imageLoader(ctx).execute(r) as? coil.request.SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                bm?.let { vibrant(it) } ?: 0
            }.getOrDefault(0).also { v ->
                if (v != 0) {
                    mem[k] = v
                    runCatching {
                        if (mem.size > 300) mem.keys.take(100).forEach { mem.remove(it) }
                        store(ctx).writeText(mem.entries.joinToString("\n") { "${it.key},${it.value}" })
                    }
                }
            }
        }
        if (c != 0) { color.value = Color(c); has.value = true }
    }

    /** Average weighted by saturation and brightness, then lifted into a pleasant range. */
    private fun vibrant(bm: Bitmap): Int {
        val w = bm.width; val h = bm.height
        if (w <= 0 || h <= 0) return 0
        val px = IntArray(w * h); bm.getPixels(px, 0, w, 0, 0, w, h)
        val hsv = FloatArray(3)
        var r = 0.0; var g = 0.0; var b = 0.0; var wt = 0.0
        for (c in px) {
            if (android.graphics.Color.alpha(c) < 200) continue
            android.graphics.Color.colorToHSV(c, hsv)
            val s = hsv[1]; val v = hsv[2]
            if (v < 0.16f || (s < 0.10f && v > 0.92f)) continue
            val k = (0.15 + s * s) * (0.4 + v)
            r += android.graphics.Color.red(c) * k; g += android.graphics.Color.green(c) * k; b += android.graphics.Color.blue(c) * k; wt += k
        }
        if (wt <= 0.0) return 0
        android.graphics.Color.colorToHSV(android.graphics.Color.rgb((r / wt).toInt(), (g / wt).toInt(), (b / wt).toInt()), hsv)
        hsv[1] = hsv[1].coerceIn(0.45f, 0.90f); hsv[2] = hsv[2].coerceIn(0.38f, 0.72f)
        return android.graphics.Color.HSVToColor(hsv)
    }
}
