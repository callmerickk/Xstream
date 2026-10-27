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
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.focus.focusRequester
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

/** The player listens here: voice commands ("pause", "next episode", "forward 30") and a pause while the AI card is open. */
object AiPlayerBus {
    val cmd = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 8)
    @Volatile var open = false
    @Volatile var title = ""
    /** Was the video playing when the AI card paused it (it carries on when the card closes)? */
    @Volatile var wasPlaying = false
    fun send(act: String, secs: Int = 0) { if (open) cmd.tryEmit(act to secs) }
    fun duck() = send("duck")
    fun unduck() = send("unduck")
}

internal enum class XState { Idle, Listening, Thinking, Speaking }

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
    /** When the voice really started (0 = not yet): the text waits for the voice, never runs ahead of it. */
    var speechStartedAt by mutableLongStateOf(0L)
    /** The voice engine reports each word as it says it (most do); else the text follows an estimate from the start. */
    var rangeSeen by mutableStateOf(false)
    val speechRate: Float get() = (vm.store("aiVoiceRate")?.toFloatOrNull() ?: 0.96f).coerceIn(0.7f, 1.3f)
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
    /** What to start when [pendingOpen] opens: null = just show the page, "" = play, else an episode id. */
    private var pendingPlay: String? = null
    private var closeAfterSpeech = false
    var onClose: () -> Unit = {}
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
            Snack.show("Allow microphone access to talk to ${aiName()}. You can still type.")
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
                    override fun onStart(utteranceId: String?) { main.post { if (utteranceId != null && utteranceId == utt) speechStartedAt = android.os.SystemClock.elapsedRealtime() } }
                    override fun onDone(utteranceId: String?) { main.post { if (utteranceId != null && utteranceId == utt) afterSpeech() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { main.post { if (utteranceId != null && utteranceId == utt) afterSpeech() } }
                    override fun onError(utteranceId: String?, errorCode: Int) { main.post { if (utteranceId != null && utteranceId == utt) afterSpeech() } }
                    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                        main.post { if (utteranceId != null && utteranceId == utt) { rangeSeen = true; spokenChars = end } }
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
                    Snack.show("Allow microphone access to talk to ${aiName()}.")
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
            // The bubble shows exactly the words the voice says (same text), so they can light up together.
            val say = humanize(r.say)
            val id = addLine(false, say, r.items, r.rowTitle)
            closeAfterSpeech = r.closeAfter
            speak(id, say, r.open, if (r.play) (r.episode ?: "") else null)
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
        val id = addLine(false, humanize(hello))
        lastViaVoice = true
        speak(id, humanize(hello), null)
    }

    private fun speak(lineId: Long, text: String, thenOpen: Meta?, thenPlay: String? = null) {
        pendingOpen = thenOpen
        pendingPlay = thenPlay
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
        speechStartedAt = 0L
        rangeSeen = false
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
        val play = pendingPlay
        pendingOpen = null; pendingPlay = null
        if (o != null) { if (play != null) playTitle(o, play) else openTitle(o); return }
        if (closeAfterSpeech) { closeAfterSpeech = false; handsFree = false; dropRecognizer(); onClose(); return }
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

    /** Stop button: quiet now (no voice, no mic) until you tap to talk again. */
    fun stopAll() {
        handsFree = false
        cancelAfter()
        pendingOpen = null
        job?.cancel(); seq++
        dropRecognizer()
        stopSpeaking()
        level = 0f
        state = XState.Idle; hint = "Tap to talk"
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

    /** Straight to the video: the title page opens and presses Play by itself (Continue Watching / that episode). */
    fun playTitle(m: Meta, episode: String) {
        ActLog.add("AI", "play ${m.name} ${episode}")
        pendingDetailPlay.value = episode.ifEmpty { m.id }
        openTitle(m)
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
 * AIDEN, the Xstream movie buddy, in a bubble that springs out of the AI button (Home stays behind it, blurred).
 * Hands-free: it listens, answers out loud and listens again. Tap the core to talk, stop or interrupt.
 * The words of each answer light up exactly as the voice says them.
 */
@Composable
fun VoiceAssistant(vm: AppViewModel, onClose: () -> Unit, onOpen: (Meta) -> Unit, firstAsk: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = remember { XSession(ctx, vm, scope) }
    s.onOpen = onOpen
    s.onClose = onClose

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> s.onMic(ok) }
    s.requestMic = { runCatching { askMic.launch(Manifest.permission.RECORD_AUDIO) } }

    DisposableEffect(Unit) {
        s.start()
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
    var typing by remember { mutableStateOf(false) }
    var typingFocus by remember { mutableStateOf(false) }
    val profile by vm.current.collectAsState()

    fun send() {
        val q = typed.trim()
        if (q.isEmpty()) return
        typed = ""
        focus.clearFocus()
        s.handleUser(q, voice = false)
    }

    // Springs out of the AI button (bottom right) and back.
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.74f, stiffness = 380f)) }
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val w = minOf(360, cfg.screenWidthDp - 24).dp
    val h = minOf(600, (cfg.screenHeightDp * (if (typingFocus) 0.52f else 0.68f)).toInt()).dp

    Box(Modifier.fillMaxSize()) {
        // Tap outside: close.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = pop.value }.background(Color(0x8C050305)).quietClick { focus.clearFocus(); onClose() })
        Column(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().imePadding()
                .padding(end = 12.dp, bottom = if (typingFocus) 12.dp else 136.dp),
            horizontalAlignment = Alignment.End,
        ) {
            val shape = RoundedCornerShape(30.dp)
            Column(
                Modifier.width(w).height(h)
                    .graphicsLayer {
                        val v = pop.value
                        alpha = v.coerceIn(0f, 1f); scaleX = 0.6f + 0.4f * v; scaleY = scaleX
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.92f, 1f)
                    }
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(Color(0xF7260C12), Color(0xF7120910), Color(0xFA0B080B))))
                    .border(1.dp, Brush.verticalGradient(listOf(Color(0x80FF5A64), Color(0x33FF5A64))), shape)
                    .quietClick { focus.clearFocus() },
            ) {
                // ── Header: AIDEN + live status, sound, close ──
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        AiNameMark(18.sp)
                        val (label, col) = when (s.state) {
                            XState.Listening -> "LISTENING" to Color(0xFF9FE3B8)
                            XState.Thinking -> "THINKING" to Color(0xFFFFC2C6)
                            XState.Speaking -> "SPEAKING" to Color(0xFFFF8A91)
                            XState.Idle -> (if (vm.aiReady()) "ONLINE" else "OFFLINE MODE") to Color(0xFFB39CA2)
                        }
                        Text(label, fontSize = 10.sp, letterSpacing = 1.6.sp, color = col, modifier = Modifier.padding(top = 2.dp))
                    }
                    HeaderIcon(if (s.voiceOut) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, if (s.voiceOut) "Mute voice" else "Turn voice on", s.voiceOut) { s.toggleVoice() }
                    HeaderIcon(Icons.Default.Close, "Close", false) { onClose() }
                }

                // ── The core ──
                if (!typingFocus) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    AidenCore(s.state, s.level, 128.dp) { focus.clearFocus(); s.tapOrb() }
                    val sub = when (s.state) {
                        XState.Listening -> if (s.partial.isBlank()) "I'm all ears…" else "Tap when you're done"
                        XState.Speaking -> "Tap the core to interrupt"
                        XState.Thinking -> "Tap to ask something else"
                        XState.Idle -> s.hint.ifBlank { "Tap the core or a suggestion" }
                    }
                    Text(sub, fontSize = 11.sp, color = Color(0xFFB39CA2), textAlign = TextAlign.Center)
                }

                // ── Conversation ──
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
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            // The hello sits at the top, under the core; new lines push it up as the chat grows.
                            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top),
                        ) {
                            if (showPartial) item(key = "partial") { UserBubble(s.partial, live = true) }
                            items(s.lines.asReversed(), key = { it.id }) { line ->
                                if (line.user) UserBubble(line.text, live = false)
                                else AssistantBubble(line, s) { m -> s.openTitle(m) }
                            }
                        }
                    }
                }
                if (s.lines.any { !it.user } && s.lines.none { it.user } && !typingFocus) SuggestionChips { s.handleUser(it, voice = true) }

                // ── Talk / type ──
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (typing) {
                        Row(
                            Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(25.dp)).background(Color.White.copy(alpha = 0.07f))
                                .border(1.dp, Color.White.copy(alpha = if (typingFocus) 0.24f else 0.12f), RoundedCornerShape(25.dp)).padding(start = 16.dp, end = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(1f)) {
                                if (typed.isEmpty()) Text("Type to ${aiName()}…", fontSize = 14.sp, color = Color(0xFFB39CA2), maxLines = 1)
                                val fr = remember { androidx.compose.ui.focus.FocusRequester() }
                                LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
                                BasicTextField(
                                    typed, { typed = it },
                                    Modifier.fillMaxWidth().focusRequester(fr).onFocusChanged { f ->
                                        if (f.isFocused && !typingFocus) s.startTyping()
                                        typingFocus = f.isFocused
                                    }.tvOkKeyboard(),
                                    singleLine = true,
                                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                                    cursorBrush = SolidColor(Color(0xFFFF5A64)),
                                    keyboardOptions = tvKeyboard(KeyboardOptions(imeAction = ImeAction.Send)),
                                    keyboardActions = KeyboardActions(onSend = { send() }),
                                )
                            }
                            Box(
                                Modifier.size(40.dp).clip(CircleShape)
                                    .background(if (typed.isNotBlank()) SolidColor(Color(0xFFE50914)) else SolidColor(Color.White.copy(alpha = 0.08f)))
                                    .quietClick { send() },
                                Alignment.Center,
                            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", Modifier.size(18.dp), tint = Color.White) }
                        }
                        Spacer(Modifier.width(8.dp))
                        RoundBtn(Icons.Default.Mic, "Talk instead") { typing = false; typed = ""; focus.clearFocus(); s.tapOrb() }
                    } else {
                        RoundBtn(Icons.Default.Keyboard, "Type instead") { typing = true }
                        Spacer(Modifier.width(8.dp))
                        val cta = when (s.state) { XState.Speaking -> "INTERRUPT"; XState.Thinking -> "THINKING…"; XState.Listening -> "LISTENING…"; XState.Idle -> "TAP TO TALK" }
                        Row(
                            Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(25.dp))
                                .background(Brush.horizontalGradient(listOf(Color(0xFFB0060F), Color(0xFFE50914), Color(0xFFFF4B55))))
                                .quietClick { focus.clearFocus(); s.tapOrb() },
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Mic, null, Modifier.size(18.dp), tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text(cta, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Color.White)
                        }
                        Spacer(Modifier.width(8.dp))
                        RoundBtn(Icons.Default.Stop, "Stop") { s.stopAll() }
                    }
                }
            }
            // The little pointer down to the AI button.
            if (!typingFocus) Box(
                Modifier.padding(end = 18.dp).offset(y = (-9).dp).size(18.dp)
                    .graphicsLayer { rotationZ = 45f; alpha = pop.value }
                    .background(Color(0xFF0B080B))
            )
        }
    }
}

@Composable
private fun RoundBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(50.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape).quietClick(onClick),
        Alignment.Center,
    ) { Icon(icon, label, Modifier.size(20.dp), tint = Color.White) }
}

/** True for very dark accent colours that would vanish on the dark sheet. */
private fun Color.luminanceLow(): Boolean = red * 0.299f + green * 0.587f + blue * 0.114f < 0.18f

@Composable
private fun HeaderIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 3.dp).size(40.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = if (on) 0.10f else 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .quietClick(onClick),
        Alignment.Center,
    ) { Icon(icon, label, Modifier.size(18.dp), tint = Color.White) }
}

@Composable
private fun EmptyChat(name: String?, onPick: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (name != null) "Hey $name, I'm ${aiName()}" else "Hey, I'm ${aiName()}", fontWeight = FontWeight.Bold,
            fontSize = 20.sp, color = Color.White, textAlign = TextAlign.Center,
        )
        Text(
            "Talk to me like a friend: a mood, a vibe, or a title.", Modifier.padding(top = 6.dp),
            fontSize = 13.sp, color = Color(0xFFB39CA2), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        SuggestionChips(onPick)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionChips(onPick: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Like Interstellar, lighter", "Surprise me", "Continue my show", "Hindi thriller").forEach { c ->
            Text(
                c,
                Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x14E50914))
                    .border(1.dp, Color(0x52FF5A64), RoundedCornerShape(18.dp))
                    .quietClick { onPick(c) }.padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 12.sp, color = Color(0xFFFBE7E9),
            )
        }
    }
}

@Composable
private fun UserBubble(text: String, live: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            Modifier.widthIn(max = 260.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp))
                .background(Color.White.copy(alpha = if (live) 0.05f else 0.08f))
                .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp))
                .padding(horizontal = 13.dp, vertical = 9.dp),
            fontSize = 13.5.sp, lineHeight = 19.sp,
            color = if (live) Color.White.copy(alpha = 0.7f) else Color.White,
            fontStyle = if (live) FontStyle.Italic else FontStyle.Normal,
        )
    }
}

/**
 * AIDEN's answer. While the voice says it, the words light up one by one exactly with the voice (the current word
 * glows red, the rest wait faintly); nothing runs ahead of the voice. Text-only: the words appear one by one.
 */
@Composable
private fun AssistantBubble(line: ChatLine, s: XSession, onOpen: (Meta) -> Unit) {
    val full = line.text
    var shown by remember(line.id) { mutableIntStateOf(if (s.revealed.contains(line.id)) full.length else 0) }
    LaunchedEffect(line.id) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        while (shown < full.length) {
            androidx.compose.runtime.withFrameMillis { }
            val now = android.os.SystemClock.elapsedRealtime()
            val raw = when {
                s.speakingId == line.id -> when {
                    s.speechStartedAt == 0L -> 0
                    s.rangeSeen -> s.spokenChars
                    // No word reports from this voice: follow the voice's pace from the moment it started.
                    else -> ((now - s.speechStartedAt) * 0.0145f * s.speechRate).toInt()
                }
                // Finished speaking (or the voice is off): the rest shows.
                s.speakingId != line.id && (s.revealed.contains(line.id) || !s.voiceOut || s.state != XState.Speaking) ->
                    if (s.voiceOut && now - t0 < 250) shown else maxOf(shown, ((now - t0) * 0.05f).toInt())
                else -> shown
            }
            // Whole words only.
            val end = if (raw <= 0) 0 else full.indexOf(' ', raw.coerceAtMost(full.length - 1)).let { if (it < 0) full.length else it }
            if (end > shown) shown = end
            if (s.speakingId != line.id && s.speakingId != -1L) shown = full.length   // a newer answer is speaking
        }
        s.revealed.add(line.id)
    }
    val curStart = full.lastIndexOf(' ', (shown - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }.coerceAtMost(shown)
    val speakingNow = s.speakingId == line.id && shown < full.length
    Column(Modifier.fillMaxWidth()) {
        Text(
            buildAnnotatedString {
                if (speakingNow && shown > 0) {
                    append(full.take(curStart))
                    withStyle(SpanStyle(background = Color(0x8CE50914), color = Color.White)) { append(full.substring(curStart, shown)) }
                } else append(full.take(shown))
                withStyle(SpanStyle(color = Color.White.copy(alpha = 0.2f))) { append(full.drop(shown)) }
            },
            Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(Brush.linearGradient(listOf(Color(0x29E50914), Color(0x59280A10))))
                .border(1.dp, Color(0x47FF5A64), RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                .padding(horizontal = 13.dp, vertical = 10.dp),
            fontSize = 15.sp, lineHeight = 22.sp, color = Color.White,
        )
        if (line.items.isNotEmpty() && (shown >= full.length || shown > full.length / 2)) {
            if (line.rowTitle.isNotBlank()) Text(
                line.rowTitle, Modifier.padding(start = 4.dp, top = 10.dp, bottom = 6.dp),
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFB39CA2), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            LazyRow(
                Modifier.fillMaxWidth().padding(top = if (line.rowTitle.isBlank()) 8.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(line.items.distinctBy { it.type + it.id }, key = { it.type + it.id }) { m ->
                    Column(Modifier.width(88.dp).quietClick { onOpen(m) }) {
                        AsyncImage(
                            m.poster, m.name,
                            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(Surface1)
                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Text(m.name, Modifier.padding(top = 4.dp, start = 2.dp), fontSize = 10.5.sp, color = Color(0xFFD9C2C6), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * AIDEN's core: turning red and white rings (faster while thinking / speaking), a glowing red heart that follows your
 * voice while listening and shows a voice meter while speaking, and a ripple when touched.
 */
@Composable
internal fun AidenCore(state: XState, level: Float, size: Dp, onTap: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "aiden")
    val busy = state == XState.Thinking || state == XState.Speaking
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "s1")
    val spinFast by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "s2")
    val breathe by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val phase by inf.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(900, easing = LinearEasing)), label = "p")
    val busyA by animateFloatAsState(if (busy) 1f else 0f, tween(400), label = "busy")
    val speakA by animateFloatAsState(if (state == XState.Speaking) 1f else 0f, tween(300), label = "sp")
    val listenA by animateFloatAsState(if (state == XState.Listening) 1f else 0f, tween(300), label = "li")
    val lvl by animateFloatAsState(if (state == XState.Listening) level else 0f, tween(110), label = "lvl")
    val ripple = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Box(Modifier.size(size), Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val R = this.size.minDimension / 2f
            val a = spin + spinFast * busyA
            // Glow
            drawCircle(Brush.radialGradient(listOf(Color(0xFFE50914).copy(alpha = 0.35f + 0.25f * breathe + 0.2f * busyA), Color.Transparent), c, R), R, c)
            // Outer dotted ring + red segmented ring
            rotate(a, c) {
                drawCircle(Color(0x47FFB3B8), R * 0.94f, c, style = Stroke(1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx()))))
                drawCircle(Color(0xE6E50914), R * 0.85f, c, style = Stroke(2.2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(R * 0.7f, R * 0.33f, R * 0.12f, R * 0.33f))))
            }
            // White arc, the other way
            rotate(-a * 1.6f, c) {
                drawArc(Color(0x8CFFFFFF), 0f, 70f, false, Offset(c.x - R * 0.71f, c.y - R * 0.71f), Size(R * 1.42f, R * 1.42f), style = Stroke(1.6.dp.toPx()))
            }
            // Listening: rings that open with your voice
            if (listenA > 0.01f) for (i in 0..2) drawCircle(Color(0xFFFF5A64).copy(alpha = (0.6f - 0.18f * i) * listenA), R * (0.62f + 0.08f * i + 0.18f * lvl * (i + 1) / 3f), c, style = Stroke(1.6.dp.toPx()))
            // The heart
            val core = R * (0.36f + 0.03f * breathe + 0.06f * lvl)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE3E5), Color(0xFFFF5A64), Color(0xFF9E0710)), Offset(c.x - core * 0.25f, c.y - core * 0.3f), core * 1.5f), core, c)
            // Voice meter while speaking
            if (speakA > 0.01f) for (i in -2..2) {
                val hgt = core * (0.25f + 0.45f * kotlin.math.abs(sin(phase + i * 0.9f))) * speakA
                drawLine(Color.White, Offset(c.x + i * core * 0.22f, c.y - hgt), Offset(c.x + i * core * 0.22f, c.y + hgt), strokeWidth = 2.6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            // Touch ripple
            if (ripple.value < 1f) drawCircle(Color(0xFFFF6B74).copy(alpha = 1f - ripple.value), core * (1f + 1.4f * ripple.value), c, style = Stroke(2.dp.toPx()))
        }
        Box(Modifier.size(size * 0.62f).clip(CircleShape).quietClick { scope.launch { ripple.snapTo(0f); ripple.animateTo(1f, tween(700)) }; onTap() }, Alignment.Center) {
            val micA = (1f - speakA).coerceIn(0f, 1f)
            if (state == XState.Idle) Icon(Icons.Default.Mic, "Talk", Modifier.size(size * 0.17f).graphicsLayer { alpha = micA }, tint = Color.White)
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

// ───────────── AIDEN wakes up when the app opens ─────────────

/** Once per app start. */
object AidenWakeOnce { @Volatile var done = false }

/** A short, personal hello for the moment the app opens. */
fun aidenGreeting(vm: AppViewModel): String {
    val name = vm.current.value?.name?.trim()?.takeIf { it.isNotBlank() && it != "Me" }
    val n = name?.let { ", $it" } ?: ""
    val cal = java.util.Calendar.getInstance()
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val day = cal.get(java.util.Calendar.DAY_OF_WEEK)
    val last = vm.progress.value.firstOrNull()?.title?.substringBefore("  S")?.takeIf { it.isNotBlank() }
    val lines = ArrayList<String>()
    when (hour) {
        in 5..11 -> lines += listOf("Good morning$n. Fresh coffee, fresh picks. I've lined up something good for you.", "Morning$n! A great day starts with a great story. Tap me when you're ready.")
        in 12..16 -> lines += listOf("Good afternoon$n. Perfect time for a quick episode, don't you think?", "Hey$n, afternoon break? I know just the thing.")
        in 17..21 -> lines += listOf("Good evening$n. The couch is calling. Want me to find tonight's movie?", "Evening$n! Lights down, sound up. I've got picks ready when you are.")
        else -> lines += listOf("Still up$n? Night owls get the best thrillers. Tap me when you're ready.", "Late night again$n? I saved you something worth staying up for.")
    }
    if (day == java.util.Calendar.FRIDAY || day == java.util.Calendar.SATURDAY) lines += "It's the weekend$n. Movie night rules apply, and I've picked a few you'll love."
    if (last != null) lines += listOf("Welcome back$n. $last is waiting right where you left it.", "Hey$n! Ready for more $last, or shall I surprise you?")
    return lines.random()
}

/**
 * When the app opens, AIDEN wakes up: a capsule slides out of the AI button, says a short personal hello (the words
 * light up with the voice), then slides back and sleeps until you tap the button. Tap the capsule to talk.
 */
@Composable
fun AidenWake(vm: AppViewModel, onOpen: () -> Unit) {
    if (BuildConfig.IS_TV) return
    val texts by vm.texts.collectAsState()
    if (!isOn(texts["aiWake"] ?: "yes")) return
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val text = remember { aidenGreeting(vm) }
    var shown by remember { mutableIntStateOf(0) }
    val ttsRef = remember { arrayOfNulls<TextToSpeech>(1) }
    DisposableEffect(Unit) { onDispose { runCatching { ttsRef[0]?.stop(); ttsRef[0]?.shutdown() }; ttsRef[0] = null } }
    LaunchedEffect(Unit) {
        if (AidenWakeOnce.done) return@LaunchedEffect
        AidenWakeOnce.done = true
        delay(1400)
        val voice = isOn(texts["aiWakeVoice"] ?: "yes") && vm.store("aiVoiceOut") != "no" &&
            runCatching { (ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL }.getOrDefault(true)
        // The voice is made ready first, so the words and the voice start together.
        var startedAt = 0L; var spoken = -1; var done = false
        if (voice) {
            val ready = kotlinx.coroutines.CompletableDeferred<TextToSpeech?>()
            val main = Handler(Looper.getMainLooper())
            var t: TextToSpeech? = null
            t = runCatching { TextToSpeech(ctx) { st -> ready.complete(if (st == TextToSpeech.SUCCESS) t else null) } }.getOrNull()
            ttsRef[0] = t
            val engine = kotlinx.coroutines.withTimeoutOrNull(2500) { ready.await() }
            if (engine != null) {
                runCatching { engine.setSpeechRate((vm.store("aiVoiceRate")?.toFloatOrNull() ?: 0.96f).coerceIn(0.7f, 1.3f)) }
                vm.store("aiVoice")?.let { n -> runCatching { engine.voices?.firstOrNull { it.name == n }?.let { v -> engine.setVoice(v) } } }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { main.post { startedAt = android.os.SystemClock.elapsedRealtime() } }
                    override fun onDone(id: String?) { main.post { done = true } }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { done = true } }
                    override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) { main.post { spoken = end } }
                })
                open = true
                engine.speak(humanize(text), TextToSpeech.QUEUE_FLUSH, Bundle(), "aiden_wake")
            }
        }
        open = true
        val t0 = android.os.SystemClock.elapsedRealtime()
        val full = humanize(text)
        while (shown < full.length) {
            androidx.compose.runtime.withFrameMillis { }
            val now = android.os.SystemClock.elapsedRealtime()
            val raw = when {
                !voice || ttsRef[0] == null -> ((now - t0) * 0.03f).toInt()
                done -> full.length
                startedAt == 0L -> if (now - t0 > 3000) ((now - t0 - 3000) * 0.03f).toInt() else 0
                spoken >= 0 -> spoken
                else -> ((now - startedAt) * 0.0145f).toInt()
            }
            val end = if (raw <= 0) 0 else full.indexOf(' ', raw.coerceAtMost(full.length - 1)).let { if (it < 0) full.length else it }
            if (end > shown) shown = end
        }
        val until = android.os.SystemClock.elapsedRealtime() + 6000
        while (!done && voice && ttsRef[0] != null && android.os.SystemClock.elapsedRealtime() < until) delay(100)
        delay(1800)
        open = false
        delay(600)
        runCatching { ttsRef[0]?.shutdown() }; ttsRef[0] = null
    }
    val full = remember { humanize(text) }
    androidx.compose.animation.AnimatedVisibility(
        open,
        enter = androidx.compose.animation.expandHorizontally(tween(520, easing = FastOutSlowInEasing), expandFrom = Alignment.End) + androidx.compose.animation.fadeIn(tween(300)),
        exit = androidx.compose.animation.shrinkHorizontally(tween(420), shrinkTowards = Alignment.End) + androidx.compose.animation.fadeOut(tween(300)),
    ) {
        val shape = RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp, topEnd = 8.dp, bottomEnd = 8.dp)
        Row(
            Modifier.widthIn(max = 270.dp).clip(shape)
                .background(Brush.horizontalGradient(listOf(Color(0xF2260C12), Color(0xF2150A0E))))
                .border(1.dp, Brush.horizontalGradient(listOf(Color(0x80FF5A64), Color(0x26FF5A64))), shape)
                .quietClick { open = false; runCatching { ttsRef[0]?.stop() }; onOpen() }
                .padding(start = 10.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AidenCore(if (shown < full.length) XState.Speaking else XState.Idle, 0f, 34.dp) { open = false; onOpen() }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(aiName().uppercase(), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp, color = Color(0xFFFF8A91))
                val cur = full.lastIndexOf(' ', (shown - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }.coerceAtMost(shown)
                Text(
                    buildAnnotatedString {
                        if (shown in 1 until full.length) {
                            append(full.take(cur))
                            withStyle(SpanStyle(background = Color(0x8CE50914))) { append(full.substring(cur, shown)) }
                        } else append(full.take(shown))
                        withStyle(SpanStyle(color = Color.White.copy(alpha = 0.2f))) { append(full.drop(shown)) }
                    },
                    fontSize = 12.5.sp, lineHeight = 17.sp, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
