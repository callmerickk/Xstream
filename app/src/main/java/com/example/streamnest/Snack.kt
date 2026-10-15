package com.example.streamnest

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** One premium in-app message (replaces Android's grey Toast). Shown by [SnackHost] at the top of the app. */
data class SnackMsg(val text: String, val action: String? = null, val millis: Long = 2600, val onAction: (() -> Unit)? = null, val id: Long = System.nanoTime())

object Snack {
    val current = MutableStateFlow<SnackMsg?>(null)
    fun show(text: String, action: String? = null, millis: Long = 2600, onAction: (() -> Unit)? = null) {
        current.value = SnackMsg(Public.say(text), action, millis, onAction)
    }
    fun hide() { current.value = null }
}

private val noRippleSnack = MutableInteractionSource()

/** Glass capsule that springs in from the top and fades away by itself. */
@Composable
fun SnackHost(modifier: Modifier = Modifier) {
    val msg by Snack.current.collectAsState()
    var shown by remember { mutableStateOf<SnackMsg?>(null) }
    LaunchedEffect(msg?.id) {
        val m = msg ?: return@LaunchedEffect
        shown = m
        delay(m.millis)
        if (Snack.current.value?.id == m.id) Snack.current.value = null
    }
    Box(modifier.fillMaxWidth().statusBarsPadding().padding(top = 14.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            msg != null,
            enter = slideInVertically(spring(dampingRatio = 0.72f, stiffness = 420f)) { -it } + fadeIn(tween(180)) + scaleIn(initialScale = 0.92f),
            exit = slideOutVertically(tween(220)) { -it / 2 } + fadeOut(tween(200)) + scaleOut(targetScale = 0.96f),
        ) {
            val m = shown ?: return@AnimatedVisibility
            val shape = RoundedCornerShape(26.dp)
            Row(
                Modifier.widthIn(max = 520.dp)
                    .shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(Color(0xF2202128), Color(0xF2141519))))
                    .border(1.dp, Brush.linearGradient(listOf(Color(0x40FFFFFF), Color(0x0DFFFFFF), Color(0x26FFFFFF))), shape)
                    .padding(start = 16.dp, end = if (m.action != null) 6.dp else 18.dp, top = 6.dp, bottom = 6.dp)
                    .heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Brush.radialGradient(listOf(BrandRed, BrandRed.copy(alpha = 0.4f)))))
                Spacer(Modifier.width(10.dp))
                Text(m.text, Modifier.weight(1f, fill = false).padding(vertical = 6.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = Color(0xFFEDEFF5), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (m.action != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        m.action,
                        Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x26FFFFFF))
                            .clickable(interactionSource = noRippleSnack, indication = null) { Snack.hide(); m.onAction?.invoke() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    )
                }
            }
        }
    }
}
