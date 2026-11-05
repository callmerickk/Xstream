package com.example.streamnest

import com.lagradost.cloudstream3.MainAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * VegaMovies / RogMovies move to new addresses often, and the plugin sometimes starts on a dead one (1vegamovies.shop)
 * or on one that answers its search with a web page. Instead of reloading the plugin (which could land on a dead
 * address again — 0.8.2), Xstream tests the known addresses with one tiny search and gives the plugin the one that
 * really answers with results. Checked when the plugin loads and whenever its search fails; logged (SITE).
 */
object SiteAddress {
    private val known = mapOf(
        "vega" to listOf("https://new1.vegamovies.lease", "https://vegamovies.lease", "https://vegamovies.mq", "https://1vegamovies.shop"),
        "rog" to listOf("https://rogmovies.one", "https://rogmovies.im", "https://rogmovies.com"),
    )
    private val checkedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val good = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun kind(api: MainAPI): String? = when {
        api.name.contains("vega", true) -> "vega"
        api.name.contains("rogmovies", true) -> "rog"
        else -> null
    }
    fun handles(api: MainAPI) = kind(api) != null

    private fun answers(base: String): Boolean = runCatching {
        Api.http.newCall(Request.Builder().url("${base.trimEnd('/')}/search.php?q=a&page=1")
            .header("User-Agent", com.lagradost.cloudstream3.USER_AGENT).header("Accept", "application/json, text/plain, */*").build())
            .execute().use { r -> r.isSuccessful && (r.body?.string()?.trimStart()?.firstOrNull()?.let { it == '{' || it == '[' } == true) }
    }.getOrDefault(false)

    private fun listed(k: String): List<String> = runCatching {
        val o = Api.json.parseToJsonElement(Api.http.newCall(Request.Builder().url("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json").build())
            .execute().use { it.body?.string().orEmpty() }) as JsonObject
        o.entries.filter { (key, _) -> if (k == "vega") key.contains("vega", true) else key.contains("rog", true) }
            .mapNotNull { (_, v) -> runCatching { v.jsonPrimitive.contentOrNull }.getOrNull()?.takeIf { it.startsWith("http") } }
    }.getOrDefault(emptyList())

    /** Makes sure [api] uses an address that answers. true = it changed (worth searching again). */
    suspend fun fix(api: MainAPI, force: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val k = kind(api) ?: return@withContext false
        val now = System.currentTimeMillis()
        val cur = runCatching { api.mainUrl }.getOrDefault("")
        good[k]?.let { g -> if (now - (checkedAt[k] ?: 0) < 30 * 60_000L) { if (g != cur) { api.mainUrl = g; ActLog.add("SITE", "${api.name}: using $g"); return@withContext true }; if (!force) return@withContext false } }
        if (!force && answers(cur)) { good[k] = cur; checkedAt[k] = now; ActLog.add("SITE", "${api.name}: $cur answers"); return@withContext false }
        val cands = (listed(k) + known[k].orEmpty() + cur).filter { it.startsWith("http") }.map { it.trimEnd('/') }.distinct()
        for (c in cands) {
            if (answers(c)) {
                good[k] = c; checkedAt[k] = now
                val changed = c != cur.trimEnd('/')
                if (changed) runCatching { api.mainUrl = c }
                val took = runCatching { api.mainUrl.trimEnd('/') == c }.getOrDefault(false)
                ActLog.add("SITE", "${api.name}: ${if (changed) "switched $cur → $c" + (if (took) "" else " (the plugin kept its own address)") else "$c answers"}")
                return@withContext changed
            }
        }
        checkedAt[k] = now
        ActLog.add("SITE", "${api.name}: no known address answered (${cands.joinToString { it.substringAfter("//") }})")
        false
    }
}
