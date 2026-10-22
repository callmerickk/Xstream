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

// ───────────── Xstream TV only: pieces used by the TV screens (the phone never shows these) ─────────────

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
    BackHandler { dismiss() }
    val name = meta.name.substringBefore("  S").ifBlank { "this" }
    Box(
        Modifier.fillMaxSize().background(Color(0x8C000000))
            // The remote stays inside the capsule: ▲ ▼ never reach the page behind it.
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
val TvQualities = listOf("Instant", "4K", "Full HD", "Mid HD")

fun tvQualityOf(s: StreamItem): String = when {
    s.isPlaylist() -> "Instant"
    s.effectiveRes() >= 1440 -> "4K"
    s.effectiveRes() >= 1080 -> "Full HD"
    else -> "Mid HD"
}

/**
 * Every playable link sorted into the four qualities: Instant = streaming playlists (start at once); 4K / Full HD /
 * Mid HD = files, the preferred HD source first (when its language is as good as the rest), then your language, then
 * how reliably the server played.
 */
fun AppViewModel.tvGroups(all: List<StreamItem>): Map<String, List<StreamItem>> {
    val ok = all.filter { it.stream.url != null && !Ranker.isDead(it) && !Ranker.keepsFailing(it) && !it.unplayableHere() && HostStats.penalty(it.stream.url.orEmpty()) == 0 }
    return TvQualities.associateWith { q ->
        val l = ok.filter { tvQualityOf(it) == q && (q == "Instant" || it.effectiveRes() >= 700 || q == "Mid HD") }
        if (q == "Instant") l.sortedWith(compareBy<StreamItem>({ langRank(it) }, { -Ranker.score(it) }))
        else {
            val bestLang = l.minOfOrNull { langRank(it) } ?: 0
            l.sortedWith(compareBy<StreamItem>(
                { if (it.isVegamovies() && (langRank(it) <= bestLang || "multi" in it.languages())) 0 else 1 },
                { langRank(it) }, { -Ranker.score(it) },
            ))
        }
    }
}

private fun tagLine(s: StreamItem): String = listOfNotNull(VisualTags.audio(s), VisualTags.size(s),
    s.languagesOrOriginal().joinToString(" + ") { langLabel(it) }).joinToString(" · ")

/** Quality bubble: Instant · 4K · Full HD · Mid HD. Focus never jumps (the four rows never change). */
@Composable
fun TvQualityBubble(groups: Map<String, List<StreamItem>>, playing: String?, upgradeReady: Boolean, modifier: Modifier = Modifier, onPick: (String, StreamItem) -> Unit, onClose: () -> Unit) {
    val frs = remember { List(TvQualities.size) { FocusRequester() } }
    val curItem = groups.values.flatten().firstOrNull { it.stream.url == playing }
    val curQ = curItem?.let { tvQualityOf(it) }
    LaunchedEffect(Unit) {
        val i = TvQualities.indexOf(curQ).coerceAtLeast(0)
        repeat(10) { delay(60); if (frs[i].tryFocus()) return@LaunchedEffect }
    }
    BackHandler { onClose() }
    Column(
        modifier.width(380.dp).clip(RoundedCornerShape(24.dp)).background(TxPanel).border(1.dp, TxRim, RoundedCornerShape(24.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.padding(start = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Quality", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Playing: ${curQ ?: "—"}", fontSize = 12.sp, color = TxMuted)
        }
        TvQualities.forEachIndexed { i, q ->
            val list = groups[q].orEmpty()
            val top = list.firstOrNull()
            val sub = when {
                top == null -> "Not found yet"
                q == "Instant" -> "Starts in seconds"
                else -> listOfNotNull(VisualTags.quality(top), VisualTags.audio(top)).joinToString(" · ") +
                    if (upgradeReady && top.isVegamovies()) " · better source ready" else ""
            }
            TxButton(Modifier.fillMaxWidth().focusRequester(frs[i]), idle = Color.Transparent, onClick = {
                if (top == null) Snack.show("No $q link found yet") else onPick(q, top)
            }) { _ ->
                Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 9.dp).graphicsLayer { alpha = if (top == null) 0.45f else 1f }) {
                    Text(q, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(sub, fontSize = 11.5.sp, color = if (sub.endsWith("ready")) TxGreen else TxMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (q == curQ) Icon(Icons.Default.Check, null, Modifier.padding(end = 12.dp).size(18.dp), tint = TxGreen)
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
    val initTab = remember { extra.firstOrNull { it.stream.url == playing }?.let { tvQualityOf(it) } ?: "Full HD" }
    var tab by remember { mutableStateOf(initTab) }
    val tabFrs = remember { TvQualities.associateWith { FocusRequester() } }
    val listState = rememberLazyListState()
    LaunchedEffect(tab) { listState.scrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvQualities.forEachIndexed { i, q ->
                val n = groups[q].orEmpty().size
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
                val topPick = i == 0 && tab != "Instant" && s.isVegamovies()
                TxButton(Modifier.fillMaxWidth(), idle = if (now) Color(0x26E50914) else Color(0x0AFFFFFF), onClick = { onPick(s, all) }) { _ ->
                    Column(Modifier.width(74.dp).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(VisualTags.quality(s), fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            (if (s.isPlaylist()) listOf("Instant") else VisualTags.video(s).ifEmpty { listOf(VisualTags.primary(s)) }).take(5).forEach { tg ->
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
