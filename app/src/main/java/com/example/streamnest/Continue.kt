package com.example.streamnest

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * The badge in the top-right corner of a Continue Watching card:
 *  LEFT  — "1h 50m left" while you're in the middle of something,
 *  NEXT  — "Next · S5 E12" once an episode is (almost) finished and the next one is out,
 *  AIRS  — "New episode in 4 days" / "Tomorrow" / "Today" when you've caught up with a show that's still airing,
 *  NONE  — finished the last episode there is.
 */
data class CwBadge(val kind: Int, val text: String, val nextId: String? = null) {
    companion object { const val LEFT = 0; const val NEXT = 1; const val AIRS = 2; const val NONE = 3 }
}

private object SeriesCache { val map = ConcurrentHashMap<String, Pair<Long, Meta?>>() }

/** A series' episode list (from your addons), cached for 6 hours. */
suspend fun AppViewModel.seriesMeta(id: String): Meta? {
    SeriesCache.map[id]?.let { (at, m) -> if (System.currentTimeMillis() - at < 6 * 3600_000L) return m }
    val m = runCatching { fullMeta(Meta(id = id, type = "series")) }.getOrNull()
    SeriesCache.map[id] = System.currentTimeMillis() to m
    return m
}

fun Progress.fraction(): Float = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
fun Progress.isSeries(): Boolean = key.contains(":")
/** An episode watched to (about) the end — Continue Watching then points at the next one. */
fun Progress.finished(): Boolean = isSeries() && fraction() >= 0.9f

fun cwTimeLeft(p: Progress): String {
    val left = ((p.durationMs - p.positionMs).coerceAtLeast(0L) / 60_000L).toInt()
    return when {
        left >= 60 -> "${left / 60}h ${left % 60}m left"
        left > 0 -> "${left}m left"
        else -> "Almost done"
    }
}

private fun releasedMs(s: String?): Long? {
    val d = s?.take(10) ?: return null
    return runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(d)?.time }.getOrNull()
}

/** Works out the badge (looks the show's episodes up only for finished episodes). */
suspend fun AppViewModel.cwBadge(p: Progress): CwBadge {
    if (!p.finished()) return CwBadge(CwBadge.LEFT, cwTimeLeft(p))
    val base = p.key.substringBefore(":")
    val meta = seriesMeta(base) ?: return CwBadge(CwBadge.LEFT, "Watched")
    val eps = meta.videos.orEmpty().filter { it.season != null && it.episode != null && (it.season ?: 0) > 0 }
        .sortedWith(compareBy({ it.season }, { it.episode }))
    val i = eps.indexOfFirst { it.id == p.key }
    val next = if (i >= 0) eps.getOrNull(i + 1) else null
    if (next == null) return CwBadge(CwBadge.NONE, "Watched")
    val rel = releasedMs(next.released)
    val now = System.currentTimeMillis()
    if (rel != null && rel > now) {
        val days = ((rel - now) / 86_400_000L).toInt() + 1
        val txt = when {
            days <= 1 -> "New episode tomorrow"
            else -> "New episode in $days days"
        }
        return CwBadge(CwBadge.AIRS, txt, next.id)
    }
    return CwBadge(CwBadge.NEXT, "Next · S${next.season} E${next.episode}", next.id)
}

/** The title behind a Continue Watching entry (to open its page). */
fun Progress.asMeta(): Meta = Meta(
    id = key.substringBefore(":"), type = if (isSeries()) "series" else "movie",
    name = title.substringBefore("  S").trim(), poster = image, background = image,
)
