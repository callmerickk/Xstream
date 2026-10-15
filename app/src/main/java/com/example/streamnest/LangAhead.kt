package com.example.streamnest

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * MovieBox gives each dub as its own stream. While one plays, the other languages you rank highest (Hindi / English /
 * Original) get their start saved quietly, and when Audio & Subtitles opens, the part at the current time too — so
 * changing language feels like changing an audio track.
 */
object LangAhead {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var aroundJob: Job? = null
    /** Streams whose start is saved on the phone. */
    val saved: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun start(ctx: Context, others: List<StreamItem>) {
        job?.cancel()
        val list = others.mapNotNull { it.stream.url?.takeIf { u -> u.startsWith("http") } }.distinct().take(2)
        if (list.isEmpty()) return
        job = scope.launch {
            for (u in list) {
                val len = if (StreamType.quick(u)?.isNotEmpty() == true) 256L * 1024 else 2L * 1024 * 1024
                if (StreamCache.saveParts(ctx, u, 0, len, 1, null) { } == null) saved += u
            }
        }
    }

    /** The other languages' video at [posMs] (plain files: by their size and length), about 3 MB each. */
    fun around(ctx: Context, others: List<StreamItem>, posMs: Long, durMs: Long) {
        if (durMs <= 0) return
        aroundJob?.cancel()
        val list = others.mapNotNull { it.stream.url?.takeIf { u -> u.startsWith("http") && StreamType.quick(u)?.isNotEmpty() != true } }.distinct().take(2)
        aroundJob = scope.launch {
            for (u in list) {
                val size = StreamCache.sizes[u] ?: StreamCache.probe(u).size.takeIf { it > 0 } ?: continue
                val at = (size.toDouble() * posMs / durMs).toLong() - 512L * 1024
                StreamCache.saveParts(ctx, u, at.coerceAtLeast(0), 3L * 1024 * 1024, 2, null) { }
            }
        }
    }

    fun stop() { job?.cancel(); aroundJob?.cancel() }
}
