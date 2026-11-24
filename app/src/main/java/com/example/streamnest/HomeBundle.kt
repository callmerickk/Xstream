package com.example.streamnest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** TV 0.9.14: Home's catalogs in one request through StreamX's Cloudflare Worker (`/bundle`). Empty map = use the usual way. */
object HomeBundle {
    private const val URL = "https://streamx-home.callmerick093.workers.dev/bundle"
    private const val KEY = "sx-home-1"

    suspend fun fetch(urls: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) return@withContext emptyMap()
        val t0 = System.currentTimeMillis()
        val body = buildJsonObject { put("urls", JsonArray(urls.map { JsonPrimitive(it) })) }.toString()
        val out = withTimeoutOrNull(5_000) {
            runCatching {
                Api.http.newCall(SxWorker.stamp(Request.Builder().url(URL)).post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                    if (!r.isSuccessful) return@runCatching emptyMap<String, String>()
                    val o = Api.json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject ?: return@runCatching emptyMap<String, String>()
                    o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }.toMap()
                }
            }.getOrNull()
        }.orEmpty()
        val missed = urls.filter { it !in out }.map { runCatching { java.net.URI(it).host }.getOrNull() ?: "?" }.groupingBy { it }.eachCount()
        ActLog.add("NET", "Home bundle: ${out.size} of ${urls.size} catalogs from Cloudflare in ${System.currentTimeMillis() - t0} ms" +
            if (missed.isNotEmpty()) " · missed: " + missed.entries.joinToString(", ") { "${it.key}×${it.value}" } else "")
        out
    }
}
