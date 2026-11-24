package com.example.streamnest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 0.9.9: Skip intro / recap / credits times made to fit the copy that plays. The times come from introdb.app, measured on
 * one copy (usually the streaming service's, 23.976 fps). Two corrections:
 *  1. Speed: a 25 fps (European) copy runs ~4 % faster → times × 23.976 / 25.
 *  2. Learned: after Skip, a jump back / forward (up to 30 s) within 15 s means the skip was off by that much — saved
 *     per show + kind of copy (MovieBox, VegaMovies, streaming…) on this device and shared in the account cloud
 *     (`xshared/intro`). Your own correction is used at once; other people's when at least two agree (their median).
 */
object IntroFix {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What happened after the last Skip: jumps made in the next 15 s (the skip's own jump ignored). */
    class Learn { @Volatile var at = 0L; @Volatile var ignoreUntil = 0L; @Volatile var sum = 0L }

    fun kindOf(s: StreamItem?): String = when {
        s == null -> "other"
        s.isMovieBox() -> "moviebox"
        s.isVegaRog() -> "vegamovies"
        s.isPlaylist() -> "stream"
        else -> s.addonName.substringAfter("· ").lowercase().replace(Regex("[^a-z0-9]"), "").take(24).ifBlank { "other" }
    }

    /** The speed factor for a copy at [fps] (25 / 50 fps = the faster European copy). */
    // TV 0.9.10: no speed scaling — MovieBox's 25 fps copies have the same running time as the streaming ones (the
    // ×0.959 of 0.9.9 moved every time, the credits by ~50 s). Kept as 1.0 so nothing else changes.
    @Suppress("UNUSED_PARAMETER") fun speed(fps: Float): Double = 1.0

    fun adjust(s: Segments, k: Double, off: Long): Segments {
        if (k == 1.0 && off == 0L) return s
        fun f(sg: Seg?) = sg?.let { it.copy(start_ms = ((it.start_ms * k).toLong() + off).coerceAtLeast(0), end_ms = ((it.end_ms * k).toLong() + off).coerceAtLeast(0)) }
        return s.copy(intro = f(s.intro), recap = f(s.recap), outro = f(s.outro))
    }

    private fun lkey(show: String, kind: String) = "introOff2_${show}_$kind"
    private fun path(show: String, kind: String) = "${CloudConfig.DB_URL}/xshared/intro2/${show.replace(Regex("[^a-zA-Z0-9]"), "")}/$kind"

    /** The correction for this show + copy: this device's own, else what at least two people agreed on. */
    suspend fun offset(vm: AppViewModel, show: String, kind: String): Long = withContext(Dispatchers.IO) {
        vm.store(lkey(show, kind))?.toLongOrNull()?.let { return@withContext it }
        runCatching {
            val t = Cloud.token() ?: return@runCatching 0L
            val body = Api.get("${path(show, kind)}.json?auth=$t")
            if (body.isBlank() || body.trim() == "null") return@runCatching 0L
            val v = Api.json.parseToJsonElement(body).jsonObject.values.mapNotNull { (it as? JsonObject)?.get("o")?.jsonPrimitive?.longOrNull }
                .filter { kotlin.math.abs(it) <= 60_000 }.sorted()
            if (v.size >= 2) v[v.size / 2] else 0L
        }.getOrDefault(0L)
    }

    /** Saves a correction of [deltaMs] on top of [current] (here, and shared). Returns the new correction. */
    fun learn(vm: AppViewModel, show: String, kind: String, current: Long, deltaMs: Long): Long {
        val v = (current + deltaMs).coerceIn(-60_000, 60_000)
        vm.storePut(lkey(show, kind), v.toString())
        scope.launch {
            runCatching {
                val t = Cloud.token() ?: return@runCatching
                val uid = Cloud.user.value?.uid ?: return@runCatching
                Api.http.newCall(Request.Builder().url("${path(show, kind)}/$uid.json?auth=$t")
                    .put("""{"o":$v,"at":${System.currentTimeMillis()}}""".toRequestBody("application/json".toMediaType())).build()).execute().close()
            }
        }
        return v
    }
}
