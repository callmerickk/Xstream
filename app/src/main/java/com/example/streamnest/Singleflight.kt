package com.example.streamnest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.concurrent.ConcurrentHashMap

/** 0.9.7: the second caller with the same key waits for the first one's answer instead of asking again. */
object Singleflight {
    private val flights = ConcurrentHashMap<String, CompletableDeferred<Any?>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> run(key: String, block: suspend () -> T): T {
        val mine = CompletableDeferred<Any?>()
        val other = flights.putIfAbsent(key, mine)
        if (other != null) {
            try { return other.await() as T } catch (e: CancellationException) { if (!currentCoroutineContext().isActive) throw e }
            return block()   // the first caller was cancelled, we are not: ask ourselves
        }
        try {
            val v = block(); mine.complete(v); return v
        } catch (e: Throwable) {
            mine.completeExceptionally(e); throw e
        } finally { flights.remove(key, mine) }
    }
}
