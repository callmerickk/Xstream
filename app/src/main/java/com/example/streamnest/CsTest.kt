package com.example.streamnest

import android.content.Context
import android.os.SystemClock
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.CsBridge
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One server's links for one title, grouped like CloudStream shows them: qualities, audio and subtitles together. */
data class CsGroup(val provider: String, val source: String, val type: String, val qualities: List<String>, val audios: List<String>, val subs: Int, val links: Int, val firstMs: Long, val playMs: Long? = null, val playErr: String? = null)
data class CsTitleResult(val title: String, val provider: String, val ok: Boolean, val summary: String, val groups: List<CsGroup>, val totalMs: Long)

/**
 * Source Test › CloudStream: for each chosen title and each switched-on plugin provider — search, match (name, year,
 * type, IMDb id when the plugin gives one), load, pick the episode, collect the links (with the time each arrived),
 * read every HLS playlist (qualities, audio, subtitles), and optionally play the best link to its first picture.
 * Every step is written to a log you can save, copy or share.
 */
object CsTester {
    val running = MutableStateFlow(false)
    val step = MutableStateFlow("")
    val results = MutableStateFlow<List<CsTitleResult>>(emptyList())
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
            CsBridge.sink = { w("    · $it") }
            try { run(ctx, vm, manual, playTest) } catch (e: Throwable) { w("STOPPED: ${e.javaClass.simpleName}: ${e.message}") }
            finally {
                CsBridge.sink = null
                logFile.value = writeLog(ctx)
                running.value = false; step.value = "Done"
                SourceTester.releaseTestSurface()
            }
        }
    }

    private suspend fun run(ctx: Context, vm: AppViewModel, manual: Meta?, playTest: Boolean) {
        w("Xstream ${BuildConfig.VERSION_NAME} · CloudStream plugin test · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
        step.value = "Reading repositories…"
        if (CsPlugins.catalog.value.isEmpty()) CsPlugins.refresh()?.let { w("Repositories: $it") }
        val entries = CsPlugins.enabledEntries()
        w("Plugins switched on: " + entries.joinToString { "${it.name} v${it.version}" }.ifEmpty { "none" })
        // Load every plugin, timing each.
        for (e in entries) {
            step.value = "Loading ${e.name}…"
            val t0 = SystemClock.elapsedRealtime()
            runCatching { CsPlugins.ensureLoaded(e) }
                .onSuccess { r -> w("LOAD ${e.name}: ${SystemClock.elapsedRealtime() - t0} ms · providers: ${r.apis.joinToString { "${it.name} (${it.mainUrl})" }} · link readers: ${r.extractors.joinToString { it.name }}") }
                .onFailure { t -> w("LOAD ${e.name}: FAILED ${t.javaClass.simpleName}: ${t.message}" + (t.cause?.let { " ← ${it.javaClass.simpleName}: ${it.message}" } ?: "")) }
        }
        val providers = CsPlugins.providers()
        if (providers.isEmpty()) { w("No provider loaded — nothing to test."); return }
        val titles = if (manual != null) listOf(TestTitle(manual.id.substringBefore(":"), manual.type, manual.name, manual.releaseInfo?.take(4), manual.id.substringBefore(":") + if (manual.type == "series") ":1:1" else "", "${manual.name} (picked)"))
        else buildList {
            for (tv in listOf(false, false, true, true)) { step.value = "Picking titles…"; SourceTester.pickOne(vm, tv, map { it.imdb }.toSet())?.let { add(it) } }
        }
        w("Titles: " + titles.joinToString { it.label })
        for (t in titles) for ((e, api) in providers) {
            step.value = "${api.name} · ${t.name}"
            val r = testOne(ctx, vm, api, e, t, playTest)
            results.value = results.value + r
        }
        w("")
        w("SUMMARY")
        results.value.groupBy { it.provider }.forEach { (p, rs) ->
            val ok = rs.count { it.ok }
            w("  $p: ${ok}/${rs.size} titles with links · avg ${rs.filter { it.ok }.map { it.totalMs }.average().takeIf { !it.isNaN() }?.toLong() ?: 0} ms to links")
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("\\(\\d{4}\\)|season\\s*\\d+|s\\d+|[^a-z0-9]"), "")
    private fun similarity(a: String, b: String): Double {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.contains(y) || y.contains(x)) return 0.85 * minOf(x.length, y.length) / maxOf(x.length, y.length) + 0.15
        val common = x.toSet().intersect(y.toSet()).size.toDouble()
        return common / maxOf(x.toSet().size, y.toSet().size) * 0.6
    }
    private fun yearOf(r: SearchResponse): Int? = (r as? MovieSearchResponse)?.year ?: (r as? TvSeriesSearchResponse)?.year

    private suspend fun testOne(ctx: Context, vm: AppViewModel, api: MainAPI, e: CsPluginEntry, t: TestTitle, playTest: Boolean): CsTitleResult {
        val series = t.type == "series"
        val season = t.videoId.split(":").getOrNull(1)?.toIntOrNull()
        val episode = t.videoId.split(":").getOrNull(2)?.toIntOrNull()
        w("")
        w("== ${api.name} (${e.name}) · ${t.label}${if (series) " · S${season}E${episode}" else ""}")
        val t0 = SystemClock.elapsedRealtime()
        fun ms() = SystemClock.elapsedRealtime() - t0
        fun fail(why: String) = CsTitleResult(t.name, api.name, false, why, emptyList(), ms()).also { w("  ✗ $why (${ms()} ms)") }
        // 1. Search
        val q = t.name.substringBefore(" (").trim()
        val found = try { withTimeoutOrNull(30_000) { api.search(q, 1)?.items ?: api.search(q) } }
            catch (x: NotImplementedError) { runCatching { withTimeoutOrNull(30_000) { api.search(q) } }.getOrNull() }
            catch (x: Throwable) { return fail("search error ${x.javaClass.simpleName}: ${x.message?.take(160)}") }
        if (found == null) return fail("search: no answer in 30 s")
        w("  search \"$q\": ${found.size} results in ${ms()} ms" + found.take(5).joinToString("") { "\n     · ${it.name} [${it.type}${yearOf(it)?.let { y -> ", $y" } ?: ""}] ${it.url.take(90)}" })
        val wantYear = t.year?.take(4)?.toIntOrNull()
        val scored = found.map { r ->
            var s = similarity(r.name, q)
            val y = yearOf(r)
            if (wantYear != null && y != null) s += if (kotlin.math.abs(y - wantYear) <= 1) 0.25 else -0.3
            val isSeries = r.type in setOf(TvType.TvSeries, TvType.Anime, TvType.AsianDrama, TvType.Cartoon, TvType.Documentary)
            if (r.type != null) s += if (isSeries == series) 0.1 else -0.25
            r to s
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull()?.takeIf { it.second >= 0.55 } ?: return fail("no matching result (best: ${scored.firstOrNull()?.let { "${it.first.name} ${"%.2f".format(it.second)}" } ?: "none"})")
        w("  match: ${best.first.name} (score ${"%.2f".format(best.second)})")
        // 2. Load
        val t1 = SystemClock.elapsedRealtime()
        val lr: LoadResponse = try { withTimeoutOrNull(30_000) { api.load(best.first.url) } } catch (x: Throwable) { return fail("load error ${x.javaClass.simpleName}: ${x.message?.take(160)}") }
            ?: return fail("load: nothing in 30 s")
        val imdb = lr.syncData["imdb"]
        w("  load: ${lr.javaClass.simpleName} \"${lr.name}\" ${lr.year ?: ""} in ${SystemClock.elapsedRealtime() - t1} ms" + (imdb?.let { " · IMDb $it ${if (it == t.imdb) "✓ same title" else "✗ different from ${t.imdb}"}" } ?: ""))
        val data: String = when (lr) {
            is MovieLoadResponse -> lr.dataUrl
            is TvSeriesLoadResponse -> {
                w("    episodes: ${lr.episodes.size} · seasons: ${lr.episodes.mapNotNull { it.season }.distinct().sorted().joinToString()}")
                (lr.episodes.firstOrNull { (it.season ?: 1) == (season ?: 1) && it.episode == (episode ?: 1) } ?: lr.episodes.firstOrNull { it.episode == (episode ?: 1) && it.season == null })?.data
                    ?: return fail("episode S${season}E${episode} not found")
            }
            is AnimeLoadResponse -> lr.episodes.values.flatten().firstOrNull { it.episode == (episode ?: 1) }?.data ?: return fail("episode not found")
            else -> return fail("unknown page type ${lr.javaClass.simpleName}")
        }
        // 3. Links
        val t2 = SystemClock.elapsedRealtime()
        val links = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, ExtractorLink>>()
        val subs = java.util.concurrent.CopyOnWriteArrayList<SubtitleFile>()
        val ok = try {
            withTimeoutOrNull(60_000) { api.loadLinks(data, false, { subs += it }, { l -> links += (SystemClock.elapsedRealtime() - t2) to l }) }
        } catch (x: Throwable) { w("  loadLinks error ${x.javaClass.simpleName}: ${x.message?.take(200)}"); false }
        w("  loadLinks: ${links.size} links, ${subs.size} subtitles in ${SystemClock.elapsedRealtime() - t2} ms" + (if (ok == null) " (stopped at 60 s)" else ""))
        links.forEach { (at, l) ->
            w("    +${at}ms ${l.source} · ${l.name} · ${com.lagradost.cloudstream3.utils.Qualities.getStringByInt(l.quality).ifEmpty { "?" }} · ${l.type} · ${hostOf(l.url)}" +
                (if (l.referer.isNotBlank()) " · referer ${hostOf(l.referer)}" else "") + (if (l.headers.isNotEmpty()) " · headers ${l.headers.keys.joinToString()}" else ""))
        }
        subs.take(8).forEach { w("    sub: ${it.lang} · ${hostOf(it.url)}") }
        if (links.isEmpty()) return fail("no links")
        // 4. Group per server; read HLS playlists.
        val groups = links.groupBy { it.second.source }.map { (src, ls) ->
            val first = ls.minOf { it.first }
            val hls = ls.firstOrNull { it.second.type == ExtractorLinkType.M3U8 }?.second
            val probe = hls?.let { probeHls(it) }
            val qs = (ls.map { com.lagradost.cloudstream3.utils.Qualities.getStringByInt(it.second.quality) }.filter { it.isNotBlank() } + probe?.first.orEmpty()).distinct()
            probe?.let { (v, a, s) -> w("    HLS ${src}: qualities ${v.joinToString().ifEmpty { "?" }} · audio ${a.joinToString().ifEmpty { "one" }} · subtitles ${s.joinToString().ifEmpty { "none" }}") }
            CsGroup(api.name, src, ls.map { it.second.type.name }.distinct().joinToString("/"), qs, probe?.second.orEmpty(), subs.size + (probe?.third?.size ?: 0), ls.size, first)
        }.toMutableList()
        // 5. Play the best link of each server to its first picture.
        if (playTest) groups.indices.forEach { i ->
            val g = groups[i]
            val best = links.map { it.second }.filter { it.source == g.source }.maxByOrNull { it.quality } ?: return@forEach
            step.value = "Playing ${g.source} · ${t.name}"
            val item = StreamItem(api.name, StreamInfo(url = best.url, name = "${api.name} · ${best.name}",
                behaviorHints = BehaviorHints(proxyHeaders = ProxyHeaders(best.getAllHeaders().takeIf { it.isNotEmpty() }))))
            StreamHeaders.remember(item)
            val (ff, err) = withContext(Dispatchers.Main) { SourceTester.firstPicture(ctx, vm, best.url, null) }
            w("    PLAY ${g.source}: " + (ff?.let { "first picture in $it ms" } ?: "failed: $err"))
            groups[i] = g.copy(playMs = ff, playErr = err)
        }
        val total = ms()
        w("  ✓ ${groups.size} server(s), first link after ${links.minOf { it.first }} ms of loadLinks · total ${total} ms")
        return CsTitleResult(t.name, api.name, true, "${links.size} links · ${groups.size} servers", groups, total)
    }

    private fun hostOf(u: String) = runCatching { java.net.URI(u).host?.removePrefix("www.") }.getOrNull() ?: u.take(30)

    /** Reads an HLS master playlist: (qualities, audio languages, subtitle languages). */
    private suspend fun probeHls(l: ExtractorLink): Triple<List<String>, List<String>, List<String>>? = withContext(Dispatchers.IO) {
        runCatching {
            val req = okhttp3.Request.Builder().url(l.url).apply { l.getAllHeaders().forEach { (k, v) -> header(k, v) }; if (l.headers.keys.none { it.equals("user-agent", true) }) header("user-agent", com.lagradost.cloudstream3.USER_AGENT) }.build()
            val text = Api.http.newCall(req).execute().use { it.body?.string().orEmpty() }
            if (!text.contains("#EXTM3U")) return@runCatching null
            val v = Regex("RESOLUTION=\\d+x(\\d+)").findAll(text).map { "${it.groupValues[1]}p" }.distinct().toList()
            fun media(type: String) = Regex("#EXT-X-MEDIA:[^\\n]*TYPE=$type[^\\n]*").findAll(text).map { m ->
                Regex("NAME=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1) ?: Regex("LANGUAGE=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1) ?: "?"
            }.distinct().toList()
            Triple(v, media("AUDIO"), media("SUBTITLES"))
        }.getOrNull()
    }

    private fun writeLog(ctx: Context): File? = runCatching {
        val d = File(ctx.filesDir, "source-tests").apply { mkdirs() }
        File(d, "xstream-cloudstream-test-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.txt").apply { writeText(synchronized(buf) { buf.toString() }) }
    }.getOrNull()
}

/** The CloudStream plugin list: repositories, each plugin with a switch and its state. */
@Composable
fun CsPluginList(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { CsPlugins.init(ctx); if (CsPlugins.catalog.value.isEmpty()) CsPlugins.refresh() }
    val repos by CsPlugins.repos.collectAsStateWithLifecycle()
    val cat by CsPlugins.catalog.collectAsStateWithLifecycle()
    val on by CsPlugins.enabled.collectAsStateWithLifecycle()
    val st by CsPlugins.status.collectAsStateWithLifecycle()
    var newRepo by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Repositories", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Refresh", Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x1FFFFFFF)).clickable { msg = "Refreshing…"; scope.launch { msg = CsPlugins.refresh() ?: "Up to date" } }
                .padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        msg?.let { Text(it, fontSize = 12.sp, color = Muted) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newRepo, { newRepo = it.trim() }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Add a repository link (repo.json)", fontSize = 13.sp) }, shape = RoundedCornerShape(12.dp))
            Text("Add", Modifier.padding(start = 8.dp).clip(RoundedCornerShape(12.dp)).background(Accent).clickable(enabled = newRepo.startsWith("http")) {
                CsPlugins.addRepo(newRepo); newRepo = ""; scope.launch { msg = CsPlugins.refresh() ?: "Added" }
            }.padding(horizontal = 14.dp, vertical = 12.dp), fontWeight = FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (showAll) "All plugins" else "Switched on and your picks", Modifier.weight(1f), fontSize = 12.sp, color = Muted)
            Text(if (showAll) "Show fewer" else "Show all ${cat.size}", Modifier.clickable { showAll = !showAll }.padding(6.dp), fontSize = 12.sp, color = Accent, fontWeight = FontWeight.Bold)
        }
        repos.forEach { r ->
            val list = cat.filter { it.repo == r.url }.filter { showAll || it.key in on }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF15161C)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(16.dp)).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name.ifBlank { r.url.substringAfter("githubusercontent.com/").substringBefore("/") }, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("${cat.count { it.repo == r.url }} plugins", fontSize = 11.sp, color = Muted)
                    Text("  Remove", Modifier.clickable { CsPlugins.removeRepo(r.url) }, fontSize = 11.sp, color = Color(0xFFFF8A80))
                }
                list.forEach { p ->
                    Row(Modifier.padding(top = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name + "  v${p.version}" + (p.language?.let { " · $it" } ?: ""), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(st[p.key] ?: (if (p.status == 0) "Marked down by its author" else p.tvTypes.joinToString(" · ")), fontSize = 11.sp, color = if (st[p.key]?.startsWith("Error") == true) Color(0xFFFF8A80) else Muted)
                        }
                        Switch(p.key in on, { v ->
                            CsPlugins.setEnabled(p.key, v)
                            if (v) scope.launch { runCatching { CsPlugins.ensureLoaded(p) }.onFailure { t -> CsPlugins.status.value = CsPlugins.status.value + (p.key to "Error: ${t.javaClass.simpleName}: ${t.message?.take(80)}") } }
                        })
                    }
                    // Plugins with their own settings screen.
                    val plug = CsPlugins.loaded[p.key]?.plugin as? com.lagradost.cloudstream3.plugins.Plugin
                    if (plug?.openSettings != null) Text("Settings", Modifier.padding(top = 2.dp).clickable { CsSettingsActivity.open(ctx, p.key) }, fontSize = 12.sp, color = Accent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Source Test › CloudStream tab. */
@Composable
fun CsTestTab(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by CsTester.running.collectAsStateWithLifecycle()
    val stepText by CsTester.step.collectAsStateWithLifecycle()
    val res by CsTester.results.collectAsStateWithLifecycle()
    val lf by CsTester.logFile.collectAsStateWithLifecycle()
    var play by remember { mutableStateOf(true) }
    var warm by remember { mutableStateOf(vm.store("csWarm") == "yes") }
    var manual by remember { mutableStateOf<Meta?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item { Text("CloudStream plugins run on Xstream's own CloudStream library. They're tested here only — Quick Play doesn't use them yet.", Modifier.padding(16.dp), fontSize = 12.5.sp, color = Muted) }
        item { CsPluginList(vm) }
        item {
            Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF15161C)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Test", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Play the best link of each server", Modifier.weight(1f), fontSize = 13.sp); Switch(play, { play = it }) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Keep plugins warm", fontSize = 13.sp); Text("Load them and wake their servers when Xstream opens", fontSize = 11.sp, color = Muted) }
                    Switch(warm, { warm = it; vm.storePut("csWarm", if (it) "yes" else "no") })
                }
                Text("One title (optional):", fontSize = 12.sp, color = Muted)
                if (manual != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(manual!!.name + " · " + manual!!.type, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Clear", Modifier.clickable { manual = null }.padding(6.dp), fontSize = 12.sp, color = Accent)
                } else TitleSearchBox(vm) { manual = it }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (running) "Stop" else if (manual != null) "Test this title" else "Test 2 movies + 2 series",
                        Modifier.clip(RoundedCornerShape(14.dp)).background(if (running) Color(0xFF3A3A42) else Accent)
                            .clickable { if (running) CsTester.stop() else CsTester.start(scope, ctx, vm, manual, play) }.padding(horizontal = 16.dp, vertical = 11.dp), fontWeight = FontWeight.Bold)
                    if (!running) Text("Warm now", Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x1FFFFFFF)).clickable {
                        msg = "Warming…"; scope.launch { val sb = StringBuilder(); CsPlugins.warmAll { sb.appendLine(it) }; msg = sb.toString().trim().ifEmpty { "Nothing switched on" } }
                    }.padding(horizontal = 16.dp, vertical = 11.dp), fontWeight = FontWeight.SemiBold)
                }
                if (running) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Accent); Text("  $stepText", fontSize = 12.sp) }
                msg?.let { Text(it, fontSize = 11.5.sp, color = Muted, fontFamily = FontFamily.Monospace) }
            }
        }
        if (res.isNotEmpty()) item { Text("Results", Modifier.padding(horizontal = 16.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        res.forEach { r ->
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF15161C)).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (r.ok) "✓" else "✗", color = if (r.ok) Color(0xFF3DD68C) else Color(0xFFFF8A80), fontWeight = FontWeight.Bold)
                        Text("  ${r.provider} · ${r.title}", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("${"%.1f".format(r.totalMs / 1000f)} s", fontSize = 11.sp, color = Muted)
                    }
                    Text(r.summary, fontSize = 11.5.sp, color = Muted)
                    r.groups.forEach { g ->
                        Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1E26)).padding(10.dp)) {
                            Text("${g.source} · ${g.type} · ${g.links} link${if (g.links == 1) "" else "s"}", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(g.qualities.joinToString(" · ").ifEmpty { null }, g.audios.takeIf { it.isNotEmpty() }?.let { "audio: " + it.joinToString() },
                                if (g.subs > 0) "${g.subs} subtitles" else null, "first link +${g.firstMs} ms",
                                g.playMs?.let { "▶ ${it} ms" } ?: g.playErr?.let { "▶ ✗ $it" }).joinToString("  ·  "), fontSize = 11.sp, color = Color(0xFFC9CED8))
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

/** Reads an HLS master playlist: (qualities, audio names, subtitle names). Null = not a playlist / unreachable. */
internal suspend fun probeHlsMaster(url: String, headers: Map<String, String>): Triple<List<String>, List<String>, List<String>>? = withContext(Dispatchers.IO) {
    runCatching {
        val req = okhttp3.Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (headers.keys.none { it.equals("user-agent", true) }) header("user-agent", com.lagradost.cloudstream3.USER_AGENT)
        }.build()
        val text = Api.http.newCall(req).execute().use { it.body?.string().orEmpty() }
        if (!text.contains("#EXTM3U")) return@runCatching null
        val v = Regex("RESOLUTION=\\d+x(\\d+)").findAll(text).map { "${it.groupValues[1]}p" }.distinct().toList()
        fun media(type: String) = Regex("#EXT-X-MEDIA:[^\\n]*TYPE=$type[^\\n]*").findAll(text).map { m ->
            Regex("NAME=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1) ?: Regex("LANGUAGE=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1) ?: "?"
        }.distinct().toList()
        Triple(v, media("AUDIO"), media("SUBTITLES"))
    }.getOrNull()
}
