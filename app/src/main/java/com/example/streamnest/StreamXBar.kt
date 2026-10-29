package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Xstream's StreamX bottom bar (the default; Settings › Layout › Bottom bar can switch to the capsule): 4 items on a dark
 * base with a glowing curved top edge. It slides away while you scroll down and comes back when you scroll up.
 * Xstream's tabs: 0 Home, 1 Search, 3 Library, 2 My Space.
 */
@Composable
fun StreamXBar(tab: Int, vm: AppViewModel, compact: State<Float>, onTab: (Int) -> Unit, modifier: Modifier = Modifier) {
    var switcher by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    // Hidden by a pure layer translation (draw phase only): nothing is re-laid-out while it moves.
    Box(modifier.fillMaxWidth().graphicsLayer {
        val v = compact.value.coerceIn(0f, 1f)
        translationY = v * 120.dp.toPx(); alpha = 1f - v * 0.6f
    }) {
        Spacer(Modifier.matchParentSize().drawWithCache {
            val glow = Accent
            val glow2 = Palette.accent2
            val yl = 26.dp.toPx(); val yr = 18.dp.toPx(); val peak = (-6).dp.toPx()
            val surface = Path().apply {
                moveTo(0f, yl); quadraticBezierTo(size.width * 0.5f, peak, size.width, yr)
                lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            val base = Brush.verticalGradient(listOf(Color(0xFA120E18), Color(0xFF07080C)))
            val edge = Path().apply { moveTo(0f, yl); quadraticBezierTo(size.width * 0.5f, peak, size.width, yr) }
            val light = Brush.horizontalGradient(listOf(glow2, glow, glow.copy(alpha = 0.35f), glow.copy(alpha = 0.08f)))
            val wide = Stroke(width = 14.dp.toPx()); val mid = Stroke(width = 7.dp.toPx()); val thin = Stroke(width = 2.2.dp.toPx())
            onDrawBehind {
                drawPath(surface, base)
                drawPath(edge, light, style = wide, alpha = 0.10f)
                drawPath(edge, light, style = mid, alpha = 0.22f)
                drawPath(edge, light, style = thin)
            }
        })
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 14.dp).height(66.dp),
            horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically,
        ) {
            SxNavItem(tab == 0, t("navHome"), compact, { onTab(0) }) { c -> Icon(Icons.Default.Home, t("navHome"), Modifier.size(26.dp), tint = c) }
            SxNavItem(tab == 1, t("navSearch"), compact, { onTab(1) }) { c -> Icon(Icons.Default.Search, t("navSearch"), Modifier.size(26.dp), tint = c) }
            SxNavItem(tab == 3, t("navLibrary"), compact, { onTab(3) }) { c -> Icon(Icons.Default.VideoLibrary, t("navLibrary"), Modifier.size(26.dp), tint = c) }
            Box {
                val profiles by vm.profiles.collectAsStateWithLifecycle()
                var hover by remember { mutableIntStateOf(-1) }
                var adding by remember { mutableStateOf(false) }
                val density = LocalDensity.current
                val itemPx = with(density) { 72.dp.toPx() }
                val gapPx = with(density) { 34.dp.toPx() }
                val sel = tab == 2
                val accent = BtnColor
                val glow = remember(accent) { Brush.radialGradient(listOf(accent.copy(alpha = 0.42f), accent.copy(alpha = 0.10f), Color.Transparent)) }
                Box(
                    Modifier.width(96.dp).fillMaxHeight().clip(RoundedCornerShape(22.dp))
                        .pointerInput(profiles.size) {
                            // Tap = My Space. Hold = the profiles capsule opens above; slide up and let go on a profile to switch.
                            awaitEachGesture {
                                awaitFirstDown()
                                val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    while (true) { val e = awaitPointerEvent(); if (e.changes.all { !it.pressed }) return@withTimeoutOrNull false }
                                    @Suppress("UNREACHABLE_CODE") false
                                } == null
                                if (!held) { onTab(2); return@awaitEachGesture }
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress); switcher = true; hover = -1
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.firstOrNull() ?: break
                                    if (!c.pressed) break
                                    c.consume()
                                    val up = -(c.position.y) - gapPx
                                    val total = profiles.size + 1
                                    val fromBottom = if (up < 0f) -1 else (up / itemPx).toInt()
                                    val idx = if (fromBottom in 0 until total) { val top = total - 1 - fromBottom; if (top == 0) profiles.size else top - 1 } else -1
                                    if (idx != hover) { hover = idx; if (idx >= 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                }
                                val wantsAdd = hover == profiles.size
                                val pick = profiles.getOrNull(hover)
                                hover = -1
                                if (wantsAdd) { switcher = false; adding = true }
                                if (pick != null) {
                                    switcher = false
                                    if (pick.id == vm.current.value?.id) onTab(2) else ProfileLock.ask(pick) { vm.selectProfile(pick); onTab(2) }
                                }
                            }
                        }
                        .then(if (sel) Modifier.background(glow) else Modifier),
                    Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(vm, 30.dp, if (sel) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                        if (sel) Text(t("navMySpace"), Modifier.graphicsLayer { alpha = 1f - compact.value; compositingStrategy = CompositingStrategy.ModulateAlpha },
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                    }
                }
                if (switcher) SxProfileSwitcher(vm, hover, onDismiss = { switcher = false }, onPicked = { switcher = false; onTab(2) }, onAdd = { switcher = false; adding = true })
                if (adding) ProfileEditor(vm, null) { adding = false }
            }
        }
    }
}

@Composable
private fun SxNavItem(selected: Boolean, label: String, compact: State<Float>, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val c = if (selected) Color.White else Color(0xFF8A94A8)
    val click by rememberUpdatedState(onClick)
    val accent = BtnColor
    val selectedGlow = remember(accent) { Brush.radialGradient(listOf(accent.copy(alpha = 0.42f), accent.copy(alpha = 0.10f), Color.Transparent)) }
    Box(
        Modifier.width(96.dp).fillMaxHeight().clip(RoundedCornerShape(22.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { click() }) }
            .then(if (selected) Modifier.background(selectedGlow) else Modifier),
        Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon(c)
            if (selected) Text(label, Modifier.graphicsLayer { alpha = 1f - compact.value; compositingStrategy = CompositingStrategy.ModulateAlpha },
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c, maxLines = 1)
        }
    }
}

/** Quick profile switcher above the My Space button (slide a finger to pick, or tap). */
@Composable
private fun SxProfileSwitcher(vm: AppViewModel, hover: Int, onDismiss: () -> Unit, onPicked: () -> Unit, onAdd: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val above = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2).coerceIn(16, maxOf(16, windowSize.width - popupContentSize.width - 16))
                val y = (anchorBounds.top - popupContentSize.height - 8).coerceAtLeast(0)
                return IntOffset(x, y)
            }
        }
    }
    Popup(popupPositionProvider = above, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val shape = RoundedCornerShape(40.dp)
        val pop = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(Unit) { pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 420f)) }
        Column(
            Modifier.graphicsLayer {
                val v = pop.value
                alpha = v.coerceIn(0f, 1f); scaleX = 0.88f + 0.12f * v; scaleY = scaleX
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            }.shadow(18.dp, shape).clip(shape).background(Color(0xF21A1820)).border(1.dp, Color(0x26FFFFFF), shape)
                .padding(horizontal = 8.dp, vertical = 13.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            run {
                val hot = hover == profiles.size
                val grow by androidx.compose.animation.core.animateFloatAsState(if (hot) 1.18f else 1f, label = "hovAdd")
                Column(
                    Modifier.width(66.dp).height(72.dp).graphicsLayer { scaleX = grow; scaleY = grow }
                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onAdd),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0x22FFFFFF)).border(2.dp, if (hot) Color.White else Color(0x44FFFFFF), CircleShape), Alignment.Center) {
                        Icon(Icons.Default.Add, "Add profile", Modifier.size(24.dp), tint = Color.White)
                    }
                    Text("Add", Modifier.padding(top = 2.dp), fontSize = 11.sp, color = Color(0xFFB6BDCC), maxLines = 1)
                }
            }
            profiles.forEachIndexed { i, pr ->
                val on = pr.id == current?.id
                val hot = i == hover
                val grow by androidx.compose.animation.core.animateFloatAsState(if (hot) 1.18f else 1f, label = "hov")
                Column(
                    Modifier.width(66.dp).height(72.dp).graphicsLayer { scaleX = grow; scaleY = grow }
                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { if (!on) ProfileLock.ask(pr) { vm.selectProfile(pr); onPicked() } else onPicked() },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    ProfileAvatar(vm, pr, 44.dp, if (hot) Modifier.border(2.dp, Color.White, CircleShape) else if (on) Modifier.border(2.dp, Accent, CircleShape) else Modifier, animate = false)
                    Text(pr.name, Modifier.padding(top = 4.dp), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) Color.White else Color(0xFFB6BDCC))
                }
            }
        }
    }
}

