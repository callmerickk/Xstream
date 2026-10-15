package com.example.streamnest

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Inter Display: the airy headline face of the loading scenes (bundled, SIL Open Font License). */
val DisplayFont = FontFamily(
    Font(R.font.inter_display_light, FontWeight.Light),
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
)

// ───────────── Shared bits ─────────────

private val Gold = Color(0xFFE2B968)
private val GoldDark = Color(0xFFB47A26)
private val GoldLight = Color(0xFFF6DC9C)
private val Ink = Color(0xFFF3EFE8)

/** Seconds since this scene appeared, updated every frame (read it only in draw / placement lambdas). */
@Composable
private fun rememberSceneClock(): FloatState {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { n -> t.floatValue = (n - start) / 1_000_000_000f }
    }
    return t
}

/** The poster as a Painter, loaded big enough to be magnified by the lens. */
@Composable
private fun rememberPosterPainter(poster: String?): Painter {
    val ctx = LocalContext.current
    val req = remember(poster) { ImageRequest.Builder(ctx).data(poster).size(480, 720).build() }
    return rememberAsyncImagePainter(req)
}

/** Draws [painter] like CSS background-size: cover into (0,0)-(w,h). */
private fun DrawScope.drawCover(painter: Painter, w: Float, h: Float) {
    val isz = painter.intrinsicSize
    clipRect(0f, 0f, w, h) {
        if (isz == Size.Unspecified || !(isz.width > 0f) || !(isz.height > 0f)) {
            with(painter) { draw(Size(w, h)) }
        } else {
            val k = max(w / isz.width, h / isz.height)
            val dw = isz.width * k
            val dh = isz.height * k
            translate((w - dw) / 2f, (h - dh) / 2f) { with(painter) { draw(Size(dw, dh)) } }
        }
    }
}

private fun warmTone(scope: DrawScope) = with(scope) {
    drawRect(
        Brush.radialGradient(
            0f to Color(0x40461610), 1f to Color(0x66000000),
            center = Offset(size.width * 0.52f, size.height * 0.46f), radius = size.maxDimension * 0.6f,
        )
    )
}

/** The poster card: 12dp corners, faint gold rim, deep shadow, darker bottom. */
@Composable
private fun PosterCard(painter: Painter, title: String, s: Float, modifier: Modifier) {
    val shape = RoundedCornerShape((12f * s).dp)
    Box(
        modifier.shadow((18f * s).dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .background(Brush.verticalGradient(listOf(Color(0xFF2A2630), Color(0xFF15141A))))
            .border(1.dp, Color(0x52F2D394), shape),
    ) {
        if (title.isNotBlank()) Text(
            title, Modifier.align(Alignment.Center).padding((10f * s).dp), color = Color(0x99FFFFFF),
            textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
            fontSize = with(LocalDensity.current) { (12f * s).dp.toSp() }, fontFamily = InterFont, fontWeight = FontWeight.SemiBold,
        )
        Image(painter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color(0x80000000))))
    }
}

/** One word in gold, with a sheen sliding across it (like the CSS background-clip:text). */
@Composable
private fun GoldWord(text: String, fontSize: TextUnit, time: FloatState, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            drawContent()
            val w = size.width
            val u = (time.floatValue % 3.2f) / 3.2f
            val left = -1.44f * w + u * 2.88f * w
            drawRect(
                Brush.linearGradient(
                    0f to Color(0xFFF7E1A8), 0.45f to Color(0xFFD9A441), 0.55f to Color(0xFFFFF2C9), 1f to Color(0xFFC98A2E),
                    start = Offset(left, 0f), end = Offset(left + 2.2f * w, size.height * 0.18f),
                ),
                blendMode = BlendMode.SrcIn,
            )
        },
        color = Color.White, fontSize = fontSize, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold,
        letterSpacing = with(LocalDensity.current) { (-0.4).dp.toSp() }, maxLines = 1,
    )
}

/** Kicker, headline with a gold word, and a status line under the stage. */
@Composable
private fun SceneText(kicker: String, pre: String, gold: String, post: String, s: Float, time: FloatState, line: @Composable () -> Unit) {
    val d = LocalDensity.current
    fun tsp(v: Float) = with(d) { (v * s).dp.toSp() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            kicker, Modifier.padding(top = (6f * s).dp), color = Color(0xD9E8C480), fontSize = tsp(10.5f),
            fontFamily = InterFont, fontWeight = FontWeight.SemiBold, letterSpacing = tsp(3.2f), maxLines = 1,
        )
        val headSize = tsp(25f)
        val spacing = tsp(-0.4f)
        Row(Modifier.padding(top = (6f * s).dp)) {
            Text(pre, Modifier.alignByBaseline(), color = Ink, fontSize = headSize, fontFamily = DisplayFont, fontWeight = FontWeight.Light, letterSpacing = spacing, maxLines = 1)
            Spacer(Modifier.width((6.5f * s).dp))
            GoldWord(gold, headSize, time, Modifier.alignByBaseline())
            Spacer(Modifier.width((6.5f * s).dp))
            Text(post, Modifier.alignByBaseline(), color = Ink, fontSize = headSize, fontFamily = DisplayFont, fontWeight = FontWeight.Light, letterSpacing = spacing, maxLines = 1)
        }
        Box(Modifier.padding(top = (6f * s).dp).height((20f * s).dp), Alignment.Center) { line() }
    }
}

@Composable
private fun SceneLine(text: String, s: Float) {
    Text(
        text, color = Color(0x9EECE8E1), fontSize = with(LocalDensity.current) { (13f * s).dp.toSp() },
        fontFamily = InterFont, fontWeight = FontWeight.Normal, maxLines = 1,
    )
}

/** Stage scale: the 560 × 262 design (plus text) shrinks on small screens, never grows. */
// Scales by the smaller of width and height (fits phones in landscape and the 960×540 dp TV canvas); a TV may grow it a little.
private fun stageScale(maxW: Dp, maxH: Dp): Float = minOf(if (BuildConfig.IS_TV) 1.2f else 1f, maxW / 600.dp, maxH / 360.dp).coerceAtLeast(0.3f)

// ───────────── Finding: a gold magnifier glides over the poster by itself ─────────────

private const val FPX = 212f
private const val FPY = 24f
private const val FPW = 136f
private const val FPH = 204f
private const val LENS_ZOOM = 2.3f
private const val LENS_R = 32f

private class FindPose(val cx: Float, val cy: Float, val sc: Float, val handleAngle: Float)

private fun findPose(t: Float): FindPose {
    val lx = FPW / 2f + sin(t * 1.05f) * 40f
    val ly = FPH * 0.46f + sin(t * 0.68f + 1f) * 62f
    val sc = 1f + sin(t * 2.1f) * 0.03f
    val ang = Math.toRadians((42f + sin(t * 1.05f) * 10f).toDouble()).toFloat()
    return FindPose(FPX + lx, FPY + ly, sc, ang)
}

/**
 * "Finding the finest copy": a gold magnifier drifts over the poster on its own
 * (the poster really is magnified inside the lens). [checked] of [total] sources have answered so far.
 */
@Composable
fun FindingScene(poster: String?, title: String, checked: Int, total: Int, bestQuality: String?, modifier: Modifier = Modifier) {
    val time = rememberSceneClock()
    val posterPainter = rememberPosterPainter(poster)
    var hint by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); hint = (hint + 1) % 3 } }

    BoxWithConstraints(modifier.fillMaxSize().drawBehind { warmTone(this) }) {
        val s = stageScale(maxWidth, maxHeight)
        fun u(v: Float) = (v * s).dp
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(u(560f), u(262f))) {
                PosterCard(posterPainter, title, s, Modifier.offset(u(FPX), u(FPY)).size(u(FPW), u(FPH)))
                Canvas(Modifier.matchParentSize()) {
                    val k = size.width / 560f
                    val p = findPose(time.floatValue)
                    val c = Offset(p.cx, p.cy)
                    withTransform({ scale(k, k, pivot = Offset.Zero) }) {
                        // The lens, with a gentle pulse.
                        withTransform({ scale(p.sc, p.sc, pivot = c) }) {
                            val outer = Path().apply { addOval(Rect(c, LENS_R)) }
                            clipPath(outer, ClipOp.Difference) {
                                drawCircle(
                                    Brush.radialGradient(0f to Color(0x99000000), 0.62f to Color(0x80000000), 1f to Color.Transparent, center = c + Offset(0f, 8f), radius = 50f),
                                    radius = 50f, center = c + Offset(0f, 8f),
                                )
                                drawCircle(
                                    Brush.radialGradient(0f to Color(0x59E8C27A), 0.64f to Color(0x59E8C27A), 1f to Color.Transparent, center = c, radius = 50f),
                                    radius = 50f, center = c,
                                )
                            }
                            val inner = Path().apply { addOval(Rect(c, LENS_R - 3f)) }
                            val lx = p.cx - FPX
                            val ly = p.cy - FPY
                            val inside = lx > 8f && lx < FPW - 8f && ly > 8f && ly < FPH - 8f
                            clipPath(inner) {
                                if (inside) {
                                    withTransform({ scale(LENS_ZOOM, LENS_ZOOM, pivot = c) }) {
                                        translate(FPX, FPY) { drawCover(posterPainter, FPW, FPH) }
                                    }
                                } else drawCircle(Color(0x1AA0C8FF), LENS_R - 3f, c)
                                drawCircle(Brush.radialGradient(0.62f to Color.Transparent, 1f to Color(0x4D000000), center = c, radius = LENS_R - 3f), LENS_R - 3f, c)
                            }
                            drawCircle(Gold, LENS_R - 1.5f, c, style = Stroke(3f))
                            // Glass highlight (top-left).
                            val hc = c + Offset(-9f, -14.5f)
                            withTransform({ rotate(-30f, pivot = hc) }) {
                                drawArc(Color(0xBFFFFFFF), 200f, 140f, false, topLeft = hc - Offset(10f, 5.5f), size = Size(20f, 11f), style = Stroke(2f, cap = StrokeCap.Round))
                            }
                        }
                        // Handle: from the ring's edge outward.
                        val r0 = LENS_R + 1f
                        val ux = cos(p.handleAngle)
                        val uy = sin(p.handleAngle)
                        val hs = Offset(p.cx + ux * r0, p.cy + uy * r0)
                        val he = Offset(p.cx + ux * (r0 + 34f), p.cy + uy * (r0 + 34f))
                        drawLine(Brush.linearGradient(listOf(Color(0xFFA86E1F), GoldLight), start = hs, end = he), hs, he, strokeWidth = 6f, cap = StrokeCap.Round)
                    }
                }
                if (bestQuality != null) {
                    val shape = RoundedCornerShape(u(8f))
                    Text(
                        bestQuality,
                        Modifier
                            .offset {
                                val p = findPose(time.floatValue)
                                IntOffset(((p.cx + 20f) * s).dp.roundToPx(), ((p.cy - 48f) * s).dp.roundToPx())
                            }
                            .graphicsLayer {
                                val ph = time.floatValue % 4.4f
                                alpha = when {
                                    ph < 2.8f -> 0f
                                    ph < 3.9f -> ((ph - 2.8f) / 0.3f).coerceIn(0f, 1f)
                                    else -> (1f - (ph - 3.9f) / 0.3f).coerceIn(0f, 1f)
                                }
                                shadowElevation = (6f * s).dp.toPx()
                                this.shape = shape
                                clip = true
                            }
                            .background(Brush.horizontalGradient(listOf(GoldLight, Color(0xFFD9A441))))
                            .padding(horizontal = u(7f), vertical = u(3f)),
                        color = Color(0xFF1A1206), fontFamily = InterFont, fontWeight = FontWeight.Bold, maxLines = 1,
                        fontSize = with(LocalDensity.current) { (10f * s).dp.toSp() }, letterSpacing = with(LocalDensity.current) { (0.6f * s).dp.toSp() },
                    )
                }
            }
            SceneText("STREAMX CONCIERGE", "Finding the", "finest", "copy for you", s, time) {
                Crossfade(hint, animationSpec = tween(400), label = "findHint") { i ->
                    val text = when (i) {
                        0 -> if (total > 0) "Checking ${checked.coerceIn(1, total)} of $total sources" else "Looking through your sources"
                        1 -> "Inspecting the picture quality"
                        else -> "Hindi audio first — worth the wait"
                    }
                    Box(Modifier.fillMaxWidth(), Alignment.Center) { SceneLine(text, s) }
                }
            }
        }
    }
}

// ───────────── Serving: the cloche lifts off the poster by itself ─────────────

private const val SPX = 228f
private const val SPY = 64f
private const val SPW = 104f
private const val SPH = 156f
private const val PLATE_X = 160f
private const val LID_X = 192f
private const val LID_Y = 52f
private const val LID_OX = 88f
private const val LID_OY = 158.4f

private class ServePose(val lidTx: Float, val lidTy: Float, val lidRot: Float, val lidSc: Float, val lidAlpha: Float)

private fun servePose(b: Float): ServePose {
    // A little rattle while steam escapes, then the lid lifts off by itself and floats aside.
    val rattle = if (b < 1f) sin(b * 40f) * max(0f, 1f - b) * 1.6f else 0f
    val lift = ((b - 1f) / 1.4f).coerceIn(0f, 1f)
    val e = 1f - (1f - lift) * (1f - lift) * (1f - lift)
    val hover = sin(b * 2f) * 3f * e
    return ServePose(
        lidTx = 132f * e,
        lidTy = -70f * e + hover - abs(rattle),
        lidRot = 24f * e + rattle,
        lidSc = 1f - 0.42f * e,
        lidAlpha = 1f - 0.12f * e,
    )
}

private val goldH = listOf(GoldDark, GoldLight, GoldDark)

private fun DrawScope.drawPlate() {
    translate(PLATE_X, 196f) {
        drawOval(Color.Black.copy(alpha = 0.55f), topLeft = Offset(16f, 32f), size = Size(208f, 16f))
        drawOval(Brush.horizontalGradient(goldH, startX = 10f, endX = 230f), topLeft = Offset(10f, 10f), size = Size(220f, 32f))
        val pc = Offset(120f, 24f)
        withTransform({ scale(1f, 12f / 100f, pivot = pc) }) {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFF4F2EE), Color(0xFF9C9993)), center = Offset(120f, 4f), radius = 120f), radius = 100f, center = pc)
        }
        drawOval(Color(0xFFD9D6CF), topLeft = Offset(46f, 16f), size = Size(148f, 16f))
    }
}

private val lidDome = Path().apply {
    moveTo(8f, 170f)
    cubicTo(8f, 70f, 44f, 22f, 88f, 22f)
    cubicTo(132f, 22f, 168f, 70f, 168f, 170f)
    close()
}
private val lidShine = Path().apply {
    moveTo(40f, 60f)
    cubicTo(52f, 40f, 70f, 32f, 84f, 30f)
}

private fun DrawScope.drawLid(alpha: Float) {
    drawPath(
        lidDome,
        Brush.linearGradient(
            0f to Color(0xFFF4F1EA), 0.4f to Color(0xFFAEABA5), 0.72f to Color(0xFF5D5B58), 1f to Color(0xFFC9C5BD),
            start = Offset(8f, 22f), end = Offset(168f, 170f),
        ),
        alpha = alpha,
    )
    drawPath(lidShine, Color.White.copy(alpha = 0.55f), alpha = alpha, style = Stroke(5f, cap = StrokeCap.Round))
    drawRoundRect(Brush.horizontalGradient(goldH, startX = 2f, endX = 174f), topLeft = Offset(2f, 166f), size = Size(172f, 7f), cornerRadius = CornerRadius(3.5f), alpha = alpha)
    drawCircle(Brush.horizontalGradient(goldH, startX = 80f, endX = 96f), radius = 8f, center = Offset(88f, 15f), alpha = alpha)
}

/** Rounded-rect outline around the small poster, starting at the top-left corner, clockwise. */
private val progressPath = Path().apply {
    val l = SPX + 1.5f
    val t = SPY + 1.5f
    val r = l + 101f
    val b = t + 153f
    val rad = 11f
    moveTo(l + rad, t)
    lineTo(r - rad, t)
    arcTo(Rect(r - 2 * rad, t, r, t + 2 * rad), -90f, 90f, false)
    lineTo(r, b - rad)
    arcTo(Rect(r - 2 * rad, b - 2 * rad, r, b), 0f, 90f, false)
    lineTo(l + rad, b)
    arcTo(Rect(l, b - 2 * rad, l + 2 * rad, b), 90f, 90f, false)
    lineTo(l, t + rad)
    arcTo(Rect(l, t, l + 2 * rad, t + 2 * rad), 180f, 90f, false)
    close()
}

private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x)

// Smoke puffs: left, top, size (design units) and start delay (s). The first five sit behind the lid,
// the rest in front of it.
private val puffsBack = floatArrayOf(
    236f, 46f, 40f, 0.3f,
    268f, 22f, 60f, 1.0f,
    298f, 44f, 46f, 1.6f,
    250f, 30f, 54f, 2.2f,
    286f, 52f, 36f, 2.8f,
)
private val puffsFront = floatArrayOf(
    232f, 40f, 46f, 0.0f,
    262f, 28f, 56f, 0.6f,
    292f, 40f, 42f, 1.2f,
    252f, 50f, 38f, 1.8f,
    282f, 34f, 50f, 2.4f,
)
// Thin wisps: left, top, start delay.
private val wispsAt = floatArrayOf(
    252f, 44f, 0f,
    278f, 38f, 0.8f,
    304f, 44f, 1.6f,
    265f, 46f, 0.4f,
    292f, 42f, 1.2f,
)

private fun DrawScope.drawPuffs(puffs: FloatArray, b: Float) {
    var i = 0
    while (i + 3 < puffs.size) {
        val left = puffs[i]
        val top = puffs[i + 1]
        val w = puffs[i + 2]
        val delayS = puffs[i + 3]
        i += 4
        if (b < delayS) continue
        val q = ((b - delayS) % 3.2f) / 3.2f
        val a = if (q < 0.3f) 0.9f * easeOut(q / 0.3f) else 0.9f * (1f - easeOut((q - 0.3f) / 0.7f))
        val e = easeOut(q)
        val c = Offset(left + w / 2f - 14f * e, top + w / 2f + 10f - 70f * e)
        val rad = (w / 2f) * (0.5f + 1.1f * e) + 4f
        drawCircle(
            Brush.radialGradient(0f to Color(0x8CFFF6EC), 0.45f to Color(0x4DFFF4E6), 1f to Color.Transparent, center = c, radius = rad),
            radius = rad, center = c, alpha = a.coerceIn(0f, 1f),
        )
    }
}

private fun DrawScope.drawWisps(b: Float) {
    var i = 0
    while (i + 2 < wispsAt.size) {
        val x0 = wispsAt[i]
        val y0 = wispsAt[i + 1]
        val delayS = wispsAt[i + 2]
        i += 3
        if (b < delayS) continue
        val q = ((b - delayS) % 2.4f) / 2.4f
        val a = if (q < 0.4f) 0.9f * (q / 0.4f) else 0.9f * (1f - (q - 0.4f) / 0.6f)
        val e = q * q
        val x = x0 - 8f * e
        val y = y0 + 8f - 42f * e
        drawRoundRect(
            Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3FFF0DC), Color.Transparent), startY = y, endY = y + 30f),
            topLeft = Offset(x - 0.5f, y), size = Size(3.5f, 30f), cornerRadius = CornerRadius(2f), alpha = a.coerceIn(0f, 1f),
        )
    }
}

/**
 * "Serving it fresh": the poster stands on a gold plate under a silver cloche; the lid rattles, then lifts
 * off by itself and steam rises. A gold outline around the poster shows [progress] (0..1).
 */
@Composable
fun ServingScene(poster: String?, progress: Float, modifier: Modifier = Modifier) {
    val time = rememberSceneClock()
    val posterPainter = rememberPosterPainter(poster)
    val prog by animateFloatAsState(progress.coerceIn(0f, 1f), tween(300), label = "serveProgress")
    val measure = remember { PathMeasure().apply { setPath(progressPath, false) } }
    val segment = remember { Path() }

    BoxWithConstraints(modifier.fillMaxSize().drawBehind { warmTone(this) }) {
        val s = stageScale(maxWidth, maxHeight)
        fun u(v: Float) = (v * s).dp
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(u(560f), u(262f))) {
                // The plate (below the poster).
                Canvas(Modifier.matchParentSize()) {
                    val k = size.width / 560f
                    withTransform({ scale(k, k, pivot = Offset.Zero) }) { drawPlate() }
                }
                PosterCard(posterPainter, "", s, Modifier.offset(u(SPX), u(SPY)).size(u(SPW), u(SPH)))
                // Progress outline, steam, the lid and more steam in front of it.
                Canvas(Modifier.matchParentSize()) {
                    val k = size.width / 560f
                    val b = time.floatValue
                    val p = servePose(b)
                    withTransform({ scale(k, k, pivot = Offset.Zero) }) {
                        if (prog > 0.002f) {
                            segment.reset()
                            measure.getSegment(0f, measure.length * prog, segment, true)
                            drawPath(segment, Gold.copy(alpha = 0.22f), style = Stroke(9f, cap = StrokeCap.Round))
                            drawPath(segment, Gold.copy(alpha = 0.4f), style = Stroke(5.5f, cap = StrokeCap.Round))
                            drawPath(segment, Gold, style = Stroke(3f, cap = StrokeCap.Round))
                        }
                        drawWisps(b)
                        drawPuffs(puffsBack, b)
                        withTransform({
                            translate(LID_X + p.lidTx, LID_Y + p.lidTy)
                            rotate(p.lidRot, pivot = Offset(LID_OX, LID_OY))
                            scale(p.lidSc, p.lidSc, pivot = Offset(LID_OX, LID_OY))
                        }) { drawLid(p.lidAlpha) }
                        drawPuffs(puffsFront, b)
                    }
                }
            }
            val pct = (prog * 100f).roundToInt().coerceIn(0, 100)
            val phase = when {
                prog < 0.45f -> "Plating your movie"
                prog < 0.8f -> "Fresh out of the kitchen"
                else -> "Your table is ready"
            }
            SceneText("STREAMX KITCHEN", "Serving it", "fresh", "for you", s, time) { SceneLine("$phase · $pct%", s) }
        }
    }
}
