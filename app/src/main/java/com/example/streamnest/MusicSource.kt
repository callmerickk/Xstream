package com.example.streamnest

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Xstream Music's catalogue: the SimpMusic CloudStream plugin (AllForU repository) — YouTube Music's home shelves,
 * search, albums, playlists and artists with their covers. The plugin is only asked for lists; each song's audio comes
 * from [YtDirect.music] (the same fast YouTube path as the trailers), in two qualities picked by [MusicQuality].
 * Settings › Music › "Use Live server for music" switches back to the Live server's songs.
 */
object SimpMusic {
    /** One thing on a shelf: a song (plays at once) or an album / playlist / artist (opens its song list). */
    data class Item(val title: String, val subtitle: String, val art: String?, val url: String, val videoId: String?, val wide: Boolean = false) {
        val isSong get() = videoId != null
        fun track() = LiveServer.Track(id = "yt:$videoId", title = title, artist = subtitle, artworkURL = art, format = "yt")
    }
    data class Shelf(val title: String, val items: List<Item>, val wide: Boolean)

    private val lock = Mutex()
    private var api: MainAPI? = null
    @Volatile var home: List<Shelf>? = null; private set

    fun useLive(ctx: Context) = BuildConfig.HAS_LIVE && ctx.getSharedPreferences("music", Context.MODE_PRIVATE).getBoolean("useLive", false)
    fun setUseLive(ctx: Context, on: Boolean) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE).edit().putBoolean("useLive", on).apply()

    /** The plugin, downloaded and loaded on first use. */
    suspend fun api(): MainAPI? = lock.withLock {
        api?.let { return it }
        withContext(Dispatchers.IO) {
            if (CsPlugins.repos.value.none { it.url == CsPlugins.ALLFORU }) CsPlugins.addRepo(CsPlugins.ALLFORU)
            fun find() = CsPlugins.catalog.value.firstOrNull { it.internalName.equals("SimpMusic", true) || it.name.equals("SimpMusic", true) }
            val e = find() ?: run { runCatching { CsPlugins.refresh() }; find() } ?: return@withContext null
            runCatching { CsPlugins.ensureLoaded(e).apis.firstOrNull() }.onFailure { ActLog.add("MUSIC", "SimpMusic didn't load: ${it.message}") }.getOrNull()
        }?.also { api = it }
    }

    /** "…watch?v=ID", a JSON blob with "videoId", or a bare 11-character id. */
    fun videoIdOf(s: String?): String? {
        if (s.isNullOrBlank()) return null
        Regex("\"?videoId\"?\\s*[:=]\\s*\"?([A-Za-z0-9_-]{11})").find(s)?.let { return it.groupValues[1] }
        Regex("[?&]v=([A-Za-z0-9_-]{11})").find(s)?.let { return it.groupValues[1] }
        Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(s)?.let { return it.groupValues[1] }
        Regex("\\|([A-Za-z0-9_-]{11})$").find(s)?.let { return it.groupValues[1] }
        return s.trim().takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
    }

    private fun item(r: SearchResponse, wide: Boolean): Item {
        val vid = if (r is MovieSearchResponse) videoIdOf(r.url) else null
        val sub = when {
            vid != null -> "Song"
            r.url.contains("browse/MPRE") || r.url.contains("album", true) -> "Album"
            r.url.contains("UC") && r.url.contains("channel", true) -> "Artist"
            else -> "Playlist"
        }
        // Names often carry " - Artist" or " • Artist": shown as the subtitle.
        val parts = r.name.split(" • ", " · ", " - ", limit = 2)
        return Item(parts[0].trim(), parts.getOrNull(1)?.trim() ?: sub, r.posterUrl, r.url, vid, wide)
    }

    /** YouTube Music's home shelves (Quick picks, new releases, charts, moods…). */
    suspend fun loadHome(): List<Shelf> {
        home?.let { return it }
        val direct = runCatching { YtMusic.home() }.getOrNull()?.shelves.orEmpty().map { shelf ->
            Shelf(shelf.title, shelf.items.map { item ->
                val song = item.kind == YtmKind.SONG || item.kind == YtmKind.VIDEO
                Item(item.title, item.subtitle, item.thumb,
                    if (song) "https://music.youtube.com/watch?v=" + item.id else "https://music.youtube.com/browse/" + item.id,
                    item.id.takeIf { song }, item.kind == YtmKind.VIDEO)
            }, shelf.style == YtmStyle.WIDE_VIDEO_CAROUSEL)
        }.filter { it.items.isNotEmpty() }
        if (direct.isNotEmpty()) { home = direct; return direct }
        val a = api() ?: return emptyList()
        val shelves = coroutineScope {
            a.mainPage.map { mp ->
                async(Dispatchers.IO) {
                    runCatching { withTimeoutOrNull(20_000) { a.getMainPage(1, MainPageRequest(mp.name, mp.data, mp.horizontalImages)) } }
                        .onFailure { ActLog.add("MUSIC", "shelf ${mp.name}: ${it.message}") }.getOrNull()?.items.orEmpty()
                }
            }.awaitAll().flatten()
        }.map { l: HomePageList -> Shelf(l.name, l.list.map { item(it, l.isHorizontalImages) }, l.isHorizontalImages) }.filter { it.items.isNotEmpty() }
        if (shelves.isNotEmpty()) home = shelves
        return shelves
    }

    suspend fun search(q: String): List<Item> = withContext(Dispatchers.IO) {
        val direct = runCatching { YtMusic.search(q).items }.getOrDefault(emptyList())
        if (direct.isNotEmpty()) return@withContext direct.map { result ->
            val song = result.kind == YtmKind.SONG || result.kind == YtmKind.VIDEO
            Item(result.title, result.subtitle, result.thumb,
                if (song) "https://music.youtube.com/watch?v=" + result.id else "https://music.youtube.com/browse/" + result.id,
                result.id.takeIf { song }, result.kind == YtmKind.VIDEO)
        }
        val a = api() ?: return@withContext emptyList()
        runCatching { a.search(q).orEmpty().map { item(it, false) } }.getOrDefault(emptyList())
    }

    /** The songs of an album / playlist / artist (or the one song). */
    suspend fun songs(it: Item): List<LiveServer.Track> = withContext(Dispatchers.IO) {
        if (it.isSong) return@withContext listOf(it.track())
        val directId = it.url.substringAfter("music.youtube.com/browse/", "").takeIf { id -> id.isNotBlank() }
        if (directId != null) {
            val direct = runCatching { YtMusic.browse(directId).items }.getOrDefault(emptyList())
                .filter { result -> result.kind == YtmKind.SONG || result.kind == YtmKind.VIDEO }
                .map { result -> LiveServer.Track(id = "yt:" + result.id, title = result.title, artist = result.subtitle,
                    album = it.title, durationMs = result.durationMs, format = "yt", artworkURL = result.thumb) }
            if (direct.isNotEmpty()) return@withContext direct
        }
        val a = api() ?: return@withContext emptyList()
        when (val r = runCatching { a.load(it.url) }.getOrNull()) {
            is TvSeriesLoadResponse -> {
                var loggedRaw = false
                r.episodes.mapNotNull { e ->
                if (!loggedRaw) { ActLog.add("MUSIC", "playlist first episode data=" + e.data.take(500)); loggedRaw = true }
                val id = videoIdOf(e.data) ?: return@mapNotNull null
                LiveServer.Track(id = "yt:$id", title = e.name ?: "Song", artist = e.description?.lineSequence()?.firstOrNull().orEmpty().take(60).ifBlank { r.name },
                    album = r.name, durationMs = (e.runTime ?: 0) * 1000L, format = "yt", artworkURL = e.posterUrl ?: r.posterUrl)
                }
            }
            is MovieLoadResponse -> listOfNotNull(videoIdOf(r.dataUrl)?.let { id ->
                LiveServer.Track(id = "yt:$id", title = r.name, artist = it.subtitle, format = "yt", artworkURL = r.posterUrl)
            })
            else -> emptyList()
        }
    }
}

/**
 * Music audio quality, automatic: about 160 kbps on a good connection, 128 kbps when it's slow. Starts from what the
 * phone reports for its connection, drops while a song stops to load, and goes back up after a minute of smooth play.
 */
object MusicQuality {
    @Volatile var hi = true
    @Volatile var smoothSince = 0L

    fun start(ctx: Context) {
        hi = runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val kbps = caps?.linkDownstreamBandwidthKbps ?: 0
            caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || kbps >= 1_500)
        }.getOrDefault(true)
        smoothSince = System.currentTimeMillis()
    }
}
