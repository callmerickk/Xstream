package com.example.streamnest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
/**
 * 0.9.4: the streamx-home Worker's address and the headers every call carries. x-sx-ver ("xstream-phone/64") lets the
 * Worker answer old versions with "Update the app" once the owner sets MIN_VER there (nothing changes until then).
 * Sent by both apps: it is only a version number.
 */
object SxWorker {
    const val BASE = "https://streamx-home.callmerick093.workers.dev"
    const val KEY = "sx-home-1"
    val VER: String = Brand.NAME.substringBefore(' ').lowercase() + (if (BuildConfig.IS_TV) "-tv/" else "-phone/") + BuildConfig.VERSION_CODE
    fun stamp(b: Request.Builder): Request.Builder = b.header("x-sx", KEY).header("x-sx-ver", VER)
    /**
     * 0.9.4 (accessGate): the sign-in token for /find and /p, only while approval is required and only from accounts that
     * aren't the developer's — the Worker then plays the "waiting for approval" video to accounts not approved.
     * null = not sent (the Worker doesn't judge approval then).
     */
    fun approvalToken(): String? {
        if (!Feature.accessGate || BuildConfig.IS_TV || Public.dev) return null
        val ctx = Public.vm?.appContext ?: return null
        if (!Access.requiredKnown(ctx)) return null
        return Cloud.freshToken()
    }
}
/**
 * 0.9.4 (Feature.cloudPrep): the cloud's part of preparing links.
 *  - [checked]: what the Worker's Cron found when it checked a link (size, seekable, when). Prepare uses it instead of
 *    its own one-byte check while it is under 10 minutes old.
 *  - [good]: after a real first picture, the Worker is told which link played and how fast (title type + id, hashes of
 *    the addon address and the link, the server group, start time) — never an account or the link itself.
 *  - [reportCw]: the Continue-watching titles (type + IMDb id), at most once a day each, so the Cron prepares them.
 */
object CloudPrep {
    data class Checked(val at: Long, val size: Long, val seek: Boolean, val type: String)
    private val checked = ConcurrentHashMap<String, Checked>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val FRESH_MS = 10 * 60_000L
    fun noteChecked(m: Map<String, Checked>) {
        if (!Feature.cloudPrep || m.isEmpty()) return
        if (checked.size > 3000) checked.clear()
        checked.putAll(m)
    }
    /** The cloud's check as if this phone had made it — only when under 10 minutes old, never for pixeldrain. */
    fun probeOf(url: String): LinkProbe? {
        if (!Feature.cloudPrep || isPixeldrain(url)) return null
        val c = checked[url] ?: return null
        if (System.currentTimeMillis() - c.at > FRESH_MS || c.size <= 0) return null
        val host = hostOf(url)
        return LinkProbe(ok = true, status = 206, ttfbMs = 0, size = c.size, seekable = c.seek, type = c.type.ifBlank { null },
            host = host, finalHost = host, error = null)
    }
    private fun sha(t: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(t.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun post(path: String, body: String) {
        runCatching {
            Api.http.newCall(SxWorker.stamp(Request.Builder().url(SxWorker.BASE + path)).post(body.toRequestBody("application/json".toMediaType())).build())
                .execute().close()
        }
    }
    /** A link showed its first picture after [startMs]. Only addon links (plugins have no address the cloud knows). */
    fun good(vm: AppViewModel, type: String, id: String, url: String, addonName: String?, startMs: Long) {
        if (!Feature.cloudPrep || BuildConfig.IS_TV || startMs <= 0 || startMs > 120_000) return
        if (!id.startsWith("tt") || (type != "movie" && type != "series") || !url.startsWith("http") || url.contains("127.0.0.1") || url.contains("localhost")) return
        val a = vm.addons.value.firstOrNull { it.label() == addonName } ?: return
        val base = CloudFind.baseOf(a.baseUrl)
        scope.launch {
            val body = buildJsonObject {
                put("type", type); put("id", id); put("a", sha(base)); put("u", sha(url))
                put("host", hostGroup(url)); put("ms", startMs)
            }.toString()
            post("/good", body)
            ActLog.add("NET", "cloud: told the last good link (${hostGroup(url)}, ${startMs} ms)")
        }
    }
    private val cwSent = ConcurrentHashMap<String, Long>()
    /**
     * 0.9.10: returns how many ids were really sent (0 = all were sent in the last 24 h). [quiet] = no log line here (the
     * ask-ahead caller, CwAhead, writes its own).
     */
    fun reportCw(keys: List<Pair<String, String>>, quiet: Boolean = false): Int {
        if (!Feature.cloudPrep || BuildConfig.IS_TV) return 0
        val now = System.currentTimeMillis()
        val fresh = keys.filter { (t, id) -> id.startsWith("tt") && (t == "movie" || t == "series") && now - (cwSent[id] ?: 0L) > 24 * 3600_000L }.take(20)
        if (fresh.isEmpty()) return 0
        fresh.forEach { cwSent[it.second] = now }
        scope.launch {
            val body = buildJsonObject {
                put("items", JsonArray(fresh.map { (t, id) -> buildJsonObject { put("type", t); put("id", id) } }))
            }.toString()
            post("/cw", body)
            if (!quiet) ActLog.add("NET", "cloud: ${fresh.size} Continue-watching titles sent to be prepared")
        }
        return fresh.size
    }
    /** Server group (r2.dev, hakunaymatata.com; workers.dev kept whole) — the same rule as the Worker's. */
    fun hostGroup(url: String): String {
        val h = hostOf(url).orEmpty()
        return if (h.endsWith("workers.dev")) h else h.split('.').takeLast(2).joinToString(".")
    }
    /** The "checked" part of a /find answer. */
    fun parseChecked(o: JsonObject?): Map<String, Checked> {
        if (o == null) return emptyMap()
        return o.mapNotNull { (u, v) ->
            val j = v as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (j[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val at = s("at")?.toLongOrNull() ?: return@mapNotNull null
            u to Checked(at, s("size")?.toLongOrNull() ?: 0L, s("seek") == "true", s("type").orEmpty())
        }.toMap()
    }
}
/**
 * 0.9.10 (Feature.askAhead): the title page asks the cloud ahead for the episodes you will probably play next.
 * Episode ids go through CloudPrep.reportCw (its own 24 h memory per id). Here: at most 5 ids per call and, for the
 * page-level call, one call per title + open season every 6 h (SharedPreferences "cwAhead").
 */
object CwAhead {
    private const val GAP = 6L * 3600_000L
    private fun prefs(ctx: android.content.Context) =
        ctx.applicationContext.getSharedPreferences("cwAhead", android.content.Context.MODE_PRIVATE)
    /** [limitKey] null = no 6 h gate (an episode row held in view); else "<titleKey>:<limitKey>" is asked once per 6 h. */
    fun ask(ctx: android.content.Context, titleKey: String, ids: List<String>, limitKey: String?): Int {
        if (!Feature.askAhead || BuildConfig.IS_TV) return 0
        val send = ids.filter { it.startsWith("tt") }.distinct().take(5)
        if (send.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        val gate = limitKey?.let { "$titleKey:$it" }
        if (gate != null && now - p.getLong(gate, 0L) < GAP) return 0
        val n = CloudPrep.reportCw(send.map { "series" to it }, quiet = true)
        if (gate != null) {
            val e = p.edit()
            if (p.all.size > 200) p.all.forEach { (k, v) -> if (v is Long && now - v > GAP) e.remove(k) }
            e.putLong(gate, now).apply()
        }
        if (n > 0) ActLog.add("NET", "cloud asked ahead: $n episodes")
        return n
    }
}
