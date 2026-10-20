package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

open class StreamSB : ExtractorApi() {
    override val name = "StreamSB"
    override val mainUrl = "https://sbchill.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        guarded {
            val id = url.substringBefore('?').substringBefore('#').trimEnd('/')
                .substringAfterLast('/').removeSuffix(".html").removePrefix("embed-")
            val host = hostOf(url) ?: mainUrl
            val rnd = randomHex(12)
            val payload = toHex("$rnd||$id||$rnd||streamsb")
            for (endpoint in listOf("sources48", "sources16", "sources50")) {
                val resp = try {
                    app.get(
                        "$host/$endpoint/$payload",
                        headers = browserHeaders("watchsb" to "sbstream", "Accept" to "application/json"),
                        referer = url
                    )
                } catch (_: Throwable) { continue }
                val data = try { JSONObject(resp.text).optJSONObject("stream_data") } catch (_: Throwable) { null }
                    ?: continue
                val files = listOf(data.optString("file"), data.optString("backup")) 
                    .filter { it.startsWith("http") }.distinct()
                if (files.isEmpty()) continue
                files.forEach { f ->
                    callback(
                        newExtractorLink(name, name, f, ExtractorLinkType.M3U8) {
                            this.referer = "$host/"
                            this.quality = getQualityFromName(data.optString("title"))
                            this.headers = browserHeaders()
                        }
                    )
                }
                data.optJSONArray("subs")?.let { subs ->
                    for (i in 0 until subs.length()) {
                        val s = subs.optJSONObject(i) ?: continue
                        val file = s.optString("file")
                        if (file.isNotBlank()) subtitleCallback(SubtitleFile(s.optString("label", "Subtitle"), file))
                    }
                }
                return@guarded
            }
        }
    }
}
