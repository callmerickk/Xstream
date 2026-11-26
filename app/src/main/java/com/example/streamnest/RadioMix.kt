package com.example.streamnest

import kotlin.random.Random

/**
 * Switch: Feature.musicFix1
 * Radio order for a song: tapped song first, next 2 songs stay (best matches),
 * the rest is shuffled with a fresh seed each time.
 * Every second start of the same song also mixes in a "Discover" / "Deep cuts" list.
 * Memory only: last 20 song ids and how often their radio started.
 */
object RadioMix {
    class Mix(val songs: List<YtmItem>, val flavour: String, val extra: YtmNext?)

    private val starts = LinkedHashMap<String, Int>()

    private fun noteStart(id: String): Int = synchronized(starts) {
        val c = (starts.remove(id) ?: 0) + 1
        starts[id] = c
        while (starts.size > 20) starts.remove(starts.keys.first())
        c
    }

    private fun fresh() = Random(System.nanoTime())

    /** Keep the first [keep] songs in place, shuffle everything after them. */
    fun shuffleFrom(list: List<YtmItem>, keep: Int): List<YtmItem> =
        list.take(keep) + list.drop(keep).shuffled(fresh())

    /** Picks a Discover or Deep cuts chip at random, if the first answer has one. */
    private fun flavourChip(chips: List<Pair<String, String>>): Pair<String, String>? {
        val found = chips.filter {
            it.first.contains("discover", true) || it.first.contains("deep cut", true)
        }
        return if (found.isEmpty()) null else found.random(fresh())
    }

    /**
     * [first] is the normal answer for the tapped song. The tapped song is removed
     * because it is already playing. The first 2 remaining songs are positions 2 and 3.
     */
    suspend fun build(songId: String, first: YtmNext): Mix {
        val count = noteStart(songId)
        val main = first.queue.filter { it.id != songId }
        val head = main.take(2)
        val tail = main.drop(2).shuffled(fresh())

        var flavour = "none"
        var extra: YtmNext? = null
        var fl: List<YtmItem> = emptyList()

        if (count % 2 == 0) {                       // every second start of the same song
            val pick = flavourChip(first.chips)
            if (pick != null) {
                val pl = pick.second.substringBefore('|').ifBlank { "RDAMVM$songId" }
                val pr = pick.second.substringAfter('|', "").ifBlank { null }
                val e = runCatching { YtMusic.next(songId, pl, pr) }.getOrNull()
                if (e != null) {
                    val headIds = head.map { it.id }.toSet()
                    fl = e.queue.filter { it.id != songId && it.id !in headIds }.shuffled(fresh())
                    if (fl.isNotEmpty()) {
                        extra = e
                        flavour = if (pick.first.contains("discover", true)) "Discover" else "Deep cuts"
                    }
                }
            }
        }

        // alternate one from the main list, one from the flavour list, no duplicates
        val out = LinkedHashMap<String, YtmItem>()
        head.forEach { out[it.id] = it }
        for (k in 0 until maxOf(tail.size, fl.size)) {
            tail.getOrNull(k)?.let { out.putIfAbsent(it.id, it) }
            fl.getOrNull(k)?.let { out.putIfAbsent(it.id, it) }
        }
        return Mix(out.values.toList(), flavour, extra)
    }
}
