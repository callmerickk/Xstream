package com.example.streamnest

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** VR mode across player screens: a new episode / title picked in VR opens straight in VR again. */
object VrState {
    @Volatile private var resumeAt = 0L
    /** The next player opens in VR (only if it opens within 90 s). */
    var resume: Boolean
        get() = System.currentTimeMillis() - resumeAt < 90_000
        set(v) { resumeAt = if (v) System.currentTimeMillis() else 0L }
    val openTitle = kotlinx.coroutines.flow.MutableStateFlow<Meta?>(null)
    /** Remembered between films while the app is open. */
    var sizeIdx = 1
    var fixed = false
    var smooth = false
}

/** A real 3D copy (two pictures in one): 0 = none, 1 = side by side, 2 = top / bottom. */
fun stereoKind(name: String?): Int {
    val n = name?.lowercase() ?: return 0
    return when {
        Regex("(^|[^a-z])(h-?sbs|half-?sbs|3d[ ._-]?sbs|sbs[ ._-]?3d|full-?sbs)([^a-z]|$)").containsMatchIn(n) -> 1
        Regex("(^|[^a-z])(h-?ou|half-?ou|h-?tab|3d[ ._-]?(ou|tab)|(ou|tab)[ ._-]?3d)([^a-z]|$)").containsMatchIn(n) -> 2
        else -> 0
    }
}

/** One control of the VR panel; its place is fixed (in panel dp), so what is drawn and what is clicked always agree. */
private data class VrCtl(val id: String, val x: Float, val y: Float, val w: Float, val h: Float, val label: String = "", val meta: Meta? = null) {
    fun has(px: Float, py: Float) = px in x..(x + w) && py in y..(y + h)
}

private const val PANEL_W = 236f
private const val BTN = 34f
private const val GAP = 4f

/** The compact panel: title, timeline, then rows of six icons. */
private fun controlLayout(hasNext: Boolean, has3d: Boolean): List<VrCtl> {
    fun col(i: Int) = 8f + i * (BTN + GAP)
    val r1 = 44f; val r2 = r1 + 30f + GAP; val r3 = r2 + 30f + GAP
    return buildList {
        add(VrCtl("bar", 8f, 20f, PANEL_W - 16f, 18f, "Jump here"))
        add(VrCtl("back", col(0), r1, BTN, 30f, "Back 10 s")); add(VrCtl("play", col(1), r1, BTN, 30f, "Play / Pause"))
        add(VrCtl("fwd", col(2), r1, BTN, 30f, "Forward 10 s")); add(VrCtl("volDown", col(3), r1, BTN, 30f, "Volume down"))
        add(VrCtl("volUp", col(4), r1, BTN, 30f, "Volume up")); add(VrCtl("bright", col(5), r1, BTN, 30f, "Brightness"))
        add(VrCtl("subs", col(0), r2, BTN, 30f, "Subtitles")); add(VrCtl("audio", col(1), r2, BTN, 30f, "Audio language"))
        add(VrCtl("size", col(2), r2, BTN, 30f, "Screen size")); add(VrCtl("pic", col(3), r2, BTN, 30f, "Picture"))
        add(VrCtl("fixed", col(4), r2, BTN, 30f, "Fixed screen")); add(VrCtl("center", col(5), r2, BTN, 30f, "Recenter"))
        var c = 0
        if (hasNext) add(VrCtl("next", col(c++), r3, BTN, 30f, "Next episode"))
        add(VrCtl("browse", col(c++), r3, BTN, 30f, "Browse"))
        if (has3d) add(VrCtl("3d", col(c++), r3, BTN, 30f, "3D"))
        add(VrCtl("exit", col(5), r3, BTN, 30f, "Leave VR"))
    }
}
private const val CONTROL_H = 166f

/** Browse: up to three rows of five posters. */
private fun browseLayout(rows: List<Pair<String, List<Meta>>>): List<VrCtl> = buildList {
    rows.forEachIndexed { r, (_, items) ->
        val y0 = 8f + r * 88f
        items.forEachIndexed { i, m -> add(VrCtl("t:" + m.id, 8f + i * 45f, y0 + 14f, 41f, 66f, m.name, m)) }
    }
    add(VrCtl("browseBack", 8f, 8f + rows.size * 88f, 70f, 24f, "Back"))
}
private fun browseHeight(rows: Int) = 8f + rows * 88f + 32f

/**
 * Xstream Live · VR headset (JioDive and other phone headsets): a cinema around you — a curved screen whose edges come
 * towards you, rows of seats and wall lamps (they glow up when you pause). Each eye sees the room from its own position,
 * so the room has real depth. The headset's button (it taps the screen) opens the controls: a dot in the middle of your
 * view is the pointer — turn your head to put it on a control, press to click; press on empty space to close.
 * "Fixed screen": the screen stays where it is in the room when you turn your head (look around the cinema).
 */
@Composable
fun VrHeadsetView(
    vm: AppViewModel, player: Player, title: String, subText: () -> String, stereo: Int, threeD: (() -> Unit)?,
    onSeekTo: (Long) -> Unit, onNext: (() -> Unit)?, onExit: () -> Unit,
) {
    Dialog(onDismissRequest = { ActLog.add("PLAY", "VR: left (Back)"); onExit() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        val view = LocalView.current
        val ctx = LocalContext.current
        val window = (view.parent as? DialogWindowProvider)?.window
        LaunchedEffect(Unit) {
            window?.let { w ->
                // The whole screen, notch area and edges included (nothing of the normal player shows around it).
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                if (android.os.Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                w.setDimAmount(0f)
                WindowCompat.getInsetsController(w, w.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            ActLog.add("PLAY", "VR: on · screen ${listOf("Small", "Big", "IMAX", "Giant")[VrState.sizeIdx]} · " +
                (if (VrState.fixed) "fixed screen" else "screen follows the head") + (if (stereo != 0) " · 3D copy" else ""))
        }
        var glView by remember { mutableStateOf<VrGlView?>(null) }
        var controls by remember { mutableStateOf(false) }
        var browse by remember { mutableStateOf(false) }
        // Head direction from the phone's motion sensor (radians, + = looking left / up), relative to "straight ahead".
        var headYaw by remember { mutableFloatStateOf(0f) }
        var headPitch by remember { mutableFloatStateOf(0f) }
        var openYaw by remember { mutableFloatStateOf(0f) }
        var openPitch by remember { mutableFloatStateOf(0f) }
        var recenter by remember { mutableIntStateOf(0) }
        var sizeIdx by remember { mutableIntStateOf(VrState.sizeIdx) }
        var fixed by remember { mutableStateOf(VrState.fixed) }
        var smooth by remember { mutableStateOf(VrState.smooth) }
        var subsOn by remember { mutableStateOf(true) }
        var tick by remember { mutableIntStateOf(0) }
        var hint by remember { mutableStateOf(true) }
        var playing by remember { mutableStateOf(player.isPlaying) }
        LaunchedEffect(Unit) { delay(5_000); hint = false }
        // Half-width of the curved screen (in units of its distance from you).
        val sizes = listOf("Small" to 0.7f, "Big" to 0.95f, "IMAX" to 1.2f, "Giant" to 1.45f)
        LaunchedEffect(sizeIdx, glView) { glView?.setScreen(sizes[sizeIdx].second); VrState.sizeIdx = sizeIdx }
        LaunchedEffect(smooth, glView) { glView?.setSharp(if (smooth) 0f else 0.35f); VrState.smooth = smooth }
        LaunchedEffect(stereo, glView) { glView?.setStereo(stereo) }
        // The wall lamps: dim while the film plays, warm and bright when you pause (like the interval).
        androidx.compose.runtime.DisposableEffect(player) {
            val l = object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying } }
            player.addListener(l); onDispose { player.removeListener(l) }
        }
        val lamps by animateFloatAsState(if (playing) 0.12f else 1f, tween(1_400), label = "vrLamps")
        LaunchedEffect(lamps, glView) { glView?.setLamps(lamps) }
        // Motion sensor: on while the controls are open, and all the time with "Fixed screen".
        val sensing = controls || fixed
        DisposableEffect(sensing, recenter) {
            if (!sensing) return@DisposableEffect onDispose { }
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
            val rv = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
            val r = FloatArray(9)
            var base: Pair<Float, Float>? = null
            val l = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(e: android.hardware.SensorEvent) {
                    android.hardware.SensorManager.getRotationMatrixFromVector(r, e.values)
                    // Where the back of the phone points = where you look (works in any landscape direction).
                    val fx = -r[2]; val fy = -r[5]; val fz = -r[8]
                    val heading = kotlin.math.atan2(fx, fy)
                    val up = kotlin.math.asin(fz.coerceIn(-1f, 1f))
                    val b = base ?: (heading to up).also { base = it }
                    var dy = heading - b.first
                    while (dy > Math.PI) dy -= (2 * Math.PI).toFloat(); while (dy < -Math.PI) dy += (2 * Math.PI).toFloat()
                    headYaw = -dy
                    headPitch = up - b.second
                }
                override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
            }
            if (rv != null) sm.registerListener(l, rv, android.hardware.SensorManager.SENSOR_DELAY_GAME)
            else ActLog.add("PLAY", "VR: this phone has no motion sensor")
            onDispose { sm.unregisterListener(l); if (!fixed) { headYaw = 0f; headPitch = 0f } }
        }
        LaunchedEffect(headYaw, headPitch, fixed, glView) { glView?.setHead(if (fixed) headYaw else 0f, if (fixed) headPitch else 0f, fixed) }
        LaunchedEffect(controls) { while (controls) { tick++; delay(500) } }
        val browseRows = remember(browse) { if (browse) browseItems(vm) else emptyList() }
        val hasNext = onNext != null
        val layout = if (browse) browseLayout(browseRows) else controlLayout(hasNext, threeD != null || stereo != 0)
        val pH = if (browse) browseHeight(browseRows.size) else CONTROL_H

        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val eyeW = maxWidth.value / 2f
            val eyeH = maxHeight.value
            // Pointer travel: about one eye width for 75° of head turn.
            val k = eyeW / 1.3f
            val relYaw = headYaw - openYaw; val relPitch = headPitch - openPitch
            val pointerX = PANEL_W / 2f - relYaw * k
            val pointerY = pH / 2f - relPitch * k
            val over = if (controls) layout.firstOrNull { it.has(pointerX, pointerY) } else null
            fun click() {
                if (!controls) { openYaw = headYaw; openPitch = headPitch; controls = true; browse = false; ActLog.add("PLAY", "VR: controls opened"); return }
                val h = layout.firstOrNull { it.has(pointerX, pointerY) }
                ActLog.add("PLAY", "VR click: ${h?.label ?: "empty space → controls closed"} · dot at ${pointerX.toInt()},${pointerY.toInt()} · head ${Math.toDegrees(relYaw.toDouble()).toInt()}°/${Math.toDegrees(relPitch.toDouble()).toInt()}°")
                if (h == null) { controls = false; browse = false; return }
                val dur = player.duration.coerceAtLeast(0)
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                when {
                    h.id == "play" -> if (player.isPlaying) player.pause() else player.play()
                    h.id == "back" -> onSeekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                    h.id == "fwd" -> onSeekTo((player.currentPosition + 10_000).coerceAtMost(dur))
                    h.id == "bar" -> if (dur > 0) onSeekTo((dur * ((pointerX - h.x) / h.w).coerceIn(0f, 1f)).toLong())
                    h.id == "volDown" -> am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, 0)
                    h.id == "volUp" -> am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, 0)
                    h.id == "bright" -> window?.let { w ->
                        // Cycles 30 % → 55 % → 80 % → 100 %.
                        val cur = w.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.55f
                        val next = listOf(0.3f, 0.55f, 0.8f, 1f).firstOrNull { it > cur + 0.01f } ?: 0.3f
                        w.attributes = w.attributes.apply { screenBrightness = next }
                    }
                    h.id == "size" -> sizeIdx = (sizeIdx + 1) % sizes.size
                    h.id == "pic" -> smooth = !smooth
                    h.id == "fixed" -> { fixed = !fixed; VrState.fixed = fixed; recenter++ }
                    h.id == "center" -> { recenter++; openYaw = 0f; openPitch = 0f; headYaw = 0f; headPitch = 0f }
                    h.id == "subs" -> subsOn = !subsOn
                    h.id == "audio" -> nextAudio(player)
                    h.id == "3d" -> threeD?.invoke()
                    h.id == "next" -> onNext?.let { VrState.resume = true; it() }
                    h.id == "browse" -> browse = true
                    h.id == "browseBack" -> browse = false
                    h.id == "exit" -> onExit()
                    h.meta != null -> { VrState.resume = true; VrState.openTitle.value = h.meta }
                }
                tick++
            }
            // Always the newest click (it reads where the dot is right now — the old one kept the first position).
            val clickNow by rememberUpdatedState(::click)
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { clickNow() }) }) {
                AndroidView(factory = { c -> VrGlView(c, player).also { glView = it } }, onRelease = { it.release() }, modifier = Modifier.fillMaxSize())
                // Where the bottom of the curved screen sits in each eye (for the subtitles).
                val half = sizes[sizeIdx].second
                val aspect = player.videoSize.let { v -> if (v.width > 0 && v.height > 0) v.width * v.pixelWidthHeightRatio / v.height * (if (stereo == 2) 2f else 1f) else 16f / 9f }
                val screenHalfH = half / aspect
                val eyeAspect = eyeW / eyeH
                val subY = (screenHalfH * eyeAspect * 0.86f).coerceAtMost(0.8f) * eyeH / 2f
                val subDx = if (fixed) headYaw * k else 0f
                val subDy = if (fixed) headPitch * k else 0f
                val sub = if (subsOn) subText() else ""
                val subStyle = TextStyle(color = Color.White, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    shadow = Shadow(Color(0xE6000000), Offset(0f, 2f), 6f))
                Row(Modifier.fillMaxSize()) {
                    repeat(2) { eye ->
                        val cx = eyeW / 2f + (if (eye == 0) 0.02f else -0.02f) * eyeW
                        val cy = eyeH / 2f
                        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                            // Subtitles like the normal player: white, no box, a soft shadow.
                            if (sub.isNotBlank()) Text(sub, Modifier.align(Alignment.Center).offset(x = (cx - eyeW / 2f + subDx).dp, y = (subY - subDy).dp).padding(horizontal = 36.dp),
                                style = subStyle)
                            if (controls) {
                                val ox = cx - pointerX; val oy = cy - pointerY
                                Box(Modifier.offset(ox.dp, oy.dp).size(PANEL_W.dp, pH.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xE60F1014))
                                    .border(1.dp, Color(0x594B4FD8), RoundedCornerShape(14.dp))) {
                                    if (browse) BrowsePanel(browseRows, layout, over)
                                    else ControlPanel(player, title, tick, layout, over, subsOn, sizes[sizeIdx].first, smooth, fixed, pointerX)
                                }
                                // The pointer: always in the middle of your view.
                                Box(Modifier.offset((cx - 5).dp, (cy - 5).dp).size(10.dp).clip(CircleShape)
                                    .background(Color.White).border(2.dp, Color(0xFF6A6FF0), CircleShape))
                            } else if (hint) Text("Press the headset button for controls", Modifier.align(Alignment.BottomCenter).padding(bottom = (eyeH * 0.12f).dp)
                                .clip(RoundedCornerShape(12.dp)).background(Color(0xAA000000)).padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 10.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/** Next audio language of the playing file (Hindi → English → …). */
private fun nextAudio(player: Player) {
    val groups = player.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
    if (groups.size < 2) return
    val cur = groups.indexOfFirst { it.isSelected }
    val g = groups[(cur + 1) % groups.size]
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
}

private fun browseItems(vm: AppViewModel): List<Pair<String, List<Meta>>> = listOf(
    "Continue watching" to vm.progress.value.take(5).map { it.toMeta() },
    "Top 10" to (HomeShuffle.frozen["movie"].orEmpty() + HomeShuffle.frozen["series"].orEmpty()).take(5),
    "Watchlist" to vm.myList.value.take(5),
).filter { it.second.isNotEmpty() }

private fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

@Composable
private fun ControlPanel(player: Player, title: String, tick: Int, layout: List<VrCtl>, over: VrCtl?, subsOn: Boolean, size: String,
    smooth: Boolean, fixed: Boolean, px: Float) {
    @Suppress("UNUSED_VARIABLE") val t = tick
    Text(title, Modifier.offset(8.dp, 5.dp).width((PANEL_W - 16f).dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
    val dur = player.duration.coerceAtLeast(1); val pos = player.currentPosition
    val bar = layout.first { it.id == "bar" }
    val overBar = over?.id == "bar"
    Box(Modifier.offset(bar.x.dp, (bar.y + 7f).dp).size(bar.w.dp, if (overBar) 5.dp else 3.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x40FFFFFF))) {
        Box(Modifier.fillMaxHeight().width((bar.w * pos / dur).coerceIn(0f, bar.w).dp).background(Color(0xFFE50914)))
    }
    if (overBar) Box(Modifier.offset((px - 1).dp, (bar.y + 2f).dp).size(2.dp, 14.dp).background(Color.White))
    Text(if (overBar) "Jump to ${fmt((dur * ((px - bar.x) / bar.w).coerceIn(0f, 1f)).toLong())}" else "${fmt(pos)} / ${fmt(dur)}",
        Modifier.offset(bar.x.dp, (bar.y + 12f).dp), fontSize = 8.sp, color = Color(0xFFB8BED3))
    layout.filter { it.id != "bar" }.forEach { c ->
        val icon: ImageVector? = when (c.id) {
            "back" -> Icons.Default.Replay10; "fwd" -> Icons.Default.Forward10
            "play" -> if (player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow
            "volDown" -> Icons.AutoMirrored.Filled.VolumeDown; "volUp" -> Icons.AutoMirrored.Filled.VolumeUp
            "bright" -> Icons.Default.Brightness6; "subs" -> if (subsOn) Icons.Default.Subtitles else Icons.Default.SubtitlesOff
            "audio" -> Icons.Default.Audiotrack; "size" -> Icons.Default.AspectRatio; "center" -> Icons.Default.CenterFocusStrong
            "pic" -> Icons.Default.Tune; "fixed" -> if (fixed) Icons.Default.Lock else Icons.Default.LockOpen
            "3d" -> Icons.Default.ViewInAr
            "next" -> Icons.Default.SkipNext; "browse" -> Icons.Default.GridView; "exit" -> Icons.Default.Close
            else -> null
        }
        val hot = over?.id == c.id
        val on = (c.id == "fixed" && fixed)
        Box(Modifier.offset(c.x.dp, c.y.dp).size(c.w.dp, c.h.dp).clip(RoundedCornerShape(8.dp))
            .background(if (hot) Color(0xFF4B4FD8) else if (c.id == "play") Color(0xCCE50914) else if (on) Color(0x664B4FD8) else Color(0xCC1A1E2E))
            .border(if (hot) 1.5.dp else 1.dp, if (hot) Color.White else Color(0x24FFFFFF), RoundedCornerShape(8.dp)), Alignment.Center) {
            if (icon != null) Icon(icon, c.label, Modifier.size(16.dp), tint = Color.White)
            else Text(c.label, fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
    // The name of the control under the dot (one line under the rows).
    val last = layout.maxOf { it.y + it.h }
    Text(when {
        over == null -> "Press on empty space to close"
        over.id == "size" -> "Screen size · $size"
        over.id == "pic" -> "Picture · ${if (smooth) "Smooth" else "Sharp"}"
        over.id == "fixed" -> "Fixed screen · ${if (fixed) "on" else "off"}"
        else -> over.label
    }, Modifier.offset(0.dp, (last + 3f).dp).width(PANEL_W.dp), fontSize = 8.sp, color = Color(0xFFC9CEDE), textAlign = TextAlign.Center, maxLines = 1)
}

@Composable
private fun BrowsePanel(rows: List<Pair<String, List<Meta>>>, layout: List<VrCtl>, over: VrCtl?) {
    rows.forEachIndexed { r, (name, _) ->
        Text(name, Modifier.offset(8.dp, (8f + r * 88f).dp), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB8BED3))
    }
    layout.forEach { c ->
        val hot = over?.id == c.id
        if (c.meta != null) Box(Modifier.offset(c.x.dp, c.y.dp).size(c.w.dp, c.h.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1A1E2E))
            .border(if (hot) 2.dp else 0.dp, Color.White, RoundedCornerShape(6.dp))) {
            coil.compose.AsyncImage(c.meta.poster, c.meta.name, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        } else Box(Modifier.offset(c.x.dp, c.y.dp).size(c.w.dp, c.h.dp).clip(RoundedCornerShape(8.dp))
            .background(if (hot) Color(0xFF4B4FD8) else Color(0xCC1A1E2E)), Alignment.Center) { Text(c.label, fontSize = 9.sp, color = Color.White) }
    }
    if (over?.meta != null) Text(over.meta.name, Modifier.offset(90.dp, (8f + rows.size * 88f + 4f).dp).width((PANEL_W - 98f).dp), fontSize = 9.sp, color = Color.White, maxLines = 1)
    if (rows.isEmpty()) Text("Nothing to show yet", Modifier.offset(8.dp, 10.dp), fontSize = 10.sp, color = Color(0xFFB8BED3))
}

private class VrGlView(ctx: Context, private val player: Player) : GLSurfaceView(ctx) {
    private val renderer = VrRenderer(this)
    private val sizeListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) { renderer.setVideo(videoSize) }
    }

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        keepScreenOn = true
        renderer.setVideo(player.videoSize)
        player.addListener(sizeListener)
    }

    /** Called on the GL thread once the video texture exists: the player draws into it from now on. */
    fun attach(surface: Surface) = post { player.setVideoSurface(surface) }

    fun setScreen(f: Float) { renderer.screen = f; requestRender() }
    fun setSharp(f: Float) { renderer.sharp = f; requestRender() }
    fun setStereo(k: Int) { renderer.stereo = k; requestRender() }
    fun setLamps(f: Float) { renderer.lamps = f; requestRender() }
    /** With a fixed screen the picture is redrawn as the head turns (every frame); otherwise only when a new frame comes. */
    fun setHead(yaw: Float, pitch: Float, fixed: Boolean) {
        renderer.yaw = yaw; renderer.pitch = pitch
        val mode = if (fixed) RENDERMODE_CONTINUOUSLY else RENDERMODE_WHEN_DIRTY
        if (renderMode != mode) renderMode = mode
        requestRender()
    }

    fun release() {
        player.removeListener(sizeListener)
        renderer.surface?.let { player.clearVideoSurface(it) }
        queueEvent { renderer.free() }
    }
}

private class VrRenderer(private val view: VrGlView) : GLSurfaceView.Renderer {
    @Volatile var surface: Surface? = null
    private var st: SurfaceTexture? = null
    private var tex = 0
    private var prog = 0
    private val texM = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    @Volatile private var dirty = false
    @Volatile private var videoAspect = 16f / 9f
    @Volatile private var videoW = 1920f
    @Volatile private var videoH = 1080f
    @Volatile var screen = 0.95f
    @Volatile var sharp = 0.35f
    @Volatile var stereo = 0
    @Volatile var lamps = 0.12f
    @Volatile var yaw = 0f
    @Volatile var pitch = 0f
    private var w = 1; private var h = 1

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    fun setVideo(v: VideoSize) {
        if (v.width > 0 && v.height > 0) { videoAspect = v.width * v.pixelWidthHeightRatio / v.height; videoW = v.width.toFloat(); videoH = v.height.toFloat() }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val s = SurfaceTexture(tex)
        s.setOnFrameAvailableListener { dirty = true; view.requestRender() }
        st = s
        val sf = Surface(s); surface = sf
        view.attach(sf)
        prog = program(VS, FS)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { w = width; h = height }

    override fun onDrawFrame(gl: GL10?) {
        val s = st ?: return
        if (dirty) { dirty = false; runCatching { s.updateTexImage(); s.getTransformMatrix(texM) } }
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(prog)
        val pos = GLES20.glGetAttribLocation(prog, "aPos")
        val uv = GLES20.glGetAttribLocation(prog, "aUv")
        quad.position(0); GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(pos)
        quad.position(2); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(uv)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uTex"), 1, false, texM, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "sTex"), 0)
        val half = w / 2
        val eyeAspect = half.toFloat() / h
        // One picture of a 3D copy is half the frame: its shape is the frame's shape with the halves stretched back.
        val aspect = when (stereo) { 2 -> videoAspect * 2f; else -> videoAspect }
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uHalfW"), screen)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uHalfH"), screen / aspect)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uAspect"), eyeAspect)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uTexel"), 1f / videoW, 1f / videoH)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSharp"), sharp)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uLamps"), lamps)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uYaw"), yaw)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uPitch"), pitch)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uStereo"), stereo)
        for (eye in 0..1) {
            GLES20.glViewport(eye * half, 0, half, h)
            // Lens centre a little towards the nose (phone headsets' lenses sit closer together than half the screen).
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uShift"), if (eye == 0) 0.04f else -0.04f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uEye"), if (eye == 0) -1f else 1f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    fun free() {
        surface?.release(); surface = null
        st?.release(); st = null
        if (tex != 0) GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
        if (prog != 0) GLES20.glDeleteProgram(prog)
    }

    private fun program(vs: String, fs: String): Int {
        fun sh(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
            val ok = IntArray(1); GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) ActLog.add("ERROR", "VR shader: " + GLES20.glGetShaderInfoLog(it).take(300))
        }
        return GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs)); GLES20.glLinkProgram(p)
        }
    }

    private companion object {
        const val VS = """
attribute vec2 aPos; attribute vec2 aUv; varying vec2 vUv;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); vUv = aUv; }
"""
        // Every pixel is a ray from one eye (the two eyes 6.4 cm apart; the screen's middle is ~4 m away = 1 unit), turned
        // by your head when the screen is fixed. It meets the nearest of: the curved screen (part of a cylinder whose edges
        // come towards you), four rows of seats in front of you, the side walls with their lamps, the floor.
        const val FS = """
#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vUv;
uniform samplerExternalOES sTex; uniform mat4 uTex;
uniform float uHalfW; uniform float uHalfH; uniform float uShift; uniform float uAspect; uniform vec2 uTexel;
uniform float uSharp; uniform float uLamps; uniform float uYaw; uniform float uPitch; uniform float uEye; uniform int uStereo;
vec3 tap(vec2 p) { return texture2D(sTex, (uTex * vec4(p, 0.0, 1.0)).xy).rgb; }
vec2 pick(vec2 q) {
    if (uStereo == 1) return vec2(q.x * 0.5 + (uEye > 0.0 ? 0.5 : 0.0), q.y);
    if (uStereo == 2) return vec2(q.x, q.y * 0.5 + (uEye > 0.0 ? 0.0 : 0.5));
    return q;
}
float lamp(vec3 p, vec3 c) { vec3 d = p - c; return exp(-dot(d, d) * 45.0); }
void main() {
    vec2 c = vUv * 2.0 - 1.0;
    c.x -= uShift;
    float r2 = dot(vec2(c.x, c.y / uAspect), vec2(c.x, c.y / uAspect));
    c *= 1.0 + 0.10 * r2 + 0.04 * r2 * r2;
    vec3 d = normalize(vec3(c.x, c.y / uAspect, 1.0));
    // Head turn (fixed screen): pitch, then yaw (+ = looking left / up).
    float cp = cos(uPitch), sp = sin(uPitch);
    d = vec3(d.x, d.y * cp + d.z * sp, -d.y * sp + d.z * cp);
    float cy = cos(uYaw), sy = sin(uYaw);
    d = vec3(d.x * cy - d.z * sy, d.y, d.x * sy + d.z * cy);
    vec3 o = vec3(uEye * 0.008, 0.0, 0.0);
    float best = 1e9; vec3 col = vec3(0.0);
    // Screen light (average of the film) that falls on the room.
    vec3 glow = (tap(pick(vec2(0.3, 0.5))) + tap(pick(vec2(0.7, 0.5))) + tap(pick(vec2(0.5, 0.3))) + tap(pick(vec2(0.5, 0.7)))) * 0.25;
    // 1. The curved screen.
    float R = 1.6; float zc = 1.0 - R;
    float a = d.x * d.x + d.z * d.z;
    float b = 2.0 * (o.x * d.x - d.z * zc);
    float cc = o.x * o.x + zc * zc - R * R;
    float disc = b * b - 4.0 * a * cc;
    if (disc > 0.0) {
        float t = (-b + sqrt(disc)) / (2.0 * a);
        vec3 p = o + d * t;
        float theta = atan(p.x, p.z - zc);
        float u = (theta * R) / (2.0 * uHalfW) + 0.5;
        float v = p.y / (2.0 * uHalfH) + 0.5;
        if (t > 0.0 && u >= 0.0 && u <= 1.0 && v >= 0.0 && v <= 1.0) {
            vec2 q = vec2(u, v);
            vec3 s = tap(pick(q));
            if (uSharp > 0.0) {
                vec3 bl = (tap(pick(q + vec2(uTexel.x, 0.0))) + tap(pick(q - vec2(uTexel.x, 0.0))) + tap(pick(q + vec2(0.0, uTexel.y))) + tap(pick(q - vec2(0.0, uTexel.y)))) * 0.25;
                s = clamp(s + (s - bl) * uSharp, 0.0, 1.0);
            }
            float edge = smoothstep(0.0, 0.01, min(min(u, 1.0 - u), min(v, 1.0 - v)));
            best = t; col = s * edge;
        }
    }
    float floorY = -uHalfH - 0.32;
    // 2. Seats: four rows of seat backs in front of you (dark red), lit by the screen and the lamps.
    for (int k = 0; k < 4; k++) {
        float zr = 0.22 + float(k) * 0.16;
        if (d.z > 0.001) {
            float t = (zr - o.z) / d.z;
            vec3 p = o + d * t;
            float top = floorY + 0.28 + float(k) * 0.03;
            float fx = fract((p.x + float(k) * 0.03) / 0.11);
            float back = step(0.07, fx) * step(fx, 0.93);
            float rnd = smoothstep(top, top - 0.02, p.y + 0.012 * pow(abs(fx - 0.5) * 2.0, 2.0));
            if (t > 0.0 && t < best && p.y < top && p.y > floorY && back * rnd > 0.5) {
                best = t;
                float lit = 0.10 + 0.55 * dot(glow, vec3(0.33)) + 0.25 * uLamps;
                col = vec3(0.30, 0.03, 0.04) * lit * (0.75 + 0.25 * (1.0 - fx));
            }
        }
    }
    // 3. Side walls; their warm lamps sit just beyond the screen's edges, so you see them glow when you pause.
    float thetaS = uHalfW / R;
    for (int sgn = 0; sgn < 2; sgn++) {
        float wx = sgn == 0 ? -1.6 : 1.6;
        if (abs(d.x) > 0.001) {
            float t = (wx - o.x) / d.x;
            vec3 p = o + d * t;
            if (t > 0.0 && t < best && p.y > floorY && p.y < 1.0) {
                best = t;
                float z1 = 1.6 / tan(thetaS + 0.2);
                float z2 = 1.6 / tan(thetaS + 0.6);
                float l = lamp(p, vec3(wx, 0.22, z1)) + lamp(p, vec3(wx, 0.22, z2)) + lamp(p, vec3(wx, 0.22, z2 - 0.6));
                vec3 warm = vec3(1.0, 0.62, 0.30);
                col = vec3(0.025, 0.02, 0.03) + glow * 0.05 + warm * l * (0.2 + 0.8 * uLamps) + warm * 0.04 * uLamps;
            }
        }
    }
    // 4. Floor.
    if (d.y < -0.001) {
        float t = (floorY - o.y) / d.y;
        vec3 p = o + d * t;
        if (t > 0.0 && t < best) {
            best = t;
            col = vec3(0.02, 0.012, 0.015) + glow * 0.04 + vec3(0.06, 0.035, 0.02) * uLamps * smoothstep(0.9, 1.45, abs(p.x));
        }
    }
    gl_FragColor = vec4(col, 1.0);
}
"""
    }
}
