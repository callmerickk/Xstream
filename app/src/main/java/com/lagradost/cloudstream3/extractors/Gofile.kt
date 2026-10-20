package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

open class Gofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false

    private val videoExt = listOf(".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".ts")

    private fun collect(node: JSONObject, out: MutableList<JSONObject>, depth: Int) {
        val children = node.optJSONObject("children") ?: return
        val keys = children.keys()
        while (keys.hasNext()) {
            val child = children.optJSONObject(keys.next()) ?: continue
            if (child.optString("type") == "folder") {
                if (depth < 4) collect(child, out, depth + 1)
            } else {
                val n = child.optString("name").lowercase()
                if (child.optString("mimetype").startsWith("video") || videoExt.any { n.endsWith(it) }) {
                    out.add(child)
                }
            }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        guarded {
            val id = url.substringAfter("/d/", "").substringBefore('?').substringBefore('#')
                .substringBefore('/')
            if (id.isBlank()) return@guarded
            val apiHeaders = browserHeaders("Origin" to "https://gofile.io")
            val acc = app.post("https://api.gofile.io/accounts", headers = apiHeaders, referer = "https://gofile.io/")
            val token = JSONObject(acc.text).getJSONObject("data").getString("token")
            val listing = app.get(
                "https://api.gofile.io/contents/$id?wt=4fd6sg89d7s6",
                headers = browserHeaders("Origin" to "https://gofile.io", "Authorization" to "Bearer $token"),
                referer = "https://gofile.io/"
            )
            val data = JSONObject(listing.text).optJSONObject("data") ?: return@guarded
            val files = mutableListOf<JSONObject>()
            collect(data, files, 0)
            for (f in files) {
                val link = f.optString("link")
                if (link.isBlank()) continue
                val fname = f.optString("name")
                callback(
                    newExtractorLink(name, "$name $fname".trim(), link, ExtractorLinkType.VIDEO) {
                        this.referer = "https://gofile.io/"
                        this.quality = getQualityFromName(fname)
                        this.headers = browserHeaders("Cookie" to "accountToken=$token")
                    }
                )
            }
        }
    }
}
