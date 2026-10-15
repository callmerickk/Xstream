package com.example.streamnest

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * Addon stream requests, shared: while one request for a title is on its way, everyone else (title page, player,
 * upgrade search, Sources) waits for that same answer instead of sending their own; a good answer is reused for 45 s.
 * Fewer requests = the addon's server doesn't start refusing us ("too many requests").
 * The quick-play addon gets one more try after a short pause when it answers nothing or refuses (a busy moment).
 */
object AddonCalls {
    private val inFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<StreamInfo>>>()
    private val recent = ConcurrentHashMap<String, Pair<Long, List<StreamInfo>>>()
    /** The request itself runs here, not in the caller: leaving the title page (which cancels its own work) never
     *  cancels a request the player is waiting for too. */
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    suspend fun streams(url: String, retry: Boolean): List<StreamInfo> {
        recent[url]?.let { (at, l) -> if (System.currentTimeMillis() - at < 45_000 && l.isNotEmpty()) return l }
        val d = inFlight.computeIfAbsent(url) {
            scope.async {
                try {
                    fetch(url, retry).also { l -> if (l.isNotEmpty()) recent[url] = System.currentTimeMillis() to l }
                } finally { scope.launch { inFlight.remove(url) } }
            }
        }
        return d.await()
    }

    private suspend fun fetch(url: String, retry: Boolean): List<StreamInfo> {
        val first = runCatching { Api.fetch<StreamResponse>(url).streams }
        val l = first.getOrNull()
        if (!retry || (l != null && l.isNotEmpty())) return l ?: throw first.exceptionOrNull()!!
        // Busy (429) → wait a little longer; empty / other error → a short pause. Then once more.
        val busy = first.exceptionOrNull()?.message?.contains("429") == true
        delay(if (busy) 2_500 else 1_200)
        return runCatching { Api.fetch<StreamResponse>(url).streams }.getOrNull() ?: l ?: throw first.exceptionOrNull()!!
    }

    /** A title's links failed / were refused: the next request asks the server again. */
    fun forgetTitle(id: String) { val k = id.substringBefore(':'); recent.keys.removeIf { it.contains(k) } }
}
