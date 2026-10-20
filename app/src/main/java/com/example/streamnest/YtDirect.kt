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
    data class Src(val video: String, val audio: String?, val hls: Boolean, val ua: String, val height: Int)

    private class Client(val name: String, val num: Int, val version: String, val ua: String, val extra: Map<String, Any>)

    // The app clients YouTube serves plain addresses to (no page scripts to decode), tried in order.
    private val clients = listOf(
        Client("ANDROID_VR", 28, "1.62.27",
            "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L")),
        Client("IOS", 5, "20.10.4",
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.3.2.22D82")),
        Client("ANDROID", 3, "19.44.38",
            "com.google.android.youtube/19.44.38 (Linux; U; Android 11) gzip",
            mapOf("androidSdkVersion" to 30, "osName" to "Android", "osVersion" to "11")),
    )

    private val cache = object : LinkedHashMap<String, Pair<Long, Src?>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Src?>>?) = size > 40
    }
    private val JSONT = "application/json".toMediaType()

    /** Tallest picture to ask for: small on TV sticks (a second decoder next to the movie's), sharper on phones. */
    private val maxHeight: Int get() = if (BuildConfig.IS_TV) 480 else 720

    /** The trailer's addresses, or null when YouTube offers nothing playable. Remembered for 3 hours. */
    suspend fun resolve(videoId: String): Src? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[videoId] }?.let { (at, src) -> if (now - at < 3 * 3600_000L) return@withContext src }
        var out: Src? = null
        for (c in clients) {
            out = runCatching { ask(c, videoId) }.getOrNull()
            if (out != null) { ActLog.add("TRAILER", "$videoId · ${c.name} · ${if (out.hls) "HLS" else "${out.height}p"}"); break }
        }
        // YouTube said no to every app client: ask public Piped / Invidious mirrors (they hand out playable addresses).
        if (out == null) out = runCatching { mirrors(videoId) }.getOrNull()
        if (out == null) ActLog.add("TRAILER", "$videoId · no direct video (embedded player instead)")
        synchronized(cache) { cache[videoId] = now to out }
        out
    }

    /** The media source for a resolved trailer (video + audio merged, one muxed file, or HLS). */
    @OptIn(UnstableApi::class)
    fun source(src: Src): MediaSource {
        val ds = OkHttpDataSource.Factory(Api.http).setUserAgent(src.ua)
        fun prog(u: String): MediaSource = ProgressiveMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(u))
        return when {
            src.hls -> HlsMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(src.video))
            src.audio != null -> MergingMediaSource(prog(src.video), prog(src.audio))
            else -> prog(src.video)
        }
    }

    private val quick by lazy { Api.http.newBuilder().callTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build() }
    private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
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

    private fun ask(c: Client, videoId: String): Src? {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", c.name); put("clientVersion", c.version); put("hl", "en"); put("gl", "US")
                    c.extra.forEach { (k, v) -> if (v is Int) put(k, v) else put(k, v.toString()) }
                }
            }
            put("videoId", videoId); put("contentCheckOk", true); put("racyCheckOk", true)
        }
        val req = Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", c.ua).header("X-YouTube-Client-Name", c.num.toString()).header("X-YouTube-Client-Version", c.version)
            .header("Origin", "https://www.youtube.com")
            .post(body.toString().toRequestBody(JSONT)).build()
        val text = Api.http.newCall(req).execute().use { r -> if (!r.isSuccessful) return null; r.body?.string() } ?: return null
        val root = Api.json.parseToJsonElement(text).jsonObject
        val status = ((root["playabilityStatus"] as? JsonObject)?.get("status") as? JsonPrimitive)?.contentOrNull
        if (status != "OK") return null
        val sd = root["streamingData"] as? JsonObject ?: return null
        fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        fun i(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.intOrNull ?: 0
        fun l(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L
        val adaptive = ((sd["adaptiveFormats"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }.filter { s(it, "url") != null }
        val video = adaptive.filter { s(it, "mimeType")?.startsWith("video/mp4") == true && s(it, "mimeType")?.contains("avc1") == true && i(it, "height") in 1..maxHeight }
            .maxWithOrNull(compareBy({ i(it, "height") }, { l(it, "bitrate") }))
        val audio = adaptive.filter { s(it, "mimeType")?.startsWith("audio/mp4") == true }.maxByOrNull { l(it, "bitrate") }
        if (video != null && audio != null) return Src(s(video, "url")!!, s(audio, "url")!!, false, c.ua, i(video, "height"))
        // One file with both (usually 360p), then the HLS playlist (iPhone client).
        val muxed = ((sd["formats"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }.filter { s(it, "url") != null }.maxByOrNull { i(it, "height") }
        if (muxed != null) return Src(s(muxed, "url")!!, null, false, c.ua, i(muxed, "height"))
        s(sd, "hlsManifestUrl")?.let { return Src(it, null, true, c.ua, 0) }
        return null
    }
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
        val ds = OkHttpDataSource.Factory(Api.http).setUserAgent(src.ua)
        fun prog(u: String): MediaSource = ProgressiveMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(u))
        val source: MediaSource = when {
            src.hls -> HlsMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(src.video))
            src.audio != null -> MergingMediaSource(prog(src.video), prog(src.audio))
            else -> prog(src.video)
        }
        // A small buffer: trailers start fast and leave memory for the app (and the movie, on TV).
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 15_000, 800, 1_500).build()
        ExoPlayer.Builder(ctx).setLoadControl(load).build().apply {
            setMediaSource(source); repeatMode = Player.REPEAT_MODE_OFF; volume = if (muted) 0f else 1f
            prepare(); playWhenReady = true
        }
    }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
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
            override fun onRenderedFirstFrame() { playing() }
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
        modifier = modifier.graphicsLayer { scaleX = zoom; scaleY = zoom },
    )
}
