package com.example.streamnest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

@Serializable enum class YtmKind { SONG, VIDEO, ALBUM, SINGLE, PLAYLIST, ARTIST, PODCAST, EPISODE }
enum class YtmStyle { SPEED_DIAL_GRID, QUICK_PICKS, SQUARE_CAROUSEL, WIDE_VIDEO_CAROUSEL, LIST, COMMUNITY_PLAYLIST, BIG_DISCOVER, EXPLORE_TILES }

@Serializable data class YtmItem(
    val kind: YtmKind, val id: String, val title: String, val subtitle: String, val thumb: String?,
    val playlistId: String? = null, val params: String? = null, val durationMs: Long = 0,
    val explicit: Boolean = false, val hasVideo: Boolean = false, val counterpartId: String? = null,
)
data class YtmShelf(val title: String, val strapline: String?, val style: YtmStyle, val items: List<YtmItem>, val moreBrowseId: String?)
data class YtmPage(val shelves: List<YtmShelf>, val items: List<YtmItem> = emptyList(), val continuation: String? = null, val title: String? = null,
    /** Mood chips (label, browse params) on Home. */ val chips: List<Pair<String, String>> = emptyList(), val header: YtmItem? = null)
data class YtmNext(val queue: List<YtmItem>, val continuation: String?, val lyricsBrowseId: String?, val relatedBrowseId: String?, val chips: List<Pair<String, String>>,
    /** song id → its other version (Song ↔ Video). */ val counterparts: Map<String, String> = emptyMap(), val title: String? = null)

object YtMusic {
    const val CLIENT_VERSION = "1.20241023.01.00"
    private const val TTL = 30L * 60L * 1000L
    private const val BASE = "https://music.youtube.com/youtubei/v1/"
    private data class Cached(val at: Long, val page: YtmPage)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonElement.obj() = this as? JsonObject
    private fun JsonElement.str() = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement.arr() = this as? JsonArray
    private fun JsonObject.str(key: String) = this[key]?.str()
    private fun JsonObject.obj(key: String) = this[key]?.obj()
    private fun JsonObject.arr(key: String) = this[key]?.arr()
    private fun JsonObject.text(key: String) = textOf(this[key])

    private fun textOf(el: JsonElement?): String? {
        if (el == null) return null
        val o = el.obj()
        if (o != null) {
            o.str("simpleText")?.let { return it }
            o.arr("runs")?.mapNotNull { it.obj()?.str("text") }?.joinToString("")?.takeIf { it.isNotBlank() }?.let { return it }
            o.obj("text")?.let { return textOf(it) }
        }
        return el.str()
    }

    private fun walk(el: JsonElement, visit: (JsonObject) -> Unit) {
        when (el) {
            is JsonObject -> { visit(el); el.values.forEach { walk(it, visit) } }
            is JsonArray -> el.forEach { walk(it, visit) }
            else -> Unit
        }
    }

    private suspend fun endpoint(endpoint: String, body: JsonObject): JsonObject? = withContext(Dispatchers.IO) { runCatching {
        val visitor = YtDirect.visitorData()
        val ctx = buildJsonObject {
            putJsonObject("client") {
                put("clientName", "WEB_REMIX"); put("clientVersion", CLIENT_VERSION); put("hl", "en")
                put("gl", Locale.getDefault().country.ifBlank { "IN" })
                if (!visitor.isNullOrBlank()) put("visitorData", visitor)
            }
        }
        val request = buildJsonObject { put("context", ctx); body.forEach { (k, v) -> put(k, v) } }
        val req = Request.Builder().url(BASE + endpoint + "?prettyPrint=false")
            .header("Origin", "https://music.youtube.com").header("Referer", "https://music.youtube.com/")
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .header("X-Goog-Visitor-Id", visitor.orEmpty())
            .post(request.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        YtDirect.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()?.let { json.parseToJsonElement(it).obj() }
        }
    }.onFailure { ActLog.add("MUSIC", "YouTube Music " + endpoint + ": " + it.message) }.getOrNull() }

    private fun context(browseId: String, continuation: String? = null, params: String? = null) = buildJsonObject {
        put("browseId", browseId)
        if (continuation != null) put("continuation", continuation)
        if (params != null) put("params", params)
    }

    private fun duration(text: String?): Long {
        if (text.isNullOrBlank()) return 0
        val parts = text.split(":").mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) return 0
        var seconds = 0L
        parts.forEach { seconds = seconds * 60 + it }
        return seconds * 1000
    }

    private fun hd(url: String?, wide: Boolean = false): String? {
        if (url.isNullOrBlank()) return null
        if (url.contains("i.ytimg.com/vi/")) return url.substringBefore("=") + "/maxresdefault.jpg"
        if (url.contains("lh3.googleusercontent.com") || url.contains("yt3.ggpht.com"))
            return url.substringBefore("=") + if (wide) "=w1280-h720" else "=w1080-h1080-l90-rj"
        return url
    }

    private fun thumb(o: JsonObject, wide: Boolean = false): String? {
        var result: String? = null
        walk(o) { x -> x.arr("thumbnails")?.forEach { t -> t.obj()?.str("url")?.let { result = it } } }
        return hd(result, wide)
    }

    private fun browseId(o: JsonObject): String? {
        var result: String? = null
        walk(o) { x -> if (result == null) result = x.obj("browseEndpoint")?.str("browseId") }
        return result
    }
    private fun videoId(o: JsonObject): String? {
        var result: String? = null
        walk(o) { x -> if (result == null) result = x.obj("watchEndpoint")?.str("videoId") ?: x.str("videoId") }
        return result
    }
    private fun playlistId(o: JsonObject): String? {
        var result: String? = null
        walk(o) { x -> if (result == null) result = x.obj("watchEndpoint")?.str("playlistId") ?: x.str("playlistId") }
        return result
    }
    private fun params(o: JsonObject): String? {
        var result: String? = null
        walk(o) { x -> if (result == null) result = x.obj("browseEndpoint")?.str("params") }
        return result
    }
    private fun strings(o: JsonObject): List<String> {
        val out = mutableListOf<String>()
        walk(o) { x ->
            x.str("simpleText")?.let { out += it }
            x.arr("runs")?.forEach { r -> r.obj()?.str("text")?.let { out += it } }
        }
        return out.distinct()
    }

    private fun videoType(o: JsonObject): String {
        var t = ""
        walk(o) { x -> if (t.isEmpty()) x.str("musicVideoType")?.let { t = it } }
        return t
    }

    private fun item(o: JsonObject): YtmItem? {
        // The item's own target: its navigationEndpoint first (an album card also holds a play button for its songs).
        val nav = o.obj("navigationEndpoint")
            ?: o.arr("flexColumns")?.firstNotNullOfOrNull { c -> var e: JsonObject? = null; walk(c) { x -> if (e == null) e = x.obj("navigationEndpoint") }; e }
        val navBrowse = nav?.obj("browseEndpoint")?.str("browseId")
        val navVideo = nav?.obj("watchEndpoint")?.str("videoId")
        val video = navVideo ?: if (navBrowse == null) (o.str("videoId") ?: o.obj("playlistItemData")?.str("videoId") ?: videoId(o)) else null
        val browse = navBrowse ?: if (video == null) browseId(o) else null
        val title = o.obj("title")?.let(::textOf)
            ?: o.arr("flexColumns")?.firstOrNull()?.let(::textOf)
            ?: strings(o).firstOrNull()?.takeIf { it.length in 1..180 } ?: return null
        val subtitle = o.obj("subtitle")?.let(::textOf)
            ?: o.obj("longBylineText")?.let(::textOf)
            ?: o.arr("flexColumns")?.drop(1)?.mapNotNull { textOf(it) }?.filter { it.isNotBlank() }?.joinToString(" • ")
            ?: ""
        val vt = videoType(o)
        val kind = when {
            video != null && (vt.contains("OMV") || vt.contains("UGC") || subtitle.startsWith("Video")) -> YtmKind.VIDEO
            video != null -> YtmKind.SONG
            browse?.startsWith("UC") == true -> YtmKind.ARTIST
            browse?.startsWith("MPRE") == true -> if (subtitle.startsWith("Single")) YtmKind.SINGLE else YtmKind.ALBUM
            browse?.startsWith("MPSP") == true -> YtmKind.PODCAST
            else -> YtmKind.PLAYLIST
        }
        val length = o.obj("lengthText")?.let(::textOf) ?: o.obj("duration")?.let(::textOf)
            ?: o.arr("fixedColumns")?.firstOrNull()?.let(::textOf)
        val id = video ?: browse ?: return null
        return YtmItem(kind, id, title, subtitle, thumb(o, kind == YtmKind.VIDEO), playlistId(o), params(o),
            duration(length), false, kind == YtmKind.VIDEO)
    }

    private val renderers = listOf("musicResponsiveListItemRenderer", "musicTwoRowItemRenderer", "musicVideoRenderer",
        "playlistPanelVideoRenderer", "musicMultiRowListItemRenderer", "musicNavigationButtonRenderer")

    /** Only real item cards (never their parent sections, which used to come out with the wrong titles). */
    private fun extractItems(root: JsonObject): List<YtmItem> {
        val out = LinkedHashMap<String, YtmItem>()
        walk(root) { o ->
            renderers.forEach { k -> o.obj(k)?.let { r -> item(r)?.let { out.putIfAbsent(it.kind.name + ":" + it.id, it) } } }
        }
        return out.values.toList()
    }

    private fun chips(root: JsonObject): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        walk(root) { o ->
            o.obj("chipCloudChipRenderer")?.let { c ->
                val label = c.obj("text")?.let(::textOf) ?: return@let
                val params = c.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("params") ?: return@let
                out += label to params
            }
        }
        return out.distinctBy { it.first }
    }

    private fun extractShelves(root: JsonObject): List<YtmShelf> {
        val shelves = mutableListOf<YtmShelf>()
        walk(root) { wrapper ->
            val key = listOf("musicCarouselShelfRenderer", "musicShelfRenderer", "musicImmersiveCarouselShelfRenderer")
                .firstOrNull { wrapper[it]?.obj() != null } ?: return@walk
            val o = wrapper.obj(key) ?: return@walk
            val title = o.obj("header")?.let { h ->
                h.obj("musicCarouselShelfBasicHeaderRenderer")?.obj("title")?.let(::textOf)
                    ?: h.obj("musicShelfHeaderRenderer")?.obj("title")?.let(::textOf)
            } ?: o.obj("title")?.let(::textOf) ?: return@walk
            val items = extractItems(o).distinctBy { it.id }
            if (items.isEmpty()) return@walk
            val wide = items.any { it.kind == YtmKind.VIDEO }
            val style = when {
                title.contains("quick pick", true) -> YtmStyle.QUICK_PICKS
                title.contains("community", true) -> YtmStyle.COMMUNITY_PLAYLIST
                title.contains("discover", true) -> YtmStyle.BIG_DISCOVER
                wide -> YtmStyle.WIDE_VIDEO_CAROUSEL
                key.contains("Shelf") -> YtmStyle.LIST
                else -> YtmStyle.SQUARE_CAROUSEL
            }
            shelves += YtmShelf(title, null, style, items, browseId(o.obj("header") ?: o))
        }
        return shelves.distinctBy { it.title }
    }

    private fun continuation(root: JsonObject): String? {
        var token: String? = null
        walk(root) { o -> if (token == null) token = o.obj("nextContinuationData")?.str("continuation") ?: o.str("continuation") }
        return token
    }

    private suspend fun cached(key: String, block: suspend () -> YtmPage): YtmPage {
        cache[key]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.let { return it.page }
        val page = block()
        cache[key] = Cached(System.currentTimeMillis(), page)
        return page
    }

    suspend fun home(continuation: String? = null, params: String? = null): YtmPage = cached("home:" + continuation.orEmpty() + ":" + params.orEmpty()) {
        val r = withContext(Dispatchers.IO) { endpoint("browse", buildJsonObject {
            put("browseId", "FEmusic_home"); if (continuation != null) put("continuation", continuation); if (params != null) put("params", params)
        }) } ?: return@cached YtmPage(emptyList())
        YtmPage(extractShelves(r), extractItems(r), continuation(r), chips = chips(r))
    }
    suspend fun explore(): YtmPage = browsePage("FEmusic_explore", "explore")
    suspend fun newReleases(): YtmPage = browsePage("FEmusic_new_releases", "new")
    suspend fun charts(country: String = Locale.getDefault().country.ifBlank { "IN" }): YtmPage = cached("charts:" + country) {
        val r = withContext(Dispatchers.IO) { endpoint("browse", buildJsonObject { put("browseId", "FEmusic_charts") }) }
            ?: return@cached YtmPage(emptyList())
        YtmPage(extractShelves(r), extractItems(r), title = "Charts")
    }
    suspend fun moods(): YtmPage = browsePage("FEmusic_moods_and_genres", "moods")
    suspend fun moodPage(params: String): YtmPage = cached("mood:" + params) {
        val r = withContext(Dispatchers.IO) { endpoint("browse", buildJsonObject { put("browseId", "FEmusic_moods_and_genres"); put("params", params) }) }
            ?: return@cached YtmPage(emptyList())
        YtmPage(extractShelves(r), extractItems(r))
    }
    private suspend fun browsePage(id: String, key: String): YtmPage = cached(key) {
        val r = withContext(Dispatchers.IO) { endpoint("browse", context(id)) } ?: return@cached YtmPage(emptyList())
        YtmPage(extractShelves(r), extractItems(r), continuation(r))
    }

    suspend fun searchSuggestions(q: String): List<String> = withContext(Dispatchers.IO) {
        val r = endpoint("music/get_search_suggestions", buildJsonObject { put("input", q) }) ?: return@withContext emptyList()
        strings(r).filter { it.isNotBlank() }.distinct().take(8)
    }
    private val filters = mapOf("all" to null, "songs" to "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D",
        "videos" to "EgWKAQIQAWoKEAkQBRAKEAMQBA%3D%3D", "albums" to "EgWKAQIYAWoKEAkQBRAKEAMQBA%3D%3D",
        "artists" to "EgWKAQIgAWoKEAkQBRAKEAMQBA%3D%3D", "playlists" to "EgWKAQIoAWoKEAkQBRAKEAMQBA%3D%3D")
    suspend fun search(q: String, filter: String = "all"): YtmPage = cached("search:" + filter + ":" + q.lowercase()) {
        val r = withContext(Dispatchers.IO) { endpoint("search", buildJsonObject {
            put("query", q); filters[filter.lowercase()]?.let { put("params", it) }
        }) } ?: return@cached YtmPage(emptyList())
        YtmPage(extractShelves(r), extractItems(r), continuation(r), q)
    }
    suspend fun browse(browseId: String): YtmPage = cached("browse:" + browseId) {
        val r = withContext(Dispatchers.IO) { endpoint("browse", context(browseId)) } ?: return@cached YtmPage(emptyList())
        var head: YtmItem? = null
        walk(r) { o ->
            if (head == null) listOf("musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer", "musicDetailHeaderRenderer", "musicResponsiveHeaderRenderer")
                .firstNotNullOfOrNull { o.obj(it) }?.let { h ->
                    head = YtmItem(if (browseId.startsWith("UC")) YtmKind.ARTIST else YtmKind.ALBUM, browseId,
                        h.obj("title")?.let(::textOf) ?: "", listOfNotNull(h.obj("subtitle")?.let(::textOf), h.obj("secondSubtitle")?.let(::textOf)).joinToString(" • "),
                        thumb(h, browseId.startsWith("UC")))
                }
        }
        YtmPage(extractShelves(r), extractItems(r), continuation(r), head?.title ?: browseId, header = head)
    }

    suspend fun next(videoId: String, playlistId: String? = null, params: String? = null): YtmNext = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("videoId", videoId); if (playlistId != null) put("playlistId", playlistId); if (params != null) put("params", params)
        }
        val r = endpoint("next", body) ?: return@withContext YtmNext(emptyList(), null, null, null, emptyList())
        var lyrics: String? = null; var related: String? = null
        walk(r) { o ->
            val id = o.obj("browseEndpoint")?.str("browseId")
            if (id?.startsWith("MPLY") == true) lyrics = id
            if (id?.startsWith("MPTR") == true) related = id
        }
        val counterparts = HashMap<String, String>()
        walk(r) { o ->
            o.obj("playlistPanelVideoWrapperRenderer")?.let { w ->
                val a = w.obj("primaryRenderer")?.obj("playlistPanelVideoRenderer")?.str("videoId")
                val b = w.arr("counterpart")?.firstOrNull()?.obj()?.obj("counterpartRenderer")?.obj("playlistPanelVideoRenderer")?.str("videoId")
                if (a != null && b != null) { counterparts[a] = b; counterparts[b] = a }
            }
        }
        var title: String? = null
        walk(r) { o -> if (title == null) o.obj("playlistPanelRenderer")?.obj("title")?.let { title = textOf(it) } }
        val queue = LinkedHashMap<String, YtmItem>()
        walk(r) { o -> o.obj("playlistPanelVideoRenderer")?.let { p -> item(p)?.let { queue.putIfAbsent(it.id, it) } } }
        YtmNext(queue.values.toList(), continuation(r), lyrics, related, emptyList(), counterparts, title)
    }
    suspend fun lyrics(browseId: String): String = withContext(Dispatchers.IO) {
        val r = endpoint("browse", context(browseId)) ?: return@withContext ""
        strings(r).filter { it.isNotBlank() }.distinct().joinToString("\n")
    }
    /** A browse page that needs params too (a mood or genre). */
    suspend fun browseWith(browseId: String, params: String): YtmPage = cached("browse:$browseId:$params") {
        val r = withContext(Dispatchers.IO) { endpoint("browse", context(browseId, params = params)) } ?: return@cached YtmPage(emptyList())
        YtmPage(extractShelves(r), extractItems(r), continuation(r))
    }
    suspend fun related(browseId: String): YtmPage = browsePage(browseId, "related:" + browseId)
}
