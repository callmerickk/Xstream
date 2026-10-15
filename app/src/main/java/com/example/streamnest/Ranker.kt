package com.example.streamnest

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One provider group in one quality group and language, as measured by the Source Test. */
@Serializable
data class RankEntry(
    val key: String, val addon: String = "", val label: String = "", val host: String = "",
    val bucket: String = "", val lang: String = "", val tests: Int = 0, val ok: Int = 0, val medianMs: Long = 0,
    val seekable: Boolean = true, val score: Double = 0.0,
    /** Never played in any test and always answered "not a video / not found / bad request": hidden from Sources. */
    val dead: Boolean = false,
)

@Serializable data class RankFile(val at: Long = 0, val author: String = "", val entries: List<RankEntry> = emptyList())

/** A provider group that failed twice in normal use (shown in Developer's Corner, re-tested by the next Source Test). */
@Serializable data class FailReport(val key: String, val label: String = "", val count: Int = 0, val reason: String = "", val at: Long = 0, val device: String = "")

/**
 * Which provider groups play fastest and most reliably, from the Source Test (shared with every Xstream user through the
 * cloud), plus what this phone learns while you watch: a group that fails twice goes "under observation" (tried last).
 */
object Ranker {
    @Volatile var file = RankFile()
        private set
    private val scores = ConcurrentHashMap<String, Double>()      // key|bucket|lang -> score
    private val anyScores = ConcurrentHashMap<String, Double>()   // key -> best score in any bucket
    private val unseekable = ConcurrentHashMap.newKeySet<String>()
    private val deadGroups = ConcurrentHashMap.newKeySet<String>()
    /** Links that answered with a web page / 400 / 404 in this session (never tried again). */
    private val deadUrls = ConcurrentHashMap.newKeySet<String>()
    private val fails = ConcurrentHashMap<String, Int>()
    private val deadHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val okHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val hostStrikes = ConcurrentHashMap<String, Int>()
    val observed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var prefs: android.content.SharedPreferences? = null
    private var dir: File? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences("ranker", Context.MODE_PRIVATE)
        dir = ctx.filesDir
        observed += prefs?.getStringSet("observed", emptySet()).orEmpty()
        deadHosts += prefs?.getStringSet("deadHosts", emptySet()).orEmpty()
        runCatching { use(Api.json.decodeFromString<RankFile>(File(ctx.filesDir, "ranking.json").readText())) }
    }

    private fun use(f: RankFile) {
        file = f
        scores.clear(); anyScores.clear(); unseekable.clear(); deadGroups.clear()
        f.entries.forEach { e ->
            scores["${e.key}|${e.bucket}|${e.lang}"] = e.score
            anyScores[e.key] = maxOf(anyScores[e.key] ?: Double.NEGATIVE_INFINITY, e.score)
            if (!e.seekable) unseekable += e.key
        }
        // A group is dead only when it never played in any quality / language and every test called it dead.
        f.entries.groupBy { it.key }.forEach { (k, l) -> if (l.all { it.dead && it.ok == 0 }) deadGroups += k }
        groupTotals.clear()
        f.entries.groupBy { it.key }.forEach { (k, l) -> groupTotals[k] = l.sumOf { it.tests } to l.sumOf { it.ok } }
    }

    /** Uses a new ranking (from a test or the cloud) and keeps it on the phone. A new test also clears "under observation". */
    fun apply(f: RankFile, fromTest: Boolean) {
        use(f)
        dir?.let { d -> runCatching { File(d, "ranking.json").writeText(Api.json.encodeToString(f)) } }
        if (fromTest) {
            val tested = f.entries.map { it.key }.toSet()
            observed.removeAll(tested); tested.forEach { fails.remove(it) }
            prefs?.edit()?.putStringSet("observed", observed.toSet())?.apply()
        }
    }

    /** Brings back a saved ranking and dead-server list (Developer's Corner › Auto-play logic › Restore). */
    fun restore(f: RankFile, hosts: List<String>?) {
        apply(f, fromTest = false)
        if (hosts != null) {
            deadHosts.clear(); deadHosts += hosts; hostStrikes.clear()
            prefs?.edit()?.putStringSet("deadHosts", deadHosts.toSet())?.apply()
        }
    }

    /** Dead as a whole: never played in any quality / language and every test called it dead. */
    fun isKeyDead(key: String): Boolean = key in deadGroups

    /** Forget every test result on this phone (start the ranking from scratch). */
    fun reset() {
        AutoPlayRules.checkpoint(Public.vm, "Before ranking reset")
        deadHosts.clear(); okHosts.clear(); hostStrikes.clear()
        prefs?.edit()?.remove("deadHosts")?.apply()
        apply(RankFile(), fromTest = true)
        dir?.let { d -> runCatching { File(d, "ranking.json").delete() } }
    }

    // ───── Groups ─────

    private val noise = Regex(
        "\\b(2160p|1440p|1080p|720p|480p|360p|4k|uhd|fhd|hd|sd|hdr10\\+?|hdr|dv|dovi|sdr|x26[45]|h\\.?26[45]|hevc|avc|10bit|8bit|web-?dl|webrip|web|bluray|brrip|remux|hindi|english|dual|multi|audio|org|esub|esubs|aac|ddp?5\\.?1|atmos)\\b",
    )

    /** "addon|provider label|server" — the same for every title the provider serves. */
    fun groupKey(s: StreamItem): String {
        val host = runCatching { Uri.parse(s.stream.url.orEmpty()).host }.getOrNull().orEmpty().split('.').takeLast(2).joinToString(".")
        val base = (s.stream.behaviorHints?.bingeGroup?.takeIf { it.isNotBlank() } ?: s.stream.name ?: "").lowercase()
        val mb = s.isMovieBox()
        val label = noise.replace(base, " ").replace(Regex("[^a-z0-9]+"), " ").trim()
            .split(" ").filter { it.isNotBlank() && it.length > 1 && !it.all(Char::isDigit) && !(mb && it in dubWords) }.take(4).joinToString(" ")
        return "${s.addonName}|$label|$host"
    }

    /** Language words removed from MovieBox group names: all its dubs (French, Hindi, Original…) are one server. */
    private val dubWords: Set<String> = QsLanguages.keys + setOf("original", "orig", "org", "dub", "dubbed", "audio", "fre", "fra", "hin", "eng", "spa", "ger", "ita", "por", "rus", "jpn", "kor", "chi", "ara", "tur", "tam", "tel", "mal", "kan")

    fun labelOf(key: String): String = key.split('|').filter { it.isNotBlank() }.joinToString(" · ")

    /** 4k / fhd / mhd / low — the player's four quality groups. */
    fun bucket(res: Int): String = when { res >= 1440 -> "4k"; res >= 1080 -> "fhd"; res >= 720 -> "mhd"; else -> "low" }
    fun tier(res: Int): Int = when { res >= 1440 -> 4; res >= 1080 -> 3; res >= 720 -> 2; else -> 1 }

    fun lang(s: StreamItem): String { val l = s.languages(); return when { "hindi" in l -> "hindi"; "english" in l -> "english"; else -> "other" } }

    /** Reliability score: higher = more reliable and faster. Unknown = 0; under observation = far below everything. */
    fun score(s: StreamItem): Double {
        val k = groupKey(s)
        if (k in observed) return -1000.0
        val sc = scores["$k|${bucket(s.effectiveRes())}|${lang(s)}"] ?: anyScores[k] ?: 0.0
        // Older tests judged streaming playlists (DASH / HLS) "not seekable" (they are): undo that penalty.
        return if (k in unseekable && s.isPlaylist()) sc + 60 else sc
    }

    fun knownUnseekable(s: StreamItem): Boolean = groupKey(s) in unseekable && !s.isPlaylist()

    /** Dead link: its provider group never plays (web pages, "not found", "bad request"), it failed that way this session,
     *  or it is a signed link that has already expired. Hidden from Sources and never chosen. */
    fun isDead(s: StreamItem): Boolean {
        val url = s.stream.url ?: return true
        return url in deadUrls || groupKey(s) in deadGroups || signedLinkExpired(url) || hostKey(url) in deadHosts
    }
    fun deadGroupCount(): Int = deadGroups.size
    fun deadHostList(): List<String> = deadHosts.toList().sorted()

    /** A dead link. Its server gets a strike: 3 strikes and never a working video = the whole server is hidden. */
    fun markDeadUrl(url: String) {
        deadUrls += url
        val h = hostKey(url)
        if (h.isEmpty() || h in okHosts) return
        val n = (hostStrikes[h] ?: 0) + 1
        hostStrikes[h] = n
        if (n >= 3 && deadHosts.add(h)) prefs?.edit()?.putStringSet("deadHosts", deadHosts.toSet())?.apply()
    }

    /** This server sent a real video: never hide it as a whole. */
    fun noteHostOk(url: String) {
        val h = hostKey(url)
        if (h.isEmpty() || !okHosts.add(h)) return
        hostStrikes.remove(h)
        if (deadHosts.remove(h)) prefs?.edit()?.putStringSet("deadHosts", deadHosts.toSet())?.apply()
    }

    /** Server name used for "whole server dead": the site (hubcloud.ist), but the full name on shared platforms
     *  (one workers.dev app can be dead while another works). */
    fun hostKey(url: String): String {
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return ""
        val shared = listOf("workers.dev", "r2.dev", "pages.dev", "cloudflarestorage.com", "googleusercontent.com", "herokuapp.com", "vercel.app", "netlify.app")
        return if (shared.any { host.endsWith(it) }) host else host.split('.').takeLast(2).joinToString(".")
    }

    /** Is this answer a "dead link" (not a temporary problem)? 400 / 404 / 410, a web page instead of a video, or an
     *  expired / revoked signed storage link (Cloudflare R2 answers 403 with an XML error at once). */
    fun isDeadReason(status: Int, type: String?, url: String? = null): Boolean =
        status == 400 || status == 404 || status == 410 || (status in 200..299 && type?.startsWith("text/html", true) == true) ||
            (status == 403 && type?.contains("xml", true) == true && url != null && hostKey(url).endsWith("cloudflarestorage.com"))

    /**
     * A provider group that keeps failing in the Source Test (3+ tests and never played, or 4+ tests and under 25%).
     * With "Tested sources only" on, it is hidden from Sources and never picked; untested groups always stay.
     */
    fun keepsFailing(s: StreamItem): Boolean = keyFailing(groupKey(s))
    fun keyFailing(k: String): Boolean {
        val st = groupTotals[k] ?: return false
        return (st.first >= 3 && st.second == 0) || (st.first >= 4 && st.second.toDouble() / st.first < 0.25)
    }
    /** tests / played per group over every quality and language. */
    private val groupTotals = ConcurrentHashMap<String, Pair<Int, Int>>()

    /** Amazon-style signed links (Cloudflare R2 …) carry their own expiry: X-Amz-Date + X-Amz-Expires. */
    fun signedLinkExpired(url: String): Boolean = runCatching {
        val u = Uri.parse(url)
        val date = u.getQueryParameter("X-Amz-Date") ?: return false
        val secs = u.getQueryParameter("X-Amz-Expires")?.toLongOrNull() ?: return false
        val fmt = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val start = fmt.parse(date)?.time ?: return false
        System.currentTimeMillis() > start + secs * 1000 - 30_000
    }.getOrDefault(false)

    /** Score of a measured group: success rate first, then speed; files you can't jump inside are pushed down. */
    fun scoreOf(ok: Int, tests: Int, medianMs: Long, seekable: Boolean): Double {
        if (tests == 0) return 0.0
        val rate = ok.toDouble() / tests
        val secs = (medianMs / 1000.0).coerceAtMost(20.0)
        return rate * 100 - secs * 4 - (if (seekable) 0 else 60)
    }

    // ───── Learning while you watch ─────

    fun recordOk(s: StreamItem?) {
        s?.stream?.url?.let { noteHostOk(it) }
        val k = s?.let { groupKey(it) } ?: return
        fails.remove(k)
    }

    /** Failures from real plays since the last Source Test (shown at the top of the next test log). */
    fun playFails(): List<String> = prefs?.getString("playFails", null)?.split("\n")?.filter { it.isNotBlank() }.orEmpty()
    fun clearPlayFails() { prefs?.edit()?.remove("playFails")?.apply() }
    private fun logPlayFail(item: StreamItem, reason: String) {
        val p = prefs ?: return
        val t = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.US).format(java.util.Date())
        val line = "$t · ${labelOf(groupKey(item))} · ${Prepare.qualityName(item.resolution())} · ${reason.replace('\n', ' ').take(140)} · ${item.stream.url.orEmpty().take(90)}"
        p.edit().putString("playFails", (playFails() + line).takeLast(150).joinToString("\n")).apply()
    }

    /** A link failed to play. The second failure in a row puts its group under observation and reports it. */
    suspend fun recordFail(s: StreamItem?, reason: String) {
        val item = s ?: return
        logPlayFail(item, reason)
        val k = groupKey(item)
        val n = (fails[k] ?: 0) + 1
        fails[k] = n
        if (n >= 2 && k !in observed) {
            observed += k
            prefs?.edit()?.putStringSet("observed", observed.toSet())?.apply()
            report(FailReport(k, labelOf(k), n, reason.take(160), System.currentTimeMillis(), android.os.Build.MODEL ?: ""))
        }
    }

    // ───── Cloud (shared/sourceRanks: everyone reads, only the owner writes; shared/reports: failure reports) ─────

    private val jsonType = "application/json".toMediaType()

    /** Downloads the shared ranking (no sign-in needed). */
    suspend fun fetchShared(): Boolean = withContext(Dispatchers.IO) {
        val f = runCatching { Api.json.decodeFromString<RankFile>(Api.get("${CloudConfig.DB_URL}/shared/sourceRanks.json")) }.getOrNull()
            ?: return@withContext false
        if (f.entries.isNotEmpty() && f.at > file.at) {
            AutoPlayRules.checkpoint(Public.vm, "Before the shared ranking")
            apply(f, fromTest = false)
        }
        true
    }

    /** Uploads this phone's ranking for everyone. Only works from the owner's account (Firebase rules). Null = done, else why not. */
    suspend fun upload(): String? = withContext(Dispatchers.IO) {
        if (file.entries.isEmpty()) return@withContext "No ranking yet — run the Source Test first."
        val t = Cloud.token() ?: return@withContext "Sign in first (Settings › Account & Cloud Sync)."
        runCatching {
            val req = Request.Builder().url("${CloudConfig.DB_URL}/shared/sourceRanks.json?auth=$t")
                .put(Api.json.encodeToString(file).toRequestBody(jsonType)).build()
            Api.http.newCall(req).execute().use { r -> if (r.isSuccessful) null else if (r.code == 401 || r.code == 403) "Not allowed: only the owner's account can upload." else "HTTP ${r.code}" }
        }.getOrElse { "Couldn't reach the cloud: ${it.javaClass.simpleName}" }
    }

    private suspend fun report(r: FailReport) = withContext(Dispatchers.IO) {
        val uid = Cloud.user.value?.uid ?: return@withContext
        val t = Cloud.token() ?: return@withContext
        val id = Integer.toHexString(r.key.hashCode())
        runCatching {
            val req = Request.Builder().url("${CloudConfig.DB_URL}/shared/reports/$uid/$id.json?auth=$t")
                .put(Api.json.encodeToString(r).toRequestBody(jsonType)).build()
            Api.http.newCall(req).execute().close()
        }
    }

    /** Every user's failure reports (owner only). */
    suspend fun allReports(): Pair<List<FailReport>, String?> = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext emptyList<FailReport>() to "Sign in first."
        runCatching {
            val body = Api.get("${CloudConfig.DB_URL}/shared/reports.json?auth=$t")
            if (body.trim() == "null") return@runCatching emptyList<FailReport>() to null
            val byUser = Api.json.decodeFromString<Map<String, Map<String, FailReport>>>(body)
            byUser.values.flatMap { it.values }.groupBy { it.key }.map { (k, l) ->
                FailReport(k, l.first().label, l.sumOf { it.count }, l.maxByOrNull { it.at }?.reason.orEmpty(), l.maxOf { it.at }, "${l.size} phone(s)")
            }.sortedByDescending { it.count } to null
        }.getOrElse { emptyList<FailReport>() to "Couldn't read reports (${it.message?.take(60)})" }
    }
}

/**
 * Candidates for a quality level, best first: the right quality group first (4K / Full HD / Mid HD / Low — falling back
 * to the next lower one), then servers that keep failing in real plays last (e.g. FSL storage links picked cold), then
 * your language (Hindi → English…), then the measured reliability, then resolution. Groups under observation and files
 * you can't jump inside go last. MovieBox links (one link per dub) are ordered among themselves language first.
 */
fun AppViewModel.rankedCandidates(list: List<StreamItem>, level: String, quickOnly: Boolean): List<StreamItem> {
    val testedOnly = isOn(texts.value["testedOnly"] ?: "yes")
    val pool = list.filter { it.stream.url != null && (!quickOnly || isQuick(it)) && !Ranker.isDead(it) && !(testedOnly && Ranker.keepsFailing(it)) }
        .distinctBy { it.stream.url }
    val target = AllQualityLevels.firstOrNull { it.id == level }?.res ?: 0
    // Auto: Full HD (TV and phone start fast), else the best below, else anything.
    val targetTier = if (target == 0) 3 else Ranker.tier(target)
    fun tierCost(s: StreamItem): Int { val t = Ranker.tier(s.effectiveRes()); return if (t == targetTier) 0 else if (t < targetTier) targetTier - t else 10 + t }
    val sorted = pool.sortedWith(
        compareBy<StreamItem>({ if (Ranker.score(it) <= -1000.0) 1 else 0 }, { if (Ranker.knownUnseekable(it)) 1 else 0 }, { if (it.unplayableHere()) 1 else 0 },
            { tierCost(it) }, { HostStats.penalty(it.stream.url.orEmpty()) }, { langRank(it) }, { if (it.isHeavy()) 1 else 0 })
            .thenByDescending { Ranker.score(it) }.thenByDescending { it.effectiveRes() },
    )
    // MovieBox only: your language before quality (Hindi → English → Original audio → unlabelled → other dubs), in the
    // places MovieBox links already hold — every other server keeps its order.
    val slots = sorted.indices.filter { sorted[it].isMovieBox() }
    if (slots.size < 2) return sorted
    val mb = slots.map { sorted[it] }.sortedWith(
        compareBy<StreamItem>({ if (Ranker.score(it) <= -1000.0) 1 else 0 }, { if (it.unplayableHere()) 1 else 0 }, { langRank(it) },
            // Streaming playlists (.mpd) start at once, the single .mp4 file needs opening first: playlists first.
            { if (it.isPlaylist()) 0 else 1 }, { tierCost(it) })
            .thenByDescending { Ranker.score(it) }.thenByDescending { it.effectiveRes() },
    )
    val out = sorted.toMutableList()
    slots.forEachIndexed { i, at -> out[at] = mb[i] }
    return out
}

/** A MovieBox link (directly from PenguPlay, or through AIOStreams …): every dub of a title is a separate link. */
fun StreamItem.isMovieBox(): Boolean {
    val t = (allText() + " " + (stream.behaviorHints?.bingeGroup ?: "") + " " + addonName + " " + stream.url.orEmpty() + " " + (sx()["n"] ?: "")).lowercase()
    return "moviebox" in t || "hakunaymatata" in t
}

/** A streaming playlist (DASH .mpd / HLS .m3u8) rather than one video file. */
fun StreamItem.isPlaylist(): Boolean = stream.url?.let { StreamType.mimeFor(it, fileName()) != null } == true

/**
 * Resolution for choosing: the one the stream names; else what was learned from its playlist or from playing it;
 * else a guess from the file size (an episode or a film).
 */
fun StreamItem.effectiveRes(): Int {
    resolution().takeIf { it > 0 }?.let { return it }
    val url = stream.url.orEmpty()
    ResMemory.get(url)?.let { return it }
    val size = stream.behaviorHints?.videoSize?.takeIf { it > 0 } ?: StreamCache.sizes[url] ?: sizeInText(allText()) ?: return 0
    val mb = size / (1024 * 1024)
    val episode = Regex("s\\d{1,2}[ ._-]?e\\d{1,3}").containsMatchIn(allText())
    return if (episode) when { mb < 300 -> 480; mb < 700 -> 720; mb < 3000 -> 1080; else -> 2160 }
    else when { mb < 800 -> 480; mb < 1600 -> 720; mb < 7000 -> 1080; else -> 2160 }
}

private fun sizeInText(t: String): Long? {
    val m = Regex("(\\d+(?:\\.\\d+)?)\\s*(gb|mb)(?![a-z])").find(t) ?: return null
    val v = m.groupValues[1].toDoubleOrNull() ?: return null
    return (if (m.groupValues[2] == "gb") v * 1024 * 1024 * 1024 else v * 1024 * 1024).toLong()
}

/** Picture height learned for a link: from its playlist (DASH / HLS lists it) or from playing it. */
object ResMemory {
    private val byUrl = ConcurrentHashMap<String, Int>()
    fun get(url: String): Int? = byUrl[url]
    fun put(url: String, height: Int) { if (height in 144..4400) byUrl[url] = when { height >= 1600 -> 2160; height >= 1000 -> 1080; height >= 650 -> 720; else -> 480 } }
    /** Highest picture in a DASH / HLS playlist text. */
    fun fromPlaylist(url: String, text: String) {
        val hs = Regex("height=\"(\\d+)\"").findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() } +
            Regex("RESOLUTION=\\d+x(\\d+)").findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }
        hs.maxOrNull()?.let { put(url, it) }
    }
}

/**
 * What real plays on this phone say about each server: a start that showed a picture, or one that failed (refused,
 * nothing arrived). Servers that mostly fail go behind the others; servers that are good but slow to answer the first
 * time (pixeldrain) get more time before the player moves on.
 */
object HostStats {
    private var prefs: android.content.SharedPreferences? = null
    private val ok = ConcurrentHashMap<String, Int>()
    private val fail = ConcurrentHashMap<String, Int>()
    private val slowest = ConcurrentHashMap<String, Long>()

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences("host_stats", Context.MODE_PRIVATE)
        prefs?.all?.forEach { (k, v) ->
            val parts = (v as? String)?.split(",") ?: return@forEach
            ok[k] = parts.getOrNull(0)?.toIntOrNull() ?: 0
            fail[k] = parts.getOrNull(1)?.toIntOrNull() ?: 0
            parts.getOrNull(2)?.toLongOrNull()?.let { slowest[k] = it }
        }
    }

    /** One name per storage service: every FSL / R2 bucket counts as "cloudflarestorage.com". */
    fun key(url: String): String {
        val h = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return ""
        return h.split('.').takeLast(2).joinToString(".")
    }

    private fun save(k: String) {
        // Recent behaviour matters most: old counts fade once they get big.
        if ((ok[k] ?: 0) + (fail[k] ?: 0) > 40) { ok[k] = (ok[k] ?: 0) / 2; fail[k] = (fail[k] ?: 0) / 2 }
        prefs?.edit()?.putString(k, "${ok[k] ?: 0},${fail[k] ?: 0},${slowest[k] ?: 0}")?.apply()
    }

    fun noteOk(url: String, startMs: Long) {
        val k = key(url).ifEmpty { return }
        ok[k] = (ok[k] ?: 0) + 1
        if (startMs in 1..30_000) slowest[k] = maxOf((slowest[k] ?: 0L) * 9 / 10, startMs)
        save(k)
    }

    fun noteFail(url: String) {
        val k = key(url).ifEmpty { return }
        fail[k] = (fail[k] ?: 0) + 1
        save(k)
    }

    /** Mostly failing? (3+ failures and under half the starts worked.) FSL storage links count as failing until proven. */
    fun failing(url: String): Boolean {
        val k = key(url)
        val o = ok[k] ?: 0; val f = fail[k] ?: 0
        if (o + f == 0) return k == "cloudflarestorage.com"
        return f >= 3 && o * 2 < o + f
    }

    /** Sort key: 1 = behind the other servers of the same quality. */
    fun penalty(url: String): Int = if (url.isNotEmpty() && failing(url)) 1 else 0

    /** How long a link may stay silent (nothing arrived) before the player tries the next one. */
    fun startBudgetMs(url: String): Long {
        val k = key(url)
        if (failing(url)) return 3_500L
        val o = ok[k] ?: 0
        val slow = slowest[k] ?: 0L
        return when {
            o >= 2 && slow > 0 -> (slow * 3 / 2).coerceIn(6_000L, 12_000L)
            k == "pixeldrain.dev" || k == "pixeldrain.com" -> 10_000L
            else -> 6_000L
        }
    }

    fun summary(): List<String> = (ok.keys + fail.keys).toSortedSet().map { k ->
        "$k · started ${ok[k] ?: 0} · failed ${fail[k] ?: 0}" + ((slowest[k] ?: 0L).takeIf { it > 0 }?.let { " · slowest start ${"%.1f".format(it / 1000f)} s" } ?: "")
    }
}

/** What this device can decode (checked once). */
object DeviceCodecs {
    val dolbyVision: Boolean by lazy {
        runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .any { !it.isEncoder && it.supportedTypes.any { t -> t.equals("video/dolby-vision", true) } }
        }.getOrDefault(false)
    }
}

private fun StreamItem.allText(): String = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename).joinToString(" ").lowercase()
private val heavyRx = Regex("remux|truehd|true-hd|dts-?hd|dts-?x|dts[ ._-]?ma")
private val dvRx = Regex("(^|[^a-z])(dv|dovi|dolby[ ._-]?vision)([^a-z]|$)")
private val hdrRx = Regex("hdr10|hdr")

/** Huge "REMUX" files or lossless TrueHD / DTS-HD audio: slow to start on mobile data — chosen only when nothing lighter exists. */
fun StreamItem.isHeavy(): Boolean = heavyRx.containsMatchIn(allText()) || (stream.behaviorHints?.videoSize ?: 0L) > 12L * 1024 * 1024 * 1024

/** Dolby Vision-only video (no HDR10 / SDR base) on a device without a Dolby Vision decoder: no picture. */
fun StreamItem.unplayableHere(): Boolean {
    if (DeviceCodecs.dolbyVision) return false
    val t = allText()
    return dvRx.containsMatchIn(t) && !hdrRx.containsMatchIn(t)
}
