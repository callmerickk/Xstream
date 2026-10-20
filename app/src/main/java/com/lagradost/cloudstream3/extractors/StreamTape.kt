package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

open class StreamTape : ExtractorApi() {
    override val name = "StreamTape"
    override val mainUrl = "https://streamtape.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        guarded {
            val id = Regex("/(?:e|v|d)/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
            val base = hostOf(url) ?: mainUrl
            val embed = if (id != null) "$base/e/$id" else url
            val page = app.get(embed, headers = browserHeaders(), referer = referer)
            val text = page.text

            // The page assembles the link as: '<prefix>' + ('<tail>').substring(n)[.substring(m)...]
            val expr = Regex("(?:robotlink|ideoooolink|norobotlink)'\\)\\.innerHTML\\s*=\\s*(.+?);", RegexOption.DOT_MATCHES_ALL)
                .findAll(text).map { it.groupValues[1] }.lastOrNull { it.contains("get_video") }
            var link: String? = null
            if (expr != null) {
                val literals = Regex("'([^']*)'").findAll(expr).map { it.groupValues[1] }.toList()
                if (literals.size >= 2) {
                    var tail = literals[1]
                    Regex("\\.substring\\((\\d+)\\)").findAll(expr).forEach {
                        val n = it.groupValues[1].toInt()
                        tail = if (n <= tail.length) tail.substring(n) else tail
                    }
                    link = literals[0] + tail
                }
            }
            if (link == null) {
                link = Regex("(?://|https://)[\\w.-]+/get_video\\?id=[^'\"\\s<]+").find(text)?.value
            }
            val final = absUrl(link ?: return@guarded, null)
            val title = page.document.selectFirst("meta[name=og:title]")?.attr("content")
                ?: page.document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: pageTitle(text)
            callback(
                newExtractorLink(name, name, final, ExtractorLinkType.VIDEO) {
                    this.referer = "$base/"
                    this.quality = getQualityFromName(title)
                    this.headers = browserHeaders()
                }
            )
        }
    }
}

class StreamTapeCc : StreamTape() { override val mainUrl = "https://streamtape.cc" }
class StreamTapeTo : StreamTape() { override val mainUrl = "https://streamtape.to" }
class StreamTapeNet : StreamTape() { override val mainUrl = "https://streamtape.net" }
class StreamTapeXyz : StreamTape() { override val mainUrl = "https://streamtape.xyz" }
class StreamTapeSite : StreamTape() { override val mainUrl = "https://streamtape.site" }
class StreamTapeOnline : StreamTape() { override val mainUrl = "https://streamtape.online" }
class StreamTapeAdBlock : StreamTape() { override val mainUrl = "https://streamtapeadblock.com" }
class StrTapeCloud : StreamTape() { override val mainUrl = "https://strtape.cloud" }
class StrTapeSite : StreamTape() { override val mainUrl = "https://strtape.site" }
class StrTapeTech : StreamTape() { override val mainUrl = "https://strtape.tech" }
class StrTapeCom : StreamTape() { override val mainUrl = "https://strtape.com" }
class StrTapeNet : StreamTape() { override val mainUrl = "https://strtape.net" }
class StrTapeAdBlock : StreamTape() { override val mainUrl = "https://strtapeadblock.com" }
class ShaveTape : StreamTape() { override val mainUrl = "https://shavetape.cash" }
class StapeFun : StreamTape() { override val mainUrl = "https://stape.fun" }
class StreamTaPe : StreamTape() { override val mainUrl = "https://streamta.pe" }
class ScloudOnline : StreamTape() { override val mainUrl = "https://scloud.online" }
