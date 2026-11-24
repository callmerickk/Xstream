package com.example.streamnest

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** What a quick look at a link (first byte only) tells us. */
data class LinkProbe(
    val ok: Boolean, val status: Int, val ttfbMs: Long, val size: Long, val seekable: Boolean,
    val type: String?, val host: String?, val finalHost: String?, val error: String?,
    /** 0.9.2: the server is only busy (pixeldrain's 403 "too many files opened too fast"): skip it now, it isn't dead. */
    val busy: Boolean = false,
)

/** 0.9.2: pixeldrain's own hosts (pixeldrain.com / .dev / .net …). */
fun isPixeldrain(url: String?): Boolean = runCatching { android.net.Uri.parse(url).host?.lowercase()?.contains("pixeldrain") == true }.getOrDefault(false)

/**
 * Saved video on the phone's storage (like a file on a hard disk): the player reads from here first and only asks the
 * server for what isn't saved. The title page saves the first minute (or the minute from your resume point) before you
 * press Play. Up to 2 GB; the oldest is removed first.
 *
 * Files are saved under a key made from their file name + size (not the link), so a refreshed link for the same file
 * still finds what was saved.
 */
@OptIn(UnstableApi::class)
object StreamCache {
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    @Volatile private var cache: SimpleCache? = null
    private val keys = ConcurrentHashMap<String, String>()

    fun get(ctx: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(File(ctx.applicationContext.cacheDir, "vcache"), LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(ctx.applicationContext)).also { cache = it }
    }

    /** Exit: every saved video start / first frame is deleted (the phone stays light). Returns the bytes freed. */
    fun clearAll(ctx: Context): Long = runCatching {
        val c = get(ctx)
        val before = c.cacheSpace
        c.keys.toList().forEach { k -> runCatching { c.removeResource(k) } }
        before - c.cacheSpace
    }.getOrDefault(0L)

    /** TV 0.8.5: drops what is saved for one video (a title removed from Continue Watching). */
    fun removeUrl(ctx: Context, url: String) { runCatching { get(ctx).removeResource(keyFor(Uri.parse(url))) } }
    fun usedBytes(ctx: Context): Long = runCatching { get(ctx).cacheSpace }.getOrDefault(0L)
    /** TV 0.9.15: without opening the cache (opening builds a disk database). */
    fun usedBytesIfOpen(): Long = runCatching { cache?.cacheSpace ?: 0L }.getOrDefault(0L)

    /** Remembers the stable key of a link (file name + size when the addon gives them). */
    fun register(item: StreamItem) {
        val url = item.stream.url ?: return
        val f = item.stream.behaviorHints?.filename?.trim()?.lowercase()
        val size = item.stream.behaviorHints?.videoSize
        if (!f.isNullOrEmpty() && size != null && size > 0) keys[url] = "f:$f:$size"
    }

    fun keyFor(uri: Uri): String = keys[uri.toString()] ?: uri.toString()

    /** Player data source: saved video first, then the network. Files on the phone (downloads) are read directly. */
    fun factory(ctx: Context, upstream: DataSource.Factory): DataSource.Factory {
        val cached = cachedFactory(ctx, upstream)
        return DataSource.Factory { LocalOrCached(upstream.createDataSource(), cached.createDataSource()) }
    }

    /** Reads file:// / content:// straight from the upstream source, everything else through the saved-video cache. */
    private class LocalOrCached(private val local: DataSource, private val cached: DataSource) : DataSource {
        private var cur: DataSource? = null
        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
            local.addTransferListener(transferListener); cached.addTransferListener(transferListener)
        }
        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme?.lowercase()
            // Pieces of a streaming playlist (DASH / HLS) go straight to the network: the saved-video cache is for whole
            // files (instant start); routing every 4-second piece through it only added waits on its lock.
            val path = dataSpec.uri.path.orEmpty().lowercase()
            val piece = path.endsWith(".m4s") || path.endsWith(".ts") || path.endsWith(".mpd") || path.endsWith(".m3u8") || path.endsWith(".m4a") ||
                path.endsWith(".vtt") || path.endsWith(".aac") || "/dash/" in path
            val ds = if (scheme == "file" || scheme == "content" || scheme == null || piece) local else cached
            cur = ds
            return ds.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = cur!!.read(buffer, offset, length)
        override fun getUri(): Uri? = cur?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = cur?.responseHeaders ?: emptyMap()
        override fun close() { val c = cur; cur = null; c?.close() }
    }

    private fun cachedFactory(ctx: Context, upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(get(ctx))
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory { spec -> keyFor(spec.uri) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setEventListener(object : CacheDataSource.EventListener {
                override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) { PlayTiming.savedRead.addAndGet(cachedBytesRead) }
                override fun onCacheIgnored(reason: Int) {}
            })

    private fun writerSource(ctx: Context): CacheDataSource {
        val http = OkHttpDataSource.Factory(Api.http).setUserAgent(PlayerUserAgent)
        val up = DefaultDataSource.Factory(ctx, withStreamHeaders(http))
        return CacheDataSource.Factory().setCache(get(ctx)).setUpstreamDataSourceFactory(up)
            .setCacheKeyFactory { spec -> keyFor(spec.uri) }.createDataSource()
    }

    /** Bytes of this link already saved from [start] on (continuous). */
    fun savedBytes(ctx: Context, url: String, start: Long, length: Long): Long =
        runCatching { get(ctx).getCachedBytes(keyFor(Uri.parse(url)), start, length) }.getOrDefault(0L)

    /**
     * Saves [length] bytes of the link from byte [start]. [onProgress] gets 0..1. Returns null when it worked, else why not.
     * Cancelling the coroutine stops it (what was saved stays saved).
     */
    suspend fun save(ctx: Context, url: String, start: Long, length: Long, onProgress: (Float) -> Unit): String? =
        saveParts(ctx, url, start, length, 1, null, onProgress)

    /** Servers that refused parallel pieces (403 / 429): one connection from now on. */
    private val singleHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // 0.9.1 (smartParts, Xstream 0.8.20): pixeldrain refuses more than a few connections at once: it gets 2 pieces (1 if it
    // refuses even those). Other hosts that refuse are learned and kept on disk for 7 days (max 50).
    private val seedHosts = setOf("pixeldrain.dev", "pixeldrain.com", "pixeldrain.net")
    private val learnedAt = ConcurrentHashMap<String, Long>()
    /** How often a host delivered all of 4+ parallel pieces this session (2 or more = 8 pieces are allowed). */
    private val parallelOk = ConcurrentHashMap<String, Int>()
    private val loggedSingle: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var singleLoaded = false
    private const val SINGLE_KEY = "single_hosts"
    private const val SINGLE_MAX = 50
    private const val SINGLE_TTL = 7L * 24 * 3600 * 1000
    private fun singlePrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("stream_cache", Context.MODE_PRIVATE)

    private fun loadSingleHosts(ctx: Context) {
        if (singleLoaded) return
        synchronized(this) {
            if (singleLoaded) return
            val now = System.currentTimeMillis()
            runCatching {
                singlePrefs(ctx).getString(SINGLE_KEY, null).orEmpty().split(';').forEach { e ->
                    val h = e.substringBefore('=')
                    val t = e.substringAfter('=', "").toLongOrNull() ?: return@forEach
                    if (h.isNotBlank() && now - t < SINGLE_TTL) { learnedAt[h] = t; singleHosts += h }
                }
            }
            singleLoaded = true
        }
    }

    /** This host refused extra connections: one connection from now on, and remembered across restarts. */
    private fun rememberSingle(ctx: Context, host: String) {
        if (host.isBlank()) return
        parallelOk.remove(host)
        singleHosts += host
        if (host in seedHosts) return
        val now = System.currentTimeMillis()
        learnedAt[host] = now
        learnedAt.entries.removeIf { now - it.value >= SINGLE_TTL }
        if (learnedAt.size > SINGLE_MAX) {
            learnedAt.entries.sortedBy { it.value }.take(learnedAt.size - SINGLE_MAX).forEach { learnedAt.remove(it.key); singleHosts.remove(it.key) }
        }
        runCatching { singlePrefs(ctx).edit().putString(SINGLE_KEY, learnedAt.entries.joinToString(";") { "${it.key}=${it.value}" }).apply() }
    }

    /** 4+ parallel pieces all delivered. Twice this session = the host may get 8 pieces. */
    private fun noteParallelOk(host: String, parts: Int) {
        if (host.isBlank()) return
        val n = parallelOk.merge(host, 1) { a, b -> a + b } ?: 1
        if (n == 2) ActLog.add("NET", "$host answered $parts parallel parts twice — up to 8 allowed")
    }
    /** File sizes learned from probes (for the playback booster). */
    val sizes = ConcurrentHashMap<String, Long>()

    /** Parallel pieces on? (Settings › Playback › Parallel download, on by default.) */
    @Volatile var parallel = true

    /**
     * Like a download manager (IDM): the range is split into [parts] pieces downloaded at the same time over separate
     * connections (servers that limit each connection's speed then deliver several times faster). [onHeadDone] runs as
     * soon as the first piece — the part the player needs first — is complete. A server that refuses extra connections
     * is remembered and gets one connection after that. Null = all saved, else why not.
     */
    suspend fun saveParts(ctx: Context, url: String, start: Long, length: Long, parts0: Int, onHeadDone: (() -> Unit)?, onProgress: (Float) -> Unit): String? =
        withContext(Dispatchers.IO) {
            val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
            val smart = Feature.smartParts
            val parts = if (!smart) { if (!parallel || host in singleHosts || length < 4L * 1024 * 1024) 1 else parts0.coerceIn(1, 8) } else {
                // 0.9.1 (smartParts): 4 pieces by default; 8 only for a host that already delivered 4 parallel pieces twice.
                loadSingleHosts(ctx)
                val cap = if ((parallelOk[host] ?: 0) >= 2) 8 else 4
                val wantsParallel = parallel && length >= 4L * 1024 * 1024 && parts0 > 1
                if (wantsParallel && host in singleHosts && loggedSingle.add(host)) ActLog.add("NET", "$host: saving with one connection (it refuses extra ones)")
                if (!parallel || host in singleHosts || length < 4L * 1024 * 1024) 1
                else if (host in seedHosts) parts0.coerceIn(1, 2)
                else parts0.coerceIn(1, cap)
            }
            val piece = (length + parts - 1) / parts
            val got = LongArray(parts)
            val headSent = java.util.concurrent.atomic.AtomicBoolean(false)
            val errors = java.util.Collections.synchronizedList(ArrayList<String>())
            // 0.9.1 (smartParts): part 0 saved completely / an error that is not a 403 or 429 / all pieces delivering (once).
            val head0Done = java.util.concurrent.atomic.AtomicBoolean(false)
            val otherError = java.util.concurrent.atomic.AtomicBoolean(false)
            val parallelCounted = java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                coroutineScope {
                    (0 until parts).map { i ->
                        val pos = start + i * piece
                        val len = minOf(piece, start + length - pos)
                        launch {
                            if (len <= 0) return@launch
                            val spec = DataSpec.Builder().setUri(Uri.parse(url)).setPosition(pos).setLength(len).setKey(keyFor(Uri.parse(url))).build()
                            val headAt = minOf(len, length / 4)
                            val w = CacheWriter(writerSource(ctx), spec, null) { _, cached, _ ->
                                got[i] = cached
                                // The first quarter of the range is in, without gaps: enough for the player to start.
                                if (i == 0 && cached >= headAt && !headSent.getAndSet(true)) onHeadDone?.invoke()
                                if (smart && parts >= 4 && !parallelCounted.get() && got.all { it > 0 } && parallelCounted.compareAndSet(false, true)) noteParallelOk(host, parts)
                                onProgress((got.sum().toFloat() / length).coerceIn(0f, 1f))
                            }
                            try {
                                coroutineScope {
                                    val watcher = launch { try { awaitCancellation() } finally { w.cancel() } }
                                    try { runInterruptible { w.cache() } } finally { watcher.cancel() }
                                }
                                if (i == 0) head0Done.set(true)
                                if (i == 0 && !headSent.getAndSet(true)) onHeadDone?.invoke()
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                val msg = e.javaClass.simpleName + (e.message?.let { ": " + it.take(120) } ?: "")
                                val refused = msg.contains("403") || msg.contains("429")
                                if (!refused) otherError.set(true)
                                // 0.9.1 (smartParts): remembered on disk too (7 days), not only until the app closes.
                                if (parts > 1 && refused) { if (smart) rememberSingle(ctx, host) else singleHosts += host }
                                errors += msg
                            }
                        }
                    }
                }
                val first = errors.firstOrNull()
                // 0.9.1 (smartParts): the host refused the EXTRA connections (403 / 429) but part 0, the head, is saved
                // completely: a good link — success, and the host is on the one-connection list from now on.
                if (smart && first != null && parts > 1 && head0Done.get() && !otherError.get()) {
                    ActLog.add("NET", "$host refused extra connections — the first part is saved, using the link")
                    null
                } else first
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.javaClass.simpleName + (e.message?.let { ": " + it.take(120) } ?: "")
            }
        }

    /**
     * Races links: asks them all at once and returns the first one whose server answers with a real video (not a web
     * page, not refused), with its answer. The others are cancelled. Null when none answers within [timeoutMs].
     */
    suspend fun race(urls: List<String>, timeoutMs: Long = 6000): Pair<String, LinkProbe>? = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        val list = urls.distinct()
        val ch = kotlinx.coroutines.channels.Channel<Pair<String, LinkProbe>?>(list.size)
        // Detached: a slow server's request is simply abandoned (we don't wait for it to finish).
        val bgJob = kotlinx.coroutines.SupervisorJob()
        val bg = kotlinx.coroutines.CoroutineScope(bgJob + Dispatchers.IO)
        list.forEach { u ->
            bg.launch {
                val pr = probe(u)
                val good = pr.ok && pr.type?.startsWith("text/html", true) != true
                // A server that answers but then sends nothing (seen with FSL storage links) must not win: the first
                // 128 KB of real video has to arrive. Playlists are small text files: their answer is enough.
                val ok = good && (isPlaylistType(u, pr.type) || firstBytes(u, 128 * 1024))
                if (ok) { warm += u; if (Feature.smartParts) raced[u] = SystemClock.elapsedRealtime() to pr }   // 0.9.1: kept for the next link
                ch.trySend(if (ok) u to pr else null)
            }
        }
        var left = list.size
        var win: Pair<String, LinkProbe>? = null
        try {
            while (left > 0 && win == null) { win = ch.receive(); left-- }
        } finally {
            // The others may finish a moment later: they become the warm backups (tried first if the winner stalls).
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { kotlinx.coroutines.delay(5_000); bgJob.cancel() }
        }
        win
    }

    /** 0.9.1 (smartParts): the answer each good link gave inside race() (kept 60 s), so attempt() needn't probe it again. */
    private val raced = ConcurrentHashMap<String, Pair<Long, LinkProbe>>()
    fun racedProbe(url: String): LinkProbe? {
        if (!Feature.smartParts) return null
        if (raced.size > 60) raced.clear()
        return raced[url]?.takeIf { SystemClock.elapsedRealtime() - it.first < 60_000 }?.second
    }

    /** Links that already delivered real video this session (fast, proven backups). */
    val warm: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun isPlaylistType(url: String, type: String?): Boolean {
        val t = type?.lowercase().orEmpty()
        return "dash" in t || "mpegurl" in t || StreamType.quick(url)?.isNotEmpty() == true
    }

    /** Do the first [want] bytes of the video actually arrive? (Also warms the connection for the player.) */
    suspend fun firstBytes(url: String, want: Int): Boolean = runInterruptible(Dispatchers.IO) {
        runCatching {
            val b = Request.Builder().url(url).header("Range", "bytes=0-${want - 1}")
            val h = StreamHeaders.forUrl(url)
            h.forEach { (k, v) -> b.header(k, v) }
            if (h.keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", PlayerUserAgent)
            Api.http.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) return@use false
                val src = r.body?.source() ?: return@use false
                var got = 0L
                val buf = okio.Buffer()
                while (got < want) {
                    val n = src.read(buf, (want - got).coerceAtMost(16_384L))
                    if (n < 0) break
                    got += n; buf.clear()
                }
                // A whole (small) file counts too; a short HTML / XML error doesn't.
                got >= want || (got > 0 && r.header("Content-Type")?.lowercase()?.let { it.startsWith("video/") || "octet" in it } == true)
            }
        }.getOrDefault(false)
    }

    /** One quick request for the first byte: is the server answering, how fast, how big, and can we jump inside the file? */
    suspend fun probe(url: String): LinkProbe = withContext(Dispatchers.IO) {
        val host = runCatching { Uri.parse(url).host }.getOrNull()
        if (!url.startsWith("http")) return@withContext LinkProbe(false, 0, 0, -1, false, null, host, host, "not a web link")
        val t0 = SystemClock.elapsedRealtime()
        try {
            val b = Request.Builder().url(url).header("Range", "bytes=0-0")
            val h = StreamHeaders.forUrl(url)
            h.forEach { (k, v) -> b.header(k, v) }
            if (h.keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", PlayerUserAgent)
            Api.http.newCall(b.build()).execute().use { r ->
                val ms = SystemClock.elapsedRealtime() - t0
                val range = r.header("Content-Range")
                val size = range?.substringAfter('/')?.trim()?.toLongOrNull() ?: r.header("Content-Length")?.toLongOrNull()?.takeIf { r.code == 200 } ?: -1L
                val ct = r.header("Content-Type")?.lowercase().orEmpty()
                // Streaming playlists (DASH / HLS) are always seekable: the player jumps between their pieces.
                val playlist = "dash" in ct || "mpegurl" in ct || StreamType.quick(url)?.isNotEmpty() == true
                val seekable = playlist || r.code == 206 || r.header("Accept-Ranges")?.contains("bytes", true) == true
                // A playlist names its picture sizes: remember the best one (quality for links that don't say it).
                if (playlist && r.code == 200) runCatching { ResMemory.fromPlaylist(url, r.peekBody(300_000).string()) }
                // A web page instead of a video (link-page hosts): not playable.
                if (r.isSuccessful && ct.startsWith("text/html")) {
                    return@use LinkProbe(false, r.code, ms, size, false, ct, host, r.request.url.host, "web page, not a video")
                }
                // The server already says what the file is: the player needs no extra "what type is it?" request.
                if (r.isSuccessful) when {
                    "dash" in ct -> StreamType.remember(url, StreamType.DASH)
                    "mpegurl" in ct -> StreamType.remember(url, StreamType.HLS)
                    ct.startsWith("video/") || ct.startsWith("audio/") -> StreamType.remember(url, "")
                    ct.contains("octet-stream") && size > 20L * 1024 * 1024 -> StreamType.remember(url, "")
                }
                if (r.isSuccessful && size > 0) sizes[url] = size
                // 0.9.2: pixeldrain answers 403 with a short JSON note when an address opens files too fast (rate limit /
                // captcha / hotlink): that link is busy, not dead (read ≤ 2 KB of the note).
                if (r.code == 403 && "json" in ct && (isPixeldrain(url) || isPixeldrain(r.request.url.toString()))) {
                    val note = runCatching { r.peekBody(2048).string().lowercase() }.getOrDefault("")
                    if (listOf("rate_limit", "captcha", "hotlink", "limit").any { it in note })
                        return@use LinkProbe(false, r.code, ms, size, false, r.header("Content-Type"), host, r.request.url.host, "busy (pixeldrain limit, HTTP 403)", busy = true)
                }
                LinkProbe(r.isSuccessful, r.code, ms, size, seekable, r.header("Content-Type"), host, r.request.url.host,
                    if (r.isSuccessful) null else "HTTP ${r.code}")
            }
        } catch (e: Throwable) {
            LinkProbe(false, 0, SystemClock.elapsedRealtime() - t0, -1, false, null, host, host, e.javaClass.simpleName + (e.message?.let { ": " + it.take(100) } ?: ""))
        }
    }
}

/** "2h 10min" / "130 min" / "45m" → milliseconds (null when unknown). */
fun runtimeMs(runtime: String?): Long? {
    val t = runtime?.lowercase() ?: return null
    val h = Regex("(\\d+)\\s*h").find(t)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val m = Regex("(\\d+)\\s*m").find(t)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val total = h * 60 + m
    return if (total > 0) total * 60_000L else null
}

/**
 * How many bytes one minute of this file is, roughly: from its size and length when known, else from its quality.
 * [bigFile] = more than ~150 MB a minute (then only ~15 s are saved).
 */
fun minuteBytes(item: StreamItem?, size: Long, durMs: Long?): Long {
    if (size > 0 && durMs != null && durMs > 10 * 60_000L) return (size.toDouble() * 60_000.0 / durMs * 1.15).toLong()
    val res = item?.resolution() ?: 0
    val mbps = when { res >= 1440 -> 25.0; res >= 1080 -> 8.0; res >= 720 -> 4.0; else -> 5.0 }
    return (mbps * 1_000_000 / 8 * 60).toLong()
}

/** Is this link a playlist (HLS / DASH)? Those stream in small pieces and start fast; nothing to pre-save. */
fun isPlaylistLink(item: StreamItem?, url: String): Boolean =
    StreamType.mimeFor(url, item?.fileName()) != null
