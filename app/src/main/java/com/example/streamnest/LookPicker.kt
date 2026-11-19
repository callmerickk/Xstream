package com.example.streamnest

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Settings › Layout: Home style, Bottom bar and Theme are each picked on their own (any mix works), from cards that show a
 * small live preview drawn with the colours of the theme you picked. "Quick looks" set all three in one tap.
 */

/** The colours a preview is drawn with. */
internal data class LookColors(val bg: Color, val tint: Color, val accent: Color, val accent2: Color)

internal fun lookOfTheme(th: ThemeOpt) = LookColors(parseColor(th.bg) ?: Color(0xFF07080C), themeTint(th), Color(th.c1), Color(th.c2))
internal fun lookOfCustom(t: CustomTheme) = LookColors(parseColor(t.background) ?: Color(0xFF07080C), parseColor(t.tint) ?: Color(0xFF16204A),
    parseColor(t.accent) ?: Color(0xFFE50914), parseColor(t.accent2) ?: Color(0xFFFF6A5C))

/** The theme in use now ("Midnight" while the Live style has not had a theme picked). */
internal fun currentThemeName(texts: Map<String, String>): String =
    if (homeStyleOf(texts) == "live" && texts["themePicked"] != "1") "Midnight" else texts["theme"] ?: "Elegant"

internal fun currentLook(texts: Map<String, String>): LookColors {
    val name = currentThemeName(texts)
    Themes.firstOrNull { it.name == name }?.let { return lookOfTheme(it) }
    return LookColors(Palette.bg, Palette.tint, Palette.accent, Palette.accent2)
}

private val PosterTones = listOf(Color(0xFF7A4B2A), Color(0xFF2B4C7E), Color(0xFF6D2A3A), Color(0xFF3C6B4A), Color(0xFF5B3D7A), Color(0xFF8A6D2B))

private fun DrawScope.posterRows(top: Float, n: Int = 4) {
    val gap = size.width * 0.04f
    val w = (size.width - gap * (n + 1)) / n
    val h = w * 0.78f
    for (r in 0 until 2) for (i in 0 until n) {
        drawRoundRect(PosterTones[(i + r * 3) % PosterTones.size], Offset(gap + i * (w + gap), top + r * (h + gap * 1.6f)), Size(w, h), CornerRadius(w * 0.12f))
    }
}

private fun DrawScope.miniBar(bar: String, c: LookColors) {
    val w = size.width; val h = size.height
    when (bar) {
        "orbit" -> {
            val l = w * 0.14f; val bw = w * 0.72f; val bh = h * 0.085f; val y = h - bh - h * 0.04f
            drawRoundRect(c.accent.copy(alpha = 0.35f), Offset(l + bw * 0.2f, y + bh * 0.7f), Size(bw * 0.6f, bh * 0.45f), CornerRadius(bh))
            drawRoundRect(Color(0x14FFFFFF), Offset(l, y), Size(bw, bh), CornerRadius(bh / 2))
            drawRoundRect(Color(0x40FFFFFF), Offset(l, y), Size(bw, bh), CornerRadius(bh / 2), style = Stroke(1.2f))
        }
        "streamx" -> {
            val yl = h * 0.9f; val peak = h * 0.855f; val yr = h * 0.885f
            val surface = Path().apply { moveTo(0f, yl); quadraticBezierTo(w / 2, peak, w, yr); lineTo(w, h); lineTo(0f, h); close() }
            drawPath(surface, Brush.verticalGradient(listOf(Color(0xFA120E18), Color(0xFF07080C)), startY = peak, endY = h))
            val edge = Path().apply { moveTo(0f, yl); quadraticBezierTo(w / 2, peak, w, yr) }
            val light = Brush.horizontalGradient(listOf(c.accent2, c.accent, c.accent.copy(alpha = 0.3f)))
            drawPath(edge, light, style = Stroke(6f), alpha = 0.25f)
            drawPath(edge, light, style = Stroke(1.8f))
        }
        else -> {
            // Capsule (Classic): a dark pill with round buttons, red edge.
            val l = w * 0.12f; val bw = w * 0.76f; val bh = h * 0.075f; val y = h - bh - h * 0.035f
            drawRoundRect(Color(0xE61A0F13), Offset(l, y), Size(bw, bh), CornerRadius(bh / 2))
            drawRoundRect(c.accent.copy(alpha = 0.6f), Offset(l, y), Size(bw, bh), CornerRadius(bh / 2), style = Stroke(1.5f))
            for (i in 0..3) drawCircle(c.accent, bh * 0.32f, Offset(l + bw * (0.14f + i * 0.24f), y + bh / 2), style = Stroke(1.6f), alpha = if (i == 0) 1f else 0.6f)
        }
    }
}

/**
 * A tiny phone screen drawn like the real Home styles (0.8.7, from the 19 Nov screenshots):
 * Live — a wide card with a red rim, a curved bottom and a red glow under it, the title centred, Play + two round buttons;
 * Stack — a tall card on the left with the next cards peeking behind it on the right, "picked for you" chip, Watch Now + ＋;
 * Classic — a centre card with a white glowing rim and cards fanned on both sides, Watch Now + ＋.
 * Then "Pick up where you left off" and the bar. [animate] slides the cards and rows as on the real Home.
 */
@Composable
internal fun MiniHome(style: String, bar: String, c: LookColors, modifier: Modifier = Modifier, animate: Boolean = false) {
    val t = if (animate) {
        val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "mini")
        inf.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(4200, easing = androidx.compose.animation.core.LinearEasing)), label = "t").value
    } else 0f
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(c.bg)) {
        val w = size.width; val h = size.height
        drawRect(Brush.radialGradient(listOf(c.tint, Color.Transparent), center = Offset(w / 2, 0f), radius = w * 0.9f))
        // A card change every ~4 s: the cards slide over (0 → 1 during the last fifth of the cycle).
        val slide = ((t - 0.8f) / 0.2f).coerceIn(0f, 1f)
        val ease = slide * slide * (3 - 2 * slide)
        val tones = listOf(Color(0xFFC46B2C), Color(0xFF2B4C7E), Color(0xFF6D2A3A), Color(0xFF3C6B4A))
        val k = 0
        fun art(i: Int) = Brush.linearGradient(listOf(tones[i % tones.size], Color(0xFF14161E)), Offset(0f, 0f), Offset(w, h * 0.5f))
        val top = h * 0.07f
        var below = 0f
        when (style) {
            "live" -> {
                val cw = w * 0.84f; val ch = h * 0.3f; val y = top
                for (d in -1..1) {
                    val x = (w - cw) / 2 + (d - ease) * (cw + w * 0.04f)
                    val dip = ch * 0.1f
                    val card = Path().apply {
                        moveTo(x, y + 12f); quadraticBezierTo(x, y, x + 12f, y); lineTo(x + cw - 12f, y); quadraticBezierTo(x + cw, y, x + cw, y + 12f)
                        lineTo(x + cw, y + ch - 6f); quadraticBezierTo(x + cw / 2, y + ch - dip * 2, x, y + ch - 6f); close()
                    }
                    if (d == 0) {
                        val glow = Path().apply { moveTo(x + cw * 0.1f, y + ch + 4f); quadraticBezierTo(x + cw / 2, y + ch - dip * 1.2f, x + cw * 0.9f, y + ch + 4f) }
                        drawPath(glow, c.accent, style = Stroke(14f), alpha = 0.3f); drawPath(glow, c.accent, style = Stroke(4f), alpha = 0.7f)
                    }
                    drawPath(card, art(k + d + 1)); drawPath(card, c.accent, style = Stroke(2f), alpha = 0.9f)
                }
                val ty = y + ch + h * 0.05f
                drawRoundRect(Color.White, Offset(w * 0.22f, ty), Size(w * 0.56f, h * 0.03f), CornerRadius(6f))
                drawRoundRect(Color(0x99FFFFFF), Offset(w * 0.32f, ty + h * 0.045f), Size(w * 0.36f, h * 0.018f), CornerRadius(4f))
                val by = ty + h * 0.09f; val bh = h * 0.055f
                drawRoundRect(Color(0xFFE6E7EB), Offset(w * 0.08f, by), Size(w * 0.5f, bh), CornerRadius(bh / 2))
                drawCircle(Color(0x33FFFFFF), bh / 2, Offset(w * 0.7f, by + bh / 2)); drawCircle(Color(0x33FFFFFF), bh / 2, Offset(w * 0.86f, by + bh / 2))
                below = by + bh + h * 0.04f
            }
            "streamx" -> {
                val cw = w * 0.74f; val ch = h * 0.46f; val x = w * 0.05f; val y = top
                for (b in 2 downTo 1) drawRoundRect(art(k + b + (if (ease > 0.5f) 1 else 0)), Offset(x + cw - cw * 0.25f + b * w * 0.06f, y + b * h * 0.012f),
                    Size(cw * 0.3f, ch - b * h * 0.024f), CornerRadius(18f), alpha = 0.9f - b * 0.2f)
                val fx = x - ease * w * 0.9f
                drawRoundRect(art(k), Offset(fx, y), Size(cw, ch), CornerRadius(20f))
                if (ease > 0f) drawRoundRect(art(k + 1), Offset(fx + w * 0.9f, y), Size(cw, ch), CornerRadius(20f))
                drawRoundRect(Color(0x66000000), Offset(x + cw * 0.06f, y + ch * 0.05f), Size(cw * 0.55f, h * 0.035f), CornerRadius(20f))
                drawRoundRect(c.accent.copy(alpha = 0.8f), Offset(x + cw * 0.06f, y + ch * 0.05f), Size(cw * 0.55f, h * 0.035f), CornerRadius(20f), style = Stroke(1.5f))
                val by = y + ch - h * 0.075f; val bh = h * 0.05f
                drawRoundRect(Color(0x66FFFFFF), Offset(x + cw * 0.06f, by), Size(cw * 0.66f, bh), CornerRadius(bh / 2))
                drawRoundRect(Color(0x66FFFFFF), Offset(x + cw * 0.76f, by), Size(cw * 0.18f, bh), CornerRadius(bh / 2))
                below = y + ch + h * 0.04f
            }
            "rows", "none" -> below = h * 0.1f
            else -> {
                val cw = w * 0.72f; val ch = h * 0.47f; val y = top
                drawRoundRect(art(k + 2), Offset(-cw * 0.82f + w * 0.08f, y + h * 0.03f), Size(cw, ch - h * 0.06f), CornerRadius(18f), alpha = 0.6f)
                drawRoundRect(art(k + 3), Offset(w - w * 0.08f, y + h * 0.03f), Size(cw, ch - h * 0.06f), CornerRadius(18f), alpha = 0.6f)
                val x = (w - cw) / 2 - ease * w
                drawRoundRect(art(k), Offset(x, y), Size(cw, ch), CornerRadius(20f))
                drawRoundRect(Color.White, Offset(x, y), Size(cw, ch), CornerRadius(20f), style = Stroke(3f))
                drawRoundRect(c.accent.copy(alpha = 0.35f), Offset(x - 3f, y - 3f), Size(cw + 6f, ch + 6f), CornerRadius(22f), style = Stroke(5f))
                if (ease > 0f) drawRoundRect(art(k + 1), Offset(x + w, y), Size(cw, ch), CornerRadius(20f))
                val by = y + ch - h * 0.075f; val bh = h * 0.05f
                drawRoundRect(Color(0x66FFFFFF), Offset(x + cw * 0.07f, by), Size(cw * 0.64f, bh), CornerRadius(bh / 2))
                drawRoundRect(Color(0x66FFFFFF), Offset(x + cw * 0.75f, by), Size(cw * 0.18f, bh), CornerRadius(bh / 2))
                below = y + ch + h * 0.04f
            }
        }
        // "Pick up where you left off": a heading and wide cards that drift along.
        if (below < h * 0.8f) {
            if (style == "live") drawRect(c.accent, Offset(w * 0.05f, below), Size(3f, h * 0.022f))
            drawRoundRect(Color(0xCCFFFFFF), Offset(w * 0.08f, below), Size(w * 0.5f, h * 0.022f), CornerRadius(4f))
            val ry = below + h * 0.04f; val rw = w * 0.56f; val rh = rw * 0.56f
            val drift = t * w * 0.25f
            for (i in 0..2) {
                val rx = w * 0.05f + i * (rw + w * 0.04f) - drift
                drawRoundRect(Brush.linearGradient(listOf(tones[(i + 1) % 4], Color(0xFF101218)), Offset(rx, ry), Offset(rx + rw, ry + rh)),
                    Offset(rx, ry), Size(rw, rh), CornerRadius(10f))
                drawRect(c.accent, Offset(rx + 4f, ry + rh - 3f), Size(rw * 0.45f, 3f))
            }
        }
        miniBar(bar, c)
    }
}

/** A bottom sheet over the page (full width, rounded top). */
@Composable
private fun LookSheet(title: String, sub: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { onDismiss() }, Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Color(0xFF16171D))
                    .border(BorderStroke(1.dp, Color(0x22FFFFFF)), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { }
                    .navigationBarsPadding().padding(horizontal = 18.dp).padding(top = 10.dp, bottom = 18.dp)
                    .heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(42.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3D49)))
                Spacer(Modifier.height(14.dp))
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(sub, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
                content()
            }
        }
    }
}

@Composable
private fun PickCard(selected: Boolean, modifier: Modifier = Modifier, onLong: (() -> Unit)? = null, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    @OptIn(ExperimentalFoundationApi::class)
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(if (selected) Color(0xFF24262E) else Color(0xFF1D1F26))
            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else Color(0xFF2C2F3A), RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, content = content,
    )
}

@Composable
private fun Tick(modifier: Modifier) =
    Box(modifier.size(22.dp).clip(CircleShape).background(Color(0xFFE50914)), Alignment.Center) { Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = Color.White) }

/** The Layout page's "Look" card: three rows with their own small previews, then Quick looks. */
@Composable
internal fun LookSection(vm: AppViewModel, onEditTheme: (CustomTheme?) -> Unit) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<String?>(null) }
    val style = homeStyleOf(texts); val bar = barKindOf(texts); val look = currentLook(texts)
    val styleName = mapOf("live" to "Live", "streamx" to "Stack", "classic" to "Classic")
    val barName = mapOf("orbit" to "Orbit", "streamx" to "Horizon", "capsule" to "Capsule")
    @Composable fun Row3(t: String, s: String, onClick: () -> Unit, thumb: @Composable () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp, 64.dp).clip(RoundedCornerShape(9.dp)).border(1.dp, Color(0xFF2C2F3A), RoundedCornerShape(9.dp))) { thumb() }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(t, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(s, fontSize = 13.sp, color = Muted)
            }
            Text("›", fontSize = 22.sp, color = Muted)
        }
    }
    SettingsCard {
        Row3("Home style", styleName[style] ?: "Live", { sheet = "style" }) { MiniHome(style, bar, look, Modifier.fillMaxSize()) }
        RowDivider(20.dp)
        Row3("Bottom bar", barName[bar] ?: "Orbit", { sheet = "bar" }) { MiniHome("none", bar, look, Modifier.fillMaxSize()) }
        RowDivider(20.dp)
        val tn = currentThemeName(texts).removePrefix("custom:")
        Row3("Theme", tn + (Themes.firstOrNull { it.name == tn }?.note?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), { sheet = "theme" }) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(look.tint, look.bg))), Alignment.BottomCenter) {
                Box(Modifier.padding(8.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(look.accent))
            }
        }
    }
    Text("QUICK LOOKS", Modifier.padding(start = 24.dp, top = 18.dp, bottom = 8.dp), fontSize = 13.sp, color = Muted, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(Triple("All Live", "live", "orbit") to "Midnight", Triple("All Stack", "streamx", "streamx") to "Crimson", Triple("All Classic", "classic", "capsule") to "Elegant")
            .forEach { (t, theme) ->
                val th = Themes.first { it.name == theme }
                val on = style == t.second && bar == t.third && currentThemeName(texts) == theme
                PickCard(on, Modifier.weight(1f), onClick = {
                    vm.setText("homeStyle", t.second); vm.setText("navBar", t.third); applyThemeOpt(vm, th); vm.refresh()
                    Snack.show("${t.first} on")
                }) {
                    MiniHome(t.second, t.third, lookOfTheme(th), Modifier.fillMaxWidth().height(96.dp))
                    Text(t.first, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                }
            }
    }
    Text("One tap sets all three together. You can still change any one after.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), fontSize = 12.sp, color = Muted)

    when (sheet) {
        "style" -> LookSheet("Home style", "How the top of Home and the rows look. The colours follow your theme.", { sheet = null }) {
            val opts = listOf(Triple("live", "Live", "Curved card with trailer and glow"), Triple("streamx", "Stack", "A big card with the next ones stacked behind"),
                Triple("classic", "Classic", "Full-width poster, Xstream original"))
            opts.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (k, n, d) ->
                        PickCard(style == k, Modifier.weight(1f), onClick = {
                            if (style != k) { vm.setText("homeStyle", k); vm.refresh(); Snack.show("$n Home on — its rows refresh in a moment") }
                            sheet = null
                        }) {
                            Box { MiniHome(k, bar, look, Modifier.fillMaxWidth().height(170.dp), animate = true); if (style == k) Tick(Modifier.align(Alignment.TopEnd).padding(6.dp)) }
                            Text(n, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                            Text(d, fontSize = 11.sp, color = Muted, textAlign = TextAlign.Center, lineHeight = 14.sp)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        "bar" -> LookSheet("Bottom bar", "Works with every Home style.", { sheet = null }) {
            listOf(Triple("orbit", "Orbit", "Clear pill with the red glow line (Live)"), Triple("streamx", "Horizon", "The glowing curved edge, water rings on Me"),
                Triple("capsule", "Capsule", "The floating capsule (Xstream original)")).forEach { (k, n, d) ->
                PickCard(bar == k, Modifier.fillMaxWidth().padding(bottom = 10.dp), onClick = { vm.setText("navBar", k); sheet = null }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box { MiniHome("rows", k, look, Modifier.size(120.dp, 78.dp)); if (bar == k) Tick(Modifier.align(Alignment.TopEnd).padding(4.dp)) }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(n, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(d, fontSize = 11.5.sp, color = Muted)
                        }
                    }
                }
            }
        }
        "theme" -> LookSheet("Theme", "Background, glow and accent together — on any Home style.", { sheet = null }) {
            val cur = currentThemeName(texts)
            @Composable fun ThemeCard(name: String, note: String, c: LookColors, selected: Boolean, modifier: Modifier, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
                PickCard(selected, modifier, onLong, onClick) {
                    Box {
                        MiniHome(style, bar, c, Modifier.fillMaxWidth().height(96.dp))
                        if (selected) Tick(Modifier.align(Alignment.TopEnd).padding(4.dp))
                    }
                    Text(name, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
                    Text(note.ifBlank { " " }, fontSize = 10.sp, color = Muted, maxLines = 1)
                }
            }
            Themes.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { th ->
                        ThemeCard(th.name, th.note.ifBlank { if (th.enhanced) "glowing" else "" }, lookOfTheme(th), cur == th.name, Modifier.weight(1f)) { applyThemeOpt(vm, th) }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            val mine = remember(texts["customThemes"]) { CustomThemes.list(vm) }
            Text("YOUR THEMES", Modifier.padding(top = 8.dp, bottom = 8.dp), fontSize = 11.sp, color = Muted, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            var menuFor by remember { mutableStateOf<CustomTheme?>(null) }
            (mine.map<CustomTheme, CustomTheme?> { it } + null).chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { t ->
                        if (t == null) PickCard(false, Modifier.weight(1f), onClick = { sheet = null; onEditTheme(null) }) {
                            Box(Modifier.fillMaxWidth().height(96.dp), Alignment.Center) { Icon(Icons.Default.Add, null, tint = Muted) }
                            Text("Make one", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                            Text(" ", fontSize = 10.sp)
                        } else Box(Modifier.weight(1f)) {
                            ThemeCard(t.name, "hold to edit", lookOfCustom(t), cur == "custom:" + t.name, Modifier.fillMaxWidth(), onLong = { menuFor = t }) { CustomThemes.apply(vm, t) }
                            DropdownMenu(menuFor == t, { menuFor = null }) {
                                DropdownMenuItem({ Text("Use") }, { menuFor = null; CustomThemes.apply(vm, t) })
                                DropdownMenuItem({ Text("Edit") }, { menuFor = null; sheet = null; onEditTheme(t) })
                                DropdownMenuItem({ Text("Delete", color = Color(0xFFFF8A80)) }, { menuFor = null; CustomThemes.delete(vm, t.name) })
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
