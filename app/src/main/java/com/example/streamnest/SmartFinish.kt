package com.example.streamnest

import java.util.concurrent.ConcurrentHashMap

/** 0.9.7 (smartFinish): "is this title watched?" and the credits times the player already fetched. */
object SmartFinish {
    private val credits = ConcurrentHashMap<String, Long>()
    private fun prefs() = Public.vm?.appContext?.getSharedPreferences("smartfinish", android.content.Context.MODE_PRIVATE)

    /** The player got the skip times: the credits start of this title / episode is kept (at most ~200). */
    fun noteCredits(key: String, startMs: Long) {
        if (startMs <= 0 || credits[key] == startMs) return
        credits[key] = startMs
        val p = prefs() ?: return
        val e = p.edit().putLong(key, startMs)
        if (p.all.size > 200) p.all.keys.take(50).forEach { e.remove(it) }
        e.apply()
    }
    fun credits(key: String): Long? {
        credits[key]?.let { return it }
        val v = prefs()?.getLong(key, 0L) ?: 0L
        return v.takeIf { it > 0 }?.also { credits[key] = it }
    }

    /** Credits started (time known and sane) → 92 % → at most 8 min left of a film of an hour or more / 3 min of an episode. */
    fun done(p: Progress, series: Boolean): Boolean {
        val d = p.durationMs; val pos = p.positionMs
        if (d <= 0 || pos <= 0) return false
        val cr = credits(p.key)
        if (cr != null && cr >= d * 0.5 && cr < d && pos >= cr) return true
        if (pos >= d * (if (series) 0.95 else 0.92)) return true
        val rem = d - pos
        return if (series) rem <= 3 * 60_000L else (d >= 60 * 60_000L && rem <= 8 * 60_000L)
    }
}
