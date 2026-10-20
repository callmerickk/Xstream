package com.lagradost.cloudstream3

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import java.text.SimpleDateFormat
import java.util.Locale

// Xstream's own implementation of the provider base class and helper functions CloudStream plugins call.

/** Jackson, as plugins expect it: unknown fields ignored, Kotlin classes understood. */
val mapper: JsonMapper = JsonMapper.builder().addModule(kotlinModule())
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()

val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

abstract class MainAPI {
    open var name = "NONE"
    open var mainUrl = "NONE"
    open var storedCredentials: String? = null
    open var canBeOverridden: Boolean = true
    open var lang = "en"
    open var sourcePlugin: String? = null
    open val hasMainPage = false
    open val hasQuickSearch = false
    open val hasDownloadSupport = true
    open val hasChromecastSupport = true
    open val instantLinkLoading = false
    open val usesWebView = false
    open val sequentialMainPage = false
    open val sequentialMainPageDelay: Long = 0L
    open val sequentialMainPageScrollDelay: Long = 0L
    open val supportedTypes: Set<TvType> = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Anime, TvType.OVA)
    open val mainPage: List<MainPageData> = listOf(MainPageData("", "", false))

    open suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = throw NotImplementedError()
    open suspend fun search(query: String): List<SearchResponse>? = throw NotImplementedError()
    open suspend fun search(query: String, page: Int): SearchResponseList? = search(query)?.let { SearchResponseList(it, false) }
    open suspend fun quickSearch(query: String): List<SearchResponse>? = throw NotImplementedError()
    open suspend fun load(url: String): LoadResponse? = throw NotImplementedError()
    open suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean =
        throw NotImplementedError()
    open fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? = null
}

fun base64Decode(string: String): String = String(android.util.Base64.decode(string, android.util.Base64.DEFAULT), Charsets.ISO_8859_1)
fun base64DecodeArray(string: String): ByteArray = android.util.Base64.decode(string, android.util.Base64.DEFAULT)
fun base64Encode(array: ByteArray): String = String(android.util.Base64.encode(array, android.util.Base64.NO_WRAP), Charsets.ISO_8859_1)

fun MainAPI.fixUrlNull(url: String?): String? = if (url.isNullOrEmpty()) null else fixUrl(url)

/** A relative link made absolute on the provider's site. */
fun MainAPI.fixUrl(url: String): String {
    if (url.startsWith("http") || url.startsWith("{\"")) return url
    if (url.isEmpty()) return ""
    if (url.startsWith("//")) return "https:$url"
    return if (url.startsWith('/')) mainUrl.trimEnd('/') + url else "${mainUrl.trimEnd('/')}/$url"
}

fun mainPageOf(vararg elements: Pair<String, String>): List<MainPageData> = elements.map { (data, name) -> MainPageData(name, data) }
fun mainPage(url: String, name: String, horizontalImages: Boolean = false) = MainPageData(name, url, horizontalImages)

fun newHomePageResponse(name: String, list: List<SearchResponse>, hasNext: Boolean? = null): HomePageResponse =
    HomePageResponse(listOf(HomePageList(name, list)), hasNext ?: list.isNotEmpty())
fun newHomePageResponse(data: MainPageRequest, list: List<SearchResponse>, hasNext: Boolean? = null): HomePageResponse =
    HomePageResponse(listOf(HomePageList(data.name, list, data.horizontalImages)), hasNext ?: list.isNotEmpty())
fun newHomePageResponse(list: HomePageList, hasNext: Boolean? = null): HomePageResponse = HomePageResponse(listOf(list), hasNext ?: list.list.isNotEmpty())
fun newHomePageResponse(list: List<HomePageList>, hasNext: Boolean? = null): HomePageResponse = HomePageResponse(list, hasNext ?: list.any { it.list.isNotEmpty() })

fun newSearchResponseList(list: List<SearchResponse>, hasNext: Boolean? = null): SearchResponseList = SearchResponseList(list, hasNext ?: false)
fun List<SearchResponse>.toNewSearchResponseList(hasNext: Boolean? = null): SearchResponseList = SearchResponseList(this, hasNext ?: false)

fun MainAPI.newMovieSearchResponse(name: String, url: String, type: TvType = TvType.Movie, fix: Boolean = true, initializer: MovieSearchResponse.() -> Unit = {}): MovieSearchResponse =
    MovieSearchResponse(name, if (fix) fixUrl(url) else url, this.name, type).apply(initializer)

fun MainAPI.newTvSeriesSearchResponse(name: String, url: String, type: TvType = TvType.TvSeries, fix: Boolean = true, initializer: TvSeriesSearchResponse.() -> Unit = {}): TvSeriesSearchResponse =
    TvSeriesSearchResponse(name, if (fix) fixUrl(url) else url, this.name, type).apply(initializer)

fun MainAPI.newAnimeSearchResponse(name: String, url: String, type: TvType = TvType.Anime, fix: Boolean = true, initializer: AnimeSearchResponse.() -> Unit = {}): AnimeSearchResponse =
    AnimeSearchResponse(name, if (fix) fixUrl(url) else url, this.name, type).apply(initializer)

fun MainAPI.newLiveSearchResponse(name: String, url: String, type: TvType = TvType.Live, fix: Boolean = true, initializer: LiveSearchResponse.() -> Unit = {}): LiveSearchResponse =
    LiveSearchResponse(name, if (fix) fixUrl(url) else url, this.name, type).apply(initializer)

/** [data] is what loadLinks gets back: a String as it is, anything else as JSON. */
private fun dataString(data: Any?): String = when (data) { null -> ""; is String -> data; else -> mapper.writeValueAsString(data) }

suspend fun <T> MainAPI.newMovieLoadResponse(name: String, url: String, type: TvType, data: T?, initializer: suspend MovieLoadResponse.() -> Unit = {}): MovieLoadResponse {
    val r = MovieLoadResponse(name, url, this.name, type, dataString(data))
    r.initializer()
    return r
}

suspend fun MainAPI.newMovieLoadResponse(name: String, url: String, type: TvType, dataUrl: String?, initializer: suspend MovieLoadResponse.() -> Unit = {}): MovieLoadResponse {
    val r = MovieLoadResponse(name, url, this.name, type, dataUrl ?: "")
    r.initializer()
    return r
}

suspend fun MainAPI.newTvSeriesLoadResponse(name: String, url: String, type: TvType, episodes: List<Episode>, initializer: suspend TvSeriesLoadResponse.() -> Unit = {}): TvSeriesLoadResponse {
    val r = TvSeriesLoadResponse(name, url, this.name, type, episodes)
    r.initializer()
    return r
}

suspend fun MainAPI.newAnimeLoadResponse(name: String, url: String, type: TvType, comingSoonIfNone: Boolean = true, initializer: suspend AnimeLoadResponse.() -> Unit = {}): AnimeLoadResponse {
    val r = AnimeLoadResponse(name = name, url = url, apiName = this.name, type = type)
    r.initializer()
    if (comingSoonIfNone && r.episodes.values.all { it.isEmpty() }) r.comingSoon = true
    return r
}

suspend fun MainAPI.newLiveStreamLoadResponse(name: String, url: String, dataUrl: String, initializer: suspend LiveStreamLoadResponse.() -> Unit = {}): LiveStreamLoadResponse {
    val r = LiveStreamLoadResponse(name, url, this.name, dataUrl)
    r.initializer()
    return r
}

fun <T> MainAPI.newEpisode(data: T, initializer: Episode.() -> Unit = {}): Episode = Episode(dataString(data)).apply(initializer)
fun <T> MainAPI.newEpisode(data: T, initializer: Episode.() -> Unit = {}, fix: Boolean): Episode =
    Episode(if (fix && data is String) fixUrl(data) else dataString(data)).apply(initializer)

suspend fun newSubtitleFile(lang: String, url: String, initializer: suspend SubtitleFile.() -> Unit = {}): SubtitleFile {
    val s = SubtitleFile(lang, url)
    s.initializer()
    return s
}

/** The episode's air date, from text in [format]. */
fun Episode.addDate(date: String?, format: String = "yyyy-MM-dd") {
    if (date.isNullOrBlank()) return
    this.date = runCatching { SimpleDateFormat(format, Locale.US).parse(date)?.time }.getOrNull()
}
fun Episode.addDate(date: java.util.Date?) { this.date = date?.time }

fun getQualityFromString(string: String?): SearchQuality? {
    val s = string?.lowercase()?.replace(Regex("[^a-z0-9]"), "") ?: return null
    return when {
        s.contains("cam") && s.contains("rip") -> SearchQuality.CamRip
        s.contains("hdcam") -> SearchQuality.HdCam
        s.contains("cam") -> SearchQuality.Cam
        s.contains("4k") || s.contains("2160") || s.contains("uhd") -> SearchQuality.FourK
        s.contains("bluray") || s.contains("bdrip") -> SearchQuality.BlueRay
        s.contains("webrip") || s.contains("webdl") || s.contains("web") -> SearchQuality.WebRip
        s.contains("hdr") -> SearchQuality.HDR
        s.contains("dvd") -> SearchQuality.DVD
        s.contains("hq") -> SearchQuality.HQ
        s.contains("hd") || s.contains("1080") || s.contains("720") -> SearchQuality.HD
        s.contains("sd") || s.contains("480") -> SearchQuality.SD
        else -> null
    }
}
