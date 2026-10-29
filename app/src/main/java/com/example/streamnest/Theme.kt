package com.example.streamnest

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.node.invalidateDraw

val Bg: Color get() = Palette.bg
val Accent: Color get() = Palette.accent
/** Card / placeholder surface: a deep neutral graphite (no blue cast), so artwork colours look true. */
val Surface1 = Color(0xFF15161C)

// Rounded wordmark font used for the app name (Poppins, free Google font).
val BrandFont = FontFamily(
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamNestTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = BtnColor, onPrimary = OnBtn,
            background = Bg, surface = Bg, surfaceVariant = Surface1,
            onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = Color(0xFFB6BDCC),
        ),
    ) {
        // Every Text/Icon defaults to white, so nothing renders black on the dark background.
        // No tap ripples anywhere in the app (clean, premium feel).
        // TV: no tap ripples either, but whatever has focus is clearly highlighted (the remote has to see it).
        if (BuildConfig.IS_TV) CompositionLocalProvider(
            LocalContentColor provides Color.White,
            LocalIndication provides TvFocusIndication,
        ) { content() }
        else CompositionLocalProvider(
            LocalContentColor provides Color.White,
            LocalIndication provides NoIndication,
            LocalRippleConfiguration provides null,
        ) { Responsive(content) }
    }
}

/** An indication that draws nothing: taps don't flash a ripple. */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 7
}

/**
 * TV: a soft white wash and a thin white outline on the focused item (no press ripple). Drawn only on focus changes.
 */
private object TvFocusIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = FocusNode(interactionSource)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 11

    private class FocusNode(private val src: InteractionSource) : Modifier.Node(), androidx.compose.ui.node.DrawModifierNode {
        private var focused = false
        override fun onAttach() {
            coroutineScope.launch {
                var n = 0
                src.interactions.collect { i ->
                    when (i) {
                        is androidx.compose.foundation.interaction.FocusInteraction.Focus -> n++
                        is androidx.compose.foundation.interaction.FocusInteraction.Unfocus -> n--
                    }
                    val f = n > 0
                    if (f != focused) { focused = f; invalidateDraw() }
                }
            }
        }
        /** Follows the item's shape: round items get a circle, short ones a pill, the rest rounded corners. */
        private fun androidx.compose.ui.graphics.drawscope.DrawScope.radius(): Float {
            val w = size.width; val h = size.height
            val mn = minOf(w, h); val mx = maxOf(w, h)
            return when {
                mx > 0f && (mx - mn) / mx < 0.15f -> mn / 2f
                h <= 56.dp.toPx() -> h / 2f
                else -> 14.dp.toPx()
            }
        }
        override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
            // Wide rows inside a rounded settings card: the highlight sits a little inside the row with its own curve,
            // so it never meets the card's rounded corners at a different radius.
            if (focused && size.width > 300.dp.toPx() && size.height > 40.dp.toPx()) {
                val ix = 6.dp.toPx(); val iy = 4.dp.toPx()
                val r = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx())
                val tl = androidx.compose.ui.geometry.Offset(ix, iy)
                val sz = androidx.compose.ui.geometry.Size(size.width - 2 * ix, size.height - 2 * iy)
                drawRoundRect(Color(0xFF2A2C35), topLeft = tl, size = sz, cornerRadius = r)
                drawRoundRect(Color.White.copy(alpha = 0.08f), topLeft = tl, size = sz, cornerRadius = r)
                drawContent()
                drawRoundRect(Color.White.copy(alpha = 0.85f), topLeft = tl, size = sz, cornerRadius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                return
            }
            // Everything else: a soft light wash over the WHOLE item. It is clipped by the item's own shape (rounded card,
            // pill, circle…), so it always follows its exact curve — no separate edge that could sit at another radius.
            val cr = androidx.compose.ui.geometry.CornerRadius(radius())
            // No dark base under it any more: an item that is already coloured (a red "selected" button) keeps its colour
            // and simply brightens, instead of turning grey under the highlight.
            if (focused) drawRoundRect(Color.White.copy(alpha = 0.2f), cornerRadius = cr)
            drawContent()
            if (focused) drawRoundRect(Color.White.copy(alpha = 0.06f), cornerRadius = cr)
        }
    }
}

/** Brand red used for the "X" in the Xstream wordmark. */
val BrandRed = Color(0xFFE50914)

/** App name wordmark. "Xstream" is drawn as a red "X" + white "stream"; any custom name is drawn plainly. */
@Composable
fun Wordmark(name: String, fontSize: TextUnit = 18.sp, animated: Boolean = false) {
    val n0 = name.trim()
    // The developer's designed look for the app name (Developer's Corner › AI assistant), when there is one.
    parseNameStyle(Public.vm?.texts?.value?.get("appNameStyle"))?.let { StyledName(n0.ifBlank { "Xstream" }, it, fontSize); return }
    // The old name (synced from a StreamX profile) shows as Xstream too.
    val brand = n0.lowercase() == "xstream" || n0.lowercase() == "streamx"
    if (brand && animated) { HangingWordmark("Xstream", fontSize); return }
    if (brand) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = BrandRed)) { append("X") }
                append("stream")
            },
            fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = fontSize, letterSpacing = (-0.5).sp, color = Color.White,
        )
    } else Text(n0, fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = fontSize, letterSpacing = (-0.5).sp)
}

/**
 * Fits every screen: the whole UI is designed for a ~392dp-wide phone and scaled to the real screen
 * (smaller on compact phones, larger on big ones), so icons and text keep the same proportions.
 * The screen size seen by layouts is adjusted to match, so width-based layouts stay correct.
 */
@Composable
private fun Responsive(content: @Composable () -> Unit) {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val shortSide = minOf(cfg.screenWidthDp, cfg.screenHeightDp).takeIf { it > 0 } ?: 392
    val scale = (shortSide / 392f).coerceIn(0.86f, 1.14f)
    // TV screens are designed directly for a 960×540dp (1080p) canvas: no extra scaling there.
    if (BuildConfig.IS_TV || kotlin.math.abs(scale - 1f) < 0.02f) { content(); return }
    val scaledCfg = androidx.compose.runtime.remember(cfg, scale) {
        android.content.res.Configuration(cfg).apply {
            screenWidthDp = (cfg.screenWidthDp / scale).toInt()
            screenHeightDp = (cfg.screenHeightDp / scale).toInt()
            smallestScreenWidthDp = (cfg.smallestScreenWidthDp / scale).toInt()
        }
    }
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density * scale, density.fontScale),
        androidx.compose.ui.platform.LocalConfiguration provides scaledCfg,
        content = content,
    )
}


/** The hanging-X animation plays once each time the app opens; afterwards the X simply hangs. */
private object LogoSwing { var played = false }

/**
 * The same Xstream wordmark (white "Stream", red "X"), animated: when the app opens the X is attached, then comes loose at
 * one corner and swings from "Stream" like a hanging sign until it settles.
 */
@Composable
private fun HangingWordmark(n: String, fontSize: TextUnit) {
    val angle = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (LogoSwing.played) 34f else 0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (LogoSwing.played) return@LaunchedEffect
        LogoSwing.played = true
        kotlinx.coroutines.delay(900)
        // A small "unhook" jolt, then a pendulum swing that settles (an under-damped spring).
        angle.animateTo(-4f, androidx.compose.animation.core.tween(140, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        angle.animateTo(34f, androidx.compose.animation.core.spring(dampingRatio = 0.22f, stiffness = 38f))
    }
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
        Text(
            n.take(1),
            androidx.compose.ui.Modifier.graphicsLayer {
                rotationZ = -angle.value
                // Hangs from its top-right corner, where it touches "stream".
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.88f, 0.16f)
            },
            fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = fontSize, letterSpacing = (-0.5).sp, color = BrandRed,
        )
        Text(n.drop(1), fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = fontSize, letterSpacing = (-0.5).sp, color = Color.White)
    }
}
