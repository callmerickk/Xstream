package com.example.streamnest

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.sin

// ───────────── StreamX TV loading screen (Rick's pick) ─────────────
//  Background: a slow-motion "moment" — the title's own scene stills, slowly zooming and drifting, crossfading.
//  Front: the poster, with the loading progress running around its border; beside it "Did you know?" facts about the
//  title, and the heartbeat line writing itself across as the video loads (real percentage + what's happening).

private val MomentRed = Color(0xFFE50914)

/** Short facts about a title from TMDB (or its story when TMDB has nothing). */
fun loadingFacts(info: TmdbInfo?, story: String?, title: String): List<String> {
    val out = ArrayList<String>()
    if (info != null) {
        info.tagline?.takeIf { it.isNotBlank() }?.let { out += "Its tagline: “${it.trim().trimEnd('.')}.”" }
        val cast = info.cast.take(3).map { it.name }
        if (cast.size >= 2) out += "Starring ${cast.dropLast(1).joinToString(", ")} and ${cast.last()}."
        info.cast.firstOrNull { it.role.isNotBlank() }?.let { out += "${it.name} plays ${it.role.substringBefore(" /").trim()}." }
        if (info.directors.isNotEmpty()) out += (if (info.kind == "tv") "Created by " else "Directed by ") + info.directors.take(2).joinToString(" and ") + "."
        if (info.writers.isNotEmpty() && info.kind != "tv") out += "Written by " + info.writers.take(2).joinToString(" and ") + "."
        fun money(v: Long) = if (v >= 1_000_000_000L) String.format(Locale.US, "$%.1f billion", v / 1e9) else "$${v / 1_000_000L} million"
        if (info.budget >= 1_000_000L) out += "It was made on a ${money(info.budget)} budget."
        if (info.revenue >= 1_000_000L) out += "It earned ${money(info.revenue)} at the box office."
        info.companies.take(2).map { it.name }.takeIf { it.isNotEmpty() }?.let { out += "From ${it.joinToString(" and ")}." }
        val when_ = info.release?.take(4)
        if (when_ != null && info.runtime != null) out += "Released in $when_ · ${if (info.kind == "tv") "episodes run about" else "it runs"} ${info.runtime}."
        if (info.genres.size >= 2) out += "A mix of ${info.genres.take(3).joinToString(", ").lowercase()}."
    }
    if (out.size < 3) (info?.overview ?: story)?.split(Regex("(?<=[.!?])\\s+"))?.filter { it.length in 30..170 }?.take(3)?.let { out += it }
    if (out.isEmpty()) out += "$title is almost ready."
    return out.distinct().take(8)
}

/**
 * The background moment: at most two scene stills, loaded once at screen size. The first slowly zooms in, then (every
 * 12 s) a soft crossfade to the other one zooming out, and back. Only the graphics chip moves them (no redraws), so the
 * remote and the player keep full speed on a Fire TV Stick.
 */
@Composable
private fun MomentBackground(stills: List<String>) {
    val ctx = LocalContext.current
    val two = remember(stills) { stills.take(2) }
    var i by remember(two) { mutableIntStateOf(0) }
    LaunchedEffect(two) {
        two.getOrNull(1)?.let { ctx.imageLoader.enqueue(tvRequest(ctx, it, 640, 360)) }
        if (two.size < 2) return@LaunchedEffect
        while (true) { delay(12_000); i = 1 - i }
    }
    Crossfade(targetState = i, animationSpec = tween(1400), label = "moment", modifier = Modifier.fillMaxSize()) { k ->
        val url = two.getOrNull(k) ?: return@Crossfade
        val t = remember(k) { Animatable(0f) }
        // TV 0.8.4: on a 1 GB stick the picture stays still (a moving picture stutters while the stream opens).
        LaunchedEffect(k) { if (!TvSmall.on(ctx)) t.animateTo(1f, tween(13_500, easing = LinearEasing)) }
        // Zoom in on the first picture, zoom out on the second: a slow cinematic push and pull.
        val (s0, s1, dx) = if (k == 0) Triple(1.04f, 1.16f, -0.02f) else Triple(1.16f, 1.04f, 0.02f)
        AsyncImage(
            tvRequest(ctx, url, 640, 360), null,
            Modifier.fillMaxSize().graphicsLayer {
                val v = t.value
                val sc = s0 + (s1 - s0) * v
                scaleX = sc; scaleY = sc
                translationX = size.width * dx * v
            },
            contentScale = ContentScale.Crop,
        )
    }
}

/** True when this TV draws the loading screen too slowly (over ~35 ms a frame): the plain progress bar is used then. */
private object LoaderPerf { @Volatile var slow: Boolean? = null }

/**
 * TV loading screen. [percent] 0..100 (real: finding a source, connecting, loading the first minutes), [stage] what is
 * happening now. [fallbackArt] is used as the moment when the title has no scene stills.
 */
@Composable
fun TvMomentLoader(
    vm: AppViewModel, imdbId: String, type: String, title: String, poster: String?, fallbackArt: String?, story: String?,
    stage: String, percent: Int, modifier: Modifier = Modifier,
) {
    var info by remember(imdbId) { mutableStateOf<TmdbInfo?>(null) }
    LaunchedEffect(imdbId) { if (imdbId.startsWith("tt")) info = runCatching { vm.tmdbInfo(imdbId, type) }.getOrNull() }
    val stills = remember(info, fallbackArt) { info?.backdrops?.takeIf { it.isNotEmpty() } ?: listOfNotNull(info?.backdrop ?: fallbackArt ?: poster) }
    // TV 0.8.3: one smooth, continuous run to 95 %, then it holds there until the picture starts (no steps, no jumps).
    // Only the drawing reads it, so nothing else on the screen is redrawn while it moves.
    val prog = remember { Animatable(0f) }
    LaunchedEffect(Unit) { prog.animateTo(0.95f, tween(16_000, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.55f, 0.25f, 1f))) }
    val p: () -> Float = { prog.value }
    val ctx = LocalContext.current

    // TV 0.7.4 layout: the title's picture blurred behind (a tiny copy stretched up: a free blur on Android 9), a large
    // landscape card in the middle with the moment moving inside it and the progress running around its edge, and the
    // heartbeat line with the percentage under it.
    Box(modifier.fillMaxSize().background(Color.Black)) {
        // TV 0.7.5: no poster behind — a soft, dark "frosted" glow only (one gradient: nothing to decode, no lag).
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF1C1A22), Color(0xFF0B0A0F), Color.Black), radius = 1500f)))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(536.dp, 314.dp)) {
                Box(Modifier.fillMaxSize().padding(8.dp).shadow(26.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(Surface1)) {
                    MomentBackground(stills)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xB3000000))))
                    Text(title, Modifier.align(Alignment.BottomStart).padding(18.dp), fontFamily = BrandFont, fontSize = 24.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // TV 0.8.4: the border progress runs on the screen's drawing thread (an animated vector), so it stays smooth
                // while the app is busy opening the stream. The drawn version below is the fallback.
                val borderId = remember { ctx.resources.getIdentifier("tv_loader_border", "drawable", ctx.packageName) }
                if (borderId != 0) RtAnim(borderId, Modifier.fillMaxSize())
                else androidx.compose.foundation.layout.Spacer(Modifier.fillMaxSize().drawWithCache {
                    val sw = 3.dp.toPx()
                    val inset = sw / 2f + 2.dp.toPx()
                    val r = 27.dp.toPx()
                    val path = Path().apply { addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(r, r))) }
                    val pm = PathMeasure().apply { setPath(path, false) }
                    val seg = Path()
                    val track = Stroke(sw); val glow = Stroke(sw + 4.dp.toPx(), cap = StrokeCap.Round); val line = Stroke(sw, cap = StrokeCap.Round)
                    onDrawBehind {
                        drawPath(path, Color(0x1FFFFFFF), style = track)
                        val pv = p()
                        if (pv > 0.002f) {
                            seg.reset()
                            pm.getSegment(0f, pm.length * pv.coerceIn(0f, 1f), seg, true)
                            drawPath(seg, MomentRed, style = glow, alpha = 0.25f)
                            drawPath(seg, MomentRed, style = line)
                        }
                    }
                })
            }
            val beatId = remember { ctx.resources.getIdentifier("tv_loader_heartbeat", "drawable", ctx.packageName) }
            if (beatId != 0) {
                Box(Modifier.width(380.dp).padding(top = 18.dp)) { RtAnim(beatId, Modifier.fillMaxWidth().height(40.dp)) }
                Text("$stage…", Modifier.width(380.dp).padding(top = 4.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFC9CED8))
                return@Column
            }
            // Measures how fast this TV draws the first ~40 frames; slow TVs get the plain bar.
            var slow by remember { mutableStateOf(LoaderPerf.slow == true) }
            LaunchedEffect(Unit) {
                if (LoaderPerf.slow != null) return@LaunchedEffect
                val t0 = withFrameNanos { it }; var n = 0; var last = t0
                while (n < 40) { last = withFrameNanos { it }; n++ }
                val avgMs = (last - t0) / 1_000_000f / n
                LoaderPerf.slow = avgMs > 35f
                slow = LoaderPerf.slow == true
                if (slow) ActLog.add("APP", "loading screen: plain bar (%.0f ms a frame)".format(avgMs))
            }
            Box(Modifier.width(380.dp).padding(top = 18.dp)) {
                if (slow) PlainBar(p, Modifier.fillMaxWidth().padding(top = 18.dp).height(5.dp))
                else HeartbeatBar(p, Modifier.fillMaxWidth().height(40.dp))
            }
            Row(Modifier.width(380.dp).padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                Text("$stage…", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFC9CED8))
                LoaderPercent(p)
            }
        }
    }
}

/**
 * The heartbeat progress line. The whole trace is built once per size and only revealed up to [p] (a clip), so nothing
 * is recomputed per frame; a small dot marks the head.
 */
/** An animated vector (runs on the drawing thread from Android 7.1 on), started once. */
@Composable
private fun RtAnim(id: Int, modifier: Modifier) {
    androidx.compose.ui.viewinterop.AndroidView({ c ->
        android.widget.ImageView(c).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_XY
            val d = androidx.core.content.ContextCompat.getDrawable(c, id)
            setImageDrawable(d)
            (d as? android.graphics.drawable.Animatable)?.start()
        }
    }, modifier)
}

@Composable
private fun LoaderPercent(p: () -> Float) {
    Text("${(p() * 100).toInt()}%", fontFamily = BrandFont, fontSize = 22.sp, fontWeight = FontWeight.Light)
}

@Composable
internal fun HeartbeatBar(p: Float, modifier: Modifier) = HeartbeatBar({ p }, modifier)

@Composable
internal fun HeartbeatBar(p: () -> Float, modifier: Modifier) {
    androidx.compose.foundation.layout.Spacer(modifier.drawWithCache {
        val w = size.width; val h = size.height
        val y0 = h * 0.62f; val amp = h * 0.5f; val per = w / 7f
        fun beat(t0: Float): Float {
            val t = ((t0 % 1f) + 1f) % 1f * 100f
            return when {
                t < 10f -> 0f; t < 13f -> -0.06f * sin((t - 10f) / 3f * Math.PI.toFloat()); t < 16f -> 0f
                t < 17.5f -> 0.12f * ((t - 16f) / 1.5f); t < 19.5f -> 0.12f - 1.12f * ((t - 17.5f) / 2f)
                t < 21.5f -> -1f + 1.4f * ((t - 19.5f) / 2f); t < 23f -> 0.4f - 0.4f * ((t - 21.5f) / 1.5f)
                t < 34f -> -0.16f * sin((t - 23f) / 11f * Math.PI.toFloat()); else -> 0f
            }
        }
        val path = Path()
        var x = 0f
        while (x <= w) { val y = y0 + beat(x / per) * amp; if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y); x += 3f }
        val glow = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val line = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val brush = Brush.horizontalGradient(listOf(Color(0x40E50914), Color(0xE6FF464F), Color.White), startX = 0f, endX = w)
        onDrawBehind {
            val head = w * p().coerceIn(0f, 1f)
            drawLine(Color(0x24FFFFFF), Offset(head, y0), Offset(w, y0), 1.5f)
            if (head < 2f) return@onDrawBehind
            clipRect(right = head) {
                drawPath(path, MomentRed, style = glow, alpha = 0.22f)
                drawPath(path, brush, style = line)
            }
            val hy = y0 + beat(head / per) * amp
            drawCircle(Color(0x66FF5A5F), 6.dp.toPx(), Offset(head, hy))
            drawCircle(Color.White, 3.dp.toPx(), Offset(head, hy))
        }
    })
}

/** Plain progress bar (used on TVs that draw the heartbeat too slowly). */
@Composable
private fun PlainBar(p: () -> Float, modifier: Modifier) {
    androidx.compose.foundation.layout.Spacer(modifier.clip(RoundedCornerShape(3.dp)).background(Color(0x24FFFFFF)).drawBehind {
        drawRect(MomentRed, size = Size(size.width * p().coerceIn(0f, 1f), size.height))
    })
}
