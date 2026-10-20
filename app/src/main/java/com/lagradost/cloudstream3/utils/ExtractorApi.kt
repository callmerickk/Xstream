package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.CsBridge
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.extractors.BuiltInExtractors
import java.net.URI

// Xstream's own implementation of CloudStream's link types and extractor helpers.

enum class ExtractorLinkType { VIDEO, M3U8, DASH, TORRENT, MAGNET;
    fun getMimeType(): String = when (this) { VIDEO -> "video/mp4"; M3U8 -> "application/x-mpegURL"; DASH -> "application/dash+xml"; TORRENT -> "application/x-bittorrent"; MAGNET -> "video/*" }
}

/** Ask the type from the link (m3u8 / mpd / magnet / file). */
val INFER_TYPE: ExtractorLinkType = ExtractorLinkType.VIDEO

fun inferTypeFromUrl(url: String): ExtractorLinkType {
    val p = url.substringBefore('?').lowercase()
    return when {
        url.startsWith("magnet:") -> ExtractorLinkType.MAGNET
        p.endsWith(".m3u8") || p.contains(".m3u8") || url.contains("m3u8", true) -> ExtractorLinkType.M3U8
        p.endsWith(".mpd") -> ExtractorLinkType.DASH
        p.endsWith(".torrent") -> ExtractorLinkType.TORRENT
        else -> ExtractorLinkType.VIDEO
    }
}

enum class Qualities(val value: Int, val defaultPriority: Int) {
    Unknown(400, 4), P144(144, 0), P240(240, 2), P360(360, 3), P480(480, 4), P720(720, 5), P1080(1080, 6), P1440(1440, 7), P2160(2160, 8);
    companion object {
        fun getStringByInt(qual: Int?): String = when (qual) { 0 -> "Auto"; Unknown.value -> ""; P2160.value -> "4K"; null -> ""; else -> "${qual}p" }
        fun getStringByIntFull(quality: Int): String = getStringByInt(quality)
    }
}

fun getQualityFromName(qualityName: String?): Int {
    if (qualityName == null) return Qualities.Unknown.value
    val m = qualityName.lowercase().replace("p", "").trim()
    return when (m) {
        "4k", "uhd" -> Qualities.P2160.value
        "2k" -> Qualities.P1440.value
        else -> m.toIntOrNull() ?: Regex("(\\d{3,4})\\s*[pP]").find(qualityName)?.groupValues?.get(1)?.toIntOrNull()
            ?: when { qualityName.contains("4k", true) -> 2160; qualityName.contains("fhd", true) -> 1080; qualityName.contains("hd", true) -> 720; else -> Qualities.Unknown.value }
    }
}

fun httpsify(url: String): String = if (url.startsWith("//")) "https:$url" else url

fun getAndUnpack(string: String): String = JsUnpacker(string).unpack() ?: string

open class ExtractorLink(
    open val source: String,
    open val name: String,
    open val url: String,
    open var referer: String,
    open var quality: Int,
    open var headers: Map<String, String> = mapOf(),
    open var extractorData: String? = null,
    open var type: ExtractorLinkType = inferTypeFromUrl(url),
) {
    /** Separate audio tracks some plugins attach. */
    open var audioTracks: List<com.lagradost.cloudstream3.AudioFile> = emptyList()
    val isM3u8: Boolean get() = type == ExtractorLinkType.M3U8
    val isDash: Boolean get() = type == ExtractorLinkType.DASH
    /** Every header to send with the link (Referer included). */
    fun getAllHeaders(): Map<String, String> = if (referer.isBlank()) headers else mapOf("referer" to referer) + headers
    override fun toString(): String = "ExtractorLink(name=$name, url=$url, referer=$referer, quality=$quality, type=$type)"
}

/** The way plugins make a link: name, address, type; then quality / referer / headers set inside [initializer]. */
suspend fun newExtractorLink(source: String, name: String, url: String, type: ExtractorLinkType? = null, initializer: suspend ExtractorLink.() -> Unit = {}): ExtractorLink {
    // INFER_TYPE (or no type) = decide from the address.
    val t = if (type == null || type == ExtractorLinkType.VIDEO) inferTypeFromUrl(url) else type
    val link = ExtractorLink(source, name, url, "", Qualities.Unknown.value, mapOf(), null, t)
    link.initializer()
    return link
}

/** A protected (DRM) link. Xstream's player doesn't play these; plugins can still create them. */
open class DrmExtractorLink(
    override val source: String, override val name: String, override val url: String,
    override var referer: String = "", override var quality: Int = Qualities.Unknown.value,
    override var headers: Map<String, String> = mapOf(), override var extractorData: String? = null,
    override var type: ExtractorLinkType = inferTypeFromUrl(url),
    open var kid: String? = null, open var key: String? = null, open var uuid: java.util.UUID = CLEARKEY_UUID,
    open var kty: String? = null, open var keyRequestParameters: java.util.HashMap<String, String> = java.util.HashMap(),
    open var licenseUrl: String? = null,
) : ExtractorLink(source, name, url, referer, quality, headers, extractorData, type)

val CLEARKEY_UUID: java.util.UUID = java.util.UUID(-0x1d8e62a7567a4c37L, 0x781AB030AF78D30EL)
val WIDEVINE_UUID: java.util.UUID = java.util.UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
val PLAYREADY_UUID: java.util.UUID = java.util.UUID(-0x65fb0f8667bfbd7aL, -0x546d19a41f77a06bL)

suspend fun newDrmExtractorLink(source: String, name: String, url: String, type: ExtractorLinkType? = null, uuid: java.util.UUID,
                                initializer: suspend DrmExtractorLink.() -> Unit = {}): DrmExtractorLink {
    val t = if (type == null || type == ExtractorLinkType.VIDEO) inferTypeFromUrl(url) else type
    return DrmExtractorLink(source, name, url, type = t, uuid = uuid).apply { initializer() }
}

/** The packed (eval) JavaScript in a page, if any. */
fun getPacked(string: String): String? = Regex("""eval\(function\(p,a,c,k,e,.*\)\)""").find(string)?.value

abstract class ExtractorApi {
    abstract val name: String
    abstract val mainUrl: String
    abstract val requiresReferer: Boolean
    open val sourcePlugin: String? = null

    open suspend fun getUrl(url: String, referer: String? = null, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        getUrl(url, referer)?.forEach(callback)
    }
    open suspend fun getUrl(url: String, referer: String? = null): List<ExtractorLink>? = emptyList()
    open fun getExtractorUrl(id: String): String = id
}

fun ExtractorApi.fixUrl(url: String): String {
    if (url.startsWith("http") || url.startsWith("{\"")) return url
    if (url.isEmpty()) return ""
    if (url.startsWith("//")) return "https:$url"
    return if (url.startsWith('/')) mainUrl.trimEnd('/') + url else "${mainUrl.trimEnd('/')}/$url"
}

private fun hostOf(u: String): String = runCatching { URI(u.trim()).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: ""

/** The extractor that handles [url]: one a plugin registered first, then Xstream's built-in ones. */
fun findExtractor(url: String): ExtractorApi? {
    val h = hostOf(url)
    if (h.isEmpty()) return null
    fun match(e: ExtractorApi): Boolean {
        val eh = hostOf(e.mainUrl)
        return eh.isNotEmpty() && (h == eh || h.endsWith(".$eh") || url.startsWith(e.mainUrl.trimEnd('/')))
    }
    return CsBridge.allExtractors.lastOrNull { match(it) } ?: BuiltInExtractors.list.firstOrNull { match(it) }
}

suspend fun loadExtractor(url: String, referer: String? = null, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
    val fixed = httpsify(url.trim())
    val e = findExtractor(fixed)
    if (e == null) { CsBridge.note("loadExtractor: no extractor for ${hostOf(fixed)} (${fixed.take(80)})"); return false }
    CsBridge.note("loadExtractor: ${e.name} ← ${hostOf(fixed)}")
    return try { e.getUrl(fixed, referer ?: e.mainUrl, subtitleCallback, callback); true }
    catch (t: Throwable) { com.lagradost.cloudstream3.mvvm.logError(t); false }
}

suspend fun loadExtractor(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean =
    loadExtractor(url, null, subtitleCallback, callback)
