package com.example.streamnest

import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Main sections of StreamX TV (picked in the left rail). */
enum class TvTab { Search, Home, Library, MySpace, Settings }

/** Width of the always-visible icon strip; section content starts right of it. */
val TvRailW = 64.dp

/** Left padding of rows inside a section (content already starts after the rail). */
val TvRailEdge = 20.dp

private val RailIdle = Color(0xFF8E94A3)
private val RailScrim = Brush.horizontalGradient(
    0f to Color(0xF2050608), 0.28f to Color(0xD9050608), 0.5f to Color(0x80050608), 0.7f to Color.Transparent,
)

private data class RailItem(val tab: TvTab, val label: String, val icon: ImageVector)

/**
 * Hotstar-style navigation: the StreamX logo top-left, a narrow strip of icons that always stays on the left, and the
 * profile at the bottom. When [open] (LEFT from the first item of a row), the strip grows into a labelled menu over a
 * dark gradient and gets focus; RIGHT closes it ([onClose]), OK picks a section. The collapsed strip is never focusable,
 * so the remote can only reach it through [open].
 */
@Composable
fun TvRail(
    open: Boolean,
    vm: AppViewModel,
    current: TvTab,
    refocus: Int = 0,
    style: String = "slim",
    openStyle: String = "slim",
    onSelect: (TvTab) -> Unit,
    onProfile: () -> Unit,
    onClose: () -> Unit,
) {
    val profile by vm.current.collectAsStateWithLifecycle()
    val close by rememberUpdatedState(onClose)
    val p by animateFloatAsState(if (open) 1f else 0f, tween(200), label = "rail")
    val items = listOf(
        RailItem(TvTab.Search, t("navSearch"), Icons.Default.Search),
        RailItem(TvTab.Home, t("navHome"), Icons.Default.Home),
        RailItem(TvTab.Library, "Library", Icons.Default.VideoLibrary),
        RailItem(TvTab.MySpace, t("navMySpace"), Icons.Default.Person),
        RailItem(TvTab.Settings, "Settings", Icons.Default.Settings),
    )
    val requesters = remember { List(items.size + 1) { FocusRequester() } } // last = profile
    val focusedIdx = remember { intArrayOf(-1) }
    LaunchedEffect(open, refocus) {
        if (!open) return@LaunchedEffect
        withFrameNanos { }
        val i = items.indexOfFirst { it.tab == current }.coerceAtLeast(0)
        // Retry a few frames: the strip's items only become focusable once it is open.
        repeat(6) { if (requesters[i].tryFocus()) return@LaunchedEffect; kotlinx.coroutines.delay(50) }
    }

    // TV 0.9.3: the closed look ([style]) and the open look ([openStyle]) are picked separately (Settings › Layout).
    val accent = Accent
    val capH = (items.size * 48 + 14).dp
    val sameCapsule = style == openStyle && (style == "floating" || style == "orbit")
    // TV 0.9.6: a floating / orbit bar that opens dims the page behind it a little, so it never melts into a bright
    // picture or the Glass background.
    if (p > 0.01f && (openStyle == "floating" || openStyle == "orbit"))
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = p }.background(Brush.horizontalGradient(0f to Color(0x99000000), 0.35f to Color(0x4D000000), 0.6f to Color.Transparent)))
    // Open backgrounds (alpha only while animating).
    if (p > 0.01f && !sameCapsule) when (openStyle) {
        "slim" -> Box(Modifier.fillMaxSize().graphicsLayer { alpha = p }.background(RailScrim))
        "dark" -> Box(Modifier.fillMaxHeight().width(250.dp).graphicsLayer { alpha = p }.background(Color(0xF50A0B0F)))
        "solid" -> Box(Modifier.fillMaxHeight().width(250.dp).graphicsLayer { alpha = p }.background(Color(0xFF15161C))
            .drawBehind { drawRect(Color(0xFF23252E), androidx.compose.ui.geometry.Offset(size.width - 1f, 0f), androidx.compose.ui.geometry.Size(1f, size.height)) })
        "floating", "orbit" -> Box(Modifier.fillMaxHeight().width(250.dp).graphicsLayer { alpha = p }) {
            RailCapsule(openStyle == "orbit", 232.dp, capH, accent, Modifier.align(Alignment.CenterStart), solid = true)
        }
    }
    // Closed strip.
    if (p < 0.99f || sameCapsule) when (style) {
        "solid" -> Box(Modifier.fillMaxHeight().width(TvRailW).graphicsLayer { alpha = 1f - p }.background(Color(0xFF15161C))
            .drawBehind { drawRect(Color(0xFF23252E), androidx.compose.ui.geometry.Offset(size.width - 1f, 0f), androidx.compose.ui.geometry.Size(1f, size.height)) })
        "floating", "orbit" -> Box(Modifier.fillMaxHeight().width(250.dp).graphicsLayer { if (!sameCapsule) alpha = 1f - p }) {
            // One capsule that grows with the names when the open look is the same; else it fades out.
            val w = if (sameCapsule) (if (style == "orbit") 60.dp else 52.dp) + ((232 - (if (style == "orbit") 60 else 52)) * p).dp else (if (style == "orbit") 60.dp else 52.dp)
            RailCapsule(style == "orbit", w, capH, accent, Modifier.align(Alignment.CenterStart), solid = sameCapsule && p > 0.3f)
        }
        else -> Unit
    }

    val shown = { if (style == "hidden") p else 1f }
    Box(
        Modifier.fillMaxHeight().width(260.dp)
            .onPreviewKeyEvent { e ->
                if (!open || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> { close(); true }
                    Key.DirectionLeft -> true
                    // ▲ ▼ move only between the menu's own items (never onto the page behind, e.g. the search keypad).
                    Key.DirectionUp -> { val i = focusedIdx[0]; if (i > 0) requesters[i - 1].tryFocus(); true }
                    Key.DirectionDown -> { val i = focusedIdx[0]; if (i in 0 until items.size) requesters[i + 1].tryFocus(); true }
                    else -> false
                }
            },
    ) {
        // Logo, top-left corner — always there, Hidden side bar too.
        Box(Modifier.padding(start = 17.dp, top = 20.dp)) { AppLogo(vm, 30.dp) }
        Row(
            Modifier.padding(start = 56.dp, top = 24.dp).graphicsLayer { alpha = p; translationX = (1f - p) * -12.dp.toPx() },
            verticalAlignment = Alignment.CenterVertically,
        ) { Wordmark(t("appName"), 20.sp) }

        // Sections, centred vertically (the Hidden side bar shows them only while open).
        Column(Modifier.align(Alignment.CenterStart).padding(start = 10.dp).graphicsLayer { alpha = shown() }) {
            items.forEachIndexed { i, it ->
                RailRow(
                    it.icon, it.label, selected = it.tab == current, open = open, progress = { p }, style = style, openStyle = openStyle,
                    requester = requesters[i], onFocused = { focusedIdx[0] = i },
                ) { onSelect(it.tab) }
                Spacer(Modifier.height(6.dp))
            }
        }

        // Profile, bottom-left.
        Box(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 22.dp).graphicsLayer { alpha = shown() }) {
            RailProfile(vm, profile, open, { p }, requesters[items.size], onFocused = { focusedIdx[0] = items.size }, onClick = onProfile)
        }
    }
}

/**
 * The capsule behind Floating glass (see-through dark glass, a red line on its right edge) and Orbit (Xstream Live's bar
 * standing up: dark navy glass, red at both ends of the border, a soft halo).
 */
@Composable
private fun RailCapsule(orbit: Boolean, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, accent: Color, modifier: Modifier, solid: Boolean = false) {
    val cap = RoundedCornerShape(26.dp)
    Box(
        modifier.padding(start = 6.dp).width(w).height(h)
            .then(if (solid) Modifier.shadow(18.dp, cap, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
            .drawBehind {
                if (orbit) drawOval(Brush.radialGradient(listOf(Color(0x332A3570), Color.Transparent), androidx.compose.ui.geometry.Offset(0f, size.height / 2f), size.height * 0.6f),
                    androidx.compose.ui.geometry.Offset(-size.height * 0.3f, 0f), androidx.compose.ui.geometry.Size(size.height * 0.6f, size.height))
            }
            .clip(cap)
            // TV 0.9.6: open = nearly solid dark glass (it was 72 % see-through and melted into the background).
            .background(if (orbit) Brush.horizontalGradient(listOf(Color(0xF0151A2B), Color(0xF50F1014)))
                else if (solid) Brush.verticalGradient(listOf(Color(0xEB16171F), Color(0xF00C0D12))) else Brush.verticalGradient(listOf(Color(0xB80E1016), Color(0xB80E1016))))
            .border(1.dp, if (orbit) Brush.verticalGradient(listOf(accent.copy(alpha = 0.5f), Color(0x14FFFFFF), Color(0x14FFFFFF), accent.copy(alpha = 0.5f)))
                else if (solid) Brush.verticalGradient(listOf(Color(0x59FFFFFF), Color(0x1FFFFFFF))) else Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x26FFFFFF))), cap)
            .drawBehind {
                if (!orbit) drawRect(Brush.verticalGradient(listOf(Color.Transparent, accent, Color.Transparent)),
                    androidx.compose.ui.geometry.Offset(size.width - 2.dp.toPx(), size.height * 0.2f),
                    androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height * 0.6f))
            },
    )
}

@Composable
private fun RailRow(
    icon: ImageVector, label: String, selected: Boolean, open: Boolean, progress: () -> Float, style: String = "slim", openStyle: String = "slim",
    requester: FocusRequester, onFocused: () -> Unit, onClick: () -> Unit,
) {
    var focused0 by remember { mutableStateOf(false) }
    // 0.9.5 (big screen, phone app): the mouse lights an item like focus; the closed strip only takes clicks on its icons.
    val hoverSrc = remember { MutableInteractionSource() }
    val hovered = if (BuildConfig.IS_TV) false else hoverSrc.collectIsHoveredAsState().value
    val focused = focused0 || hovered
    val fg = when {
        focused -> Color.White
        selected -> Color.White
        else -> RailIdle
    }
    val accent = Accent
    val orbitOpen = openStyle == "orbit"
    val lit = Brush.verticalGradient(listOf(Color(0x29FFFFFF), Color(0x12FFFFFF)))
    Row(
        Modifier.width(if (!BuildConfig.IS_TV && !open) 46.dp else if (openStyle == "floating" || orbitOpen) 206.dp else 220.dp).height(42.dp)
            .focusRequester(requester)
            .focusProperties { canFocus = open }
            .onFocusChanged { focused0 = it.isFocused; if (it.isFocused) onFocused() }
            .clip(CircleShape)
            .then(
                if (!focused) Modifier
                else if (orbitOpen) Modifier.background(lit).border(1.dp, Brush.verticalGradient(listOf(Color(0x61FFFFFF), Color(0x1FFFFFFF))), CircleShape)
                else Modifier.background(Color(0x26FFFFFF)),
            )
            .clickable(interactionSource = hoverSrc, indication = null, onClick = onClick)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).drawBehind {
                val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                val a = 1f - progress()
                // Open Orbit: the red glow line sits under the icon of the item with the selector only.
                if (orbitOpen && focused && progress() > 0.01f) {
                    val lw = 18.dp.toPx(); val y = size.height / 2f + 15.dp.toPx()
                    drawRoundRect(accent.copy(alpha = 0.35f * progress()), androidx.compose.ui.geometry.Offset(c.x - lw / 2f - 3.dp.toPx(), y - 3.dp.toPx()),
                        androidx.compose.ui.geometry.Size(lw + 6.dp.toPx(), 8.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                    drawRoundRect(accent.copy(alpha = progress()), androidx.compose.ui.geometry.Offset(c.x - lw / 2f, y),
                        androidx.compose.ui.geometry.Size(lw, 2.5.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                }
                // Closed strip: the page you are on.
                if (!selected || a <= 0.01f) return@drawBehind
                when (style) {
                    "orbit" -> {
                        drawCircle(lit, 20.dp.toPx(), c, alpha = a)
                        drawCircle(Color(0x47FFFFFF), 20.dp.toPx(), c, alpha = a, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                        val lw = 16.dp.toPx(); val y = c.y + 13.dp.toPx()
                        drawRoundRect(accent.copy(alpha = a), androidx.compose.ui.geometry.Offset(c.x - lw / 2f, y), androidx.compose.ui.geometry.Size(lw, 2.5.dp.toPx()),
                            androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                    }
                    "floating" -> {
                        drawCircle(Color.White.copy(alpha = 0.14f * a), 18.dp.toPx(), c)
                        drawCircle(accent, 18.dp.toPx(), c, alpha = a, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                    }
                    "solid" -> drawRect(accent, androidx.compose.ui.geometry.Offset(-22.dp.toPx(), -6.dp.toPx()),
                        androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height + 12.dp.toPx()), alpha = a)
                    else -> Unit
                }
            },
            Alignment.Center,
        ) {
            Icon(icon, label, Modifier.size(if (selected || focused) 21.dp else 19.dp), tint = fg)
        }
        Spacer(Modifier.width(18.dp))
        Text(
            label,
            Modifier.graphicsLayer { val v = progress(); alpha = v; translationX = (1f - v) * -10.dp.toPx() },
            fontSize = if (focused) 16.sp else 15.sp,
            fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium,
            color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RailProfile(
    vm: AppViewModel, profile: Profile?, open: Boolean, progress: () -> Float,
    requester: FocusRequester, onFocused: () -> Unit, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.width(if (!BuildConfig.IS_TV && !open) 44.dp else 220.dp).height(44.dp)
            .focusRequester(requester)
            .focusProperties { canFocus = open }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .clip(CircleShape)
            .background(if (focused) Color(0x26FFFFFF) else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(start = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(vm, profile, 28.dp, Modifier.border(1.5.dp, if (focused) Color.White else BrandRed, CircleShape), animate = false)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.graphicsLayer { alpha = progress() }) {
            Text(profile?.name ?: "", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Switch profile", fontSize = 10.sp, color = TvSubText)
        }
    }
}
