package com.example.streamnest

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable data class ExtraDef(val name: String = "", val isRequired: Boolean = false)
@Serializable data class CatalogDef(val type: String = "", val id: String = "", val name: String? = null, val extra: List<ExtraDef>? = null)

@Serializable
data class Manifest(
    val id: String = "",
    val name: String = "Addon",
    val version: String = "",
    val description: String? = null,
    val resources: JsonElement? = null,
    val types: List<String> = emptyList(),
    val idPrefixes: List<String>? = null,
    val catalogs: List<CatalogDef> = emptyList(),
)

@Serializable
data class Addon(val baseUrl: String, val manifest: Manifest) {
    fun supports(resource: String, type: String, id: String): Boolean {
        if (resources?.toString()?.contains("\"$resource\"") != true) return false
        if (manifest.types.isNotEmpty() && type !in manifest.types) return false
        val p = manifest.idPrefixes
        return p == null || p.any { id.startsWith(it) }
    }
    private val resources get() = manifest.resources
}

@Serializable data class Video(
    val id: String = "", val title: String? = null, val name: String? = null, val season: Int? = null, val episode: Int? = null,
    val thumbnail: String? = null, val overview: String? = null, val description: String? = null, val released: String? = null,
)

@Serializable
data class Meta(
    val id: String,
    val type: String = "movie",
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val runtime: String? = null,
    val genres: List<String>? = null,
    val videos: List<Video>? = null,
    val trailerStreams: List<TrailerStream>? = null,
    val trailers: List<TrailerRef>? = null,
    val cast: List<String>? = null,
    val director: List<String>? = null,
    val writer: List<String>? = null,
    /** "poster" (2:3, default), "landscape" (16:9) or "square" — as the addon sends it. */
    val posterShape: String? = null,
)

@Serializable data class TrailerStream(val title: String? = null, val ytId: String? = null)
@Serializable data class TrailerRef(val source: String? = null, val type: String? = null)

@Serializable data class Subtitle(val id: String? = null, val url: String = "", val lang: String = "und")
@Serializable data class ProxyHeaders(val request: Map<String, String>? = null)
@Serializable data class BehaviorHints(
    val filename: String? = null, val videoSize: Long? = null, val bingeGroup: String? = null,
    val notWebReady: Boolean? = null, val proxyHeaders: ProxyHeaders? = null,
)
@Serializable data class StreamInfo(
    val url: String? = null, val name: String? = null, val title: String? = null, val description: String? = null,
    val subtitles: List<Subtitle>? = null, val behaviorHints: BehaviorHints? = null,
)

@Serializable data class CatalogResponse(val metas: List<Meta> = emptyList())
@Serializable data class MetaResponse(val meta: Meta? = null)
@Serializable data class StreamResponse(val streams: List<StreamInfo> = emptyList())
@Serializable data class SubsResponse(val subtitles: List<Subtitle> = emptyList())

@Serializable data class Shelf(val title: String, val items: List<Meta>)
data class StreamItem(val addonName: String, val stream: StreamInfo)

@Serializable
data class Progress(val key: String, val title: String, val image: String?, val url: String, val subs: List<Subtitle>, val positionMs: Long, val durationMs: Long, val updated: Long)

data class Channel(val name: String, val logo: String?, val group: String?, val url: String)

/** avatar: "" = none, "img" = jpg/png, "gif" = animated gif, "video" = short looping clip */
@Serializable
data class Profile(val id: String, val name: String, val kids: Boolean = false, val avatar: String = "", val ver: Int = 0)

/** Skip-intro timestamps from introdb.app (milliseconds). */
@Serializable data class Seg(val start_ms: Long = 0, val end_ms: Long = 0, val confidence: Double = 1.0)
@Serializable data class Segments(
    val intro: Seg? = null, val recap: Seg? = null, val outro: Seg? = null,
    val is_movie: Boolean? = null, val season: Int? = null, val episode: Int? = null,
)
