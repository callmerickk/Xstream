package com.example.streamnest

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.sin

// ───────────── StreamX TV only: pieces used by the TV screens (the phone never shows these) ─────────────

private val TxShape = RoundedCornerShape(14.dp)
private val TxPanel = Color(0xF5111216)
private val TxRim = Color(0x47E50914)
private val TxMuted = Color(0xFF9AA3B5)
private val TxGreen = Color(0xFF3CCF7A)

/** Things the TV screens hand to each other (title page → player). */
object TvFlags {
    /** "Watch together" chosen on a title page: the player opens the Watch together panel once the picture is on. */
    @Volatile var openTogether = false
}

private fun Modifier.txFocusBox(focused: Boolean, shape: androidx.compose.ui.graphics.Shape = TxShape, idle: Color = Color.Transparent): Modifier =
    this.clip(shape).background(if (focused) TvFocusFill else idle).border(2.dp, if (focused) TvFocusEdge else Color.Transparent, shape)

/** A focusable row / button for the TV: OK runs [onClick]. */
@Composable
fun TxButton(
    modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = TxShape, idle: Color = Color(0x12FFFFFF),
    onFocus: (Boolean) -> Unit = {}, onClick: () -> Unit, content: @Composable RowScope.(Boolean) -> Unit,
) {
    var f by remember { mutableStateOf(false) }
    val cb by rememberUpdatedState(onFocus)
    Row(
        modifier.onFocusChanged { if (it.isFocused != f) { f = it.isFocused; cb(it.isFocused) } }
            .txFocusBox(f, shape, idle)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(f) }
}

/**
 * A slider the TV remote can reach and move: ◀ ▶ step it (shown with the TV focus box). Only used on TV — the phone keeps
 * its Material slider.
 */
@Composable
fun TvSlider(
    value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, step: Float,
    modifier: Modifier = Modifier, enabled: Boolean = true, accent: Color = Color(0xFFE50914),
) {
    var f by remember { mutableStateOf(false) }
    val cur by rememberUpdatedState(value)
    val cb by rememberUpdatedState(onValueChange)
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val frac = ((value - range.start) / span).coerceIn(0f, 1f)
    Box(
        modifier.fillMaxWidth().height(36.dp)
            .onFocusChanged { f = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (!enabled || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { cb((cur - step).coerceIn(range.start, range.endInclusive)); true }
                    Key.DirectionRight -> { cb((cur + step).coerceIn(range.start, range.endInclusive)); true }
                    else -> false
                }
            }
            .focusable(enabled)
            .txFocusBox(f, RoundedCornerShape(18.dp))
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            val y = size.height / 2f
            val sw = 4.dp.toPx()
            drawLine(Color(0x33FFFFFF), Offset(0f, y), Offset(size.width, y), sw, StrokeCap.Round)
            drawLine(accent, Offset(0f, y), Offset(size.width * frac, y), sw, StrokeCap.Round)
            val r = if (f) 8.dp.toPx() else 6.dp.toPx()
            if (f) drawCircle(Color(0x2EFFFFFF), r + 6.dp.toPx(), Offset(size.width * frac, y))
            drawCircle(Color.White, r, Offset(size.width * frac, y))
        }
    }
}

// ───────────── "How was it?" for the remote ─────────────

/**
 * The phone's vote capsule made for the remote: it opens with focus on its first choice, ◀ ▶ move between the three,
 * OK votes, and Back closes only the capsule (never the page behind it).
 */
@Composable
fun TvVoteCapsule(meta: Meta, onVote: (Int) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    var chosen by remember { mutableStateOf<Int?>(null) }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(Unit) { repeat(10) { delay(70); if (first.tryFocus()) return@LaunchedEffect } }
    LaunchedEffect(chosen) { if (chosen != null) { delay(1400); dismiss() } }
    val name = meta.name.substringBefore("  S").ifBlank { "this" }
    // TV 0.8.7: its own window over a darkened page — the remote can't slip to the page behind (◀ from the first choice
    // used to land on a card there), Back closes only the capsule.
    androidx.compose.ui.window.Dialog(onDismissRequest = { dismiss() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    Box(
        Modifier.fillMaxSize().background(Color(0xC7000000))
            .onPreviewKeyEvent { e -> e.key == Key.DirectionUp || e.key == Key.DirectionDown },
        Alignment.BottomCenter,
    ) {
        val shape = RoundedCornerShape(34.dp)
        Column(
            Modifier.padding(bottom = 56.dp).width(600.dp).clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xF7221E28), Color(0xFA141218)))).border(1.dp, Color(0x1FFFFFFF), shape)
                .padding(18.dp),
        ) {
            Row(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(meta.poster, null, Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF22222A)), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text("How was $name?", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Tunes your recommendations · Back to skip", fontSize = 12.sp, color = TxMuted)
                }
            }
            val c = chosen
            if (c == null) Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Triple("🔥 Go for it", 1, true), Triple("👎 Skip", -1, false), Triple("🌊 Just surfing", 0, false)).forEachIndexed { i, (label, v, primary) ->
                    TxButton(
                        Modifier.weight(1f).fillMaxHeight().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                        shape = RoundedCornerShape(26.dp), idle = if (primary) Color(0xFFD10A14) else Color(0x1AFFFFFF),
                        onClick = { chosen = v; onVote(v) },
                    ) { _ ->
                        Text(label, Modifier.fillMaxWidth(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
            } else Text(
                when (c) { 1 -> "Got it — more like this coming up 🔥"; -1 -> "Got it — we'll show less like this"; else -> "No problem — enjoy surfing 🌊" },
                Modifier.fillMaxWidth().height(52.dp).wrapContentHeight(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
        }
    }
    }
}

// ───────────── Loading screen: heartbeat monitor with a real percentage ─────────────

/**
 * TV loading screen: a heart-monitor line sweeping across, the percentage loaded (big, monitor green) and what is
 * happening right now. Drawn on one Canvas; only the sweep animates.
 */
@Composable
fun TvHeartbeat(title: String, stage: String, percent: Int, modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "ecg")
    val t by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    val beat by tr.animateFloat(1f, 1.18f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "beat")
    Box(modifier.fillMaxSize().background(Color(0xE606070B))) {
        Text(
            buildString { append("Loading  "); append(title) }, Modifier.padding(start = 48.dp, top = 34.dp),
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFC9CED8), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Canvas(Modifier.fillMaxWidth().height(230.dp).align(Alignment.Center)) {
            val w = size.width; val h = size.height
            val grid = w / 40f
            var gx = 0f
            while (gx < w) { drawLine(Color(0x123CCF7A), Offset(gx, 0f), Offset(gx, h), 1f); gx += grid }
            var gy = 0f
            while (gy < h) { drawLine(Color(0x123CCF7A), Offset(0f, gy), Offset(w, gy), 1f); gy += grid }
            // One beat every quarter of the width; the trace fades behind the sweep and leaves a gap ahead of it.
            fun v(x: Float): Float {
                val p = ((x / (w / 4f)) % 1f) * 70f
                return when {
                    p < 4f -> 0f; p < 6f -> -0.08f; p < 8f -> 0f; p < 9f -> 0.1f; p < 11f -> -0.85f; p < 13f -> 0.35f; p < 15f -> 0f
                    p < 26f -> -0.12f * sin((p - 15f) / 11f * Math.PI.toFloat())
                    else -> 0f
                }
            }
            val head = t * w
            val gap = w * 0.05f
            val base = h * 0.6f
            val seg = 8
            for (s in 0 until seg) {
                val x0 = w * s / seg; val x1 = w * (s + 1) / seg
                val path = Path()
                var started = false
                var x = x0
                while (x <= x1) {
                    val age = ((head - x) % w + w) % w
                    if (age > w - gap) { started = false } else {
                        val y = base + v(x) * h * 0.55f
                        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                    }
                    x += 3f
                }
                val age = ((head - (x0 + x1) / 2f) % w + w) % w
                drawPath(path, TxGreen.copy(alpha = (1f - age / w).coerceIn(0.12f, 1f)), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            drawCircle(Color(0xFFBFFFD9), 4.dp.toPx(), Offset(head, base + v(head) * h * 0.55f))
        }
        Column(Modifier.align(Alignment.TopEnd).padding(top = 120.dp, end = 80.dp), horizontalAlignment = Alignment.End) {
            Text("$percent%", fontSize = 76.sp, fontWeight = FontWeight.ExtraBold, color = TxGreen, lineHeight = 78.sp)
            Text("LOADED", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp, color = Color(0xFF7FE7AB))
        }
        Row(Modifier.align(Alignment.BottomStart).padding(start = 60.dp, bottom = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("❤", Modifier.graphicsLayer { scaleX = beat; scaleY = beat }, fontSize = 30.sp, color = Color(0xFFE50914))
            Column(Modifier.padding(start = 14.dp)) {
                Text("$stage…", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Pulse steady. Picture incoming.", fontSize = 12.sp, color = TxMuted)
            }
        }
    }
}

// ───────────── Quality (simple) and Sources (by quality, with the picture tags) ─────────────

/** The four TV qualities. */
/**
 * TV 0.8.5 — YouTube-style lines: Auto (the streaming playlist, adapts by itself) · 4K (only on a TV that can play it) ·
 * 1080p 60fps (not on a box that couldn't) · 1080p · 720p · 480p. Every source is also under "Other streams".
 */
val TvQualities = listOf("Auto", "4K", "1080p 60fps", "1080p", "720p", "480p")

/**
 * TV 0.9.6: the best picture inside a streaming playlist, read from its list of levels (RESOLUTION=…) — so an
 * "unlabelled" playlist (Netflix / Hotstar / Prime) shows up under 1080p / 720p in the quality sheet. Read in the
 * background after the first picture, a few KB each, remembered per link.
 */
object HlsTop {
    private val known = java.util.concurrent.ConcurrentHashMap<String, Int>()
    fun of(url: String?): Int? = url?.let { known[it] }?.takeIf { it > 0 }
    suspend fun read(urls: List<String>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        for (u in urls.filter { !known.containsKey(it) }.take(6)) {
            known[u] = 0
            val h = runCatching {
                val b = okhttp3.Request.Builder().url(u)
                StreamHeaders.forUrl(u).forEach { (k, v) -> b.header(k, v) }
                if (StreamHeaders.forUrl(u).keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", PlayerUserAgent)
                Api.http.newBuilder().callTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build().newCall(b.build()).execute().use { r ->
                    val t = r.body?.source()?.let { src -> src.request(65_536); src.buffer.snapshot(minOf(65_536L, src.buffer.size).toInt()).utf8() }.orEmpty()
                    Regex("RESOLUTION=\\d+x(\\d+)").findAll(t).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0
                }
            }.getOrDefault(0)
            known[u] = h
            if (h > 0) ActLog.add("PLAY", "TV: playlist quality read — ${h}p · ${android.net.Uri.parse(u).host}")
        }
    }
}

/** A quality row for a playlist whose levels were read ([HlsTop]); null = not known (stays under Auto). */
fun hlsRow(s: StreamItem): String? = HlsTop.of(s.stream.url)?.let { h -> when { h >= 1440 -> "4K"; h >= 1000 -> "1080p"; h >= 700 -> "720p"; else -> "480p" } }

fun tvQualityOf(s: StreamItem): String = when {
    s.isPlaylist() -> "Auto"
    s.effectiveRes() >= 1440 -> if (TvWeak.k4) "4K" else "4K (not on this TV)"
    s.effectiveRes() >= 1000 && "60 fps" in VisualTags.video(s) -> if (TvWeak.no60) "60fps (not on this TV)" else "1080p 60fps"
    s.effectiveRes() >= 1000 -> "1080p"
    s.effectiveRes() >= 700 -> "720p"
    else -> "480p"
}

/** VegaMovies or RogMovies (the same family of sites). */
fun StreamItem.isVegaRog(): Boolean = isVegamovies() ||
    listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename, stream.url, addonName).any { it.contains("rogmovies", true) }

/**
 * Every playable link sorted into the four qualities: Instant = streaming playlists (start at once); 4K / Full HD /
 * Mid HD = files, the preferred HD source first (when its language is as good as the rest), then your language, then
 * how reliably the server played.
 */
fun AppViewModel.tvGroups(all: List<StreamItem>): Map<String, List<StreamItem>> {
    val ok = all.filter { it.stream.url != null && !Ranker.isDead(it) && !Ranker.keepsFailing(it) && !it.unplayableHere() && HostStats.penalty(it.stream.url.orEmpty()) == 0 }
    return TvQualities.associateWith { q ->
        val l = ok.filter { tvQualityOf(it) == q }
        if (q == "Auto") l.sortedWith(compareBy<StreamItem>({ langRank(it) }, { -Ranker.score(it) }))
        else {
            val bestLang = l.minOfOrNull { langRank(it) } ?: 0
            l.sortedWith(compareBy<StreamItem>(
                // Small boxes: H.264 copies first (their HEVC decoder drops frames on big files).
                { if (TvWeak.on && TvWeak.hevc(it)) 1 else 0 },
                { if (it.isVegaRog() && (langRank(it) <= bestLang || "multi" in it.languages())) 0 else 1 },
                { langRank(it) }, { -Ranker.score(it) },
            )) +
            // TV 0.9.6: and the streaming playlists whose best level is this quality (after the files).
            ok.filter { it.isPlaylist() && hlsRow(it) == q }.sortedWith(compareBy<StreamItem>({ langRank(it) }, { -Ranker.score(it) }))
        }
    }
}

/** The tick's colour by kind of link: red = streaming playlist (HLS), yellow = a plugin's link, green = a file. */
private fun qTick(s: StreamItem): Color = when {
    // Exactly like Xstream: HLS red · the HD file sites green · MovieBox / quick links yellow · the rest green.
    s.isPlaylist() -> Color(0xFFFF4D57)
    Regex("(?i)vega|rog ?movies|uhd ?movies|4k ?hd ?hub|hdhub4u|hd ?hub").containsMatchIn(listOfNotNull(s.addonName, s.stream.name, s.stream.title, s.stream.url).joinToString(" ")) -> Color(0xFF2FBF71)
    s.isMovieBox() || listOfNotNull(s.addonName).any { it.contains("Nuvio", true) } -> Color(0xFFF5C518)
    else -> Color(0xFF2FBF71)
}

private fun tagLine(s: StreamItem): String = listOfNotNull(VisualTags.audio(s), VisualTags.size(s),
    s.languagesOrOriginal().joinToString(" + ") { langLabel(it) }).joinToString(" · ")

/**
 * TV 0.8.5 quality list, like the phone (YouTube style): Auto · 4K · 1080p 60fps · 1080p · 720p · 480p — a line only when
 * there is a stream for it — and "Other streams ▾" opening every source. Focus starts on what plays and stays inside.
 */
@Composable
fun TvQualityBubble(
    groups: Map<String, List<StreamItem>>, playing: String?, upgradeReady: Boolean, modifier: Modifier = Modifier,
    ladder: List<Int> = emptyList(), playingH: Int = 0, chosen: String = "Auto",
    onVariant: (Int?) -> Unit = {}, onAuto: () -> Unit = {}, onPick: (String, StreamItem) -> Unit, onClose: () -> Unit,
) {
    // TV 0.9.4: exactly Xstream's quality bar — QUALITY, then Auto · 4K · (1080p 60fps) · 1080p · 720p · 480p. The white
    // frame is what you chose, the tick what really plays (red = streaming playlist, green = HD file sites, yellow =
    // MovieBox / quick links). A streaming playlist's own levels are listed too; rows with nothing to play are dimmed.
    val curItem = groups.values.flatten().firstOrNull { it.stream.url == playing }
    val tick = curItem?.let { qTick(it) } ?: Color(0xFFFF4D57)
    val sixtyNow = curItem != null && groups["1080p 60fps"].orEmpty().any { it.stream.url == playing }
    val playRow = when {
        playingH >= 1440 -> "4K"
        playingH >= 1000 -> if (sixtyNow) "1080p 60fps" else "1080p"
        playingH >= 700 -> "720p"
        playingH > 0 -> "480p"
        else -> curItem?.let { tvQualityOf(it) }
    }
    val lines = TvQualities.filter { it != "1080p 60fps" || groups[it].orEmpty().isNotEmpty() }
    fun level(q: String): Int? = when (q) {
        "4K" -> ladder.firstOrNull { it >= 1440 }
        "1080p" -> ladder.firstOrNull { it in 980..1180 }
        "720p" -> ladder.firstOrNull { it in 620..820 }
        "480p" -> ladder.firstOrNull { it in 380..580 }
        else -> null
    }
    val frs = remember { TvQualities.associateWith { FocusRequester() } }
    var others by remember { mutableStateOf(false) }
    var openGroup by remember { mutableStateOf<String?>(null) }
    val all = remember(groups) { groups.values.flatten().distinctBy { it.stream.url } }
    LaunchedEffect(Unit) {
        val q = if (chosen in lines) chosen else lines.first()
        repeat(12) { delay(50); if (frs.getValue(q).tryFocus()) return@LaunchedEffect }
    }
    BackHandler { if (others) others = false else onClose() }
    val shape = RoundedCornerShape(20.dp)
    val rs = RoundedCornerShape(12.dp)
    androidx.compose.foundation.lazy.LazyColumn(
        modifier.width(236.dp).heightIn(max = 400.dp).clip(shape).background(Color(0xF0141418)).border(1.dp, Color(0x1FFFFFFF), shape).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text("QUALITY", Modifier.padding(start = 6.dp, top = 2.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8A94A8), letterSpacing = 0.8.sp) }
        items(lines.size) { idx ->
            val q = lines[idx]
            val lv = level(q)
            val top = groups[q].orEmpty().firstOrNull()
            val enabled = q == "Auto" || lv != null || top != null || playRow == q
            val framed = chosen == q
            val ticked = playRow == q || (q == "Auto" && playRow == null)
            var f by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().focusRequester(frs.getValue(q)).onFocusChanged { f = it.isFocused }
                .clip(rs).background(if (f) Color(0x40FFFFFF) else if (framed) Color(0x1FFFFFFF) else Color(0x0DFFFFFF))
                .border(if (framed || f) 1.5.dp else 1.dp, if (framed || f) Color.White else Color(0x12FFFFFF), rs)
                .clickable {
                    when {
                        // TV 0.9.6: Auto never changes the stream (it switched a MovieBox 1080p to an unlabelled Netflix playlist):
                        // it only lets the playing stream follow the internet again.
                        q == "Auto" -> { onVariant(null); onAuto() }
                        lv != null -> onVariant(lv)
                        top != null -> onPick(q, top)
                        else -> Snack.show("No $q stream found yet")
                    }
                }
                .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (q == "1080p 60fps") "1080p" else q, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Color.White else Color(0xFF5E6575))
                if (q == "1080p 60fps") Text("60fps", Modifier.padding(start = 7.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFF5C518)).padding(horizontal = 5.dp, vertical = 1.dp),
                    fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color.Black)
                Spacer(Modifier.weight(1f))
                if (ticked) Icon(Icons.Default.Check, null, Modifier.size(17.dp), tint = tick)
            }
        }
        item {
            var f by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp).onFocusChanged { f = it.isFocused }.clip(RoundedCornerShape(10.dp))
                .background(if (f) Color(0x33FFFFFF) else Color.Transparent).clickable { others = !others }.padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("OTHER STREAMS · ${all.size}", Modifier.weight(1f), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (f) Color.White else Color(0xFF8A94A8), letterSpacing = 0.8.sp)
                Text(if (others) "▴" else "▾", fontSize = 12.sp, color = Color(0xFF8A94A8))
            }
        }
        // TV 0.9.6 (as Xstream): the other streams in groups — 4K, 1080p, 720p, 480p and Auto (streaming playlists) —
        // each opening to its streams with the provider's name (real names on the developer account, code names else).
        if (others) {
            val cols = listOf("4K", "1080p", "720p", "480p", "Auto").map { g ->
                g to (if (g == "1080p") groups["1080p 60fps"].orEmpty() + groups["1080p"].orEmpty() else groups[g].orEmpty()).distinctBy { it.stream.url }
            }.filter { it.second.isNotEmpty() }
            items(cols.size) { k ->
                val (g, list) = cols[k]
                val here = list.any { it.stream.url == playing }
                val opened = openGroup == g
                var f by remember { mutableStateOf(false) }
                val gs = RoundedCornerShape(12.dp)
                Column {
                    Row(Modifier.fillMaxWidth().onFocusChanged { f = it.isFocused }.clip(gs)
                        .background(if (f) Color(0x40FFFFFF) else if (here) Color(0x33E50914) else Color(0x0FFFFFFF))
                        .border(1.dp, if (f) Color.White else if (here) Color(0xCCE50914) else Color(0x14FFFFFF), gs)
                        .clickable { openGroup = if (opened) null else g }.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (g == "Auto") "Streaming" else g, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text((if (here) "Playing · " else "") + "${list.size} stream" + (if (list.size == 1) "" else "s"), fontSize = 10.5.sp,
                                color = if (here) Color(0xFFFF8D94) else Color(0xFF9AA3B5))
                        }
                        if (here) Icon(Icons.Default.Check, null, Modifier.size(15.dp), tint = tick)
                        Text(if (opened) "▴" else "▾", Modifier.padding(start = 6.dp), fontSize = 12.sp, color = Color(0xFF8A94A8))
                    }
                    if (opened) list.forEachIndexed { n, st ->
                        val on = st.stream.url == playing
                        var sf by remember { mutableStateOf(false) }
                        val rs2 = RoundedCornerShape(10.dp)
                        Row(Modifier.padding(top = 5.dp, start = 8.dp).fillMaxWidth().onFocusChanged { sf = it.isFocused }.clip(rs2)
                            .background(if (sf) Color(0x40FFFFFF) else if (on) Color(0x26E50914) else Color(0x0DFFFFFF))
                            .border(1.dp, if (sf) Color.White else if (on) Color(0xCCE50914) else Color(0x12FFFFFF), rs2)
                            .clickable { if (!on) onPick(if (g == "Auto") "Auto" else tvQualityOf(st).takeIf { it in TvQualities } ?: g, st) }
                            .padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${n + 1}", Modifier.width(20.dp), fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color(0xFFFF4D57))
                            Column(Modifier.weight(1f)) {
                                val q = hlsRow(st)?.let { "$it · " }.orEmpty()
                                Text(q + (VisualTags.video(st).ifEmpty { listOf(VisualTags.primary(st)) } + Public.streamName(st)).joinToString(" · "),
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(tagLine(st), fontSize = 10.5.sp, color = Color(0xFF9AA3B5), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (on) Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = tick)
                        }
                    }
                }
            }
        }
    }
}

/**
 * TV Sources: the four qualities as tabs (focus = open), then every link of that quality with its picture / sound tags,
 * size and languages (no server or provider names). The top HD pick is marked.
 */
@Composable
fun TvSourcesPanel(
    vm: AppViewModel, type: String, key: String, playing: String?, extra: List<StreamItem>, firstFocus: FocusRequester?,
    onPick: (StreamItem, List<StreamItem>) -> Unit,
) {
    var fetched by remember { mutableStateOf<List<StreamItem>>(emptyList()) }
    LaunchedEffect(key) { vm.streamsFlow(type, key).collect { (l, _) -> fetched = l } }
    val slow by SlowAddons.links.collectAsState()
    val all by produceState(emptyList<StreamItem>(), fetched, slow[key], extra) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { vm.filtered((extra + fetched + slow[key].orEmpty()).distinctBy { it.stream.url }).list.filter { it.manualOk() } }.getOrDefault(emptyList())
        }
    }
    val groups by produceState(emptyMap<String, List<StreamItem>>(), all) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { vm.tvGroups(all) }.getOrDefault(emptyMap()) }
    }
    val initTab = remember { extra.firstOrNull { it.stream.url == playing }?.let { tvQualityOf(it) }?.takeIf { it in TvQualities } ?: "1080p" }
    var tab by remember { mutableStateOf(initTab) }
    val tabFrs = remember { TvQualities.associateWith { FocusRequester() } }
    val listState = rememberLazyListState()
    LaunchedEffect(tab) { listState.scrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvQualities.forEachIndexed { i, q ->
                val n = groups[q].orEmpty().size
                if (q != "Auto" && n == 0 && q != initTab) return@forEachIndexed
                var f by remember { mutableStateOf(false) }
                val on = q == tab
                Row(
                    Modifier.then(if (q == initTab && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier).focusRequester(tabFrs.getValue(q))
                        .onFocusChanged { f = it.isFocused; if (it.isFocused) tab = q }
                        .clip(RoundedCornerShape(20.dp)).background(if (on) Color.White else Color(0x14FFFFFF))
                        .border(2.dp, if (f) TvFocusEdge else Color.Transparent, RoundedCornerShape(20.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { tab = q }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(q, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on) Color.Black else Color.White)
                    Text("  $n", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color(0xFF555A66) else TxMuted)
                }
            }
        }
        val list = groups[tab].orEmpty()
        if (list.isEmpty()) Text(if (all.isEmpty()) "Finding sources…" else "No $tab links for this title.", Modifier.padding(8.dp), fontSize = 13.sp, color = TxMuted)
        LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            itemsIndexed(list, key = { _, s -> s.stream.url ?: s.hashCode().toString() }) { i, s ->
                val now = s.stream.url == playing
                val topPick = i == 0 && tab != "Auto" && s.isVegaRog()
                TxButton(Modifier.fillMaxWidth(), idle = if (now) Color(0x26E50914) else Color(0x0AFFFFFF), onClick = { onPick(s, all) }) { _ ->
                    Column(Modifier.width(74.dp).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(VisualTags.quality(s), fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            (if (s.isPlaylist()) listOf("Auto") else VisualTags.video(s).ifEmpty { listOf(VisualTags.primary(s)) }).take(5).forEach { tg ->
                                val (bg, fg) = when {
                                    tg.contains("Vision", true) || tg == "DV" -> Color(0xFF4B2F9E) to Color.White
                                    tg.contains("HDR", true) -> Color(0xFF6B4E0E) to Color(0xFFFFE08A)
                                    tg.contains("IMAX", true) -> Color(0xFF0E4D55) to Color(0xFF9DF0F8)
                                    else -> Color(0x1FFFFFFF) to Color.White
                                }
                                Text(tg, Modifier.clip(RoundedCornerShape(5.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = fg, maxLines = 1)
                            }
                        }
                        Text(tagLine(s), Modifier.padding(top = 4.dp), fontSize = 11.5.sp, color = Color(0xFFC9CED8), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(Modifier.padding(end = 12.dp), horizontalAlignment = Alignment.End) {
                        if (topPick) Text("TOP PICK", Modifier.clip(RoundedCornerShape(4.dp)).background(TxGreen).padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color(0xFF06140C))
                        if (now) Text("Playing", Modifier.padding(top = 3.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF5A5F))
                    }
                }
            }
        }
    }
}

/** The green "better quality is ready" pill (TV player). */
@Composable
fun TvUpgradePill(title: String, sub: String, focused: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.clip(shape).background(Color(0xEB111216))
            .border(if (focused) 2.dp else 1.dp, if (focused) TvFocusEdge else Color(0x733CCF7A), shape)
            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Brush.linearGradient(listOf(Color(0xFF1F8F55), TxGreen))), Alignment.Center) {
            Text("↑", fontSize = 18.sp, fontWeight = FontWeight.Black)
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(sub, fontSize = 11.sp, color = Color(0xFFC9CED8), maxLines = 1)
        }
    }
}

/** An icon-only player button (Next / Episodes), styled like the TV Watch Next pill; its name shows under it on focus. */
@Composable
fun TvIconPill(focused: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier.size(42.dp, 38.dp).clip(TvFocusShape).background(if (focused) TvFocusFill else Color.Transparent)
                .border(2.dp, if (focused) TvFocusEdge else Color.Transparent, TvFocusShape),
            Alignment.Center,
        ) { Icon(icon, label, Modifier.size(21.dp), tint = Color.White) }
        if (focused) Text(label, Modifier.offset(y = 42.dp).wrapContentWidth(unbounded = true), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = Color(0xFFD5D9E2), maxLines = 1, softWrap = false)
    }
}

// ───────────── Profile editor for the remote ─────────────

/** Name, kids, PIN-free basics: every field works with the remote (OK on the name opens the keyboard). */
@Composable
fun TvProfileEditor(vm: AppViewModel, p: Profile, onClose: () -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var kids by remember { mutableStateOf(p.kids) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(10) { delay(60); if (first.tryFocus()) return@LaunchedEffect } }
    TvModal(onClose) {
        val shape = RoundedCornerShape(26.dp)
        Column(Modifier.width(520.dp).clip(shape).background(TxPanel).border(1.dp, TxRim, shape).padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Edit profile", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Name", fontSize = 12.sp, color = TxMuted)
            var nf by remember { mutableStateOf(false) }
            androidx.compose.foundation.text.BasicTextField(
                name, { name = it.take(20) },
                Modifier.fillMaxWidth().focusRequester(first).onFocusChanged { nf = it.isFocused }.tvOkKeyboard(ring = false)
                    .txFocusBox(nf, TxShape, Color(0x14FFFFFF)).padding(horizontal = 14.dp, vertical = 12.dp),
                singleLine = true, keyboardOptions = tvKeyboard(),
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
            )
            TxButton(Modifier.fillMaxWidth(), onClick = { kids = !kids }) { _ ->
                Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("Kids profile", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Only family-friendly rows on Home", fontSize = 11.5.sp, color = TxMuted)
                }
                TxSwitch(kids, Modifier.padding(end = 14.dp))
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TxButton(Modifier.weight(1f), idle = Color(0xFFD10A14), onClick = { vm.renameProfile(p, name, kids); Snack.show("Profile saved"); onClose() }) { _ ->
                    Text("Save", Modifier.fillMaxWidth().padding(vertical = 12.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                TxButton(Modifier.weight(1f), onClick = onClose) { _ ->
                    Text("Cancel", Modifier.fillMaxWidth().padding(vertical = 12.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * A TV pop-up in its own window: the remote can only move between the pop-up's own items (it never falls onto the page
 * behind), and Back closes it.
 */
@Composable
fun TvModal(onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xA6000000)), Alignment.Center, content = content)
    }
}

@Composable
fun TxSwitch(on: Boolean, modifier: Modifier = Modifier) {
    Box(Modifier.then(modifier).size(44.dp, 24.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Color(0xFFE50914) else Color(0x38FFFFFF))) {
        Box(Modifier.padding(3.dp).size(18.dp).offset(x = if (on) 20.dp else 0.dp).clip(CircleShape).background(Color.White))
    }
}

/** Small helper: a TV section title. */
@Composable
fun TxTitle(text: String, modifier: Modifier = Modifier, size: Int = 26) =
    Text(text, modifier, fontSize = size.sp, fontWeight = FontWeight.ExtraBold)

/** Card surface used by the dashboard tiles. */
fun Modifier.txCard(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(18.dp)): Modifier =
    this.clip(shape).background(Color(0x0DFFFFFF)).border(1.dp, Color(0x14FFFFFF), shape)

fun pctOf(v: Float): Int = (v * 100f).roundToInt().coerceIn(0, 100)

@Suppress("unused") private val keepDp: Dp = 0.dp

private val cwMetaCache = java.util.concurrent.ConcurrentHashMap<String, Meta>()

/** The full title behind a Continue Watching entry (series: with its episodes), cached for the session. */
suspend fun AppViewModel.cwMeta(p: Progress): Meta? {
    val id = p.key.substringBefore(":")
    cwMetaCache[id]?.let { return it }
    val m = if (p.isSeries()) seriesMeta(id) else runCatching { fullMeta(Meta(id = id, type = "movie")) }.getOrNull()
    if (m != null) cwMetaCache[id] = m
    return m
}

/** Warms things up ahead of time on Fire TV (the first WebView in a process takes ~1–2 s to start). */
object TvWarm {
    @Volatile private var web = false
    fun webView(ctx: android.content.Context) {
        if (web || !BuildConfig.IS_TV) return
        web = true
        runCatching { android.webkit.WebView(ctx).destroy() }
    }
}


/** TV 0.8.4: a weak box (≤ 1.5 GB, e.g. Fire TV Stick 3rd gen) — no 60 fps, H.264 first. Set at start by [TvBox.setupOnce]. */
object TvWeak {
    @Volatile var on = false
    /** TV 0.8.5: this TV can play 4K (a 4K screen mode, hardware HEVC 10-bit, enough memory). */
    @Volatile var k4 = false
    /** No 60 fps here: a weak box, or 60 fps dropped frames on it before (remembered). */
    @Volatile var no60 = false
    private val hevcRx = Regex("hevc|x265|h\\.?265", RegexOption.IGNORE_CASE)
    fun hevc(s: StreamItem): Boolean = hevcRx.containsMatchIn(listOfNotNull(s.stream.name, s.stream.title, s.stream.description).joinToString(" "))
}


/**
 * TV 0.8.5: Back on Home asks "Exit StreamX?" (like the phone). Exit stops every player, frees the picture memory and,
 * with "Clear cache too", deletes the temporary video data; then the app is fully closed, so the stick gets its memory back.
 */
@Composable
fun TvExitDialog(onStay: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var clear by remember { mutableStateOf(true) }
    val stayFr = remember { FocusRequester() }
    // TV 0.9.15: the size used to be read on the main thread, which could first build the whole video cache (a disk
    // database + folder scan) when nothing had played yet — the screen froze and Android closed the app. Now it's read in
    // the background, and only if the cache is already open.
    var used by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { used = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { StreamCache.usedBytesIfOpen() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onStay) {
        Column(Modifier.width(460.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF17151C))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(18.dp)).padding(26.dp)) {
            Text("Exit ${Brand.NAME}?", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text("Your place in everything you watch is saved.", Modifier.padding(top = 6.dp), fontSize = 14.sp, color = Color(0xFFBDB7C3))
            // TV 0.8.8: a plain row (no outline), lit softly when selected.
            TvPlainRow(Modifier.padding(top = 14.dp), onClick = { clear = !clear }) {
                Text(if (clear) "☑" else "☐", fontSize = 18.sp, color = Color.White)
                Text("Clear cache too" + (if (used > 1_000_000) " (${used / 1_000_000} MB)" else ""), Modifier.padding(start = 10.dp), fontSize = 16.sp, color = Color.White)
            }
            Row(Modifier.padding(top = 18.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TxButton(Modifier.weight(1f).focusRequester(stayFr), onClick = onStay) { _ ->
                    Text("Stay", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                TxButton(Modifier.weight(1f), onClick = { tvExitNow(ctx, clear) }) { _ ->
                    Text("Exit", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF4D57))
                }
            }
        }
    }
    LaunchedEffect(Unit) { repeat(5) { kotlinx.coroutines.delay(60); if (runCatching { stayFr.requestFocus() }.isSuccess) return@LaunchedEffect } }
}

private fun tvExitNow(ctx: android.content.Context, clear: Boolean) {
    ActLog.add("APP", "TV exit" + if (clear) " · cache cleared" else "")
    runCatching { Preloader.release() }
    runCatching { coil.Coil.imageLoader(ctx).memoryCache?.clear() }
    val act = ctx.tvActivity()
    Thread {
        if (clear) {
            runCatching { StreamCache.clearAll(ctx) }
            listOf("logs", "avatar_dl", "updates", "xs-hls-thumbs").forEach { d -> runCatching { java.io.File(ctx.cacheDir, d).deleteRecursively() } }
            ctx.cacheDir.listFiles()?.filter { it.isFile && (it.name.endsWith(".ts") || it.name.endsWith(".tmp") || it.name.startsWith("seg")) }?.forEach { runCatching { it.delete() } }
        }
        // Give the log and the cloud a moment, then close the app completely.
        runCatching { Thread.sleep(700) }
        android.os.Process.killProcess(android.os.Process.myPid())
    }.start()
    act?.finishAndRemoveTask()
}


/** TV 0.8.8: a list row with no outline — a soft white wash when selected (exit pop-up, card options). */
@Composable
fun TvPlainRow(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    var f by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (f) Color(0x2EFFFFFF) else Color.Transparent)
            .onFocusChanged { f = it.isFocused }
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, content = content,
    )
}
