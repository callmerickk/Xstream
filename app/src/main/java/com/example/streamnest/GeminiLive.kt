package com.example.streamnest

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/*
 * Gemini Live: a real-time voice conversation (Google's native-audio model, the one the Gemini app talks with).
 * Mic audio streams up (16 kHz PCM), the voice streams back (24 kHz PCM) and plays as it arrives; you can interrupt
 * it any time. The assistant can also call the app's own functions (play a title, pause, …) — see [LiveTool].
 * Uses the profile's own Gemini key (Help & Settings › Integrations), free tier.
 */

/** One function the assistant may call; [run] returns a small JSON-able result (or throws). */
class LiveTool(val name: String, val description: String, val params: JsonObject?, val run: suspend (JsonObject) -> JsonElement)

enum class LiveState { Connecting, Listening, Thinking, Speaking, Ended, Failed }

object GeminiLiveModels {
    @Volatile private var cached: List<String>? = null

    /** Models that can hold a live voice conversation, best first (native audio, then other live models). */
    suspend fun list(key: String): List<String> = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        val names = ArrayList<String>()
        runCatching {
            var page: String? = null
            repeat(4) {
                val url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200&key=$key" + (page?.let { "&pageToken=$it" } ?: "")
                Api.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    val o = Api.json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                    (o["models"] as? JsonArray)?.forEach { m ->
                        val mo = m.jsonObject
                        val n = (mo["name"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                        val methods = (mo["supportedGenerationMethods"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                        if (methods.any { it.equals("bidiGenerateContent", true) }) names += n.removePrefix("models/")
                    }
                    page = (o["nextPageToken"] as? JsonPrimitive)?.contentOrNull
                }
                if (page.isNullOrBlank()) return@runCatching
            }
        }
        fun score(n: String): Int {
            var s = 0
            if (n.contains("native-audio")) s += 100
            if (n.contains("live")) s += 40
            if (n.contains("flash")) s += 10
            if (n.contains("preview")) s -= 3
            if (n.contains("thinking")) s -= 20
            Regex("(\\d+)\\.(\\d+)").find(n)?.let { s += it.groupValues[1].toInt() * 8 + it.groupValues[2].toInt() }
            return s
        }
        val found = names.distinct().sortedByDescending(::score)
        val fallback = listOf("gemini-2.5-flash-native-audio-preview-09-2025", "gemini-live-2.5-flash-preview", "gemini-2.0-flash-live-001")
        val out = (found + fallback).distinct()
        if (found.isNotEmpty()) cached = out
        out
    }
}

/**
 * One live conversation. Create, [start], then [close]. All callbacks arrive on the main thread.
 * [level] is the loudness of the voice the speaker is playing RIGHT NOW (0..1) — it drives the avatar's mouth.
 */
class GeminiLive(
    private val ctx: Context,
    private val key: String,
    private val voiceName: String,
    private val system: String,
    private val tools: List<LiveTool>,
    private val modelOverride: String? = null,
) {
    val state = MutableStateFlow(LiveState.Connecting)
    /** What the assistant is saying (this turn), from its own transcript. */
    val said = MutableStateFlow("")
    /** What it heard you say (this turn). */
    val heard = MutableStateFlow("")
    val micLevel = MutableStateFlow(0f)
    /** Mic muted by the user (the assistant still talks). */
    @Volatile var muted = false
    /** Gets the microphone (with two assistants on screen only the one you're talking to listens). */
    @Volatile var listening = true
    /** Tool calls still running (the overlay never closes while one runs). */
    val toolsRunning = java.util.concurrent.atomic.AtomicInteger(0)
    var onTurnDone: (String) -> Unit = {}
    var onError: (String) -> Unit = {}
    /** Called from the tool runner; returning false drops the call (e.g. the same title asked twice). */
    var onToolCall: (String, JsonObject) -> Unit = { _, _ -> }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var ws: WebSocket? = null
    private val alive = AtomicBoolean(true)
    private val ready = AtomicBoolean(false)
    private var track: AudioTrack? = null
    private val playQ = ConcurrentLinkedQueue<ShortArray>()
    /** (end frame of a 20 ms block written to the speaker, its loudness) — matched against the play head for lip sync. */
    private val levels = ConcurrentLinkedQueue<Pair<Long, Float>>()
    @Volatile private var written = 0L
    @Volatile private var headset = false
    private var pendingText: String? = null
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var usedSco = false
    @Volatile private var resumeHandle: String? = null
    @Volatile private var model: String? = null
    @Volatile private var reconnects = 0
    /** Audio samples received this turn, and the transcript known at each point (for subtitles that follow the voice). */
    @Volatile private var queued = 0L
    @Volatile private var turnOpen = false
    /** A new reply starts: fresh transcript. */
    private fun openTurn() {
        if (turnOpen) return
        turnOpen = true
        textMarks.clear()
        main.post { said.value = ""; spoken.value = "" }
    }
    private val textMarks = ConcurrentLinkedQueue<Pair<Long, String>>()
    /** The words of this turn that have actually been heard so far (follows the speaker, not the network). */
    val spoken = MutableStateFlow("")

    fun start(firstText: String? = null) {
        pendingText = firstText
        scope.launch {
            val models = modelOverride?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: GeminiLiveModels.list(key)
            connect(models, 0)
        }
    }

    private fun connect(models: List<String>, i: Int) {
        if (!alive.get()) return
        if (i >= models.size) { fail("Couldn't start a live voice chat with your Gemini key."); return }
        val model = models[i]
        this.model = model
        val client = Api.http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"
        var setupDone = false
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setupJson(model)) }
            override fun onMessage(webSocket: WebSocket, text: String) { if (handle(text)) setupDone = true }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (handle(bytes.utf8())) setupDone = true }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                ActLog.add("AI", "live closed $code ${reason.take(160)} · $model")
                webSocket.close(1000, null)
                if (!setupDone && alive.get()) connect(models, i + 1) else if (alive.get()) reconnect(model)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ActLog.add("AI", "live failed ${t.message?.take(120)} · ${response?.code} · $model")
                if (!setupDone && alive.get() && (response?.code ?: 0) != 401 && (response?.code ?: 0) != 403) connect(models, i + 1)
                else if (setupDone && alive.get() && reconnects < 3) reconnect(model)
                else if (alive.get()) fail(if (response?.code == 401 || response?.code == 403) "Your Gemini key was refused." else "The live voice chat dropped.")
            }
        })
        ActLog.add("AI", "live connecting · $model")
    }

    /** The connection dropped (or the server asked to move): join again and carry on where we were. */
    private fun reconnect(model: String) {
        reconnects++
        ready.set(false)
        ActLog.add("AI", "live reconnecting (${reconnects}) · resume=${resumeHandle != null}")
        scope.launch { kotlinx.coroutines.delay(400L * reconnects); connect(listOf(model), 0) }
    }

    private fun setupJson(model: String): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", "models/$model")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") { putJsonObject("prebuiltVoiceConfig") { put("voiceName", voiceName) } }
                }
            }
            putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
            if (tools.isNotEmpty()) putJsonArray("tools") {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray {
                        tools.forEach { t ->
                            add(buildJsonObject {
                                put("name", t.name); put("description", t.description)
                                t.params?.let { put("parameters", it) }
                            })
                        }
                    })
                })
            }
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
            // Only clear speech starts a turn (TV / room noise doesn't), and a short pause ends it.
            putJsonObject("realtimeInputConfig") {
                putJsonObject("automaticActivityDetection") {
                    put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                    put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                    put("prefixPaddingMs", 120)
                    put("silenceDurationMs", 650)
                }
            }
            putJsonObject("sessionResumption") { resumeHandle?.let { put("handle", it) } }
            putJsonObject("contextWindowCompression") { putJsonObject("slidingWindow") {} }
        }
    }.toString()

    /** Returns true once the server confirmed the setup. */
    private fun handle(raw: String): Boolean {
        val o = runCatching { Api.json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return false
        if (o.containsKey("setupComplete")) {
            ready.set(true)
            main.post { if (alive.get() && state.value == LiveState.Connecting) state.value = LiveState.Listening }
            if (track == null) startAudio()
            pendingText?.let { t -> pendingText = null; sendText(t) }
            return true
        }
        (o["sessionResumptionUpdate"] as? JsonObject)?.let { u ->
            val h = (u["newHandle"] as? JsonPrimitive)?.contentOrNull
            if (!h.isNullOrBlank() && (u["resumable"] as? JsonPrimitive)?.booleanOrNull != false) resumeHandle = h
        }
        (o["serverContent"] as? JsonObject)?.let { sc ->
            (sc["inputTranscription"] as? JsonObject)?.let { tr ->
                val t = (tr["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (t.isNotBlank()) main.post { heard.value = (heard.value + t).take(400); if (state.value == LiveState.Listening) state.value = LiveState.Listening }
            }
            (sc["outputTranscription"] as? JsonObject)?.let { tr ->
                val t = (tr["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (t.isNotEmpty()) openTurn()
                val q = queued
                if (t.isNotEmpty()) main.post {
                    said.value = (said.value + t).takeLast(600)
                    textMarks.add(q to said.value)
                }
            }
            (sc["modelTurn"] as? JsonObject)?.let { mt ->
                (mt["parts"] as? JsonArray)?.forEach { p ->
                    val inl = (p as? JsonObject)?.get("inlineData") as? JsonObject ?: return@forEach
                    val data = (inl["data"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                    val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull() ?: return@forEach
                    openTurn()
                    val s = ShortArray(bytes.size / 2)
                    for (k in s.indices) s[k] = ((bytes[2 * k].toInt() and 0xFF) or (bytes[2 * k + 1].toInt() shl 8)).toShort()
                    playQ.add(s)
                    queued += s.size
                    main.post { if (state.value != LiveState.Speaking) { state.value = LiveState.Speaking } }
                }
            }
            if ((sc["interrupted"] as? JsonPrimitive)?.booleanOrNull == true) {
                turnOpen = false
                flushSpeaker()
                main.post { state.value = LiveState.Listening }
            }
            if ((sc["turnComplete"] as? JsonPrimitive)?.booleanOrNull == true) {
                turnOpen = false
                val text = said.value
                main.post {
                    onTurnDone(text)
                    // Stay "Speaking" until the speaker has played everything.
                    scope.launch {
                        while (alive.get() && (playQ.isNotEmpty() || playing())) kotlinx.coroutines.delay(60)
                        main.post { if (alive.get() && state.value == LiveState.Speaking) { state.value = LiveState.Listening; heard.value = "" } }
                    }
                }
            }
        }
        (o["toolCall"] as? JsonObject)?.let { tc ->
            val calls = (tc["functionCalls"] as? JsonArray).orEmpty()
            main.post { state.value = LiveState.Thinking }
            toolsRunning.incrementAndGet()
            scope.launch {
                try {
                val out = calls.mapNotNull { c ->
                    val co = c as? JsonObject ?: return@mapNotNull null
                    val name = (co["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val id = (co["id"] as? JsonPrimitive)?.contentOrNull
                    val args = co["args"] as? JsonObject ?: JsonObject(emptyMap())
                    val tool = tools.firstOrNull { it.name == name }
                    main.post { onToolCall(name, args) }
                    val res: JsonElement = try {
                        withContext(Dispatchers.IO) { tool?.run?.invoke(args) } ?: buildJsonObject { put("error", "unknown function") }
                    } catch (e: Exception) { buildJsonObject { put("error", e.message ?: "failed") } }
                    ActLog.add("AI", "tool $name ${args.toString().take(120)} → ${res.toString().take(120)}")
                    buildJsonObject {
                        id?.let { put("id", it) }; put("name", name)
                        put("response", res as? JsonObject ?: buildJsonObject { put("result", res) })
                    }
                }
                ws?.send(buildJsonObject { putJsonObject("toolResponse") { put("functionResponses", JsonArray(out)) } }.toString())
                } finally { toolsRunning.decrementAndGet() }
            }
        }
        if (o.containsKey("goAway")) { ActLog.add("AI", "live: server will close soon, moving"); model?.let { m -> runCatching { ws?.close(1000, "move") }; reconnect(m) } }
        return false
    }

    /** Type instead of talking (or the app's own first prompt, e.g. the welcome). */
    fun sendText(text: String) {
        if (!ready.get()) { pendingText = text; return }
        main.post { heard.value = ""; said.value = ""; spoken.value = "" }
        newTurn()
        // realtimeInput.text: the way the native-audio models take typed input (clientContent turns get error 1007).
        ws?.send(buildJsonObject { putJsonObject("realtimeInput") { put("text", text) } }.toString())
    }

    private fun newTurn() { textMarks.clear() }

    /** Audio from the shared mic (see [LiveMic]). */
    internal fun sendAudio(b64: String) {
        if (!ready.get() || !listening) return
        ws?.send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}")
    }

    /** Words heard so far: the transcript part whose audio has reached the speaker. Call every frame. */
    fun spokenNow(): String {
        val t = track ?: return said.value
        val head = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
        val played = head
        var best = spoken.value
        for (m in textMarks) { if (m.first <= played + 2400) best = m.second else break }
        if (!playing() && playQ.isEmpty() && state.value != LiveState.Speaking) best = said.value
        if (best != spoken.value) spoken.value = best
        return best
    }

    /** Loudness of what is playing right now (0..1), for the avatar's mouth. */
    fun level(): Float {
        val t = track ?: return 0f
        val head = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
        var lv = 0f
        while (true) {
            val f = levels.peek() ?: break
            if (f.first < head) levels.poll() else { lv = f.second; break }
        }
        if (levels.isEmpty() && !playing()) return 0f
        return lv
    }

    private fun playing(): Boolean {
        val t = track ?: return false
        val head = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
        return head < written - 240
    }

    // ── audio ──

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        LiveMic.route(ctx)
        usedSco = LiveMic.usedSco
        headset = LiveMic.headset
        // Speaker: 24 kHz mono, at media volume (follows the media slider, plays even on silent mode).
        val outRate = 24000
        val minOut = AudioTrack.getMinBufferSize(outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(if (usedSco) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(outRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minOut * 2, outRate / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() }
        }.getOrNull()
        scope.launch(Dispatchers.IO) {
            val t = track ?: return@launch
            while (alive.get()) {
                val s = playQ.poll()
                if (s == null) { Thread.sleep(8); continue }
                // 20 ms blocks: write, and remember each block's loudness against the frame it ends on.
                var off = 0
                while (off < s.size && alive.get()) {
                    val n = minOf(480, s.size - off)
                    var sum = 0.0
                    for (k in off until off + n) { val v = s[k] / 32768.0; sum += v * v }
                    val rms = sqrt(sum / n).toFloat()
                    written += n
                    levels.add(written to (rms * 4.2f).coerceIn(0f, 1f))
                    LiveMic.speakingUntil = System.currentTimeMillis() + 450
                    runCatching { t.write(s, off, n) }
                    off += n
                }
            }
        }
        if (!LiveMic.join(ctx, this)) main.post { onError("The microphone is busy.") }
    }

    /** One 40 ms mic chunk (from [LiveMic]). */
    internal fun onMic(buf: ShortArray, n: Int, rms: Float, bytes: ByteArray) {
        micLevel.value = (rms * 6f).coerceIn(0f, 1f)
        if (!listening) return
        // Speaker on (no headset) while one of them talks: only a clearly louder voice gets through
        // (so they don't hear themselves), quieter sound is sent as silence.
        val gate = muted || (!headset && System.currentTimeMillis() < LiveMic.speakingUntil && rms < 0.1f)
        for (k in 0 until n) { val v = if (gate) 0 else buf[k].toInt(); bytes[2 * k] = (v and 0xFF).toByte(); bytes[2 * k + 1] = ((v shr 8) and 0xFF).toByte() }
        sendAudio(Base64.encodeToString(bytes, 0, n * 2, Base64.NO_WRAP))
    }

    private fun flushSpeaker() {
        playQ.clear(); levels.clear(); textMarks.clear()
        track?.let { t -> runCatching { t.pause(); t.flush(); t.play() } }
        written = 0; queued = 0
    }

    private fun fail(msg: String) {
        main.post { state.value = LiveState.Failed; onError(msg) }
        close()
    }

    private fun end() { main.post { if (state.value != LiveState.Failed) state.value = LiveState.Ended }; close() }

    fun close() {
        if (!alive.getAndSet(false)) return
        runCatching { ws?.close(1000, "bye") }
        ws = null
        LiveMic.leave(this)
        runCatching { track?.stop() }; runCatching { track?.release() }; track = null
        scope.cancel()
    }
}

/**
 * The one microphone all live chats share (Android lets only one app recording run with echo cancelling).
 * Routes to earbuds / a headset when one is connected, and hands every chunk to each joined chat.
 */
object LiveMic {
    private val sessions = java.util.concurrent.CopyOnWriteArrayList<GeminiLive>()
    private var record: AudioRecord? = null
    @Volatile var usedSco = false; private set
    @Volatile var headset = false; private set
    /** Until when one of the assistants is audible on the speaker (echo guard). */
    @Volatile var speakingUntil = 0L
    private var routed = false

    @Synchronized fun route(ctx: Context) {
        if (routed) return
        routed = true
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching {
            am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        }.getOrNull() else null
        headset = dev != null || runCatching { am.isBluetoothA2dpOn || am.isWiredHeadsetOn }.getOrDefault(false)
        usedSco = false
        if (dev != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION; am.setCommunicationDevice(dev); usedSco = true }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && runCatching { am.isBluetoothA2dpOn && am.isBluetoothScoAvailableOffCall }.getOrDefault(false)) {
            runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION; @Suppress("DEPRECATION") am.startBluetoothSco(); @Suppress("DEPRECATION") am.isBluetoothScoOn = true; usedSco = true }
        }
        preferred = dev
    }
    private var preferred: AudioDeviceInfo? = null
    private var appCtx: Context? = null

    @SuppressLint("MissingPermission")
    @Synchronized fun join(ctx: Context, s: GeminiLive): Boolean {
        appCtx = ctx.applicationContext
        if (!sessions.contains(s)) sessions.add(s)
        if (record != null) return true
        val inRate = 16000
        val minIn = AudioRecord.getMinBufferSize(inRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, inRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn * 2, 6400))
        }.getOrNull()
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) { runCatching { r?.release() }; return false }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) preferred?.let { d -> runCatching { r.preferredDevice = d } }
        runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId)?.enabled = true }
        runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId)?.enabled = true }
        runCatching { if (android.media.audiofx.AutomaticGainControl.isAvailable()) android.media.audiofx.AutomaticGainControl.create(r.audioSessionId)?.enabled = false }
        record = r
        r.startRecording()
        Thread({
            val buf = ShortArray(640)
            val bytes = ByteArray(1280)
            while (record === r) {
                val n = runCatching { r.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n <= 0) { if (n < 0) Thread.sleep(20); continue }
                var sum = 0.0
                for (k in 0 until n) { val v = buf[k] / 32768.0; sum += v * v }
                val rms = sqrt(sum / n).toFloat()
                for (s2 in sessions) runCatching { s2.onMic(buf, n, rms, bytes) }
            }
            runCatching { r.stop() }; runCatching { r.release() }
        }, "live-mic").apply { isDaemon = true }.start()
        return true
    }

    @Synchronized fun leave(s: GeminiLive) {
        sessions.remove(s)
        if (sessions.isNotEmpty()) return
        record = null
        if (routed) {
            val am = appCtx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (usedSco && am != null) runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice() else { @Suppress("DEPRECATION") am.stopBluetoothSco() }
                am.mode = AudioManager.MODE_NORMAL
            }
            routed = false; usedSco = false; preferred = null
        }
    }
}
