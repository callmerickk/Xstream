package com.example.streamnest

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.background
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

/**
 * Xstream Live · VR headset (JioDive and other phone headsets): the film on a big screen in front of you, one picture per
 * lens, each corrected for the lens (barrel distortion) so the edges look straight through the glass.
 * Tap = play / pause · double-tap or Back = leave VR. Media keys of a Bluetooth remote keep working.
 */
@Composable
fun VrHeadsetView(player: Player, onExit: () -> Unit) {
    Dialog(onDismissRequest = onExit, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        val view = LocalView.current
        LaunchedEffect(Unit) {
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
                WindowCompat.getInsetsController(w, w.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            ActLog.add("PLAY", "VR headset mode on")
        }
        var hint by remember { mutableStateOf(true) }
        var poke by remember { mutableStateOf(0) }
        LaunchedEffect(poke) { hint = true; delay(3500); hint = false }
        Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTapGestures(onTap = { if (player.isPlaying) player.pause() else player.play(); poke++ }, onDoubleTap = { onExit() })
        }) {
            AndroidView(factory = { c -> VrGlView(c, player) }, onRelease = { it.release() }, modifier = Modifier.fillMaxSize())
            // The same note in front of each eye.
            if (hint) Row(Modifier.fillMaxSize()) {
                repeat(2) {
                    Box(Modifier.weight(1f).fillMaxHeight(), Alignment.BottomCenter) {
                        Text(if (player.isPlaying) "Tap: pause · Double-tap: leave VR" else "Paused · Tap to play · Double-tap: leave VR",
                            Modifier.padding(bottom = 54.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xAA000000)).padding(horizontal = 10.dp, vertical = 5.dp),
                            fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }
    }
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
        var sx = 0.78f; var sy = sx * eyeAspect / videoAspect
        if (sy > 0.78f) { sy = 0.78f; sx = sy * videoAspect / eyeAspect }
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
