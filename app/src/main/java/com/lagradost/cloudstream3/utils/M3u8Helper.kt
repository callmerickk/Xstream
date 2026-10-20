package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.app

/** CloudStream's HLS helper: one link per quality listed in a master playlist (or the playlist itself). */
class M3u8Helper {
    companion object {
        suspend fun generateM3u8(source: String, streamUrl: String, referer: String, quality: Int? = null,
                                 headers: Map<String, String> = mapOf(), name: String = source): List<ExtractorLink> {
            val base = ExtractorLink(source, name, streamUrl, referer, quality ?: Qualities.Unknown.value, headers, null, ExtractorLinkType.M3U8)
            val text = runCatching { app.get(streamUrl, headers = headers, referer = referer.ifBlank { null }).text }.getOrNull() ?: return listOf(base)
            if (!text.contains("#EXT-X-STREAM-INF")) return listOf(base)
            val out = ArrayList<ExtractorLink>()
            val lines = text.lines()
            for (i in lines.indices) {
                val l = lines[i]
                if (!l.startsWith("#EXT-X-STREAM-INF")) continue
                val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: continue
                val h = Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull()
                val abs = runCatching { java.net.URI(streamUrl).resolve(uri).toString() }.getOrDefault(uri)
                out += ExtractorLink(source, name, abs, referer, h ?: quality ?: Qualities.Unknown.value, headers, null, ExtractorLinkType.M3U8)
            }
            return out.ifEmpty { listOf(base) }
        }
    }
}
