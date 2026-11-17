package com.example.streamnest

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
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
}

private data class VrHit(val id: String, val x: Float, val y: Float, val w: Float, val h: Float) {
    fun has(px: Float, py: Float) = px in x..(x + w) && py in y..(y + h)
}

/**
 * Xstream Live · VR headset (JioDive and other phone headsets): the film on a big screen in front of you, one picture per
 * lens, each corrected for the lens. The headset's button (it taps the screen) opens the controls: the gyro switches on and
 * a dot in the middle of your view is the pointer — turn your head to put it on a control, press the button to click.
 * Pressing with the dot on empty space closes the controls, the gyro switches off and the big screen stays.
 */
@Composable
fun VrHeadsetView(
    vm: AppViewModel, player: Player, title: String,
    onSeekTo: (Long) -> Unit, onNext: (() -> Unit)?, onExit: () -> Unit,
) {
    Dialog(onDismissRequest = onExit, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        val view = LocalView.current
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val window = (view.parent as? DialogWindowProvider)?.window
        LaunchedEffect(Unit) {
            window?.let { w ->
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.getInsetsController(w, w.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            ActLog.add("PLAY", "VR headset mode on")
        }
        var glView by remember { mutableStateOf<VrGlView?>(null) }
        var controls by remember { mutableStateOf(false) }
        var browse by remember { mutableStateOf(false) }
        var yaw by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }     // + = looking left (radians)
        var pitch by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }   // + = looking up
        var sizeIdx by remember { androidx.compose.runtime.mutableIntStateOf(1) }
        var subsOn by remember { mutableStateOf(true) }
        var cue by remember { mutableStateOf("") }
        var tick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        var hint by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { delay(5_000); hint = false }
        val hits = remember { mutableListOf<VrHit>() }
        val sizes = listOf("Small" to 0.6f, "Big" to 0.78f, "IMAX" to 0.95f)
        LaunchedEffect(sizeIdx, glView) { glView?.setScreen(sizes[sizeIdx].second) }
        // Subtitles for both eyes (the normal subtitle layer isn't drawn in VR).
        androidx.compose.runtime.DisposableEffect(player) {
            val l = object : Player.Listener {
                override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) { cue = cueGroup.cues.joinToString("\n") { it.text?.toString().orEmpty() }.trim() }
            }
            player.addListener(l); onDispose { player.removeListener(l) }
        }
        // Gyro only while the controls are open.
        androidx.compose.runtime.DisposableEffect(controls) {
            if (!controls) return@DisposableEffect onDispose { }
            yaw = 0f; pitch = 0f
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
            val gyro = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)
            @Suppress("DEPRECATION")
            val rot = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            val flip = if (rot == Surface.ROTATION_270) -1f else 1f
            var last = 0L
            val l = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(e: android.hardware.SensorEvent) {
                    if (last != 0L) {
                        val dt = (e.timestamp - last) / 1e9f
                        // Landscape: the phone's X axis points up (turning the head = around X), its Y axis points left (nodding).
                        yaw = (yaw + flip * e.values[0] * dt).coerceIn(-1.2f, 1.2f)
                        pitch = (pitch - flip * e.values[1] * dt).coerceIn(-1.0f, 1.0f)
                    }
                    last = e.timestamp
                }
                override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
            }
            if (gyro != null) sm.registerListener(l, gyro, android.hardware.SensorManager.SENSOR_DELAY_GAME)
            onDispose { sm.unregisterListener(l) }
        }
        LaunchedEffect(controls) { while (controls) { tick++; delay(500) } }
        val density = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val eyeW = maxWidth.value / 2f
            val eyeH = maxHeight.value
            // Pointer travel: about one eye width for 75° of head turn.
            val k = eyeW / 1.3f
            val pW = 320f; val pH = if (browse) 380f else 250f
            val pointerX = pW / 2f - yaw * k
            val pointerY = pH / 2f - pitch * k
            fun click() {
                if (!controls) { controls = true; browse = false; return }
                val h = hits.firstOrNull { it.has(pointerX, pointerY) }
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
                    h.id == "briDown" || h.id == "briUp" -> window?.let { w ->
                        val cur = w.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.6f
                        w.attributes = w.attributes.apply { screenBrightness = (cur + if (h.id == "briUp") 0.1f else -0.1f).coerceIn(0.05f, 1f) }
                    }
                    h.id == "size" -> sizeIdx = (sizeIdx + 1) % sizes.size
                    h.id == "center" -> { yaw = 0f; pitch = 0f }
                    h.id == "subs" -> subsOn = !subsOn
                    h.id == "audio" -> nextAudio(player)
                    h.id == "next" -> onNext?.let { VrState.resume = true; it() }
                    h.id == "browse" -> browse = true
                    h.id == "browseBack" -> browse = false
                    h.id == "exit" -> onExit()
                    h.id.startsWith("t:") -> {
                        val m = browseItems(vm).flatMap { it.second }.firstOrNull { "t:" + it.id == h.id }
                        if (m != null) { VrState.resume = true; VrState.openTitle.value = m }
                    }
                }
                tick++
            }
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { click() }) }) {
                AndroidView(factory = { c -> VrGlView(c, player).also { glView = it } }, onRelease = { it.release() }, modifier = Modifier.fillMaxSize())
                Row(Modifier.fillMaxSize()) {
                    repeat(2) { eye ->
                        // The lens centre sits a little towards the nose (same shift as the film).
                        val cx = eyeW / 2f + (if (eye == 0) 0.02f else -0.02f) * eyeW
                        val cy = eyeH / 2f
                        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                            if (subsOn && cue.isNotBlank()) Text(cue, Modifier.align(Alignment.BottomCenter).padding(bottom = (eyeH * 0.2f).dp, start = 30.dp, end = 30.dp)
                                .clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 12.sp, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            if (controls) {
                                val ox = cx - pointerX; val oy = cy - pointerY
                                Box(Modifier.offset(ox.dp, oy.dp).size(pW.dp, pH.dp)) {
                                    if (eye == 0) hits.clear()
                                    if (browse) BrowsePanel(vm, pointerX, pointerY) { if (eye == 0) hits += it }
                                    else ControlPanel(player, title, tick, sizes[sizeIdx].first, subsOn, onNext != null, pointerX, pointerY) { if (eye == 0) hits += it }
                                }
                                // The pointer: always in the middle of your view.
                                Box(Modifier.offset((cx - 6).dp, (cy - 6).dp).size(12.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(Color.White).border(2.dp, Color(0xFF6A6FF0), androidx.compose.foundation.shape.CircleShape))
                            } else if (hint) Text("Press the headset button for controls", Modifier.align(Alignment.BottomCenter).padding(bottom = (eyeH * 0.12f).dp)
                                .clip(RoundedCornerShape(12.dp)).background(Color(0xAA000000)).padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 11.sp, color = Color.White)
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
private fun VrButton(id: String, label: String, x: Float, y: Float, w: Float, h: Float, px: Float, py: Float, add: (VrHit) -> Unit, accent: Boolean = false) {
    val hit = VrHit(id, x, y, w, h)
    add(hit)
    val over = hit.has(px, py)
    Box(Modifier.offset(x.dp, y.dp).size(w.dp, h.dp).clip(RoundedCornerShape(12.dp))
        .background(if (over) Color(0xFF4B4FD8) else if (accent) Color(0xCCE50914) else Color(0xCC1A1E2E))
        .border(if (over) 2.dp else 1.dp, if (over) Color.White else Color(0x33FFFFFF), RoundedCornerShape(12.dp)), Alignment.Center) {
        Text(label, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun ControlPanel(player: Player, title: String, tick: Int, size: String, subsOn: Boolean, hasNext: Boolean, px: Float, py: Float, add: (VrHit) -> Unit) {
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Color(0xE60F1014)).border(1.dp, Color(0x334B4FD8), RoundedCornerShape(20.dp)))
    Text(title, Modifier.offset(12.dp, 8.dp).width(296.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
    // Timeline: point anywhere on it and press to jump there.
    val dur = player.duration.coerceAtLeast(1); val pos = player.currentPosition
    val bar = VrHit("bar", 12f, 32f, 296f, 26f); add(bar)
    val overBar = bar.has(px, py)
    Box(Modifier.offset(12.dp, 43.dp).size(296.dp, if (overBar) 6.dp else 4.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x40FFFFFF))) {
        Box(Modifier.fillMaxHeight().width((296f * pos / dur).coerceIn(0f, 296f).dp).background(Color(0xFFE50914)))
    }
    if (overBar) Box(Modifier.offset((px - 1).dp, 36.dp).size(2.dp, 18.dp).background(Color.White))
    Text(if (overBar) "Jump to ${fmt((dur * ((px - 12f) / 296f).coerceIn(0f, 1f)).toLong())}" else "${fmt(pos)} / ${fmt(dur)}",
        Modifier.offset(12.dp, 58.dp), fontSize = 10.sp, color = Color(0xFFB8BED3))
    val bw = 56f; val gap = 8f
    fun x(i: Int) = 12f + i * (bw + gap) - 4f
    VrButton("back", "−10 s", x(0), 80f, bw, 44f, px, py, add)
    VrButton("play", if (player.isPlaying) "Pause" else "Play", x(1), 80f, bw, 44f, px, py, add, accent = true)
    VrButton("fwd", "+10 s", x(2), 80f, bw, 44f, px, py, add)
    VrButton("volDown", "Vol −", x(3), 80f, bw, 44f, px, py, add)
    VrButton("volUp", "Vol +", x(4), 80f, bw, 44f, px, py, add)
    VrButton("briDown", "Bright −", x(0), 132f, bw, 44f, px, py, add)
    VrButton("briUp", "Bright +", x(1), 132f, bw, 44f, px, py, add)
    VrButton("size", "Screen\n$size", x(2), 132f, bw, 44f, px, py, add)
    VrButton("audio", "Audio", x(3), 132f, bw, 44f, px, py, add)
    VrButton("subs", if (subsOn) "Subs on" else "Subs off", x(4), 132f, bw, 44f, px, py, add)
    VrButton("center", "Recenter", x(0), 184f, bw, 44f, px, py, add)
    VrButton("browse", "Browse", x(1), 184f, bw, 44f, px, py, add)
    if (hasNext) VrButton("next", "Next ep", x(2), 184f, bw, 44f, px, py, add)
    VrButton("exit", "Leave VR", x(4), 184f, bw, 44f, px, py, add)
}

@Composable
private fun BrowsePanel(vm: AppViewModel, px: Float, py: Float, add: (VrHit) -> Unit) {
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Color(0xE60F1014)).border(1.dp, Color(0x334B4FD8), RoundedCornerShape(20.dp)))
    val rows = remember { browseItems(vm) }
    rows.forEachIndexed { r, (name, items) ->
        val y0 = 8f + r * 112f
        Text(name, Modifier.offset(12.dp, y0.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB8BED3))
        items.forEachIndexed { i, m ->
            val hit = VrHit("t:" + m.id, 12f + i * 60f, y0 + 16f, 54f, 86f); add(hit)
            val over = hit.has(px, py)
            Box(Modifier.offset(hit.x.dp, hit.y.dp).size(54.dp, 86.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1A1E2E))
                .border(if (over) 2.dp else 0.dp, Color.White, RoundedCornerShape(8.dp))) {
                coil.compose.AsyncImage(m.poster, m.name, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            }
            if (over) Text(m.name, Modifier.offset(12.dp, (y0 + 2f).dp).padding(start = 120.dp), fontSize = 11.sp, color = Color.White, maxLines = 1)
        }
    }
    if (rows.isEmpty()) Text("Nothing to show yet", Modifier.offset(12.dp, 20.dp), fontSize = 12.sp, color = Color(0xFFB8BED3))
    VrButton("browseBack", "Back", 12f, 336f, 70f, 36f, px, py, add)
}

private class VrGlView(ctx: Context, private val player: Player) : GLSurfaceView(ctx) {
    private val renderer = VrRenderer(this)
    private val sizeListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) { renderer.setVideo(videoSize) }
    }

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        keepScreenOn = true
        renderer.setVideo(player.videoSize)
        player.addListener(sizeListener)
    }

    /** Called on the GL thread once the video texture exists: the player draws into it from now on. */
    fun attach(surface: Surface) = post { player.setVideoSurface(surface) }

    /** Screen size: share of each eye's width the film fills (Small / Big / IMAX). */
    fun setScreen(f: Float) { renderer.screen = f; requestRender() }

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
    @Volatile var screen = 0.78f
    private var w = 1; private var h = 1

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    fun setVideo(v: VideoSize) { if (v.width > 0 && v.height > 0) videoAspect = v.width * v.pixelWidthHeightRatio / v.height }

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
        // The virtual screen fills ~78 % of each eye's width, keeping the film's shape.
        val half = w / 2
        val eyeAspect = half.toFloat() / h
        var sx = screen; var sy = sx * eyeAspect / videoAspect
        if (sy > screen) { sy = screen; sx = sy * videoAspect / eyeAspect }
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uScreen"), sx, sy)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uAspect"), eyeAspect)
        for (eye in 0..1) {
            GLES20.glViewport(eye * half, 0, half, h)
            // Lens centre a little towards the nose (phone headsets' lenses sit closer together than half the screen).
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uShift"), if (eye == 0) 0.04f else -0.04f)
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
        fun sh(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
        return GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs)); GLES20.glLinkProgram(p)
        }
    }

    private companion object {
        const val VS = """
attribute vec2 aPos; attribute vec2 aUv; varying vec2 vUv;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); vUv = aUv; }
"""
        // Barrel distortion (k1, k2) cancels the headset lenses' pincushion; outside the virtual screen stays black.
        const val FS = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vUv;
uniform samplerExternalOES sTex; uniform mat4 uTex; uniform vec2 uScreen; uniform float uShift; uniform float uAspect;
void main() {
    vec2 c = vUv * 2.0 - 1.0;
    c.x -= uShift;
    vec2 a = vec2(c.x * uAspect, c.y);
    float r2 = dot(a, a) / (1.0 + uAspect * uAspect) * 2.0;
    vec2 d = c * (1.0 + 0.22 * r2 + 0.24 * r2 * r2);
    vec2 v = d / uScreen;
    if (abs(v.x) > 1.0 || abs(v.y) > 1.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    vec2 t = (uTex * vec4(v * 0.5 + 0.5, 0.0, 1.0)).xy;
    float edge = smoothstep(1.0, 0.97, max(abs(v.x), abs(v.y)));
    gl_FragColor = vec4(texture2D(sTex, t).rgb * edge, 1.0);
}
"""
    }
}
