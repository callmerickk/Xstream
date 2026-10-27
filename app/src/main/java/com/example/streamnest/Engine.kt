package com.example.streamnest

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import kotlinx.serialization.encodeToString
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.concurrent.ConcurrentHashMap

/*
 * Some addons (e.g. MovieBox) only serve video when the player sends the request headers they give
 * with each stream (behaviorHints.proxyHeaders.request: Referer, User-Agent, Origin…). We remember them
 * by link and by server, and add them to every video request, in both engines.
 */
object StreamHeaders {
    private val byUrl = ConcurrentHashMap<String, Map<String, String>>()
    private val byHost = ConcurrentHashMap<String, Map<String, String>>()
    @Volatile var active: Map<String, String> = emptyMap()
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences("stream_headers", Context.MODE_PRIVATE)
        runCatching {
            prefs?.getString("all", null)?.let { Api.json.decodeFromString<Map<String, Map<String, String>>>(it) }?.forEach { (u, h) ->
                byUrl[u] = h; Uri.parse(u).host?.let { byHost[it] = h }
            }
        }
    }

    /** Hosts whose links bring their own Cookie (MovieBox's CloudFront pass): the app's cookie jar must not replace it. */
    val ownCookieHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun remember(s: StreamItem) {
        val url = s.stream.url ?: return
        val h = s.stream.behaviorHints?.proxyHeaders?.request?.filterKeys { it.isNotBlank() }.orEmpty()
        if (h.isEmpty()) return
        byUrl[url] = h
        Uri.parse(url).host?.let { host ->
            byHost[host] = h
            if (h.keys.any { it.equals("Cookie", true) }) ownCookieHosts += host
        }
        // Keep the newest 200 links so Continue Watching still works after a restart.
        val keep = byUrl.entries.toList().takeLast(200).associate { it.key to it.value }
        runCatching { prefs?.edit()?.putString("all", Api.json.encodeToString(keep))?.apply() }
    }

    fun forUrl(url: String): Map<String, String> = byUrl[url] ?: Uri.parse(url).host?.let { byHost[it] }.orEmpty()

    /**
     * Headers for any request while a video plays. The link itself: its own headers. Anything else (the video pieces of a
     * playlist, on the same or another server): the PLAYING link's headers first. Many links share one server (pengu.uk
     * serves VA Player, vidfast, cinejoy, MovieBox… each with different Referer / tokens / cookies), so "the last headers
     * seen for this server" were often another link's — and the pieces were refused (403 / 428).
     */
    fun forRequest(uri: Uri): Map<String, String> = byUrl[uri.toString()] ?: active.takeIf { it.isNotEmpty() } ?: uri.host?.let { byHost[it] }.orEmpty()

    fun exact(uri: Uri): Map<String, String>? = byUrl[uri.toString()]

    /** Headers for a link's own requests and its pieces, set while it is prepared, tested or played. */
    fun use(url: String) { active = forUrl(url) }
}

/**
 * Headers of a second player that plays at the same time as the main one (plugin mode's quiet Netflix-type swap): its
 * own link's headers until it becomes the main player ([map] = null → the usual rules).
 */
class HeaderHold(@Volatile var map: Map<String, String>?)

/** Wraps the HTTP source so every request carries the stream's headers. */
@OptIn(UnstableApi::class)
fun withStreamHeaders(upstream: DataSource.Factory, hold: HeaderHold? = null): DataSource.Factory =
    ResolvingDataSource.Factory(upstream) { spec ->
        val own = hold?.map
        val h = if (own != null) StreamHeaders.exact(spec.uri) ?: own else StreamHeaders.forRequest(spec.uri)
        if (h.isEmpty()) spec else spec.withAdditionalHeaders(h)
    }

// ───────────── VLC engine (Playback > Player engine) ─────────────

/** Events from VLC, turned into the same states the player UI already uses. */
interface VlcListener {
    fun onPlaying(playing: Boolean)
    fun onBuffering(buffering: Boolean)
    fun onFirstFrame()
    fun onError()
    fun onEnded()
}

class VlcEngine(ctx: Context, private val listener: VlcListener) {
    private val lib = LibVLC(ctx.applicationContext, arrayListOf(
        "--network-caching=1000", "--http-reconnect", "--audio-time-stretch", "--avcodec-skiploopfilter=1", "--sub-autodetect-file",
    ))
    val mp = MediaPlayer(lib)
    /** Movie volume 0…1 (Watch together lowers it while a friend talks). */
    fun volumeScale(f: Float) { runCatching { mp.volume = (f * 100).toInt().coerceIn(0, 100) } }
    private var pendingSeek = 0L
    /** Preferred audio languages in order (e.g. hindi, original); applied once when a file starts. */
    var preferAudio: List<String> = emptyList()
    private var audioChosen = false
    private var vout = false
    private var pendingPlay = false
    private var attached: VLCVideoLayout? = null

    init {
        mp.setEventListener(MediaPlayer.EventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> {
                    listener.onPlaying(true); listener.onBuffering(false)
                    if (!audioChosen) { audioChosen = true; pickAudio() }
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> listener.onPlaying(false)
                MediaPlayer.Event.Buffering -> listener.onBuffering(e.buffering < 100f)
                MediaPlayer.Event.Vout -> if (e.voutCount > 0 && !vout) {
                    vout = true
                    // The start position is given to VLC up front (:start-time); if a server ignored it, seek now.
                    val want = pendingSeek
                    pendingSeek = 0
                    if (want > 5_000 && runCatching { mp.time }.getOrDefault(0L) < want - 5_000) runCatching { mp.setTime(want) }
                    listener.onFirstFrame()
                }
                MediaPlayer.Event.EncounteredError -> listener.onError()
                MediaPlayer.Event.EndReached -> listener.onEnded()
            }
        })
    }

    fun attach(layout: VLCVideoLayout) {
        if (attached === layout) return
        runCatching { mp.detachViews() }
        mp.attachViews(layout, null, true, false)
        val go = synchronized(this) { attached = layout; pendingPlay.also { pendingPlay = false } }
        if (go) work { mp.play() }
    }

    private fun pickAudio() {
        val tracks = audioTracks()
        if (tracks.size < 2) return
        for (lang in preferAudio) {
            val words = (QsLanguages[lang] ?: listOf(lang)).filter { it.length >= 3 }
            tracks.firstOrNull { (_, name) -> val n = name.lowercase(); words.any { n.contains(it) } }?.let { selectAudio(it.first); return }
        }
    }

    /** [hw] = false plays with software decoding (slower, but handles codecs the phone's chip can't). */
    /**
     * [hw] = false plays with software decoding (slower, but handles codecs the phone's chip can't).
     * [cachingMs] = how much video VLC keeps ready ahead; bigger for large / 4K files so they don't stutter.
     */
    fun load(url: String, headers: Map<String, String>, subs: List<Subtitle>, startMs: Long, hw: Boolean = true, cachingMs: Int = 1000) {
        vout = false
        audioChosen = false
        val m = Media(lib, Uri.parse(url))
        m.setHWDecoderEnabled(hw, false)
        if (!hw) m.addOption(":avcodec-threads=0")
        m.addOption(":network-caching=${cachingMs.coerceIn(300, 8000)}")
        // Big files: keep up by dropping a late frame instead of freezing.
        if (cachingMs > 1500) { m.addOption(":drop-late-frames"); m.addOption(":skip-frames") }
        // Start exactly where you were (switching source / audio, resuming).
        if (startMs > 1_000) m.addOption(":start-time=${"%.3f".format(java.util.Locale.US, startMs / 1000.0)}")
        headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { m.addOption(":http-user-agent=${it.value}") }
        headers.entries.firstOrNull { it.key.equals("Referer", true) }?.let { m.addOption(":http-referrer=${it.value}") }
        pendingSeek = startMs
        listener.onBuffering(true)
        // Swapping the video waits for VLC to close the old one (a dead link can take many seconds): done on VLC's own
        // thread, never the screen's, so the app can't freeze ("not responding").
        val sub = subs.filter { it.url.isNotBlank() }
        work {
            mp.setMedia(m)
            m.release()
            sub.forEach { runCatching { mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(it.url), false) } }
            // VLC needs its video views attached before playback starts, or it plays without a picture.
            synchronized(this) { if (attached != null) mp.play() else pendingPlay = true }
        }
    }

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "vlc-control").apply { isDaemon = true } }
    private fun work(f: () -> Unit) { runCatching { worker.execute { runCatching { f() } } } }

    // Position, length and playing are read by a small background thread and kept here: asking VLC from the screen's
    // thread waited on VLC's lock while it opened a slow link, and the app froze ("not responding").
    @Volatile private var cTime = 0L
    @Volatile private var cLength = 0L
    @Volatile private var cPlaying = false
    @Volatile private var alive = true
    private val poller = Thread({
        while (alive) {
            runCatching { cTime = mp.time.coerceAtLeast(0L); cLength = mp.length.coerceAtLeast(0L); cPlaying = mp.isPlaying }
            runCatching { Thread.sleep(200) }
        }
    }, "vlc-state").apply { isDaemon = true; start() }
    val time: Long get() = cTime
    val length: Long get() = cLength
    val isPlaying: Boolean get() = cPlaying
    fun play() = work { mp.play() }
    fun pause() = work { mp.pause() }
    fun seek(ms: Long) { cTime = ms.coerceAtLeast(0L); work { mp.setTime(ms.coerceAtLeast(0L)) } }
    var rate: Float
        get() = runCatching { mp.rate }.getOrDefault(1f)
        set(v) { mp.setRate(v) }

    /** 0 = Fit, 1 = Crop (fill screen), 2 = Stretch — same order as the ExoPlayer aspect modes. */
    fun setAspect(i: Int) {
        mp.setVideoScale(when (i) { 1 -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN; 2 -> MediaPlayer.ScaleType.SURFACE_FILL; else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT })
    }

    fun audioTracks(): List<Pair<Int, String>> = mp.audioTracks?.filter { it.id >= 0 }?.map { it.id to it.name } ?: emptyList()
    fun subTracks(): List<Pair<Int, String>> = mp.spuTracks?.filter { it.id >= 0 }?.map { it.id to it.name } ?: emptyList()
    val audioTrack: Int get() = mp.audioTrack
    val subTrack: Int get() = mp.spuTrack
    fun selectAudio(id: Int) { mp.setAudioTrack(id) }
    fun selectSub(id: Int) { mp.setSpuTrack(id) }

    /** Called when switching back to ExoPlayer: the VLC video view goes away, so let go of it too. */
    fun stop() = runCatching {
        synchronized(this) { pendingPlay = false }
        runCatching { mp.detachViews() }; attached = null
        work { mp.stop() }
    }

    fun release() {
        runCatching { mp.setEventListener(null as MediaPlayer.EventListener?) }
        runCatching { mp.detachViews() }
        // Stopping / freeing VLC can wait on the network: off the screen's thread.
        alive = false
        work { runCatching { poller.join(1_000) }; runCatching { mp.stop() }; runCatching { mp.release() }; runCatching { lib.release() } }
        runCatching { worker.shutdown() }
    }
}

// ───────────── Playback log (Developer's Corner) ─────────────

/** Why links failed, newest first, so stubborn files can be fixed. */
object PlaybackLog {
    private const val KEY = "lines"
    fun add(ctx: Context, line: String) {
        val p = ctx.getSharedPreferences("playback_log", Context.MODE_PRIVATE)
        val stamp = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.US).format(java.util.Date())
        val all = listOf("$stamp  $line") + all(ctx)
        p.edit().putString(KEY, all.take(60).joinToString("\n")).apply()
    }
    fun all(ctx: Context): List<String> =
        ctx.getSharedPreferences("playback_log", Context.MODE_PRIVATE).getString(KEY, "")!!.split("\n").filter { it.isNotBlank() }
    fun clear(ctx: Context) { ctx.getSharedPreferences("playback_log", Context.MODE_PRIVATE).edit().clear().apply() }

    /** Asks the server for the first bytes, to learn why a link failed (e.g. HTTP 403). */
    suspend fun probe(url: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val b = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-1")
            StreamHeaders.forUrl(url).forEach { (k, v) -> runCatching { b.header(k, v) } }
            Api.http.newCall(b.build()).execute().use { r -> "HTTP ${r.code}" + (r.header("Content-Type")?.let { " · $it" } ?: "") }
        }.getOrElse { "network: ${it.javaClass.simpleName}" }
    }
}

/**
 * What kind of link a stream is (DASH manifest, HLS playlist or a plain file). ExoPlayer guesses from the end of the
 * link, but many addon links hide it (a proxy / token link like ".../play?id=…" that actually returns a DASH .mpd).
 * Guessed wrong, ExoPlayer tries to read the manifest as a video file and fails. So: the link's or the file name's
 * extension first, else one small request (with the addon's headers) to read the server's content type / first bytes.
 */
object StreamType {
    const val DASH = "application/dash+xml"
    const val HLS = "application/x-mpegURL"
    private val cache = ConcurrentHashMap<String, String>() // url -> mime ("" = plain file)
    private val fileExt = Regex("\\.(mp4|mkv|webm|avi|mov|m4v|ts|flv|wmv|mpg|mpeg|3gp)$", RegexOption.IGNORE_CASE)

    private fun byName(name: String?): String? {
        val n = name?.substringBefore('?')?.substringBefore('#')?.trim()?.lowercase() ?: return null
        return when {
            n.endsWith(".mpd") || Regex("\\.mpd(?![a-z0-9])").containsMatchIn(n) -> DASH
            n.endsWith(".m3u8") || n.endsWith(".m3u") || Regex("\\.m3u8(?![a-z0-9])").containsMatchIn(n) -> HLS
            fileExt.containsMatchIn(n) -> ""
            else -> null
        }
    }

    /** Known without any network request (null = unknown). */
    fun quick(url: String, filename: String? = null): String? =
        cache[url] ?: byName(Uri.parse(url).path) ?: byName(filename)

    /** Type learned from another request (e.g. the first-byte check): no second request needed. "" = plain file. */
    fun remember(url: String, mime: String) { cache[url] = mime }

    /** The MIME type to give ExoPlayer, or null to let it guess. */
    fun mimeFor(url: String, filename: String? = null): String? = quick(url, filename)?.takeIf { it.isNotEmpty() }

    /** Needs a probe before playing? (http link whose type can't be told from its name). */
    fun needsProbe(url: String, filename: String?): Boolean =
        (url.startsWith("http://") || url.startsWith("https://")) && !Relay.isWrapped(url) && quick(url, filename) == null

    /** One small request (first 4 KB) to find the type. Never throws; gives up after 4 s (= plain file). */
    fun probe(url: String): String {
        cache[url]?.let { return it }
        val mime = runCatching {
            val b = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-4095")
            StreamHeaders.forUrl(url).forEach { (k, v) -> b.header(k, v) }
            if (StreamHeaders.forUrl(url).keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", PlayerUserAgent)
            val client = Api.http.newBuilder().callTimeout(2500, java.util.concurrent.TimeUnit.MILLISECONDS).build()
            client.newCall(b.build()).execute().use { r ->
                val ct = r.header("Content-Type").orEmpty().lowercase()
                val finalName = byName(r.request.url.encodedPath)
                when {
                    "dash" in ct -> DASH
                    "mpegurl" in ct -> HLS
                    finalName != null -> finalName
                    ct.startsWith("video/") || ct.startsWith("audio/") -> ""
                    else -> {
                        val head = r.body?.source()?.let { src -> src.request(512); src.buffer.snapshot(minOf(512L, src.buffer.size).toInt()).utf8() }.orEmpty()
                        when {
                            head.trimStart().startsWith("#EXTM3U") -> HLS
                            "<MPD" in head -> DASH
                            else -> ""
                        }
                    }
                }
            }
        }.getOrDefault("")
        cache[url] = mime
        return mime
    }
}
