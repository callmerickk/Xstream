package com.example.streamnest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 0.8.18: the Cloudflare link finder (`/find`). One request asks every addon at once over Cloudflare's network and
 * answers per addon address; with the Worker's database it answers from memory (≤3 h) and refreshes quietly.
 * Its answers are shown first; each addon's own direct answer still comes and replaces it (links some addons make
 * for the asking device stay right). Plugins aren't involved. Empty map = nothing from the cloud.
 * 0.8.20: an addon the cloud already answered is no longer asked a second time (see addonStreamsFlow), and the
 * request stops at once when the title is left.
 * 0.9.1: on the shared code behind Feature.cloudFind (off = never called, StreamX asks each addon itself).
 */
object CloudFind {
    private const val URL = "https://streamx-home.callmerick093.workers.dev/find"
    private const val KEY = "sx-home-1"
    /** 0.8.20: the cloud's answer is never read past 5 MB. */
    private const val MAX_BODY = 5L * 1024 * 1024
    @Volatile var enabled = true
    private val recent = ConcurrentHashMap<String, Pair<Long, Map<String, List<StreamInfo>>>>()

    /** 0.8.20: addons whose cloud links were refused this session: always asked directly from now on (cleared on restart). */
    val directOnly: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** 0.8.20: link → addon address, for links that came from the cloud and that no direct answer has confirmed yet. */
    val cloudUrls = ConcurrentHashMap<String, String>()

    /** 0.8.20: remember which links came from the cloud (kept small). */
    fun markCloud(base: String, urls: List<String?>) {
        if (cloudUrls.size > 3000) cloudUrls.clear()
        urls.forEach { u -> if (!u.isNullOrBlank()) cloudUrls[u] = base }
    }

    /** 0.8.20: a cloud link was refused: its addon is asked directly from now on. Returns the addon address, or null if the link wasn't from the cloud. */
    fun refusedBase(url: String): String? {
        val base = cloudUrls[url] ?: return null
        directOnly += base
        return base
    }

    suspend fun find(type: String, id: String, bases: List<String>): Map<String, List<StreamInfo>> = withContext(Dispatchers.IO) {
        if (!Feature.cloudFind || !enabled || bases.isEmpty() || BuildConfig.IS_TV) return@withContext emptyMap()
        val key = "$type|$id|" + bases.sorted().joinToString(",").hashCode()
        recent[key]?.let { (at, m) -> if (System.currentTimeMillis() - at < 60_000) return@withContext m }
        val t0 = System.currentTimeMillis()
        val body = buildJsonObject {
            put("type", type); put("id", id); put("addons", JsonArray(bases.map { JsonPrimitive(it) }))
        }.toString()
        // 0.8.20: a real, cancellable call. Leaving the title (or the 7 s limit) cancels the request itself.
        val got: Map<String, List<StreamInfo>>? = try {
            withTimeoutOrNull(7_000) { post(body) }
        } catch (e: CancellationException) {
            ActLog.add("NET", "Cloud link finder stopped — title left")
            throw e
        }
        val out = got.orEmpty()
        val links = out.values.sumOf { it.size }
        ActLog.add("NET", "Cloud link finder: $links links from ${out.count { it.value.isNotEmpty() }} of ${bases.size} addons in ${System.currentTimeMillis() - t0} ms")
        if (out.isNotEmpty()) recent[key] = System.currentTimeMillis() to out
        out
    }

    /** 0.8.20: enqueue() instead of execute(): cancelling the coroutine cancels the OkHttp call. Any failure = empty map. */
    private suspend fun post(body: String): Map<String, List<StreamInfo>> = suspendCancellableCoroutine { cont ->
        // 0.9.4: x-sx-ver always; the sign-in token only while approval is required (SxWorker.approvalToken).
        val rb = SxWorker.stamp(Request.Builder().url(URL)).post(body.toRequestBody("application/json".toMediaType()))
        SxWorker.approvalToken()?.let { rb.header("Authorization", "Bearer $it") }
        val call = Api.http.newCall(rb.build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { cont.resume(emptyMap()) }
            override fun onResponse(call: Call, response: Response) {
                val out = response.use { r -> runCatching { parse(r) }.getOrDefault(emptyMap()) }
                cont.resume(out)
            }
        })
    }

    private fun parse(r: Response): Map<String, List<StreamInfo>> {
        if (!r.isSuccessful) return emptyMap()
        val src = r.body?.source() ?: return emptyMap()
        // Never unbounded: ask for at most 5 MB + 1 byte; more than that = not used.
        src.request(MAX_BODY + 1)
        if (src.buffer.size > MAX_BODY) return emptyMap()
        val o = Api.json.parseToJsonElement(src.buffer.readUtf8()) as? JsonObject ?: return emptyMap()
        val per = o["addons"] as? JsonObject ?: return emptyMap()
        // 0.9.4 (cloudPrep): the Cron's checks of these links (size, seekable, when).
        if (Feature.cloudPrep) CloudPrep.noteChecked(CloudPrep.parseChecked(o["checked"] as? JsonObject))
        return per.mapNotNull { (base, v) ->
            runCatching { base to Api.json.decodeFromJsonElement(ListSerializer(StreamInfo.serializer()), v).filter { !it.url.isNullOrBlank() } }.getOrNull()
        }.toMap()
    }

    /** The addon address the Worker keys its answer by. */
    fun baseOf(baseUrl: String) = baseUrl.removeSuffix("/manifest.json").trimEnd('/')
}
