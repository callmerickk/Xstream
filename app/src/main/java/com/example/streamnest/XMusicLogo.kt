package com.example.streamnest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

/**
 * Music prompt 4 (switch musicLogo2): the new Xstream Music logo, drawn from pack-L-logo.svg on a 1024-unit canvas.
 * A dark rounded tile with purple + red glow, a glossy red ribbon "X" wearing silver headphones with red ear cups,
 * two sound waves out of each cup, and the red "● MUSIC" pill. The layers are separate so the opening can animate them.
 */
private object XLogoState { var logged = false }

private fun ribbon(y0: Float, y1: Float) = Brush.verticalGradient(
    0f to Color(0xFFFF7A7F), 0.3f to Color(0xFFF01622), 0.8f to Color(0xFFB8060F), 1f to Color(0xFF6E0007),
    startY = y0, endY = y1,
)
private val BarBrush = ribbon(-59f, 59f)
private val CupBrush = ribbon(420f, 620f)
private val TileBrush = Brush.verticalGradient(0f to Color(0xFF34323D), 0.55f to Color(0xFF1B1A21), 1f to Color(0xFF0F0F12), startY = 0f, endY = 1024f)
private val GlowPurple = Brush.radialGradient(listOf(Color(0x8C6A5A8A), Color(0x006A5A8A)), Offset(512f, 389f), 563f)
private val GlowRed = Brush.radialGradient(listOf(Color(0x8CFF1A2A), Color(0x00FF1A2A)), Offset(512f, 480f), 330f)
private val BandBrush = Brush.verticalGradient(0f to Color(0xFFD9D9DE), 1f to Color(0xFF5D5D66), startY = 270f, endY = 500f)
private val WaveBrush = Brush.linearGradient(listOf(Color.White, Color(0xFF9B9BA3)), Offset(0f, -88f), Offset(90f, 88f))
private val PillBrush = Brush.verticalGradient(0f to Color(0xFFFF2A34), 1f to Color(0xFFB8050E), startY = 806f, endY = 898f)

/** "MUSIC" as strokes (same shapes as the launcher icon), so no font is needed. */
private val letters: Path by lazy {
    PathParser().parsePathString(
        "M456,870V840L473,858L490,840V870 M506,840V856A14,14 0 0 0 534,856V840 " +
            "M572,845C570,838 552,838 552,848C552,858 572,855 572,864C572,874 554,874 551,865 " +
            "M590,840V870 M634,848C630,838 606,838 606,855C606,872 630,872 634,862",
    ).toPath()
}

/** The tile with its two glows and the thin light edge. Draw it inside a 1024-unit transform. */
internal fun DrawScope.xLogoTile() {
    drawRoundRect(TileBrush, Offset.Zero, Size(1024f, 1024f), CornerRadius(230f))
    drawRoundRect(GlowPurple, Offset.Zero, Size(1024f, 1024f), CornerRadius(230f))
    drawCircle(GlowRed, 330f, Offset(512f, 480f))
    drawRoundRect(Color.White.copy(alpha = 0.08f), Offset(3f, 3f), Size(1018f, 1018f), CornerRadius(228f), style = Stroke(5f))
}

/** Headphones + X ribbon + ear cups + the four waves. [waveAlpha] fades only the waves, [showX] hides only the ribbon. */
internal fun DrawScope.xLogoArt(waveAlpha: Float = 1f, showX: Boolean = true) {
    withTransform({ translate(512f, 470f); scale(1.1f, 1.1f, Offset.Zero); translate(-460f, -500f) }) {
        // headband + its light streak
        drawArc(BandBrush, 180f, 180f, false, Offset(230f, 270f), Size(460f, 460f), style = Stroke(44f, cap = StrokeCap.Round))
        drawArc(Color.White.copy(alpha = 0.35f), 188.6f, 70.1f, false, Offset(260f, 300f), Size(400f, 400f), style = Stroke(10f, cap = StrokeCap.Round))
        // the X: two glossy ribbons (soft shadow = two offset dark copies, no blur needed)
        if (showX) for (deg in floatArrayOf(-45f, 45f)) {
            withTransform({ translate(460f, 505f); scale(0.78f, 0.78f, Offset.Zero); rotate(deg, Offset.Zero) }) {
                drawRoundRect(Color.Black.copy(alpha = 0.15f), Offset(-213f, -51f), Size(446f, 134f), CornerRadius(67f))
                drawRoundRect(Color.Black.copy(alpha = 0.30f), Offset(-205f, -43f), Size(430f, 118f), CornerRadius(59f))
                drawRoundRect(BarBrush, Offset(-215f, -59f), Size(430f, 118f), CornerRadius(59f))
                drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(-175f, -45f), Size(350f, 23.6f), CornerRadius(11.8f))
            }
        }
        // ear cups
        for (x in floatArrayOf(168f, 656f)) {
            drawRoundRect(CupBrush, Offset(x, 420f), Size(96f, 200f), CornerRadius(44f))
            drawRoundRect(Color.White.copy(alpha = 0.4f), Offset(x + 18f, 440f), Size(22f, 150f), CornerRadius(11f))
        }
        // waves: two out of each cup (left one mirrored)
        if (waveAlpha > 0f) {
            val inner = Path().apply { moveTo(0f, -50f); quadraticBezierTo(30f, 0f, 0f, 50f) }
            val outer = Path().apply { moveTo(42f, -88f); quadraticBezierTo(92f, 0f, 42f, 88f) }
            val st = Stroke(26f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            withTransform({ translate(148f, 520f); scale(-1f, 1f, Offset.Zero) }) {
                drawPath(inner, WaveBrush, waveAlpha, st); drawPath(outer, WaveBrush, 0.75f * waveAlpha, st)
            }
            withTransform({ translate(772f, 520f) }) {
                drawPath(inner, WaveBrush, waveAlpha, st); drawPath(outer, WaveBrush, 0.75f * waveAlpha, st)
            }
        }
    }
}

/** The red "● MUSIC" pill (outside the art group, as in the SVG). */
internal fun DrawScope.xLogoPill() {
    drawRoundRect(PillBrush, Offset(352f, 806f), Size(320f, 92f), CornerRadius(46f))
    drawRoundRect(Color(0xFF5A0006), Offset(352f, 806f), Size(320f, 92f), CornerRadius(46f), style = Stroke(5f))
    drawCircle(Color.White, 13f, Offset(404f, 852f))
    drawPath(letters, Color.White, style = Stroke(8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** The one Music logo, [size] square (tile included). */
@Composable
fun XMusicLogo(size: Dp, modifier: Modifier = Modifier, tile: Boolean = true) {
    LaunchedEffect(Unit) {
        if (!XLogoState.logged) { XLogoState.logged = true; MusicLog.add("New Music logo on: X ribbon + headphones + MUSIC pill (musicLogo2)") }
    }
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 1024f
        withTransform({ scale(k, k, Offset.Zero) }) {
            if (tile) xLogoTile()
            xLogoArt()
            xLogoPill()
        }
    }
}
