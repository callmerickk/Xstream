package com.example.streamnest

import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 0.9.7 (dataDiet): MovieBox plugin answers per title / episode kept 30 min, in memory and in a small file, so the
 * ladder, warm searches and the title page reuse one answer instead of asking MovieBox again (links carry their
 * captions). Identical asks at the same time share one call (Singleflight). Failures are never kept; an empty answer
 * only 30 s. MovieBox links stay valid ~4 h, so 30 min is safe.
 */
object MovieBoxCache {
    private const val TTL = 30 * 60_000L
    private const val EMPTY_TTL = 30_000L
    @Serializable private data class Row(val addon: String, val stream: StreamInfo)
    @Serializable private data class Blob(val at: Long, val rows: List<Row>)
    private class Hit(val at: Long, val list: List<StreamItem>)
    private val mem = ConcurrentHashMap<String, Hit>()

    private fun dir(): File? = Public.vm?.appContext?.let { File(it.noBackupFilesDir, "mb-links").apply { mkdirs() } }
    private fun file(key: String) = dir()?.let { File(it, Integer.toHexString(key.hashCode()) + "_" + key.length + ".json") }
    private fun fresh(h: Hit) = System.currentTimeMillis() - h.at < (if (h.list.isEmpty()) EMPTY_TTL else TTL)

    private fun lookup(key: String): List<StreamItem>? {
        mem[key]?.let { if (fresh(it)) return it.list else mem.remove(key) }
        val f = file(key)?.takeIf { it.exists() } ?: return null
        val b = runCatching { Api.json.decodeFromString(Blob.serializer(), f.readText()) }.getOrNull() ?: return null
        if (System.currentTimeMillis() - b.at >= TTL || b.rows.isEmpty()) { runCatching { f.delete() }; return null }
        val list = b.rows.map { StreamItem(it.addon, it.stream).also { s -> StreamHeaders.remember(s) } }
        mem[key] = Hit(b.at, list)
        return list
    }

    private fun store(key: String, list: List<StreamItem>) {
        val now = System.currentTimeMillis()
        mem[key] = Hit(now, list)
        if (mem.size > 60) mem.entries.sortedBy { it.value.at }.take(mem.size - 40).forEach { mem.remove(it.key) }
        if (list.isEmpty()) return
        val f = file(key) ?: return
        runCatching {
            f.writeText(Api.json.encodeToString(Blob.serializer(), Blob(now, list.map { Row(it.addonName, it.stream) })))
            f.parentFile?.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
        }
    }

    suspend fun links(key: String, fetch: suspend () -> List<StreamItem>): List<StreamItem> {
        runCatching { lookup(key) }.getOrNull()?.let { ActLog.add("MBCACHE", "hit · $key · ${it.size} links"); return it }
        return Singleflight.run("mb|$key") {
            runCatching { lookup(key) }.getOrNull() ?: fetch().also { l -> runCatching { store(key, l) }; ActLog.add("MBCACHE", "miss · $key · ${l.size} links") }
        }
    }
}
