@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.example.streamnest

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/** 0.9.7 (dataDiet): numbers, gates and counters of the data diet. Callers check Feature.dataDiet first. */
object DataDiet {
    const val PHONE_MIN_MS = 20_000      // low-water mark: top up below this
    const val PHONE_MAX_MS = 40_000      // high-water mark: stop at this
    const val PHONE_MB = 60
    const val CLIMB_AFTER_MS = 30_000L
    const val AHEAD_WIFI_MS = 20_000L
    const val AHEAD_MOBILE_MS = 30_000L
    const val SAVE_AHEAD_MS = 180_000L
    const val SAVE_PACE = 2.0

    /** Phones only; big-screen / TV mode keep their own buffers. */
    fun phone(): Boolean = !BuildConfig.IS_TV && !BigScreen.on

    private val video = AtomicLong(); private val bg = AtomicLong(); private val thumbs = AtomicLong()
    @Volatile private var title = ""; @Volatile private var startedAt = 0L

    fun addBackground(bytes: Long) { if (bytes > 0) bg.addAndGet(bytes) }
    fun addThumbs(bytes: Long) { if (bytes > 0) thumbs.addAndGet(bytes) }

    /** Counts network bytes only (cache hits never reach the HTTP source). [background] decides the bucket. */
    fun transfer(background: () -> Boolean): TransferListener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork) (if (background()) bg else video).addAndGet(bytesTransferred.toLong())
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    }

    fun beginPlay(name: String) { video.set(0); bg.set(0); thumbs.set(0); title = name; startedAt = android.os.SystemClock.elapsedRealtime() }

    /** The per-play summary line, written when the player closes. */
    fun endPlay() {
        if (startedAt == 0L) return
        fun mb(a: AtomicLong) = "%.1f".format(a.get() / 1048576.0)
        val min = (android.os.SystemClock.elapsedRealtime() - startedAt) / 60_000.0
        ActLog.add("DATA", "data · $title · video ${mb(video)} MB · background ${mb(bg)} MB · thumbs ${mb(thumbs)} MB · minutes ${"%.1f".format(min)}")
        video.set(0); bg.set(0); thumbs.set(0); startedAt = 0L
    }

    /** May a better copy be loaded in the background now? (30 s since start and enough loaded ahead.) */
    fun climbOk(sinceStartMs: Long, aheadMs: Long, wifi: Boolean): Boolean =
        sinceStartMs >= CLIMB_AFTER_MS && aheadMs >= (if (wifi) AHEAD_WIFI_MS else AHEAD_MOBILE_MS)

    /** Step-down guard: in the first 10 s after a start / jump, "little ahead" alone is no reason to step down while the
     *  measured speed is at least twice the bitrate that plays. true = keep the quality. */
    fun holdQuality(sinceStartOrSeekMs: Long, aheadMs: Long, measuredBps: Long, currentBps: Long): Boolean =
        Feature.dataDiet && sinceStartOrSeekMs < 10_000 && aheadMs < 5_000 && currentBps > 0 && measuredBps >= 2 * currentBps
}

/**
 * 0.9.7 (dataDiet): rules for background saving (the Booster's saving ahead). One guard per saving run: mustStop()
 * before every piece, aheadAllowed() before saving further, pace() after every piece, partsFor() for the connections.
 */
class BgSaveGuard(private val qualityKey: String, private val copyUrl: String) {
    /** The quality or the copy changed → stop at once. */
    fun mustStop(nowQualityKey: String?, nowUrl: String?): Boolean = nowUrl != copyUrl || nowQualityKey != qualityKey
    /** Never more than ~3 min ahead of the play position. */
    fun aheadAllowed(savedUntilMs: Long, playPosMs: Long): Boolean = savedUntilMs - playPosMs < DataDiet.SAVE_AHEAD_MS
    /** Paced to ~2× the file's bitrate: [bytes] take at least bytes·8 / (2·bitrate) seconds. */
    suspend fun pace(bytes: Long, tookMs: Long, streamBps: Long) {
        if (streamBps <= 0 || bytes <= 0) return
        val minMs = (bytes * 8_000.0 / (streamBps * DataDiet.SAVE_PACE)).toLong()
        if (tookMs < minMs) delay(minMs - tookMs)
    }
    /** Parallel pieces only for the first seconds of a start; saving ahead is one connection. */
    fun partsFor(headOfStart: Boolean, normal: Int): Int = if (headOfStart) normal else 1
}
