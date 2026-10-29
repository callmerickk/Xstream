package com.example.streamnest

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Live server (64-bit Xstream only): PencariMovie Server (MIT, github.com/aiskendi/pencarimovie-server), shipped unchanged
 * as an app library (libxlive.so — Android only runs programs that come with the app) and run on this phone at
 * 127.0.0.1. It answers like an addon: /stream/<type>/<id>.json for movies and episodes, /music/… for songs.
 *
 * It starts by itself a few seconds after the app opens (so it's warm before a title page asks), restarts if it stops,
 * and is reused if a copy is already running. Its links are local addresses the player opens directly; the title page's
 * instant-play (Prepare) saves their first minute like any other link.
 */
object LiveServer {
    const val NAME = "Live server"
    data class State(val status: String = "Off", val ready: Boolean = false, val port: Int = 0, val version: String = "", val note: String = "")

    val state = MutableStateFlow(State())
    /** Last lines the server printed (Settings › Live server › Log). */
    val log = MutableStateFlow<List<String>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var proc: Process? = null
    @Volatile private var app: Context? = null
    @Volatile private var wanted = false
    private var restarts = 0

    /** Local calls only: short timeouts, no DNS, no proxy. */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .proxy(java.net.Proxy.NO_PROXY).retryOnConnectionFailure(true).build()
    }

    private fun binary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libxlive.so")
    /** This build has the Live server and this phone can run it. */
    fun available(ctx: Context): Boolean = BuildConfig.HAS_LIVE && binary(ctx).exists()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("live_server", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = available(ctx) && prefs(ctx).getBoolean("on", true)
    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("on", on).apply()
        if (on) start(ctx) else stop()
    }
    val base: String get() = "http://127.0.0.1:${state.value.port}"

    private fun note(line: String) {
        val l = (log.value + line.take(400)).takeLast(300)
        log.value = l
    }

    /** Starts it (once) after [delayMs]; safe to call often. */
    fun start(ctx: Context, delayMs: Long = 0) {
        val c = ctx.applicationContext; app = c
        if (!enabled(c)) { state.value = State(if (available(c)) "Off" else "Not in this version"); return }
        wanted = true
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            lock.withLock { if (!state.value.ready) launchLocked(c) }
        }
    }

    /** Waits (up to [ms]) until the server answers; starts it when needed. */
    suspend fun awaitReady(ctx: Context, ms: Long = 12_000): Boolean {
        if (state.value.ready) return true
        if (!enabled(ctx)) return false
        start(ctx)
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) { if (state.value.ready) return true; delay(150) }
        return state.value.ready
    }

    fun stop() {
        wanted = false
        runCatching { proc?.destroy() }
        proc = null
        state.value = State("Off")
    }

    fun restart(ctx: Context) { stop(); restarts = 0; start(ctx) }

    private fun version(port: Int): String? = runCatching {
        http.newCall(Request.Builder().url("http://127.0.0.1:$port/api/version").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = Api.json.parseToJsonElement(r.body!!.string()).jsonObject
            (o["version"] as? JsonPrimitive)?.contentOrNull ?: (o["current_version"] as? JsonPrimitive)?.contentOrNull
        }
    }.getOrNull()

    private fun portFree(p: Int) = runCatching { java.net.ServerSocket(p, 1, java.net.InetAddress.getByName("127.0.0.1")).close(); true }.getOrDefault(false)

    private suspend fun launchLocked(ctx: Context) {
        // A copy already running (from before the app was closed, or this app's own): use it.
        val saved = prefs(ctx).getInt("port", 8088)
        for (p in listOf(saved, 8088).distinct()) version(p)?.let { v ->
            state.value = State("Ready", true, p, v); note("Using the Live server already running (port $p, $v)"); return
        }
        val port = (listOf(saved) + (8088..8099)).firstOrNull { portFree(it) } ?: run { state.value = State("No free port"); return }
        val root = File(ctx.filesDir, "live").apply { mkdirs() }
        val tmp = File(ctx.cacheDir, "live-tmp").apply { mkdirs() }
        state.value = State("Starting", false, port)
        val pb = ProcessBuilder(binary(ctx).absolutePath, "--port", "$port", "--host", "127.0.0.1", "--no-open",
            "--app-version", BuildConfig.VERSION_NAME).directory(root).redirectErrorStream(true)
        pb.environment().apply {
            put("PM_APP_ROOT", root.absolutePath); put("HOME", root.absolutePath); put("TMPDIR", tmp.absolutePath)
            put("PM_NO_TRAY", "1")
        }
        val p = runCatching { pb.start() }.getOrElse { e ->
            state.value = State("Could not start", note = e.message ?: ""); note("Could not start: ${e.message}"); return
        }
        proc = p
        prefs(ctx).edit().putInt("port", port).apply()
        Thread({
            runCatching { p.inputStream.bufferedReader().forEachLine { note(it) } }
        }, "live-log").apply { isDaemon = true }.start()
        Thread({
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (proc === p) {
                proc = null
                state.value = State("Stopped ($code)")
                note("The Live server stopped (code $code)")
                ActLog.add("LIVE", "server stopped, code $code")
                // Back by itself, with a growing pause, unless it was switched off.
                if (wanted && restarts < 5) { restarts++; start(ctx, 1_500L * restarts) }
            }
        }, "live-wait").apply { isDaemon = true }.start()
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until && proc === p) {
            version(port)?.let { v ->
                state.value = State("Ready", true, port, v); restarts = 0
                ActLog.add("LIVE", "ready on $port ($v)"); return
            }
            delay(200)
        }
        if (proc === p) state.value = State("Not answering", false, port)
    }

    // ───────────── movies & episodes ─────────────

    /** The server's own status card (bot connecting, nothing found …) of the last title asked, for Sources. */
    val lastNote = MutableStateFlow("")
    private val warmed = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Streams for a title (an IMDb id, "tt…:season:episode" for episodes). */
    suspend fun streams(ctx: Context, type: String, id: String): List<StreamItem> = withContext(Dispatchers.IO) {
        if (!awaitReady(ctx)) return@withContext emptyList()
        val streamId = if (type == "series" && !id.contains(':')) "$id:1:1" else id
        val t0 = System.currentTimeMillis()
        val body = runCatching {
            http.newBuilder().readTimeout(20, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url("$base/stream/$type/${enc(streamId)}.json").build()).execute()
                .use { if (it.isSuccessful) it.body!!.string() else null }
        }.getOrNull() ?: return@withContext emptyList()
        val raw = runCatching { (Api.json.parseToJsonElement(body).jsonObject["streams"] as? JsonArray).orEmpty() }.getOrDefault(emptyList())
        val out = ArrayList<StreamItem>()
        var noteText = ""
        raw.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val s = runCatching { Api.json.decodeFromJsonElement(StreamInfo.serializer(), o) }.getOrNull() ?: return@forEach
            // Cards with only a link to the server page (setting up, nothing found, sponsor) are notes, never streams.
            if (s.url.isNullOrBlank()) {
                val d = s.description.orEmpty()
                if (noteText.isEmpty() && (Regex("(?i)no streams|filtered out").containsMatchIn(s.name.orEmpty()) || Regex("(?i)bot|clock|token|provision").containsMatchIn(d)))
                    noteText = d.lines().filter { it.isNotBlank() }.take(2).joinToString(" ").replace(Regex("(?i)telegram ?"), "")
                return@forEach
            }
            // The server's own wording names where its files live; Xstream just says Live server.
            fun clean(t: String?) = t?.replace(Regex("(?i)\\btelegram\\b\\s*"), "")?.trim()
            out += StreamItem(NAME, s.copy(name = clean(s.name), title = clean(s.title), description = clean(s.description)))
        }
        lastNote.value = noteText
        ActLog.add("LIVE", "${out.size} links in ${System.currentTimeMillis() - t0} ms · $id" + if (noteText.isNotEmpty()) " · $noteText" else "")
        // The server finds a file's location on the first read: ask for 2 bytes of the best two now, so Play (and the
        // title page's instant start) doesn't wait for it.
        out.take(2).forEach { warm(it.stream.url!!) }
        out
    }

    /** Asks the server for the first 2 bytes of a link (it looks the file up and keeps it ready). */
    fun warm(url: String) {
        if (!url.startsWith("http://127.0.0.1") || !warmed.add(url)) return
        scope.launch {
            runCatching { http.newCall(Request.Builder().url(url).header("Range", "bytes=0-1").build()).execute().close() }
        }
    }

    /** Settings page of the server (bots, quality, catalogs) for the in-app browser. */
    fun dashboardUrl(): String = "$base/#settings"

    // ───────────── music ─────────────

    @Serializable
    data class Track(
        val id: String = "", val title: String = "", val artist: String = "", val album: String? = null,
        val durationMs: Long = 0, val format: String = "", val artworkURL: String? = null,
    )

    private fun tracksFrom(body: String): List<Track> = runCatching {
        val arr = Api.json.parseToJsonElement(body).jsonObject["tracks"] as? JsonArray ?: return emptyList()
        arr.mapNotNull { el -> (el as? JsonObject)?.let { o ->
            Track(
                id = (o["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                title = (o["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                artist = (o["artist"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                album = (o["album"] as? JsonPrimitive)?.contentOrNull,
                durationMs = (o["durationMs"] as? JsonPrimitive)?.longOrNull ?: 0,
                format = (o["format"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                artworkURL = (o["artworkURL"] as? JsonPrimitive)?.contentOrNull,
            )
        } }.filter { it.id.isNotBlank() && it.format.lowercase() !in VideoFormats }
    }.getOrDefault(emptyList())
    /** The music addon also lists video files; the music player shows songs only. */
    private val VideoFormats = setOf("mkv", "mp4", "avi", "webm", "ts", "m2ts", "mov", "wmv", "flv", "m4v", "3gp")

    private fun getText(url: String, readS: Long = 20): String? = runCatching {
        http.newBuilder().readTimeout(readS, TimeUnit.SECONDS).build().newCall(Request.Builder().url(url).build()).execute()
            .use { if (it.isSuccessful) it.body!!.string() else null }
    }.getOrNull()

    suspend fun latestTracks(ctx: Context): List<Track> = withContext(Dispatchers.IO) {
        if (!awaitReady(ctx)) emptyList() else getText("$base/music/catalog/latest.json")?.let(::tracksFrom).orEmpty()
    }

    suspend fun searchTracks(ctx: Context, q: String): List<Track> = withContext(Dispatchers.IO) {
        if (q.isBlank() || !awaitReady(ctx)) emptyList() else getText("$base/music/search?q=${enc(q.trim())}")?.let(::tracksFrom).orEmpty()
    }

    /** The playable address of a song (null when the server can't find it right now). */
    suspend fun trackUrl(ctx: Context, t: Track): String? = withContext(Dispatchers.IO) {
        if (!awaitReady(ctx)) return@withContext null
        repeat(3) { attempt ->
            val body = getText("$base/music/stream/${enc(t.id)}.json", 25)
            val url = body?.let { runCatching { (Api.json.parseToJsonElement(it).jsonObject["url"] as? JsonPrimitive)?.contentOrNull }.getOrNull() }
            if (!url.isNullOrBlank()) return@withContext url
            delay(1_500L * (attempt + 1))
        }
        null
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
