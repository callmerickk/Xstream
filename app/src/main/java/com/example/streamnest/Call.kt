package com.example.streamnest

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Watch together voice + video: WebRTC straight between the phones (the room only passes the handshake). Mic and camera
 * start off. Every pair connects once both are in the room; tracks are switched on / off without reconnecting.
 * A relay (TURN) set in Developer's Corner helps phones on mobile networks that can't reach each other directly.
 */
object Call {
    val micOn = MutableStateFlow(false)
    val camOn = MutableStateFlow(false)
    val hearOn = MutableStateFlow(true)
    val duckOn = MutableStateFlow(true)
    val bubblesOn = MutableStateFlow(true)
    val frontCam = MutableStateFlow(true)
    val bubbleDp = MutableStateFlow(96)
    /** uid → their video (when their camera sends). */
    val videos = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    /** Who is talking now (their voice is coming in). */
    val talking = MutableStateFlow<Set<String>>(emptySet())
    /** "Frame a friend": whose framing window is open. */
    val framing = MutableStateFlow<String?>(null)

    private val exec = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + exec.asCoroutineDispatcher())
    private var app: Context? = null
    private var factory: PeerConnectionFactory? = null
    private var egl: EglBase? = null
    private var audioSrc: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var texHelper: SurfaceTextureHelper? = null
    private var videoSrc: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var statsJob: Job? = null
    private var adm: JavaAudioDeviceModule? = null
    private val peers = HashMap<String, Peer>()

    private class Peer(val uid: String, val pc: PeerConnection, val offerer: Boolean, var cid: String = java.util.UUID.randomUUID().toString().take(8)) {
        var audioT: RtpTransceiver? = null
        var videoT: RtpTransceiver? = null
        var remoteAudio: AudioTrack? = null
        var remoteSet = false
        val pendingIce = mutableListOf<IceCandidate>()
        val made = System.currentTimeMillis()
    }

    fun init(ctx: Context) {
        if (app == null) app = ctx.applicationContext
        val p = ctx.getSharedPreferences("call", Context.MODE_PRIVATE)
        hearOn.value = p.getBoolean("hear", true); duckOn.value = p.getBoolean("duck", true)
        bubblesOn.value = p.getBoolean("bubbles", true); bubbleDp.value = p.getInt("size", 96)
    }

    private fun save(k: String, v: Any) {
        val e = app?.getSharedPreferences("call", Context.MODE_PRIVATE)?.edit() ?: return
        when (v) { is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v) }; e.apply()
    }

    fun eglContext(): EglBase.Context? = egl?.eglBaseContext

    /** Created on first use (only phones in a room with Xstream 0.9.41+ load WebRTC). */
    private fun ensureFactory(): PeerConnectionFactory? {
        factory?.let { return it }
        val ctx = app ?: return null
        return runCatching {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
            val e = EglBase.create(); egl = e
            val adm0 = JavaAudioDeviceModule.builder(ctx)
                .setUseHardwareAcousticEchoCanceler(true).setUseHardwareNoiseSuppressor(true)
                // Friends' voices play like the movie does (loudspeaker / headphones), not like a phone call (earpiece).
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .createAudioDeviceModule()
            adm0.setMicrophoneMute(!micOn.value); adm = adm0
            PeerConnectionFactory.builder().setAudioDeviceModule(adm0)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(e.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(e.eglBaseContext))
                .createPeerConnectionFactory().also { factory = it }
        }.onFailure { android.util.Log.w("Xstream", "call: ${it.message}") }.getOrNull()
    }

    private fun iceServers(): List<PeerConnection.IceServer> {
        val l = mutableListOf(PeerConnection.IceServer.builder(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")).createIceServer())
        val (u, n, pw) = AppLook.turn.value
        if (u.isNotBlank()) l += PeerConnection.IceServer.builder(u.split(",").map { it.trim() }.filter { it.isNotEmpty() }).setUsername(n).setPassword(pw).createIceServer()
        return l
    }

    // ── Room events ──

    private var room: String? = null
    private var sigJob: Job? = null

    fun onRoom(code: String) {
        scope.launch {
            room = code
            sigJob?.cancel()
            sigJob = scope.launch { Together.sig.collect { (from, msg) -> runCatching { onSignal(from, msg) } } }
            if (statsJob == null) statsJob = scope.launch { statsLoop() }
        }
    }

    /** Everyone present (Xstream with voice/video): connect to them; people who left: hang up. */
    fun onMembers(m: Map<String, RoomMember>) {
        scope.launch {
            if (room == null) return@launch
            val me = Together.me
            val now = Together.serverNow()
            val here = m.filter { (uid, v) -> uid != me && v.rtc && now - v.seen < 40_000 }.keys
            peers.keys.filter { it !in here }.forEach { close(it) }
            here.forEach { uid ->
                val p = peers[uid]
                // Only the one with the smaller id calls, so two calls never cross. A call that didn't connect is retried.
                val stuck = p != null && p.pc.iceConnectionState().let { it == PeerConnection.IceConnectionState.FAILED || it == PeerConnection.IceConnectionState.CLOSED } &&
                    System.currentTimeMillis() - p.made > 8_000
                if (me < uid && (p == null || stuck)) { if (stuck) close(uid); offer(uid) }
            }
            refreshVideos()
        }
    }

    private fun newPeer(uid: String, offerer: Boolean): Peer? {
        val f = ensureFactory() ?: return null
        val cfg = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        var peer: Peer? = null
        val pc = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                Together.signal(uid, buildJsonObject { put("t", "ice"); put("m", c.sdpMid ?: ""); put("i", c.sdpMLineIndex); put("c", c.sdp) })
            }
            override fun onTrack(t: RtpTransceiver) {
                scope.launch {
                    val tr = t.receiver.track()
                    if (tr is AudioTrack) { peer?.remoteAudio = tr; tr.setVolume(if (hearOn.value) 1.0 else 0.0) }
                    refreshVideos()
                }
            }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { scope.launch { refreshVideos() } }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
        }) ?: return null
        peer = Peer(uid, pc, offerer)
        peers[uid] = peer
        return peer
    }

    /** Mic / camera off = this phone only receives (the mic isn't opened at all); on = sends too. */
    private fun dir(on: Boolean) = if (on) RtpTransceiver.RtpTransceiverDirection.SEND_RECV else RtpTransceiver.RtpTransceiverDirection.RECV_ONLY

    private fun attachTracks(p: Peer) {
        runCatching { p.audioT?.direction = dir(micOn.value && audioTrack != null) }
        runCatching { p.videoT?.direction = dir(camOn.value && videoTrack != null) }
        p.audioT?.sender?.setTrack(if (micOn.value) audioTrack else null, false)
        p.videoT?.sender?.let { s ->
            s.setTrack(if (camOn.value) videoTrack else null, false)
            runCatching { val prm = s.parameters; prm.encodings.forEach { it.maxBitrateBps = 450_000; it.maxFramerate = 24 }; s.parameters = prm }
        }
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    private fun offer(uid: String) {
        val p = newPeer(uid, true) ?: return
        p.audioT = p.pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(dir(micOn.value && audioTrack != null)))
        p.videoT = p.pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(dir(camOn.value && videoTrack != null)))
        attachTracks(p)
        sendOffer(p)
    }

    private fun sendOffer(p: Peer) {
        p.pc.createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription) {
                scope.launch {
                    p.pc.setLocalDescription(Sdp(), d)
                    Together.signal(p.uid, buildJsonObject { put("t", "offer"); put("sdp", d.description); put("c", p.cid) })
                }
            }
        }, MediaConstraints())
    }

    /** Mic or camera switched: the call is updated (the caller sends a new offer; the other side asks it to). */
    private fun renegotiate() {
        peers.values.forEach { p ->
            attachTracks(p)
            if (p.offerer) { if (p.pc.signalingState() == PeerConnection.SignalingState.STABLE) sendOffer(p) }
            else Together.signal(p.uid, buildJsonObject { put("t", "reneg") })
        }
    }

    private fun onSignal(from: String, msg: JsonObject) {
        when (msg["t"]?.jsonPrimitive?.contentOrNull) {
            "reneg" -> { peers[from]?.takeIf { it.offerer && it.pc.signalingState() == PeerConnection.SignalingState.STABLE }?.let { attachTracks(it); sendOffer(it) } }
            "offer" -> {
                val cid = msg["c"]?.jsonPrimitive?.contentOrNull ?: ""
                val old = peers[from]
                // Same call, updated (mic / camera switched on the other side) → answer on it. Else a new call.
                val p = if (old != null && !old.offerer && old.cid == cid && cid.isNotEmpty()) old
                    else { close(from); newPeer(from, false)?.also { it.cid = cid } ?: return }
                val sdp = SessionDescription(SessionDescription.Type.OFFER, msg["sdp"]?.jsonPrimitive?.contentOrNull ?: return)
                p.pc.setRemoteDescription(object : Sdp() {
                    override fun onSetSuccess() {
                        scope.launch {
                            p.remoteSet = true
                            p.pc.transceivers.forEach { t ->
                                if (t.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) p.audioT = t else p.videoT = t
                            }
                            attachTracks(p)
                            p.pendingIce.forEach { p.pc.addIceCandidate(it) }; p.pendingIce.clear()
                            p.pc.createAnswer(object : Sdp() {
                                override fun onCreateSuccess(d: SessionDescription) {
                                    scope.launch {
                                        p.pc.setLocalDescription(Sdp(), d)
                                        Together.signal(from, buildJsonObject { put("t", "answer"); put("sdp", d.description) })
                                    }
                                }
                            }, MediaConstraints())
                        }
                    }
                }, sdp)
            }
            "answer" -> {
                val p = peers[from] ?: return
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, msg["sdp"]?.jsonPrimitive?.contentOrNull ?: return)
                p.pc.setRemoteDescription(object : Sdp() {
                    override fun onSetSuccess() { scope.launch { p.remoteSet = true; p.pendingIce.forEach { p.pc.addIceCandidate(it) }; p.pendingIce.clear() } }
                }, sdp)
            }
            "ice" -> {
                val p = peers[from] ?: return
                val c = IceCandidate(msg["m"]?.jsonPrimitive?.contentOrNull ?: "", msg["i"]?.jsonPrimitive?.intOrNull ?: 0, msg["c"]?.jsonPrimitive?.contentOrNull ?: return)
                if (p.remoteSet) p.pc.addIceCandidate(c) else p.pendingIce += c
            }
        }
    }

    private fun close(uid: String) {
        val p = peers.remove(uid) ?: return
        runCatching { p.pc.dispose() }
        refreshVideos()
    }

    private fun refreshVideos() {
        val m = HashMap<String, VideoTrack>()
        peers.values.forEach { p -> (p.videoT?.receiver?.track() as? VideoTrack)?.let { m[p.uid] = it } }
        videos.value = m
    }

    /** Who is talking: the incoming voice level, checked 3 times a second. */
    private suspend fun statsLoop() {
        while (scope.isActive) {
            delay(330)
            if (peers.isEmpty()) { if (talking.value.isNotEmpty()) talking.value = emptySet(); continue }
            val now = HashSet<String>()
            val latch = CountDownLatch(peers.size)
            peers.values.toList().forEach { p ->
                runCatching {
                    p.pc.getStats { rep ->
                        val lvl = rep.statsMap.values.filter { it.type == "inbound-rtp" && it.members["kind"] == "audio" }
                            .maxOfOrNull { (it.members["audioLevel"] as? Number)?.toDouble() ?: 0.0 } ?: 0.0
                        if (lvl > 0.04) synchronized(now) { now += p.uid }
                        latch.countDown()
                    }
                }.onFailure { latch.countDown() }
            }
            runCatching { latch.await(1, java.util.concurrent.TimeUnit.SECONDS) }
            if (now != talking.value) talking.value = now
        }
    }

    // ── This phone's mic / camera ──

    fun setMic(on: Boolean) {
        micOn.value = on
        scope.launch {
            if (on && audioTrack == null) {
                val f = ensureFactory() ?: return@launch
                audioSrc = f.createAudioSource(MediaConstraints())
                audioTrack = f.createAudioTrack("mic", audioSrc)
            }
            audioTrack?.setEnabled(on)
            adm?.setMicrophoneMute(!on)
            renegotiate()
            Together.announce()
        }
    }

    fun setCam(on: Boolean) {
        camOn.value = on
        scope.launch {
            if (on) startCamera() else stopCamera()
            renegotiate()
            Together.announce()
        }
    }

    private fun startCamera() {
        if (videoTrack != null) return
        val ctx = app ?: return
        val f = ensureFactory() ?: return
        val en = if (Camera2Enumerator.isSupported(ctx)) Camera2Enumerator(ctx) else Camera1Enumerator(false)
        val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) == frontCam.value } ?: en.deviceNames.firstOrNull() ?: return
        val cap = en.createCapturer(name, null) ?: return
        val helper = SurfaceTextureHelper.create("cam", egl?.eglBaseContext)
        val src = f.createVideoSource(false)
        cap.initialize(helper, ctx, src.capturerObserver)
        cap.startCapture(640, 480, 24)
        capturer = cap; texHelper = helper; videoSrc = src
        videoTrack = f.createVideoTrack("cam", src)
    }

    private fun stopCamera() {
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }; capturer = null
        peers.values.forEach { it.videoT?.sender?.setTrack(null, false) }
        runCatching { videoTrack?.dispose() }; videoTrack = null
        runCatching { videoSrc?.dispose() }; videoSrc = null
        runCatching { texHelper?.dispose() }; texHelper = null
    }

    fun flip() {
        frontCam.value = !frontCam.value
        scope.launch { capturer?.switchCamera(null) }
    }

    fun setHear(on: Boolean) {
        hearOn.value = on; save("hear", on)
        scope.launch { peers.values.forEach { it.remoteAudio?.setVolume(if (on) 1.0 else 0.0) } }
    }
    fun setDuck(on: Boolean) { duckOn.value = on; save("duck", on) }
    fun setBubbles(on: Boolean) { bubblesOn.value = on; save("bubbles", on) }
    fun setSize(dp: Int) { bubbleDp.value = dp.coerceIn(64, 170); save("size", bubbleDp.value) }

    /** Left the room: hang up and turn the mic and camera off. */
    fun stop() {
        micOn.value = false; camOn.value = false
        scope.launch {
            sigJob?.cancel(); sigJob = null
            statsJob?.cancel(); statsJob = null
            room = null
            peers.keys.toList().forEach { close(it) }
            stopCamera()
            runCatching { audioTrack?.dispose() }; audioTrack = null
            runCatching { audioSrc?.dispose() }; audioSrc = null
            talking.value = emptySet(); videos.value = emptyMap()
        }
    }
}

// ───────────── Framing: which part of a friend's camera fills their circle ─────────────

/** Centre ([fx], [fy] as 0…1 of the picture) and the circle's size [r] (share of the picture's height). */
data class Frame(val fx: Float = 0.5f, val fy: Float = 0.42f, val r: Float = 0.9f) {
    fun clamp(aspect: Float): Frame {
        val rr = r.coerceIn(0.22f, minOf(1f, aspect))
        val mx = rr / (2 * aspect); val my = rr / 2
        return Frame(fx.coerceIn(mx, 1 - mx), fy.coerceIn(my, 1 - my), rr)
    }
}

object Frames {
    val all = MutableStateFlow<Map<String, Frame>>(emptyMap())
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("call_frames", Context.MODE_PRIVATE)
    fun load(ctx: Context) {
        all.value = prefs(ctx).all.mapNotNull { (k, v) -> (v as? String)?.split(",")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 3 }?.let { k to Frame(it[0], it[1], it[2]) } }.toMap()
    }
    fun of(uid: String) = all.value[uid] ?: Frame()
    fun set(ctx: Context, uid: String, f: Frame) {
        all.value = all.value + (uid to f)
        prefs(ctx).edit().putString(uid, "${f.fx},${f.fy},${f.r}").apply()
    }
}

/** A friend's camera on a TextureView (so it can be cut into a circle and moved / zoomed). */
class RemoteView(ctx: Context, private val onSize: (Int, Int) -> Unit) : TextureView(ctx), TextureView.SurfaceTextureListener, VideoSink {
    private val renderer = EglRenderer("bubble")
    private var track: VideoTrack? = null
    private var w = 0
    private var h = 0
    private var ready = false

    init {
        surfaceTextureListener = this
        Call.eglContext()?.let { renderer.init(it, EglBase.CONFIG_PLAIN, GlRectDrawer()); ready = true }
        isOpaque = false
    }

    fun bind(t: VideoTrack?) {
        if (t === track) return
        runCatching { track?.removeSink(this) }
        track = t
        runCatching { t?.addSink(this) }
    }

    override fun onFrame(f: VideoFrame) {
        val rw = f.rotatedWidth; val rh = f.rotatedHeight
        if (rw != w || rh != h) { w = rw; h = rh; post { onSize(rw, rh) } }
        if (ready) renderer.onFrame(f)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) { if (ready) renderer.createEglSurface(st) }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        if (ready) { val l = CountDownLatch(1); renderer.releaseEglSurface { l.countDown() }; runCatching { l.await(1, java.util.concurrent.TimeUnit.SECONDS) } }
        return true
    }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    fun release() { runCatching { track?.removeSink(this) }; track = null; if (ready) runCatching { renderer.release() }; ready = false }
}

/** A friend's camera framed into a circle of [sizeDp]. */
@Composable
fun FramedVideo(uid: String, track: VideoTrack, sizeDp: androidx.compose.ui.unit.Dp, frame: Frame, onView: (RemoteView) -> Unit = {}) {
    var aspect by remember { mutableFloatStateOf(4f / 3f) }
    val f = frame.clamp(aspect)
    val d = LocalDensity.current
    val s = with(d) { sizeDp.toPx() }
    val hPx = s / f.r
    val wPx = hPx * aspect
    Box(Modifier.size(sizeDp).clip(CircleShape).background(Color(0xFF1A1B22))) {
        AndroidView(
            factory = { RemoteView(it) { w, h -> if (w > 0 && h > 0) aspect = w.toFloat() / h }.also(onView) },
            update = { it.bind(track) },
            onRelease = { it.release() },
            modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                .offset { IntOffset((s / 2 - f.fx * wPx).roundToInt(), (s / 2 - f.fy * hPx).roundToInt()) }
                .requiredSize(with(d) { wPx.toDp() }, with(d) { hPx.toDp() }),
        )
    }
}

/**
 * Friends' bubbles on the player: their camera in a circle (or their initial when the camera is off and they talk /
 * have the mic on). Drag to move — it snaps to the nearest corner; double-tap = bigger / smaller; tap = Watch together.
 */
@Composable
fun CallBubbles(modifier: Modifier, onTap: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val members by Together.members.collectAsState()
    val videos by Call.videos.collectAsState()
    val talking by Call.talking.collectAsState()
    val show by Call.bubblesOn.collectAsState()
    val sizeDp by Call.bubbleDp.collectAsState()
    val frames by Frames.all.collectAsState()
    LaunchedEffect(Unit) { Frames.load(ctx) }
    if (!show) return
    val me = Together.me
    val now = Together.serverNow()
    val people = members.filter { (uid, m) -> uid != me && now - m.seen < 40_000 && (m.cam || m.mic) }.toList()
    if (people.isEmpty()) return
    var corner by remember { mutableStateOf(ctx.getSharedPreferences("call", Context.MODE_PRIVATE).getString("corner", "bl") ?: "bl") }
    var drag by remember { mutableStateOf(Offset.Zero) }
    var boxW by remember { mutableIntStateOf(0) }
    var boxH by remember { mutableIntStateOf(0) }
    val size by animateDpAsState(sizeDp.dp, tween(220), label = "bubble")
    val d = LocalDensity.current
    BoxWithConstraints(modifier.onSizeChanged { boxW = it.width; boxH = it.height }) {
        val alignment = when (corner) { "tl" -> Alignment.TopStart; "tr" -> Alignment.TopEnd; "br" -> Alignment.BottomEnd; else -> Alignment.BottomStart }
        val stack = if (corner.startsWith("t")) Arrangement.spacedBy(10.dp, Alignment.Top) else Arrangement.spacedBy(10.dp, Alignment.Bottom)
        Column(
            Modifier.align(alignment).padding(horizontal = 18.dp, vertical = 64.dp)
                .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
                .pointerInput(corner) {
                    detectDragGestures(
                        onDragEnd = {
                            // Snap to the nearest corner (never left over the subtitles in the middle).
                            val cx = (if (corner.endsWith("l")) 0f else boxW.toFloat()) + drag.x
                            val cy = (if (corner.startsWith("t")) 0f else boxH.toFloat()) + drag.y
                            corner = (if (cy < boxH / 2f) "t" else "b") + (if (cx < boxW / 2f) "l" else "r")
                            drag = Offset.Zero
                            ctx.getSharedPreferences("call", Context.MODE_PRIVATE).edit().putString("corner", corner).apply()
                        },
                        onDragCancel = { drag = Offset.Zero },
                    ) { ch, amount -> ch.consume(); drag += amount }
                },
            verticalArrangement = stack,
            horizontalAlignment = if (corner.endsWith("l")) Alignment.Start else Alignment.End,
        ) {
            people.forEach { (uid, m) ->
                val tk = videos[uid]
                val speaking = uid in talking
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(size).shadow(14.dp, CircleShape).clip(CircleShape)
                            .border(if (speaking) 3.dp else 2.dp, if (speaking) Color(0xFF3CCF7A) else Color(0xE6FFFFFF), CircleShape)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { onTap() },
                                    onDoubleTap = { val l = listOf(80, 110, 150); Call.setSize(l.firstOrNull { it > Call.bubbleDp.value } ?: l[0]) },
                                )
                            },
                        Alignment.Center,
                    ) {
                        if (m.cam && tk != null) FramedVideo(uid, tk, size, frames[uid] ?: Frame())
                        else Box(Modifier.fillMaxSize().background(Color(0xFF3C3F58)), Alignment.Center) {
                            Text(m.name.take(1).uppercase(), fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(m.name.ifBlank { "Friend" }, Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 7.dp, vertical = 1.dp),
                        fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

/** "Frame a friend": their whole camera with a circle on it — drag / pinch / arrows; ◎ follows their face. */
@Composable
fun FrameDialog(uid: String, onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val videos by Call.videos.collectAsState()
    val members by Together.members.collectAsState()
    val track = videos[uid]
    var aspect by remember { mutableFloatStateOf(4f / 3f) }
    var f by remember { mutableStateOf(Frames.of(uid)) }
    var auto by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<RemoteView?>(null) }
    fun upd(n: Frame) { f = n.clamp(aspect); Frames.set(ctx, uid, f) }
    // ◎ Keep the face in the circle: a small snapshot each 0.7 s, Android's own face finder, then a smooth move.
    LaunchedEffect(auto, view) {
        val v = view ?: return@LaunchedEffect
        while (auto && isActive) {
            delay(700)
            val bmp = runCatching { v.getBitmap(240, (240 / aspect).roundToInt().let { it - it % 2 }) }.getOrNull() ?: continue
            val face = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    val b565 = bmp.copy(android.graphics.Bitmap.Config.RGB_565, false)
                    val out = arrayOfNulls<android.media.FaceDetector.Face>(1)
                    val n = android.media.FaceDetector(b565.width, b565.height, 1).findFaces(b565, out)
                    if (n > 0) out[0]?.let { fc -> val p = android.graphics.PointF(); fc.getMidPoint(p); Triple(p.x / b565.width, p.y / b565.height, fc.eyesDistance() / b565.height) } else null
                }.getOrNull()
            }
            if (face != null) upd(Frame(f.fx + (face.first - f.fx) * 0.5f, f.fy + (face.second + face.third * 0.35f - f.fy) * 0.5f, f.r))
        }
    }
    Dialog(onDismissRequest = onDone) {
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xF5111216)).border(1.dp, Color(0x47E50914), RoundedCornerShape(24.dp)).padding(18.dp)) {
            Text("Frame ${members[uid]?.name?.ifBlank { null } ?: "your friend"}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Drag the circle onto the face, or use the arrows. Pinch or ± to zoom.", Modifier.padding(top = 4.dp, bottom = 12.dp), fontSize = 12.sp, color = Color(0xFF9AA3B5))
            if (track == null) Text("Their camera is off.", fontSize = 13.sp, color = Color(0xFFD5D9E2))
            else {
                val d = LocalDensity.current
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val wDp = maxWidth
                    val hDp = wDp / aspect
                    val wPx = with(d) { wDp.toPx() }; val hPx = with(d) { hDp.toPx() }
                    Box(Modifier.size(wDp, hDp).clip(RoundedCornerShape(12.dp)).pointerInput(aspect) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            auto = false
                            upd(Frame(f.fx + pan.x / wPx, f.fy + pan.y / hPx, f.r / zoom))
                        }
                    }) {
                        AndroidView(factory = { RemoteView(it) { w, h -> if (w > 0 && h > 0) aspect = w.toFloat() / h }.also { v -> view = v } },
                            update = { it.bind(track) }, onRelease = { it.release(); view = null }, modifier = Modifier.fillMaxSize())
                        val dia = f.r * hPx
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val c = Offset(f.fx * wPx, f.fy * hPx)
                            val path = androidx.compose.ui.graphics.Path().apply {
                                addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                                addOval(androidx.compose.ui.geometry.Rect(c, dia / 2))
                                fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                            }
                            drawPath(path, Color(0x8C000000))
                            drawCircle(Color.White, dia / 2, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    @Composable fun padKey(t: String, desc: String, on: Boolean = false, act: () -> Unit) =
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Color(0xFF3CCF7A) else Color(0xFF262730))
                            .pointerInput(t) { detectTapGestures { act() } }, Alignment.Center) { Text(t, fontSize = 15.sp) }
                    padKey("◀", "Left") { auto = false; upd(f.copy(fx = f.fx - 0.04f)) }
                    padKey("▲", "Up") { auto = false; upd(f.copy(fy = f.fy - 0.04f)) }
                    padKey("▼", "Down") { auto = false; upd(f.copy(fy = f.fy + 0.04f)) }
                    padKey("▶", "Right") { auto = false; upd(f.copy(fx = f.fx + 0.04f)) }
                    padKey("−", "Zoom out") { upd(f.copy(r = f.r * 1.12f)) }
                    padKey("+", "Zoom in") { upd(f.copy(r = f.r * 0.88f)) }
                    padKey("◎", "Keep face centred", auto) { auto = !auto }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) { TextButton(onDone) { Text("Done") } }
        }
    }
}

/** The movie gets quieter while a friend talks (Watch together › Audio). */
fun duckTarget(): Float = if (Together.active() && Call.duckOn.value && Call.hearOn.value && Call.talking.value.isNotEmpty()) 0.35f else 1f

@Suppress("unused") private fun Float.near(o: Float) = abs(this - o) < 0.01f
