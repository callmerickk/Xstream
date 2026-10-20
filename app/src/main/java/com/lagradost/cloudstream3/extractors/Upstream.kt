package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

open class Upstream : ExtractorApi() {
    override val name = "Upstream"
    override val mainUrl = "https://upstream.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        guarded {
            val id = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
                .removeSuffix(".html").removePrefix("embed-")
            val base = hostOf(url) ?: mainUrl
            val embed = "$base/embed-$id.html"
            val page = app.get(embed, headers = browserHeaders(), referer = referer ?: "$base/")
            val unpacked = unpackAll(page.text) ?: page.text
            val raw = Regex("file\\s*:\\s*\"([^\"]+)\"").findAll(unpacked)
                .map { it.groupValues[1] }
                .firstOrNull { it.startsWith("http") || it.startsWith("//") } ?: return@guarded
            val link = absUrl(raw, base)
            val isM3u8 = link.contains(".m3u8")
            callback(
                newExtractorLink(
                    name, name, link,
                    if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$base/"
                    this.quality = getQualityFromName(pageTitle(page.text))
                    this.headers = browserHeaders()
                }
            )
        }
    }
}
