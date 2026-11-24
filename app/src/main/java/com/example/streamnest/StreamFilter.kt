package com.example.streamnest

import java.util.concurrent.ConcurrentHashMap

/**
 * The "SX|…" line of the StreamX formatter (PenguPlay / AIOStreams custom formatter): exact facts about a stream
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
    /** 0.9.2: each film's name (from the same look-up as its year), for the file-name check. */
    private val names = ConcurrentHashMap<String, String>()
    private val yearRx = Regex("(?<![0-9])(19[2-9][0-9]|20[0-4][0-9])(?![0-9])")
    private val epRx = Regex("(?i)(?<![a-z0-9])s(\\d{1,2})[ ._-]?e(\\d{1,3})(?![0-9])")
    private val rangeRx = Regex("(?i)s\\d{1,2}[ ._-]?e\\d{1,3}[ ._-]*(-|to|~)[ ._-]*e?\\d{1,3}")

    fun noteMeta(m: Meta) {
        m.name.takeIf { it.isNotBlank() }?.let { names[m.id.substringBefore(":")] = it }
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
            val want = years[parts[0]]
            if (want != null) {
                val got = sx["y"]?.toIntOrNull()?.let { listOf(it) } ?: yearRx.findAll(file).mapNotNull { it.value.toIntOrNull() }.toList()
                if (got.isNotEmpty() && got.none { kotlin.math.abs(it - want) <= 1 }) return true
            }
            // 0.9.2 (fileNameCheck): the addon may name the link after the film asked for while the real file is another one.
            if (Feature.fileNameCheck) fileNameProblem(s, parts[0])?.let { name ->
                if (logged.add(s.stream.url.orEmpty()) || logged.size > 500) { if (logged.size > 500) logged.clear(); ActLog.add("PLAY", "wrong title (file name) · ${s.addonName} · ${name.take(120)}") }
                return true
            }
            return false
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

    private val logged: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** 0.9.2: a year standing on its own in a name ("Dhamaal.2007.720p", "(2023)"), never inside an id like "aB2015cD". */
    private val nameYearRx = Regex("(?<![A-Za-z0-9])(19[2-9][0-9]|20[0-4][0-9])(?![A-Za-z0-9])")
    private val stop = setOf("the", "and", "a", "an", "of", "part", "movie", "film", "hindi", "dubbed", "english", "tamil", "telugu",
        "malayalam", "kannada", "dual", "audio", "org", "www", "com", "in", "on", "to", "la", "le", "de", "ii", "iii")
    private val junkRx = Regex("(?i)\\b(\\d{3,4}p|4k|uhd|hdr|hdrip|webrip|web|dl|bluray|brrip|dvdrip|predvd|hdtc|x26[45]|h26[45]|hevc|aac|ddp?5|esub|esubs|mkv|mp4)\\b.*")

    /** 0.9.2: the names this link really carries: its web address's last part(s), decoded (true), and the file-name hint (false). */
    private fun realNames(s: StreamItem): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        val path = s.stream.url?.substringBefore('?')?.substringBefore('#')?.substringAfter("://", "")?.substringAfter('/', "").orEmpty()
        val segs = path.split('/').filter { it.isNotBlank() }
        fun dec(x: String) = runCatching { java.net.URLDecoder.decode(x.replace("+", "%2B"), "UTF-8") }.getOrDefault(x).replace('+', ' ')
        // The last part; when it is only "index.m3u8" / "video.mp4" / an id, the one before it.
        segs.lastOrNull()?.let { out += dec(it) to true }
        if (segs.size >= 2 && out.firstOrNull()?.let { nameYearRx.containsMatchIn(it.first) } != true) out += dec(segs[segs.size - 2]) to true
        s.stream.behaviorHints?.filename?.takeIf { it.isNotBlank() }?.let { out += it to false }
        // Only parts that read like a name with a year ("Non Stop Dhamaal 2023 …"), not bare numbers or ids.
        return out.filter { (n, _) -> nameYearRx.containsMatchIn(n) && Regex("[A-Za-z]{3,}").containsMatchIn(n) }
    }

    private fun words(x: String): List<String> = x.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().split(' ')
        .filter { it.length >= 3 && it !in stop && it.toIntOrNull() == null }

    /** Same title, allowing spelling (Dhamal / Dhamaal), joined words (Spiderman / Spider-Man) and extra words (Non Stop Dhamaal). */
    private fun sameTitle(want: String, got: String): Boolean {
        val w = words(want); val g = words(got)
        if (w.isEmpty() || g.isEmpty()) return true
        val ws = w.joinToString(""); val gs = g.joinToString("")
        if (ws in gs || gs in ws) return true
        fun near(a: String, b: String): Boolean {
            if (a == b || (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a)))) return true
            if (kotlin.math.abs(a.length - b.length) > 2) return false
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) { var prev = d[0]; d[0] = i
                for (j in 1..b.length) { val t = d[j]; d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1); prev = t } }
            return d[b.length] <= maxOf(1, minOf(a.length, b.length) / 4)
        }
        return w.any { a -> g.any { b -> near(a, b) } }
    }

    /**
     * 0.9.2: a film link whose real file name shows another film: the decoded name (for the log), else null.
     * Another 4-digit year (±1 allowed) in the web address's name or the file-name hint; or the link is labelled with the
     * film's own name while the name in its web address (before the year) shares no word with it — the relabelled-file
     * case (a foreign film simply named in its own language is not labelled with the English name, so it is kept).
     * Unsure (no name with a year, film year / name unknown) = keep.
     */
    fun fileNameProblem(s: StreamItem, imdb: String, wantYear: Int? = null, wantName: String? = null): String? {
        val k = imdb.substringBefore(":")
        val y = wantYear ?: years[k]
        val title = wantName ?: names[k]
        val label = listOfNotNull(s.stream.behaviorHints?.filename, s.stream.title, s.stream.description).joinToString(" ")
        val relabelled = title != null && label.isNotBlank() && words(title).isNotEmpty() && sameTitle(title, label)
        for ((n, fromUrl) in realNames(s)) {
            val ys = nameYearRx.findAll(n).mapNotNull { it.value.toIntOrNull() }.toList()
            if (y != null && ys.isNotEmpty() && ys.none { kotlin.math.abs(it - y) <= 1 }) return n
            if (fromUrl && relabelled) {
                val head = n.substring(0, nameYearRx.find(n)?.range?.first ?: n.length).replace(junkRx, "")
                if (words(head).size >= 1 && !sameTitle(title!!, head)) return n
            }
        }
        return null
    }
}
