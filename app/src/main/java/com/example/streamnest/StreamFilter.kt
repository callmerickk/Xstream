package com.example.streamnest

import java.util.concurrent.ConcurrentHashMap

/**
 * The "SX|…" line of the Xstream formatter (PenguPlay / AIOStreams custom formatter): exact facts about a stream
 * (title, year, season, episode, resolution, language codes, size in bytes, length). Hidden in Sources.
 */
fun StreamItem.sx(): Map<String, String> {
    val line = listOfNotNull(stream.description, stream.title).joinToString("\n").lineSequence()
        .map { it.trim() }.firstOrNull { it.startsWith("SX|") } ?: return emptyMap()
    return line.removePrefix("SX|").split('|').mapNotNull { kv ->
        val k = kv.substringBefore('=', "").trim(); val v = kv.substringAfter('=', "").trim()
        if (k.isEmpty() || v.isEmpty() || v.equals("undefined", true) || v.equals("null", true) || v.equals("unknown", true)) null else k to v
    }.toMap()
}

/** The file name a stream names (behaviour hint, else the 📁 line / any line that looks like a file). */
fun StreamItem.fileName(): String {
    stream.behaviorHints?.filename?.takeIf { it.isNotBlank() }?.let { return it }
    val lines = listOfNotNull(stream.title, stream.description).joinToString("\n").lines().map { it.trim() }
    return lines.firstOrNull { it.startsWith("📁") }?.removePrefix("📁")?.trim()
        ?: lines.firstOrNull { Regex("\\.(mkv|mp4|avi|mpd|m3u8|webm|ts)(\\b|$)", RegexOption.IGNORE_CASE).containsMatchIn(it) }.orEmpty()
}

/**
 * Wrong-title links: some addons answer a search by name, so "Dune" (2021) also returned Dune: Prophecy S01E01 and
 * Dune: Part Two (2024). Those are dropped before anything is shown or raced.
 */
object TitleMatch {
    private val years = ConcurrentHashMap<String, Int>()
    private val yearRx = Regex("(?<![0-9])(19[2-9][0-9]|20[0-4][0-9])(?![0-9])")
    private val epRx = Regex("(?i)(?<![a-z0-9])s(\\d{1,2})[ ._-]?e(\\d{1,3})(?![0-9])")
    private val rangeRx = Regex("(?i)s\\d{1,2}[ ._-]?e\\d{1,3}[ ._-]*(-|to|~)[ ._-]*e?\\d{1,3}")

    fun noteMeta(m: Meta) {
        val y = m.releaseInfo?.let { yearRx.find(it)?.value?.toIntOrNull() } ?: return
        years[m.id.substringBefore(":")] = y
    }

    /** Looks up a film's year once (Cinemeta) when no screen has given it yet. */
    suspend fun ensureYear(type: String, id: String) {
        val k = id.substringBefore(":")
        if (type != "movie" || !k.startsWith("tt") || years.containsKey(k)) return
        runCatching { Api.fetch<MetaResponse>("$CINEMETA/meta/movie/$k.json").meta }.getOrNull()?.let { noteMeta(it) }
    }

    /** Is this link for another title / episode than [id]? Unsure = keep. */
    fun wrong(s: StreamItem, type: String, id: String): Boolean {
        val sx = s.sx()
        val file = s.fileName()
        val parts = id.split(":")
        if (type == "movie") {
            // An episode file for a film.
            if (!sx["e"].isNullOrBlank() || (file.isNotEmpty() && epRx.containsMatchIn(file))) return true
            val want = years[parts[0]] ?: return false
            val got = sx["y"]?.toIntOrNull()?.let { listOf(it) } ?: yearRx.findAll(file).mapNotNull { it.value.toIntOrNull() }.toList()
            return got.isNotEmpty() && got.none { kotlin.math.abs(it - want) <= 1 }
        }
        if (parts.size >= 3) {
            val ws = parts[1].toIntOrNull() ?: return false
            val we = parts[2].toIntOrNull() ?: return false
            val sxS = sx["s"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
            val sxE = sx["e"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
            if (sxS.isNotEmpty() && sxE.isNotEmpty()) return ws !in sxS || we !in sxE
            if (file.isEmpty() || rangeRx.containsMatchIn(file)) return false
            val all = epRx.findAll(file).toList()
            // An episode asked for, and the file is a whole film ("Supergirl (2026) 2160p … .mkv"): no episode mark,
            // no season / episode word, a video file name — another title.
            if (all.isEmpty() && Regex("(?i)\\.(mkv|mp4|avi|webm|m4v)$").containsMatchIn(file) &&
                !Regex("(?i)season|episode|\\bep\\.?\\s*\\d|\\b\\d{1,2}x\\d{1,3}\\b|complete").containsMatchIn(file)) return true
            if (all.size != 1) return false
            val m = all[0]
            return m.groupValues[1].toIntOrNull() != ws || m.groupValues[2].toIntOrNull() != we
        }
        return false
    }
}
