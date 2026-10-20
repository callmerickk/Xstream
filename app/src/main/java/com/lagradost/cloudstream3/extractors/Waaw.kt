package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

/** Best effort: Netu/Waaw obfuscates its player and often adds a captcha. */
open class Waaw : ExtractorApi() {
    override val name = "Waaw"
    override val mainUrl = "https://waaw.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        guarded {
            val id = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
            val base = hostOf(url) ?: mainUrl
            val candidates = listOf(url, "$base/f/$id", "$base/e/$id").distinct()
            for (u in candidates) {
                val page = app.get(u, headers = browserHeaders(), referer = referer ?: "$base/")
                val text = (page.text + "\n" + (unpackAll(page.text) ?: "")).replace("\\/", "/")
                val media = MEDIA_REGEX.find(text)?.value ?: continue
                val isM3u8 = media.contains(".m3u8")
                callback(
                    newExtractorLink(
                        name, name, media,
                        if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$base/"
                        this.quality = getQualityFromName(pageTitle(page.text))
                        this.headers = browserHeaders()
                    }
                )
                return@guarded
            }
        }
    }
}
