package com.example.streamnest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Prompt 7 (Feature.accessDevices): listens for a change of THIS phone's record, access/devices/<uid>/<hash>,
 * so a Grant or Revoke shows at once (no waiting for the 30 s check).
 * The event stream sends the current value first; that first event is skipped, every later one calls [onChange].
 * Runs until its coroutine is cancelled (the caller cancels it when the account changes or the gate leaves the screen).
 */
object AccessWatch {
    // Same client as the rest of the app, but with no read time-out: the stream is meant to stay quiet for a long time.
    private val client by lazy { Api.http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build() }

    suspend fun run(uid: String, onChange: suspend () -> Unit) {
        var wait = 2_000L
        while (true) {
            val started = System.currentTimeMillis()
            runCatching { streamOnce(uid, onChange) }
            // A connection that lasted a while starts again quickly; one that dies at once waits longer each time.
            val lasted = System.currentTimeMillis() - started
            wait = if (lasted > 20_000L) 2_000L else (wait * 2).coerceAtMost(60_000L)
            delay(wait)
        }
    }

    private suspend fun streamOnce(uid: String, onChange: suspend () -> Unit): Unit = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext
        val req = Request.Builder()
            .url("${CloudConfig.DB_URL}/access/devices/$uid/${AccessDevices.hash(uid)}.json?auth=$t")
            .header("Accept", "text/event-stream")
            .build()
        val call = client.newCall(req)
        // A blocking read does not notice a coroutine being cancelled, so cancel the call by hand.
        val reg = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) return@use
                val src = r.body?.source() ?: return@use
                ActLog.add("APP", "access: listening for changes to this device")
                var first = true
                var event = ""
                while (true) {
                    val line = src.readUtf8Line() ?: break
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim()
                    } else if (line.startsWith("data:")) {
                        if (event == "put" || event == "patch") {
                            if (first) first = false else onChange()
                        } else if (event == "cancel" || event == "auth_revoked") {
                            // The sign-in token ran out (about every hour) or the rules refused: end, run() reconnects with a fresh token.
                            break
                        }
                    }
                }
            }
        } finally {
            reg?.dispose()
        }
    }
}
