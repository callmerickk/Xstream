package com.example.streamnest

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 0.9.3 (Feature.comboTest): Source Test › Combined.
 * For ~10 mixed titles every switched-on addon, CloudStream plugin and Nuvio plugin is asked at once; per source we
 * record when its first link came, when its first link answered as playable (one-byte check), its first picture, how
 * many links and how many files only it found. Then Quick Play strategies are replayed from that recording without
 * touching the network, and the best set per kind of title is suggested.
 */

@Serializable data class ComboSource(
    val name: String, val kind: String = "addon",
    val firstLinkMs: Long = -1, val firstPlayableMs: Long = -1, val firstPictureMs: Long = -1,
    val links: Int = 0, val unique: Int = 0, val answerBytes: Long = 0,
    /** When each one-byte check answered (ms from the start). */ val probeAt: List<Long> = emptyList(),
)
@Serializable data class ComboTitle(val label: String, val kind: String, val sources: List<ComboSource> = emptyList())
@Serializable data class ComboRecording(val at: Long = 0, val app: String = "", val net: String = "", val titles: List<ComboTitle> = emptyList())

/** One replayed start: time to the first playable link (null = never), requests, data, which source won. */
data class SimResult(val greenMs: Long?, val requests: Int, val bytes: Long, val first: String?)

object ComboTest {
    val running = MutableStateFlow(false)
    val step = MutableStateFlow("")
    val done = MutableStateFlow(0)
    val total = MutableStateFlow(0)
    val report = MutableStateFlow("")
    val logFile = MutableStateFlow<File?>(null)
    val suggested = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private var job: Job? = null
    private const val PROBE_BYTES = 1024L
    private const val GATHER_MS = 45_000L

    val kindNames = linkedMapOf("hindiMovie" to "Hindi movie", "movie" to "Hollywood movie", "series" to "English series", "hindiSeries" to "Hindi TV")

    /** The ~10 titles: (kind, series?, language, years). Old = 1995–2012, new = 2018 on. */
    private fun plan(): List<Triple<String, Boolean, Pair<String, IntRange?>>> {
        val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val old = 1995..2012; val new = 2018..now
        return listOf(
            Triple("hindiMovie", false, "hi" to new), Triple("hindiMovie", false, "hi" to old), Triple("hindiMovie", false, "hi" to null),
            Triple("movie", false, "en" to new), Triple("movie", false, "en" to old), Triple("movie", false, "en" to null),
            Triple("series", true, "en" to new), Triple("series", true, "en" to old),
            Triple("hindiSeries", true, "hi" to new), Triple("hindiSeries", true, "hi" to null),
        )
    }

    fun stop() { job?.cancel(); job = null }

    fun start(vm: AppViewModel, ctx: Context) {
        if (running.value || SourceTester.running.value || RefreshLab.running.value) return
        job = vm.viewModelScope.launch {
            running.value = true; report.value = ""; logFile.value = null; done.value = 0
            val titles = ArrayList<ComboTitle>()
            try {
                val p = plan(); total.value = p.size
                val seen = HashSet<String>()
                for ((kind, tv, lp) in p) {
                    if (!isActive) break
                    var t: TestTitle? = null
                    var tries = 0
                    while (t == null && tries++ < 4) { step.value = "Picking ${kindNames[kind]} (try $tries)…"; t = SourceTester.pickOne(vm, tv, seen, lp.first, lp.second) }
                    if (t == null) { done.value = done.value + 1; continue }
                    seen += t.imdb
                    step.value = "${done.value + 1}/${p.size} · asking every source · ${t.name}"
                    titles += recordTitle(vm, ctx, t, kind)
                    done.value = done.value + 1
                }
            } finally {
                val rec = ComboRecording(System.currentTimeMillis(), vm.appVersion, if (isOnWifi(ctx)) "Wi-Fi" else "mobile data", titles)
                if (titles.isNotEmpty()) save(ctx, rec)
                val txt = if (titles.isEmpty()) "No titles were recorded." else fullReport(vm, rec)
                report.value = txt
                logFile.value = if (titles.isNotEmpty()) writeReport(ctx, txt) else null
                running.value = false
                step.value = if (titles.isEmpty()) "Stopped" else "Finished · ${titles.size} titles"
            }
        }
    }

    private class Acc(val name: String, val kind: String) {
        var firstLink = -1L; var firstPlayable = -1L; var picture = -1L
        val links = LinkedHashMap<String, StreamItem>()
        val sizes = HashMap<String, Long>()
        val probeAt = ArrayList<Long>()
        var probes = 0; var bytes = 0L
        var playable: StreamItem? = null
    }

    fun kindOf(name: String) = when { name.startsWith("CloudStream · ") -> "cloudstream"; name.startsWith("Nuvio · ") -> "nuvio"; else -> "addon" }

    private suspend fun recordTitle(vm: AppViewModel, ctx: Context, t: TestTitle, kind: String): ComboTitle {
        val t0 = SystemClock.elapsedRealtime()
        val acc = java.util.concurrent.ConcurrentHashMap<String, Acc>()
        val sem = Semaphore(6)
        withTimeoutOrNull(GATHER_MS + 15_000) {
            coroutineScope {
                fun onList(l: List<StreamItem>) {
                    for (s in l) {
                        val url = s.stream.url ?: continue
                        val a = acc.getOrPut(s.addonName) { Acc(s.addonName, kindOf(s.addonName)) }
                        val probe = synchronized(a) {
                            if (url in a.links) null else {
                                a.links[url] = s
                                if (a.firstLink < 0) a.firstLink = SystemClock.elapsedRealtime() - t0
                                a.bytes += 150 + url.length + listOfNotNull(s.stream.name, s.stream.title, s.stream.description).sumOf { it.length }
                                (a.probes < 3 && a.firstPlayable < 0).also { if (it) a.probes++ }
                            }
                        } ?: continue
                        if (probe) launch {
                            sem.withPermit {
                                val pr = runCatching { StreamCache.probe(url) }.getOrNull()
                                val at = SystemClock.elapsedRealtime() - t0
                                synchronized(a) {
                                    a.probeAt += at
                                    if (pr != null && pr.ok && !pr.busy) {
                                        if (pr.size > 0) a.sizes[url] = pr.size
                                        if (a.firstPlayable < 0 || at < a.firstPlayable) { a.firstPlayable = at; a.playable = s }
                                    }
                                }
                            }
                        }
                    }
                }
                launch { withTimeoutOrNull(GATHER_MS) { runCatching { vm.addonStreamsFlow(t.type, t.videoId, null, null).first { (l, d) -> onList(vm.filtered(l).list); d } } } }
                if (!BuildConfig.IS_TV) launch { withTimeoutOrNull(GATHER_MS) { runCatching { PluginSources.modeStreams(vm, t.type, t.videoId, null, null, all = true).first { (l, d) -> onList(vm.filtered(l).list); d } } } }
            }
        }
        // First picture: the fastest playable link of each source (at most 6 sources), in the real player.
        acc.values.filter { it.playable != null }.sortedBy { it.firstPlayable }.take(6).forEach { a ->
            val s = a.playable ?: return@forEach
            step.value = "${done.value + 1}/${total.value} · first picture · ${a.name.removePrefix("CloudStream · ")} · ${t.name}"
            val (ms, _) = runCatching { SourceTester.firstPicture(ctx, vm, s.stream.url!!, s.fileName()) }.getOrDefault(null to "failed")
            if (ms != null) a.picture = a.firstPlayable + ms
        }
        SourceTester.releaseTestSurface()
        // Unique finds: a file no other source has (same size ±1 % and a similar name, or the same name when sizes are unknown).
        data class F(val src: String, val size: Long, val tokens: Set<String>)
        fun sizeOf(a: Acc, u: String, s: StreamItem): Long = a.sizes[u] ?: s.stream.behaviorHints?.videoSize ?: textSize(s)
        val files = acc.values.flatMap { a -> a.links.map { (u, s) -> F(a.name, sizeOf(a, u, s), tokens(s.fileName().ifBlank { s.stream.title.orEmpty() })) } }
        fun same(x: F, y: F): Boolean {
            val sim = jaccard(x.tokens, y.tokens)
            return if (x.size > 0 && y.size > 0) kotlin.math.abs(x.size - y.size) <= maxOf(x.size, y.size) / 100 && sim >= 0.4 else sim >= 0.8
        }
        val out = acc.values.map { a ->
            val mine = files.filter { it.src == a.name }
            val others = files.filter { it.src != a.name }
            val unique = mine.count { f -> others.none { same(f, it) } }
            ComboSource(a.name, a.kind, a.firstLink, a.firstPlayable, a.picture, a.links.size, unique, a.bytes, a.probeAt.sorted())
        }.sortedWith(compareBy({ if (it.firstPlayableMs < 0) Long.MAX_VALUE else it.firstPlayableMs }, { it.name }))
        ActLog.add("COMBO", "${t.name} · ${kindNames[kind]} · ${out.size} sources · first playable " +
            (out.firstOrNull { it.firstPlayableMs >= 0 }?.let { "${it.name} ${"%.1f".format(it.firstPlayableMs / 1000f)} s" } ?: "none"))
        return ComboTitle(t.label, kind, out)
    }

    private val sizeRx = Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*(gb|mb)\\b")
    private fun textSize(s: StreamItem): Long = sizeRx.find(listOfNotNull(s.stream.name, s.stream.title, s.stream.description).joinToString(" "))?.let { m ->
        val v = m.groupValues[1].toDoubleOrNull() ?: return@let -1L
        (v * if (m.groupValues[2].equals("gb", true)) 1_073_741_824.0 else 1_048_576.0).toLong()
    } ?: -1L
    private fun tokens(t: String): Set<String> = t.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.toSet()
    private fun jaccard(a: Set<String>, b: Set<String>): Double = if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size

    // ───── Recording on disk (for "What if?") ─────

    private fun dir(ctx: Context) = File(ctx.filesDir, "combo-tests").apply { mkdirs() }
    private fun save(ctx: Context, r: ComboRecording) { runCatching { File(dir(ctx), "last-recording.json").writeText(Api.json.encodeToString(ComboRecording.serializer(), r)) } }
    fun lastRecording(ctx: Context): ComboRecording? = runCatching { Api.json.decodeFromString<ComboRecording>(File(dir(ctx), "last-recording.json").readText()) }.getOrNull()
    fun pastReports(ctx: Context): List<File> = dir(ctx).listFiles()?.filter { it.name.endsWith(".txt") }?.sortedByDescending { it.lastModified() }?.take(3).orEmpty()
    private fun writeReport(ctx: Context, txt: String): File? = runCatching {
        dir(ctx).listFiles()?.filter { it.name.endsWith(".txt") }?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
        File(dir(ctx), "${Brand.NAME.lowercase()}-combined-test-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.txt").also { it.writeText(txt) }
    }.getOrNull()

    // ───── Replay (no network) ─────

    /** One start replayed: each source starts at its offset (null = never asked); stops at the first playable link. */
    fun sim(t: ComboTitle, start: (ComboSource) -> Long?, oneRequestFor: (ComboSource) -> Boolean = { false }): SimResult {
        val cands = t.sources.mapNotNull { s -> val o = start(s) ?: return@mapNotNull null; if (s.firstPlayableMs < 0) null else Triple(s, o, o + s.firstPlayableMs) }
        val win = cands.minByOrNull { it.third }
        val green = win?.third
        val asked = t.sources.filter { s -> val o = start(s); o != null && (green == null || o <= green) }
        val shared = asked.count { oneRequestFor(it) }
        val requests = (asked.size - shared + (if (shared > 0) 1 else 0)) + asked.sumOf { s -> val o = start(s)!!; s.probeAt.count { green == null || o + it <= green } }
        val bytes = asked.sumOf { it.answerBytes } + asked.sumOf { s -> val o = start(s)!!; s.probeAt.count { green == null || o + it <= green } } * PROBE_BYTES
        return SimResult(green, requests, bytes, win?.first?.name)
    }

    /** Sources best first over these titles: share of titles with a playable link, then median time to it. */
    fun ranking(titles: List<ComboTitle>): List<String> {
        val names = titles.flatMap { t -> t.sources.map { it.name } }.distinct()
        return names.map { n ->
            val fps = titles.mapNotNull { t -> t.sources.firstOrNull { it.name == n }?.firstPlayableMs?.takeIf { it >= 0 } }.sorted()
            Triple(n, fps.size.toDouble() / titles.size.coerceAtLeast(1), if (fps.isEmpty()) Long.MAX_VALUE else fps[fps.size / 2])
        }.sortedWith(compareBy({ -it.second }, { it.third })).map { it.first }
    }

    data class Strategy(val name: String, val per: (ComboTitle) -> SimResult)

    /** The Quick Play sources this phone would ask first today (rule, else the built-in choice). */
    fun quickNow(vm: AppViewModel, names: List<String>, rule: List<String> = Rules.quickSources()): Set<String> =
        if (rule.isNotEmpty()) names.filter { n -> rule.any { Rules.matches(n, it) } }.toSet()
        else names.filter { n -> vm.isQuickName(n) }.toSet()

    fun strategies(vm: AppViewModel, rec: ComboRecording): List<Strategy> {
        val all = ranking(rec.titles)
        val byKind = rec.titles.groupBy { it.kind }.mapValues { (_, l) -> ranking(l) }
        val names = rec.titles.flatMap { t -> t.sources.map { it.name } }.distinct()
        val q = quickNow(vm, names)
        val wait = Rules.ms(if (vm.pluginMode()) Rules.QUICK_WAIT_PLUGINS else Rules.QUICK_WAIT_ADDONS)
        val out = mutableListOf(
            Strategy("As set now (Quick Play first, rest after ${wait / 1000} s)") { t -> sim(t, { s -> if (s.name in q) 0L else wait }) },
            Strategy("All at once") { t -> sim(t, { 0L }) },
        )
        for (k in listOf(2, 3)) for (x in listOf(3_000L, 6_000L)) {
            val top = all.take(k).toSet()
            out += Strategy("Fastest $k first, rest after ${x / 1000} s (${top.joinToString { short(it) }})") { t -> sim(t, { s -> if (s.name in top) 0L else x }) }
        }
        out += Strategy("By title type (best 3 of each type first, rest after 4 s)") { t -> val top = byKind[t.kind].orEmpty().take(3).toSet(); sim(t, { s -> if (s.name in top) 0L else 4_000L }) }
        out += Strategy("Cloud first (addons in one request, plugins after 4 s)") { t -> sim(t, { s -> if (s.kind == "addon") 0L else 4_000L }, { it.kind == "addon" }) }
        return out
    }

    fun short(n: String) = n.removePrefix("CloudStream · ")

    fun strategyTable(vm: AppViewModel, rec: ComboRecording): String = buildString {
        val n = rec.titles.size
        strategies(vm, rec).forEach { st ->
            val r = rec.titles.map { st.per(it) }
            val g = r.mapNotNull { it.greenMs }.sorted()
            appendLine("• ${st.name}")
            appendLine("    green ${g.size}/$n titles · average ${if (g.isEmpty()) "—" else "%.1f s".format(g.average() / 1000)} · median ${if (g.isEmpty()) "—" else "%.1f s".format(g[g.size / 2] / 1000f)}" +
                " · requests ≈ ${"%.0f".format(r.map { it.requests }.average())} · data ≈ ${"%.0f".format(r.map { it.bytes }.average() / 1024)} KB per title")
        }
    }

    /** The best 3 sources per kind of title, from the recording. */
    fun suggest(rec: ComboRecording): Map<String, List<String>> = rec.titles.groupBy { it.kind }.mapValues { (_, l) -> ranking(l).filter { n -> l.any { t -> t.sources.any { it.name == n && it.firstPlayableMs >= 0 } } }.take(3) }

    private fun fullReport(vm: AppViewModel, rec: ComboRecording): String = buildString {
        appendLine("${Brand.NAME} Combined source test (addons + CloudStream + Nuvio, all at once)")
        appendLine("Started: ${SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US).format(Date(rec.at))} · App ${rec.app} · ${Build.MANUFACTURER} ${Build.MODEL} · ${rec.net}")
        appendLine("Per source: first link · first playable (one-byte check answered) · first picture (player) · links · files only it found")
        appendLine()
        rec.titles.forEach { t ->
            appendLine("== ${t.label} · ${kindNames[t.kind]} ==")
            t.sources.forEach { s ->
                fun f(ms: Long) = if (ms < 0) "—" else "%.1f s".format(ms / 1000f)
                appendLine("  ${short(s.name).take(34).padEnd(34)} link ${f(s.firstLinkMs).padStart(6)} · playable ${f(s.firstPlayableMs).padStart(6)} · picture ${f(s.firstPictureMs).padStart(6)} · ${s.links} links · ${s.unique} only here")
            }
            if (t.sources.isEmpty()) appendLine("  no source answered")
            appendLine()
        }
        appendLine("== Strategies replayed from this recording (no network): time to green, requests, data ==")
        append(strategyTable(vm, rec))
        appendLine()
        appendLine("== Suggested Quick Play per type of title ==")
        val sg = suggest(rec)
        suggested.value = sg
        sg.forEach { (k, l) -> appendLine("  ${kindNames[k]}: ${l.joinToString(" → ") { short(it) }.ifBlank { "nothing played" }}") }
        appendLine()
        appendLine("Note: the type sets are picked from the same titles they are replayed on (best case).")
    }.also { txt -> txt.lines().filter { it.startsWith("• ") || it.startsWith("    green") || it.startsWith("  ") && ":" in it && "→" in it }.take(30).forEach { ActLog.add("COMBO", it.trim()) } }

    /** Rebuild the suggestions / report from the kept recording (after the app restarted). */
    fun reload(vm: AppViewModel, ctx: Context) {
        if (report.value.isNotEmpty() || running.value) return
        val rec = lastRecording(ctx) ?: return
        suggested.value = suggest(rec)
        report.value = fullReport(vm, rec)
    }
}

/** The built-in "is this source quick?" for a source name alone (Combined test, What if). */
fun AppViewModel.isQuickName(name: String): Boolean = isQuickBuiltIn(StreamItem(name, StreamInfo(url = "https://x.invalid/")))

/** Developer's Corner › Source Test › Combined. */
@Composable
fun ComboTestTab(vm: AppViewModel) {
    val ctx = LocalContext.current
    val running by ComboTest.running.collectAsStateWithLifecycle()
    val step by ComboTest.step.collectAsStateWithLifecycle()
    val done by ComboTest.done.collectAsStateWithLifecycle()
    val total by ComboTest.total.collectAsStateWithLifecycle()
    val report by ComboTest.report.collectAsStateWithLifecycle()
    val sugg by ComboTest.suggested.collectAsStateWithLifecycle()
    val file by ComboTest.logFile.collectAsStateWithLifecycle()
    var msg by remember { mutableStateOf<String?>(null) }
    val view = LocalView.current
    DisposableEffect(running) { view.keepScreenOn = running; onDispose { view.keepScreenOn = false } }
    androidx.compose.runtime.LaunchedEffect(Unit) { ComboTest.reload(vm, ctx) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 80.dp)) {
        Text("Asks every switched-on addon, CloudStream plugin and Nuvio plugin at once for 10 titles (Hindi and Hollywood movies, English series, Hindi TV; old and new), records when each source answers and plays, then replays Quick Play strategies from the recording — no extra requests.",
            Modifier.padding(16.dp), fontSize = 13.sp, color = Muted)
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                Text(if (running) "Stop" else "Start combined test",
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(if (running) Color(0xFF3A2228) else BrandRed)
                        .clickable { if (running) ComboTest.stop() else ComboTest.start(vm, ctx) }.padding(vertical = 14.dp),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                if (step.isNotEmpty()) Text(step, Modifier.padding(top = 10.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (running && total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = Accent)
                Text("Takes about 10 minutes. Keep ${Brand.NAME} open (the screen stays on).", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = Muted)
            }
        }
        if (sugg.isNotEmpty() && Feature.devRules) {
            SectionLabel("Suggested Quick Play")
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    sugg.forEach { (k, l) ->
                        Text("${ComboTest.kindNames[k]}: ${l.joinToString(" → ") { ComboTest.short(it) }.ifBlank { "nothing played" }}", fontSize = 13.5.sp)
                        if (l.isNotEmpty()) Text("Use as my Quick Play (try on this phone)", Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x1FFFFFFF))
                            .clickable { Rules.tryLocal(mapOf(Rules.QUICK_SOURCES to l.joinToString("\n"))); msg = "Quick Play on this phone: ${l.joinToString(", ") { ComboTest.short(it) }} — see Developer's Corner › Rules" }
                            .padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        msg?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), fontSize = 13.sp, color = Color(0xFF7CD992)) }
        val past = remember(file, running) { ComboTest.pastReports(ctx) }
        if (past.isNotEmpty()) {
            SectionLabel("Reports")
            SettingsCard {
                Column(Modifier.padding(16.dp)) {
                    past.forEach { f ->
                        Text(SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.US).format(Date(f.lastModified())) + " · ${f.length() / 1024} KB", Modifier.padding(top = 6.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Save", Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x1FFFFFFF)).clickable { msg = SourceTester.saveToDownloads(ctx, f) ?: "Saved to Downloads: ${f.name}" }.padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 13.sp)
                            Text("Share", Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x1FFFFFFF)).clickable { SourceTester.shareLog(ctx, f) }.padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        if (report.isNotEmpty()) {
            SectionLabel("Report")
            SettingsCard { Text(report.take(20_000), Modifier.padding(14.dp), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFD5D8DF)) }
        }
        if (Feature.devAsk) DevAskBox(vm, "combo")
    }
}
