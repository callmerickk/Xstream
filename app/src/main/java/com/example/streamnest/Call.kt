package com.example.streamnest

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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
    /** 0.9.76: the call's state for the room panel ("" = no call with anyone yet). */
    val status = MutableStateFlow("")
    /** 0.9.76: why the camera couldn't start (shown in the room panel), null = fine. */
    val camError = MutableStateFlow<String?>(null)
    // 0.9.8 (wt2): per-friend mute, "You" bubble, my own video for the preview, signal dots, data saver.
    private val _peerMuted = MutableStateFlow<Set<String>>(emptySet())
    val peerMuted: StateFlow<Set<String>> = _peerMuted
    private val _selfView = MutableStateFlow(false)   // 0.9.9: off by default
    val selfView: StateFlow<Boolean> = _selfView
    val localVideo = MutableStateFlow<VideoTrack?>(null)
    /** 0.9.8: uid → 1..3 signal dots (3 = good). */
    val quality = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** 0.9.8: true while my video is lowered (mobile data, or the movie is buffering). */
    val dataSaver = MutableStateFlow(false)
    /** 0.9.8: the player sets this while the movie buffers. */
    val movieBuffering = MutableStateFlow(false)
    private var saverJob: Job? = null

    // 0.9.8: one friend's voice volume: the global switch AND their own mute.
    private fun vol(uid: String): Double = if (hearOn.value && !(Feature.wt2 && uid in _peerMuted.value)) 1.0 else 0.0

    fun setPeerMuted(uid: String, muted: Boolean) {
        if (!Feature.wt2) return
        val n = if (muted) _peerMuted.value + uid else _peerMuted.value - uid
        _peerMuted.value = n
        room?.let { r -> app?.getSharedPreferences("call", Context.MODE_PRIVATE)?.edit()?.putStringSet("muted_$r", n)?.apply() }
        scope.launch { peers[uid]?.remoteAudio?.setVolume(vol(uid)) }
        ActLog.add("ROOM", "call · ${Together.members.value[uid]?.name ?: "friend"}: ${if (muted) "muted" else "unmuted"} (this phone only)")
    }

    // 0.9.9: a new key ("selfView2", default off): 0.9.8's default-on is not carried over.
    fun setSelfView(on: Boolean) { if (!Feature.wt2) return; _selfView.value = on; save("selfView2", on) }

    /** 0.9.76: relay servers (TURN) from our Cloudflare Worker — mobile networks often can't reach each other directly. */
    @Volatile private var relayServers: List<PeerConnection.IceServer> = emptyList()
    @Volatile private var relayUntil = 0L
    private fun fetchRelay() {
        if (System.currentTimeMillis() < relayUntil) return
        runCatching {
            val req = okhttp3.Request.Builder().url("https://streamx-home.callmerick093.workers.dev/turn").header("x-sx", "sx-home-1").build()
            Api.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { ActLog.add("ROOM", "call · relay: not available (${r.code}) — direct only"); relayUntil = System.currentTimeMillis() + 10 * 60_000; return }
                val root = Api.json.parseToJsonElement(r.body?.string().orEmpty())
                val arr = (root as? JsonObject)?.get("iceServers") ?: root
                val list = mutableListOf<PeerConnection.IceServer>()
                fun add(o: JsonObject) {
                    val urls = when (val u = o["urls"]) { is kotlinx.serialization.json.JsonArray -> u.mapNotNull { it.jsonPrimitive.contentOrNull }; null -> emptyList(); else -> listOfNotNull(u.jsonPrimitive.contentOrNull) }
                    if (urls.isEmpty()) return
                    val b = PeerConnection.IceServer.builder(urls)
                    o["username"]?.jsonPrimitive?.contentOrNull?.let { b.setUsername(it) }
                    o["credential"]?.jsonPrimitive?.contentOrNull?.let { b.setPassword(it) }
                    list += b.createIceServer()
                }
                when (arr) { is kotlinx.serialization.json.JsonArray -> arr.forEach { (it as? JsonObject)?.let(::add) }; is JsonObject -> add(arr); else -> {} }
                relayServers = list; relayUntil = System.currentTimeMillis() + 20 * 3600_000
                ActLog.add("ROOM", "call · relay: ready (${list.size} server set${if (list.size == 1) "" else "s"})")
            }
        }.onFailure { ActLog.add("ROOM", "call · relay: couldn't reach (${it.javaClass.simpleName})"); relayUntil = System.currentTimeMillis() + 5 * 60_000 }
    }

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
        var askedLow = false            // 0.9.8: this friend asked me for lighter video
        var qRecv = 0L; var qLost = 0L; var qLoss = 0.0   // 0.9.8: loss window for the signal dots
    }

    fun init(ctx: Context) {
        if (app == null) app = ctx.applicationContext
        val p = ctx.getSharedPreferences("call", Context.MODE_PRIVATE)
        hearOn.value = p.getBoolean("hear", true); duckOn.value = p.getBoolean("duck", true)
        bubblesOn.value = p.getBoolean("bubbles", true); bubbleDp.value = p.getInt("size", 96)
        if (Feature.wt2) _selfView.value = p.getBoolean("selfView2", false)
    }

    private fun save(k: String, v: Any) {
        val e = app?.getSharedPreferences("call", Context.MODE_PRIVATE)?.edit() ?: return
        when (v) { is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v) }; e.apply()
    }

    fun eglContext(): EglBase.Context? = egl?.eglBaseContext

    /** Created on first use (only phones in a room with StreamX 0.9.41+ load WebRTC). */
    private fun ensureFactory(): PeerConnectionFactory? {
        factory?.let { return it }
        val ctx = app ?: return null
        // 0.9.6 (webrtcOnDemand): the native library is downloaded (once, checked) before the first call; none → null →
        // the call's existing "couldn't load" path. Off: lib is null and the 0.9.5 lines below run unchanged.
        val lib: java.io.File? = if (Feature.webrtcOnDemand) (WebRtcLib.fetch(ctx, userStarted = true)
            ?: run { status.value = "Couldn't get Watch together ready — check your internet and try again"; return null }) else null
        return runCatching {
            val init = PeerConnectionFactory.InitializationOptions.builder(ctx)
            // 0.9.6: load OUR checked file instead of System.loadLibrary("jingle_peerconnection_so").
            if (lib != null) init.setNativeLibraryLoader { _ -> System.load(lib.path); true }
            PeerConnectionFactory.initialize(init.createInitializationOptions())
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
        }.onFailure { android.util.Log.w(Brand.NAME, "call: ${it.message}") }.getOrNull()
    }

    private fun iceServers(): List<PeerConnection.IceServer> {
        val l = mutableListOf(PeerConnection.IceServer.builder(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")).createIceServer())
        val (u, n, pw) = AppLook.turn.value
        if (u.isNotBlank()) l += PeerConnection.IceServer.builder(u.split(",").map { it.trim() }.filter { it.isNotEmpty() }).setUsername(n).setPassword(pw).createIceServer()
        l += relayServers
        return l
    }

    // ── Room events ──

    @Volatile private var room: String? = null
    private var sigJob: Job? = null

    fun onRoom(code: String) {
        scope.launch {
            room = code
            status.value = ""
            // 0.9.8: this room's muted friends are remembered.
            if (Feature.wt2) _peerMuted.value = app?.getSharedPreferences("call", Context.MODE_PRIVATE)?.getStringSet("muted_$code", emptySet())?.toSet() ?: emptySet()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fetchRelay() }
            sigJob?.cancel()
            sigJob = scope.launch { Together.sig.collect { (from, msg) -> runCatching { onSignal(from, msg) } } }
            if (statsJob == null) statsJob = scope.launch { statsLoop() }
            if (Feature.wt2) startSaver()
        }
    }

    /** Everyone present (StreamX with voice/video): connect to them; people who left: hang up. */
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
                    if (tr is AudioTrack) { peer?.remoteAudio = tr; tr.setVolume(if (Feature.wt2) vol(uid) else if (hearOn.value) 1.0 else 0.0) }
                    refreshVideos()
                }
            }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
                scope.launch {
                    refreshVideos()
                    // 0.9.76: every friend's call state goes to the log and the room panel.
                    val name = Together.members.value[uid]?.name ?: "friend"
                    ActLog.add("ROOM", "call · $name: ${s.name.lowercase()}")
                    when (s) {
                        PeerConnection.IceConnectionState.CHECKING -> if (status.value != "Connected") status.value = "Connecting call…"
                        PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                            status.value = "Connected"; logRoute(peer)
                            if (Feature.wt2 && dataSaver.value) peer?.let { tellSaver(it) }   // 0.9.8: a new call learns we're saving
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            status.value = if (relayServers.isEmpty()) "Couldn't connect — trying relay" else "Couldn't connect — retrying"
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { relayUntil = 0; fetchRelay() }
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> status.value = "Reconnecting call…"
                        else -> {}
                    }
                }
            }
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
            // 0.9.8: normal or lowered limits per friend; off = the old fixed limits.
            if (Feature.wt2) applySend(p)
            else runCatching { val prm = s.parameters; prm.encodings.forEach { it.maxBitrateBps = 450_000; it.maxFramerate = 24 }; s.parameters = prm }
        }
    }

    /** 0.9.8: my video to this friend: lowered while I save data or they asked, normal otherwise. */
    private fun applySend(p: Peer) {
        val s = p.videoT?.sender ?: return
        runCatching {
            val low = dataSaver.value || p.askedLow
            val prm = s.parameters
            prm.encodings.forEach {
                it.maxBitrateBps = if (low) 120_000 else 450_000
                it.maxFramerate = if (low) 10 else 24
                it.scaleResolutionDownBy = if (low) 2.0 else 1.0
            }
            s.parameters = prm
        }
    }

    private fun tellSaver(p: Peer) {
        Together.signal(p.uid, buildJsonObject { put("t", "saver"); put("on", dataSaver.value.toString()) })
    }

    private fun isMetered(): Boolean = runCatching {
        (app?.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)?.isActiveNetworkMetered == true
    }.getOrDefault(false)

    /** 0.9.8: mobile data or a buffering movie → lighter video; back to normal on Wi-Fi after 8 s of smooth playback. */
    private fun startSaver() {
        if (saverJob != null) return
        saverJob = scope.launch {
            var lastBuf = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                if (movieBuffering.value) lastBuf = now
                val metered = isMetered()
                val choppy = lastBuf != 0L && now - lastBuf < 8_000
                val want = metered || choppy
                if (want != dataSaver.value) {
                    dataSaver.value = want
                    val why = if (metered) "mobile data" else if (want) "movie buffering" else "Wi-Fi, playback smooth"
                    ActLog.add("ROOM", "call · data saver ${if (want) "on" else "off"} ($why)")
                    peers.values.toList().forEach { applySend(it); tellSaver(it) }
                }
                delay(1000)
            }
        }
    }

    /** 0.9.8: 1..3 dots from the call's round-trip time, packet loss (over a window) and incoming frame rate. */
    private fun rate(p: Peer, rep: RTCStatsReport): Int {
        var rtt = 0.0; var fps = -1.0; var lost = 0L; var recv = 0L
        rep.statsMap.values.forEach { s ->
            val m = s.members
            if (s.type == "candidate-pair" && m["nominated"] == true && m["state"] == "succeeded") rtt = (m["currentRoundTripTime"] as? Number)?.toDouble() ?: rtt
            if (s.type == "inbound-rtp") {
                lost += (m["packetsLost"] as? Number)?.toLong() ?: 0L
                recv += (m["packetsReceived"] as? Number)?.toLong() ?: 0L
                if (m["kind"] == "video") fps = (m["framesPerSecond"] as? Number)?.toDouble() ?: fps
            }
        }
        val dR = recv - p.qRecv; val dL = lost - p.qLost
        if (dR < 0 || dL < 0) { p.qRecv = recv; p.qLost = lost }
        else if (dR + dL >= 60) { p.qLoss = dL * 100.0 / (dR + dL); p.qRecv = recv; p.qLost = lost }
        val a = if (rtt < 0.15) 3 else if (rtt < 0.35) 2 else 1
        val b = if (p.qLoss < 3.0) 3 else if (p.qLoss < 10.0) 2 else 1
        val c = if (fps <= 0.0 || fps >= 8.0) 3 else if (fps >= 4.0) 2 else 1
        return minOf(a, b, c)
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
                    override fun onSetSuccess() { scope.launch { p.remoteSet = true; p.pendingIce.forEach { p.pc.addIceCandidate(it) }; p.pendingIce.clear(); if (Feature.wt2) applySend(p) } }
                }, sdp)
            }
            "ice" -> {
                val p = peers[from] ?: return
                val c = IceCandidate(msg["m"]?.jsonPrimitive?.contentOrNull ?: "", msg["i"]?.jsonPrimitive?.intOrNull ?: 0, msg["c"]?.jsonPrimitive?.contentOrNull ?: return)
                if (p.remoteSet) p.pc.addIceCandidate(c) else p.pendingIce += c
            }
            // 0.9.8: a friend on a weak link asks for lighter video (older apps ignore this message).
            "saver" -> if (Feature.wt2) peers[from]?.let { p ->
                p.askedLow = msg["on"]?.jsonPrimitive?.contentOrNull == "true"
                applySend(p)
                ActLog.add("ROOM", "call · ${Together.members.value[from]?.name ?: "friend"} asked for ${if (p.askedLow) "lighter" else "normal"} video")
            }
        }
    }

    private fun close(uid: String) {
        val p = peers.remove(uid) ?: return
        runCatching { p.pc.dispose() }
        if (Feature.wt2) quality.value = quality.value - uid
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
            if (peers.isEmpty()) {
                if (talking.value.isNotEmpty()) talking.value = emptySet()
                if (Feature.wt2 && quality.value.isNotEmpty()) quality.value = emptyMap()
                continue
            }
            val now = HashSet<String>()
            val qs = HashMap<String, Int>()   // 0.9.8
            val latch = CountDownLatch(peers.size)
            peers.values.toList().forEach { p ->
                runCatching {
                    p.pc.getStats { rep ->
                        val lvl = rep.statsMap.values.filter { it.type == "inbound-rtp" && it.members["kind"] == "audio" }
                            .maxOfOrNull { (it.members["audioLevel"] as? Number)?.toDouble() ?: 0.0 } ?: 0.0
                        // 0.9.8: a muted friend doesn't light the ring or lower the movie.
                        if (lvl > 0.04 && !(Feature.wt2 && p.uid in _peerMuted.value)) synchronized(now) { now += p.uid }
                        if (Feature.wt2) runCatching { val q = rate(p, rep); synchronized(qs) { qs[p.uid] = q } }
                        latch.countDown()
                    }
                }.onFailure { latch.countDown() }
            }
            runCatching { latch.await(1, java.util.concurrent.TimeUnit.SECONDS) }
            if (now != talking.value) talking.value = now
            if (Feature.wt2) { val snap = synchronized(qs) { HashMap(qs) }; if (snap != quality.value) quality.value = snap }
        }
    }

    // ── This phone's mic / camera ──

    fun setMic(on: Boolean) {
        micOn.value = on
        scope.launch {
            if (on && audioTrack == null) {
                val f = ensureFactory() ?: run { ActLog.add("ROOM", "call · mic couldn't start — calling didn't load"); micOn.value = false; return@launch }
                runCatching {
                    audioSrc = f.createAudioSource(MediaConstraints())
                    audioTrack = f.createAudioTrack("mic", audioSrc)
                }.onFailure { ActLog.add("ROOM", "call · mic couldn't start — ${it.message}"); micOn.value = false; return@launch }
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
        fun fail(why: String) { camError.value = why; camOn.value = false; ActLog.add("ROOM", "call · camera couldn't start — $why") }
        val f = ensureFactory() ?: return fail("video calling couldn't load on this phone")
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return fail("camera permission is off")
        runCatching {
            val en = if (Camera2Enumerator.isSupported(ctx)) Camera2Enumerator(ctx) else Camera1Enumerator(false)
            val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) == frontCam.value } ?: en.deviceNames.firstOrNull() ?: return fail("no camera found")
            val cap = en.createCapturer(name, object : CameraVideoCapturer.CameraEventsHandler {
                override fun onCameraError(e: String?) { fail(e ?: "camera error") }
                override fun onCameraDisconnected() { fail("another app took the camera") }
                override fun onCameraFreezed(e: String?) {}
                override fun onCameraOpening(n: String?) {}
                override fun onFirstFrameAvailable() { camError.value = null; ActLog.add("ROOM", "call · camera on (first picture)") }
                override fun onCameraClosed() {}
            }) ?: return fail("the camera couldn't be opened")
            val helper = SurfaceTextureHelper.create("cam", egl?.eglBaseContext)
            val src = f.createVideoSource(false)
            cap.initialize(helper, ctx, src.capturerObserver)
            // 0.9.76: 480p at 15 fps — less memory and data during a film (a phone was closed at 580 MB with the camera on).
            cap.startCapture(640, 480, 15)
            capturer = cap; texHelper = helper; videoSrc = src
            videoTrack = f.createVideoTrack("cam", src)
            localVideo.value = videoTrack   // 0.9.8: for the "You" bubble
        }.onFailure { fail(it.message ?: it.javaClass.simpleName) }
    }

    /** 0.9.76: once connected, log whether the call goes straight between the phones or through the relay. */
    private fun logRoute(p: Peer?) {
        val pc = p?.pc ?: return
        runCatching {
            pc.getStats { rep ->
                val pair = rep.statsMap.values.firstOrNull { it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true }
                val local = pair?.members?.get("localCandidateId")?.let { rep.statsMap[it] }
                val kind = local?.members?.get("candidateType")?.toString() ?: "?"
                ActLog.add("ROOM", "call · route: ${if (kind == "relay") "through the relay" else "direct ($kind)"}")
            }
        }
    }

    private fun stopCamera() {
        localVideo.value = null
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
        scope.launch { peers.values.forEach { it.remoteAudio?.setVolume(if (Feature.wt2) vol(it.uid) else if (on) 1.0 else 0.0) } }   // 0.9.8: + per-friend mute
    }
    fun setDuck(on: Boolean) { duckOn.value = on; save("duck", on) }
    fun setBubbles(on: Boolean) { bubblesOn.value = on; save("bubbles", on) }
    fun setSize(dp: Int) { bubbleDp.value = dp.coerceIn(64, 170); save("size", bubbleDp.value) }

    /** Left the room: hang up and turn the mic and camera off. */
    fun stop() {
        micOn.value = false; camOn.value = false; status.value = ""; camError.value = null
        scope.launch {
            sigJob?.cancel(); sigJob = null
            statsJob?.cancel(); statsJob = null
            if (Feature.wt2) { saverJob?.cancel(); saverJob = null; dataSaver.value = false; quality.value = emptyMap() }
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
    /** 0.9.8: server time of my own framing of each person (missing / 0 = saved before 0.9.8). */
    val at = MutableStateFlow<Map<String, Long>>(emptyMap())
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("call_frames", Context.MODE_PRIVATE)
    private fun atPrefs(ctx: Context) = ctx.getSharedPreferences("call_frames_at", Context.MODE_PRIVATE)
    fun load(ctx: Context) {
        all.value = prefs(ctx).all.mapNotNull { (k, v) -> (v as? String)?.split(",")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 3 }?.let { k to Frame(it[0], it[1], it[2]) } }.toMap()
        if (Feature.wt2) at.value = atPrefs(ctx).all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
    }
    fun of(uid: String) = all.value[uid] ?: Frame()
    fun set(ctx: Context, uid: String, f: Frame, stamp: Long = if (Feature.wt2) Together.serverNow() else 0L) {
        all.value = all.value + (uid to f)
        prefs(ctx).edit().putString(uid, "${f.fx},${f.fy},${f.r}").apply()
        if (Feature.wt2) { at.value = at.value + (uid to stamp); atPrefs(ctx).edit().putLong(uid, stamp).apply() }
    }
    fun clear(ctx: Context, uid: String) {
        all.value = all.value - uid; at.value = at.value - uid
        prefs(ctx).edit().remove(uid).apply(); atPrefs(ctx).edit().remove(uid).apply()
    }
    /** 0.9.8: the framing rule: the owner's published frame, unless my own override is newer. */
    fun pick(pub: FrameMsg?, o: Frame?, oAt: Long): Frame = when {
        pub != null && (o == null || pub.at >= oAt) -> Frame(pub.fx, pub.fy, pub.r)
        o != null -> o
        else -> Frame()
    }
    fun effective(uid: String, pub: FrameMsg?): Frame = pick(pub, all.value[uid], at.value[uid] ?: 0L)
    /** 0.9.8: true (once) when the owner's newer frame has just replaced my override; the override is dropped. */
    fun takePublished(ctx: Context, uid: String, pub: FrameMsg?): Boolean {
        val a = at.value[uid] ?: 0L
        if (pub == null || all.value[uid] == null || a <= 0L || pub.at <= a) return false
        clear(ctx, uid); return true
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
fun FramedVideo(uid: String, track: VideoTrack, sizeDp: androidx.compose.ui.unit.Dp, frame: Frame, onView: (RemoteView) -> Unit = {}, onAspect: (Float) -> Unit = {}) {
    var aspect by remember { mutableFloatStateOf(4f / 3f) }
    val f = frame.clamp(aspect)
    val d = LocalDensity.current
    val s = with(d) { sizeDp.toPx() }
    val hPx = s / f.r
    val wPx = hPx * aspect
    Box(Modifier.size(sizeDp).clip(CircleShape).background(Color(0xFF1A1B22))) {
        AndroidView(
            factory = { RemoteView(it) { w, h -> if (w > 0 && h > 0) { aspect = w.toFloat() / h; onAspect(aspect) } }.also(onView) },
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
private fun CallBubblesLegacy(modifier: Modifier, onTap: () -> Unit) {
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
    var f by remember { mutableStateOf(if (Feature.wt2) Frames.effective(uid, members[uid]?.frame) else Frames.of(uid)) }
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

/** 0.9.8: friends' bubbles. wt2 on = free placement (CallBubblesV2); off = the old corner bubbles. */
@Composable
fun CallBubbles(modifier: Modifier, controlsVisible: Boolean = false, onTap: () -> Unit) {
    if (Feature.wt2) CallBubblesV2(modifier, controlsVisible, onTap) else CallBubblesLegacy(modifier, onTap)
}

/** 0.9.8: where each bubble sits and how big it is, remembered on this phone. x / y = top-left as a share of the player. */
object BubbleSpots {
    class Spot(val x: Float, val y: Float, val dp: Float)
    val all = MutableStateFlow<Map<String, Spot>>(emptyMap())
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("call_bubbles", Context.MODE_PRIVATE)
    fun load(ctx: Context) {
        all.value = prefs(ctx).all.mapNotNull { (k, v) -> (v as? String)?.split(",")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 3 }?.let { k to Spot(it[0], it[1], it[2]) } }.toMap()
    }
    fun set(ctx: Context, id: String, s: Spot) {
        all.value = all.value + (id to s)
        prefs(ctx).edit().putString(id, "${s.x},${s.y},${s.dp}").apply()
    }
}

// 0.9.8: room around the circle so the pop-out buttons stay inside their parent (needed for touch).
private val BUBBLE_PAD = 36.dp

@Composable
private fun CallBubblesV2(modifier: Modifier, controlsVisible: Boolean, onTap: () -> Unit) {
    val ctx = LocalContext.current
    remember(ctx) { BubbleSpots.load(ctx); Frames.load(ctx) }
    val members by Together.members.collectAsState()
    val videos by Call.videos.collectAsState()
    val talking by Call.talking.collectAsState()
    val show by Call.bubblesOn.collectAsState()
    val quality by Call.quality.collectAsState()
    val muted by Call.peerMuted.collectAsState()
    val selfOn by Call.selfView.collectAsState()
    val camMine by Call.camOn.collectAsState()
    val myTrack by Call.localVideo.collectAsState()
    val frames by Frames.all.collectAsState()
    val stamps by Frames.at.collectAsState()
    val me = Together.me
    val now = Together.serverNow()
    val people = members.filter { (uid, m) -> uid != me && now - m.seen < 40_000 && (m.cam || m.mic) }.toList()

    // 0.9.9: "You" only when I turned it on (room panel, or the 👤 chip on a friend's bubble) — never just because the
    // controls came up. Its mic / camera buttons still spring out while the controls are up.
    val selfShown = selfOn

    // Talker gets the big bubble (3+ on camera). Every change takes effect after 600 ms of staying the same.
    val camUids = people.filter { (uid, m) -> m.cam && videos[uid] != null }.map { it.first }
    val onCam = camUids.size + (if (selfShown && camMine) 1 else 0)
    var big by remember { mutableStateOf<String?>(null) }
    val cur = big
    val talkerNow: String? = if (onCam < 3) null else talking.filter { it in camUids }.sorted().let { s -> if (cur != null && cur in s) cur else s.firstOrNull() }
    LaunchedEffect(talkerNow) { delay(600); big = talkerNow }
    val bigUid = big?.takeIf { it in camUids && onCam >= 3 }

    // One line of text at the top: the first-time hint, "changed their frame", "camera is off".
    var banner by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(banner) { if (banner != null) { delay(4000); banner = null } }
    val anyBubble = show && (people.isNotEmpty() || selfShown)
    LaunchedEffect(anyBubble) {
        if (!anyBubble) return@LaunchedEffect
        val p = ctx.getSharedPreferences("call", Context.MODE_PRIVATE)
        if (!p.getBoolean("hint_wt2_frame", false)) { p.edit().putBoolean("hint_wt2_frame", true).apply(); banner = "Double-tap a bubble to frame it" }
    }
    // The owner re-framed after my override: theirs wins, tell me once.
    val pubKey = people.map { (uid, m) -> uid to m.frame?.at }
    LaunchedEffect(pubKey) {
        people.forEach { (uid, m) -> if (Frames.takePublished(ctx, uid, m.frame)) banner = "${m.name.ifBlank { "Your friend" }} changed their frame" }
    }

    if (!anyBubble) return
    BoxWithConstraints(modifier) {
        val boxW = constraints.maxWidth.toFloat(); val boxH = constraints.maxHeight.toFloat()
        fun modeOf(uid: String) = if (bigUid == null) 0 else if (uid == bigUid) 1 else -1
        people.forEachIndexed { i, (uid, m) ->
            key(uid) {
                BubbleItem(
                    id = uid, name = m.name.ifBlank { "Friend" }, track = if (m.cam) videos[uid] else null,
                    speaking = uid in talking, dots = quality[uid] ?: 0, muted = uid in muted, isSelf = false,
                    mode = modeOf(uid), frame = Frames.pick(m.frame, frames[uid], stamps[uid] ?: 0L),
                    index = i, boxW = boxW, boxH = boxH, controlsVisible = false,
                    onTap = onTap, onSaveFrame = { f -> Frames.set(ctx, uid, f) }, onNote = { banner = it },
                    // 0.9.9: the first friend's bubble carries a small 👤 chip (while the controls are up) for my own bubble.
                    selfChip = if (i == 0) controlsVisible else null, selfOnNow = selfOn,
                )
            }
        }
        if (selfShown) key("self") {
            BubbleItem(
                id = "self", name = "You", track = if (camMine) myTrack else null,
                speaking = false, dots = 0, muted = false, isSelf = true,
                mode = modeOf("self"), frame = Frames.pick(members[me]?.frame, frames[me], stamps[me] ?: 0L),
                index = people.size, boxW = boxW, boxH = boxH, controlsVisible = controlsVisible,
                onTap = onTap, onNote = { banner = it },
                // My own frame: remember it now (newest time) and publish it; friends see it as the owner's frame.
                onSaveFrame = { f -> Frames.set(ctx, me, f, Together.serverNow()); Together.publishFrame(f) },
            )
        }
        banner?.let {
            Text(it, Modifier.align(Alignment.TopCenter).padding(top = 72.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xB3000000))
                .padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 0.9.8: one bubble: free drag, pinch to resize, double-tap to frame, glass ring, name pill, dots, talking ring. */
@Composable
private fun BubbleItem(
    id: String, name: String, track: VideoTrack?, speaking: Boolean, dots: Int, muted: Boolean, isSelf: Boolean,
    mode: Int, frame: Frame, index: Int, boxW: Float, boxH: Float, controlsVisible: Boolean,
    onTap: () -> Unit, onSaveFrame: (Frame) -> Unit, onNote: (String) -> Unit,
    /** 0.9.9: non-null on the bubble that carries the "my own bubble" chip: whether the chip shows now. */
    selfChip: Boolean? = null, selfOnNow: Boolean = false,
) {
    val ctx = LocalContext.current
    val d = LocalDensity.current
    val spots by BubbleSpots.all.collectAsState()
    val defDp by Call.bubbleDp.collectAsState()
    val saved = spots[id]
    var liveDp by remember { mutableStateOf<Float?>(null) }
    var liveXY by remember { mutableStateOf<Offset?>(null) }
    var liveFrame by remember { mutableStateOf<Frame?>(null) }
    var frameMode by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(4f / 3f) }
    LaunchedEffect(track == null) { if (track == null) { frameMode = false; liveFrame = null } }

    // Size: my own remembered size, times the talker factor (animated; the pinch itself is not delayed).
    val baseDp = (liveDp ?: saved?.dp ?: defDp.toFloat()).coerceIn(56f, 180f)
    val k by animateFloatAsState(when (mode) { 1 -> minOf(1.45f, 180f / baseDp); -1 -> maxOf(0.7f, 56f / baseDp); else -> 1f }, tween(380), label = "talkScale")
    val shown = (baseDp * k).dp
    val sizePx = with(d) { shown.toPx() }
    val padPx = with(d) { BUBBLE_PAD.toPx() }
    val maxX = (boxW - sizePx).coerceAtLeast(0f)
    val maxY = (boxH - sizePx - with(d) { 26.dp.toPx() }).coerceAtLeast(0f)
    val defX = with(d) { 18.dp.toPx() }
    val defY = boxH - with(d) { (64.dp + ((index + 1) * (defDp + 30)).dp).toPx() }
    val raw = liveXY ?: (if (saved != null) Offset(saved.x * boxW, saved.y * boxH) else Offset(defX, defY))
    val target = Offset(raw.x.coerceIn(0f, maxX), raw.y.coerceIn(0f, maxY))
    val spec: AnimationSpec<Offset> = if (liveXY != null) snap<Offset>() else spring<Offset>(stiffness = Spring.StiffnessMediumLow)
    val pos by animateOffsetAsState(target, spec, label = "bubblePos")

    // The gesture block below lives across recompositions, so it calls these (always the latest versions).
    val onMove by rememberUpdatedState<(Offset, Float) -> Unit>({ pan, zoom ->
        if (frameMode) {
            val c = liveFrame ?: frame
            val hPx = sizePx / c.r; val wPx = hPx * aspect
            liveFrame = Frame(c.fx - pan.x / wPx, c.fy - pan.y / hPx, c.r / zoom).clamp(aspect)   // picture follows the finger
        } else {
            if (zoom != 1f) liveDp = ((liveDp ?: baseDp) * zoom).coerceIn(56f, 180f)
            liveXY = (liveXY ?: target) + pan
        }
    })
    val onEnd by rememberUpdatedState<() -> Unit>({
        if (!frameMode) {
            val p = liveXY ?: target
            val edge = with(d) { 12.dp.toPx() }; val inset = with(d) { 6.dp.toPx() }
            var x = p.x.coerceIn(0f, maxX); var y = p.y.coerceIn(0f, maxY)
            if (x < edge) x = inset else if (maxX - x < edge) x = maxX - inset      // gentle edge snap, only near an edge
            if (y < edge) y = inset else if (maxY - y < edge) y = maxY - inset
            x = x.coerceIn(0f, maxX); y = y.coerceIn(0f, maxY)
            BubbleSpots.set(ctx, id, BubbleSpots.Spot(x / boxW.coerceAtLeast(1f), y / boxH.coerceAtLeast(1f), liveDp ?: baseDp))
            liveXY = null; liveDp = null
        }
    })
    val onDouble by rememberUpdatedState<() -> Unit>({
        if (!frameMode) {
            if (track == null) onNote(if (isSelf) "Turn your camera on to frame yourself" else "$name's camera is off")
            else { frameMode = true; liveFrame = null }
        } else {
            frameMode = false
            liveFrame?.let { onSaveFrame(it) }      // my own → published; a friend's → my local override
            liveFrame = null
        }
    })
    val onTapCb by rememberUpdatedState(onTap)

    Box(Modifier.offset { IntOffset((pos.x - padPx).roundToInt(), (pos.y - padPx).roundToInt()) }.size(shown + BUBBLE_PAD * 2)) {
        Box(
            Modifier.padding(BUBBLE_PAD).size(shown).shadow(10.dp, CircleShape).clip(CircleShape).background(Color(0xFF1A1B22))
                // Drag, pinch, tap and double-tap in ONE block: slop decides drag; two fingers = pinch; a tap waits for a 2nd tap.
                .pointerInput(id) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val slop = viewConfiguration.touchSlop
                        var moved = false
                        var dist = Offset.Zero
                        do {
                            val ev = awaitPointerEvent()
                            val pan = ev.calculatePan(); val zoom = ev.calculateZoom()
                            if (!moved) {
                                dist += pan
                                if (ev.changes.count { it.pressed } >= 2 || dist.getDistance() > slop) moved = true
                            } else if (pan != Offset.Zero || zoom != 1f) {
                                onMove(pan, zoom)
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                        if (moved) { onEnd(); return@awaitEachGesture }
                        val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown(requireUnconsumed = false) }
                        if (second == null) { if (!frameMode) onTapCb(); return@awaitEachGesture }
                        val up = waitForUpOrCancellation()
                        if (up != null && (up.position - second.position).getDistance() <= slop * 2) { up.consume(); onDouble() }
                    }
                },
            Alignment.Center,
        ) {
            if (track != null) FramedVideo(id, track, shown, liveFrame ?: frame, onAspect = { aspect = it })
            else Box(Modifier.fillMaxSize().background(Color(0xFF3C3F58)), Alignment.Center) {
                Text(name.take(1).uppercase(), fontSize = (shown.value * 0.36f).sp, fontWeight = FontWeight.Bold)
            }
        }
        // Thin glass ring (+ dashed edge in frame mode).
        Canvas(Modifier.padding(BUBBLE_PAD - 8.dp).size(shown + 16.dp)) {
            val r = size.minDimension / 2f - 8.dp.toPx()
            drawCircle(Brush.linearGradient(listOf(Color(0xF2FFFFFF), Color(0x24FFFFFF), Color(0xB3FFFFFF)), Offset.Zero, Offset(size.width, size.height)),
                r, center, style = Stroke(1.5.dp.toPx()))
            drawCircle(Color(0x1FFFFFFF), r - 2.5.dp.toPx(), center, style = Stroke(1.dp.toPx()))
            if (frameMode) drawCircle(Color.White, r - 1.dp.toPx(), center, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
        }
        if (speaking) TalkRing(shown, Modifier.padding(BUBBLE_PAD - 8.dp))
        // Name pill (or the frame-mode caption) with the signal dots.
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = BUBBLE_PAD + shown + 3.dp)
                .then(if (frameMode) Modifier.requiredWidthIn(max = 240.dp) else Modifier.widthIn(max = shown + BUBBLE_PAD * 2))
                .clip(RoundedCornerShape(10.dp)).background(Color(0x66000000)).border(0.5.dp, Color(0x40FFFFFF), RoundedCornerShape(10.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (frameMode) Text("Drag · pinch · double-tap to save", fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            else {
                if (muted) Text("🔇", fontSize = 9.sp)
                Text(name, Modifier.weight(1f, fill = false), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (dots > 0) SignalDots(dots)
            }
        }
        if (isSelf && !frameMode) SelfControls(controlsVisible, shown, onRight = target.x + sizePx / 2f < boxW / 2f)
        if (selfChip != null && !isSelf && !frameMode) SelfChip(selfChip, selfOnNow, shown)
    }
}

/** 0.9.9: a tiny 👤 chip at a friend's bubble edge (upper right, on the ring) while the controls are up: shows / hides
 *  my own bubble. */
@Composable
private fun BoxScope.SelfChip(visible: Boolean, selfOn: Boolean, shown: Dp) {
    val a by animateFloatAsState(if (visible) 1f else 0f, tween(180), label = "selfChip")
    if (a <= 0.01f) return
    val c = shown / 2
    val off = c * 0.7071f      // 45° on the circle
    Box(
        Modifier.align(Alignment.TopStart)
            .offset(x = BUBBLE_PAD + c + off - 11.dp, y = BUBBLE_PAD + c - off - 11.dp)
            .graphicsLayer { alpha = a; scaleX = 0.6f + 0.4f * a; scaleY = 0.6f + 0.4f * a }
            .size(22.dp).clip(CircleShape)
            .background(if (selfOn) Color(0xCC3CCF7A) else Color(0xB3202228))
            .border(1.dp, Color(0x80FFFFFF), CircleShape)
            .then(if (visible) Modifier.clickable { Call.setSelfView(!selfOn) } else Modifier)
            .semantics { contentDescription = if (selfOn) "Hide my own bubble" else "Show my own bubble" },
        Alignment.Center,
    ) { Text("👤", fontSize = 10.sp) }
}

/** 0.9.8: green talking ring with a soft pulse. */
@Composable
private fun TalkRing(shown: Dp, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "talk")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    Canvas(modifier.size(shown + 16.dp)) {
        val r = size.minDimension / 2f - 8.dp.toPx()
        drawCircle(Color(0xFF3CCF7A).copy(alpha = 0.22f + 0.2f * p), r + 2.dp.toPx() + 4.dp.toPx() * p, center, style = Stroke(3.dp.toPx()))
        drawCircle(Color(0xFF3CCF7A), r + 1.dp.toPx(), center, style = Stroke(2.5f.dp.toPx()))
    }
}

@Composable
private fun SignalDots(n: Int) {
    val c = when (n) { 3 -> Color(0xFF3CCF7A); 2 -> Color(0xFFF2B84B); else -> Color(0xFFE5484D) }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..3) Box(Modifier.size(4.dp).clip(CircleShape).background(if (i <= n) c else Color(0x40FFFFFF)))
    }
}

/** 0.9.8: mic / camera / switch-camera: 28 dp glass buttons that spring out of my bubble and slide back into it. */
@Composable
private fun BoxScope.SelfControls(visible: Boolean, shown: Dp, onRight: Boolean) {
    val ctx = LocalContext.current
    val mic by Call.micOn.collectAsState()
    val cam by Call.camOn.collectAsState()
    val micAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) Call.setMic(true) }
    val camAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) Call.setCam(true) }
    fun has(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val r = with(LocalDensity.current) { (shown / 2 + 18.dp).toPx() }
    val sx = if (onRight) 1f else -1f
    fun spot(deg: Float): Offset { val a = deg * (PI / 180.0).toFloat(); return Offset(cos(a) * r * sx, sin(a) * r) }
    val a = spot(-44f); val b = spot(0f); val c = spot(44f)
    PopButton(0, visible, a.x, a.y, if (mic) "🎤" else "🔇", "Microphone", mic) {
        if (mic) Call.setMic(false) else if (has(android.Manifest.permission.RECORD_AUDIO)) Call.setMic(true) else micAsk.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    PopButton(1, visible, b.x, b.y, if (cam) "📷" else "🚫", "Camera", cam) {
        if (cam) Call.setCam(false) else if (has(android.Manifest.permission.CAMERA)) Call.setCam(true) else camAsk.launch(android.Manifest.permission.CAMERA)
    }
    PopButton(2, visible, c.x, c.y, "🔄", "Switch camera", false) { Call.flip() }
}

@Composable
private fun BoxScope.PopButton(index: Int, visible: Boolean, tx: Float, ty: Float, glyph: String, desc: String, on: Boolean, onClick: () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        delay(if (visible) index * 70L else (2 - index) * 45L)       // staggered out, staggered back in
        p.animateTo(if (visible) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 380f))
    }
    Box(
        Modifier.align(Alignment.Center)
            .offset { IntOffset((tx * p.value).roundToInt(), (ty * p.value).roundToInt()) }
            .graphicsLayer { val s = p.value.coerceAtLeast(0f); scaleX = s; scaleY = s; alpha = p.value.coerceIn(0f, 1f) }
            .size(28.dp).clip(CircleShape)
            .background(Brush.linearGradient(if (on) listOf(Color(0x883CCF7A), Color(0x443CCF7A)) else listOf(Color(0x66FFFFFF), Color(0x33000000))))
            .border(1.dp, Color(0x66FFFFFF), CircleShape)
            .then(if (visible) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = desc },
        Alignment.Center,
    ) { Text(glyph, fontSize = 13.sp) }
}
