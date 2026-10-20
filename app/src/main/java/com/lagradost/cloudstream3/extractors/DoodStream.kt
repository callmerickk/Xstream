package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

open class DoodStream : ExtractorApi() {
    override val name = "DoodStream"
    override val mainUrl = "https://dood.la"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        guarded {
            val embed = url.replace("/d/", "/e/")
            val page = app.get(embed, headers = browserHeaders(), referer = referer)
            val base = hostOf(page.url) ?: hostOf(embed) ?: return@guarded
            val md5Path = Regex("/pass_md5/[^'\"\\s]+").find(page.text)?.value ?: return@guarded
            val token = md5Path.substringAfterLast('/')
            val body = app.get(base + md5Path, headers = browserHeaders(), referer = page.url).text.trim()
            if (!body.startsWith("http")) return@guarded
            val link = body + randomString(10) + "?token=$token&expiry=${System.currentTimeMillis()}"
            val title = pageTitle(page.text)?.replace(Regex("(?i)\\s*-\\s*dood.*$"), "")
            callback(
                newExtractorLink(name, name, link, ExtractorLinkType.VIDEO) {
                    this.referer = "$base/"
                    this.quality = getQualityFromName(title)
                    this.headers = browserHeaders()
                }
            )
        }
    }
}

class DoodWs : DoodStream() { override val mainUrl = "https://dood.ws" }
class DoodSh : DoodStream() { override val mainUrl = "https://dood.sh" }
class DoodPm : DoodStream() { override val mainUrl = "https://dood.pm" }
class DoodWf : DoodStream() { override val mainUrl = "https://dood.wf" }
class DoodRe : DoodStream() { override val mainUrl = "https://dood.re" }
class DoodYt : DoodStream() { override val mainUrl = "https://dood.yt" }
class DoodCx : DoodStream() { override val mainUrl = "https://dood.cx" }
class DoodSo : DoodStream() { override val mainUrl = "https://dood.so" }
class DoodTo : DoodStream() { override val mainUrl = "https://dood.to" }
class DoodWatch : DoodStream() { override val mainUrl = "https://dood.watch" }
class DoodLi : DoodStream() { override val mainUrl = "https://dood.li" }
class DoodStreamCom : DoodStream() { override val mainUrl = "https://doodstream.com" }
class DoodsPro : DoodStream() { override val mainUrl = "https://doods.pro" }
class Dooood : DoodStream() { override val mainUrl = "https://dooood.com" }
class D0000d : DoodStream() { override val mainUrl = "https://d0000d.com" }
class D0o0d : DoodStream() { override val mainUrl = "https://d0o0d.com" }
class Ds2Play : DoodStream() { override val mainUrl = "https://ds2play.com" }
class Ds2Video : DoodStream() { override val mainUrl = "https://ds2video.com" }
