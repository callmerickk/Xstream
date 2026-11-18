package com.example.streamnest

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Lesson from the Live server: search once, remember it. The links a title page got ready (or Home looked up ahead) are
 * kept on the phone for a few hours; the next time the title opens they are checked first (one tiny request) and the
 * light turns green at once, while a fresh search runs behind and replaces them.
 */
object LinkMemory {
    @Serializable private data class Saved(val at: Long, val items: List<Item>)
    @Serializable private data class Item(val addon: String, val stream: StreamInfo)

    private const val TTL = 4 * 3600_000L
    private val mem = ConcurrentHashMap<String, Pair<Long, List<StreamItem>>>()
    private fun file(ctx: Context, id: String) = File(ctx.cacheDir, "linkmem/" + id.hashCode().toUInt().toString(16) + ".json")

    fun get(ctx: Context, id: String): List<StreamItem>? {
        val now = System.currentTimeMillis()
        mem[id]?.let { (at, l) -> if (now - at < TTL) return l }
        val f = file(ctx, id)
        if (!f.exists() || now - f.lastModified() > TTL) return null
        val s = runCatching { Api.json.decodeFromString<Saved>(f.readText()) }.getOrNull() ?: return null
        if (now - s.at > TTL) return null
        val l = s.items.map { StreamItem(it.addon, it.stream) }.filter { !it.stream.url.isNullOrBlank() && !it.stream.url!!.startsWith("http://127.0.0.1") }
        if (l.isNotEmpty()) mem[id] = s.at to l
        return l.ifEmpty { null }
    }

    fun put(ctx: Context, id: String, list: List<StreamItem>) {
        val l = list.filter { !it.stream.url.isNullOrBlank() && !it.stream.url!!.startsWith("http://127.0.0.1") }.distinctBy { it.stream.url }.take(12)
        if (l.isEmpty()) return
        val now = System.currentTimeMillis()
        mem[id] = now to l
        runCatching {
            val f = file(ctx, id); f.parentFile?.mkdirs()
            f.writeText(Api.json.encodeToString(Saved.serializer(), Saved(now, l.map { Item(it.addonName, it.stream) })))
        }
    }

    /** Home looks ahead: the first cards (and Pick up where you left off) are looked up two at a time, quietly. */
    private val ahead = Semaphore(2)
    private val asked = ConcurrentHashMap.newKeySet<String>()
    suspend fun lookAhead(vm: AppViewModel, type: String, id: String) {
        if (BuildConfig.IS_TV || BuildConfig.LIVE_ONLY || !asked.add(id) || get(vm.appContext, id) != null) return
        ahead.withPermit {
            val l = withContext(Dispatchers.IO) { runCatching { vm.quickStreams(type, id) }.getOrDefault(emptyList()) }
            if (l.isNotEmpty()) put(vm.appContext, id, l)
        }
    }
}
