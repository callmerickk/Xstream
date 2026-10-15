package com.example.streamnest

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

// ───────────── "Recommended for <name>" ─────────────

/**
 * Built from what this profile liked ("Go for it"), its watchlist and favourite genres,
 * plus AI picks when an AI key is set. Skipped, finished and in-progress titles are left out.
 */
suspend fun AppViewModel.buildRecommendations(): List<Meta> = coroutineScope {
    val v = votes.value
    val liked = v.values.filter { it.v > 0 }.sortedByDescending { it.at }
    val kids = current.value?.kids == true
    val exclude = v.filterValues { it.v != 0 }.keys +
        doneKeys.value.map { it.substringBefore(":") } +
        progress.value.map { it.key.substringBefore(":") }
    val genreScore = HashMap<String, Int>()
    liked.forEachIndexed { i, l -> l.genres.forEach { g -> genreScore[g] = (genreScore[g] ?: 0) + (if (i < 5) 3 else 2) } }
    myList.value.forEach { m -> m.genres.orEmpty().forEach { g -> genreScore[g] = (genreScore[g] ?: 0) + 1 } }
    v.values.filter { it.v < 0 }.forEach { d -> d.genres.forEach { g -> genreScore[g] = (genreScore[g] ?: 0) - 1 } }
    var genres = genreScore.entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { it.key }.take(3)
    if (kids) genres = listOf("Animation", "Family")
    if (genres.isEmpty() && liked.isEmpty()) return@coroutineScope emptyList()
    val types = liked.map { it.type }.filter { it == "movie" || it == "series" }.distinct().ifEmpty { listOf("movie", "series") }
    val ai = async { if (kids) emptyList() else runCatching { aiRecommendations() }.getOrDefault(emptyList()) }
    val byGenre = async { if (genres.isNotEmpty()) runCatching { genreTitles(genres, types) }.getOrDefault(emptyList()) else emptyList() }
    val mixed = ArrayList<Meta>()
    val a = ai.await(); val g = byGenre.await()
    for (i in 0 until maxOf(a.size, g.size)) { a.getOrNull(i)?.let { mixed += it }; g.getOrNull(i)?.let { mixed += it } }
    mixed.distinctBy { it.id }.filter { it.id !in exclude }.take(30)
}

// ───────────── Vote capsule: Go for it / Skip / Just surfing ─────────────

private val noRipple = MutableInteractionSource()

/** Floating glass capsule that slides up after you finish watching. Options float gently inside. */
@Composable
fun VoteCapsule(meta: Meta, onVote: (Int) -> Unit, onDismiss: () -> Unit) {
    val enter = remember { Animatable(0f) }
    val dim = remember { Animatable(0f) }
    val pops = remember { List(3) { Animatable(0f) } }
    var chosen by remember { mutableStateOf<Int?>(null) }
    val doneAlpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(Unit) {
        launch { dim.animateTo(1f, tween(400)) }
        launch { enter.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 260f)) }
        delay(300)
        pops.forEachIndexed { i, p -> launch { delay(i * 110L); p.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 320f)) } }
    }
    fun close(after: Long = 0L) {
        scope.launch {
            delay(after)
            launch { dim.animateTo(0f, tween(300)) }
            enter.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
            dismiss()
        }
    }
    fun pick(v: Int) {
        if (chosen != null) return
        chosen = v
        onVote(v)
        scope.launch { doneAlpha.animateTo(1f, tween(350, delayMillis = 250)) }
        close(1500)
    }

    val bob = rememberInfiniteTransition(label = "bob")
    val phase by bob.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val name = meta.name.substringBefore("  S").ifBlank { "this" }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim.value }.background(Color(0x66000000))
            .clickable(interactionSource = noRipple, indication = null) { if (chosen == null) close() })
        val shape = RoundedCornerShape(40.dp)
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 14.dp, end = 14.dp, bottom = 96.dp)
                .widthIn(max = 520.dp).fillMaxWidth()
                .graphicsLayer {
                    val v = enter.value
                    translationY = (1f - v) * size.height * 1.6f
                    alpha = v.coerceIn(0f, 1f)
                    scaleX = 0.9f + 0.1f * v; scaleY = scaleX
                }
                .shadow(24.dp, shape)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xF0221E28), Color(0xF5141218))))
                .border(1.dp, Color(0x1FFFFFFF), shape)
                .clickable(interactionSource = noRipple, indication = null) {}
                .padding(14.dp),
        ) {
            Row(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(meta.poster, null, Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF22222A)), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("How was $name?", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Tunes your recommendations", fontSize = 12.sp, color = Color(0xFF9AA3B5))
                }
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0x14FFFFFF)).clickable { if (chosen == null) close() }, Alignment.Center) {
                    Icon(Icons.Default.Close, "Close", Modifier.size(15.dp), tint = Color(0xFF9AA3B5))
                }
            }
            Box(Modifier.fillMaxWidth().height(50.dp)) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val opts = listOf(Triple("🔥 Go for it", 1, true), Triple("👎 Skip", -1, false), Triple("🌊 Just surfing", 0, false))
                    opts.forEachIndexed { i, (label, v, primary) ->
                        val p = pops[i]
                        val isChosen = chosen == v
                        val gone = chosen != null && !isChosen
                        val pillShape = RoundedCornerShape(24.dp)
                        Text(
                            label,
                            Modifier.weight(1f).height(46.dp)
                                .graphicsLayer {
                                    val pv = p.value
                                    val float = if (chosen == null) sin((phase + i * 0.33f) * 2f * PI.toFloat()) * 2.5.dp.toPx() else 0f
                                    translationY = (1f - pv) * 20.dp.toPx() + float
                                    val sc = (0.8f + 0.2f * pv) * (if (isChosen) 1f + doneAlpha.value * 0.2f else 1f) * (if (gone) 1f - doneAlpha.value * 0.2f else 1f)
                                    scaleX = sc; scaleY = sc
                                    alpha = pv.coerceIn(0f, 1f) * (if (chosen != null) 1f - doneAlpha.value else 1f)
                                }
                                .shadow(8.dp, pillShape)
                                .clip(pillShape)
                                .background(if (primary) Brush.linearGradient(listOf(Color(0xFFE50914), Color(0xFFFF5A4F))) else Brush.linearGradient(listOf(Color(0x1FFFFFFF), Color(0x14FFFFFF))))
                                .border(1.dp, if (primary) Color.Transparent else Color(0x1AFFFFFF), pillShape)
                                .clickable { pick(v) }
                                .wrapContentHeight(Alignment.CenterVertically),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1,
                        )
                    }
                }
                Text(
                    when (chosen) { 1 -> "Got it — more like this coming up 🔥"; -1 -> "Got it — we'll show less like this"; else -> "No problem — enjoy surfing 🌊" },
                    Modifier.align(Alignment.Center).graphicsLayer { alpha = doneAlpha.value; translationY = (1f - doneAlpha.value) * 8.dp.toPx() },
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
