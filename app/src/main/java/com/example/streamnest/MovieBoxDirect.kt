package com.example.streamnest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * MovieBox asked directly (its web API — the same one the open-source CineStream plugin uses), instead of through four
 * CloudStream plugin copies that each search step by step. One search, then every language version (original, [Hindi]…)
 * in parallel, each with its download (MP4) and play (DASH / streams) answers at once. The title's MovieBox pages are
 * remembered, so the next episode needs only the link requests.
 *
 * Safety: an answer that looks wrong (a file under 50 MB, an error, nothing at all) is logged ("MBDIRECT") and the
 * CloudStream MovieBox plugins are asked instead. When the direct way keeps failing while the plugins work, the plugins
 * go first for a day, then the direct way is tried again.
 */
object MovieBoxDirect {
    private const val BASE = "https://h5-api.aoneroom.com"
    private const val SITE = "https://fmoviesunblocked.net"
    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L
    /** title key → [(subjectId, language, detailPath)] */
    private val pages = java.util.concurrent.ConcurrentHashMap<String, List<Triple<String, String, String>>>()
    private fun prefs() = CsPlugins.prefsOrNull()

    /** false = the direct way failed recently while the plugins worked: plugins first (until a day has passed). */
    fun preferred(): Boolean = System.currentTimeMillis() - (prefs()?.getLong("mbDirectBadAt", 0L) ?: 0L) > 24 * 3600_000L
    fun noteBroken(why: String) { prefs()?.edit()?.putLong("mbDirectBadAt", System.currentTimeMillis())?.apply(); ActLog.add("MBDIRECT", "direct MovieBox looks broken ($why) — plugins first for a day") }
    fun noteOk() { prefs()?.edit()?.remove("mbDirectBadAt")?.apply() }

    private fun headers(b: Request.Builder, referer: String = BASE): Request.Builder = b
        .header("X-Client-Info", "{\"timezone\":\"Asia/Kolkata\"}").header("Accept", "application/json").header("Accept-Language", "en-US,en;q=0.5")
        .header("Referer", referer).header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
        .apply { token?.let { header("Authorization", "Bearer $it") } }

    private fun call(req: Request): String = Api.http.newCall(req).execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body?.string().orEmpty() }

    private fun ensureToken() {
        if (token != null && System.currentTimeMillis() - tokenAt < 30 * 60_000L) return
        val xUser = Api.http.newCall(Request.Builder().url("$BASE/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox").build()).execute().use { it.header("x-user") }
            ?: error("no token")
        token = (Api.json.parseToJsonElement(xUser) as JsonObject).s("token") ?: error("no token")
        tokenAt = System.currentTimeMillis()
    }

    private fun JsonObject.s(k: String) = runCatching { this[k]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }
    private fun JsonElement?.obj() = this as? JsonObject
    private fun unwrap(o: JsonObject): JsonObject { val d = o["data"].obj() ?: return o; return d["data"].obj() ?: d }
    private fun norm(t: String) = t.lowercase().replace("&", "and").replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** Links for a film or episode; empty = nothing (the caller then asks the plugins). */
    suspend fun links(name: String, year: Int?, series: Boolean, season: Int?, episode: Int?): List<StreamItem> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val key = "${norm(name)}|$year|$series"
        val subs = pages[key] ?: run {
            ensureToken()
            val body = Api.json.encodeToString(JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
                put("keyword", kotlinx.serialization.json.JsonPrimitive(name)); put("page", kotlinx.serialization.json.JsonPrimitive(1))
                put("perPage", kotlinx.serialization.json.JsonPrimitive(24)); put("subjectType", kotlinx.serialization.json.JsonPrimitive(if (series) 2 else 1))
            })
            val res = Api.json.parseToJsonElement(call(headers(Request.Builder().url("$BASE/wefeed-h5api-bff/subject/search")).post(body.toRequestBody("application/json".toMediaType())).build())) as JsonObject
            val items = (unwrap(res)["items"] as? JsonArray).orEmpty().mapNotNull { it.obj() }
            // Same name exactly (a "[Hindi]" / "[Tamil]" version counts), same year (±1) when MovieBox says one.
            val rx = Regex("^(.*?)(?:\\s+\\[([^\\]]+)\\])?$")
            val found = items.mapNotNull { o ->
                val id = o.s("subjectId") ?: return@mapNotNull null
                val raw = (o.s("title") ?: return@mapNotNull null).replace(Regex("\\sS\\d+(?:-S?\\d+)*$", RegexOption.IGNORE_CASE), "")
                val m = rx.find(raw.trim()) ?: return@mapNotNull null
                if (norm(m.groupValues[1]) != norm(name)) return@mapNotNull null
                val y = o.s("releaseDate")?.take(4)?.toIntOrNull()
                if (year != null && y != null && kotlin.math.abs(y - year) > 1 && !series) return@mapNotNull null
                if (series && year != null && y != null && y < year - 1) return@mapNotNull null
                Triple(id, m.groupValues[2].ifBlank { "Original" }, o.s("detailPath").orEmpty())
            }.distinctBy { it.first }.take(4)
            if (found.isNotEmpty()) pages[key] = found
            found
        }
        if (subs.isEmpty()) { ActLog.add("MBDIRECT", "\"$name\": not found"); return@withContext emptyList() }
        val out = coroutineScope {
            subs.map { (id, lang, dp0) -> async { runCatching { one(id, lang, dp0, series, season, episode) }.onFailure { ActLog.add("MBDIRECT", "\"$name\" [$lang]: ${it.message}") }.getOrDefault(emptyList()) } }.awaitAll().flatten()
        }
        ActLog.add("MBDIRECT", "\"$name\"${if (series) " S${season}E$episode" else ""}: ${out.size} links in ${System.currentTimeMillis() - t0} ms (${subs.joinToString { it.second }})")
        out
    }

    private suspend fun one(id: String, lang: String, dp0: String, series: Boolean, season: Int?, episode: Int?): List<StreamItem> = coroutineScope {
        val dp = dp0.ifBlank {
            val o = Api.json.parseToJsonElement(call(Request.Builder().url("https://h5.aoneroom.com/wefeed-h5-bff/web/post/list/subject?id=$id").build())) as JsonObject
            ((o["data"].obj()?.get("items") as? JsonArray)?.firstOrNull().obj()?.get("subject").obj())?.s("detailPath") ?: ""
        }
        val q = "subjectId=$id" + (if (series) "&se=$season&ep=$episode" else "") + "&detailPath=$dp"
        val ref = "$SITE/spa/videoPlayPage/movies/$dp?id=$id&type=/movie/detail"
        val dl = async { runCatching { unwrap(Api.json.parseToJsonElement(call(headers(Request.Builder().url("$BASE/wefeed-h5api-bff/subject/download?$q"), ref).header("Origin", SITE).build())) as JsonObject) }.getOrNull() }
        val pl = async { runCatching { unwrap(Api.json.parseToJsonElement(call(headers(Request.Builder().url("$BASE/wefeed-h5api-bff/subject/play?$q"), ref).header("Origin", SITE).build())) as JsonObject) }.getOrNull() }
        val d = dl.await(); val p = pl.await()
        val hdr = mapOf("Referer" to "$SITE/", "Origin" to SITE)
        val label = if (lang == "Original") "" else " [$lang]"
        val out = ArrayList<StreamItem>()
        fun item(url: String, res: Int, size: Long?, kind: String): StreamItem {
            val q2 = if (res > 0) "${res}p" else "Auto"
            return StreamItem("CloudStream · MovieBox", StreamInfo(url = url, name = "MovieBox$label\n$q2", title = "MovieBox$label $q2 $kind",
                behaviorHints = BehaviorHints(videoSize = size?.takeIf { it > 0 }, proxyHeaders = ProxyHeaders(hdr)))).also { StreamHeaders.remember(it) }
        }
        val seen = HashSet<Int>()
        (d?.get("downloads") as? JsonArray).orEmpty().mapNotNull { it.obj() }.forEach { o ->
            val u = o.s("url") ?: return@forEach
            if (o["vipLocked"]?.jsonPrimitive?.booleanOrNull == true) return@forEach
            val res = o.s("resolution")?.toIntOrNull() ?: 0
            val size = o["size"]?.jsonPrimitive?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
            // A "film" of a few MB is a sample or an error page: MovieBox changed something — logged, skipped.
            if (size != null && size in 1 until 50L * 1024 * 1024) { ActLog.add("MBDIRECT", "skipped a ${size / 1024} KB file ($lang ${res}p) — looks wrong"); return@forEach }
            seen += res; out += item(u, res, size, "MP4")
        }
        (p?.get("streams") as? JsonArray).orEmpty().mapNotNull { it.obj() }.forEach { o ->
            val u = o.s("url") ?: return@forEach
            if (o["vipLocked"]?.jsonPrimitive?.booleanOrNull == true) return@forEach
            val res = o.s("resolutions")?.toIntOrNull() ?: o.s("resolution")?.toIntOrNull() ?: 0
            if (res in seen) return@forEach
            seen += res; out += item(u, res, null, "stream")
        }
        (p?.get("dash") as? JsonArray).orEmpty().mapNotNull { it.obj() }.forEach { o ->
            val u = o.s("url") ?: return@forEach
            if (o["vipLocked"]?.jsonPrimitive?.booleanOrNull == true) return@forEach
            out += item(u, 0, null, "DASH")
        }
        out
    }
}
