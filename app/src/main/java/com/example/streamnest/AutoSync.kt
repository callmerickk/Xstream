package com.example.streamnest

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Subtitle auto sync: the player's sound is measured in 0.1 s steps (how loud, i.e. when people talk), and the online
 * subtitle's lines are slid in time until they best cover the talking. Only the main player is measured.
 */
object Speech {
    /** 0.1 s step (video time / 100) → loudness in dB. */
    private val levels = ConcurrentHashMap<Int, Float>()
    @Volatile private var rate = 0
    @Volatile private var channels = 0
    @Volatile var anchorHintMs = 0L
    private var anchorMs = 0L
    private var frames = 0L
    private var winFrames = 0
    private var sum = 0.0

    fun configure(sampleRate: Int, ch: Int) { rate = sampleRate; channels = ch; flush() }
    fun flush() { anchorMs = anchorHintMs; frames = 0; winFrames = 0; sum = 0.0 }
    fun clear() { levels.clear() }

    @Volatile var float = false
    fun feed(buf: ByteBuffer) {
        val r = rate; val ch = channels
        if (r <= 0 || ch <= 0) return
        val win = r / 10
        val b = buf.order(ByteOrder.LITTLE_ENDIAN)
        val bytes = if (float) 4 else 2
        while (b.remaining() >= bytes * ch) {
            var s = 0.0
            for (c in 0 until ch) s += if (float) b.float.toDouble() * 32768.0 else b.short.toDouble()
            val m = s / ch / 32768.0
            sum += m * m; winFrames++
            if (winFrames >= win) {
                val db = (10 * kotlin.math.log10(sum / winFrames + 1e-10)).toFloat()
                val t = anchorMs + frames * 1000 / r
                levels[(t / 100).toInt()] = db
                frames += winFrames; winFrames = 0; sum = 0.0
                if (levels.size > 12_000) levels.keys.filter { it < (t / 100).toInt() - 9_000 }.forEach { levels.remove(it) }
            }
        }
    }

    /**
     * The shift (ms, as used by Adjust timing) that best lines [cues] up with the talking between [fromMs] and [toMs];
     * null when there is too little to judge or the current shift is already right.
     */
    /** Why the last check moved nothing: "few" = too little sound measured / too few lines yet, "ok" = already in step. */
    @Volatile var lastWhy = ""
    fun measuredSteps(fromMs: Long, toMs: Long): Int = ((fromMs / 100).toInt()..(toMs / 100).toInt()).count { levels.containsKey(it) }

    /**
     * Best shift (ms) for [cues] against the talking heard in [fromMs]..[toMs] (searched ±30 s), and how sure: the share
     * of 0.1 s steps that agree. [currentMs] is the shift in use; null when too little was heard or the best shift isn't
     * clearly better than the current one (so nothing jumps around on a guess).
     */
    internal fun evaluateImpl(cues: List<ExtCue>, fromMs: Long, toMs: Long, currentMs: Long = 0L): Pair<Long, Float>? {
        val a = (fromMs / 100).toInt(); val z = (toMs / 100).toInt()
        val raw = (a..z).mapNotNull { k -> levels[k]?.let { k to it } }
        if (raw.size < 250) return null
        val sorted = raw.map { it.second }.sorted()
        val floor = sorted[sorted.size / 4]
        val thr = maxOf(floor + 6f, sorted[sorted.size / 2])
        val inWin = cues.filter { it.end >= fromMs - 31_000 && it.start <= toMs + 31_000 }
        if (inWin.size < 6) return null
        val cover = HashSet<Int>()
        inWin.forEach { c -> for (k in (c.start / 100).toInt()..(c.end / 100).toInt()) cover += k }
        val talk = raw.map { (k, v) -> k to (v > thr) }
        fun score(sh: Int): Int { var sc = 0; talk.forEach { (k, t) -> if (t == ((k - sh) in cover)) sc++ }; return sc }
        var best = 0; var bestScore = Int.MIN_VALUE
        // Coarse (0.5 s) over ±30 s, then fine (0.1 s) around the best.
        for (sh in -300..300 step 5) { val sc = score(sh); if (sc > bestScore) { bestScore = sc; best = sh } }
        for (sh in (best - 5)..(best + 5)) { val sc = score(sh); if (sc > bestScore) { bestScore = sc; best = sh } }
        val cur = (currentMs / 100).toInt()
        val curScore = score(cur)
        // Only a clear win moves the subtitles (≥1.5% of the measured steps better than now).
        if (best != cur && bestScore - curScore < maxOf(8, raw.size * 15 / 1000)) return cur * 100L to curScore.toFloat() / raw.size
        return best * 100L to bestScore.toFloat() / raw.size
    }

    fun bestOffset(cues: List<ExtCue>, fromMs: Long, toMs: Long, currentMs: Long): Long? {
        lastWhy = "few"
        val a = (fromMs / 100).toInt(); val z = (toMs / 100).toInt()
        val raw = (a..z).mapNotNull { k -> levels[k]?.let { k to it } }
        if (raw.size < 250) return null
        val sorted = raw.map { it.second }.sorted()
        val floor = sorted[sorted.size / 4]
        val thr = maxOf(floor + 6f, sorted[sorted.size / 2])
        val speech = HashMap<Int, Int>(raw.size)
        raw.forEach { (k, v) -> speech[k] = if (v > thr) 1 else -1 }
        val span = 80 // ±8 s
        val inWin = cues.filter { it.end >= fromMs - 9_000 && it.start <= toMs + 9_000 }
        if (inWin.size < 6) return null
        val cover = HashSet<Int>()
        inWin.forEach { c -> for (k in (c.start / 100).toInt()..(c.end / 100).toInt()) cover += k }
        fun score(shiftSteps: Int): Int { var sc = 0; speech.forEach { (k, v) -> if ((k - shiftSteps) in cover) sc += v }; return sc }
        val cur = (currentMs / 100).toInt()
        var best = cur; var bestScore = Int.MIN_VALUE
        for (sh in -span..span) { val sc = score(sh); if (sc > bestScore) { bestScore = sc; best = sh } }
        val curScore = score(cur)
        lastWhy = "ok"
        if (best == cur || bestScore <= 0) return null
        if (bestScore - curScore < maxOf(12, kotlin.math.abs(curScore) / 8)) return null
        lastWhy = "moved"
        return best * 100L
    }
}

/**
 * How well a subtitle file fits the talking heard between [fromMs] and [toMs]: the best shift (ms) and a match value
 * (0..1, the share of 0.1 s steps where "line shown" agrees with "someone talks"). Null when too little was heard yet.
 */
fun Speech.evaluate(cues: List<ExtCue>, fromMs: Long, toMs: Long, currentMs: Long = 0L): Pair<Long, Float>? = evaluateImpl(cues, fromMs, toMs, currentMs)

/** Passes the sound through unchanged while measuring it for [Speech]. */
@OptIn(UnstableApi::class)
class SpeechMeter : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // 16-bit and float sound are both measured (float comes from some decoders and hi-res files).
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) return AudioFormat.NOT_SET
        Speech.float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        Speech.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        runCatching { Speech.feed(inputBuffer.duplicate()) }
        replaceOutputBuffer(n).put(inputBuffer).flip()
    }

    override fun onFlush() { Speech.flush() }
}
