package com.example.streamnest

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/*
 * "June!" / "Aiden!": the names wake the assistants while Xstream is open (also during a movie).
 * An offline listener (Vosk, Apache 2.0) that only knows a handful of words, so it is light and nothing you say
 * leaves the phone until a name is heard. Its small voice model (~36 MB) downloads once, the first time.
 * It pauses while an assistant is talking with you, during Watch together calls, and when the app is in the background.
 */
object WakeWord {
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"
    private const val MODEL_DIR = "vosk-model-small-en-in-0.4"
    val status = MutableStateFlow("off")   // off · downloading · ready · listening · error
    private val running = AtomicBoolean(false)
    private val downloading = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null
    @Volatile private var lastHit = 0L
    @Volatile private var gen = 0
    var onName: (AiBuddy) -> Unit = {}
    /** Something is playing: only the full "Hey June / Hey Aiden" wakes them, said clearly. */
    @Volatile var strict = false
    /** No wake-ups until then (e.g. right after a chat closes). */
    @Volatile var quietUntil = 0L

    /** Reads one final Vosk result: which name, if it was said clearly enough. */
    private fun pick(json: String, rms: Double): AiBuddy? {
        val o = runCatching { Api.json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject }.getOrNull() ?: return null
        val words = (o["result"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { e ->
            val w = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val word = (w["word"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            val conf = (w["conf"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
            word to conf
        }.orEmpty()
        if (words.isEmpty() || words.any { it.first == "[unk]" }) return null
        val i = words.indexOfFirst { it.first in setOf("june", "aiden", "aidan", "eden") }
        if (i < 0) return null
        // the name must be the whole utterance (with "hey" / "hi" / "okay" before it), not part of other talk
        if (words.size > 2 || (words.size == 2 && i != 1)) return null
        val hasHey = i == 1 && words[0].first in setOf("hey", "hi", "okay")
        val need = if (strict) 0.93 else 0.82
        if (strict && !hasHey) return null
        if (words.any { it.second < need }) return null
        ActLog.add("AI", "wake heard: ${words.joinToString(" ") { it.first + "(" + "%.2f".format(it.second) + ")" }}")
        return if (words[i].first == "june") AiBuddy.June else AiBuddy.Aiden
    }

    private fun modelDir(ctx: Context) = File(ctx.filesDir, "vosk/$MODEL_DIR")
    fun hasModel(ctx: Context) = File(modelDir(ctx), "am").exists() || File(modelDir(ctx), "conf").exists()

    /** Start listening (downloads the model first if needed). Safe to call repeatedly. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { status.value = "off"; return }
        if (!hasModel(app)) { download(app); return }
        if (running.getAndSet(true)) return
        val g = ++gen
        val t = Thread({ loop(app, g) }, "wake-word").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }
        thread = t
        t.start()
    }

    fun stop() {
        running.set(false)
        gen++
        thread = null
        if (status.value == "listening") status.value = "ready"
    }

    private fun download(ctx: Context) {
        if (downloading.getAndSet(true)) return
        status.value = "downloading"
        Thread({
            try {
                val tmp = File(ctx.cacheDir, "vosk.zip")
                Api.http.newCall(Request.Builder().url(MODEL_URL).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    r.body!!.byteStream().use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                }
                val root = File(ctx.filesDir, "vosk").apply { mkdirs() }
                ZipInputStream(tmp.inputStream().buffered()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val out = File(root, e.name)
                        if (!out.canonicalPath.startsWith(root.canonicalPath)) continue
                        if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
                    }
                }
                tmp.delete()
                ActLog.add("AI", "wake word model ready")
                status.value = "ready"
                downloading.set(false)
                start(ctx)
            } catch (e: Exception) {
                ActLog.add("AI", "wake word model download failed: ${e.message}")
                status.value = "error"
                downloading.set(false)
            }
        }, "wake-dl").start()
    }

    @SuppressLint("MissingPermission")
    private fun loop(ctx: Context, g: Int) {
        var model: org.vosk.Model? = null
        var rec: org.vosk.Recognizer? = null
        var ar: AudioRecord? = null
        try {
            model = org.vosk.Model(modelDir(ctx).absolutePath)
            // A few short phrases plus "unknown". Whole results only, with per-word confidence:
            // "Hey June" / "Hey Aiden" always works; the bare name only when nothing is playing.
            val words = "[\"hey june\", \"hi june\", \"okay june\", \"june\", \"hey aiden\", \"hi aiden\", \"okay aiden\", \"aiden\", \"hey aidan\", \"aidan\", \"hey eden\", \"[unk]\"]"
            rec = org.vosk.Recognizer(model, 16000f, words)
            runCatching { rec.setWords(true) }
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 8192))
            if (ar.state != AudioRecord.STATE_INITIALIZED) error("mic busy")
            ar.startRecording()
            status.value = "listening"
            val buf = ShortArray(1600)
            while (running.get() && g == gen) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (k in 0 until n) { val v = buf[k] / 32768.0; sum += v * v }
                val rms = kotlin.math.sqrt(sum / n)
                if (!rec.acceptWaveForm(buf, n)) continue
                val b = pick(rec.result, rms) ?: continue
                if (System.currentTimeMillis() - lastHit > 4000 && System.currentTimeMillis() > quietUntil) {
                    lastHit = System.currentTimeMillis()
                    rec.reset()
                    ActLog.add("AI", "wake word: ${b.label}")
                    android.os.Handler(android.os.Looper.getMainLooper()).post { onName(b) }
                }
            }
        } catch (e: Throwable) {
            ActLog.add("AI", "wake word stopped: ${e.message}")
            status.value = "error"
        } finally {
            runCatching { ar?.stop() }; runCatching { ar?.release() }
            runCatching { rec?.close() }; runCatching { model?.close() }
            if (g == gen) running.set(false)
            if (status.value == "listening") status.value = "ready"
        }
    }
}
