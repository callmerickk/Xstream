package com.example.streamnest

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

/**
 * Music's bottom bar (switch musicOrbit): Xstream's own PhoneOrbitBar capsule with two faces, like a dynamic island.
 *  - "tabs": Home · Explore · Library · your picture (Xstream's PxOrbitItem / PxMeItem, so same look, sizes, animation,
 *    and press-and-hold on the picture opens the profile switcher).
 *  - "song": a turning cover, title + artist, play / pause with a progress ring, and a small › arrow.
 * Swipe the pill sideways (or tap ›) to change face; a spring slides one face out and the other in.
 * Tap the song part (or swipe it up) = Now playing. When Now playing closes, the song is back in the pill.
 */
@Composable
fun MusicOrbitBar(
    tab: String,
    profileOpen: Boolean,
    nowOpen: Boolean,
    vm: AppViewModel,
    onTab: (String) -> Unit,
    onMe: () -> Unit,
    onOpenNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val hasTrack = now.track != null
    val trackId = now.track?.id
    var song by rememberSaveable { mutableStateOf(true) }
    val songShown = song && hasTrack
    val compact = remember { mutableStateOf(0f) }   // the Xstream bar can hide itself; Music never does

    // A new song starts: show it in the pill.
    LaunchedEffect(trackId) { if (trackId != null) song = true }
    // Now playing closed (swipe down or Back): the song collapses into the pill.
    var wasOpen by remember { mutableStateOf(nowOpen) }
    LaunchedEffect(nowOpen) {
        if (wasOpen && !nowOpen && hasTrack) { song = true; MusicLog.add("Orbit pill: Now playing closed, the song is back in the pill") }
        wasOpen = nowOpen
    }

    val setSong: (Boolean) -> Unit = { on ->
        if (on != song && (!on || hasTrack)) {
            song = on
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            MusicLog.add(if (on) "Orbit pill: song side" else "Orbit pill: tabs side")
        }
    }
    val latestSet by rememberUpdatedState(setSong)
    val swipe = Modifier.pointerInput(Unit) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                if (total < -48.dp.toPx()) latestSet(false) else if (total > 48.dp.toPx()) latestSet(true)
                total = 0f
            },
            onDragCancel = { total = 0f },
        ) { _, dx -> total += dx }
    }
    val go: (String) -> Unit = { k -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onTab(k) }

    PhoneOrbitBar(0, vm, compact, onTab = {}, modifier = modifier) {
        AnimatedContent(
            targetState = songShown,
            modifier = Modifier.weight(1f).fillMaxHeight().then(swipe),
            transitionSpec = {
                val toTabs = !targetState
                (slideInHorizontally(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { if (toTabs) it else -it } + fadeIn(tween(160))) togetherWith
                    (slideOutHorizontally(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { if (toTabs) -it else it } + fadeOut(tween(120)))
            },
            label = "orbitFace",
        ) { showSong ->
            if (showSong) SongFace(onOpenNow = onOpenNow, onToTabs = { setSong(false) })
            else Row(Modifier.fillMaxSize(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                PxOrbitItem(!profileOpen && tab == "home", Icons.Default.Home, "Home") { go("home") }
                PxOrbitItem(!profileOpen && tab == "explore", Icons.Default.Explore, "Explore") { go("explore") }
                PxOrbitItem(!profileOpen && tab == "library", Icons.Default.LibraryMusic, "Library") { go("library") }
                PxMeItem(profileOpen, vm) { onMe() }
            }
        }
    }
}

/** The song face of the pill. */
@Composable
private fun SongFace(onOpenNow: () -> Unit, onToTabs: () -> Unit) {
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    val look by MusicLook.current.collectAsStateWithLifecycle()
    val t = now.track ?: return
    val playing = now.playing

    // The cover turns slowly while playing and stops where it is when paused.
    val angle = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (playing) while (true) {
            val s = angle.value % 360f
            angle.snapTo(s)
            angle.animateTo(s + 360f, tween(16000, easing = LinearEasing))
        }
    }
    val f = if (now.durMs > 0) (now.posMs.toFloat() / now.durMs).coerceIn(0f, 1f) else 0f

    Row(Modifier.fillMaxSize().padding(start = 6.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        // Cover + text: tap or swipe up = Now playing.
        Row(
            Modifier.weight(1f).fillMaxHeight()
                .pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total < -40.dp.toPx()) onOpenNow(); total = 0f }) { _, dy -> total += dy }
                }
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onOpenNow),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(50.dp).graphicsLayer { rotationZ = angle.value }.clip(CircleShape)
                    .background(Color(0xFF232B3A)).border(1.dp, Color(0x33FFFFFF), CircleShape),
                Alignment.Center,
            ) {
                if (!t.artworkURL.isNullOrBlank()) AsyncImage(t.artworkURL, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF0F1014)))   // the disc's centre hole, so the turning shows
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(now.error ?: t.artist, fontSize = 12.sp, color = if (now.error != null) Color(0xFFFF8A80) else Color(0xFF9AA3B5),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Play / pause with a progress ring.
        Box(Modifier.size(46.dp).clip(CircleShape).clickable { MusicPlayer.toggle() }, Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 2.5.dp.toPx()
                val tl = Offset(sw / 2, sw / 2)
                val sz = Size(size.width - sw, size.height - sw)
                drawArc(Color(0x26FFFFFF), 0f, 360f, false, tl, sz, style = Stroke(sw))
                drawArc(look.accent, -90f, 360f * f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            }
            if (now.buffering) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(26.dp), tint = Color.White)
        }
        // › = show the tabs.
        Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onToTabs), Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Show tabs", Modifier.size(22.dp), tint = Color(0xFFB4B9CA))
        }
    }
}
