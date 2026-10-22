package com.example.streamnest

import androidx.compose.ui.graphics.Color

/*
 * Everything about reading stream names: visual tags (HDR, Dolby Vision, 4K…), Fusion-style badges,
 * include / exclude / forced filters and the regex auto-play chain.
 */

/** Text that describes a stream (name, title, description, file name). */
fun StreamItem.text(): String = listOfNotNull(
    stream.name, stream.title, stream.description, stream.behaviorHints?.filename, addonName,
).joinToString(" \n ")

// ───────────── Visual tags ─────────────

data class TagDef(val id: String, val label: String, val group: String, val re: Regex)

private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

private val HdrAny = rx("""\bhdr(10)?\b|hdr10\+|hdr10plus""")
private val DvAny = rx("""\b(dv|dovi|dolby[ ._-]?vision)\b""")

val TagDefs = listOf(
    // Visual
    TagDef("hdr", "HDR", "Visual", HdrAny),
    TagDef("hdr10", "HDR10", "Visual", rx("""\bhdr10\b(?!\+)""")),
    TagDef("hdr10p", "HDR10+", "Visual", rx("""hdr10\+|hdr10plus""")),
    TagDef("dv", "Dolby Vision", "Visual", DvAny),
    TagDef("sdr", "SDR", "Visual", rx("""\bsdr\b""")),
    TagDef("3d", "3D", "Visual", rx("""\b3d\b|\bh-?sbs\b""")),
    // Resolution
    TagDef("4k", "4K", "Resolution", rx("""\b(2160p|4k|uhd)\b""")),
    TagDef("1440p", "1440p", "Resolution", rx("""\b(1440p|2k)\b""")),
    TagDef("1080p", "1080p", "Resolution", rx("""\b(1080p|fhd)\b""")),
    TagDef("720p", "720p", "Resolution", rx("""\b720p\b""")),
    TagDef("480p", "480p", "Resolution", rx("""\b(480p|576p|360p|sd)\b""")),
    // Source / quality
    TagDef("remux", "REMUX", "Source", rx("""\bremux\b""")),
    TagDef("bluray", "BluRay", "Source", rx("""\b(blu-?ray|bdrip|brrip|bdremux)\b""")),
    TagDef("webdl", "WEB-DL", "Source", rx("""\bweb[ ._-]?dl\b""")),
    TagDef("webrip", "WEBRip", "Source", rx("""\bweb[ ._-]?rip\b""")),
    TagDef("hdtv", "HDTV", "Source", rx("""\bhdtv\b""")),
    TagDef("cam", "CAM", "Source", rx("""\b(cam|camrip|hdcam|hq-?cam)\b""")),
    TagDef("ts", "TS / TC", "Source", rx("""\b(hd-?ts|telesync|ts-?rip|tc|telecine|hdtc)\b|\bts\b""")),
    // Codec
    TagDef("hevc", "HEVC / H.265", "Codec", rx("""\b(hevc|x265|h[ .]?265)\b""")),
    TagDef("av1", "AV1", "Codec", rx("""\bav1\b""")),
    TagDef("h264", "H.264", "Codec", rx("""\b(x264|h[ .]?264|avc)\b""")),
    TagDef("10bit", "10bit", "Codec", rx("""\b10[ ._-]?bits?\b""")),
    // Audio
    TagDef("atmos", "Atmos", "Audio", rx("""\batmos\b""")),
    TagDef("truehd", "TrueHD", "Audio", rx("""\btrue[ ._-]?hd\b""")),
    TagDef("dts", "DTS", "Audio", rx("""\bdts([ ._-]?(hd|x|ma))?\b""")),
    TagDef("ddp", "Dolby Digital+", "Audio", rx("""\b(ddp|dd\+|e-?ac-?3|eac3)|dolby digital plus""")),
    TagDef("51", "5.1", "Audio", rx("""\b5[ ._]1\b""")),
    TagDef("71", "7.1", "Audio", rx("""\b7[ ._]1\b""")),
)

/** Which tag ids a stream has. SDR is also assumed when there is no HDR / Dolby Vision marker at all. */
fun StreamItem.tags(): Set<String> {
    val t = text()
    val out = TagDefs.filter { it.re.containsMatchIn(t) }.map { it.id }.toMutableSet()
    if ("hdr10" in out || "hdr10p" in out) out += "hdr"
    if ("hdr" !in out && "dv" !in out) out += "sdr"
    return out
}

// ───────────── Filters: tagRules = "hdr:req|cam:ex|dv:in" ─────────────

enum class TagRule(val key: String, val label: String) { Off("", "Off"), Include("in", "Include"), Exclude("ex", "Exclude"), Forced("req", "Forced") }

fun parseTagRules(s: String): Map<String, TagRule> = s.split("|").mapNotNull { part ->
    val (id, r) = part.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
    val rule = TagRule.entries.firstOrNull { it.key == r && it != TagRule.Off } ?: return@mapNotNull null
    id to rule
}.toMap()

/** The user's own filter changes, Off included ("hdr:off" = "show HDR even though the developer hid it"). */
fun parseUserRules(s: String): Map<String, TagRule> = s.split("|").mapNotNull { part ->
    val (id, r) = part.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
    id to (if (r == "off") TagRule.Off else TagRule.entries.firstOrNull { it.key == r && it != TagRule.Off } ?: return@mapNotNull null)
}.toMap()
fun encodeUserRules(m: Map<String, TagRule>) = m.entries.joinToString("|") { "${it.key}:${if (it.value == TagRule.Off) "off" else it.value.key}" }

/**
 * Filters = the developer's published picks with the user's own changes on top, tag by tag: hiding HDR yourself while
 * the developer hides CAM keeps both; showing something the developer hid only changes that one tag.
 */
fun effectiveRules(dev: String?, user: String?): Map<String, TagRule> {
    val m = parseTagRules(dev.orEmpty()).toMutableMap()
    parseUserRules(user.orEmpty()).forEach { (k, r) -> if (r == TagRule.Off) m.remove(k) else m[k] = r }
    return m
}

fun encodeTagRules(m: Map<String, TagRule>) = m.filterValues { it != TagRule.Off }.entries.joinToString("|") { "${it.key}:${it.value.key}" }

data class Filtered(
    val list: List<StreamItem>,
    val hidden: Int,
    /** forced tags removed every stream (show "Show all" escape hatch) */
    val forcedEmpty: Boolean,
    val forcedLabels: List<String>,
)

/**
 * Exclude: never shown. Forced: only streams with (any of) the forced tags.
 * Include: kept with everything else but moved to the top and preferred by auto-play.
 */
fun applyTagRules(all: List<StreamItem>, rules: Map<String, TagRule>): Filtered {
    if (rules.isEmpty()) return Filtered(all, 0, false, emptyList())
    val ex = rules.filterValues { it == TagRule.Exclude }.keys
    val req = rules.filterValues { it == TagRule.Forced }.keys
    val inc = rules.filterValues { it == TagRule.Include }.keys
    val tagged = all.map { it to it.tags() }
    var kept = tagged.filter { (_, t) -> t.none { it in ex } }
    val beforeForced = kept.size
    if (req.isNotEmpty()) kept = kept.filter { (_, t) -> t.any { it in req } }
    if (inc.isNotEmpty()) kept = kept.sortedByDescending { (_, t) -> t.count { it in inc } }
    val labels = req.mapNotNull { id -> TagDefs.firstOrNull { it.id == id }?.label }
    return Filtered(kept.map { it.first }, all.size - kept.size, req.isNotEmpty() && kept.isEmpty() && beforeForced > 0, labels)
}

// ───────────── Regex auto-play with fallbacks (one pattern per line) ─────────────

fun regexList(s: String): List<String> = s.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

/** First stream matching pattern 1; if none, pattern 2; … Returns the match and which pattern hit (0-based). */
fun matchChain(list: List<StreamItem>, patterns: List<String>): Pair<StreamItem, Int>? {
    patterns.forEachIndexed { i, p ->
        val r = runCatching { Regex(p, RegexOption.IGNORE_CASE) }.getOrNull() ?: return@forEachIndexed
        list.firstOrNull { r.containsMatchIn(it.text() + " " + (it.stream.url ?: "")) }?.let { return it to i }
    }
    return null
}

// ───────────── Fusion-style badges ─────────────

enum class BadgeKind(val bg: Color, val fg: Color) {
    Res(Color(0xFFE8B931), Color.Black),
    Hdr(Color(0xFF2B2140), Color(0xFFE2C8FF)),
    Dv(Color(0xFF1B1B1F), Color.White),
    Audio(Color(0xFF16263A), Color(0xFF9FD0FF)),
    Source(Color(0xFF26262C), Color(0xFFDADDE4)),
    Codec(Color(0xFF1F2A22), Color(0xFFA8E6B5)),
    Service(Color(0xFF2A1A1C), Color(0xFFFFB4B4)),
    Size(Color(0xFF26262C), Color(0xFFB6BDCC)),
}

data class Badge(val text: String, val kind: BadgeKind, val image: String? = null)

private val Services = listOf(
    "Prime Video" to rx("""\b(amzn|amazon|prime[ ._-]?video)\b"""),
    "Netflix" to rx("""\b(nf|netflix)\b"""),
    "Disney+" to rx("""\b(dsnp|disney\+?|disneyplus)\b"""),
    "Apple TV+" to rx("""\b(atvp|apple ?tv\+?)\b"""),
    "HBO Max" to rx("""\b(hmax|hbo ?max)\b"""),
    "Hotstar" to rx("""\b(hs|jio ?hotstar|hotstar)\b"""),
    "JioCinema" to rx("""\b(jc|jio ?cinema)\b"""),
    "Hulu" to rx("""\bhulu\b"""),
    "Peacock" to rx("""\b(pcok|peacock)\b"""),
    "Paramount+" to rx("""\b(pmtp|paramount\+?)\b"""),
    "ZEE5" to rx("""\bzee5\b"""),
    "SonyLIV" to rx("""\b(sonyliv|sliv)\b"""),
)

private val SizeRe = rx("""(\d+(?:[.,]\d+)?)\s?(gb|mb|gib|mib)\b""")

fun humanSize(bytes: Long): String = if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1L shl 30).toDouble()) else "%.0f MB".format(bytes / (1L shl 20).toDouble())

/** Badge pack: user JSON of pattern -> image URL (for service logos etc.). */
data class PackBadge(val re: Regex, val image: String, val label: String)

fun StreamItem.badges(pack: List<PackBadge> = emptyList()): List<Badge> {
    val t = text()
    val tags = tags()
    val out = ArrayList<Badge>()
    when {
        "4k" in tags -> out += Badge("4K", BadgeKind.Res)
        "1440p" in tags -> out += Badge("1440p", BadgeKind.Res)
        "1080p" in tags -> out += Badge("1080p", BadgeKind.Res)
        "720p" in tags -> out += Badge("720p", BadgeKind.Res)
        "480p" in tags -> out += Badge("SD", BadgeKind.Res)
    }
    if ("dv" in tags) out += Badge("Dolby Vision", BadgeKind.Dv)
    when {
        "hdr10p" in tags -> out += Badge("HDR10+", BadgeKind.Hdr)
        "hdr10" in tags -> out += Badge("HDR10", BadgeKind.Hdr)
        "hdr" in tags -> out += Badge("HDR", BadgeKind.Hdr)
    }
    if ("3d" in tags) out += Badge("3D", BadgeKind.Hdr)
    if ("atmos" in tags) out += Badge("Atmos", BadgeKind.Audio)
    when {
        "truehd" in tags -> out += Badge("TrueHD", BadgeKind.Audio)
        "dts" in tags -> out += Badge("DTS", BadgeKind.Audio)
        "ddp" in tags -> out += Badge("Dolby Digital+", BadgeKind.Audio)
        rx("""\b(dd|ac-?3)\b|dolby digital""").containsMatchIn(t) -> out += Badge("Dolby Digital", BadgeKind.Audio)
        rx("""\baac\b""").containsMatchIn(t) -> out += Badge("AAC", BadgeKind.Audio)
    }
    when {
        "71" in tags -> out += Badge("7.1", BadgeKind.Audio)
        "51" in tags -> out += Badge("5.1", BadgeKind.Audio)
    }
    listOf("remux" to "REMUX", "bluray" to "BluRay", "webdl" to "WEB-DL", "webrip" to "WEBRip", "hdtv" to "HDTV", "cam" to "CAM", "ts" to "TS")
        .firstOrNull { it.first in tags }?.let { out += Badge(it.second, BadgeKind.Source) }
    if ("10bit" in tags) out += Badge("10bit", BadgeKind.Codec)
    when {
        "hevc" in tags -> out += Badge("H.265", BadgeKind.Codec)
        "av1" in tags -> out += Badge("AV1", BadgeKind.Codec)
        "h264" in tags -> out += Badge("H.264", BadgeKind.Codec)
    }
    val packHits = pack.filter { it.re.containsMatchIn(t) }
    if (packHits.isNotEmpty()) packHits.forEach { out += Badge(it.label, BadgeKind.Service, it.image) }
    else Services.firstOrNull { it.second.containsMatchIn(t) }?.let { out += Badge(it.first, BadgeKind.Service) }
    val size = stream.behaviorHints?.videoSize?.takeIf { it > 0 }?.let { humanSize(it) }
        ?: SizeRe.find(t)?.let { "${it.groupValues[1].replace(',', '.')} ${it.groupValues[2].uppercase().replace("IB", "B")}" }
    size?.let { out += Badge(it, BadgeKind.Size) }
    return out
}

/** Readable lines from the addon's description (emoji-rich addons keep their look). */
fun StreamItem.detailLines(): List<String> =
    listOfNotNull(stream.title, stream.description).joinToString("\n").split("\n").map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("SX|") }.distinct().take(5)
