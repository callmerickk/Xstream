package com.example.streamnest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * 0.8.18: the Cloudflare link finder (`/find`). One request asks every addon at once over Cloudflare's network and
 * answers per addon address; with the Worker's database it answers from memory (≤3 h) and refreshes quietly.
 * Its answers are shown first; each addon's own direct answer still comes and replaces it (links some addons make
 * for the asking device stay right). Plugins aren't involved. Empty map = nothing from the cloud.
 */
object CloudFind {
    private const val URL = "https://streamx-home.callmerick093.workers.dev/find"
    private const val KEY = "sx-home-1"
    @Volatile var enabled = true
    private val recent = ConcurrentHashMap<String, Pair<Long, Map<String, List<StreamInfo>>>>()

    suspend fun find(type: String, id: String, bases: List<String>): Map<String, List<StreamInfo>> = withContext(Dispatchers.IO) {
        if (!enabled || bases.isEmpty() || BuildConfig.IS_TV) return@withContext emptyMap()
        val key = "$type|$id|" + bases.sorted().joinToString(",").hashCode()
        recent[key]?.let { (at, m) -> if (System.currentTimeMillis() - at < 60_000) return@withContext m }
        val t0 = System.currentTimeMillis()
        val body = buildJsonObject {
            put("type", type); put("id", id); put("addons", JsonArray(bases.map { JsonPrimitive(it) }))
        }.toString()
        val out = withTimeoutOrNull(7_000) {
            runCatching {
                Api.http.newCall(Request.Builder().url(URL).header("x-sx", KEY).post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                    if (!r.isSuccessful) return@runCatching emptyMap<String, List<StreamInfo>>()
                    val o = Api.json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject ?: return@runCatching emptyMap()
                    val per = o["addons"] as? JsonObject ?: return@runCatching emptyMap()
                    per.mapNotNull { (base, v) ->
                        runCatching { base to Api.json.decodeFromJsonElement(ListSerializer(StreamInfo.serializer()), v).filter { !it.url.isNullOrBlank() } }.getOrNull()
                    }.toMap()
                }
            }.getOrNull()
        }.orEmpty()
        val links = out.values.sumOf { it.size }
        ActLog.add("NET", "Cloud link finder: $links links from ${out.count { it.value.isNotEmpty() }} of ${bases.size} addons in ${System.currentTimeMillis() - t0} ms")
        if (out.isNotEmpty()) recent[key] = System.currentTimeMillis() to out
        out
    }

    /** The addon address the Worker keys its answer by. */
    fun baseOf(baseUrl: String) = baseUrl.removeSuffix("/manifest.json").trimEnd('/')
}
