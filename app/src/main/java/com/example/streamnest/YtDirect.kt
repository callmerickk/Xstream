package com.example.streamnest

import android.graphics.Matrix
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Trailers as real video: asks YouTube's player service for a trailer's direct video (and audio) addresses and plays them
 * in our own ExoPlayer, so a trailer can be cropped, zoomed, rounded and faded like any picture, with no YouTube page,
 * buttons or WebView. When YouTube refuses every client, callers fall back to the old embedded player.
 */
object YtDirect {
    /** A playable trailer: [video] (with [audio] when they are separate), or an HLS playlist. [ua] must be sent along. */
    data class Src(val video: String, val audio: String?, val hls: Boolean, val ua: String, val height: Int,
        /** Dubbed soundtracks in the same video: (language name, audio address). */
        val tracks: List<Pair<String, String>> = emptyList(), val audioName: String? = null)

    /** Your audio languages as YouTube codes ("hi", "en"…), best first: a dubbed track in one of them is chosen. */
    @Volatile var prefLangs: List<String> = emptyList()

    private class Client(val name: String, val num: Int, val version: String, val ua: String, val extra: Map<String, Any>)

    // The app clients YouTube serves plain addresses to (no page scripts to decode). All are asked at once and the
    // answers pooled (Nuvio's order: visionOS first, then Android, then iPhone; the VR client is a last extra).
    private val clients = listOf(
        Client("VISIONOS", 101, "1.02",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            mapOf("deviceMake" to "Apple", "deviceModel" to "RealityDevice17,1", "osName" to "visionOS", "osVersion" to "26.5.23O471")),
        Client("ANDROID", 3, "20.10.35",
            "com.google.android.youtube/20.10.35 (Linux; U; Android 14; en_US) gzip",
            mapOf("osName" to "Android", "osVersion" to "14", "platform" to "MOBILE", "androidSdkVersion" to 34)),
        Client("IOS", 5, "20.10.1",
            "com.google.ios.youtube/20.10.1 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X)",
            mapOf("deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "17.4.0.21E219", "platform" to "MOBILE")),
        Client("ANDROID_VR", 28, "1.62.27",
            "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L")),
    )

    private val cache = object : LinkedHashMap<String, Pair<Long, Src?>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Src?>>?) = size > 40
    }
    private val JSONT = "application/json".toMediaType()

    /** Tallest picture to ask for: small on TV sticks (a second decoder next to the movie's), sharper on phones. */
    private val maxHeight: Int get() = if (BuildConfig.IS_TV) 480 else 720

    /** IPv4 first for YouTube (IPv6 to googlevideo is slow on some networks, e.g. Jio). Keeps the app's DNS choice. */
    val http: okhttp3.OkHttpClient by lazy {
        val base = Api.http
        base.newBuilder().dns(object : okhttp3.Dns {
            override fun lookup(hostname: String) = base.dns.lookup(hostname).sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
        }).build()
    }
    private val probeHttp by lazy { http.newBuilder().callTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build() }

    /** The watch page's API key and visitor id (stops most "sign in to confirm you're not a bot" refusals). 6 h. */
    @Volatile private var watchCfg: Triple<Long, String?, String?>? = null
    private fun watchConfig(videoId: String): Pair<String?, String?> {
        watchCfg?.let { (at, k, v) -> if (System.currentTimeMillis() - at < 6 * 3600_000L && v != null) return k to v }
        val html = runCatching {
            quick.newCall(Request.Builder().url("https://www.youtube.com/watch?v=$videoId&hl=en")
                .header("User-Agent", BROWSER_UA).header("Accept-Language", "en-US,en;q=0.9").build()).execute().use { r -> r.body?.string() }
        }.getOrNull().orEmpty()
        val key = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        val visitor = Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        if (visitor != null) watchCfg = Triple(System.currentTimeMillis(), key, visitor)
        return key to visitor
    }

    /** Visitor identity used by YouTube Music requests; load it once from the existing watch-page config. */
    suspend fun visitorData(): String? = withContext(Dispatchers.IO) { watchConfig("dQw4w9WgXcQ").second }

    /** Non-blocking accessor after visitorData() or music resolution populated the watch config. */
    fun cachedVisitorData(): String? = watchCfg?.third

    /** The trailer's addresses, or null when YouTube offers nothing playable. Remembered for 3 hours. */
    suspend fun resolve(videoId: String): Src? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[videoId] }?.let { (at, src) -> if (now - at < 3 * 3600_000L) return@withContext src }
        val (key, visitor) = watchConfig(videoId)
        // Every client at once; the answers are pooled and kept in the clients' order of preference.
        val answers = coroutineScope { clients.map { c -> async { runCatching { ask(c, videoId, key, visitor) }.getOrNull() } }.awaitAll() }
        val pool = clients.indices.flatMap { i -> answers[i].orEmpty().map { clients[i].name to it } }
        var out: Src? = null
        if (pool.isNotEmpty()) {
            // Each candidate's link is checked (and moved to a backup server that answers) in parallel; the best that works wins.
            val checked = coroutineScope { pool.take(6).map { (n, s) -> async { n to reachable(s) } }.awaitAll() }
            val ok = checked.firstOrNull { it.second != null }
            out = ok?.second
            if (ok != null) ActLog.add("TRAILER", "$videoId · ${ok.first} · ${if (out!!.hls) "HLS" else "${out.height}p"}${if (visitor != null) " · visitor" else ""}")
            else ActLog.add("TRAILER", "$videoId · ${pool.size} links, none answered")
        }
        // YouTube said no to every app client: ask public Piped / Invidious mirrors (they hand out playable addresses).
        if (out == null) out = runCatching { mirrors(videoId) }.getOrNull()
        if (out == null) ActLog.add("TRAILER", "$videoId · no direct video (hidden embedded player instead)")
        synchronized(cache) { cache[videoId] = now to out }
        out
    }

    /** [s] with its video (and audio) moved to a googlevideo server that answers, or null when none does. */
    private suspend fun reachable(s: Src): Src? {
        if (s.hls) return s
        val v = alive(s.video, s.ua) ?: return null
        val a = s.audio?.let { alive(it, s.ua) ?: return null }
        return s.copy(video = v, audio = a)
    }

    /** The link itself or one of its backup servers (the "mn" list), whichever answers a 1-byte request first (2 s). */
    private suspend fun alive(url: String, ua: String): String? = coroutineScope {
        val u = android.net.Uri.parse(url)
        val host = u.host.orEmpty()
        val cands = mutableListOf(url)
        if (host.contains("googlevideo.com")) {
            u.getQueryParameter("mn")?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }?.forEachIndexed { i, sv ->
                val alt = host.replaceFirst(Regex("^rr\\d+---"), "rr${i + 1}---").replaceFirst(Regex("sn-[a-z0-9]+-[a-z0-9]+"), sv)
                if (alt != host) cands += url.replace(host, alt)
            }
        }
        val win = kotlinx.coroutines.CompletableDeferred<String?>()
        val jobs = cands.distinct().map { c -> launch(Dispatchers.IO) { if (probe(c, ua)) win.complete(c) } }
        launch { jobs.joinAll(); win.complete(null) }
        val r = kotlinx.coroutines.withTimeoutOrNull(2_500) { win.await() }
        coroutineContext.cancelChildren()
        r
    }

    private fun probe(url: String, ua: String): Boolean = runCatching {
        probeHttp.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").header("User-Agent", ua).build()).execute().use { it.code in 200..299 }
    }.getOrDefault(false)

    /** The media source for a resolved trailer (video + audio merged, one muxed file, or HLS). */
    @OptIn(UnstableApi::class)
    fun source(src: Src, audio: String? = src.audio): MediaSource {
        val ds = dataSource(src.ua)
        fun prog(u: String): MediaSource = ProgressiveMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(u))
        return when {
            src.hls -> HlsMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(src.video))
            audio != null -> MergingMediaSource(prog(src.video), prog(audio))
            else -> prog(src.video)
        }
    }

    /** "hindi" → "hi" … (the app's language names to YouTube / TMDB codes). */
    fun langCode(name: String): String? = when (name.trim().lowercase()) {
        "hindi" -> "hi"; "english" -> "en"; "tamil" -> "ta"; "telugu" -> "te"; "malayalam" -> "ml"; "kannada" -> "kn"
        "bengali" -> "bn"; "marathi" -> "mr"; "punjabi" -> "pa"; "gujarati" -> "gu"; "urdu" -> "ur"; "spanish" -> "es"
        "french" -> "fr"; "german" -> "de"; "italian" -> "it"; "japanese" -> "ja"; "korean" -> "ko"; "chinese" -> "zh"
        "portuguese" -> "pt"; "russian" -> "ru"; "arabic" -> "ar"; "indonesian" -> "id"; "thai" -> "th"; "turkish" -> "tr"
        else -> null
    }
    fun langName(code: String): String = java.util.Locale(code).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { code.uppercase() }

    /** OkHttp (IPv4 first) with googlevideo links fetched in 10 MB "&range=" pieces (no throttling, no stalls). */
    @OptIn(UnstableApi::class)
    fun dataSource(ua: String): androidx.media3.datasource.DataSource.Factory {
        val up = OkHttpDataSource.Factory(http).setUserAgent(ua)
        return androidx.media3.datasource.DataSource.Factory { YtChunked(up.createDataSource()) }
    }

    private val quick by lazy { http.newBuilder().callTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build() }
    private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"
    private val pipedApis = listOf("https://pipedapi.kavin.rocks", "https://pipedapi.adminforge.de", "https://api.piped.private.coffee")
    private val invidious = listOf("https://inv.nadeko.net", "https://invidious.nerdvpn.de", "https://yewtu.be", "https://invidious.f5.si")
    private fun getJson(url: String): JsonObject? = quick.newCall(Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()).execute().use { r ->
        if (!r.isSuccessful) null else r.body?.string()?.let { Api.json.parseToJsonElement(it) as? JsonObject }
    }

    private fun mirrors(videoId: String): Src? {
        fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        fun i(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.intOrNull ?: 0
        for (base in pipedApis) {
            val o = runCatching { getJson("$base/streams/$videoId") }.getOrNull() ?: continue
            val vids = ((o["videoStreams"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }.filter { s(it, "url") != null }
            val auds = ((o["audioStreams"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }.filter { s(it, "url") != null && s(it, "mimeType")?.contains("mp4") == true }
            val v = vids.filter { s(it, "mimeType")?.contains("mp4") == true && s(it, "codec")?.startsWith("avc") != false && i(it, "height") in 1..maxHeight }.maxByOrNull { i(it, "height") }
            val a = auds.maxByOrNull { i(it, "bitrate") }
            if (v != null && (a != null || (o["videoStreams"] != null && (v["videoOnly"] as? JsonPrimitive)?.contentOrNull == "false"))) {
                ActLog.add("TRAILER", "$videoId · mirror ${base.substringAfter("//")}")
                return Src(s(v, "url")!!, if ((v["videoOnly"] as? JsonPrimitive)?.contentOrNull == "false") null else s(a!!, "url"), false, BROWSER_UA, i(v, "height"))
            }
            s(o, "hls")?.let { ActLog.add("TRAILER", "$videoId · mirror HLS"); return Src(it, null, true, BROWSER_UA, 0) }
        }
        for (base in invidious) {
            val o = runCatching { getJson("$base/api/v1/videos/$videoId?local=true&fields=formatStreams,adaptiveFormats") }.getOrNull() ?: continue
            fun abs(u: String) = if (u.startsWith("/")) base + u else u
            val ad = ((o["adaptiveFormats"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }.filter { s(it, "url") != null }
            val v = ad.filter { s(it, "type")?.startsWith("video/mp4") == true && s(it, "type")?.contains("avc1") == true &&
                (s(it, "resolution")?.removeSuffix("p")?.toIntOrNull() ?: 0) in 1..maxHeight }.maxByOrNull { s(it, "resolution")?.removeSuffix("p")?.toIntOrNull() ?: 0 }
            val a = ad.filter { s(it, "type")?.startsWith("audio/mp4") == true }.maxByOrNull { s(it, "bitrate")?.toIntOrNull() ?: 0 }
            if (v != null && a != null) { ActLog.add("TRAILER", "$videoId · mirror ${base.substringAfter("//")}"); return Src(abs(s(v, "url")!!), abs(s(a, "url")!!), false, BROWSER_UA, s(v, "resolution")?.removeSuffix("p")?.toIntOrNull() ?: 0) }
            val mux = ((o["formatStreams"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }.lastOrNull { s(it, "url") != null }
            if (mux != null) { ActLog.add("TRAILER", "$videoId · mirror ${base.substringAfter("//")} (one file)"); return Src(abs(s(mux, "url")!!), null, false, BROWSER_UA, 360) }
        }
        return null
    }

    /** YouTube's player answer for one client: its "streamingData", or null when it refuses. */
    private fun streamingData(c: Client, videoId: String, key: String?, visitor: String?): JsonObject? {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", c.name); put("clientVersion", c.version); put("hl", "en"); put("gl", "US")
                    if (visitor != null) put("visitorData", visitor)
                    c.extra.forEach { (k, v) -> if (v is Int) put(k, v) else put(k, v.toString()) }
                }
            }
            put("videoId", videoId); put("contentCheckOk", true); put("racyCheckOk", true)
            putJsonObject("playbackContext") { putJsonObject("contentPlaybackContext") { put("html5Preference", "HTML5_PREF_WANTS") } }
        }
        val url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false" + (key?.let { "&key=$it" } ?: "")
        val req = Request.Builder().url(url)
            .header("User-Agent", c.ua).header("X-YouTube-Client-Name", c.num.toString()).header("X-YouTube-Client-Version", c.version)
            .header("Origin", "https://www.youtube.com").header("Accept-Language", "en-US,en;q=0.9")
            .apply { if (visitor != null) header("X-Goog-Visitor-Id", visitor) }
            .post(body.toString().toRequestBody(JSONT)).build()
        val text = quick.newCall(req).execute().use { r -> if (!r.isSuccessful) return null; r.body?.string() } ?: return null
        val root = Api.json.parseToJsonElement(text).jsonObject
        val status = ((root["playabilityStatus"] as? JsonObject)?.get("status") as? JsonPrimitive)?.contentOrNull
        if (status != "OK") return null
        return root["streamingData"] as? JsonObject
    }

    /** A song's audio: [hi] ≈ 160 kbps (Opus), [lo] ≈ 128 kbps (AAC). [ua] must be sent along. */
    data class MusicSrc(val hi: String?, val lo: String?, val ua: String)
    private val musicCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, MusicSrc>>()

    /** The song's two audio qualities (first client that answers with a working link). Remembered for 3 hours. */
    suspend fun music(videoId: String): MusicSrc? = withContext(Dispatchers.IO) {
        musicCache[videoId]?.let { (at, m) -> if (System.currentTimeMillis() - at < 3 * 3600_000L) return@withContext m }
        val (key, visitor) = watchConfig(videoId)
        val got = coroutineScope { clients.map { c -> async { c to runCatching { streamingData(c, videoId, key, visitor) }.getOrNull() } }.awaitAll() }
        for ((c, sd) in got) {
            sd ?: continue
            fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun i(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.intOrNull ?: 0
            fun def(o: JsonObject) = ((o["audioTrack"] as? JsonObject)?.get("audioIsDefault") as? JsonPrimitive)?.contentOrNull != "false"
            val ad = ((sd["adaptiveFormats"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }
                .filter { s(it, "url") != null && s(it, "mimeType")?.startsWith("audio/") == true && def(it) }
            val hi = ad.firstOrNull { i(it, "itag") == 251 } ?: ad.filter { s(it, "mimeType")?.contains("opus") == true }.maxByOrNull { i(it, "bitrate") }
            val lo = ad.firstOrNull { i(it, "itag") == 140 } ?: ad.filter { s(it, "mimeType")?.startsWith("audio/mp4") == true }.maxByOrNull { i(it, "bitrate") }
            if (hi == null && lo == null) continue
            val h = hi?.let { alive(s(it, "url")!!, c.ua) }
            val l = lo?.let { alive(s(it, "url")!!, c.ua) }
            if (h == null && l == null) continue
            val m = MusicSrc(h, l, c.ua)
            musicCache[videoId] = System.currentTimeMillis() to m
            ActLog.add("MUSIC", "$videoId · ${c.name} · ${if (h != null) "160" else "-"}/${if (l != null) "128" else "-"} kbps")
            return@withContext m
        }
        ActLog.add("MUSIC", "$videoId · no audio")
        null
    }

    data class Format(
        val itag: Int, val url: String, val mime: String, val codecs: String, val bitrate: Int,
        val width: Int, val height: Int, val fps: Int, val initRange: LongRange?,
        val indexRange: LongRange?, val contentLength: Long,
    )
    /** User agent the DASH pieces are fetched with (the client that answered). */
    @Volatile var musicUa: String = "Mozilla/5.0"
    data class MusicStreams(
        val audioHi: Format?, val audioLo: Format?, val videos: List<Format>,
        val ua: String, val durationMs: Long,
    )
    private val musicStreamsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, MusicStreams?>>()

    /** Audio and adaptive video formats for the same YouTube item, cached for three hours. */
    suspend fun musicStreams(videoId: String): MusicStreams? = withContext(Dispatchers.IO) {
        musicStreamsCache[videoId]?.let { (at, value) ->
            if (System.currentTimeMillis() - at < 3 * 3600_000L) return@withContext value
        }
        val (key, visitor) = watchConfig(videoId)
        val got = coroutineScope {
            clients.map { client -> async { client to runCatching { streamingData(client, videoId, key, visitor) }.getOrNull() } }.awaitAll()
        }
        for ((client, data) in got) {
            data ?: continue
            fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun num(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L
            fun rng(o: JsonObject, k: String): LongRange? {
                val r = o[k] as? JsonObject ?: return null
                val start = num(r, "start"); val end = num(r, "end")
                return if (end >= start && end > 0) start..end else null
            }
            fun fmt(o: JsonObject): Format? {
                val url = str(o, "url") ?: return null
                val mime = str(o, "mimeType").orEmpty()
                val codecs = Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.getOrNull(1)
                    ?: str(o, "codecs").orEmpty()
                return Format(num(o, "itag").toInt(), url, mime, codecs, num(o, "bitrate").toInt(),
                    num(o, "width").toInt(), num(o, "height").toInt(), num(o, "fps").toInt(),
                    rng(o, "initRange"), rng(o, "indexRange"), num(o, "contentLength"))
            }
            val adaptive = ((data["adaptiveFormats"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }
            val audio = adaptive.mapNotNull(::fmt).filter { it.mime.startsWith("audio/") }
            val hiRaw = audio.firstOrNull { it.itag == 251 } ?: audio.filter { it.codecs.contains("opus", true) }.maxByOrNull { it.bitrate }
            val loRaw = audio.firstOrNull { it.itag == 140 } ?: audio.filter { it.mime.startsWith("audio/mp4") }.maxByOrNull { it.bitrate }
            val videoAll = adaptive.mapNotNull(::fmt).filter { it.mime.startsWith("video/") && it.height > 0 }
            val avc = videoAll.filter { it.codecs.contains("avc1", true) }
            val chosen = (if (avc.isNotEmpty()) avc else videoAll.filter { it.codecs.contains("vp9", true) })
                .distinctBy { it.itag }.sortedBy { it.height }
            val hi = hiRaw?.let { raw -> alive(raw.url, client.ua)?.let { raw.copy(url = it) } }
            val lo = loRaw?.let { raw -> alive(raw.url, client.ua)?.let { raw.copy(url = it) } }
            val liveVideos = coroutineScope { chosen.map { raw -> async { alive(raw.url, client.ua)?.let { raw.copy(url = it) } } }.awaitAll().filterNotNull() }
            if (hi == null && lo == null && liveVideos.isEmpty()) continue
            val durationMs = adaptive.mapNotNull { (it["approxDurationMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }.maxOrNull() ?: 0L
            val out = MusicStreams(hi, lo, liveVideos, client.ua, durationMs)
            musicUa = client.ua
            musicStreamsCache[videoId] = System.currentTimeMillis() to out
            ActLog.add("MUSIC", videoId + " · adaptive video formats " + liveVideos.size)
            return@withContext out
        }
        musicStreamsCache[videoId] = System.currentTimeMillis() to null
        null
    }

    /**
     * One DASH manifest (in memory) with the song's audio and every picture size of its video, so the player can turn the
     * picture on / off and change its size at the same second without reloading (Song ↔ Video, portrait ↔ full screen).
     */
    suspend fun dashManifest(videoId: String, hiAudio: Boolean): String? {
        val m = musicStreams(videoId) ?: return null
        val audio = (if (hiAudio) m.audioHi ?: m.audioLo else m.audioLo ?: m.audioHi)?.takeIf { it.initRange != null && it.indexRange != null } ?: return null
        val videos = m.videos.filter { it.initRange != null && it.indexRange != null }
        fun esc(u: String) = u.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        fun rep(f: Format, video: Boolean) = buildString {
            append("<Representation id=\"${f.itag}\" codecs=\"${f.codecs}\" bandwidth=\"${f.bitrate.coerceAtLeast(1)}\"")
            if (video) append(" width=\"${f.width}\" height=\"${f.height}\" frameRate=\"${f.fps.coerceAtLeast(1)}\"") else append(" audioSamplingRate=\"48000\"")
            append("><BaseURL>").append(esc(f.url)).append("</BaseURL>")
            append("<SegmentBase indexRange=\"${f.indexRange!!.first}-${f.indexRange.last}\"><Initialization range=\"${f.initRange!!.first}-${f.initRange.last}\"/></SegmentBase></Representation>")
        }
        val secs = (m.durationMs.coerceAtLeast(1000)) / 1000.0
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\" minBufferTime=\"PT1.5S\" mediaPresentationDuration=\"PT${secs}S\"><Period>")
            append("<AdaptationSet id=\"0\" contentType=\"audio\" mimeType=\"${audio.mime.substringBefore(';')}\" subsegmentAlignment=\"true\">").append(rep(audio, false)).append("</AdaptationSet>")
            if (videos.isNotEmpty()) {
                append("<AdaptationSet id=\"1\" contentType=\"video\" mimeType=\"${videos.first().mime.substringBefore(';')}\" subsegmentAlignment=\"true\">")
                videos.forEach { append(rep(it, true)) }
                append("</AdaptationSet>")
            }
            append("</Period></MPD>")
        }
    }

    /** One client's playable choices, best first: video + default-language audio, one muxed file, HLS. */
    private fun ask(c: Client, videoId: String, key: String?, visitor: String?): List<Src> {
        val sd = streamingData(c, videoId, key, visitor) ?: return emptyList()
        val out = mutableListOf<Src>()
        fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        fun i(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.intOrNull ?: 0
        fun l(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L
        // The original soundtrack, not a dubbed one (formats without track info count as the original).
        fun isDefault(o: JsonObject) = ((o["audioTrack"] as? JsonObject)?.get("audioIsDefault") as? JsonPrimitive)?.contentOrNull != "false"
        val adaptive = ((sd["adaptiveFormats"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }.filter { s(it, "url") != null }
        val video = adaptive.filter { s(it, "mimeType")?.startsWith("video/mp4") == true && s(it, "mimeType")?.contains("avc1") == true && i(it, "height") in 1..maxHeight }
            .maxWithOrNull(compareBy({ i(it, "height") }, { l(it, "bitrate") }))
        fun track(o: JsonObject) = o["audioTrack"] as? JsonObject
        fun lang(o: JsonObject) = track(o)?.let { s(it, "id") }?.substringBefore('.')?.substringBefore('-')?.lowercase()
        // Your language first (a dubbed track), then the original soundtrack.
        val prefs = prefLangs
        fun prefScore(o: JsonObject): Int { val i = lang(o)?.let { prefs.indexOf(it) } ?: -1; return if (i < 0) 0 else 100 - i }
        val audios = adaptive.filter { s(it, "mimeType")?.startsWith("audio/mp4") == true }
        val audio = audios.maxWithOrNull(compareBy({ prefScore(it) }, { isDefault(it) }, { l(it, "bitrate") }))
        val tracks = audios.filter { track(it) != null }.groupBy { lang(it) ?: "" }.mapNotNull { (_, g) ->
            val best = g.maxByOrNull { l(it, "bitrate") } ?: return@mapNotNull null
            (track(best)?.let { s(it, "displayName") } ?: "Audio") to s(best, "url")!!
        }
        if (video != null && audio != null) out += Src(s(video, "url")!!, s(audio, "url")!!, false, c.ua, i(video, "height"),
            tracks = if (tracks.size > 1) tracks else emptyList(), audioName = track(audio)?.let { s(it, "displayName") })
        // One file with both (usually 360p), then the HLS playlist (iPhone / visionOS clients).
        val muxed = ((sd["formats"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }.filter { s(it, "url") != null }.maxByOrNull { i(it, "height") }
        s(sd, "hlsManifestUrl")?.let { out += Src(it, null, true, c.ua, 0) }
        if (muxed != null) out += Src(s(muxed, "url")!!, null, false, c.ua, i(muxed, "height"))
        return out
    }
}

/**
 * googlevideo links read in 10 MB pieces with YouTube's own "&range=start-end" (one long download gets throttled or
 * cut). Other links pass straight through.
 */
@OptIn(UnstableApi::class)
internal class YtChunked(private val up: androidx.media3.datasource.DataSource) : androidx.media3.datasource.DataSource {
    private val chunk = 10L * 1024 * 1024
    private var yt = false
    private var spec: androidx.media3.datasource.DataSpec? = null
    private var pos = 0L
    private var left = androidx.media3.common.C.LENGTH_UNSET.toLong()
    private var chunkEnd = 0L
    private var inChunk = 0L

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = up.addTransferListener(transferListener)

    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
        yt = dataSpec.uri.host.orEmpty().contains("googlevideo.com") && dataSpec.uri.getQueryParameter("range") == null
        if (!yt) return up.open(dataSpec)
        spec = dataSpec; pos = dataSpec.position
        val clen = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        left = when {
            dataSpec.length != androidx.media3.common.C.LENGTH_UNSET.toLong() -> dataSpec.length
            clen != null -> (clen - pos).coerceAtLeast(0)
            else -> androidx.media3.common.C.LENGTH_UNSET.toLong()
        }
        openChunk()
        return left
    }

    private fun openChunk() {
        val s = spec!!
        val unset = androidx.media3.common.C.LENGTH_UNSET.toLong()
        chunkEnd = if (left != unset) minOf(pos + chunk, pos + left) - 1 else pos + chunk - 1
        val uri = s.uri.buildUpon().appendQueryParameter("range", "$pos-$chunkEnd").build()
        inChunk = 0
        up.open(s.buildUpon().setUri(uri).setPosition(0).setLength(unset).build())
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!yt) return up.read(buffer, offset, length)
        val unset = androidx.media3.common.C.LENGTH_UNSET.toLong()
        if (left == 0L) return androidx.media3.common.C.RESULT_END_OF_INPUT
        var n = up.read(buffer, offset, length)
        if (n == androidx.media3.common.C.RESULT_END_OF_INPUT) {
            up.close()
            // A short piece means the file ended.
            if (inChunk < chunkEnd - pos + 1 || (left != unset && left <= 0)) return androidx.media3.common.C.RESULT_END_OF_INPUT
            pos += inChunk
            openChunk()
            n = up.read(buffer, offset, length)
            if (n == androidx.media3.common.C.RESULT_END_OF_INPUT) return n
        }
        inChunk += n
        if (left != unset) left -= n
        return n
    }

    override fun getUri() = up.uri
    override fun getResponseHeaders() = up.responseHeaders
    override fun close() { up.close() }
}

/**
 * Plays a [YtDirect.Src] without any controls. [crop] fills the box (sides or top cut), otherwise the whole picture
 * fits; [zoom] enlarges it further. Starts muted when [muted]; [onPlaying] at the first picture.
 */
@OptIn(UnstableApi::class)
@Composable
fun DirectTrailer(
    src: YtDirect.Src, muted: Boolean, modifier: Modifier = Modifier, crop: Boolean = true, zoom: Float = 1f,
    onPlaying: () -> Unit = {}, onEnded: () -> Unit = {}, onError: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val playing by rememberUpdatedState(onPlaying)
    val ended by rememberUpdatedState(onEnded)
    val failed by rememberUpdatedState(onError)
    val player = remember(src) {
        val source: MediaSource = YtDirect.source(src)
        // A small buffer: trailers start fast and leave memory for the app (and the movie, on TV).
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 15_000, 800, 1_500).build()
        ExoPlayer.Builder(ctx).setLoadControl(load).build().apply {
            setMediaSource(source); repeatMode = Player.REPEAT_MODE_OFF; volume = if (muted) 0f else 1f
            prepare(); playWhenReady = true
        }
    }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    // Invisible over the backdrop until the first picture, then a 420 ms fade in (never a black box).
    val shown = remember(src) { androidx.compose.animation.core.Animatable(0f) }
    var first by remember(src) { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(first) { if (first) shown.animateTo(1f, androidx.compose.animation.core.tween(420)) }
    val view = remember { arrayOfNulls<TextureView>(1) }
    fun fit(tv: TextureView, vs: VideoSize) {
        val w = tv.width.toFloat(); val h = tv.height.toFloat()
        if (w <= 0f || h <= 0f || vs.width <= 0 || vs.height <= 0) return
        val a = vs.width * vs.pixelWidthHeightRatio / vs.height
        val box = w / h
        val m = Matrix()
        if (crop) { if (a > box) m.setScale(a / box, 1f, w / 2, h / 2) else m.setScale(1f, box / a, w / 2, h / 2) }
        else { if (a > box) m.setScale(1f, box / a, w / 2, h / 2) else m.setScale(a / box, 1f, w / 2, h / 2) }
        tv.setTransform(m)
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() { first = true; playing() }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ended() }
            override fun onPlayerError(error: PlaybackException) { ActLog.add("TRAILER", "play error ${error.errorCodeName}"); failed() }
            override fun onVideoSizeChanged(videoSize: VideoSize) { view[0]?.let { fit(it, videoSize) } }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    AndroidView(
        factory = { c ->
            TextureView(c).also { tv ->
                view[0] = tv
                tv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> fit(v as TextureView, player.videoSize) }
                player.setVideoTextureView(tv)
            }
        },
        modifier = modifier.graphicsLayer { scaleX = zoom; scaleY = zoom; alpha = shown.value },
    )
}
