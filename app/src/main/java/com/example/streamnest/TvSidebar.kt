package com.example.streamnest

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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

/** Main sections of Xstream TV (picked in the left rail). */
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
 * Hotstar-style navigation: the Xstream logo top-left, a narrow strip of icons that always stays on the left, and the
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

    // Dark gradient behind the open menu (alpha only: no layout work while it animates).
    if (p > 0.01f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = p }.background(RailScrim))

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
        // Logo, top-left corner.
        Box(Modifier.padding(start = 17.dp, top = 20.dp)) { AppLogo(vm, 30.dp) }
        Row(
            Modifier.padding(start = 56.dp, top = 24.dp).graphicsLayer { alpha = p; translationX = (1f - p) * -12.dp.toPx() },
            verticalAlignment = Alignment.CenterVertically,
        ) { Wordmark(t("appName"), 20.sp) }

        // Sections, centred vertically.
        Column(Modifier.align(Alignment.CenterStart).padding(start = 10.dp)) {
            items.forEachIndexed { i, it ->
                RailRow(
                    it.icon, it.label, selected = it.tab == current, open = open, progress = { p },
                    requester = requesters[i], onFocused = { focusedIdx[0] = i },
                ) { onSelect(it.tab) }
                Spacer(Modifier.height(6.dp))
            }
        }

        // Profile, bottom-left.
        Box(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 22.dp)) {
            RailProfile(vm, profile, open, { p }, requesters[items.size], onFocused = { focusedIdx[0] = items.size }, onClick = onProfile)
        }
    }
}

@Composable
private fun RailRow(
    icon: ImageVector, label: String, selected: Boolean, open: Boolean, progress: () -> Float,
    requester: FocusRequester, onFocused: () -> Unit, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val fg = when {
        focused -> Color.White
        selected -> Color.White
        else -> RailIdle
    }
    Row(
        Modifier.width(220.dp).height(42.dp)
            .focusRequester(requester)
            .focusProperties { canFocus = open }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .clip(CircleShape)
            .background(if (focused) Color(0x26FFFFFF) else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), Alignment.Center) {
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
        Modifier.width(220.dp).height(44.dp)
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
