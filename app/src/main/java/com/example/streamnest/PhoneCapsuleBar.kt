package com.example.streamnest

import kotlinx.coroutines.flow.first
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.CompositingStrategy
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/** 0.9.75 (from Xstream): the floating Capsule dock (Settings › Layout › Bottom bar). Tabs: 0 Home, 1 Search, 2 Library, 3 Me. */
@Composable
fun PhoneCapsuleBar(tab: Int, vm: AppViewModel, compact: State<Float>, onTab: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    var bubble by remember { mutableStateOf(false) }
    var hover by remember { mutableIntStateOf(-1) }
    val slots = remember { mutableStateMapOf<Int, androidx.compose.ui.geometry.Rect>() }
    val meOrigin = remember { arrayOf(Offset.Zero) }
    val red = Accent
    Box(
        modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 14.dp).graphicsLayer {
            val v = compact.value
            translationY = v * 96.dp.toPx(); val sc = 1f - 0.6f * v.coerceIn(0f, 1f); scaleX = sc; scaleY = sc
            alpha = (1f - v * 1.3f).coerceIn(0f, 1f)
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
        },
        Alignment.Center,
    ) {
        val shape = RoundedCornerShape(50)
        // Holding any dock button: every icon wobbles as if under water.
        var water by remember { mutableStateOf(false) }
        Row(
            Modifier.width(312.dp).height(68.dp).shadow(22.dp, shape)
                .drawBehind {
                    drawOval(Brush.radialGradient(listOf(red.copy(alpha = 0.45f), Color.Transparent), center = Offset(size.width * 0.3f, 0f), radius = size.width * 0.45f),
                        topLeft = Offset(-size.width * 0.1f, -size.height * 0.55f), size = androidx.compose.ui.geometry.Size(size.width * 0.85f, size.height * 1.1f))
                }
                .clip(shape).background(Brush.verticalGradient(listOf(Color(0xF71A1A22), Color(0xFA0D0E13))))
                .border(1.5.dp, Brush.horizontalGradient(listOf(Color(0xFFFF6A3D), red, red.copy(alpha = 0.35f), Color(0x26FFFFFF))), shape)
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            PxDockItem(0, water, tab == 0, t("navHome"), { onTab(0) }, { water = it }) { c -> Icon(Icons.Default.Home, t("navHome"), Modifier.size(23.dp), tint = c) }
            PxDockItem(1, water, tab == 1, t("navSearch"), { onTab(1) }, { water = it }) { c -> Icon(Icons.Default.Search, t("navSearch"), Modifier.size(23.dp), tint = c) }
            PxDockItem(2, water, tab == 2, "Library", { onTab(2) }, { water = it }) { c -> Icon(Icons.Default.VideoLibrary, "Library", Modifier.size(23.dp), tint = c) }
            val meLabel = t("navMySpace")
            val bob = pxRememberFloat(3, water)
            Box(
                Modifier.size(50.dp).graphicsLayer { translationY = bob.value.first * 3.dp.toPx(); rotationZ = bob.value.second }
                    .onGloballyPositioned { meOrigin[0] = it.boundsInRoot().topLeft }
                    .pointerInput(profiles) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val long = awaitLongPressOrCancellation(down.id)
                            if (long == null) { onTab(3); return@awaitEachGesture }
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            bubble = true; hover = -1
                            var moved = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                val at = meOrigin[0] + ch.position
                                if ((ch.position - down.position).getDistance() > 24f) moved = true
                                val h = slots.entries.firstOrNull { (_, r) -> r.inflate(18f).contains(at) }?.key ?: -1
                                if (h != hover) { hover = h; if (h >= 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                ch.consume()
                                if (!ch.pressed) break
                            }
                            val pick = profiles.getOrNull(hover)
                            hover = -1
                            // Released on a profile: switch. Released without sliding: the bubble stays open to tap.
                            if (pick != null) { bubble = false; if (pick.id != current?.id) ProfileLock.ask(pick) { vm.selectProfile(pick); onTab(3) } }
                            else if (moved) bubble = false
                        }
                    }
                    .semantics { contentDescription = meLabel },
                Alignment.Center,
            ) {
                // Your picture with a red ring (white inside it while My Space is open).
                Box(Modifier.size(40.dp).border(2.dp, if (tab == 3) Color.White else red, CircleShape).padding(3.dp).clip(CircleShape), Alignment.Center) { Avatar(vm, 34.dp) }
                Box(Modifier.size(46.dp).drawBehind { drawCircle(red.copy(alpha = 0.28f), radius = size.minDimension / 2f, style = androidx.compose.ui.graphics.drawscope.Stroke(5.dp.toPx())) })
            }
        }
        if (bubble) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { bubble = false } })
        if (bubble) Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp).offset(y = (-84).dp)
                .shadow(18.dp, RoundedCornerShape(40.dp)).clip(RoundedCornerShape(40.dp)).background(Color(0xF216181F))
                .border(1.dp, Brush.verticalGradient(listOf(red.copy(alpha = 0.7f), red.copy(alpha = 0.2f))), RoundedCornerShape(40.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End,
        ) {
            profiles.forEachIndexed { i, pr ->
                val on = hover == i
                val grow by animateFloatAsState(if (on) 1.18f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 500f), label = "grow")
                Row(Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { bubble = false; if (pr.id != current?.id) ProfileLock.ask(pr) { vm.selectProfile(pr); onTab(3) } },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(pr.name + if (pr.id == current?.id) " · you" else "", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (on) Color.White else Color(0xFFB6BDCC))
                    Box(Modifier.size(52.dp).onGloballyPositioned { slots[i] = it.boundsInRoot() }, Alignment.Center) {
                        if (on) PxWaterRipple(Modifier.matchParentSize())
                        // Hovered: a round ring that hugs the avatar (not a square highlight).
                        ProfileAvatar(vm, pr, 46.dp, Modifier.graphicsLayer { scaleX = grow; scaleY = grow }
                            .then(if (on) Modifier.border(2.dp, Color.White, CircleShape) else Modifier), animate = false)
                    }
                }
            }
        }
    }
}

/** Three soft rings spreading out like water, under a hovered profile. */
@Composable
private fun PxWaterRipple(modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "water")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "w")
    Spacer(modifier.drawBehind {
        for (k in 0..2) {
            val p = (t + k / 3f) % 1f
            drawCircle(Color(0xFF9FD8FF), radius = size.minDimension / 2f * (0.8f + 0.7f * p), alpha = 0.7f * (1f - p), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    })
}

/** One floating dock icon: it bobs gently all the time (as if on water) and wears a red glowing ring. */
@Composable
private fun PxDockItem(i: Int, water: Boolean, selected: Boolean, label: String, onClick: () -> Unit, onHold: (Boolean) -> Unit, icon: @Composable (Color) -> Unit) {
    val click by rememberUpdatedState(onClick)
    val hold by rememberUpdatedState(onHold)
    val bob = pxRememberFloat(i, water)
    val fill by androidx.compose.animation.animateColorAsState(if (selected) Color(0x24FFFFFF) else Color.Transparent, label = "dock")
    val red = BtnColor
    Box(
        Modifier.size(50.dp).graphicsLayer {
            val (y, r) = bob.value
            translationY = y * (if (water) 5.dp else 3.dp).toPx(); rotationZ = r
            val sc = if (water) 1f + 0.04f * y else 1f; scaleX = sc; scaleY = sc
        }
            .drawBehind {
                // Glow, then the ring.
                drawCircle(Brush.radialGradient(listOf(red.copy(alpha = if (selected) 0.32f else 0.22f), Color.Transparent), radius = size.minDimension * 0.62f), radius = size.minDimension * 0.62f)
                drawCircle(red.copy(alpha = if (selected) 0.95f else 0.6f), radius = size.minDimension / 2f - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            }
            .clip(CircleShape).background(fill)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { click() },
                    onPress = { tryAwaitRelease(); hold(false) },
                    onLongPress = { hold(true) },
                )
            }
            .semantics { contentDescription = label },
        Alignment.Center,
    ) { icon(if (selected) Color.White else Color(0xFF9AA0B0)) }
}

/** A slow float for dock icon [i] (each a little out of step): (up/down -1..1, tilt in degrees). Faster and wilder in [water]. */
@Composable
private fun pxRememberFloat(i: Int, water: Boolean): State<Pair<Float, Float>> {
    // 0.9.79: animations only on change — the icons float for a few seconds when the dock appears or "water" changes,
    // then settle and rest (it used to redraw the dock every frame, all the time).
    val t = remember { androidx.compose.animation.core.Animatable(0f) }
    val amp = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(water) {
        launch { amp.animateTo(1f, tween(400)) }
        t.animateTo(t.value + (4 * Math.PI).toFloat(), tween(6_400, easing = LinearEasing))
        amp.animateTo(0f, tween(700))
    }
    return remember(water) {
        derivedStateOf {
            val ph = t.value * (if (water) 2.6f else 1f) + i * 1.6f
            val y = kotlin.math.sin(ph) * amp.value
            val r = (if (water) 4f else 1.6f) * kotlin.math.sin(ph * 0.7f + 0.8f) * amp.value
            y to r
        }
    }
}

