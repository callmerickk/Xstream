package com.example.streamnest

/**
 * "No streams" reports for the developer. Every plugin's answer for a title is remembered for a few minutes
 * (key: name + season + episode); when that title ends with no picture (no stream found, every source failed),
 * one NOSTREAM block goes into the activity log, which the cloud log upload carries to the developer's log section.
 */
object NoStream {
    private data class Note(val at: Long, val text: String)
    private val notes = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Note>>()
    private val reported = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun key(name: String, season: Int?, episode: Int?) = name.lowercase().trim() + (if (season != null) "|S$season" else "") + (if (episode != null) "E$episode" else "")

    fun note(key: String, source: String, text: String) {
        val m = notes.getOrPut(key) { java.util.concurrent.ConcurrentHashMap() }
        m[source] = Note(System.currentTimeMillis(), text.take(120))
        if (notes.size > 60) notes.entries.sortedBy { e -> e.value.values.maxOfOrNull { it.at } ?: 0L }.take(20).forEach { notes.remove(it.key) }
    }

    /** One report per title per 10 minutes. [why]: what the user saw. */
    fun report(title: String, key: String?, why: String, tried: List<Pair<String, String>> = emptyList()) {
        val k = (key ?: title).lowercase()
        val now = System.currentTimeMillis()
        if ((reported[k] ?: 0L) > now - 600_000L) return
        reported[k] = now
        val dev = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} · ${BuildConfig.VERSION_NAME}"
        val plug = notes[key ?: ""]?.entries?.sortedBy { it.key }?.joinToString(" | ") { "${it.key}: ${it.value.text}" }.orEmpty()
        val tr = tried.joinToString(" | ") { "${it.first}: ${it.second}" }
        ActLog.add("NOSTREAM", "$title · $why · $dev" + (if (plug.isNotEmpty()) " ‖ plugins: $plug" else "") + (if (tr.isNotEmpty()) " ‖ tried: $tr" else ""))
    }

    /** Plain words for why a link didn't play (shown in reports and the source health page). */
    fun reason(code: String, http: Int?, mime: String?): String = when {
        http == 403 && mime?.contains("html") == true -> "site protection blocked it (403)"
        http == 403 -> "link expired or needs the site's referrer (403)"
        http == 404 -> "file gone (404)"
        http == 429 -> "too many requests (429)"
        http == 206 || http == 200 -> if (code.contains("BAD_HTTP")) "server answered oddly (wrong byte range or type) — player issue" else "too slow to start (timeout)"
        code.contains("TIMEOUT") -> "too slow to start (timeout)"
        code.contains("NETWORK") -> "network failed"
        code.contains("DECOD") || code.contains("FORMAT") -> "format the player can't decode"
        else -> code.lowercase().replace('_', ' ')
    }
}
