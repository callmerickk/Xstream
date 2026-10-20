package com.example.streamnest

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
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

// ───────────── Xstream TV loading screen (Rick's pick) ─────────────
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
        two.getOrNull(1)?.let { ctx.imageLoader.enqueue(tvRequest(ctx, it, 960, 540)) }
        if (two.size < 2) return@LaunchedEffect
        while (true) { delay(12_000); i = 1 - i }
    }
    Crossfade(targetState = i, animationSpec = tween(1400), label = "moment", modifier = Modifier.fillMaxSize()) { k ->
        val url = two.getOrNull(k) ?: return@Crossfade
        val t = remember(k) { Animatable(0f) }
        LaunchedEffect(k) { t.animateTo(1f, tween(13_500, easing = LinearEasing)) }
        // Zoom in on the first picture, zoom out on the second: a slow cinematic push and pull.
        val (s0, s1, dx) = if (k == 0) Triple(1.04f, 1.16f, -0.02f) else Triple(1.16f, 1.04f, 0.02f)
        AsyncImage(
            tvRequest(ctx, url, 960, 540), null,
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
    val facts = remember(info, story, title) { loadingFacts(info, story, title) }
    var factIdx by remember(facts) { mutableIntStateOf(0) }
    LaunchedEffect(facts) { while (facts.size > 1) { delay(4_200); factIdx = (factIdx + 1) % facts.size } }
    // Never goes backwards on screen.
    var shown by remember { mutableIntStateOf(0) }
    LaunchedEffect(percent) { if (percent > shown) shown = percent }
    val p by animateFloatAsState(shown / 100f, tween(400), label = "loadPct")
    val ctx = LocalContext.current

    Box(modifier.fillMaxSize().background(Color.Black)) {
        MomentBackground(stills)
        // Darkened so the front reads, a little more at the edges (vignette).
        Box(Modifier.fillMaxSize().background(Color(0x99050608)))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Color(0xCC050608)), radius = 1400f)))

        Row(Modifier.align(Alignment.Center).padding(horizontal = 70.dp), verticalAlignment = Alignment.CenterVertically) {
            // Poster with the progress running around its border.
            Box(Modifier.size(196.dp, 294.dp)) {
                Box(Modifier.fillMaxSize().padding(7.dp).shadow(24.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Surface1)) {
                    AsyncImage(tvRequest(ctx, poster ?: info?.poster ?: fallbackArt ?: "", 360, 540), title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                // The border path and its measure are built once per size; only the segment follows [p].
                androidx.compose.foundation.layout.Spacer(Modifier.fillMaxSize().drawWithCache {
                    val sw = 3.dp.toPx()
                    val inset = sw / 2f + 1.dp.toPx()
                    val r = 19.dp.toPx()
                    val path = Path().apply { addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(r, r))) }
                    val pm = PathMeasure().apply { setPath(path, false) }
                    val seg = Path()
                    val track = Stroke(sw); val glow = Stroke(sw + 4.dp.toPx(), cap = StrokeCap.Round); val line = Stroke(sw, cap = StrokeCap.Round)
                    onDrawBehind {
                        drawPath(path, Color(0x1FFFFFFF), style = track)
                        if (p > 0.002f) {
                            seg.reset()
                            pm.getSegment(0f, pm.length * p.coerceIn(0f, 1f), seg, true)
                            drawPath(seg, MomentRed, style = glow, alpha = 0.25f)
                            drawPath(seg, MomentRed, style = line)
                        }
                    }
                })
            }
            Spacer(Modifier.width(44.dp))
            Column(Modifier.widthIn(max = 470.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFD0D4DD), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("DID YOU KNOW", Modifier.padding(top = 12.dp), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.5.sp, color = Color(0xFFFF5A5F))
                AnimatedContent(
                    targetState = facts.getOrElse(factIdx) { "" },
                    transitionSpec = { (fadeIn(tween(450)) + slideInVertically(tween(450)) { it / 6 }) togetherWith fadeOut(tween(300)) },
                    label = "fact", modifier = Modifier.padding(top = 6.dp).heightIn(min = 96.dp),
                ) { f ->
                    Text(f, fontFamily = BrandFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 29.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(14.dp))
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
                if (slow) PlainBar(p, Modifier.fillMaxWidth().padding(top = 18.dp).height(6.dp))
                else HeartbeatBar(p, Modifier.fillMaxWidth().height(44.dp))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Text("$stage…", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFC9CED8))
                    Text("$shown%", fontFamily = BrandFont, fontSize = 24.sp, fontWeight = FontWeight.Light)
                }
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    facts.indices.take(6).forEach { j ->
                        Box(Modifier.size(6.dp).clip(CircleShape).background(if (j == factIdx % facts.size.coerceAtMost(6)) Color.White else Color(0x40FFFFFF)))
                    }
                }
            }
        }
    }
}

/**
 * The heartbeat progress line. The whole trace is built once per size and only revealed up to [p] (a clip), so nothing
 * is recomputed per frame; a small dot marks the head.
 */
@Composable
internal fun HeartbeatBar(p: Float, modifier: Modifier) {
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
            val head = w * p.coerceIn(0f, 1f)
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
private fun PlainBar(p: Float, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(3.dp)).background(Color(0x24FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(MomentRed))
    }
}
