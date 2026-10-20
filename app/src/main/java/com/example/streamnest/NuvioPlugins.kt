package com.example.streamnest

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// ───────────── Runtime: a hidden WebView (modern JavaScript) with our fetch / require bridge ─────────────

/**
 * Xstream's Nuvio plugin runtime. Plugins are JavaScript with `getStreams(tmdbId, mediaType, season, episode)`; they
 * run in a hidden WebView (full modern JavaScript: async / await, Promise, URL, TextEncoder…). fetch goes through
 * Xstream's network (no browser limits), and require() gives cheerio, crypto-js (bundled, MIT) and a small axios.
 */
object NuvioRuntime {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var ready = CompletableDeferred<Unit>()
    private val runs = ConcurrentHashMap<Int, CompletableDeferred<Result<String>>>()
    private val ids = AtomicInteger(0)
    @Volatile var sink: ((String) -> Unit)? = null

    private fun js(code: String) = main.post { runCatching { web?.evaluateJavascript(code, null) } }
    private fun q(s: String) = Api.json.encodeToString(s)

    private val bridge = object {
        @JavascriptInterface fun ready() { ready.complete(Unit) }
        @JavascriptInterface fun log(level: String, msg: String) { sink?.invoke("    · [$level] ${msg.take(500)}") }
        @JavascriptInterface fun done(id: Int, json: String) { runs.remove(id)?.complete(Result.success(json)) }
        @JavascriptInterface fun fail(id: Int, err: String) { runs.remove(id)?.complete(Result.failure(Exception(err))) }
        @JavascriptInterface fun fetch(id: Int, url: String, opts: String) { doFetch(id, url, opts) }
    }

    private fun doFetch(id: Int, url: String, opts: String) {
        val o = runCatching { Api.json.parseToJsonElement(opts).jsonObject }.getOrNull()
        val method = o?.get("method")?.jsonPrimitive?.contentOrNull ?: "GET"
        val headers = (o?.get("headers") as? JsonObject)?.mapValues { runCatching { it.value.jsonPrimitive.contentOrNull }.getOrNull().orEmpty() }.orEmpty()
        val body = (o?.get("body") as? JsonPrimitive)?.contentOrNull
        val follow = o?.get("redirect")?.jsonPrimitive?.contentOrNull != "manual"
        val t0 = SystemClock.elapsedRealtime()
        val req = runCatching {
            okhttp3.Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
                if (headers.keys.none { it.equals("user-agent", true) }) header("User-Agent", com.lagradost.cloudstream3.USER_AGENT)
                val ct = headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value
                val rb = if (method == "GET" || method == "HEAD") null else (body ?: "").toRequestBody(ct?.toMediaTypeOrNull())
                method(method, rb)
            }.build()
        }.getOrElse { e -> js("__xFetchDone($id, ${buildJsonObject { put("error", e.message ?: "bad request") }})"); return }
        val client = if (follow) Api.http else Api.http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                sink?.invoke("    · fetch ✗ $method ${url.take(100)} · ${e.javaClass.simpleName}: ${e.message}")
                js("__xFetchDone($id, ${buildJsonObject { put("error", e.message ?: "network error") }})")
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val text = runCatching { response.body?.string() }.getOrNull().orEmpty().let { if (it.length > 6_000_000) it.take(6_000_000) else it }
                sink?.invoke("    · fetch $method ${url.take(100)} → ${response.code} · ${text.length / 1024} KB · ${SystemClock.elapsedRealtime() - t0} ms")
                val out = buildJsonObject {
                    put("status", response.code); put("statusText", response.message); put("url", response.request.url.toString())
                    put("redirected", response.priorResponse != null)
                    putJsonObject("headers") { response.headers.names().forEach { n -> put(n, response.headers(n).joinToString(", ")) } }
                    put("body", text)
                }
                response.close()
                js("__xFetchDone($id, $out)")
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun ensure(ctx: Context) {
        if (web != null && ready.isCompleted) return
        withContext(Dispatchers.Main) {
            if (web == null) {
                ready = CompletableDeferred()
                val w = WebView(ctx.applicationContext)
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.webViewClient = WebViewClient()
                w.addJavascriptInterface(bridge, "XNative")
                val libs = runCatching { ctx.assets.open("nuvio/libs.js").bufferedReader().readText() }.getOrDefault("window.__xLibs={};")
                val boot = ctx.assets.open("nuvio/bootstrap.js").bufferedReader().readText()
                val html = "<!doctype html><html><head><meta charset=\"utf-8\"></head><body><script>$libs</script><script>$boot</script></body></html>"
                w.loadDataWithBaseURL("https://xstream.plugins/", html, "text/html", "utf-8", null)
                web = w
            }
        }
        withTimeoutOrNull(15_000) { ready.await() } ?: error("plugin runtime didn't start")
    }

    /** Runs a plugin's getStreams with [args]; the JSON array it returned. */
    suspend fun getStreams(ctx: Context, code: String, args: List<Any?>, timeoutMs: Long = 60_000): String {
        ensure(ctx)
        val id = ids.incrementAndGet()
        val d = CompletableDeferred<Result<String>>()
        runs[id] = d
        val argJson = JsonArray(args.map { a -> when (a) { null -> kotlinx.serialization.json.JsonNull; is Number -> JsonPrimitive(a); is Boolean -> JsonPrimitive(a); else -> JsonPrimitive(a.toString()) } })
        js("__xRun($id, ${q(code)}, $argJson)")
        val r = withTimeoutOrNull(timeoutMs) { d.await() } ?: run { runs.remove(id); error("no answer in ${timeoutMs / 1000} s") }
        return r.getOrThrow()
    }

    fun release() { main.post { runCatching { web?.destroy() }; web = null } }
}

// ───────────── Plugins: repositories (manifest.json) and their scrapers ─────────────

@Serializable data class NuvioScraper(
    val repo: String, val id: String, val name: String, val file: String, val version: String = "", val types: List<String> = emptyList(),
    val description: String? = null, val languages: List<String> = emptyList(), val logo: String? = null,
) { val key: String get() = "$repo|$id" }

object NuvioPlugins {
    /** No repository by default: add the Nuvio repositories you use (manifest.json links). */
    val defaultRepos = emptyList<String>()
    val repos = MutableStateFlow<List<String>>(emptyList())
    val repoNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val catalog = MutableStateFlow<List<NuvioScraper>>(emptyList())
    val enabled = MutableStateFlow<Set<String>>(emptySet())
    private var prefs: android.content.SharedPreferences? = null
    private lateinit var appCtx: Context

    fun init(ctx: Context) {
        if (prefs != null) return
        appCtx = ctx.applicationContext
        val p = ctx.getSharedPreferences("nuvio_plugins", Context.MODE_PRIVATE); prefs = p
        repos.value = p.getString("repos", null)?.split("\n")?.filter { it.isNotBlank() } ?: defaultRepos
        catalog.value = runCatching { Api.json.decodeFromString<List<NuvioScraper>>(p.getString("catalog", null) ?: "") }.getOrDefault(emptyList())
        enabled.value = p.getStringSet("enabled", null)?.toSet() ?: emptySet()
        repoNames.value = runCatching { Api.json.decodeFromString<Map<String, String>>(p.getString("names", null) ?: "") }.getOrDefault(emptyMap())
    }
    private fun save() {
        prefs?.edit()?.putString("repos", repos.value.joinToString("\n"))?.putString("catalog", Api.json.encodeToString(catalog.value))
            ?.putStringSet("enabled", enabled.value)?.putString("names", Api.json.encodeToString(repoNames.value))?.apply()
    }
    fun addRepo(u: String) { val x = u.trim(); if (x.startsWith("http") && x !in repos.value) { repos.value = repos.value + x; save() } }
    fun removeRepo(u: String) { repos.value = repos.value - u; catalog.value = catalog.value.filter { it.repo != u }; save() }
    fun setEnabled(k: String, on: Boolean) { enabled.value = if (on) enabled.value + k else enabled.value - k; save() }

    private fun manifestUrl(u: String) = if (u.endsWith(".json")) u else u.trimEnd('/') + "/manifest.json"

    suspend fun refresh(): String? = withContext(Dispatchers.IO) {
        val out = ArrayList<NuvioScraper>(); val errs = ArrayList<String>(); val names = HashMap<String, String>()
        for (r in repos.value) {
            val mu = manifestUrl(r)
            // A manifest is either { name, scrapers: [...] } or just the list of scrapers.
            val el = runCatching { Api.json.parseToJsonElement(Api.get(mu)) }.getOrNull()
            if (el == null) { errs += "can't read ${r.substringAfter("//").take(60)}"; continue }
            val o = el as? JsonObject
            o?.get("name")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.let { names[r] = it }
            val base = mu.substringBeforeLast('/') + "/"
            ((el as? JsonArray) ?: o?.get("scrapers") as? JsonArray ?: o?.get("providers") as? JsonArray)?.mapNotNull { it as? JsonObject }?.forEach { s ->
                fun str(k: String) = runCatching { s[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
                fun list(k: String) = (s[k] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
                val id = str("id") ?: str("name") ?: return@forEach
                val file = str("filename") ?: str("file") ?: return@forEach
                out += NuvioScraper(r, id, str("name") ?: id, if (file.startsWith("http")) file else base + file.trimStart('/'), str("version") ?: "",
                    list("supportedTypes"), str("description"), list("contentLanguage"), str("logo"))
            }
        }
        withContext(Dispatchers.Main) { catalog.value = out; repoNames.value = repoNames.value + names; save() }
        errs.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** The plugin's code (downloaded once per version). */
    suspend fun code(s: NuvioScraper): String = withContext(Dispatchers.IO) {
        val f = File(appCtx.filesDir, "nuvio/" + Integer.toHexString(s.repo.hashCode()) + "/" + s.id.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-" + s.version + ".js")
        if (f.exists() && f.length() > 0) return@withContext f.readText()
        val text = Api.get(s.file)
        f.parentFile?.mkdirs(); f.writeText(text)
        text
    }

    fun enabledScrapers() = catalog.value.filter { it.key in enabled.value }
}

// ───────────── Source Test › Nuvio ─────────────

data class NuvioResult(val title: String, val plugin: String, val ok: Boolean, val summary: String, val groups: List<CsGroup>, val totalMs: Long)

object NuvioTester {
    val running = MutableStateFlow(false)
    val step = MutableStateFlow("")
    val results = MutableStateFlow<List<NuvioResult>>(emptyList())
    val logFile = MutableStateFlow<File?>(null)
    private var job: Job? = null
    private val testScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    private val buf = StringBuilder()
    private fun w(s: String) { synchronized(buf) { buf.append(SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())).append("  ").append(s).append('\n') } }

    fun stop() { job?.cancel(); job = null; running.value = false; step.value = "Stopped" }

    fun start(scope: CoroutineScope, ctx: Context, vm: AppViewModel, manual: Meta?, playTest: Boolean) {
        if (running.value) return
        running.value = true; results.value = emptyList(); synchronized(buf) { buf.setLength(0) }
        // Own scope, off the main thread: plugins parse pages here, and the test keeps going if you leave the screen.
        job = testScope.launch {
            NuvioRuntime.sink = { w(it) }
            try { run(ctx, vm, manual, playTest) } catch (e: Throwable) { w("STOPPED: ${e.javaClass.simpleName}: ${e.message}") }
            finally {
                NuvioRuntime.sink = null
                logFile.value = runCatching {
                    val d = File(ctx.filesDir, "source-tests").apply { mkdirs() }
                    File(d, "xstream-nuvio-test-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.txt").apply { writeText(synchronized(buf) { buf.toString() }) }
                }.getOrNull()
                running.value = false; step.value = "Done"
                SourceTester.releaseTestSurface()
            }
        }
    }

    private suspend fun tmdbIdFor(vm: AppViewModel, imdb: String, series: Boolean): String? {
        val key = vm.tmdbKey().ifEmpty { return null }
        return runCatching {
            val o = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/find/$imdb?api_key=$key&external_source=imdb_id")).jsonObject
            ((o[if (series) "tv_results" else "movie_results"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private suspend fun run(ctx: Context, vm: AppViewModel, manual: Meta?, playTest: Boolean) {
        w("Xstream ${BuildConfig.VERSION_NAME} · Nuvio plugin test · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
        if (NuvioPlugins.catalog.value.isEmpty()) NuvioPlugins.refresh()?.let { w("Repositories: $it") }
        val list = NuvioPlugins.enabledScrapers()
        w("Plugins switched on: " + list.joinToString { "${it.name} ${it.version}" }.ifEmpty { "none" })
        if (list.isEmpty()) return
        if (vm.tmdbKey().isEmpty()) { w("No TMDB key — Nuvio plugins need TMDB ids (set it in Developer's Corner › Defaults for everyone)."); return }
        val titles = if (manual != null) listOf(TestTitle(manual.id.substringBefore(":"), manual.type, manual.name, manual.releaseInfo?.take(4), manual.id.substringBefore(":") + if (manual.type == "series") ":1:1" else "", "${manual.name} (picked)"))
        else buildList { for (tv in listOf(false, false, true, true)) { step.value = "Picking titles…"; SourceTester.pickOne(vm, tv, map { it.imdb }.toSet())?.let { add(it) } } }
        w("Titles: " + titles.joinToString { it.label })
        step.value = "Starting the plugin runtime…"
        val tr = SystemClock.elapsedRealtime()
        runCatching { NuvioRuntime.getStreams(ctx, "async function getStreams(){ return [] }", emptyList(), 15_000) }
            .onSuccess { w("Runtime ready in ${SystemClock.elapsedRealtime() - tr} ms (libraries: ${if (runCatching { ctx.assets.open("nuvio/libs.js").close() }.isSuccess) "cheerio, crypto-js" else "missing"})") }
            .onFailure { w("Runtime failed: ${it.message}"); return }
        for (t in titles) {
            val series = t.type == "series"
            val season = t.videoId.split(":").getOrNull(1)?.toIntOrNull()
            val episode = t.videoId.split(":").getOrNull(2)?.toIntOrNull()
            val tmdb = tmdbIdFor(vm, t.imdb, series)
            w("")
            w("== ${t.label} · TMDB ${tmdb ?: "?"}${if (series) " · S${season}E${episode}" else ""}")
            if (tmdb == null) { w("  ✗ no TMDB id"); continue }
            for (s in list) {
                if (series && s.types.isNotEmpty() && "tv" !in s.types && "series" !in s.types) continue
                if (!series && s.types.isNotEmpty() && "movie" !in s.types) continue
                step.value = "${s.name} · ${t.name}"
                w("-- ${s.name} ${s.version}")
                val t0 = SystemClock.elapsedRealtime()
                val codeR = runCatching { NuvioPlugins.code(s) }
                val code = codeR.getOrNull()
                if (code == null) { w("  ✗ download: ${codeR.exceptionOrNull()?.message}"); results.value = results.value + NuvioResult(t.name, s.name, false, "download failed", emptyList(), 0); continue }
                val out = runCatching { NuvioRuntime.getStreams(ctx, code, listOf(tmdb, if (series) "tv" else "movie", season, episode)) }
                val ms = SystemClock.elapsedRealtime() - t0
                val arr = out.getOrNull()?.let { runCatching { Api.json.parseToJsonElement(it) as JsonArray }.getOrNull() }
                if (arr == null) {
                    w("  ✗ ${out.exceptionOrNull()?.message?.take(600) ?: "bad answer"} (${ms} ms)")
                    results.value = results.value + NuvioResult(t.name, s.name, false, out.exceptionOrNull()?.message?.lineSequence()?.firstOrNull()?.take(120) ?: "bad answer", emptyList(), ms); continue
                }
                val streams = arr.mapNotNull { it as? JsonObject }
                w("  ${streams.size} streams in $ms ms")
                data class S(val name: String, val title: String, val url: String, val quality: String, val headers: Map<String, String>)
                val parsed = streams.mapNotNull { o ->
                    fun str(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    val url = str("url") ?: return@mapNotNull null
                    val h = (o["headers"] as? JsonObject)?.mapValues { runCatching { it.value.jsonPrimitive.contentOrNull }.getOrNull().orEmpty() }.orEmpty()
                    S(str("name") ?: s.name, str("title") ?: "", url, str("quality") ?: "", h)
                }
                parsed.forEach { x -> w("    ${x.name} · ${x.quality.ifEmpty { "?" }} · ${runCatching { java.net.URI(x.url).host }.getOrNull()} · ${x.title.replace("\n", " ").take(80)}" + (if (x.headers.isNotEmpty()) " · headers ${x.headers.keys.joinToString()}" else "")) }
                val groups = parsed.groupBy { it.name }.map { (n, xs) ->
                    val hls = xs.firstOrNull { it.url.contains(".m3u8") }
                    val pr = hls?.let { probeHlsMaster(it.url, it.headers) }
                    pr?.let { (v, a, sb) -> w("    HLS $n: qualities ${v.joinToString().ifEmpty { "?" }} · audio ${a.joinToString().ifEmpty { "one" }} · subtitles ${sb.joinToString().ifEmpty { "none" }}") }
                    var g = CsGroup(s.name, n, if (hls != null) "M3U8" else "VIDEO", (xs.map { it.quality }.filter { it.isNotBlank() } + pr?.first.orEmpty()).distinct(), pr?.second.orEmpty(), pr?.third?.size ?: 0, xs.size, ms)
                    if (playTest) {
                        val best = xs.maxByOrNull { Regex("(\\d{3,4})").find(it.quality)?.value?.toIntOrNull() ?: 0 } ?: xs.first()
                        step.value = "Playing $n · ${t.name}"
                        StreamHeaders.remember(StreamItem(s.name, StreamInfo(url = best.url, name = n, behaviorHints = BehaviorHints(proxyHeaders = ProxyHeaders(best.headers.takeIf { it.isNotEmpty() })))))
                        val (ff, err) = withContext(Dispatchers.Main) { SourceTester.firstPicture(ctx, vm, best.url, null) }
                        w("    PLAY $n: " + (ff?.let { "first picture in $it ms" } ?: "failed: $err"))
                        g = g.copy(playMs = ff, playErr = err)
                    }
                    g
                }
                results.value = results.value + NuvioResult(t.name, s.name, parsed.isNotEmpty(), "${parsed.size} streams · ${groups.size} servers", groups, ms)
            }
        }
        w("")
        w("SUMMARY")
        results.value.groupBy { it.plugin }.forEach { (p, rs) -> w("  $p: ${rs.count { it.ok }}/${rs.size} titles with streams") }
    }
}

@Composable
fun NuvioPluginList(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { NuvioPlugins.init(ctx); if (NuvioPlugins.catalog.value.isEmpty()) NuvioPlugins.refresh() }
    val repos by NuvioPlugins.repos.collectAsStateWithLifecycle()
    val names by NuvioPlugins.repoNames.collectAsStateWithLifecycle()
    val cat by NuvioPlugins.catalog.collectAsStateWithLifecycle()
    val on by NuvioPlugins.enabled.collectAsStateWithLifecycle()
    var newRepo by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Nuvio repositories", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Refresh", Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x1FFFFFFF)).clickable { msg = "Refreshing…"; scope.launch { msg = NuvioPlugins.refresh() ?: "Up to date" } }
                .padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        msg?.let { Text(it, fontSize = 12.sp, color = Muted) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newRepo, { newRepo = it.trim() }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Add a repository link (manifest.json)", fontSize = 13.sp) }, shape = RoundedCornerShape(12.dp))
            Text("Add", Modifier.padding(start = 8.dp).clip(RoundedCornerShape(12.dp)).background(Accent).clickable(enabled = newRepo.startsWith("http")) {
                NuvioPlugins.addRepo(newRepo); newRepo = ""; scope.launch { msg = NuvioPlugins.refresh() ?: "Added" }
            }.padding(horizontal = 14.dp, vertical = 12.dp), fontWeight = FontWeight.Bold)
        }
        if (repos.isEmpty()) Text("No Nuvio repository yet. Paste a repository's manifest.json link above.", fontSize = 12.5.sp, color = Muted)
        repos.forEach { r ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF15161C)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(16.dp)).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(names[r] ?: r.substringAfter("githubusercontent.com/").substringBefore("/"), Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("${cat.count { it.repo == r }} plugins", fontSize = 11.sp, color = Muted)
                    Text("  Remove", Modifier.clickable { NuvioPlugins.removeRepo(r) }, fontSize = 11.sp, color = Color(0xFFFF8A80))
                }
                cat.filter { it.repo == r }.forEach { s ->
                    Row(Modifier.padding(top = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name + (if (s.version.isNotBlank()) "  v${s.version}" else ""), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                            Text((s.types + s.languages).joinToString(" · ").ifEmpty { s.description ?: "" }, fontSize = 11.sp, color = Muted, maxLines = 1)
                        }
                        Switch(s.key in on, { NuvioPlugins.setEnabled(s.key, it) })
                    }
                }
            }
        }
    }
}

@Composable
fun NuvioTestTab(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by NuvioTester.running.collectAsStateWithLifecycle()
    val stepText by NuvioTester.step.collectAsStateWithLifecycle()
    val res by NuvioTester.results.collectAsStateWithLifecycle()
    val lf by NuvioTester.logFile.collectAsStateWithLifecycle()
    var play by remember { mutableStateOf(true) }
    var manual by remember { mutableStateOf<Meta?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item { Text("Nuvio plugins run in Xstream's own JavaScript runtime (fetch, cheerio, crypto-js, axios). Tested here only — Quick Play doesn't use them yet.", Modifier.padding(16.dp), fontSize = 12.5.sp, color = Muted) }
        item { NuvioPluginList(vm) }
        item {
            Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF15161C)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Test", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Play the best link of each server", Modifier.weight(1f), fontSize = 13.sp); Switch(play, { play = it }) }
                Text("One title (optional):", fontSize = 12.sp, color = Muted)
                if (manual != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(manual!!.name + " · " + manual!!.type, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Clear", Modifier.clickable { manual = null }.padding(6.dp), fontSize = 12.sp, color = Accent)
                } else TitleSearchBox(vm) { manual = it }
                Text(if (running) "Stop" else if (manual != null) "Test this title" else "Test 2 movies + 2 series",
                    Modifier.clip(RoundedCornerShape(14.dp)).background(if (running) Color(0xFF3A3A42) else Accent)
                        .clickable { if (running) NuvioTester.stop() else NuvioTester.start(scope, ctx, vm, manual, play) }.padding(horizontal = 16.dp, vertical = 11.dp), fontWeight = FontWeight.Bold)
                if (running) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Accent); Text("  $stepText", fontSize = 12.sp) }
                msg?.let { Text(it, fontSize = 11.5.sp, color = Muted) }
            }
        }
        res.forEach { r ->
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF15161C)).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (r.ok) "✓" else "✗", color = if (r.ok) Color(0xFF3DD68C) else Color(0xFFFF8A80), fontWeight = FontWeight.Bold)
                        Text("  ${r.plugin} · ${r.title}", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("${"%.1f".format(r.totalMs / 1000f)} s", fontSize = 11.sp, color = Muted)
                    }
                    Text(r.summary, fontSize = 11.5.sp, color = Muted)
                    r.groups.forEach { g ->
                        Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1E26)).padding(10.dp)) {
                            Text("${g.source} · ${g.type} · ${g.links} link${if (g.links == 1) "" else "s"}", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(g.qualities.joinToString(" · ").ifEmpty { null }, g.audios.takeIf { it.isNotEmpty() }?.let { "audio: " + it.joinToString() },
                                if (g.subs > 0) "${g.subs} subtitles" else null, g.playMs?.let { "▶ ${it} ms" } ?: g.playErr?.let { "▶ ✗ $it" }).joinToString("  ·  "),
                                fontSize = 11.sp, color = Color(0xFFC9CED8))
                        }
                    }
                }
            }
        }
        lf?.let { f ->
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Save" to { msg = SourceTester.saveToDownloads(ctx, f) ?: "Saved to Downloads: ${f.name}" },
                        "Copy" to { runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("log", f.readText().take(900_000))) }; msg = "Log copied" },
                        "Share" to { SourceTester.shareLog(ctx, f) }).forEach { (n, a) ->
                        Text(n, Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x1FFFFFFF)).clickable { a() }.padding(horizontal = 14.dp, vertical = 9.dp), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
