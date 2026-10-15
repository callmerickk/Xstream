package com.example.streamnest

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/* "If you liked <title>": TMDB recommendations (topped up with /similar), optionally re-ranked by the AI. */

data class LikedPick(val rec: TmRec, val match: Int, val why: String)

private val candidateCache = HashMap<String, List<TmRec>>()
/** AI answers per title. An empty list = the AI was asked and failed (don't ask again). */
private val aiPickCache = HashMap<String, List<LikedPick>>()

suspend fun AppViewModel.likedCandidates(imdb: String, info: TmdbInfo): List<TmRec> {
    candidateCache[imdb]?.let { return it }
    var list = info.recs
    if (list.size < 6) list = (list + tmdbSimilar(info.kind, info.tmdbId)).distinctBy { it.kind + it.tmdbId }
    return list.filter { it.tmdbId != info.tmdbId }.take(20).also { if (it.isNotEmpty()) candidateCache[imdb] = it }
}

/** TMDB order; match from the TMDB score, reason = first shared genre. */
fun tmdbPicks(info: TmdbInfo, list: List<TmRec>): List<LikedPick> = list.map { r ->
    val shared = r.genreIds.firstOrNull { it in info.genreIds }?.let { TmdbGenres[it] }
    LikedPick(r, (70 + r.score * 3).toInt().coerceIn(60, 98), shared?.let { "Same genre: $it" } ?: "Fans also watched")
}

/** Asks the AI once per title to rank [cands]. null = no AI, or it failed (use [tmdbPicks]). */
suspend fun AppViewModel.aiLikedPicks(imdb: String, title: String, year: String?, type: String, cands: List<TmRec>): List<LikedPick>? {
    aiPickCache[imdb]?.let { return it.ifEmpty { null } }
    if (!aiReady() || cands.isEmpty()) return null
    val pool = cands.take(12)
    val kind = if (type == "series") "TV series" else "movie"
    val system = "You recommend movies and TV shows. Answer ONLY with JSON, no other text."
    val user = buildString {
        append("Someone liked the $kind \"$title\"${year?.let { " ($it)" } ?: ""}. ")
        append("Rank these candidates by how much that person would enjoy them, best first (up to 12):\n")
        pool.forEach { append("- ${it.title}${it.year?.let { y -> " ($y)" } ?: ""}\n") }
        append("Use the exact titles from the list. For each, give a match percentage (0-100) and a reason of at most 4 words.\n")
        append("Answer with JSON exactly like: {\"picks\":[{\"title\":\"...\",\"match\":95,\"why\":\"Same universe\"}]}")
    }
    val out = runCatching {
        val raw = aiChat(system, emptyList(), user)
        val arr = parsePicks(raw) ?: return@runCatching emptyList<LikedPick>()
        arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val t = (o["title"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return@mapNotNull null
            val rec = pool.firstOrNull { it.title.equals(t, ignoreCase = true) }
                ?: pool.firstOrNull { it.title.contains(t, ignoreCase = true) || t.contains(it.title, ignoreCase = true) }
                ?: return@mapNotNull null
            val m = ((o["match"] as? JsonPrimitive)?.doubleOrNull ?: (o["match"] as? JsonPrimitive)?.contentOrNull?.trimEnd('%')?.toDoubleOrNull() ?: 80.0)
                .toInt().coerceIn(1, 99)
            val why = (o["why"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                .split(Regex("\\s+")).filter { it.isNotBlank() }.take(4).joinToString(" ").ifEmpty { "Fans also watched" }
            LikedPick(rec, m, why)
        }.distinctBy { it.rec.kind + it.rec.tmdbId }
    }.getOrDefault(emptyList())
    val good = if (out.size >= 3) out else emptyList()
    aiPickCache[imdb] = good
    return good.ifEmpty { null }
}

private fun parsePicks(raw: String): JsonArray? {
    val a = listOf(raw.indexOf('{'), raw.indexOf('[')).filter { it >= 0 }.minOrNull() ?: return null
    val b = maxOf(raw.lastIndexOf('}'), raw.lastIndexOf(']'))
    if (b <= a) return null
    val el: JsonElement = Api.json.parseToJsonElement(raw.substring(a, b + 1))
    return when (el) {
        is JsonArray -> el
        is JsonObject -> (el["picks"] as? JsonArray) ?: el.values.firstOrNull { it is JsonArray } as? JsonArray
        else -> null
    }
}
