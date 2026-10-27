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

    // Speech band (about 300 Hz – 3.4 kHz): music beats, bass and explosions mostly fall outside it, so "someone talks"
    // is judged on the voice band's loudness instead of the whole sound (what alass / ffsubsync-style tools rely on).
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    private fun bandpass(sampleRate: Int) {
        val f0 = 1000.0; val q = 0.6
        val w = 2 * Math.PI * f0 / sampleRate; val alpha = kotlin.math.sin(w) / (2 * q); val a0 = 1 + alpha
        b0 = alpha / a0; b1 = 0.0; b2 = -alpha / a0; a1 = -2 * kotlin.math.cos(w) / a0; a2 = (1 - alpha) / a0
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }
    fun configure(sampleRate: Int, ch: Int) { rate = sampleRate; channels = ch; if (sampleRate > 0) bandpass(sampleRate); flush() }
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
            // Surround sound (5.1 / 7.1): voices are in the centre channel, so only it is measured (music and effects
            // in the other channels hid the talking). Stereo / mono: the average.
            var s = 0.0; var centre = 0.0
            for (c in 0 until ch) { val v = if (float) b.float.toDouble() * 32768.0 else b.short.toDouble(); s += v; if (c == 2) centre = v }
            val m = (if (ch >= 6) centre else s / ch) / 32768.0
            val y = b0 * m + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = m; y2 = y1; y1 = y
            sum += y * y; winFrames++
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

    /** Frame-rate mismatches between the subtitle and this video file (23.976 / 24 / 25 fps), as time stretch. */
    private val ratios = doubleArrayOf(1.0, 25.0 / 23.976, 23.976 / 25.0, 24.0 / 23.976, 23.976 / 24.0, 25.0 / 24.0, 24.0 / 25.0)

    /**
     * ffsubsync-style alignment (the idea, our own code): "someone talks" per 0.1 s (voice-band loudness over a local
     * noise floor, a simple voice detector) against "a line is shown", tried for every shift within ±30 s and for the
     * usual frame-rate stretches. Returns the best shift (ms), stretch and fit (share of 0.1 s steps that agree), or null
     * when too little has been heard. [curOff] / [curRatio] = what is used now (kept unless the new fit is clearly better).
     */
    internal fun fit(cues: List<ExtCue>, fromMs: Long, toMs: Long, curOff: Long, curRatio: Double): SyncFit? {
        lastWhy = "few"
        val a = (fromMs / 100).toInt(); val z = (toMs / 100).toInt()
        val keys = ArrayList<Int>(z - a + 1); val vals = ArrayList<Float>(z - a + 1)
        for (k in a..z) levels[k]?.let { keys += k; vals += it }
        if (keys.size < 200) return null
        // Voice detector: each step against its own 30 s neighbourhood (quiet scenes and loud scenes judged fairly).
        val n = keys.size
        val talk = BooleanArray(n)
        val win = 150
        for (i in 0 until n) {
            val lo = maxOf(0, i - win); val hi = minOf(n, i + win)
            val local = vals.subList(lo, hi).sorted()
            val floor = local[local.size / 5]; val mid = local[local.size / 2]
            talk[i] = vals[i] > maxOf(floor + 7f, mid - 1f)
        }
        val inWin = cues.filter { it.end >= fromMs - 200_000 && it.start <= toMs + 200_000 }
        if (inWin.size < 6) return null
        val kArr = keys.toIntArray()
        fun coverFor(r: Double): HashSet<Int> {
            val c = HashSet<Int>()
            inWin.forEach { cue -> for (k in (cue.start * r / 100).toInt()..(cue.end * r / 100).toInt()) c += k }
            return c
        }
        fun score(cover: HashSet<Int>, sh: Int): Int { var sc = 0; for (i in 0 until n) if (talk[i] == ((kArr[i] - sh) in cover)) sc++; return sc }
        val mid = (a + z) / 2
        var best = SyncFit(curOff, curRatio, 0f); var bestScore = Int.MIN_VALUE
        for (r in ratios) {
            val cover = coverFor(r)
            // With a stretch, the matching shift is around -(r-1)·now; search ±30 s around that.
            val centre = (-(r - 1.0) * mid).toInt()
            var bSh = centre; var bSc = Int.MIN_VALUE
            for (sh in (centre - 300)..(centre + 300) step 5) { val sc = score(cover, sh); if (sc > bSc) { bSc = sc; bSh = sh } }
            for (sh in (bSh - 5)..(bSh + 5)) { val sc = score(cover, sh); if (sc > bSc) { bSc = sc; bSh = sh } }
            // A stretch must beat "no stretch" clearly (3 % of the steps): stretching is the rarer case.
            val bonus = if (r == 1.0) n * 3 / 100 else 0
            if (bSc + bonus > bestScore) { bestScore = bSc + bonus; best = SyncFit(bSh * 100L, r, bSc.toFloat() / n) }
        }
        val curScore = score(coverFor(curRatio), (curOff / 100).toInt())
        val bestRaw = (best.fit * n).toInt()
        lastWhy = "ok"
        // Only a clear win moves the subtitles (≥1.5 % of the measured steps better than now).
        if (bestRaw - curScore < maxOf(8, n * 15 / 1000)) return SyncFit(curOff, curRatio, curScore.toFloat() / n)
        lastWhy = "moved"
        return best
    }
}

/** A subtitle timing: shown at video time = subtitle time × [ratio] + [offsetMs]; [fit] = 0..1. */
data class SyncFit(val offsetMs: Long, val ratio: Double, val fit: Float)

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
