package com.example.streamnest

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * 0.9.6 (noVosk): "Hey Aiden" with Android's ON-DEVICE speech recogniser, in short listening windows, only while the
 * option is on. Never the cloud recogniser (createSpeechRecognizer) - if the phone has no on-device one, the option is hidden.
 * Everything runs on the main thread (SpeechRecognizer requires it).
 */
object WakeWordSr {
    /** Listening while something plays can make the recogniser take audio focus and PAUSE the movie. Off until tested per device. */
    private const val LISTEN_WHILE_PLAYING = false

    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    @Volatile private var on = false
    @Volatile private var gen = 0
    private var fails = 0
    private var lang: String? = "en-IN"          // like the old Vosk model; falls back to the phone's language once
    private var loggedPaused = false

    /** API 31+ and an on-device recogniser. (API 33+ can ask; on 31-32 we try once and remember a failure.) */
    fun available(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val c = ctx.applicationContext
        if (c.getSharedPreferences("wake", Context.MODE_PRIVATE).getBoolean("srBroken", false)) return false
        return runCatching { if (Build.VERSION.SDK_INT >= 33) SpeechRecognizer.isOnDeviceRecognitionAvailable(c) else true }.getOrDefault(false)
    }

    fun start(ctx: Context) {
        val c = ctx.applicationContext
        if (!available(c) || ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            WakeWord.status.value = "off"; return
        }
        main.post {
            if (on) return@post
            on = true; fails = 0; loggedPaused = false
            val g = ++gen
            listen(c, g)
        }
    }

    fun stop() {
        on = false; gen++
        main.post { destroy() }
        if (WakeWord.status.value == "listening") WakeWord.status.value = "ready"
    }

    private fun destroy() { runCatching { rec?.cancel() }; runCatching { rec?.destroy() }; rec = null }

    private fun again(c: Context, g: Int, delayMs: Long) { main.postDelayed({ listen(c, g) }, delayMs) }

    private fun broken(c: Context, why: String) {
        c.getSharedPreferences("wake", Context.MODE_PRIVATE).edit().putBoolean("srBroken", true).apply()
        ActLog.add("AI", "wake word: on-device recogniser unavailable ($why)")
        on = false; gen++; destroy(); WakeWord.status.value = "error"
    }

    private fun listen(c: Context, g: Int) {
        if (!on || g != gen) return
        // The mic is needed elsewhere (voice search, assistant, call) or a movie plays: wait, then look again.
        if (MicHold.n.value > 0 || (WakeWord.strict && !LISTEN_WHILE_PLAYING)) {
            if (!loggedPaused) { loggedPaused = true; ActLog.add("AI", "wake word: paused (mic busy or something is playing)") }
            if (WakeWord.status.value == "listening") WakeWord.status.value = "ready"
            again(c, g, 2000); return
        }
        loggedPaused = false
        try {
            destroy()
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(c)
            rec = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { WakeWord.status.value = "listening" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(b: Bundle?) { if (g == gen && hit(b, false)) fire(c, g) }
                override fun onResults(b: Bundle?) {
                    if (g != gen) return
                    if (hit(b, true)) fire(c, g) else { fails = 0; again(c, g, 250) }
                }
                override fun onError(code: Int) {
                    if (g != gen) return
                    when (code) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { fails = 0; again(c, g, 300) }   // quiet room: normal
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> again(c, g, 2000)
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { on = false; gen++; WakeWord.status.value = "off" }
                        12, 13 -> {   // ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE
                            if (lang != null) { lang = null; again(c, g, 300) } else broken(c, "language")
                        }
                        else -> {
                            fails++
                            if (fails >= 6) broken(c, "error $code") else again(c, g, minOf(30_000L, 1000L shl fails))
                        }
                    }
                }
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.packageName)
            lang?.let { i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            r.startListening(i)
        } catch (e: Throwable) {
            fails++
            if (fails >= 3) broken(c, e.javaClass.simpleName) else again(c, g, 1500)
        }
    }

    /** Same rule as the Vosk path: the WHOLE phrase is "hey aiden" (or just "aiden" when nothing plays), never a name inside other talk. */
    private fun hit(b: Bundle?, final: Boolean): Boolean {
        val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        val conf = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        for ((k, t) in list.withIndex()) {
            val w = t.lowercase().replace(Regex("[^a-z ]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val hasHey = w.size == 2 && w[0] in WakeWord.heys && w[1] in WakeWord.names
            val bare = w.size == 1 && w[0] in WakeWord.names
            if (!hasHey && !bare) continue
            if (WakeWord.strict && !hasHey) continue
            if (!final && !hasHey) continue                       // a half-heard single word is not enough
            val c = conf?.getOrNull(k) ?: -1f                     // -1 / 0 = this recogniser gives no score
            if (c > 0f && c < 0.6f) continue
            ActLog.add("AI", "wake heard: ${w.joinToString(" ")}" + (if (c > 0f) " (" + "%.2f".format(c) + ")" else ""))
            return true
        }
        return false
    }

    private fun fire(c: Context, g: Int) {
        val now = System.currentTimeMillis()
        destroy()
        if (now - WakeWord.lastHit > 4000 && now > WakeWord.quietUntil) {
            WakeWord.lastHit = now
            ActLog.add("AI", "wake word: hey aiden")
            WakeWord.onName()
        }
        again(c, g, 2500)   // the assistant takes the mic now; MicHold / strict then keep us paused
    }
}

