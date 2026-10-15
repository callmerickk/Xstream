package com.example.streamnest

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/*
 * One premium look for every popup in the app:
 *  - the screen behind dims and (Android 12+) softly blurs
 *  - the card springs in from 92% scale with a fade, and fades/shrinks out
 *  - dark glass card, 24dp corners, thin light rim, soft shadow
 * Bottom sheets slide up with the same spring.
 */

val GlassCard = Color(0xFF17171B)
private val CardShape24 = RoundedCornerShape(24.dp)
private val Rim = Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x08FFFFFF), Color(0x14FFFFFF)))
private val noRipple = MutableInteractionSource()

/** Shared animation + window logic. [content] gets the 0..1 progress and a "close with animation" callback. */
@Composable
private fun PremiumWindow(
    show: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable BoxScope.(progress: () -> Float, close: () -> Unit) -> Unit,
) {
    val p = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    val dismiss by rememberUpdatedState(onDismissRequest)

    LaunchedEffect(show, leaving) {
        if (show && !leaving) {
            p.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 420f))
        } else {
            p.animateTo(0f, tween(180))
            if (leaving) { leaving = false; dismiss() }
        }
    }
    if (!show && p.value <= 0.001f && !p.isRunning) return

    Dialog(
        onDismissRequest = { leaving = true },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Remove the default grey dim; we draw our own and add a soft blur behind on Android 12+.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            window ?: return@LaunchedEffect
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.setDimAmount(0f)
            if (Build.VERSION.SDK_INT >= 31) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                snapshotFlow { p.value }.collect { v ->
                    runCatching { window.attributes = window.attributes.apply { blurBehindRadius = (v.coerceIn(0f, 1f) * 36).toInt() } }
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            // dim layer; tap outside closes
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = p.value.coerceIn(0f, 1f) }
                    .background(Color(0x99000000))
                    .clickable(interactionSource = noRipple, indication = null) { leaving = true },
            )
            content({ p.value }, { leaving = true })
        }
    }
}

/** Centered premium dialog card with any content. */
@Composable
fun PremiumDialog(
    onDismissRequest: () -> Unit,
    show: Boolean = true,
    /** true = no card behind the content (content floats on the blurred, dimmed screen) */
    bare: Boolean = false,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) = PremiumWindow(show, onDismissRequest) { progress, close ->
    val maxH = LocalConfiguration.current.screenHeightDp.dp * 0.86f
    val base = Modifier.align(Alignment.Center)
        .padding(horizontal = 24.dp)
        .widthIn(max = 440.dp).fillMaxWidth()
        .heightIn(max = maxH)
        .graphicsLayer {
            val v = progress()
            alpha = v.coerceIn(0f, 1f)
            val s = 0.92f + 0.08f * v
            scaleX = s; scaleY = s
        }
    Column(
        if (bare) base.clickable(interactionSource = noRipple, indication = null) {} else base
            .shadow(28.dp, CardShape24, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CardShape24)
            .background(Brush.verticalGradient(listOf(Color(0xFF1C1C21), GlassCard)))
            .border(1.dp, Rim, CardShape24)
            .clickable(interactionSource = noRipple, indication = null) {}
            .padding(24.dp),
    ) { content(close) }
}

/**
 * Drop-in replacement for Material's AlertDialog with the premium animation.
 * Same parameter names, so existing dialogs only need the function name changed.
 */
@Composable
fun PremiumAlert(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = GlassCard,
    show: Boolean = true,
) = PremiumDialog(onDismissRequest, show) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        if (title != null) {
            ProvideTextStyle(LocalTextStyle.current.copy(fontSize = 21.sp, fontWeight = FontWeight.SemiBold)) { title() }
            Spacer(Modifier.height(14.dp))
        }
        if (text != null) {
            Box(Modifier.weight(1f, fill = false)) {
                ProvideTextStyle(LocalTextStyle.current.copy(fontSize = 15.sp, color = Color(0xFFC9CDD6), lineHeight = 21.sp)) { text() }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            dismissButton?.invoke()
            Spacer(Modifier.width(4.dp))
            confirmButton()
        }
    }
}

/** Bottom sheet that slides up with a spring; drag-free, tap outside or back to close. */
@Composable
fun PremiumSheet(
    onDismissRequest: () -> Unit,
    show: Boolean = true,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) = PremiumWindow(show, onDismissRequest) { progress, close ->
    val density = LocalDensity.current
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Column(
        Modifier.align(Alignment.BottomCenter)
            .widthIn(max = 560.dp).fillMaxWidth()
            .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.9f)
            .graphicsLayer {
                val v = progress()
                translationY = (1f - v) * size.height.coerceAtLeast(with(density) { 300.dp.toPx() })
                alpha = (v * 1.6f).coerceIn(0f, 1f)
            }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF1C1C21), GlassCard)))
            .border(1.dp, Rim, shape)
            .clickable(interactionSource = noRipple, indication = null) {}
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 14.dp).size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF)))
        content(close)
    }
}
