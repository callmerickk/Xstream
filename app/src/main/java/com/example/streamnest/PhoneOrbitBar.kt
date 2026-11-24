package com.example.streamnest

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 0.9.75 (from Xstream): the Orbit bottom bar ("Orbit"): a floating glass capsule. The selected tab grows into a lit pill with its
 * name (indigo → red light behind it); the others are icons only. Springy, with a soft halo under the capsule.
 * Settings › Layout › Bottom bar switches between StreamX bar, Capsule and Orbit.
 */
@Composable
fun PhoneOrbitBar(tab: Int, vm: AppViewModel, compact: State<Float>, onTab: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val items = listOf(
        Triple(0, Icons.Default.Home, t("navHome")),
        Triple(1, Icons.Default.Search, t("navSearch")),
        Triple(2, Icons.Default.VideoLibrary, t("navLibrary")),
        Triple(3, Icons.Default.Person, "Me"),
    )
    val shape = RoundedCornerShape(50)
    Box(
        modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 14.dp).graphicsLayer {
            val v = compact.value.coerceIn(0f, 1f)
            translationY = v * 110.dp.toPx(); alpha = 1f - v * 0.7f
        },
        Alignment.Center,
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(70.dp)
                .drawBehind {
                    drawOval(Brush.radialGradient(listOf(Color(0x332A3570), Color.Transparent), center = Offset(size.width / 2, size.height),
                        radius = size.width * 0.6f), topLeft = Offset(-size.width * 0.1f, size.height * 0.2f),
                        size = androidx.compose.ui.geometry.Size(size.width * 1.2f, size.height * 1.4f))
                }
                .shadow(24.dp, shape).clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xF0151A2B), Color(0xF50F1014))))
                .border(1.dp, Brush.horizontalGradient(listOf(Color(0x80E50914), Color(0x14FFFFFF), Color(0x14FFFFFF), Color(0x80E50914))), shape)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { (idx, icon, label) ->
                if (idx == 3) PxMeItem(tab == 3, vm, onTab) else
                PxOrbitItem(tab == idx, icon, label) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onTab(idx)
                }
            }
        }
    }
}

@Composable
private fun RowScope.PxOrbitItem(selected: Boolean, icon: ImageVector?, label: String, gesture: Modifier? = null,
    iconContent: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val springy = spring<Float>(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
    val grow by animateFloatAsState(if (selected) 1f else 0f, springy, label = "orbitGrow")
    val w by animateDpAsState(if (selected) 132.dp else 56.dp, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "orbitW")
    val tint by animateColorAsState(if (selected) Color.White else Color(0xFFB4B9CA), label = "orbitTint")
    // Selected tab: clear glass (a soft white fill, a fine light border, a highlight along the top) and a small red
    // glowing line under it — no colour fill.
    val pill = RoundedCornerShape(50)
    Row(
        Modifier.width(w).height(52.dp)
            .drawBehind {
                if (grow > 0.02f) {
                    val lw = size.width * 0.34f
                    val y = size.height - 6.dp.toPx()
                    drawRoundRect(Color(0xFFE50914).copy(alpha = 0.35f * grow), topLeft = Offset((size.width - lw) / 2 - 3.dp.toPx(), y - 3.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(lw + 6.dp.toPx(), 9.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                }
            }
            .clip(pill)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f * grow), Color.White.copy(alpha = 0.07f * grow))))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.38f * grow), Color.White.copy(alpha = 0.12f * grow))), pill)
            .drawBehind {
                if (grow > 0.02f) {
                    val lw = size.width * 0.34f
                    drawRoundRect(Color(0xFFE50914).copy(alpha = grow), topLeft = Offset((size.width - lw) / 2, size.height - 6.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(lw, 3.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                }
            }
            .then(gesture ?: Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick))
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconContent != null) Box(Modifier.graphicsLayer { val s = 1f + 0.12f * grow; scaleX = s; scaleY = s }) { iconContent() }
        else if (icon != null) Icon(icon, label, Modifier.size(24.dp).graphicsLayer { val s = 1f + 0.12f * grow; scaleX = s; scaleY = s }, tint = tint)
        if (grow > 0.35f) Text(label, Modifier.padding(start = 8.dp).graphicsLayer { alpha = ((grow - 0.35f) / 0.65f).coerceIn(0f, 1f) },
            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
    }
}

/**
 * "Me": your profile picture (a GIF plays) instead of a plain icon. Tap = Me; hold = the profiles open above it —
 * slide up and let go on one to switch (like StreamX).
 */
@Composable
private fun RowScope.PxMeItem(selected: Boolean, vm: AppViewModel, onTab: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    var switcher by remember { mutableStateOf(false) }
    var hover by remember { mutableIntStateOf(-1) }
    var adding by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val itemPx = with(density) { 72.dp.toPx() }
    val gapPx = with(density) { 34.dp.toPx() }
    val gesture = Modifier.pointerInput(profiles.size) {
        awaitEachGesture {
            awaitFirstDown()
            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) { val e = awaitPointerEvent(); if (e.changes.all { !it.pressed }) return@withTimeoutOrNull false }
                @Suppress("UNREACHABLE_CODE") false
            } == null
            if (!held) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onTab(3); return@awaitEachGesture }
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
                if (pick.id == vm.current.value?.id) onTab(3) else ProfileLock.ask(pick) { vm.selectProfile(pick); onTab(3) }
            }
        }
    }
    val row = this
    Box {
        row.PxOrbitItem(selected, null, "Me", gesture = gesture, iconContent = {
            ProfileAvatar(vm, current, 26.dp, if (selected) Modifier.border(1.5.dp, Color.White, CircleShape) else Modifier, animate = true)
        }) { onTab(3) }
        if (switcher) SxProfileSwitcher(vm, hover, onDismiss = { switcher = false }, onPicked = { switcher = false; onTab(3) }, onAdd = { switcher = false; adding = true })
        if (adding) ProfileEditor(vm, null) { adding = false }
    }
}
