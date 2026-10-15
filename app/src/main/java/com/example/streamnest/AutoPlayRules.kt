package com.example.streamnest

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * How the phone asks CNCVerse (Vegamovies) for links and picks the upgrade file.
 *
 * "smart" (0.9.44+): short, overlapping requests on a timed schedule (CNCVerse keeps scraping after the app hangs up),
 * one more ask a few seconds after the first links (the list keeps growing: pixeldrain copies come later), servers tried
 * in [hostRank] order and [blockHosts] skipped.
 * "classic": exactly the 0.9.43 behaviour (one ask that waits up to 20 s, then every 2 s until links come).
 *
 * TV always uses classic until the same rule is brought to Xstream TV.
 */
@Serializable
data class CncRule(
    val mode: String = "smart",
    /** First request: dropped after this long (the addon keeps working on it). */
    val firstTimeoutMs: Long = 4_000,
    /** Asked again at these seconds after the first request (while no links have come). */
    val refreshAtSec: List<Int> = listOf(6, 12, 20, 30),
    /** After the last planned ask: every N seconds until [giveUpSec]. */
    val everySec: Int = 5,
    val giveUpSec: Int = 60,
    /** Time limit of every later request. */
    val requestTimeoutMs: Long = 8_000,
    /** One more ask this long after the first links came (the rest of the list). */
    val followUpSec: Int = 6,
    /** Servers for the upgrade file, best first (matched on the end of the server name). */
    val hostRank: List<String> = listOf("cloudflarestorage.com", "r2.dev", "pixeldrain.dev", "pixeldrain.com"),
    /** Servers never tried (ad / shortener pages instead of video). */
    val blockHosts: List<String> = listOf("gpdl2.rohitkiskk.workers.dev"),
    /** Where it came from: "Built-in 0.9.44", "Refresh Lab 15 Oct", "Classic (0.9.43)"… */
    val source: String = "Built-in",
    val at: Long = 0,
) {
    val smart get() = mode == "smart"

    /** (start ms, time limit ms) of every planned ask while no links have come. */
    fun plan(): List<Pair<Long, Long>> {
        val out = mutableListOf(0L to firstTimeoutMs)
        refreshAtSec.filter { it in 1 until giveUpSec }.sorted().distinct().forEach { out += it * 1000L to requestTimeoutMs }
        var t = (out.last().first / 1000 + everySec.coerceAtLeast(2)).toInt()
        while (t < giveUpSec) { out += t * 1000L to requestTimeoutMs; t += everySec.coerceAtLeast(2) }
        return out
    }

    fun summary(): String = if (!smart) "Classic: one ask (waits up to 20 s), then every 2 s until links come (up to 20 s)." else
        "First ask dropped after ${"%.0f".format(firstTimeoutMs / 1000f)} s · again at ${refreshAtSec.joinToString(", ") { "$it s" }}" +
            " · then every $everySec s until $giveUpSec s · one more ask $followUpSec s after the first links"
}

/** What the auto-play logic is made of, saved before every change so any earlier state can come back. */
@Serializable
data class AutoPlayState(
    val rule: CncRule,
    /** null = not recorded (left as it is on restore). */
    val addonOrder: List<String>? = null,
    val qsAddons: String? = null,
    val ranking: RankFile? = null,
    val deadHosts: List<String>? = null,
)

@Serializable
data class AutoPlayCheckpoint(val id: Long, val at: Long, val reason: String, val appVersion: String = "", val state: AutoPlayState)

@Serializable
private data class AutoPlayFile(val current: CncRule = CncRule(), val builtIn: Int = 0, val history: List<AutoPlayCheckpoint> = emptyList())

object AutoPlayRules {
    /** Raise when the built-in rule changes in a new version: the old one is saved first (see [init]). */
    private const val BUILT_IN_VERSION = 1
    private const val KEEP = 25

    val BUILT_IN = CncRule(source = "Built-in 0.9.44 (from Source Test 15 Oct)")
    val CLASSIC = CncRule(mode = "classic", source = "Classic (0.9.43)")

    val current = MutableStateFlow(BUILT_IN)
    val history = MutableStateFlow<List<AutoPlayCheckpoint>>(emptyList())
    private var file: File? = null

    /** Smart CNCVerse logic in use on this device (phone only for now). */
    val smart: Boolean get() = !BuildConfig.IS_TV && current.value.smart

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "autoplay-rules.json")
        file = f
        val saved = runCatching { Api.json.decodeFromString<AutoPlayFile>(f.readText()) }.getOrNull()
        if (saved == null) {
            // First start on 0.9.44: what the phone did before is kept, so one tap brings it back.
            history.value = listOf(AutoPlayCheckpoint(System.currentTimeMillis(), System.currentTimeMillis(), "Before the 0.9.44 update", "0.9.43", AutoPlayState(CLASSIC)))
            current.value = BUILT_IN.copy(at = System.currentTimeMillis())
            save(BUILT_IN_VERSION)
            return
        }
        current.value = saved.current
        history.value = saved.history
        if (saved.builtIn < BUILT_IN_VERSION) {
            // A newer built-in rule: the old one is saved first. A rule you chose yourself is kept.
            if (saved.current.source.startsWith("Built-in")) {
                push("Before update (built-in rule changed)", AutoPlayState(saved.current))
                current.value = BUILT_IN.copy(at = System.currentTimeMillis())
            }
            save(BUILT_IN_VERSION)
        }
    }

    private var builtInSaved = BUILT_IN_VERSION
    private fun save(builtIn: Int = builtInSaved) {
        builtInSaved = builtIn
        file?.let { f -> runCatching { f.writeText(Api.json.encodeToString(AutoPlayFile(current.value, builtIn, history.value))) } }
    }

    private fun push(reason: String, state: AutoPlayState) {
        val now = System.currentTimeMillis()
        val ver = Public.vm?.appVersion ?: ""
        history.value = (history.value + AutoPlayCheckpoint(now, now, reason, ver, state)).takeLast(KEEP)
        save()
    }

    /** Everything that makes up the auto-play logic right now. */
    fun capture(vm: AppViewModel?): AutoPlayState = AutoPlayState(
        rule = current.value,
        addonOrder = vm?.addons?.value?.map { it.label() },
        qsAddons = vm?.texts?.value?.get("qsAddons"),
        ranking = Ranker.file,
        deadHosts = Ranker.deadHostList(),
    )

    /** Saves the current logic before something changes it (Source Test, shared ranking, a new rule). */
    fun checkpoint(vm: AppViewModel?, reason: String) {
        val stamp = SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date())
        push("$reason · $stamp", capture(vm))
    }

    /** A new CNCVerse rule (Refresh Lab, Classic switch, reset): the old logic is saved first. */
    fun applyRule(vm: AppViewModel?, rule: CncRule, reason: String) {
        checkpoint(vm, reason)
        current.value = rule.copy(at = System.currentTimeMillis())
        save()
    }

    /** Brings back a saved logic. What is in use now is saved first ("Undone: …"), so this can be undone too. */
    fun restore(vm: AppViewModel?, cp: AutoPlayCheckpoint) {
        val now = capture(vm)
        val stamp = SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date())
        history.value = history.value.filter { it.id != cp.id }
        push("Undone: ${cp.reason.substringBefore(" · ")} · $stamp", now)
        val s = cp.state
        current.value = s.rule
        if (vm != null) {
            s.addonOrder?.let { vm.reorderAddonsByNames(it) }
            s.qsAddons?.let { vm.setText("qsAddons", it) }
        }
        s.ranking?.let { Ranker.restore(it, s.deadHosts) }
        save()
    }

    fun undoLast(vm: AppViewModel?): AutoPlayCheckpoint? = history.value.lastOrNull()?.also { restore(vm, it) }

    // ───── Server order for the upgrade file ─────

    private fun hostOf(url: String?): String = runCatching { android.net.Uri.parse(url).host }.getOrNull()?.lowercase().orEmpty()

    /** 0 = best server; servers not in the list come after the listed ones. Classic: no order. */
    fun hostRank(url: String?): Int {
        if (!smart) return 0
        val h = hostOf(url)
        val i = current.value.hostRank.indexOfFirst { h == it || h.endsWith(".$it") || h.endsWith(it) }
        return if (i < 0) current.value.hostRank.size else i
    }

    fun blocked(url: String?): Boolean {
        if (!smart) return false
        val h = hostOf(url)
        return current.value.blockHosts.any { h == it || h.endsWith(".$it") }
    }

    // ───── Rule from the Refresh Lab analyzer page ("Copy rule JSON") ─────

    /** Reads the analyzer's rule JSON. Null + reason when it isn't one. */
    fun parseLabRule(text: String): Pair<CncRule?, String?> = runCatching<Pair<CncRule?, String?>> {
        val o = Api.json.parseToJsonElement(text.trim()).jsonObject
        fun long(k: String) = o[k]?.jsonPrimitive?.longOrNull
        fun int(k: String) = o[k]?.jsonPrimitive?.intOrNull
        fun list(k: String) = o[k]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
        val refresh = list("refreshAtSec")?.mapNotNull { it.toDoubleOrNull()?.toInt() }
            ?: return@runCatching null to "That isn't a Refresh Lab rule (no refreshAtSec)."
        val base = BUILT_IN
        val day = SimpleDateFormat("dd MMM", Locale.US).format(Date())
        CncRule(
            mode = "smart",
            firstTimeoutMs = (long("firstRequestTimeoutMs") ?: base.firstTimeoutMs).coerceIn(1_000, 30_000),
            refreshAtSec = refresh.filter { it in 1..120 },
            everySec = (int("refreshEverySecUntilGiveUp") ?: base.everySec).coerceIn(2, 30),
            giveUpSec = (int("giveUpAtSec") ?: base.giveUpSec).coerceIn(10, 120),
            followUpSec = (int("keepRefreshingAfterFirstLinksSec") ?: base.followUpSec).coerceIn(0, 60),
            hostRank = list("hostRank")?.takeIf { it.isNotEmpty() } ?: base.hostRank,
            blockHosts = list("blockHosts") ?: base.blockHosts,
            source = "Refresh Lab $day" + ((o["basedOn"] as? JsonObject)?.get("titles")?.jsonPrimitive?.intOrNull?.let { " ($it titles)" } ?: ""),
        ) to null
    }.getOrElse { null to "Couldn't read it as JSON (${it.javaClass.simpleName})." }
}

/** GET with its own time limit; cancelling the caller also stops the request. */
suspend fun timedGet(url: String, timeoutMs: Long): Pair<Int, String?> = suspendCancellableCoroutine { cont ->
    val client = Api.http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
    val call = client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build())
    cont.invokeOnCancellation { runCatching { call.cancel() } }
    call.enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
            if (cont.isActive) cont.resumeWith(Result.success((if (e is java.io.InterruptedIOException) -1 else 0) to null))
        }
        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            val r = runCatching { response.use { it.code to (if (it.isSuccessful) it.body?.string() else null) } }.getOrElse { 0 to null }
            if (cont.isActive) cont.resumeWith(Result.success(r))
        }
    })
}

/** One CNCVerse ask with a time limit: the links, or empty (timeout / error). */
suspend fun askSlowOnce(vm: AppViewModel, a: Addon, type: String, id: String, timeoutMs: Long): List<StreamItem> {
    val (_, body) = timedGet(vm.streamUrl(a, type, id), timeoutMs)
    if (body == null) return emptyList()
    return runCatching {
        Api.json.decodeFromString<StreamResponse>(body).streams.filter { !it.url.isNullOrBlank() }
            .map { StreamItem(a.label(), it).also { si -> StreamHeaders.remember(si) } }
    }.getOrDefault(emptyList())
}

/**
 * The smart CNCVerse schedule: asks at the rule's times (requests may overlap), and once links come asks once more
 * [CncRule.followUpSec] later, then stops. [onNew] gets every new batch; [stop] ends it early (e.g. a Vegamovies file).
 */
suspend fun runCncSchedule(
    vm: AppViewModel, type: String, id: String, rule: CncRule = AutoPlayRules.current.value, maxMs: Long = Long.MAX_VALUE,
    onNew: (List<StreamItem>) -> Unit = {}, stop: (List<StreamItem>) -> Boolean = { false },
): List<StreamItem> = coroutineScope {
    val slow = vm.slowStreamAddons(type, id)
    if (slow.isEmpty()) return@coroutineScope emptyList()
    val got = LinkedHashMap<String, StreamItem>()
    val firstLinks = CompletableDeferred<Long>()
    val stopped = CompletableDeferred<Unit>()
    val t0 = SystemClock.elapsedRealtime()
    val giveUp = minOf(rule.giveUpSec * 1000L, maxMs)
    fun ask(timeout: Long): Job = launch(Dispatchers.IO) {
        for (a in slow) launch {
            val l = askSlowOnce(vm, a, type, id, timeout)
            val (fresh, all) = synchronized(got) { l.filter { got.putIfAbsent(it.stream.url!!, it) == null } to got.values.toList() }
            if (fresh.isNotEmpty()) {
                onNew(fresh)
                firstLinks.complete(SystemClock.elapsedRealtime() - t0)
                if (stop(all)) stopped.complete(Unit)
            }
        }
    }
    val planner = launch {
        for ((at, to) in rule.plan()) {
            if (at >= giveUp) break
            val wait = at - (SystemClock.elapsedRealtime() - t0)
            if (wait > 0) delay(wait)
            if (firstLinks.isCompleted) break
            ask(to)
        }
    }
    val first = withTimeoutOrNull(giveUp + rule.requestTimeoutMs) {
        kotlinx.coroutines.selects.select<Long?> {
            firstLinks.onAwait { it }
            stopped.onAwait { null }
        }
    }
    planner.cancel()
    if (first != null && !stopped.isCompleted && rule.followUpSec > 0) {
        // The rest of the list: one more ask a few seconds after the first links.
        val follow = launch { delay(rule.followUpSec * 1000L); ask(rule.requestTimeoutMs).join() }
        withTimeoutOrNull(rule.followUpSec * 1000L + rule.requestTimeoutMs + 500) {
            kotlinx.coroutines.selects.select<Unit> {
                stopped.onAwait { }
                follow.onJoin { }
            }
        }
    }
    coroutineContext.cancelChildren()
    synchronized(got) { got.values.toList() }
}
