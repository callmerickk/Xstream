package com.example.streamnest

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Xstream: what a long-press on any poster does (set by Home). Gets the title and where its card is on screen. */
val LocalTitleHold = staticCompositionLocalOf<((Meta, Rect) -> Unit)?> { null }

/** Tap opens the title; long-press lifts it out (when a [LocalTitleHold] is set). */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.titleTap(m: Meta, onOpen: () -> Unit): Modifier = composed {
    val hold = LocalTitleHold.current
    val haptics = LocalHapticFeedback.current
    val rect = remember { arrayOf(Rect.Zero) }
    this.onGloballyPositioned { rect[0] = it.boundsInRoot() }
        .combinedClickable(onClick = onOpen, onLongClick = if (hold == null) null else ({ haptics.performHapticFeedback(HapticFeedbackType.LongPress); hold(m, rect[0]) }))
}

/**
 * The long-press preview: the card flies out of its row into a wide card near the top, and the title's trailer starts
 * playing inside it (real video, cropped); the title shrinks to the corner while it plays. Under it: the name, the year,
 * length and ratings, then Go to details · Play manually · Add to watchlist.
 */
@Composable
fun TitleLiftMenu(vm: AppViewModel, m: Meta, from: Rect, onDetails: () -> Unit, onManual: () -> Unit, onWatchlist: () -> Unit, onClose: () -> Unit,
                  /** The title page's long-press on Play: its own full list instead of the three poster actions. */
                  actions: List<TitleAction>? = null, subtitle: String? = null) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var origin by remember { mutableStateOf(Offset.Zero) }
    var boxW by remember { mutableFloatStateOf(0f) }
    val lift = remember { Animatable(0f) }
    val menuA = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { lift.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 320f)) }
        kotlinx.coroutines.delay(110)
        menuA.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
    }
    fun close(then: () -> Unit = {}) {
        scope.launch {
            launch { menuA.animateTo(0f, tween(150)) }
            lift.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
            onClose(); then()
        }
    }
    // The trailer (direct video) and a little more about the title.
    var src by remember { mutableStateOf<YtDirect.Src?>(null) }
    var videoOn by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<TmdbInfo?>(null) }
    var muted by remember { mutableStateOf(HeroSound.muted) }
    LaunchedEffect(m.id) {
        launch { info = runCatching { vm.tmdbInfo(m.id.substringBefore(":"), m.type) }.getOrNull() }
        val id = runCatching { vm.trailerId(m) }.getOrNull() ?: return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        src = YtDirect.resolve(id)
    }
    val shrink by animateFloatAsState(if (videoOn) 1f else 0f, tween(700), label = "shrink")
    val art = backdropOf(m) ?: info?.backdrop ?: m.poster

    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInRoot().topLeft; boxW = it.size.width.toFloat() }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() }) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = lift.value.coerceIn(0f, 1f) }.background(Color(0x99000000)))
        if (boxW > 0f) {
            val side = with(density) { 20.dp.toPx() }
            val targetW = boxW - side * 2
            val targetH = targetW * 9f / 16f
            val targetTop = with(density) { 110.dp.toPx() }
            val srcR = Rect(from.left - origin.x, from.top - origin.y, from.right - origin.x, from.bottom - origin.y)
            val t = lift.value
            val l = srcR.left + (side - srcR.left) * t
            val top = srcR.top + (targetTop - srcR.top) * t
            val w = srcR.width + (targetW - srcR.width) * t
            val h = srcR.height + (targetH - srcR.height) * t
            val cardShape = RoundedCornerShape(20.dp)
            Box(
                Modifier.offset { IntOffset(l.roundToInt(), top.roundToInt()) }
                    .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                    .shadow(24.dp, cardShape).clip(cardShape).background(Surface1)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                AsyncImage(art, m.name, Modifier.fillMaxSize().graphicsLayer { alpha = 1f - (if (videoOn) 1f else 0f) }, contentScale = ContentScale.Crop)
                src?.let { s -> DirectTrailer(s, muted, Modifier.fillMaxSize().graphicsLayer { alpha = if (videoOn) 1f else 0f }, crop = true, zoom = 1.08f,
                    onPlaying = { videoOn = true }, onEnded = { videoOn = false; src = null }, onError = { videoOn = false; src = null }) }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xCC000000))))
                // The title (logo when there is one) shrinks to the corner once the trailer plays.
                val logo = logoOf(m) ?: info?.logo
                Box(Modifier.align(Alignment.BottomStart).padding(14.dp).graphicsLayer {
                    val sc = 1f - 0.45f * shrink; scaleX = sc; scaleY = sc
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
                }) {
                    if (logo != null) AsyncImage(logo, m.name, Modifier.height(54.dp).widthIn(max = 220.dp), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart)
                    else Text(m.name, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (videoOn) Box(
                    Modifier.align(Alignment.BottomEnd).padding(10.dp).size(34.dp).clip(CircleShape).background(Color(0x88000000))
                        .clickable { muted = !muted; HeroSound.muted = muted },
                    Alignment.Center,
                ) { Icon(if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, if (muted) "Unmute" else "Mute", Modifier.size(18.dp), tint = Color.White) }
            }

            // Name, facts and ratings, then the actions.
            Column(
                Modifier.offset { IntOffset(side.roundToInt(), (targetTop + targetH + with(density) { 16.dp.toPx() }).roundToInt()) }
                    .width(with(density) { targetW.toDp() }).graphicsLayer { alpha = menuA.value; translationY = (1f - menuA.value) * 30f },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(m.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, Modifier.padding(top = 2.dp), fontSize = 12.5.sp, color = Color(0xFFB6BDCC)) }
                val facts = listOfNotNull(
                    if (m.type == "series") "Series" else "Movie",
                    (m.releaseInfo ?: info?.release)?.take(4),
                    (m.runtime ?: info?.runtime)?.takeIf { it.isNotBlank() },
                    (m.genres ?: info?.genres)?.firstOrNull(),
                )
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(facts.joinToString(" · "), fontSize = 12.5.sp, color = Color(0xFFB6BDCC))
                    m.imdbRating?.takeIf { it.isNotBlank() }?.let { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFF5C518)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                                Text("IMDb", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color.Black)
                            }
                            Text(" $r", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    info?.certification?.takeIf { it.isNotBlank() }?.let { c ->
                        Text(c, Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x22FFFFFF)).padding(horizontal = 5.dp, vertical = 1.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF17181D))
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    val acts = actions?.map { a -> Triple(a.label, a.icon) { close(a.onClick) } } ?: listOf(
                        Triple("Go to details", Icons.Default.Info) { close(onDetails) },
                        Triple("Play manually", Icons.Default.PlayArrow) { close(onManual) },
                        Triple("Add to watchlist", Icons.Default.BookmarkAdd) { close(onWatchlist) },
                    )
                    acts.forEachIndexed { i, (label, icon, go) ->
                        Row(Modifier.fillMaxWidth().clickable { go() }.padding(horizontal = 18.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Icon(icon, null, Modifier.size(20.dp), tint = Color.White)
                        }
                        if (i < acts.lastIndex) HorizontalDivider(color = Color(0x14FFFFFF))
                    }
                }
            }
        }
    }
}
