package com.example.streamnest

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * Preview frames for the seek bar (phone drag + TV remote seeking), read straight from the video file with
 * MediaMetadataRetriever on one background thread. Frames are cached per 10-second step; only the newest
 * requested position is worked on (older requests are dropped).
 *
 * Opening a big remote file takes a while (the retriever reads the file's index first), so [warmUp] opens it as soon
 * as you touch the seek bar, the first frame may take up to 15 s, and a slow answer never makes it give up. It only
 * stops for good when the file can't be opened at all (HLS / DASH, a refused link) or several frames in a row come
 * back empty. While a frame is on its way, [nearest] gives the closest one already made.
 */
class SeekThumbs(url: String, headers: Map<String, String>) {
    private val playing: String = url
    private val src: String = original(url)
    private val hdrs: Map<String, String> = run {
        val h = if (src != url || headers.isEmpty()) StreamHeaders.forUrl(src) else headers
        if (h.keys.any { it.equals("User-Agent", true) }) h else h + ("User-Agent" to PlayerUserAgent)
    }
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "seek-thumbs").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 } }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val requests = Channel<Long>(Channel.CONFLATED)
    private val lock = Any()
    /** step → frame, in access order (oldest dropped first). Guarded by [lock]. */
    private val cache = object : LinkedHashMap<Long, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ImageBitmap>?) = size > MAX_FRAMES
    }
    private val waiting = HashMap<Long, CompletableDeferred<ImageBitmap?>>()
    private var retriever: MediaMetadataRetriever? = null
    @Volatile private var opened = false
    @Volatile private var latest = -1L
    @Volatile private var emptyInARow = 0
    @Volatile private var succeeded = false
    @Volatile private var dead = (!src.startsWith("http://", true) && !src.startsWith("https://", true) && !Relay.isWrapped(playing)) ||
        Regex("\\.(m3u8|mpd)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(src)
    @Volatile private var closed = false

    init {
        if (!dead) scope.launch {
            for (b in requests) {
                if (closed || dead) break
                if (b == OPEN) { open(); continue }
                if (b != latest) { finish(b, null); continue }
                val img = runCatching { extract(b * STEP) }.getOrNull()
                if (img != null) {
                    synchronized(lock) { cache[b] = img }
                    succeeded = true; emptyInARow = 0
                } else if (opened && !succeeded && ++emptyInARow >= 4) giveUp()
                finish(b, img)
                // Requests for other positions that were overtaken meanwhile: answer them with nothing.
                synchronized(lock) { waiting.keys.filter { it != latest }.toList() }.forEach { finish(it, null) }
            }
        }
    }

    /** Opens the file in the background (call when the seek bar is touched) so the first frame comes sooner. */
    fun warmUp() { if (!dead && !closed && !opened) requests.trySend(OPEN) }

    /** The frame closest to [ms] (10-second steps), or null when it can't be made (in time). Never blocks the caller's thread. */
    suspend fun frameAt(ms: Long): ImageBitmap? {
        if (dead || closed) return null
        val b = ms.coerceAtLeast(0L) / STEP
        synchronized(lock) { cache[b] }?.let { return it }
        val d = synchronized(lock) { waiting.getOrPut(b) { CompletableDeferred() } }
        latest = b
        requests.trySend(b)
        return withTimeoutOrNull(if (succeeded) 8_000L else 15_000L) { d.await() }
    }

    /** False once it gave up (or was closed): the seek bar then shows the time only. */
    val available: Boolean get() = !dead && !closed

    /** A frame already made for [ms], without waiting. */
    fun cached(ms: Long): ImageBitmap? = synchronized(lock) { cache[ms.coerceAtLeast(0L) / STEP] }

    /** Has a frame for this 10-second step already. */
    fun has(ms: Long): Boolean = synchronized(lock) { cache.containsKey(ms.coerceAtLeast(0L) / STEP) }

    /**
     * A frame copied from the screen while the video plays (phones): the parts you've watched always get previews,
     * also for streams the file reader can't open (HLS / DASH, protected links).
     */
    fun put(ms: Long, bmp: Bitmap) {
        if (closed) return
        val small = if (bmp.width > W + 8) {
            val h = (bmp.height * (W.toFloat() / bmp.width)).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, W, h, true).also { if (it !== bmp) bmp.recycle() }
        } else bmp
        synchronized(lock) { cache[ms.coerceAtLeast(0L) / STEP] = small.asImageBitmap() }
    }

    /** The closest frame already made, within [withinMs] of [ms] (shown while the exact one loads). */
    fun nearest(ms: Long, withinMs: Long = 120_000L): ImageBitmap? {
        val b = ms.coerceAtLeast(0L) / STEP
        return synchronized(lock) {
            cache.keys.minByOrNull { kotlin.math.abs(it - b) }?.takeIf { kotlin.math.abs(it - b) * STEP <= withinMs }?.let { cache[it] }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        requests.close()
        synchronized(lock) { waiting.values.toList().also { waiting.clear() } }.forEach { it.complete(null) }
        scope.cancel()
        // Released on the worker thread, after any frame it is still reading (the native calls are serialized).
        runCatching {
            executor.execute { runCatching { retriever?.release() }; retriever = null }
            executor.shutdown()
        }
    }

    private fun giveUp() {
        dead = true
        synchronized(lock) { waiting.values.toList().also { waiting.clear() } }.forEach { it.complete(null) }
    }

    private fun finish(b: Long, img: ImageBitmap?) {
        synchronized(lock) { waiting.remove(b) }?.complete(img)
    }

    /** Runs on the worker thread only. Tries the real link first, then the link the player uses (in-app relay). */
    private fun open(): MediaMetadataRetriever? {
        retriever?.let { return it }
        if (closed || dead) return null
        for (link in listOf(src, playing).distinct()) {
            val mr = MediaMetadataRetriever()
            val ok = runCatching {
                if (link == src) mr.setDataSource(link, hdrs) else mr.setDataSource(link, emptyMap())
            }.isSuccess
            if (ok) { retriever = mr; opened = true; return mr }
            runCatching { mr.release() }
        }
        giveUp()
        return null
    }

    /** Runs on the worker thread only. */
    private fun extract(ms: Long): ImageBitmap? {
        if (closed) return null
        val r = open() ?: return null
        val us = ms * 1000L
        val bmp: Bitmap = (if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, W, H)
        else r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)) ?: return null
        val out = if (bmp.width > W + 8) {
            val h = (bmp.height * (W.toFloat() / bmp.width)).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, W, h, true).also { if (it !== bmp) bmp.recycle() }
        } else bmp
        out.prepareToDraw()
        return out.asImageBitmap()
    }

    private companion object {
        const val STEP = 10_000L
        const val OPEN = -2L
        const val MAX_FRAMES = 80
        const val W = 320
        const val H = 180

        /** The real link behind an in-app relay link (http://127.0.0.1:port/r/<base64>/v.ext). */
        fun original(url: String): String {
            if (!Relay.isWrapped(url)) return url
            val token = url.substringAfter("/r/").substringBefore("/")
            return runCatching { String(Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)) }.getOrDefault(url)
        }
    }
}
