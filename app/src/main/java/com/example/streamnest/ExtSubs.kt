package com.example.streamnest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** One line of an online subtitle file. */
data class ExtCue(val start: Long, val end: Long, val text: String)

/** An online subtitle shown by Xstream itself (no reload of the video; its timing can be moved). */
data class ExtActive(val sub: Subtitle, val cues: List<ExtCue>)

/**
 * Online subtitles (SRT / WebVTT) downloaded and read by the app, then drawn over the video in step with it. Adding one
 * never reloads the video, the timing can be moved by hand, and the first few in your language are downloaded ahead.
 */
object ExtSubs {
    private val cache = ConcurrentHashMap<String, List<ExtCue>>()
    private val timeRx = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})[,.](\\d{1,3})")

    fun has(url: String) = cache.containsKey(url)

    suspend fun load(url: String): List<ExtCue>? = withContext(Dispatchers.IO) {
        cache[url]?.let { return@withContext it }
        val text = runCatching { Api.get(url) }.getOrNull() ?: return@withContext null
        val cues = parse(text)
        if (cues.isEmpty()) null else cues.also { cache[url] = it }
    }

    private fun ms(m: MatchResult): Long {
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val mi = m.groupValues[2].toLong(); val s = m.groupValues[3].toLong()
        val frac = m.groupValues[4].padEnd(3, '0').take(3).toLong()
        return ((h * 60 + mi) * 60 + s) * 1000 + frac
    }

    fun parse(raw: String): List<ExtCue> {
        val t = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val out = ArrayList<ExtCue>()
        for (block in t.split(Regex("\n\\s*\n"))) {
            val lines = block.trim().lines()
            val ti = lines.indexOfFirst { it.contains("-->") }
            if (ti < 0) continue
            val parts = lines[ti].split("-->")
            val a = timeRx.find(parts[0]) ?: continue
            val b = timeRx.find(parts.getOrElse(1) { "" }) ?: continue
            val body = lines.drop(ti + 1).joinToString("\n")
                .replace(Regex("<[^>]+>"), "").replace(Regex("\\{\\\\[^}]*\\}"), "").trim()
            if (body.isNotEmpty()) out += ExtCue(ms(a), ms(b), body)
        }
        return out.sortedBy { it.start }
    }

    /** The lines on screen at [t] (binary search, then the few around it). */
    fun at(cues: List<ExtCue>, t: Long): List<ExtCue> {
        if (cues.isEmpty()) return emptyList()
        var lo = 0; var hi = cues.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (cues[mid].start <= t) lo = mid else hi = mid - 1 }
        val from = (lo - 3).coerceAtLeast(0); val to = (lo + 1).coerceAtMost(cues.size - 1)
        return (from..to).map { cues[it] }.filter { t >= it.start && t <= it.end }
    }
}
