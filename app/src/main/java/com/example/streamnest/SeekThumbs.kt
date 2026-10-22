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
    /** HLS (Netflix / Prime Video / Hotstar plugin links, MovieBox playlists): frames come from the playlist's segments. */
    private val hls: Boolean = (src.startsWith("http", true)) &&
        (Regex("\\.m3u8(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(src) || StreamType.quick(src) == StreamType.HLS || StreamType.quick(playing) == StreamType.HLS)
    private val hlsFrames: HlsFrames? = if (hls) HlsFrames(src, hdrs) else null
    @Volatile private var dead = !hls && ((!src.startsWith("http://", true) && !src.startsWith("https://", true) && !Relay.isWrapped(playing)) ||
        Regex("\\.(m3u8|mpd)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(src))
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

    @Volatile private var prefetching = false

    /**
     * While the video plays, previews across the whole video are made quietly ahead of time (40 spots, one every few
     * seconds), so the seek bar shows a picture the moment you drag — the nearest one while the exact one is made.
     * A seek request always goes first.
     */
    fun prefetch(durMs: Long) {
        if (dead || closed || prefetching || durMs <= 0) return
        prefetching = true
        scope.launch {
            val spots = 40
            val gap = (durMs / spots).coerceAtLeast(STEP)
            var t = gap / 2
            while (t < durMs && !closed && !dead) {
                kotlinx.coroutines.delay(1_200)
                val busy = synchronized(lock) { waiting.isNotEmpty() }
                if (busy) continue
                val b = t / STEP
                if (!has(t)) runCatching { extract(b * STEP) }.getOrNull()?.let { img -> synchronized(lock) { cache[b] = img }; succeeded = true }
                t += gap
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
            executor.execute { runCatching { retriever?.release() }; retriever = null; hlsFrames?.close() }
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
        if (hlsFrames != null) { opened = true; return null }
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
        hlsFrames?.let { h -> return h.frame(ms, W)?.let { it.prepareToDraw(); it.asImageBitmap() } }
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
        const val MAX_FRAMES = 120
        // Small previews (Netflix-size): quicker to make and to send through the network.
        const val W = 192
        const val H = 108

        /** The real link behind an in-app relay link (http://127.0.0.1:port/r/<base64>/v.ext). */
        fun original(url: String): String {
            if (!Relay.isWrapped(url)) return url
            val token = url.substringAfter("/r/").substringBefore("/")
            return runCatching { String(Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)) }.getOrDefault(url)
        }
    }
}


/**
 * Seek previews for HLS: the lowest-quality variant's segment holding the time is downloaded (a few hundred KB, with
 * the stream's headers; AES-128 segments are decrypted) and one frame is read from it. Worker thread only.
 */
class HlsFrames(private val master: String, private val headers: Map<String, String>) {
    private data class Seg(val start: Long, val dur: Long, val url: String, val key: String?, val iv: ByteArray?, val seq: Long)
    private var segs: List<Seg>? = null
    private var initSeg: ByteArray? = null
    private var failed = false
    private val keys = HashMap<String, ByteArray>()
    private val dir = java.io.File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", "xs-hls-thumbs").apply { mkdirs() }

    private fun get(url: String): ByteArray? = runCatching {
        val b = okhttp3.Request.Builder().url(url)
        headers.forEach { (k, v) -> runCatching { b.header(k, v) } }
        Api.http.newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    }.getOrNull()
    private fun text(url: String) = get(url)?.toString(Charsets.UTF_8)
    private fun abs(base: String, u: String) = runCatching { java.net.URI(base).resolve(u.trim()).toString() }.getOrDefault(u)
    private fun attr(line: String, name: String) = Regex("$name=(\"[^\"]*\"|[^,]*)").find(line)?.groupValues?.get(1)?.trim('"')

    private fun load(): List<Seg>? {
        segs?.let { return it }
        if (failed) return null
        var url = master
        var body = text(url) ?: run { failed = true; return null }
        if (body.contains("#EXT-X-STREAM-INF")) {
            // The smallest variant: least to download per preview.
            val lines = body.lines()
            val best = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") }.mapNotNull { i ->
                val u = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@mapNotNull null
                (attr(lines[i], "BANDWIDTH")?.toLongOrNull() ?: Long.MAX_VALUE) to abs(url, u)
            }.minByOrNull { it.first }?.second ?: run { failed = true; return null }
            url = best
            body = text(url) ?: run { failed = true; return null }
        }
        val out = ArrayList<Seg>()
        var t = 0L; var dur = 0L; var key: String? = null; var iv: ByteArray? = null
        var seq = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        for (raw in body.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-MAP") -> attr(l, "URI")?.let { initSeg = get(abs(url, it)) }
                l.startsWith("#EXT-X-KEY") -> {
                    val m = attr(l, "METHOD")
                    key = if (m == "AES-128") attr(l, "URI")?.let { abs(url, it) } else null
                    iv = attr(l, "IV")?.removePrefix("0x")?.removePrefix("0X")?.let { h -> runCatching { h.padStart(32, '0').chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull() }
                }
                l.startsWith("#EXTINF:") -> dur = ((l.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0) * 1000).toLong()
                l.isNotEmpty() && !l.startsWith("#") -> { out += Seg(t, dur, abs(url, l), key, iv, seq); t += dur; seq++ }
            }
        }
        if (out.isEmpty()) { failed = true; return null }
        segs = out
        return out
    }

    private fun decrypt(s: Seg, data: ByteArray): ByteArray? {
        val k = s.key ?: return data
        val keyBytes = keys.getOrPut(k) { get(k) ?: return null }
        val iv = s.iv ?: java.nio.ByteBuffer.allocate(16).putLong(8, s.seq).array()
        return runCatching {
            val c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(keyBytes, "AES"), javax.crypto.spec.IvParameterSpec(iv))
            c.doFinal(data)
        }.getOrNull()
    }

    fun frame(ms: Long, width: Int): android.graphics.Bitmap? {
        val list = load() ?: return null
        val s = list.lastOrNull { it.start <= ms } ?: list.first()
        val raw = get(s.url) ?: return null
        val data = decrypt(s, raw) ?: return null
        val f = java.io.File(dir, "seg-${s.seq}.${if (initSeg != null) "mp4" else "ts"}")
        runCatching { f.outputStream().use { o -> initSeg?.let { o.write(it) }; o.write(data) } }.getOrElse { return null }
        val mr = MediaMetadataRetriever()
        return try {
            mr.setDataSource(f.absolutePath)
            val us = ((ms - s.start).coerceAtLeast(0L)) * 1000L
            val bmp = mr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: mr.getFrameAtTime(0) ?: return null
            if (bmp.width > width + 8) {
                val h = (bmp.height * (width.toFloat() / bmp.width)).toInt().coerceAtLeast(1)
                android.graphics.Bitmap.createScaledBitmap(bmp, width, h, true).also { if (it !== bmp) bmp.recycle() }
            } else bmp
        } catch (_: Throwable) { null } finally { runCatching { mr.release() }; f.delete() }
    }

    fun close() { runCatching { dir.listFiles()?.forEach { it.delete() } } }
}
