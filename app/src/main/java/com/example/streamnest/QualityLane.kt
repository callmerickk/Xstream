package com.example.streamnest

import kotlinx.coroutines.withTimeoutOrNull

/** 0.9.7 (addonFirst): asks the quality-lane addons (CNCVerse, HdHub) once through the cloud finder and judges "clearly better". */
object QualityLane {
    private fun lane(vm: AppViewModel, type: String, id: String): List<Addon> = vm.activeAddons().filter { a ->
        a.supports("stream", type, id) && (AddonFirst.isCncName(a.label()) || AddonFirst.isCncName(a.manifest.name) ||
            AddonFirst.isHdHubName(a.label()) || AddonFirst.isHdHubName(a.manifest.name))
    }
    fun hasAddons(vm: AppViewModel, type: String, id: String) = lane(vm, type, id).isNotEmpty()

    /** One cloud ask per title (Singleflight), cancellable (CloudFind.find stops with the caller), plus what the title
     *  page's CNCVerse schedule already has. Empty on any failure. */
    suspend fun fetch(vm: AppViewModel, type: String, id: String): List<StreamItem> = runCatching {
        Singleflight.run("qlane|$type|$id") {
            val addons = lane(vm, type, id)
            val byBase = addons.associateBy { CloudFind.baseOf(it.baseUrl) }
            val m = withTimeoutOrNull(25_000) { CloudFind.find(type, id, byBase.keys.toList()) }.orEmpty()
            val fromCloud = m.flatMap { (base, l) ->
                val a = byBase[base] ?: return@flatMap emptyList()
                CloudFind.markCloud(base, l.map { it.url })
                l.filter { !it.url.isNullOrBlank() }.map { StreamItem(a.label(), it).also { s -> StreamHeaders.remember(s) } }
            }
            (fromCloud + SlowAddons.links.value[id].orEmpty()).distinctBy { it.stream.url }.filter { AddonFirst.qualityOnly(it) }
        }
    }.getOrDefault(emptyList())

    private fun fps60(s: StreamItem) = Regex("(?i)\\b(50|59|60)\\s?fps\\b").containsMatchIn(listOfNotNull(s.stream.name, s.stream.title, s.stream.description, s.stream.behaviorHints?.filename).joinToString(" "))
    private fun size(s: StreamItem) = s.stream.behaviorHints?.videoSize ?: 0L

    /** null = not clearly better. */
    fun reason(cur: StreamItem, c: StreamItem, hdrOk: Boolean): String? {
        val rc = c.effectiveRes(); val rn = cur.effectiveRes()
        return when {
            rc >= 2160 && rn < 2160 -> "4K"
            rc > rn && rn > 0 && rc >= 1080 -> "${rc}p"
            rc >= rn && rn > 0 && fps60(c) && !fps60(cur) -> "60 fps"
            rc >= rn && rn > 0 && hdrOk && HdrRule.named(c) && !HdrRule.named(cur) -> "HDR"
            rc == rn && rn > 0 && size(c) > 0 && size(cur) > 0 && size(c) >= size(cur) * 1.35 -> "higher bitrate"
            else -> null
        }
    }
    fun score(s: StreamItem): Long = s.effectiveRes() * 1_000_000_000L + (if (fps60(s)) 1_000_000L else 0L) + (size(s) / 1_000_000)
    fun label(s: StreamItem): String = listOf(VisualTags.quality(s), VisualTags.primary(s)).filter { it.isNotBlank() }.joinToString(" ")
}
