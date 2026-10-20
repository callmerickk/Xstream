package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// Xstream's own link readers for common video hosts (written from scratch; same class names plugins extend).

object BuiltInExtractors {
    val list: List<ExtractorApi> by lazy { listOf(PixelDrain(), StreamTape(), VidHidePro(), VidStack(), StreamWishExtractor(), FileMoon()) }
}

private fun origin(u: String): String = runCatching { URI(u).let { "${it.scheme}://${it.host}" } }.getOrDefault(u)

/** pixeldrain.com/u/ID (or /api/file/ID) → the file itself. */
open class PixelDrain : ExtractorApi() {
    override val name: String = "PixelDrain"
    override val mainUrl: String = "https://pixeldrain.com"
    override val requiresReferer: Boolean = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val id = Regex("/(?:u|api/file|l)/([A-Za-z0-9]+)").find(url)?.groupValues?.get(1) ?: url.substringAfterLast('/').substringBefore('?')
        val base = origin(url).ifBlank { mainUrl }
        val info = runCatching { app.get("$base/api/file/$id/info").text }.getOrNull().orEmpty()
        val fileName = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(info)?.groupValues?.get(1)
        callback(newExtractorLink(name, fileName ?: name, "$base/api/file/$id?download", ExtractorLinkType.VIDEO) {
            this.referer = url; quality = getQualityFromName(fileName)
        })
    }
}

/** streamtape: the page builds the address from two pieces of text. */
open class StreamTape : ExtractorApi() {
    override val name: String = "StreamTape"
    override val mainUrl: String = "https://streamtape.com"
    override val requiresReferer: Boolean = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val page = app.get(url.replace("/e/", "/v/"), referer = referer).text
        val m = Regex("""getElementById\('(?:robotlink|ideoolink|norobotlink)'\)\.innerHTML\s*=\s*['"]([^'"]+)['"]\s*\+\s*\(?['"]([^'"]+)['"]\)?(?:\.substring\((\d+)\))?(?:\.substring\((\d+)\))?""").findAll(page).lastOrNull() ?: return
        var tail = m.groupValues[2]
        m.groupValues[3].toIntOrNull()?.let { tail = tail.drop(it) }
        m.groupValues[4].toIntOrNull()?.let { tail = tail.drop(it) }
        val link = "https:" + (m.groupValues[1] + tail).trimStart(':') + "&stream=1"
        callback(newExtractorLink(name, name, link.replace("https:////", "https://"), ExtractorLinkType.VIDEO) { this.referer = mainUrl; quality = Qualities.Unknown.value })
    }
}

/** Hosts that hide the player setup in packed JavaScript (VidHide, StreamWish, FileMoon…): unpack, find the file. */
open class PackedPlayerExtractor : ExtractorApi() {
    override val name: String = "Packed"
    override val mainUrl: String = "https://example.invalid"
    override val requiresReferer: Boolean = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val res = app.get(url, referer = referer ?: mainUrl, headers = mapOf("accept-language" to "en-US,en;q=0.9"))
        var text = res.text
        Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\.split\('\|'\)[^)]*\)\)""", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { p ->
            JsUnpacker(p.value).unpack()?.let { text += "\n" + it }
        }
        val files = Regex("""["']?(?:file|hls\d?|src)["']?\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""").findAll(text).map { it.groupValues[1] }.toList().distinct()
        files.forEach { f ->
            val link = if (f.startsWith("/")) origin(res.url) + f else f
            callback(newExtractorLink(name, name, link, if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = origin(res.url) + "/"; quality = Qualities.Unknown.value
                headers = mapOf("origin" to origin(res.url))
            })
        }
        Regex("""\{\s*file\s*:\s*["']([^"']+\.(?:vtt|srt))["']\s*,\s*label\s*:\s*["']([^"']+)["']""").findAll(text).forEach {
            subtitleCallback(SubtitleFile(it.groupValues[2], it.groupValues[1]))
        }
    }
}

open class VidHidePro : PackedPlayerExtractor() {
    override val name: String = "VidHidePro"
    override val mainUrl: String = "https://vidhidepro.com"
}
open class StreamWishExtractor : PackedPlayerExtractor() {
    override val name: String = "StreamWish"
    override val mainUrl: String = "https://streamwish.to"
}
open class FileMoon : PackedPlayerExtractor() {
    override val name: String = "FileMoon"
    override val mainUrl: String = "https://filemoon.sx"
}

/**
 * VidStack-style players: /api/v1/video?id=… answers with hex text that is AES-CBC encrypted JSON
 * ({"source": "...m3u8", "subtitle": {...}}).
 */
open class VidStack : ExtractorApi() {
    override val name: String = "Vidstack"
    override val mainUrl: String = "https://vidstack.io"
    override val requiresReferer: Boolean = true
    open val key: String = "kiemtienmua911ca"
    open val ivList: List<String> = listOf("1234567890oiuytr", "0123456789abcdef")

    private fun decrypt(hex: String, iv: String): String? = runCatching {
        val bytes = hex.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(iv.toByteArray()))
        String(c.doFinal(bytes))
    }.getOrNull()

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val hash = url.substringAfterLast("#").substringAfterLast("/").substringBefore("?")
        val base = origin(url)
        val enc = app.get("$base/api/v1/video?id=$hash", referer = url, headers = mapOf("user-agent" to com.lagradost.cloudstream3.USER_AGENT)).text.trim()
        val dec = ivList.firstNotNullOfOrNull { decrypt(enc, it) } ?: return
        val src = Regex("\"source\"\\s*:\\s*\"(.*?)\"").find(dec)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
        callback(newExtractorLink(name, name, src, ExtractorLinkType.M3U8) {
            this.referer = "$base/"; quality = Qualities.Unknown.value; headers = mapOf("origin" to base)
        })
        Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+\\.(?:vtt|srt)[^\"]*)\"").findAll(dec.substringAfter("\"subtitle\"", "")).forEach {
            subtitleCallback(SubtitleFile(it.groupValues[1], it.groupValues[2].replace("\\/", "/").let { u -> if (u.startsWith("/")) base + u else u }))
        }
    }
}
