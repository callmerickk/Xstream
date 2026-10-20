package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

open class MultiUp : ExtractorApi() {
    override val name = "MultiUp"
    override val mainUrl = "https://multiup.org"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        guarded {
            val page = app.get(url, headers = browserHeaders(), referer = referer ?: mainUrl)
            val mirrors = linkedSetOf<String>()
            page.document.select("[link]").forEach { el ->
                val l = el.attr("link")
                if (el.attr("validity") != "invalid" && l.startsWith("http") && !l.contains("multiup.")) {
                    mirrors.add(l)
                }
            }
            if (mirrors.isEmpty()) {
                val base = hostOf(page.url) ?: mainUrl
                page.document.select("a[href*=/mirror/]").take(10).forEach { a ->
                    try {
                        val r = app.get(absUrl(a.attr("href"), base), headers = browserHeaders(), referer = url)
                        if (!r.url.contains("multiup.")) mirrors.add(r.url)
                    } catch (_: Throwable) {
                    }
                }
            }
            mirrors.take(10).forEach { m ->
                try {
                    loadExtractor(m, url, subtitleCallback, callback)
                } catch (_: Throwable) {
                }
            }
        }
    }
}
