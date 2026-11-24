package com.example.streamnest

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.draw.blur
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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

/** 0.9.76: with the Glass theme (phones) pages are see-through over the blurred picture drawn behind the whole app. */
// 0.9.2: solid again while the video player is open (nothing see-through is drawn under the video).
val Bg: Color get() = if (Palette.glass && !BuildConfig.IS_TV && !GlassBg.paused) Color(0x8C070810) else Palette.bg
/** 0.9.76: a dark panel colour that turns frosted (see-through) with the Glass theme, so every page follows it. */
fun glassy(c: Color): Color = if (Palette.glass && !BuildConfig.IS_TV && !GlassBg.paused) c.copy(alpha = 0.62f) else c

/**
 * 0.9.76: the picture behind the whole app with the Glass theme (Home's top card, the open title…).
 * 0.9.78: never cleared — a missing picture keeps the last one (the page went black after the player or a sync).
 * 0.9.2: the last blurred picture and its shade live here (not inside the screen), so the backdrop survives the
 * screen being rebuilt, the theme flicking off and on, and a picture that fails to load; it is also kept on disk so a
 * fresh start shows the last one instead of black. [paused]: the player (or a full-screen trailer) is open.
 */
object GlassBg {
    private val s = androidx.compose.runtime.mutableStateOf<String?>(null)
    var url: String? get() = s.value; set(v) { if (v != null) s.value = v }
    /** 0.9.2: the backdrop on screen now (kept until a new one is ready). */
    internal val shown = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    internal val tint = androidx.compose.runtime.mutableStateOf(Color(0xFF070810))
    /** 0.9.2: no backdrop (and no see-through pages) while the video player is on screen. */
    var paused by androidx.compose.runtime.mutableStateOf(false)
    @Volatile internal var diskTried = false
    internal fun file(ctx: android.content.Context) = java.io.File(ctx.cacheDir, "glass_last.png")
}

/**
 * 0.9.78: the Glass backdrop as the TV draws it — the picture at 192×108, blurred ONCE in the background (three box passes
 * ≈ a gaussian) and kept, then simply drawn: nothing is blurred per frame, so scrolling over it costs almost nothing. The
 * picture's own colours show through (a lighter dim than before), and the last backdrop stays while a new one is made.
 * 0.9.2: never black while Glass is on: a picture that fails keeps the last one (tries the full-size picture once),
 * the last one comes back from disk after a restart, and with nothing at all a soft shade in the theme's colours shows.
 */
@Composable
fun GlassBackdrop(url: String?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val small = androidx.compose.runtime.remember(url) { url?.replace(Regex("/t/p/(original|w1280|w780)/"), "/t/p/w300/")?.replace("/background/large/", "/background/small/")?.replace("/background/medium/", "/background/small/")?.replace("/poster/large/", "/poster/small/") }
    var last by GlassBg.shown
    var tint by GlassBg.tint
    // 0.9.2: after a restart, the last backdrop from disk while the new one is made.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (last != null || GlassBg.diskTried) return@LaunchedEffect
        GlassBg.diskTried = true
        val b = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { GlassBg.file(ctx).takeIf { it.length() > 0 }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }?.asImageBitmap() }.getOrNull()
        }
        if (b != null && last == null) last = b
    }
    androidx.compose.runtime.LaunchedEffect(small) {
        val u = small ?: return@LaunchedEffect
        TvGlass.blurred[u]?.let { last = it; GlassTints[u]?.let { t -> tint = t }; return@LaunchedEffect }
        val b = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            suspend fun load(x: String): android.graphics.drawable.Drawable? = runCatching {
                val r = coil.request.ImageRequest.Builder(ctx).data(x).size(192, 108).allowHardware(false).build()
                (coil.Coil.imageLoader(ctx).execute(r) as? coil.request.SuccessResult)?.drawable
            }.getOrNull()
            runCatching {
                // 0.9.2: the small copy failed (not every site has one) → the picture as given, once.
                val d = load(u) ?: url?.takeIf { it != u }?.let { load(it) } ?: return@runCatching null
                val bm = (d as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: android.graphics.Bitmap.createBitmap(192, 108, android.graphics.Bitmap.Config.ARGB_8888).also { x ->
                    val c = android.graphics.Canvas(x); d.setBounds(0, 0, 192, 108); d.draw(c) }
                val bl = TvGlass.blur(android.graphics.Bitmap.createScaledBitmap(bm, 192, 108, true), 7, 3)
                // 0.9.78: the page's shade takes the picture's own colour (its average, kept very dark).
                val px = android.graphics.Bitmap.createScaledBitmap(bl, 1, 1, true).getPixel(0, 0)
                GlassTints[u] = Color(((px shr 16 and 0xFF) * 0.2f + 4) / 255f, ((px shr 8 and 0xFF) * 0.2f + 5) / 255f, ((px and 0xFF) * 0.2f + 9) / 255f)
                // 0.9.2: kept on disk (≈ 40 KB) for the next start.
                runCatching { java.io.FileOutputStream(GlassBg.file(ctx)).use { o -> bl.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o) } }
                bl.asImageBitmap()
            }.getOrNull()
        }
        if (b != null) { if (TvGlass.blurred.size > 6) { TvGlass.blurred.clear(); GlassTints.keys.retainAll(setOf(u)) }; TvGlass.blurred[u] = b; last = b; GlassTints[u]?.let { tint = it } }
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color(0xFF07080C))) {
        // 0.9.2: nothing loaded yet: a soft glow in the theme's colours, never plain black.
        if (last == null) androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.radialGradient(
            listOf(Palette.tint.copy(alpha = 0.9f), Palette.accent.copy(alpha = 0.18f), Color(0xFF07080C)))))
        androidx.compose.animation.Crossfade(last, Modifier.fillMaxSize(), animationSpec = androidx.compose.animation.core.tween(600), label = "glassBg") { b ->
            if (b != null) androidx.compose.foundation.Image(b, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High, alpha = 0.8f)
        }
        val t by androidx.compose.animation.animateColorAsState(tint, androidx.compose.animation.core.tween(600), label = "glassTint")
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color(0x33000000), 0.6f to t.copy(alpha = 0.5f), 1f to t.copy(alpha = 0.8f))))
    }
}
/**
 * 0.9.2: a soft (blurred-looking) picture for long lists: a tiny copy (32 px wide) stretched up, so nothing is blurred
 * on every frame while the list scrolls (Modifier.blur re-blurs each card as it moves).
 */
@Composable
fun SoftImage(url: String?, modifier: Modifier, alpha: Float = 1f) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val req = androidx.compose.runtime.remember(url) { coil.request.ImageRequest.Builder(ctx).data(url).size(32, 18).build() }
    coil.compose.AsyncImage(req, null, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop, alpha = alpha,
        filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
}
/** 0.9.78: each backdrop's dark shade in the picture's own colour (made once with the blurred picture). */
private val GlassTints = java.util.concurrent.ConcurrentHashMap<String, Color>()
val Accent: Color get() = Palette.accent
/** Card / placeholder surface: a deep neutral graphite (no blue cast), so artwork colours look true. */
val Surface1: Color get() = if (Palette.glass) Color(0x2EFFFFFF) else Color(0xFF15161C)

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
            background = Bg, surface = if (Palette.glass && !BuildConfig.IS_TV) Color(0xEB12131A) else Bg, surfaceVariant = Surface1,
            onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = Color(0xFFB6BDCC),
        ),
    ) {
        // Every Text/Icon defaults to white, so nothing renders black on the dark background.
        // No tap ripples anywhere in the app (clean, premium feel).
        // TV: no tap ripples either, but whatever has focus is clearly highlighted (the remote has to see it).
        if (BuildConfig.IS_TV) CompositionLocalProvider(
            LocalContentColor provides Color.White,
            LocalIndication provides TvFocusIndication,
            // TV 0.8.7: Material buttons ("Publish these add-ons for everyone", dialog buttons, icon buttons) showed almost
            // nothing when focused. Their own focus layer is now a clear white wash, drawn inside the button's own shape.
            LocalRippleConfiguration provides androidx.compose.material3.RippleConfiguration(
                color = Color.White,
                rippleAlpha = androidx.compose.material.ripple.RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.34f, hoveredAlpha = 0.12f, pressedAlpha = 0.22f),
            ),
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
        /**
         * TV 0.8.7: round items get a circle; everything else a small 10 dp corner. Most items are clipped to their own
         * shape (chip, pill, card) before they are clickable, so the wash is cut to exactly that shape. The old guess (a
         * pill for anything short) drew a pill inside 14 dp chips — the highlight didn't match the button.
         */
        private fun androidx.compose.ui.graphics.drawscope.DrawScope.radius(): Float {
            val w = size.width; val h = size.height
            val mn = minOf(w, h); val mx = maxOf(w, h)
            return when {
                mx > 0f && (mx - mn) / mx < 0.15f -> mn / 2f
                else -> 10.dp.toPx()
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

/** Brand red used for the "X" in the StreamX wordmark. */
val BrandRed = Color(0xFFE50914)

/** App name wordmark. "StreamX" is drawn as "Stream" + a red "X"; any custom name is drawn plainly. */
@Composable
fun Wordmark(name: String, fontSize: TextUnit = 18.sp, animated: Boolean = false) {
    val n = name.trim()
    // The developer's designed look for the app name (Developer's Corner › AI assistant), when there is one.
    parseNameStyle(Public.vm?.texts?.value?.get("appNameStyle"))?.let { StyledName(n.ifBlank { "${Brand.NAME}" }, it, fontSize); return }
    // Brand: either app's name (a name synced from the other app) is drawn as this app's wordmark.
    val brand = n.lowercase() == "streamx" || n.lowercase() == "xstream"
    if (animated && brand) { HangingWordmark(Brand.NAME, fontSize); return }
    if (brand) {
        val b = Brand.NAME
        Text(
            buildAnnotatedString {
                // StreamX: "Stream" + red "X". Xstream: red "X" + "stream".
                if (Brand.X_FIRST) { withStyle(SpanStyle(color = BrandRed)) { append(b.take(1)) }; append(b.drop(1)) }
                else { append(b.dropLast(1)); withStyle(SpanStyle(color = BrandRed)) { append(b.takeLast(1)) } }
            },
            fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = fontSize, letterSpacing = (-0.5).sp, color = Color.White,
        )
    } else Text(n, fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = fontSize, letterSpacing = (-0.5).sp)
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
 * The same StreamX wordmark (white "Stream", red "X"), animated: when the app opens the X is attached, then comes loose at
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
    if (Brand.X_FIRST) androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
        // Xstream: the red X hangs from its top-right corner, where it touches "stream".
        Text(
            n.take(1),
            androidx.compose.ui.Modifier.graphicsLayer {
                rotationZ = -angle.value
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.88f, 0.16f)
            },
            fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = fontSize, letterSpacing = (-0.5).sp, color = BrandRed,
        )
        Text(n.drop(1), fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = fontSize, letterSpacing = (-0.5).sp, color = Color.White)
    } else androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
        Text(n.dropLast(1), fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = fontSize, letterSpacing = (-0.5).sp, color = Color.White)
        Text(
            n.takeLast(1),
            androidx.compose.ui.Modifier.graphicsLayer {
                rotationZ = angle.value
                // Hangs from its top-left corner, where it touches "Stream".
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.12f, 0.16f)
            },
            fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = fontSize, letterSpacing = (-0.5).sp, color = BrandRed,
        )
    }
}
