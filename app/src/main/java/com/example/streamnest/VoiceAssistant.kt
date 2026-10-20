package com.example.streamnest

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice as TtsVoice
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.CompositingStrategy
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * The round glass AI button next to the TV / Movies capsule.
 * [shrink] 0..1: while scrolling down it shrinks, slides to the right edge and shows only a mic.
 */
@Composable
fun AiOrbButton(vm: AppViewModel, size: androidx.compose.ui.unit.Dp = 54.dp, shrink: State<Float>? = null, onClick: () -> Unit) {
    // Performance notes (scroll smoothness, weak TV GPUs):
    //  - every [shrink] read happens inside graphicsLayer blocks (draw phase), so the shrink/expand animation
    //    never recomposes this button;
    //  - the rim spin is an Animatable that only runs while the orb is fully expanded and resting: an always-on
    //    infinite animation forced a new frame of the whole screen 60 times a second, even while the list scrolled
    //    and the capsule was animating. It pauses where it is and resumes from the same angle;
    //  - brushes / strokes are built once per size (drawWithCache), not on every draw.
    val angle = remember { Animatable(0f) }
    LaunchedEffect(shrink) {
        snapshotFlow { (shrink?.value ?: 0f) <= 0f }
            .distinctUntilChanged()
            .collectLatest { spin ->
                if (!spin) return@collectLatest
                while (true) {
                    angle.snapTo(angle.value % 360f)
                    angle.animateTo(angle.value + 360f, tween(7000, easing = LinearEasing))
                }
            }
    }
    Box(
        Modifier.graphicsLayer {
            val v = shrink?.value ?: 0f
            val sc = 1f - 0.28f * v
            scaleX = sc; scaleY = sc
            translationX = v * 14.dp.toPx()
            translationY = v * 8.dp.toPx()
        }.size(size).clip(CircleShape)
            .background(OrbFill)
            .clickable(onClick = onClick),
        Alignment.Center,
    ) {
        // Glowing blue → pink rim, slowly turning (rotation is a layer property: the circle itself is not redrawn).
        Spacer(
            Modifier.fillMaxSize()
                .graphicsLayer { rotationZ = angle.value }
                .drawWithCache {
                    val r = this.size.minDimension / 2f - 1.5.dp.toPx()
                    val stroke = Stroke(width = 2.5.dp.toPx())
                    onDrawBehind { drawCircle(OrbRim, radius = r, style = stroke) }
                }
        )
        // The logo itself turns too (slowly, the other way round), with a soft breathing scale.
        Box(Modifier.graphicsLayer {
            alpha = 1f - (shrink?.value ?: 0f)
            compositingStrategy = CompositingStrategy.ModulateAlpha
            rotationZ = -angle.value * 0.75f
            val b = 1f + 0.05f * kotlin.math.sin(Math.toRadians(angle.value * 2.0)).toFloat()
            scaleX = b; scaleY = b
        }) { AiLogo(vm, Modifier.size(size * 0.6f)) }
        Icon(Icons.Default.Mic, "AI assistant", Modifier.size(size * 0.42f).graphicsLayer {
            alpha = shrink?.value ?: 0f
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }, tint = Color.White)
    }
}

// Xstream: the dock's red — a dark ember inside, a red → orange rim with a fading tail as it spins.
private val OrbFill = Brush.radialGradient(listOf(Color(0xFF3A0D12), Color(0xFF1A0A0D), Color(0xFF0D0709)))
private val OrbRim = Brush.sweepGradient(listOf(Color(0xFFE50914), Color(0xFFFF6A3D), Color(0x33E50914), Color(0xFFE50914)))

// ───────────── Live voice conversation with X ─────────────

private enum class XState { Idle, Listening, Thinking, Speaking }

private class ChatLine(val id: Long, val user: Boolean, val text: String, val items: List<Meta> = emptyList(), val rowTitle: String = "")

/** Kept for compatibility: X now says hello every time you open it. */
private var greetedThisSession = false

/**
 * Everything the voice overlay does: listening (SpeechRecognizer), talking (TextToSpeech),
 * and the hands-free loop listen → think → speak → listen. All methods run on the main thread.
 */
private class XSession(private val ctx: Context, private val vm: AppViewModel, private val scope: CoroutineScope) {
    private val convo = XConversation(vm)
    private val main = Handler(Looper.getMainLooper())

    val lines = mutableStateListOf<ChatLine>()
    var state by mutableStateOf(XState.Idle)
    var partial by mutableStateOf("")
    var hint by mutableStateOf("")
    var level by mutableFloatStateOf(0f)
    var voiceOut by mutableStateOf(vm.store("aiVoiceOut") != "no")
    /** The assistant line being spoken right now and how far the voice has got (for the typewriter). */
    var speakingId by mutableLongStateOf(-1L)
    var spokenChars by mutableIntStateOf(0)
    val revealed = HashSet<Long>()

    var onOpen: (Meta) -> Unit = {}
    var requestMic: () -> Unit = {}

    private var alive = true
    private var begun = false
    private var waitingForTts = false
    private var handsFree = true
    private var lastViaVoice = true
    private var rec: SpeechRecognizer? = null
    private var token = 0
    private var busyRetries = 0
    private var silentRetries = 0
    private var langFallback = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voiceIsHindi: Boolean? = null
    private var utt: String? = null
    private var pendingOpen: Meta? = null
    private var job: Job? = null
    private var seq = 0
    private var nextId = 1L
    private var after: Runnable? = null

    // ── lifecycle ──

    fun start() {
        alive = true
        tts = try {
            TextToSpeech(ctx) { status -> main.post { onTtsInit(status) } }
        } catch (e: Exception) { null }
        main.post { if (alive) { if (micGranted()) scheduleBegin() else requestMic() } }
    }

    fun onMic(ok: Boolean) {
        if (!alive) return
        if (ok) { busyRetries = 0; silentRetries = 0; if (begun) listen() else scheduleBegin() }
        else {
            state = XState.Idle; hint = "Tap to talk"
            Snack.show("Allow microphone access to talk to X. You can still type.")
        }
    }

    /** Wait (briefly) for the voice engine so the hello can be spoken, then begin. */
    private fun scheduleBegin() {
        if (begun) return
        if (voiceOut && tts != null && !ttsReady) {
            waitingForTts = true
            main.postDelayed({ if (waitingForTts) { waitingForTts = false; begin() } }, 1800)
        } else begin()
    }

    private fun begin() {
        if (begun || !alive) return
        begun = true
        if (ttsReady || !voiceOut) greet() else listen()
    }

    private fun onTtsInit(status: Int) {
        if (!alive) return
        val t = tts
        if (status == TextToSpeech.SUCCESS && t != null) {
            // A touch slower than the default with natural pauses (see humanize) sounds far less robotic.
            runCatching { t.setSpeechRate((vm.store("aiVoiceRate")?.toFloatOrNull() ?: 0.96f).coerceIn(0.7f, 1.3f)); t.setPitch(1.0f) }
            runCatching {
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { main.post { if (utteranceId != null && utteranceId == utt) afterSpeech() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { main.post { if (utteranceId != null && utteranceId == utt) afterSpeech() } }
                    override fun onError(utteranceId: String?, errorCode: Int) { main.post { if (utteranceId != null && utteranceId == utt) afterSpeech() } }
                    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                        main.post { if (utteranceId != null && utteranceId == utt) spokenChars = end }
                    }
                })
            }
            ttsReady = true
        } else {
            ttsReady = false
            runCatching { t?.shutdown() }
            tts = null
        }
        if (waitingForTts) { waitingForTts = false; begin() }
    }

    /** App went to the background: stop the mic and the voice. */
    fun pause() {
        handsFree = false
        cancelAfter()
        dropRecognizer()
        stopSpeaking()
        level = 0f
        if (state != XState.Thinking) state = XState.Idle
        hint = "Tap to talk"
    }

    fun release() {
        alive = false
        main.removeCallbacksAndMessages(null)
        job?.cancel()
        dropRecognizer()
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }

    // ── listening ──

    private fun micGranted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun dropRecognizer() {
        token++
        rec?.let { r -> runCatching { r.cancel() }; runCatching { r.destroy() } }
        rec = null
    }

    private fun recLanguage(): String {
        val custom = vm.texts.value["aiVoiceLang"]?.trim().orEmpty()
        val d = Locale.getDefault()
        if (langFallback) return d.toLanguageTag()
        if (custom.isNotEmpty()) return custom
        return when {
            d.country.equals("IN", true) && d.language == "hi" -> "hi-IN"
            d.country.equals("IN", true) || d.language == "hi" -> "en-IN"
            else -> d.toLanguageTag()
        }
    }

    fun listen() {
        if (!alive) return
        cancelAfter()
        stopSpeaking()
        if (!micGranted()) { state = XState.Idle; hint = "Tap to talk"; requestMic(); return }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            state = XState.Idle; hint = "Voice input isn't available on this phone. Type instead."
            return
        }
        dropRecognizer()
        handsFree = true
        partial = ""; hint = ""; level = 0f
        state = XState.Listening
        val my = token
        try {
            val r = SpeechRecognizer.createSpeechRecognizer(ctx)
            rec = r
            r.setRecognitionListener(listenerFor(my))
            val lang = recLanguage()
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
            )
        } catch (e: Exception) {
            rec = null
            state = XState.Idle; hint = "The mic is busy. Tap to talk."
        }
    }

    private fun stillListening(my: Int) = alive && my == token && state == XState.Listening

    /** Nothing was heard: listen once more, then rest. */
    private fun onSilence(my: Int) {
        if (silentRetries < 1) {
            silentRetries++
            dropRecognizer()
            val again = token
            main.postDelayed({ if (alive && again == token && state == XState.Listening) listen() }, 250)
        } else {
            silentRetries = 0
            dropRecognizer()
            handsFree = false
            state = XState.Idle; hint = "Tap to talk"
        }
    }

    private fun listenerFor(my: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { if (my == token) level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { if (my == token) level = 0f }
        override fun onError(error: Int) {
            if (!stillListening(my)) return
            level = 0f
            if (error == 12 || error == 13) langFallback = true
            // Right after speaking the phone's recogniser can still be busy: quietly retry.
            if (busyRetries < 3 && (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT || error == 11 || error == 12 || error == 13)) {
                busyRetries++
                dropRecognizer()
                val again = token
                main.postDelayed({ if (alive && again == token && state == XState.Listening) listen() }, 400L * busyRetries)
                return
            }
            busyRetries = 0
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    val p = partial.trim()
                    if (p.isNotEmpty()) { dropRecognizer(); handleUser(p, voice = true) } else onSilence(my)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    dropRecognizer(); handsFree = false; state = XState.Idle; hint = "Tap to talk"
                    Snack.show("Allow microphone access to talk to X.")
                }
                else -> { dropRecognizer(); handsFree = false; state = XState.Idle; hint = "Tap to talk" }
            }
        }
        override fun onResults(results: Bundle?) {
            if (!stillListening(my)) return
            busyRetries = 0
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty().ifEmpty { partial.trim() }
            level = 0f
            if (text.isEmpty()) onSilence(my) else { silentRetries = 0; token++; handleUser(text, voice = true) }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            if (my != token) return
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) partial = it }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ── thinking ──

    private fun addLine(user: Boolean, text: String, items: List<Meta> = emptyList(), row: String = ""): Long {
        val id = nextId++
        lines.add(ChatLine(id, user, text, items, row))
        return id
    }

    fun handleUser(text: String, voice: Boolean) {
        val q = text.trim()
        if (q.isEmpty() || !alive) return
        lastViaVoice = voice
        if (voice) handsFree = true
        cancelAfter()
        stopSpeaking()
        if (state == XState.Listening) dropRecognizer()
        job?.cancel()
        partial = ""; hint = ""; level = 0f
        addLine(true, q)
        state = XState.Thinking
        val my = ++seq
        job = scope.launch {
            val r = try {
                convo.reply(q)
            } catch (e: CancellationException) {
                return@launch
            } catch (e: Exception) {
                XResult("Sorry, something went wrong. Try again?", emptyList(), "", null, true)
            }
            if (!alive || my != seq) return@launch
            val id = addLine(false, r.say, r.items, r.rowTitle)
            speak(id, r.say, r.open)
        }
    }

    // ── speaking ──

    private fun greet() {
        greetedThisSession = true
        val name = vm.current.value?.name?.trim()?.takeIf { it.isNotBlank() && it != "Me" }
        val n = name?.let { " $it" } ?: ""
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val part = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Hey night owl" }
        val last = vm.progress.value.firstOrNull()?.title?.substringBefore("  S")?.takeIf { it.isNotBlank() }
        val hello = if (Locale.getDefault().language == "hi") {
            listOfNotNull(
                "Hey$n! Aaj kya dekhne ka mood hai?",
                "Hi$n! Movie ya series, kya chalega aaj?",
                last?.let { "Welcome back$n! $it aage dekhein, ya kuch naya?" },
            ).random()
        } else listOfNotNull(
            "$part$n! What are you in the mood for?",
            "Hi$n! Movie night or a quick episode?",
            "Hey$n, what are we watching today? A thriller, something funny, or a surprise?",
            last?.let { "Welcome back$n! Want to carry on with $it, or try something new?" },
            last?.let { "$part$n! Shall I pick up $it where you left off, or find you something fresh?" },
        ).random()
        val id = addLine(false, hello)
        lastViaVoice = true
        speak(id, hello, null)
    }

    private fun speak(lineId: Long, text: String, thenOpen: Meta?) {
        pendingOpen = thenOpen
        val t = tts
        if (!voiceOut || t == null || !ttsReady || text.isBlank()) {
            // Text only: let the typewriter finish, then carry on.
            speakingId = -1L
            state = XState.Idle
            postAfter(minOf(400L + text.length * 16L, 2200L)) { finishTurn() }
            return
        }
        pickVoice(t, hasDevanagari(text))
        val spoken = humanize(text)
        val id = "x_${System.nanoTime()}"
        utt = id
        spokenChars = 0
        speakingId = lineId
        state = XState.Speaking
        val res = try { t.speak(spoken, TextToSpeech.QUEUE_FLUSH, Bundle(), id) } catch (e: Exception) { TextToSpeech.ERROR }
        if (res != TextToSpeech.SUCCESS) {
            utt = null; speakingId = -1L; state = XState.Idle
            postAfter(minOf(400L + text.length * 16L, 2200L)) { finishTurn() }
        }
    }

    private fun afterSpeech() {
        utt = null
        speakingId = -1L
        // A short pause so the mic doesn't hear the end of our own voice.
        postAfter(220) { finishTurn() }
    }

    /** After a reply: open the title it asked for, or keep the conversation going hands-free. */
    private fun finishTurn() {
        if (!alive) return
        val o = pendingOpen
        pendingOpen = null
        if (o != null) { openTitle(o); return }
        if (handsFree && lastViaVoice) listen() else { state = XState.Idle; if (hint.isEmpty()) hint = "Tap to talk" }
    }

    private fun stopSpeaking() {
        utt = null
        speakingId = -1L
        runCatching { tts?.stop() }
    }

    private fun postAfter(ms: Long, block: () -> Unit) {
        cancelAfter()
        val r = Runnable { after = null; if (alive) block() }
        after = r
        main.postDelayed(r, ms)
    }

    private fun cancelAfter() { after?.let { main.removeCallbacks(it) }; after = null }

    /** Best voice for the reply language: matching locale, highest quality, lowest latency, installed. */
    private fun pickVoice(t: TextToSpeech, hindi: Boolean) {
        val all: List<TtsVoice> = (try { t.voices?.toList() } catch (e: Exception) { null }) ?: emptyList()
        // The voice you chose in Help & Settings › Integrations › Her voice — applied before every reply (and checked:
        // some engines only take a voice after its language is set first).
        // One chosen voice speaks every reply (a Hindi voice also reads English); only Hindi text with a non-Hindi voice
        // chosen falls back to the Hindi pick / an automatic Hindi voice.
        val main = vm.store("aiVoice")?.let { n -> all.firstOrNull { it.name == n } }
        val chosen = when {
            main != null && (!hindi || runCatching { main.locale.language }.getOrNull() in setOf("hi", "hin")) -> main.name
            else -> vm.store(if (hindi) "aiVoiceHi" else "aiVoiceEn")
        }
        chosen?.let { name ->
            all.firstOrNull { it.name == name }?.let { v ->
                runCatching { t.setVoice(v) }
                if (runCatching { t.voice?.name }.getOrNull() != v.name) { runCatching { t.setLanguage(v.locale) }; runCatching { t.setVoice(v) } }
                if (runCatching { t.voice?.name }.getOrNull() == v.name) { voiceIsHindi = hindi; return }
            }
        }
        if (voiceIsHindi == hindi) return
        voiceIsHindi = hindi
        val usable = all.filter { v ->
            runCatching { v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }.getOrDefault(true)
        }
        val d = Locale.getDefault()
        fun lang(l: Locale?, code: String) = l != null && (l.language.equals(code, true) || (code == "hi" && l.language.equals("hin", true)) || (code == "en" && l.language.equals("eng", true)))
        fun country(l: Locale?, c: String) = l != null && (l.country.equals(c, true) || (c == "IN" && l.country.equals("IND", true)) ||
            (c == "US" && l.country.equals("USA", true)) || (c == "GB" && l.country.equals("GBR", true)))
        val india = d.country.equals("IN", true) || d.language == "hi"
        val wants: List<(Locale?) -> Boolean> = if (hindi) listOf<(Locale?) -> Boolean>({ l -> lang(l, "hi") }) else buildList<(Locale?) -> Boolean> {
            if (india) add({ l: Locale? -> lang(l, "en") && country(l, "IN") })
            if (d.language == "en" && !india) add({ l: Locale? -> lang(l, "en") && country(l, d.country) })
            add({ l: Locale? -> lang(l, "en") && country(l, "US") })
            add({ l: Locale? -> lang(l, "en") && country(l, "GB") })
            add({ l: Locale? -> lang(l, "en") && country(l, "IN") })
            add({ l: Locale? -> lang(l, "en") })
        }
        val order = compareBy<TtsVoice>({ it.quality }, { if (it.isNetworkConnectionRequired) 0 else 1 }, { -it.latency })
        for (ok in wants) {
            val best = usable.filter { v -> ok(runCatching { v.locale }.getOrNull()) }.maxWithOrNull(order) ?: continue
            if (runCatching { t.setVoice(best) }.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.SUCCESS) return
        }
        runCatching { t.setLanguage(if (hindi) Locale.forLanguageTag("hi-IN") else if (india) Locale.forLanguageTag("en-IN") else d) }
    }

    // ── user actions ──

    /** Tap on the orb: talk / stop / interrupt. */
    fun tapOrb() {
        when (state) {
            XState.Listening -> {
                val p = partial.trim()
                dropRecognizer()
                if (p.isNotEmpty()) handleUser(p, voice = true)
                else { handsFree = false; level = 0f; state = XState.Idle; hint = "Tap to talk" }
            }
            XState.Speaking -> { pendingOpen = null; busyRetries = 0; silentRetries = 0; listen() }   // barge-in
            XState.Thinking -> { job?.cancel(); seq++; busyRetries = 0; silentRetries = 0; listen() }
            XState.Idle -> { pendingOpen = null; busyRetries = 0; silentRetries = 0; listen() }
        }
    }

    /** The keyboard opened: stop the mic so it doesn't fight with typing. */
    fun startTyping() {
        handsFree = false
        cancelAfter()
        if (state == XState.Listening) { dropRecognizer(); state = XState.Idle }
        if (state == XState.Speaking) { stopSpeaking(); state = XState.Idle }
        hint = ""
    }

    fun toggleVoice() {
        voiceOut = !voiceOut
        vm.storePut("aiVoiceOut", if (voiceOut) "yes" else "no")
        if (!voiceOut && state == XState.Speaking) { stopSpeaking(); state = XState.Idle; postAfter(300) { finishTurn() } }
    }

    fun openTitle(m: Meta) {
        cancelAfter()
        handsFree = false
        dropRecognizer()
        stopSpeaking()
        onOpen(m)
    }
}

/**
 * Full-screen live conversation with X, the Xstream movie buddy.
 * Hands-free: it listens, answers out loud and listens again. Tap the orb to talk, stop or interrupt.
 */
@Composable
fun VoiceAssistant(vm: AppViewModel, onClose: () -> Unit, onOpen: (Meta) -> Unit, firstAsk: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = remember { XSession(ctx, vm, scope) }
    s.onOpen = onOpen

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> s.onMic(ok) }
    s.requestMic = { runCatching { askMic.launch(Manifest.permission.RECORD_AUDIO) } }

    DisposableEffect(Unit) {
        s.start()
        // Xstream: opened from voice search with what was said.
        firstAsk?.takeIf { it.isNotBlank() }?.let { s.handleUser(it, voice = false) }
        onDispose { s.release() }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) s.pause() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    BackHandler { onClose() }

    val focus = LocalFocusManager.current
    var typed by remember { mutableStateOf("") }
    var typingFocus by remember { mutableStateOf(false) }
    val texts by vm.texts.collectAsState()
    val ready = remember(texts) { vm.aiReady() }
    val provider = remember(texts) { vm.aiProvider() }
    val profile by vm.current.collectAsState()

    fun send() {
        val q = typed.trim()
        if (q.isEmpty()) return
        typed = ""
        focus.clearFocus()
        s.handleUser(q, voice = false)
    }

    val bg = Bg
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(bg.copy(alpha = 0.94f), Color(0xF20B0814), bg.copy(alpha = 0.98f))))
            .background(Brush.radialGradient(listOf(Accent.copy(alpha = 0.14f), Color.Transparent), radius = 1400f))
            .quietClick { focus.clearFocus() },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // ── Header ──
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFF2A3F8F), Color(0xFF2B1240))))
                        .border(1.dp, Brush.sweepGradient(listOf(Accent, Palette.accent2, Color(0xFF6FB6FF), Accent)), CircleShape),
                    Alignment.Center,
                ) { AiLogo(vm, Modifier.size(20.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("X · your movie buddy", fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White, maxLines = 1)
                    val label = when (s.state) {
                        XState.Listening -> "Listening…"
                        XState.Thinking -> "Thinking…"
                        XState.Speaking -> "Speaking…"
                        XState.Idle -> if (ready) "${provider.label} · ${if (s.voiceOut) "voice on" else "text only"}" else "Offline mode · add a key in Integrations"
                    }
                    Text(
                        label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (s.state == XState.Idle) Color(0xFF8A94A8) else Accent.copy(alpha = 0.95f).let { if (it.luminanceLow()) Color(0xFFB9C2FF) else it },
                    )
                }
                HeaderIcon(if (s.voiceOut) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, if (s.voiceOut) "Mute voice" else "Turn voice on", s.voiceOut) { s.toggleVoice() }
                HeaderIcon(Icons.Default.Close, "Close", false) { onClose() }
            }

            // ── Transcript ──
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val showPartial = s.state == XState.Listening && s.partial.isNotBlank()
                if (s.lines.isEmpty() && !showPartial) {
                    EmptyChat(profile?.name?.takeIf { it.isNotBlank() && it != "Me" }) { s.handleUser(it, voice = true) }
                } else {
                    val listState = rememberLazyListState()
                    val total = s.lines.size + if (showPartial) 1 else 0
                    LaunchedEffect(total) { if (total > 0) listState.animateScrollToItem(0) }
                    LazyColumn(
                        Modifier.fillMaxSize(), state = listState, reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                    ) {
                        if (showPartial) item(key = "partial") { UserBubble(s.partial, live = true) }
                        else if (s.lines.none { it.user }) item(key = "chips") {
                            Box(Modifier.fillMaxWidth().padding(top = 6.dp)) { SuggestionChips { s.handleUser(it, voice = true) } }
                        }
                        items(s.lines.asReversed(), key = { it.id }) { line ->
                            if (line.user) UserBubble(line.text, live = false)
                            else AssistantBubble(line, s) { m -> s.openTitle(m) }
                        }
                    }
                }
            }

            // ── Orb ──
            val orbSize by animateDpAsState(if (typingFocus) 58.dp else 104.dp, tween(300), label = "orbSize")
            Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                XOrb(s.state, s.level, orbSize) { focus.clearFocus(); s.tapOrb() }
                val sub = when (s.state) {
                    XState.Listening -> if (s.partial.isBlank()) "I'm all ears…" else "Tap when you're done"
                    XState.Speaking -> "Tap to interrupt"
                    XState.Thinking -> "Tap to ask something else"
                    XState.Idle -> s.hint.ifBlank { "Tap to talk" }
                }
                if (!typingFocus) Text(sub, Modifier.padding(top = 2.dp), fontSize = 12.sp, color = Color(0xFF8A94A8), textAlign = TextAlign.Center)
            }

            // ── Type instead ──
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)
                    .clip(RoundedCornerShape(26.dp)).background(Color.White.copy(alpha = 0.07f))
                    .border(1.dp, Color.White.copy(alpha = if (typingFocus) 0.22f else 0.10f), RoundedCornerShape(26.dp))
                    .padding(start = 18.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                    if (typed.isEmpty()) Text("Type to X…", fontSize = 15.sp, color = Color(0xFF8A94A8), maxLines = 1)
                    BasicTextField(
                        typed, { typed = it },
                        Modifier.fillMaxWidth().onFocusChanged { f ->
                            if (f.isFocused && !typingFocus) s.startTyping()
                            typingFocus = f.isFocused
                        }.tvOkKeyboard(),
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        cursorBrush = SolidColor(Accent),
                        keyboardOptions = tvKeyboard(KeyboardOptions(imeAction = ImeAction.Send)),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                    )
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background(if (typed.isNotBlank()) Brush.linearGradient(listOf(Accent, Palette.accent2)) else SolidColor(Color.White.copy(alpha = 0.08f)))
                        .quietClick { send() },
                    Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", Modifier.size(18.dp), tint = if (typed.isNotBlank()) OnAccent else Color(0xFF8A94A8)) }
            }
        }
    }
}

/** True for very dark accent colours that would vanish on the dark sheet. */
private fun Color.luminanceLow(): Boolean = red * 0.299f + green * 0.587f + blue * 0.114f < 0.18f

@Composable
private fun HeaderIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 2.dp).size(42.dp).clip(CircleShape)
            .background(if (on) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .quietClick(onClick),
        Alignment.Center,
    ) { Icon(icon, label, Modifier.size(22.dp), tint = Color.White) }
}

@Composable
private fun EmptyChat(name: String?, onPick: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (name != null) "Hey $name, I'm X" else "Hey, I'm X", fontFamily = BrandFont, fontWeight = FontWeight.Bold,
            fontSize = 26.sp, color = Color.White, textAlign = TextAlign.Center,
        )
        Text(
            "Talk to me like a friend. Tell me your mood, a vibe, or a title.", Modifier.padding(top = 8.dp),
            fontSize = 14.sp, color = Color(0xFF9AA3B5), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        SuggestionChips(onPick)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionChips(onPick: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Something like Interstellar", "A light comedy tonight", "What's trending?", "Hindi thriller").forEach { c ->
            Text(
                c,
                Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.07f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                    .quietClick { onPick(c) }.padding(horizontal = 14.dp, vertical = 9.dp),
                fontSize = 13.sp, color = Color.White,
            )
        }
    }
}

@Composable
private fun UserBubble(text: String, live: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            Modifier.widthIn(max = 290.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp))
                .background(
                    if (live) SolidColor(Color.White.copy(alpha = 0.08f))
                    else Brush.linearGradient(listOf(Accent.copy(alpha = 0.92f), Palette.accent2.copy(alpha = 0.85f)))
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            fontSize = 15.sp, lineHeight = 20.sp,
            color = if (live) Color.White.copy(alpha = 0.7f) else OnAccent,
            fontStyle = if (live) FontStyle.Italic else FontStyle.Normal,
        )
    }
}

@Composable
private fun AssistantBubble(line: ChatLine, s: XSession, onOpen: (Meta) -> Unit) {
    val full = line.text
    var shown by remember(line.id) { mutableIntStateOf(if (s.revealed.contains(line.id)) full.length else 0) }
    LaunchedEffect(line.id) {
        while (shown < full.length) {
            val speaking = s.speakingId == line.id
            delay(if (speaking) 58L else 14L)
            val next = if (speaking) maxOf(shown + 1, minOf(s.spokenChars, full.length)) else shown + 2
            shown = next.coerceAtMost(full.length)
        }
        s.revealed.add(line.id)
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            buildAnnotatedString {
                append(full.take(shown))
                // The rest is laid out invisibly so the bubble doesn't jump while it types.
                withStyle(SpanStyle(color = Color.Transparent)) { append(full.drop(shown)) }
            },
            Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp))
                .background(Color.White.copy(alpha = 0.09f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            fontSize = 15.sp, lineHeight = 21.sp, color = Color.White,
        )
        if (line.items.isNotEmpty()) {
            if (line.rowTitle.isNotBlank()) Text(
                line.rowTitle, Modifier.padding(start = 4.dp, top = 10.dp, bottom = 6.dp),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF9AA3B5), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            LazyRow(
                Modifier.fillMaxWidth().padding(top = if (line.rowTitle.isBlank()) 8.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(line.items.distinctBy { it.type + it.id }, key = { it.type + it.id }) { m ->
                    Column(Modifier.width(92.dp).quietClick { onOpen(m) }) {
                        AsyncImage(
                            m.poster, m.name,
                            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(Surface1),
                            contentScale = ContentScale.Crop,
                        )
                        Text(m.name, Modifier.padding(top = 4.dp, start = 2.dp), fontSize = 11.sp, color = Color(0xFFC9CFDA), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** The living orb: breathes when idle, follows your voice while listening, shimmers while thinking, pulses while speaking. */
@Composable
private fun XOrb(state: XState, level: Float, orbSize: Dp, onTap: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "xorb")
    val breathe by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "spin")
    val fast by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "fast")
    val phase by inf.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1150, easing = LinearEasing)), label = "phase")
    val lvl by animateFloatAsState(if (state == XState.Listening) level else 0f, tween(110), label = "lvl")
    val listenA by animateFloatAsState(if (state == XState.Listening) 1f else 0f, tween(350), label = "la")
    val thinkA by animateFloatAsState(if (state == XState.Thinking) 1f else 0f, tween(350), label = "ta")
    val speakA by animateFloatAsState(if (state == XState.Speaking) 1f else 0f, tween(350), label = "sa")
    val accent = Accent
    val accent2 = Palette.accent2

    Box(Modifier.size(orbSize * 1.7f), Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val baseR = orbSize.toPx() / 2f
            val idleA = (1f - listenA - thinkA - speakA).coerceIn(0f, 1f)
            val wave = sin(phase) * 0.6f + sin(phase * 2f + 1.3f) * 0.4f
            val scale = 1f + idleA * 0.045f * breathe + listenA * 0.22f * lvl + speakA * 0.06f * wave + thinkA * 0.02f * breathe
            val r = baseR * scale
            val energy = (idleA * (0.25f + 0.25f * breathe) + listenA * (0.35f + 0.65f * lvl) + speakA * (0.55f + 0.3f * wave) + thinkA * 0.5f).coerceIn(0f, 1f)

            // Soft glow
            val glowR = baseR * 1.7f
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.18f + 0.32f * energy), accent2.copy(alpha = 0.08f), Color.Transparent), c, glowR), glowR, c)

            // Listening: accent rings that open up with your voice
            if (listenA > 0.01f) for (i in 0..2) {
                val rr = baseR * (1.07f + 0.11f * i + lvl * 0.28f * (i + 1) / 3f)
                drawCircle(accent.copy(alpha = (0.65f - 0.2f * i) * listenA), rr.coerceAtMost(glowR), c, style = Stroke(2.dp.toPx()))
            }
            // Speaking: rhythmic ripples
            if (speakA > 0.01f) for (i in 0..2) {
                val p = ((phase / (2f * PI.toFloat())) + i / 3f) % 1f
                drawCircle(accent2.copy(alpha = (1f - p) * 0.5f * speakA), baseR * (1.0f + 0.62f * p), c, style = Stroke(2.5.dp.toPx()))
            }

            // Core: turning colour sphere
            rotate(spin + fast * thinkA, c) {
                drawCircle(Brush.sweepGradient(listOf(accent, accent2, Color(0xFF6FB6FF), Color(0xFF9B5CFF), accent), c), r, c)
            }
            // Depth + glass highlight
            drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)), c, r), r, c)
            val hl = Offset(c.x - r * 0.28f, c.y - r * 0.34f)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0f)), hl, r * 0.62f), r * 0.62f, hl)

            // Thinking: shimmer arc running around the orb
            if (thinkA > 0.01f) rotate(fast * 1.4f, c) {
                val ar = r * 1.12f
                drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.9f * thinkA), Color.Transparent), c),
                    0f, 140f, false, Offset(c.x - ar, c.y - ar), Size(ar * 2, ar * 2), style = Stroke(3.dp.toPx()),
                )
            }
        }
        Box(Modifier.size(orbSize).clip(CircleShape).quietClick(onTap), Alignment.Center) {
            val micA = (1f - listenA - thinkA - speakA).coerceIn(0f, 1f)
            Icon(Icons.Default.Mic, "Talk", Modifier.size(orbSize * 0.34f).graphicsLayer { alpha = micA }, tint = Color.White)
        }
    }
}


/**
 * Text as a person would say it: no emoji, markdown or list symbols read out loud, and short pauses at line breaks
 * and dashes (the voice engine pauses at commas and full stops).
 */
internal fun humanize(text: String): String = text
    .replace(Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]"), "")
    .replace(Regex("[*_#`>|~]+"), "")
    .replace(Regex("^\\s*[-•]\\s*", RegexOption.MULTILINE), "")
    .replace(Regex("\\s+[—–-]\\s+"), ", ")
    .replace(Regex("\\n{2,}"), ". ")
    .replace("\n", ", ")
    .replace(Regex("\\s{2,}"), " ")
    .replace(Regex("[,.]\\s*([,.])"), "$1")
    .trim()


/**
 * Help & Settings › Integrations › Her voice: the phone's natural voices (Indian English and Hindi first), each with a
 * "Hear" sample, plus the talking speed. Saved on this phone (voices differ between phones).
 */
@Composable
fun AiVoicePicker(vm: AppViewModel) {
    val ctx = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    val tts = remember { arrayOfNulls<TextToSpeech>(1) }
    DisposableEffect(Unit) {
        tts[0] = TextToSpeech(ctx) { st -> ready = st == TextToSpeech.SUCCESS }
        onDispose { runCatching { tts[0]?.stop(); tts[0]?.shutdown() } }
    }
    var chosen by remember { mutableStateOf(vm.store("aiVoice") ?: vm.store("aiVoiceEn")) }
    var rate by remember { mutableFloatStateOf(vm.store("aiVoiceRate")?.toFloatOrNull() ?: 0.96f) }
    val voices = remember(ready) {
        if (!ready) emptyList() else runCatching { tts[0]?.voices?.toList().orEmpty() }.getOrDefault(emptyList())
            .filter { v -> runCatching { v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }.getOrDefault(true) }
            .filter { v -> val l = runCatching { v.locale }.getOrNull(); l != null && (l.language == "hi" || (l.language == "en" && l.country in setOf("IN", "US", "GB"))) }
            .sortedWith(compareBy<TtsVoice>({ when { it.locale.language == "hi" -> 1; it.locale.country == "IN" -> 0; else -> 2 } }, { -it.quality }, { it.name }))
            .take(16)
    }
    fun label(v: TtsVoice): String {
        val l = v.locale
        val lang = if (l.language == "hi") "Hindi" else when (l.country) { "IN" -> "Indian English"; "US" -> "US English"; else -> "British English" }
        val kind = if (v.isNetworkConnectionRequired) "natural · online" else if (v.quality >= TtsVoice.QUALITY_HIGH) "natural" else "basic"
        return "$lang · ${v.name.substringAfterLast("-x-").substringBefore("-").uppercase().take(4)} · $kind"
    }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text("Her voice", fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        Text("Pick the voice X talks with. \"Online\" voices sound the most human. Tap Hear for a sample.", fontSize = 13.sp, color = Color(0xFF9AA3B5))
        if (!ready) Text("Loading the phone's voices…", Modifier.padding(top = 10.dp), fontSize = 13.sp, color = Color(0xFF9AA3B5))
        voices.forEach { v ->
            val hindi = v.locale.language == "hi"
            val on = v.name == chosen
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (on) Color(0x334C8DFF) else Color(0x0FFFFFFF))
                    .clickable {
                        // The one voice X talks with (Hindi voices are also kept for Hindi replies).
                        chosen = v.name; vm.storePut("aiVoice", v.name)
                        if (hindi) vm.storePut("aiVoiceHi", v.name) else vm.storePut("aiVoiceEn", v.name)
                        if (Public.dev) AppLook.publishAi(voice = v.name, hindi = hindi) else Snack.show("X will talk with this voice")
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label(v), Modifier.weight(1f), fontSize = 14.sp, color = if (on) Color.White else Color(0xFFD5D9E2))
                Text("Hear", Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x1FFFFFFF)).clickable {
                    val t = tts[0] ?: return@clickable
                    runCatching {
                        t.setVoice(v)
                        t.setSpeechRate(rate)
                        t.speak(humanize(if (hindi) "नमस्ते! आज रात क्या देखना चाहेंगे? मेरे पास कुछ बढ़िया सुझाव हैं।"
                            else "Hey! Looking for something to watch tonight? I've got a few great picks for you."), TextToSpeech.QUEUE_FLUSH, Bundle(), "sample")
                    }
                }.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 12.sp, color = Color.White)
            }
        }
        Text("Talk speed: ${"%.2f".format(rate)}×", Modifier.padding(top = 14.dp), fontSize = 14.sp)
        androidx.compose.material3.Slider(rate, { rate = (it * 100).toInt() / 100f }, valueRange = 0.8f..1.2f,
            onValueChangeFinished = { vm.storePut("aiVoiceRate", rate.toString()); if (Public.dev) AppLook.publishAi(rate = rate.toString()) })
    }
}
