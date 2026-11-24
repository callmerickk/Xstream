package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.ExtractorLink

// Xstream's own implementation of the data classes CloudStream plugins use (same names, written from scratch).

enum class TvType { Movie, AnimeMovie, TvSeries, Cartoon, Anime, OVA, Torrent, Documentary, AsianDrama, Live, NSFW, Others, Music, AudioBook, CustomMedia, Audio, Podcast }

enum class SearchQuality { Cam, CamRip, HdCam, Telesync, WorkPrint, Telecine, HQ, HD, HDR, BlueRay, DVD, SD, FourK, UHD, SDR, WebRip }

enum class DubStatus { None, Dubbed, Subbed }
enum class ShowStatus { Completed, Ongoing }
enum class ActorRole { Main, Supporting, Background }

class ErrorLoadingException(message: String? = null) : Exception(message)

/** A rating; [value] is out of 10. */
data class Score(val value: Double) {
    fun toDouble(): Double = value
    fun toInt(maxScore: Int): Int = (value / 10.0 * maxScore).toInt()
    override fun toString(): String = "%.1f".format(value)
    companion object {
        fun from10(value: Double?): Score? = value?.takeIf { !it.isNaN() }?.let { Score(it.coerceIn(0.0, 10.0)) }
        fun from10(value: String?): Score? = from10(value?.trim()?.toDoubleOrNull())
        fun from10(value: Int?): Score? = from10(value?.toDouble())
        fun from100(value: Int?): Score? = value?.let { Score((it / 10.0).coerceIn(0.0, 10.0)) }
        fun from100(value: Double?): Score? = value?.let { Score((it / 10.0).coerceIn(0.0, 10.0)) }
        fun from(value: String?, maxScore: Int): Score? = value?.toDoubleOrNull()?.let { Score((it / maxScore * 10.0).coerceIn(0.0, 10.0)) }
    }
}

data class Actor(val name: String, val image: String? = null)
data class ActorData(val actor: Actor, val role: ActorRole? = null, val roleString: String? = null, val voiceActor: Actor? = null)

data class TrailerData(val extractorUrl: String, val referer: String?, val raw: Boolean, val headers: Map<String, String> = mapOf())

interface SearchResponse {
    val name: String
    val url: String
    val apiName: String
    var type: TvType?
    var posterUrl: String?
    var posterHeaders: Map<String, String>?
    var id: Int?
    var quality: SearchQuality?
    var score: Score?
}

data class MovieSearchResponse(
    override val name: String, override val url: String, override val apiName: String, override var type: TvType? = null,
    override var posterUrl: String? = null, var year: Int? = null, override var id: Int? = null, override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null, override var score: Score? = null,
) : SearchResponse

data class TvSeriesSearchResponse(
    override val name: String, override val url: String, override val apiName: String, override var type: TvType? = null,
    override var posterUrl: String? = null, var year: Int? = null, var episodes: Int? = null, override var id: Int? = null,
    override var quality: SearchQuality? = null, override var posterHeaders: Map<String, String>? = null, override var score: Score? = null,
) : SearchResponse

data class AnimeSearchResponse(
    override val name: String, override val url: String, override val apiName: String, override var type: TvType? = null,
    override var posterUrl: String? = null, var year: Int? = null, var dubStatus: java.util.EnumSet<DubStatus>? = null,
    var otherName: String? = null, var episodes: MutableMap<DubStatus, Int> = mutableMapOf(), override var id: Int? = null,
    override var quality: SearchQuality? = null, override var posterHeaders: Map<String, String>? = null, override var score: Score? = null,
) : SearchResponse

data class LiveSearchResponse(
    override val name: String, override val url: String, override val apiName: String, override var type: TvType? = null,
    override var posterUrl: String? = null, override var id: Int? = null, override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null, override var score: Score? = null, var lang: String? = null,
) : SearchResponse

interface LoadResponse {
    var name: String
    var url: String
    var apiName: String
    var type: TvType
    var posterUrl: String?
    var year: Int?
    var plot: String?
    var score: Score?
    var tags: List<String>?
    var duration: Int?
    var trailers: MutableList<TrailerData>
    var recommendations: List<SearchResponse>?
    var actors: List<ActorData>?
    var comingSoon: Boolean
    var syncData: MutableMap<String, String>
    var posterHeaders: Map<String, String>?
    var backgroundPosterUrl: String?
    var logoUrl: String?
    var contentRating: String?

    companion object {
        fun LoadResponse.addActorNames(actors: List<String>) { this.actors = actors.map { ActorData(Actor(it)) } }
        fun LoadResponse.addActors(actors: List<Actor>?) { this.actors = actors?.map { ActorData(it) } }
        fun LoadResponse.addImdbId(id: String?) { if (!id.isNullOrBlank()) syncData["imdb"] = id }
        fun LoadResponse.addImdbUrl(url: String?) { url?.let { Regex("tt\\d+").find(it)?.value }?.let { syncData["imdb"] = it } }
        fun LoadResponse.addTMDbId(id: String?) { if (!id.isNullOrBlank()) syncData["tmdb"] = id }
        fun LoadResponse.addSimklId(id: Int?) { if (id != null) syncData["simkl"] = id.toString() }
        fun LoadResponse.addMalId(id: Int?) { if (id != null) syncData["MAL"] = id.toString() }
        fun LoadResponse.addAniListId(id: Int?) { if (id != null) syncData["anilist"] = id.toString() }
        fun LoadResponse.addDuration(input: String?) {
            val t = input?.lowercase() ?: return
            val h = Regex("(\\d+)\\s*h").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val m = Regex("(\\d+)\\s*m").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: t.filter { it.isDigit() }.toIntOrNull() ?: 0
            duration = h * 60 + m
        }
        suspend fun LoadResponse.addTrailer(trailerUrl: String?, referer: String? = null, addRaw: Boolean = false) {
            if (!trailerUrl.isNullOrBlank()) trailers.add(TrailerData(trailerUrl, referer, addRaw))
        }
        suspend fun LoadResponse.addTrailer(trailerUrls: List<String>?, referer: String? = null, addRaw: Boolean = false) {
            trailerUrls?.filter { it.isNotBlank() }?.forEach { trailers.add(TrailerData(it, referer, addRaw)) }
        }
        fun LoadResponse.addKitsuId(id: String?) { if (!id.isNullOrBlank()) syncData["kitsu"] = id }
        fun LoadResponse.addActorsOnly(actors: List<Actor>?) { this.actors = actors?.map { ActorData(it) } }
        fun LoadResponse.getImdbId(): String? = syncData["imdb"]
        fun LoadResponse.getTMDbId(): String? = syncData["tmdb"]
    }
}

data class MovieLoadResponse(
    override var name: String, override var url: String, override var apiName: String, override var type: TvType, var dataUrl: String,
    override var posterUrl: String? = null, override var year: Int? = null, override var plot: String? = null, override var score: Score? = null,
    override var tags: List<String>? = null, override var duration: Int? = null, override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null, override var actors: List<ActorData>? = null, override var comingSoon: Boolean = false,
    override var syncData: MutableMap<String, String> = mutableMapOf(), override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null, override var logoUrl: String? = null, override var contentRating: String? = null,
) : LoadResponse

data class TvSeriesLoadResponse(
    override var name: String, override var url: String, override var apiName: String, override var type: TvType, var episodes: List<Episode>,
    override var posterUrl: String? = null, override var year: Int? = null, override var plot: String? = null, var showStatus: ShowStatus? = null,
    override var score: Score? = null, override var tags: List<String>? = null, override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(), override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null, override var comingSoon: Boolean = false, override var syncData: MutableMap<String, String> = mutableMapOf(),
    override var posterHeaders: Map<String, String>? = null, var nextAiring: Any? = null, var seasonNames: List<Any>? = null,
    override var backgroundPosterUrl: String? = null, override var logoUrl: String? = null, override var contentRating: String? = null,
) : LoadResponse

data class AnimeLoadResponse(
    var engName: String? = null, var japName: String? = null, override var name: String, override var url: String, override var apiName: String,
    override var type: TvType, override var posterUrl: String? = null, override var year: Int? = null,
    var episodes: MutableMap<DubStatus, List<Episode>> = mutableMapOf(), var showStatus: ShowStatus? = null, override var plot: String? = null,
    override var tags: List<String>? = null, var synonyms: List<String>? = null, override var score: Score? = null, override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(), override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null, override var comingSoon: Boolean = false, override var syncData: MutableMap<String, String> = mutableMapOf(),
    override var posterHeaders: Map<String, String>? = null, override var backgroundPosterUrl: String? = null, override var logoUrl: String? = null,
    override var contentRating: String? = null,
) : LoadResponse

data class LiveStreamLoadResponse(
    override var name: String, override var url: String, override var apiName: String, var dataUrl: String, override var posterUrl: String? = null,
    override var year: Int? = null, override var plot: String? = null, override var type: TvType = TvType.Live, override var score: Score? = null,
    override var tags: List<String>? = null, override var duration: Int? = null, override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null, override var actors: List<ActorData>? = null, override var comingSoon: Boolean = false,
    override var syncData: MutableMap<String, String> = mutableMapOf(), override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null, override var logoUrl: String? = null, override var contentRating: String? = null,
) : LoadResponse

data class Episode(
    var data: String, var name: String? = null, var season: Int? = null, var episode: Int? = null, var posterUrl: String? = null,
    var score: Score? = null, var description: String? = null, var date: Long? = null, var runTime: Int? = null,
)

data class HomePageList(val name: String, var list: List<SearchResponse>, val isHorizontalImages: Boolean = false)
data class HomePageResponse(val items: List<HomePageList>, val hasNext: Boolean = false)
data class MainPageData(val name: String, val data: String, val horizontalImages: Boolean = false)
data class MainPageRequest(val name: String, val data: String, val horizontalImages: Boolean)
data class SearchResponseList(val items: List<SearchResponse>, val hasNext: Boolean)

/** A separate audio track for a link (plugins may attach them). */
data class AudioFile(val url: String, var headers: Map<String, String>? = null)

data class SubtitleFile(var lang: String, var url: String) {
    var headers: Map<String, String>? = null
}

/** Unused by Xstream, kept for plugins that mention it. */
data class LoadLinksResult(val links: List<ExtractorLink>)
