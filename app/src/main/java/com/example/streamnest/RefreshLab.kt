package com.example.streamnest

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Refresh Lab (Developer's Corner): how CNCVerse fills its link list over time, so the smart schedule
 * (Auto-play logic) can be set from measurements.
 *
 * Every title gets one refresh strategy (rotated over fresh titles, so each strategy sees a cold server). Asks go out at
 * the strategy's times, each with its own time limit, and may overlap. Every new link gets its one-byte check the
 * moment it appears; after the 60 s window each video link is opened in the real player; optionally the title is asked
 * again 5 and 30 minutes later (how long CNCVerse keeps it).
 *
 * The log is one JSON object per line (.jsonl) for the Refresh Lab Analyzer page, which turns it into a rule
 * ("Copy rule JSON") that Auto-play logic › Paste a rule reads.
 */
object RefreshLab {
    data class Strategy(val id: String, val polls: List<Pair<Double, Double>>)   // (at s, time limit s)

    val STRATEGIES = listOf(
        Strategy("dense2", (0..60 step 2).map { it.toDouble() to 4.0 }),
        Strategy("first20", listOf(0.0 to 20.0, 22.0 to 8.0, 30.0 to 8.0, 45.0 to 8.0, 60.0 to 8.0)),
        Strategy("first8", listOf(0.0 to 8.0, 10.0 to 8.0, 15.0 to 8.0, 25.0 to 8.0, 40.0 to 8.0, 60.0 to 8.0)),
        Strategy("kick3", listOf(0.0 to 3.0, 5.0 to 4.0, 10.0 to 4.0, 20.0 to 6.0, 40.0 to 6.0, 60.0 to 6.0)),
        Strategy("backoff", listOf(0.0, 1.0, 2.0, 4.0, 7.0, 11.0, 16.0, 24.0, 36.0, 60.0).map { it to 4.0 }),
    )
    private const val WINDOW_MS = 60_000L

    val running = MutableStateFlow(false)
    val step = MutableStateFlow("")
    val done = MutableStateFlow(0)
    val total = MutableStateFlow(0)
    val lastFile = MutableStateFlow<File?>(null)
    private var job: Job? = null

    fun stop() { job?.cancel(); job = null }

    fun start(vm: AppViewModel, ctx: Context, titles: Int, playTest: Boolean, cacheCheck: Boolean) {
        if (running.value || SourceTester.running.value) return
        job = vm.viewModelScope.launch {
            running.value = true; done.value = 0; total.value = titles; lastFile.value = null
            val f = newLogFile(ctx)
            lastFile.value = f
            try { run(vm, ctx, f, titles, playTest, cacheCheck) }
            finally {
                SourceTester.releaseTestSurface()
                SourceTester.player.value = null
                running.value = false
                step.value = if (done.value == 0) "Stopped" else "Finished · ${done.value} titles · log ready"
                lastFile.value = f
            }
        }
    }

    // ───── log ─────

    private val lock = Any()
    private fun write(f: File, o: JSONObject) = synchronized(lock) { f.appendText(o.toString() + "\n") }

    private fun newLogFile(ctx: Context): File {
        val d = File(ctx.filesDir, "refresh-lab").apply { mkdirs() }
        d.listFiles()?.filter { it.name.endsWith(".jsonl") }?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
        return File(d, "refresh-lab-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.jsonl")
    }

    /** The last five Refresh Lab logs. */
    fun pastLogs(ctx: Context): List<File> =
        File(ctx.filesDir, "refresh-lab").listFiles()?.filter { it.name.endsWith(".jsonl") }?.sortedByDescending { it.lastModified() }?.take(5).orEmpty()

    // ───── run ─────

    private suspend fun run(vm: AppViewModel, ctx: Context, f: File, count: Int, playTest: Boolean, cacheCheck: Boolean) = coroutineScope {
        val runId = System.currentTimeMillis().toString(36)
        write(f, JSONObject().put("ev", "run").put("runId", runId).put("started", System.currentTimeMillis())
            .put("app", vm.appVersion).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("network", if (isOnWifi(ctx)) "Wi-Fi" else "mobile data")
            .put("addon", vm.addons.value.filter { SlowAddons.isSlow(it) && vm.isAddonOn(it) }.joinToString(", ") { it.label() })
            .put("windowSec", WINDOW_MS / 1000)
            .put("strategies", JSONArray(STRATEGIES.map { s -> JSONObject().put("id", s.id).put("polls", JSONArray(s.polls.map { JSONArray(listOf(it.first, it.second)) })) })))

        val prefs = ctx.getSharedPreferences("refresh_lab", Context.MODE_PRIVATE)
        val used = prefs.getStringSet("used", emptySet()).orEmpty().toMutableSet()
        val order = STRATEGIES.shuffled()
        val warm = mutableListOf<Job>()
        var tested = 0
        var misses = 0
        while (tested < count && misses < count * 3 && isActive) {
            val series = tested % 2 == 1
            step.value = "Picking a ${if (series) "series" else "movie"} (${tested + 1}/$count)…"
            val t = runCatching { SourceTester.pickOne(vm, series, used) }.getOrNull()
            if (t == null) { misses++; continue }
            used += t.imdb
            prefs.edit().putStringSet("used", used.toSet()).apply()
            val type = t.type
            if (vm.slowStreamAddons(type, t.videoId).isEmpty()) {
                step.value = "CNCVerse isn't switched on — nothing to test"; return@coroutineScope
            }
            val strat = order[tested % order.size]
            step.value = "${t.name} · ${strat.id} (${tested + 1}/$count)"
            val links = testTitle(vm, f, runId, t, type, strat)
            if (playTest) playLinks(vm, ctx, f, runId, t, links)
            if (cacheCheck && links.isNotEmpty()) warm += launch { warmChecks(vm, f, runId, t, type) }
            tested++
            done.value = tested
        }
        if (warm.isNotEmpty()) { step.value = "Waiting for the cache checks (5 and 30 min)…"; warm.joinAll() }
        write(f, JSONObject().put("ev", "end").put("runId", runId).put("ended", System.currentTimeMillis()))
    }

    private data class Seen(val key: String, val item: StreamItem, val host: String, val q: String, val tSeen: Long)

    private val verdicts = ConcurrentHashMap<String, String>()

    private suspend fun testTitle(vm: AppViewModel, f: File, runId: String, t: TestTitle, type: String, strat: Strategy): List<Seen> = coroutineScope {
        val tk = "$runId:${t.videoId}"
        write(f, JSONObject().put("ev", "title").put("tk", tk).put("type", type).put("id", t.videoId).put("name", t.label).put("strategy", strat.id))
        val slow = vm.slowStreamAddons(type, t.videoId)
        val seen = ConcurrentHashMap<String, Seen>()
        val gate = Semaphore(3)
        val t0 = SystemClock.elapsedRealtime()
        fun now() = SystemClock.elapsedRealtime() - t0
        strat.polls.filter { it.first * 1000 <= WINDOW_MS }.mapIndexed { i, (at, limit) ->
            launch {
                val wait = (at * 1000).toLong() - now()
                if (wait > 0) delay(wait)
                val start = now()
                var status = 200
                val got = mutableListOf<StreamItem>()
                for (a in slow) {
                    val (code, body) = timedGet(vm.streamUrl(a, type, t.videoId), (limit * 1000).toLong())
                    if (body == null) { status = code; continue }
                    runCatching { Api.json.decodeFromString<StreamResponse>(body).streams }.getOrDefault(emptyList())
                        .filter { it.url?.startsWith("http") == true }
                        .forEach { got += StreamItem(a.label(), it).also { si -> StreamHeaders.remember(si) } }
                }
                val end = now()
                val fresh = JSONArray()
                got.forEach { s ->
                    val url = s.stream.url!!
                    val k = linkKey(url)
                    val item = Seen(k, s, labHost(url), Prepare.qualityName(s.resolution()), end)
                    if (seen.putIfAbsent(k, item) == null) {
                        fresh.put(JSONObject().put("k", k).put("host", item.host).put("q", item.q).put("label", (s.stream.name ?: "").replace("\n", " ").take(80)))
                        launch { gate.withPermit { probe(f, tk, item) } }
                    }
                }
                write(f, JSONObject().put("ev", "poll").put("tk", tk).put("i", i).put("plannedMs", (at * 1000).toLong())
                    .put("timeoutMs", (limit * 1000).toLong()).put("tStart", start).put("tEnd", end)
                    .put("status", if (got.isNotEmpty()) 200 else status).put("err", if (status == -1 && got.isEmpty()) "timeout" else JSONObject.NULL)
                    .put("count", got.size).put("new", fresh))
            }
        }.joinAll()
        seen.values.sortedBy { it.tSeen }
    }

    private suspend fun probe(f: File, tk: String, s: Seen) {
        val url = s.item.stream.url!!
        val pr = StreamCache.probe(url)
        val verdict = when {
            pr.status == -1 || pr.error?.contains("timeout", true) == true -> "timeout"
            pr.status in 200..299 && pr.type?.startsWith("text/html", true) == true -> "html"
            pr.ok -> "video"
            else -> "error"
        }
        verdicts[s.key] = verdict
        write(f, JSONObject().put("ev", "probe").put("tk", tk).put("k", s.key).put("host", s.host).put("q", s.q).put("tSeen", s.tSeen)
            .put("probeMs", pr.ttfbMs).put("http", pr.status).put("ctype", pr.type ?: "").put("size", if (pr.size > 0) pr.size else JSONObject.NULL)
            .put("seekable", pr.seekable).put("finalHost", pr.finalHost ?: "").put("verdict", verdict).put("err", pr.error ?: JSONObject.NULL))
    }

    private suspend fun playLinks(vm: AppViewModel, ctx: Context, f: File, runId: String, t: TestTitle, links: List<Seen>) {
        val tk = "$runId:${t.videoId}"
        for (s in links) {
            if (verdicts[s.key] != "video") continue
            step.value = "Playing ${s.q} · ${s.host} · ${t.name}"
            val (ms, err) = runCatching { SourceTester.firstPicture(ctx, vm, s.item.stream.url!!, s.item.stream.behaviorHints?.filename) }.getOrElse { null to it.javaClass.simpleName }
            write(f, JSONObject().put("ev", "play").put("tk", tk).put("k", s.key).put("host", s.host)
                .put("firstFrameMs", ms ?: JSONObject.NULL).put("err", err ?: JSONObject.NULL))
        }
    }

    private suspend fun warmChecks(vm: AppViewModel, f: File, runId: String, t: TestTitle, type: String) {
        val tk = "$runId:${t.videoId}"
        var elapsed = 0
        for (m in listOf(5, 30)) {
            delay((m - elapsed) * 60_000L); elapsed = m
            val t0 = SystemClock.elapsedRealtime()
            var n = 0
            for (a in vm.slowStreamAddons(type, t.videoId)) n += askSlowOnce(vm, a, type, t.videoId, 20_000).size
            write(f, JSONObject().put("ev", "warm").put("tk", tk).put("afterMin", m).put("respMs", SystemClock.elapsedRealtime() - t0).put("count", n))
        }
    }

    // ───── helpers ─────

    private val volatileParam = Regex("^(token|expires?|exp|sig|signature|x-amz-.*|hash|t|ts)$", RegexOption.IGNORE_CASE)

    /** Same file → same key even when the addon signs the link again (R2 tokens, X-Amz-…). */
    fun linkKey(url: String): String {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return url.hashCode().toString(16)
        val q = u.rawQuery?.split('&')?.filterNot { volatileParam.matches(it.substringBefore('=')) }?.sorted()?.joinToString("&")
        val base = "${u.host}${u.rawPath}${if (q.isNullOrEmpty()) "" else "?$q"}"
        return MessageDigest.getInstance("SHA-1").digest(base.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
    }

    /** Server name for the analyzer: cloudflarestorage.com / r2.dev / pixeldrain.dev, the full name for workers.dev. */
    fun labHost(url: String): String {
        val h = runCatching { android.net.Uri.parse(url).host }.getOrNull()?.lowercase() ?: return "?"
        return if (h.endsWith("workers.dev")) h else h.split('.').takeLast(2).joinToString(".")
    }
}
