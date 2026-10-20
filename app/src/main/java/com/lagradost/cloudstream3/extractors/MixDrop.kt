package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

open class MixDrop : ExtractorApi() {
    override val name = "MixDrop"
    override val mainUrl = "https://mixdrop.co"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        guarded {
            val embed = url.replace("/f/", "/e/")
            val page = app.get(embed, headers = browserHeaders(), referer = referer)
            val unpacked = unpackAll(page.text) ?: page.text
            val raw = Regex("wurl\\s*=\\s*\"([^\"]+)\"").find(unpacked)?.groupValues?.get(1)
                ?: return@guarded
            val link = absUrl(raw, null)
            val title = pageTitle(page.text)
                ?: page.document.selectFirst("meta[property=og:title]")?.attr("content")
            callback(
                newExtractorLink(name, name, link, ExtractorLinkType.VIDEO) {
                    this.referer = hostOf(page.url)?.let { "$it/" } ?: embed
                    this.quality = getQualityFromName(title)
                    this.headers = browserHeaders()
                }
            )
        }
    }
}

class MixDropTo : MixDrop() { override val mainUrl = "https://mixdrop.to" }
class MixDropSx : MixDrop() { override val mainUrl = "https://mixdrop.sx" }
class MixDropBz : MixDrop() { override val mainUrl = "https://mixdrop.bz" }
class MixDropCh : MixDrop() { override val mainUrl = "https://mixdrop.ch" }
class MixDropAg : MixDrop() { override val mainUrl = "https://mixdrop.ag" }
class MixDropGl : MixDrop() { override val mainUrl = "https://mixdrop.gl" }
class MixDropClub : MixDrop() { override val mainUrl = "https://mixdrop.club" }
class MixDropPs : MixDrop() { override val mainUrl = "https://mixdrop.ps" }
class MixDrpCo : MixDrop() { override val mainUrl = "https://mixdrp.co" }
class MixDrpTo : MixDrop() { override val mainUrl = "https://mixdrp.to" }
