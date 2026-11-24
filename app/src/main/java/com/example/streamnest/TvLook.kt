package com.example.streamnest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/**
 * TV 0.9.0: the TV's look, each part picked on its own (Settings › Look):
 *  - Home style: "sliding" (StreamX TV's card deck, the default), "live" (Xstream's wide card with the curved bottom and
 *    red glow, info under it), "curve" (one big card bent like a curved screen).
 *  - Side bar: "slim" (as before), "glass", "orbit", "solid", "floating" (a see-through capsule over the posters, the
 *    page uses the whole screen), "hidden" (nothing on the left until Play ◀ / a row's first card ◀ / Back opens it).
 *  - Colours: Midnight, Crimson, Elegant, Ocean.
 * Existing users keep Sliding + Slim + their theme until they change it.
 */
object TvLook {
    const val HOME = "tvHomeStyle"
    const val BAR = "tvBar"
    const val ASKED = "tvLookAsked"
    const val OPEN = "tvBarOpen"
    val homes = listOf("sliding" to "Sliding", "live" to "Live", "curve" to "Curve")
    // TV 0.9.3: Glass side bar removed (Glass is a colour theme now); Orbit = Xstream Live's bar standing up.
    val bars = listOf("slim" to "Slim", "floating" to "Floating glass", "orbit" to "Orbit", "solid" to "Solid", "hidden" to "Hidden")
    val opens = listOf("same" to "Same as side bar", "slim" to "Slim menu", "floating" to "Floating glass", "orbit" to "Orbit", "solid" to "Solid panel", "dark" to "Dark panel")

    fun home(texts: Map<String, String>): String = texts[HOME]?.takeIf { v -> homes.any { it.first == v } } ?: "sliding"
    fun bar(texts: Map<String, String>): String = texts[BAR]?.takeIf { v -> bars.any { it.first == v } } ?: "slim"
    fun openChoice(texts: Map<String, String>): String = texts[OPEN]?.takeIf { v -> opens.any { it.first == v } } ?: "same"
    /** How the side bar looks while open: its own look unless another was picked. */
    fun openStyle(texts: Map<String, String>): String = when (val o = openChoice(texts)) {
        "same" -> when (val b = bar(texts)) { "hidden" -> "dark"; else -> b }
        else -> o
    }

    /** The page under a floating / orbit / hidden side bar starts at the screen's left edge. */
    fun fullScreen(bar: String) = bar == "floating" || bar == "orbit" || bar == "hidden"

    data class Theme(val name: String, val accent: Color, val accent2: Color, val bg: String, val tint: Color)

    val themes = listOf(
        // Midnight: Xstream Live's navy black with the red accent.
        Theme("Midnight", Color(0xFFE50914), Color(0xFFFF6A5C), "#0F1014", Color(0xFF16204A)),
        Theme("Crimson", Color(0xFFE8323C), Color(0xFFFF6A5C), "#07080C", lerp(Color(0xFF0B0F1A), Color(0xFFE8323C), 0.22f)),
        Theme("Elegant", Color(0xFFE50914), Color(0xFFFF6A5C), "#0F1014", lerp(Color(0xFF0B0F1A), Color(0xFFE50914), 0.22f)),
        Theme("Ocean", Color(0xFF2E86DE), Color(0xFF5AB0FF), "#07080C", lerp(Color(0xFF0B0F1A), Color(0xFF2E86DE), 0.22f)),
        // Glass: the page is see-through over a soft blur of the poster you are on (Palette.glass, TV only).
        Theme("Glass", Color(0xFFE50914), Color(0xFFFF6A5C), "#8C05060A", Color(0x00000000)),
    )

    private fun hex(c: Color) = String.format("#%06X", c.toArgb() and 0xFFFFFF)
    private fun hexA(c: Color) = String.format("#%08X", c.toArgb())

    /** Same keys as the phone's theme picker (Settings › Layout), so both pages agree. */
    fun applyTheme(vm: AppViewModel, th: Theme) {
        vm.setText("theme", th.name)
        vm.setText("background", th.bg)
        vm.setText("btnColor", ""); vm.setText("btnText", "")
        vm.setText("accent", hex(th.accent))
        vm.setText("accent2", hex(th.accent2))
        vm.setText("deckTint", if (th.name == "Glass") hexA(th.tint) else hex(th.tint))
        ActLog.add("APP", "TV look: colours ${th.name}")
    }

    fun set(vm: AppViewModel, key: String, v: String) {
        vm.setText(key, v)
        ActLog.add("APP", "TV look: $key = $v")
    }
}

/** Xstream Live's card: rounded top, the bottom edge dips gently in the middle. */
val LiveHeroShape = GenericShape { size, _ ->
    val w = size.width; val h = size.height
    val r = h * 0.09f; val dip = h * 0.045f
    moveTo(0f, r); quadraticTo(0f, 0f, r, 0f); lineTo(w - r, 0f); quadraticTo(w, 0f, w, r)
    lineTo(w, h - r * 0.6f); quadraticTo(w, h, w - r, h)
    quadraticTo(w / 2f, h - 2f * dip, r, h)
    quadraticTo(0f, h, 0f, h - r * 0.6f); close()
}

/** A big card on a curved screen seen from the sofa: top and bottom edges bow toward the middle, so the sides look taller. */
val CurveHeroShape = GenericShape { size, _ ->
    val w = size.width; val h = size.height
    val bow = h * 0.075f; val c = h * 0.03f
    moveTo(0f, c); quadraticTo(0f, 0f, c, 0f)
    quadraticTo(w / 2f, 2f * bow, w - c, 0f); quadraticTo(w, 0f, w, c)
    lineTo(w, h - c); quadraticTo(w, h, w - c, h)
    quadraticTo(w / 2f, h - 2f * bow, c, h); quadraticTo(0f, h, 0f, h - c); close()
}

/** The red glow along a hero's bottom edge (stacked soft strokes: no blur filter, works on every TV). */
fun DrawScope.heroGlow(curve: Boolean, cardW: Float, cardH: Float, left: Float, accent: Color) {
    val path = Path().apply {
        if (curve) { val bow = cardH * 0.075f; moveTo(left, cardH); quadraticTo(left + cardW / 2f, cardH - 2f * bow, left + cardW, cardH) }
        else { val r = cardH * 0.09f; val dip = cardH * 0.045f; moveTo(left + r, cardH); quadraticTo(left + cardW / 2f, cardH - 2f * dip, left + cardW - r, cardH) }
    }
    val step = 5.dp.toPx()
    for (i in 5 downTo 1) {
        drawContext.canvas.save()
        drawContext.canvas.translate(0f, step * 0.8f)
        drawPath(path, accent, alpha = 0.07f, style = Stroke(step * i * 1.6f))
        drawContext.canvas.restore()
    }
}

// ───────────── Settings › Look ─────────────

/**
 * The Look section (TV Settings, right side): a small drawing of Home in the chosen look, then three lists —
 * Home style, Side bar, Colours. OK applies at once (the real Home and side bar change behind it).
 */
@Composable
fun TvLookPane(vm: AppViewModel, firstRun: Boolean = false, onDone: (() -> Unit)? = null) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    val home = TvLook.home(texts)
    val bar = TvLook.bar(texts)
    val theme = texts["theme"] ?: Defaults["theme"] ?: "Crimson"
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        for (i in 0 until 6) { if (first.tryFocus()) break; delay(60) }
        // First start: Home may take focus while it loads behind; take it back once.
        if (firstRun) { delay(700); first.tryFocus() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 14.dp, end = 12.dp, bottom = 40.dp)) {
        if (firstRun) {
            Text("Choose your look", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text("Pick how ${Brand.NAME} looks. You can change it any time in Settings › Layout.", Modifier.padding(top = 4.dp), fontSize = 13.sp, color = TvSubText)
            Spacer(Modifier.height(14.dp))
        }
        TvLookBlock(vm, first)
        if (onDone != null) {
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.tvCard(22.dp, scale = 1.05f) { onDone() }.width(180.dp).height(44.dp).clip(CircleShape).background(Color.White),
                Alignment.Center,
            ) { Text(if (firstRun) "Done" else "Close", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black) }
        }
    }
}

/** Preview + the four lists — shared by the first start and Settings › Layout (TV). */
@Composable
fun TvLookBlock(vm: AppViewModel, first: FocusRequester? = null) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    val home = TvLook.home(texts)
    val bar = TvLook.bar(texts)
    val theme = texts["theme"] ?: Defaults["theme"] ?: "Crimson"
    Column(Modifier.padding(top = 6.dp)) {
        TvLookPreview(home, bar, Modifier.padding(start = 10.dp).width(320.dp).height(180.dp))
        Spacer(Modifier.height(10.dp))
        LookGroup("HOME STYLE", TvLook.homes, home, first) { TvLook.set(vm, TvLook.HOME, it) }
        LookGroup("SIDE BAR", TvLook.bars, bar, null) { TvLook.set(vm, TvLook.BAR, it) }
        LookGroup("WHEN IT OPENS", TvLook.opens, TvLook.openChoice(texts), null) { TvLook.set(vm, TvLook.OPEN, it) }
        LookGroup("COLOURS", TvLook.themes.map { it.name to it.name }, theme, null) { n -> TvLook.themes.firstOrNull { it.name == n }?.let { TvLook.applyTheme(vm, it) } }
    }
}

@Composable
private fun LookGroup(title: String, opts: List<Pair<String, String>>, sel: String, firstFr: FocusRequester?, onPick: (String) -> Unit) {
    Text(title, Modifier.padding(start = 10.dp, top = 8.dp, bottom = 2.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Color(0xFF7C8394))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp, horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        opts.forEachIndexed { i, (k, label) ->
            val on = k == sel
            val shape = RoundedCornerShape(14.dp)
            Box(
                Modifier.then(if (i == 0 && firstFr != null) Modifier.focusRequester(firstFr) else Modifier)
                    .tvCard(14.dp, scale = 1.05f) { onPick(k) }
                    .clip(shape)
                    .background(if (on) Accent.copy(alpha = 0.32f) else Color(0x0DFFFFFF))
                    .border(if (on) 2.dp else 1.dp, if (on) Color.White else Color(0x24FFFFFF), shape)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) { Text(label, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium) }
        }
    }
}

/** A small still drawing of Home in the chosen look (cheap: a few shapes, no pictures). */
@Composable
fun TvLookPreview(home: String, bar: String, modifier: Modifier) {
    val accent = Accent
    val bg = Bg
    val tint = Palette.tint
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(14.dp))) {
        val w = size.width; val h = size.height
        drawRect(bg)
        drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.8f), Color.Transparent), Offset(w * 0.55f, 0f), w * 0.5f), w * 0.5f, Offset(w * 0.55f, 0f))
        val full = TvLook.fullScreen(bar)
        val left = if (full) (if (bar == "floating") w * 0.075f else w * 0.03f) else w * 0.08f
        val cw = w - left - w * 0.03f
        val art = Brush.verticalGradient(listOf(Color(0xFF0B1A3A), Color(0xFF2A3F7A), Color(0xFFB9584A), Color(0xFFF2A65A)))
        when (home) {
            "sliding" -> {
                val x = left + w * 0.02f; val cardW = cw * 0.86f; val cardH = h * 0.5f; val y = h * 0.08f
                drawRoundRect(Color(0x553C6B4A), Offset(x + cardW - cardW * 0.82f + cw * 0.12f, y + cardH * 0.09f), Size(cardW * 0.82f, cardH * 0.82f), CornerRadius(8f))
                drawRoundRect(Color(0x775B3D7A), Offset(x + cardW - cardW * 0.91f + cw * 0.06f, y + cardH * 0.045f), Size(cardW * 0.91f, cardH * 0.91f), CornerRadius(8f))
                drawRoundRect(art, Offset(x, y), Size(cardW, cardH), CornerRadius(10f))
                drawRoundRect(accent, Offset(x + cardW * 0.05f, y + cardH * 0.72f), Size(cardW * 0.18f, cardH * 0.14f), CornerRadius(cardH))
            }
            else -> {
                val curve = home == "curve"
                val cardW = cw * 0.82f; val cardH = cardW * 0.37f; val x = left + (cw - cardW) / 2f; val y = h * 0.06f
                drawRoundRect(Color(0x553C6B4A), Offset(x - w * 0.035f, y + cardH * 0.06f), Size(w * 0.02f, cardH * 0.88f), CornerRadius(4f))
                drawRoundRect(Color(0x555B3D7A), Offset(x + cardW + w * 0.015f, y + cardH * 0.06f), Size(w * 0.02f, cardH * 0.88f), CornerRadius(4f))
                drawContext.canvas.save(); drawContext.canvas.translate(0f, y)
                heroGlow(curve, cardW, cardH, x, accent)
                drawContext.canvas.restore()
                val shape = if (curve) CurveHeroShape else LiveHeroShape
                val outline = shape.createOutline(Size(cardW, cardH), layoutDirection, this)
                drawContext.canvas.save(); drawContext.canvas.translate(x, y)
                if (outline is androidx.compose.ui.graphics.Outline.Generic) {
                    drawPath(outline.path, art)
                    drawPath(outline.path, accent, alpha = 0.7f, style = Stroke(1.5.dp.toPx()))
                }
                drawContext.canvas.restore()
                drawRoundRect(Color(0xFFE6E7EB), Offset(x + cardW / 2f - cardW * 0.1f, y + cardH + h * 0.08f), Size(cardW * 0.2f, h * 0.07f), CornerRadius(h))
            }
        }
        // rows
        val ry = h * 0.7f
        for (r in 0..1) for (i in 0..5) {
            val cwid = cw * 0.17f
            drawRoundRect(Color(0xFF2B2E3A), Offset(left + w * 0.02f + i * (cwid + w * 0.012f), ry + r * h * 0.15f), Size(cwid, h * 0.11f), CornerRadius(5f))
        }
        // side bar
        val ic = Color(0xFF8E94A3)
        fun icons(x: Float) { for (i in 0..4) drawCircle(if (i == 1) Color.White else ic, w * 0.008f, Offset(x, h * 0.32f + i * h * 0.09f)) }
        when (bar) {
            "slim" -> { drawRect(Brush.horizontalGradient(listOf(Color(0xEB08090C), Color.Transparent)), size = Size(w * 0.07f, h)); icons(w * 0.035f) }
            "orbit" -> {
                drawRoundRect(Color(0xF0151A2B), Offset(w * 0.012f, h * 0.24f), Size(w * 0.05f, h * 0.52f), CornerRadius(w * 0.03f))
                drawRoundRect(accent.copy(alpha = 0.5f), Offset(w * 0.012f, h * 0.24f), Size(w * 0.05f, h * 0.52f), CornerRadius(w * 0.03f), style = Stroke(1f))
                drawCircle(Color(0x33FFFFFF), w * 0.016f, Offset(w * 0.037f, h * 0.41f))
                icons(w * 0.037f)
                drawRect(accent, Offset(w * 0.031f, h * 0.44f), Size(w * 0.012f, 2f))
            }
            "solid" -> { drawRect(Color(0xFF15161C), size = Size(w * 0.07f, h)); drawRect(Color(0xFF23252E), Offset(w * 0.07f, 0f), Size(1f, h)); icons(w * 0.035f) }
            "floating" -> {
                drawRoundRect(Color(0xB30E1016), Offset(w * 0.012f, h * 0.26f), Size(w * 0.045f, h * 0.48f), CornerRadius(w * 0.03f))
                drawRoundRect(Color(0x38FFFFFF), Offset(w * 0.012f, h * 0.26f), Size(w * 0.045f, h * 0.48f), CornerRadius(w * 0.03f), style = Stroke(1f))
                icons(w * 0.0345f)
            }
            else -> Unit
        }
    }
}

/** First start of a new profile: Choose your look, full screen over Home. Back or Done keeps what is picked. */
@Composable
fun TvLookFirstRun(vm: AppViewModel, onDone: () -> Unit) {
    androidx.activity.compose.BackHandler { onDone() }
    Box(
        Modifier.fillMaxSize().background(Bg)
            .background(Brush.radialGradient(listOf(Palette.tint.copy(alpha = 0.7f), Color.Transparent), Offset(700f, 0f), 1100f)),
    ) {
        Box(Modifier.padding(start = 60.dp, top = 36.dp, end = 60.dp).fillMaxSize()) {
            TvLookPane(vm, firstRun = true, onDone = onDone)
        }
    }
}

/** The poster the Glass theme blurs behind every page (set by Home's big card). */
object TvGlass {
    val art = androidx.compose.runtime.mutableStateOf<String?>(null)
    /** The last few blurred backdrops (≈ 80 KB each). */
    val blurred = java.util.concurrent.ConcurrentHashMap<String, androidx.compose.ui.graphics.ImageBitmap>()
    /** Box blur, [passes] times horizontally and vertically (3 passes ≈ gaussian). */
    fun blur(src: android.graphics.Bitmap, radius: Int, passes: Int): android.graphics.Bitmap {
        val w = src.width; val h = src.height
        var a = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        var b = IntArray(w * h)
        fun pass(inp: IntArray, out: IntArray, len: Int, lines: Int, step: Int, lineStep: Int) {
            val div = radius * 2 + 1
            for (l in 0 until lines) {
                var rs = 0; var gs = 0; var bs = 0
                val base = l * lineStep
                for (k in -radius..radius) { val p = inp[base + k.coerceIn(0, len - 1) * step]; rs += (p shr 16) and 255; gs += (p shr 8) and 255; bs += p and 255 }
                for (x in 0 until len) {
                    out[base + x * step] = (0xFF shl 24) or ((rs / div) shl 16) or ((gs / div) shl 8) or (bs / div)
                    val pOut = inp[base + (x - radius).coerceIn(0, len - 1) * step]
                    val pIn = inp[base + (x + radius + 1).coerceIn(0, len - 1) * step]
                    rs += ((pIn shr 16) and 255) - ((pOut shr 16) and 255)
                    gs += ((pIn shr 8) and 255) - ((pOut shr 8) and 255)
                    bs += (pIn and 255) - (pOut and 255)
                }
            }
        }
        repeat(passes) {
            pass(a, b, w, h, 1, w); val t = a; a = b; b = t
            pass(a, b, h, w, w, 1); val t2 = a; a = b; b = t2
        }
        return android.graphics.Bitmap.createBitmap(a, w, h, android.graphics.Bitmap.Config.ARGB_8888)
    }
}

@Composable
fun TvGlassBackdrop() {
    val url = TvGlass.art.value ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // TV 0.9.4: a real soft blur — the art at 192×108, blurred once in the background (three box passes ≈ a gaussian),
    // then drawn smoothly over the screen. The 48×27 picture blown up looked like big pixels.
    val bmp by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(TvGlass.blurred[url], url) {
        TvGlass.blurred[url]?.let { value = it; return@produceState }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val r = coil.request.ImageRequest.Builder(ctx).data(url).size(192, 108).allowHardware(false).build()
                val d = (coil.Coil.imageLoader(ctx).execute(r) as? coil.request.SuccessResult)?.drawable ?: return@runCatching null
                val src = android.graphics.Bitmap.createScaledBitmap(
                    (d as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: android.graphics.Bitmap.createBitmap(192, 108, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                        val c = android.graphics.Canvas(b); d.setBounds(0, 0, 192, 108); d.draw(c) }, 192, 108, true)
                TvGlass.blur(src, 7, 3).asImageBitmap()
            }.getOrNull()
        }?.also { if (TvGlass.blurred.size > 6) TvGlass.blurred.clear(); TvGlass.blurred[url] = it } ?: value
    }
    androidx.compose.animation.Crossfade(bmp, animationSpec = androidx.compose.animation.core.tween(600), label = "glass") { b ->
        if (b != null) androidx.compose.foundation.Image(b, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.High, alpha = 0.85f)
    }
}
