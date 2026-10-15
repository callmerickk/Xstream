package com.example.streamnest

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** One particle of a removed card. */
private class Bit(val x: Float, val y: Float, val color: Color, val delay: Float, val vx: Float, val vy: Float)

/**
 * Continue Watching long-press (phones): the page behind blurs and dims, the card lifts out of its row and glides up
 * with its title and a compact menu. Remove: the menu fades, the card breaks into particles that drift away, then the
 * row closes the gap. Tap outside: the card goes back to its place.
 */
@Composable
fun CwLiftMenu(p: Progress, from: Rect, actions: List<TitleAction>, onRemove: () -> Unit, onClose: () -> Unit) {
    val density = LocalDensity.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var origin by remember { mutableStateOf(Offset.Zero) }
    var boxW by remember { mutableFloatStateOf(0f) }
    val lift = remember { Animatable(0f) }
    val menuA = remember { Animatable(0f) }
    var bits by remember { mutableStateOf<List<Bit>?>(null) }
    val burst = remember { Animatable(0f) }
    var removing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        launch { lift.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 320f)) }
        kotlinx.coroutines.delay(110)
        menuA.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
    }
    fun close() {
        if (removing) return
        scope.launch {
            launch { menuA.animateTo(0f, tween(150)) }
            lift.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
            onClose()
        }
    }
    val title = p.title.substringBefore("  S")
    val sub = if (p.key.contains(":")) p.title.substringAfter("  ", "Series").ifBlank { "Series" } else "Movie"
    val frac = if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f

    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInRoot().topLeft; boxW = it.size.width.toFloat() }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() }) {
        // Dim (the page itself is blurred by Home while this is open).
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = lift.value.coerceIn(0f, 1f) }.background(Color(0x8C000000)))
        if (boxW > 0f) {
            val side = with(density) { 24.dp.toPx() }
            val targetW = boxW - side * 2
            val targetH = targetW * 9f / 16f
            val targetTop = with(density) { 120.dp.toPx() }
            val src = Rect(from.left - origin.x, from.top - origin.y, from.right - origin.x, from.bottom - origin.y)
            val t = lift.value
            val l = src.left + (side - src.left) * t
            val top = src.top + (targetTop - src.top) * t
            val w = src.width + (targetW - src.width) * t
            val h = src.height + (targetH - src.height) * t
            // The card.
            if (bits == null) Box(
                Modifier.offset { IntOffset(l.roundToInt(), top.roundToInt()) }.size(with(density) { w.toDp() }, with(density) { h.toDp() })
                    .shadow((18 * t).dp, RoundedCornerShape(14.dp), ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(RoundedCornerShape(14.dp)).background(Surface1)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                AsyncImage(p.image, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xD9000000))))
                timeLeft(p)?.let {
                    Text(it, Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xB3000000))
                        .padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(title, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 14.dp), fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (frac > 0f) Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 7.dp).fillMaxWidth().height(3.dp)
                    .clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF))) { Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Color.White)) }
            }
            // Title + compact menu.
            Column(
                Modifier.offset { IntOffset(side.roundToInt(), (targetTop + targetH + with(density) { 12.dp.toPx() } + (1f - menuA.value) * 30f).roundToInt()) }
                    .width(with(density) { targetW.toDp() }).graphicsLayer { alpha = menuA.value }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Text(title, Modifier.fillMaxWidth(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
                Text(sub, Modifier.fillMaxWidth().padding(top = 2.dp), fontSize = 12.sp, color = Color(0xFF9AA3B5), textAlign = TextAlign.Center)
                Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xF51C1D23))) {
                    actions.forEachIndexed { i, a ->
                        if (i > 0) HorizontalDivider(color = Color(0x0FFFFFFF))
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (a.danger) {
                                    removing = true
                                    scope.launch {
                                        launch { menuA.animateTo(0f, tween(160)) }
                                        if (BuildConfig.IS_TV) { lift.animateTo(0f, tween(260)); onRemove(); onClose(); return@launch }
                                        // Particles from the card's own picture (its artwork's colours), drifting up and right.
                                        val cell = with(density) { 4.dp.toPx() }
                                        val list = withContext(Dispatchers.Default) { particles(ctx, p.image, Rect(l, top, l + w, top + h), cell) }
                                        bits = list
                                        burst.animateTo(1f, tween(1300, easing = LinearEasing))
                                        onRemove(); onClose()
                                    }
                                } else { a.onClick(); close() }
                            }.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val c = if (a.danger) Color(0xFFFF6B8B) else Color.White
                            Text(a.label, Modifier.weight(1f), fontSize = 13.sp, color = c)
                            Icon(a.icon, null, Modifier.size(18.dp), tint = c)
                        }
                    }
                }
            }
            bits?.let { bs ->
                val cell = with(density) { 4.dp.toPx() }
                Canvas(Modifier.fillMaxSize()) {
                    val time = burst.value * 1.3f
                    for (b in bs) {
                        val k = time - b.delay
                        if (k <= 0f) { drawRect(b.color, Offset(b.x, b.y), Size(cell, cell)); continue }
                        val a = (1f - k / 0.75f).coerceIn(0f, 1f)
                        if (a <= 0f) continue
                        drawRect(b.color.copy(alpha = a), Offset(b.x + b.vx * k, b.y + b.vy * k), Size(cell * 0.8f, cell * 0.8f))
                    }
                }
            }
        }
    }
}

/** Samples the artwork into small squares (falls back to the app's card colour when it can't be loaded). */
private suspend fun particles(ctx: android.content.Context, image: String?, r: Rect, cell: Float): List<Bit> {
    val cols = (r.width / cell).toInt().coerceAtLeast(1)
    val rows = (r.height / cell).toInt().coerceAtLeast(1)
    val bmp = runCatching {
        val req = coil.request.ImageRequest.Builder(ctx).data(image).size(cols, rows).allowHardware(false).build()
        (coil.Coil.imageLoader(ctx).execute(req).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
    }.getOrNull()
    val rnd = java.util.Random()
    val out = ArrayList<Bit>(cols * rows)
    for (y in 0 until rows) for (x in 0 until cols) {
        val c = bmp?.let { b ->
            val px = b.getPixel((x * b.width / cols).coerceIn(0, b.width - 1), (y * b.height / rows).coerceIn(0, b.height - 1))
            // Darken toward the bottom like the card's own shade.
            val shade = 1f - 0.5f * (y.toFloat() / rows).let { it * it }
            Color(android.graphics.Color.red(px) / 255f * shade, android.graphics.Color.green(px) / 255f * shade, android.graphics.Color.blue(px) / 255f * shade)
        } ?: Color(0xFF2A2B33)
        val fx = x.toFloat() / cols
        out += Bit(r.left + x * cell, r.top + y * cell, c, delay = fx * 0.45f + rnd.nextFloat() * 0.25f,
            vx = (80f + rnd.nextFloat() * 220f), vy = -(40f + rnd.nextFloat() * 200f))
    }
    return out
}
