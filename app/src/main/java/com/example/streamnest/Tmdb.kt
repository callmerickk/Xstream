package com.example.streamnest

import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/* Rich title info from TMDB (Help & Settings > Integrations > TMDB key). Everything is optional. */

/** [id] = TMDB person id (empty when the cast comes from the addon instead of TMDB). */
data class TmCast(val name: String, val role: String, val photo: String?, val id: String = "")
data class TmCompany(val name: String, val logo: String?)
/** [official] = uploaded by the studio's official channel; [publishedAt] = ISO date-time ("2024-05-01T16:00:05.000Z"). */
data class TmVideo(val key: String, val name: String, val type: String, val year: String?, val official: Boolean = true, val publishedAt: String? = null)
/** [score] = TMDB vote_average (0..10), [genreIds] = TMDB genre ids. */
data class TmRec(val tmdbId: String, val kind: String, val title: String, val year: String?, val poster: String?, val score: Double = 0.0, val genreIds: List<Int> = emptyList())
data class TmdbInfo(
    val tmdbId: String, val kind: String,
    val poster: String?, val backdrop: String?, val logo: String?,
    val certification: String?, val status: String?, val release: String?, val runtime: String?,
    val country: String?, val language: String?, val genres: List<String>, val overview: String?,
    val directors: List<String>, val writers: List<String>, val cast: List<TmCast>,
    val companies: List<TmCompany>, val videos: List<TmVideo>, val recs: List<TmRec>,
    val genreIds: List<Int> = emptyList(),
    /** TV loading screen: scene stills without text (best first), tagline and money. */
    val backdrops: List<String> = emptyList(), val tagline: String? = null, val budget: Long = 0, val revenue: Long = 0,
)

private val infoCache = HashMap<String, TmdbInfo>()
private const val IMG = "https://image.tmdb.org/t/p/"

private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonObject.arr(k: String) = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
private fun JsonObject.obj(k: String) = this[k] as? JsonObject

fun AppViewModel.tmdbKey(): String = (texts.value["tmdbKey"] ?: "").trim()

/**
 * TMDB sign-in for every request in the app: TMDB gives two kinds of key, the short "API key" (v3, sent as api_key=…)
 * and the long "API Read Access Token" (starts with eyJ…, must be sent as "Authorization: Bearer"). Pasting the long one
 * into the key field made every TMDB call fail with 401 (Source Test, plugin test, posters, search). Any request with a
 * long token in api_key is rewritten to the Bearer form here, so both kinds simply work.
 */
object TmdbAuth : okhttp3.Interceptor {
    fun isToken(k: String) = k.startsWith("eyJ") || k.length > 40
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val req = chain.request()
        if (req.url.host != "api.themoviedb.org") return chain.proceed(req)
        val k = req.url.queryParameter("api_key")?.trim().orEmpty()
        if (k.isEmpty() || !isToken(k)) return chain.proceed(req)
        val url = req.url.newBuilder().removeAllQueryParameters("api_key").build()
        return chain.proceed(req.newBuilder().url(url).header("Authorization", "Bearer $k").header("accept", "application/json").build())
    }

    /** Last check of the key: "ok", "bad" (TMDB refused it: 401), "none" (no key), or "net: …" (couldn't reach TMDB). */
    val state = kotlinx.coroutines.flow.MutableStateFlow("")
    suspend fun check(key: String): String {
        val k = key.trim()
        val r = if (k.isEmpty()) "none" else runCatching { Api.get("https://api.themoviedb.org/3/configuration?api_key=$k"); "ok" }.getOrElse { e ->
            if ((e.message ?: "").contains("401")) "bad" else "net: " + (e.message ?: e.javaClass.simpleName).take(60)
        }
        state.value = r
        ActLog.add("NET", "TMDB key check: $r")
        return r
    }
    fun label(r: String) = when {
        r == "ok" -> "TMDB key works"
        r == "bad" -> "TMDB refused the key (401): paste the v3 API key or the Read Access Token from themoviedb.org › Settings › API"
        r == "none" -> "No TMDB key: add one in Integrations"
        r.startsWith("net") -> "Couldn't reach TMDB (${r.removePrefix("net: ")})"
        else -> "Checking TMDB key…"
    }
}

suspend fun AppViewModel.tmdbInfo(imdb: String, type: String): TmdbInfo? {
    val key = tmdbKey()
    if (key.isEmpty() || !imdb.startsWith("tt")) return null
    infoCache[imdb]?.let { return it }
    return runCatching {
        val find = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/find/$imdb?api_key=$key&external_source=imdb_id")).jsonObject
        val tv = type == "series"
        val hit = find.arr(if (tv) "tv_results" else "movie_results").firstOrNull()
            ?: find.arr(if (tv) "movie_results" else "tv_results").firstOrNull() ?: return null
        val kind = if (find.arr("tv_results").firstOrNull() === hit) "tv" else "movie"
        val id = hit["id"]!!.jsonPrimitive.content
        val extra = if (kind == "tv") "credits,videos,content_ratings,recommendations,images" else "credits,videos,release_dates,recommendations,images"
        val d = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/$kind/$id?api_key=$key&append_to_response=$extra&include_image_language=en,null")).jsonObject

        val imgs = d.obj("images")
        fun bestImg(field: String, lang: String?) = imgs?.arr(field)?.filter { lang == null || it.str("iso_639_1") == lang }
            ?.maxByOrNull { (it["vote_average"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0 }?.str("file_path")
        val logo = bestImg("logos", "en") ?: bestImg("logos", null)

        val cert = if (kind == "movie") {
            val res = d.obj("release_dates")?.arr("results").orEmpty()
            listOf("IN", "US", "GB").firstNotNullOfOrNull { c -> res.firstOrNull { it.str("iso_3166_1") == c }?.arr("release_dates")?.firstNotNullOfOrNull { it.str("certification") } }
        } else {
            val res = d.obj("content_ratings")?.arr("results").orEmpty()
            listOf("IN", "US", "GB").firstNotNullOfOrNull { c -> res.firstOrNull { it.str("iso_3166_1") == c }?.str("rating") }
        }
        val runtimeMin = if (kind == "movie") d["runtime"]?.jsonPrimitive?.intOrNull
        else (d["episode_run_time"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.intOrNull
        val credits = d.obj("credits")
        val crew = credits?.arr("crew").orEmpty()
        val directors = if (kind == "tv") d.arr("created_by").mapNotNull { it.str("name") } else crew.filter { it.str("job") == "Director" }.mapNotNull { it.str("name") }
        val writers = crew.filter { it.str("department") == "Writing" }.mapNotNull { it.str("name") }.distinct().take(3)
        // Actors only (credits.cast) — crew never appears in the cast row.
        val cast = credits?.arr("cast").orEmpty().take(60).mapNotNull { c ->
            TmCast(c.str("name") ?: return@mapNotNull null, c.str("character") ?: "", c.str("profile_path")?.let { "${IMG}w185$it" }, c.str("id") ?: "")
        }.distinctBy { it.id.ifEmpty { it.name } }
        val companies = (if (kind == "tv") d.arr("networks") + d.arr("production_companies") else d.arr("production_companies"))
            .mapNotNull { c -> TmCompany(c.str("name") ?: return@mapNotNull null, c.str("logo_path")?.let { "${IMG}w185$it" }) }.distinctBy { it.name }.take(6)
        val videos = d.obj("videos")?.arr("results").orEmpty().filter { it.str("site") == "YouTube" }.mapNotNull { v ->
            TmVideo(v.str("key") ?: return@mapNotNull null, v.str("name") ?: "Video", v.str("type") ?: "Video", v.str("published_at")?.take(4),
                official = (v["official"] as? JsonPrimitive)?.booleanOrNull ?: true, publishedAt = v.str("published_at"))
        }.distinctBy { it.key }.sortedByDescending { it.publishedAt ?: "" } // newest first
        val recs = d.obj("recommendations")?.arr("results").orEmpty().take(20).mapNotNull { r -> parseRec(r, kind) }.distinctBy { it.kind + it.tmdbId }
        TmdbInfo(
            tmdbId = id, kind = kind,
            poster = d.str("poster_path")?.let { "${IMG}w780$it" },
            backdrop = d.str("backdrop_path")?.let { "${IMG}w1280$it" },
            logo = logo?.let { "${IMG}w500$it" },
            certification = cert, status = d.str("status"),
            release = (d.str("release_date") ?: d.str("first_air_date")),
            runtime = runtimeMin?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
            country = (d["origin_country"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
                ?: d.arr("production_countries").firstOrNull()?.str("iso_3166_1"),
            language = d.str("original_language")?.uppercase(),
            genres = d.arr("genres").mapNotNull { it.str("name") },
            genreIds = d.arr("genres").mapNotNull { (it["id"] as? JsonPrimitive)?.intOrNull },
            overview = d.str("overview"),
            directors = directors, writers = writers, cast = cast, companies = companies, videos = videos, recs = recs,
            backdrops = imgs?.arr("backdrops").orEmpty().filter { it.str("iso_639_1") == null }
                .sortedByDescending { (it["vote_average"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0 }
                .mapNotNull { it.str("file_path")?.let { p -> "${IMG}w1280$p" } }.take(6),
            tagline = d.str("tagline"),
            budget = (d["budget"] as? JsonPrimitive)?.longOrNull ?: 0L, revenue = (d["revenue"] as? JsonPrimitive)?.longOrNull ?: 0L,
        ).also { infoCache[imdb] = it }
    }.getOrNull()
}

/** IMDb id for a TMDB recommendation, so it can open in Xstream. */
suspend fun AppViewModel.tmdbToImdb(r: TmRec): String? = runCatching {
    val o = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/${r.kind}/${r.tmdbId}/external_ids?api_key=${tmdbKey()}")).jsonObject
    o.str("imdb_id")
}.getOrNull()

private fun parseRec(r: JsonObject, kind: String): TmRec? {
    val id = r.str("id") ?: return null
    val title = r.str("title") ?: r.str("name") ?: return null
    return TmRec(
        id, r.str("media_type") ?: kind, title,
        (r.str("release_date") ?: r.str("first_air_date"))?.take(4), r.str("poster_path")?.let { "${IMG}w500$it" },
        score = (r["vote_average"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
        genreIds = (r["genre_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty(),
    )
}

private val similarCache = HashMap<String, List<TmRec>>()

/** TMDB "similar" titles (used to top up the recommendations). [kind] = "movie" or "tv". */
suspend fun AppViewModel.tmdbSimilar(kind: String, tmdbId: String): List<TmRec> {
    val key = tmdbKey()
    if (key.isEmpty()) return emptyList()
    similarCache[kind + tmdbId]?.let { return it }
    return runCatching {
        val o = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/$kind/$tmdbId/similar?api_key=$key")).jsonObject
        o.arr("results").take(20).mapNotNull { parseRec(it, kind) }.distinctBy { it.kind + it.tmdbId }
    }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) similarCache[kind + tmdbId] = it }
}

/** TMDB genre ids (movies and TV) to names. */
val TmdbGenres: Map<Int, String> = mapOf(
    28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary", 18 to "Drama",
    10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music", 9648 to "Mystery", 10749 to "Romance",
    878 to "Sci-Fi", 10770 to "TV Movie", 53 to "Thriller", 10752 to "War", 37 to "Western",
    10759 to "Action", 10762 to "Kids", 10763 to "News", 10764 to "Reality", 10765 to "Sci-Fi", 10766 to "Soap", 10767 to "Talk", 10768 to "War",
)

/**
 * Tall posters for the numbered (Top 10) rows: TMDB's own full-size portrait poster, found by IMDb id (or by name and
 * year for titles without one). Catalogs that only have a wide design are never cropped into a tall card any more.
 * Remembered for the whole session; the answer for each title is asked once.
 */
object TmdbPosters {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val none = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val running = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<String?>>()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    fun cached(m: Meta): String? = cache[m.id]

    /** A tall poster that never needs TMDB: Cinemeta's large poster by IMDb id, or the title's own when it is tall. */
    fun fallback(m: Meta): String? = hdPoster(m) ?: m.poster?.takeIf { !m.posterShape.equals("landscape", true) && !m.posterShape.equals("square", true) }?.let { hiResArt(it) }

    suspend fun portrait(m: Meta): String? {
        cache[m.id]?.let { return it }
        if (m.id in none) return null
        val vm = Public.vm ?: return null
        val key = vm.tmdbKey()
        if (key.isEmpty()) return null
        val d = running.getOrPut(m.id) { scope.async { runCatching { look(m, key) }.getOrNull() } }
        val r = d.await()
        running.remove(m.id)
        if (r != null) cache[m.id] = r else none += m.id
        return r
    }

    private suspend fun look(m: Meta, key: String): String? {
        val imdb = m.id.substringBefore(":").takeIf { it.startsWith("tt") }
        val path: String? = if (imdb != null) {
            val f = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/find/$imdb?api_key=$key&external_source=imdb_id")).jsonObject
            val first = if (m.type == "series") listOf("tv_results", "movie_results") else listOf("movie_results", "tv_results")
            first.firstNotNullOfOrNull { k -> f.arr(k).firstOrNull()?.str("poster_path") }
        } else {
            val kind = if (m.type == "series") "tv" else "movie"
            val year = m.releaseInfo?.take(4)?.toIntOrNull()
            val yp = if (year == null) "" else if (kind == "tv") "&first_air_date_year=$year" else "&year=$year"
            val o = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/search/$kind?api_key=$key&query=${java.net.URLEncoder.encode(m.name, "UTF-8")}$yp&page=1")).jsonObject
            o.arr("results").firstOrNull()?.str("poster_path")
        }
        return path?.let { "${IMG}w780$it" }
    }
}
