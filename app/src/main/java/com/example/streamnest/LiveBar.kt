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

/**
 * Xstream Live's own bottom bar ("Orbit"): a floating glass capsule. The selected tab grows into a lit pill with its
 * name (indigo → red light behind it); the others are icons only. Springy, with a soft halo under the capsule.
 * Settings › Layout › Bottom bar switches between StreamX bar, Capsule and Orbit.
 */
@Composable
fun LiveBar(tab: Int, vm: AppViewModel, compact: State<Float>, onTab: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val items = listOf(
        Triple(0, Icons.Default.Home, t("navHome")),
        Triple(1, Icons.Default.Search, t("navSearch")),
        Triple(3, Icons.Default.VideoLibrary, t("navLibrary")),
        Triple(2, Icons.Default.Person, "Me"),
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
                .border(1.dp, Brush.horizontalGradient(listOf(Color(0x4D3A4590), Color(0x18FFFFFF), Color(0x4DE50914))), shape)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { (idx, icon, label) ->
                OrbitItem(tab == idx, icon, label) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onTab(idx)
                }
            }
        }
    }
}

@Composable
private fun RowScope.OrbitItem(selected: Boolean, icon: ImageVector, label: String, onClick: () -> Unit) {
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
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, Modifier.size(24.dp).graphicsLayer { val s = 1f + 0.12f * grow; scaleX = s; scaleY = s }, tint = tint)
        if (grow > 0.35f) Text(label, Modifier.padding(start = 8.dp).graphicsLayer { alpha = ((grow - 0.35f) / 0.65f).coerceIn(0f, 1f) },
            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
    }
}
