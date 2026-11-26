package com.example.streamnest

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One pulse of the sound waves (p = 0..1): a ring grows out of each ear cup and fades. Same art transform as xLogoArt. */
internal fun DrawScope.xLogoPulse(p: Float) {
    val s = 1f + 1.6f * p
    val a = (1f - p) * 0.85f
    val ring = Path().apply { moveTo(0f, -50f); quadraticBezierTo(30f, 0f, 0f, 50f) }
    val st = Stroke(14f / s, cap = StrokeCap.Round)
    val c = Color(0xFFFFB3B8)
    withTransform({ translate(512f, 470f); scale(1.1f, 1.1f, Offset.Zero); translate(-460f, -500f) }) {
        withTransform({ translate(148f, 520f); scale(-s, s, Offset.Zero) }) { drawPath(ring, c, a, st) }
        withTransform({ translate(772f, 520f); scale(s, s, Offset.Zero) }) { drawPath(ring, c, a, st) }
    }
}

/** The red MUSIC pill on its own, [width] wide. */
@Composable
fun XMusicPill(width: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width, width * (102f / 330f))) {
        val k = size.width / 330f
        withTransform({ scale(k, k, Offset.Zero); translate(-347f, -801f) }) { xLogoPill() }
    }
}

/**
 * Xstream Music opening (switch musicLogo2): StartupSplash's own timings and easing with the new logo.
 * Icon pops in, wordmark slides in, the red X pops while the waves pulse out of both ear cups (3 pulses) and the red
 * MUSIC pill pops in below-right of the wordmark; then everything fades and the red X zooms toward you (never out).
 */
@Composable
fun MusicLogoOpening(onDone: () -> Unit) {
    val bg = remember { Animatable(0f) }
    val mark = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val x = remember { Animatable(0f) }
    val pill = remember { Animatable(0f) }
    val pulse = remember { Animatable(0f) }      // 0..3 = three pulses
    val zoom = remember { Animatable(1f) }
    val out = remember { Animatable(1f) }        // icon + wordmark + pill fade
    val xAlpha = remember { Animatable(1f) }
    val done by rememberUpdatedState(onDone)

    LaunchedEffect(Unit) {
        MusicLog.add("Music opening (musicLogo2): Xstream timings, icon, 3 wave pulses, MUSIC pill")
        launch { bg.animateTo(1f, tween(600)) }
        launch { delay(260); mark.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f)) }
        launch { delay(780); word.animateTo(1f, tween(480, easing = FastOutSlowInEasing)) }
        delay(980)
        launch { pulse.animateTo(3f, tween(900, easing = LinearEasing)) }
        launch { delay(120); pill.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 300f)) }
        x.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 300f))
        delay(650)
        launch { out.animateTo(0f, tween(320)) }
        launch { delay(250); bg.animateTo(0f, tween(800)) }
        launch { delay(260); xAlpha.animateTo(0f, tween(700)) }
        zoom.animateTo(26f, tween(950, easing = CubicBezierEasing(0.6f, 0f, 0.8f, 0.4f)))
        done()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Deep purple from the top, red glow from below.
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = bg.value }) {
            drawRect(Brush.linearGradient(listOf(Color(0xFF1C0B34), Color(0xFF12061F), Color(0xFF07040A)), Offset.Zero, Offset(size.width, size.height)))
            drawRect(Brush.radialGradient(listOf(Color(0x80803CC8), Color.Transparent), Offset(size.width * 0.5f, size.height * 0.2f), size.maxDimension * 0.6f))
            drawRect(Brush.radialGradient(listOf(Color(0x99E50914), Color.Transparent), Offset(size.width * 0.5f, size.height * 0.88f), size.maxDimension * 0.8f))
        }
        Column(Modifier.align(Alignment.Center).offset(y = (-20).dp), horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.graphicsLayer {
                    val v = mark.value
                    alpha = v.coerceIn(0f, 1f) * out.value
                    scaleX = 0.5f + 0.5f * v; scaleY = scaleX
                }) {
                    // Tile + headphones + X + waves (no pill: the pill pops in beside the wordmark), with the pulses on top.
                    Canvas(Modifier.size(76.dp)) {
                        val k = size.minDimension / 1024f
                        withTransform({ scale(k, k, Offset.Zero) }) {
                            xLogoTile()
                            xLogoArt()
                            for (i in 0..2) {
                                val p = pulse.value - i
                                if (p > 0f && p < 1f) xLogoPulse(p)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                // Same wordmark as Xstream's opening: red X (ZoomX) + the rest in white.
                if (Brand.X_FIRST) ZoomX(44f, { x.value }, { zoom.value }, { xAlpha.value })
                Text(
                    if (Brand.X_FIRST) Brand.NAME.drop(1) else Brand.NAME.dropLast(1),
                    Modifier.graphicsLayer { alpha = word.value * out.value; translationX = (1f - word.value) * -18.dp.toPx() },
                    fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = 44.sp, letterSpacing = (-1).sp, color = Color.White,
                )
                if (!Brand.X_FIRST) ZoomX(44f, { x.value }, { zoom.value }, { xAlpha.value })
            }
            // Where "LIVE" sits in Xstream Live: below-right of the wordmark.
            XMusicPill(
                88.dp,
                Modifier.offset(y = (-2).dp).graphicsLayer {
                    val v = pill.value
                    alpha = v.coerceIn(0f, 1f) * out.value
                    val s = 0.4f + 0.6f * v; scaleX = s; scaleY = s
                },
            )
        }
    }
}
