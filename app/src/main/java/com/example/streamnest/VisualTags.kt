package com.example.streamnest

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Visual tags of a stream, read from its name / title / file name: the picture (Dolby Vision, HDR10+, HDR10, HDR, SDR,
 * 60 fps, 10-bit, IMAX) and the sound (Atmos, TrueHD, DTS-HD, DTS, DD+, DD, AAC, Opus · 7.1 / 5.1 / 2.0).
 * Used by Sources: files are grouped by their picture tag and shown without server or provider names.
 */
object VisualTags {
    private fun text(s: StreamItem) = listOfNotNull(s.stream.name, s.stream.title, s.stream.description, s.stream.behaviorHints?.filename)
        .joinToString(" ").lowercase()

    private val dv = Regex("(^|[^a-z])(dv|dovi|dolby[ ._-]?vision)([^a-z]|$)")
    private val hdr10p = Regex("hdr10\\+|hdr10plus|hdr10 ?plus")
    private val hdr10 = Regex("hdr10(?![+p ])|hdr-10")
    private val hdr = Regex("(^|[^a-z])hdr([^a-z]|$)")
    private val fps60 = Regex("60 ?fps|(^|[^0-9])60p")
    private val bit10 = Regex("10 ?bit|10bit")

    /** Exact tags from the Xstream formatter's SX line (v = visual tags, a = audio tags, c = channels), when present. */
    private fun sxList(s: StreamItem, k: String) = s.sx()[k]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** The main picture tag (the group in Sources). */
    fun primary(s: StreamItem): String {
        val v = sxList(s, "v").joinToString(" ").lowercase()
        if (v.isNotEmpty()) return when {
            dv.containsMatchIn(v) -> "Dolby Vision"; hdr10p.containsMatchIn(v) -> "HDR10+"; hdr10.containsMatchIn(v) -> "HDR10"; hdr.containsMatchIn(v) -> "HDR"; else -> "SDR"
        }
        val t = text(s)
        return when {
            dv.containsMatchIn(t) -> "Dolby Vision"
            hdr10p.containsMatchIn(t) -> "HDR10+"
            hdr10.containsMatchIn(t) -> "HDR10"
            hdr.containsMatchIn(t) -> "HDR"
            else -> "SDR"
        }
    }

    /** Every picture tag of the file. */
    fun video(s: StreamItem): List<String> {
        val v = sxList(s, "v")
        if (v.isNotEmpty()) return v.map { x -> when (x.lowercase()) { "dv", "dovi" -> "Dolby Vision"; "10bit" -> "10-bit"; else -> x } }.distinct()
        val t = text(s)
        return buildList {
            if (dv.containsMatchIn(t)) add("Dolby Vision")
            if (hdr10p.containsMatchIn(t)) add("HDR10+") else if (hdr10.containsMatchIn(t)) add("HDR10") else if (hdr.containsMatchIn(t)) add("HDR")
            if (isEmpty()) add("SDR")
            if (fps60.containsMatchIn(t)) add("60 fps")
            if (bit10.containsMatchIn(t)) add("10-bit")
            if ("imax" in t) add("IMAX")
        }
    }

    /** The sound: codec and channels, e.g. "DD+ 5.1" or "AAC 2.0". */
    fun audio(s: StreamItem): String? {
        val a = sxList(s, "a"); val c = sxList(s, "c")
        if (a.isNotEmpty() || c.isNotEmpty()) return (a.take(2) + c.take(1)).joinToString(" ").ifBlank { null }
        val t = text(s)
        val codec = when {
            "atmos" in t -> "Dolby Atmos"
            Regex("truehd|true-hd").containsMatchIn(t) -> "TrueHD"
            Regex("dts-?hd|dts[ ._-]?ma|dts-?x").containsMatchIn(t) -> "DTS-HD"
            Regex("(^|[^a-z])dts([^a-z]|$)").containsMatchIn(t) -> "DTS"
            Regex("ddp|dd\\+|e-?ac-?3|eac3").containsMatchIn(t) -> "DD+"
            Regex("(^|[^a-z])(dd|ac-?3)([^a-z+p]|$)").containsMatchIn(t) -> "DD"
            Regex("(^|[^a-z])aac([^a-z]|$)").containsMatchIn(t) -> "AAC"
            "opus" in t -> "Opus"
            else -> null
        }
        val ch = when {
            Regex("7[ .]1").containsMatchIn(t) -> "7.1"
            Regex("5[ .]1").containsMatchIn(t) -> "5.1"
            Regex("2[ .]0|stereo").containsMatchIn(t) -> "2.0"
            else -> null
        }
        return listOfNotNull(codec, ch).joinToString(" ").ifBlank { null }
    }

    fun size(s: StreamItem): String? {
        val b = s.stream.behaviorHints?.videoSize?.takeIf { it > 0 }
        if (b != null) return "%.1f GB".format(b / 1_073_741_824.0)
        return Regex("(\\d+(?:\\.\\d+)?)\\s*(gb|mb)(?![a-z])").find(text(s))?.let { "${it.groupValues[1]} ${it.groupValues[2].uppercase()}" }
    }

    fun quality(s: StreamItem): String = s.effectiveRes().let { r -> when { r >= 2160 -> "4K"; r >= 1440 -> "1440p"; r > 0 -> "${r}p"; else -> "SD" } }
}

/** Who may see "Dev sources" (Developer's Corner › Dev sources access): "email" or "email / profile" lines. */
/**
 * What normal users see: no server, source or addon names anywhere. Accounts on the Dev access list, and phones where
 * Developer's Corner has been opened with the password, see the real names.
 */
object Public {
    @Volatile var vm: AppViewModel? = null
    val dev: Boolean get() = vm?.let { DevAccess.allowed(it) || isOn(it.texts.value["devEver"] ?: "no") } ?: false

    private val quickNames = listOf("pengu", "aiostream", "aio streams", "moviebox", "movie box")
    private val hdNames = listOf("cncverse", "cnc verse", "hdhub", "hd hub")
    /** The developer's-choice addons (PenguPlay, AIOStreams, CNCVerse, HdHub). */
    fun isDevChoice(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        if ((quickNames + hdNames).any { it in n }) return true
        // A renamed addon: judged by the addon behind that name.
        return vm?.addons?.value?.firstOrNull { it.label() == name }?.let { a -> listOf(a.manifest.name, a.baseUrl).any { t -> (quickNames + hdNames).any { k -> k in t.lowercase() } } } == true
    }
    fun isDevChoice(a: Addon): Boolean = isDevChoice(a.manifest.name) || isDevChoice(a.baseUrl) || isDevChoice(a.manifest.id) ||
        a.baseUrl == vm?.texts?.value?.get("qpUrl")
    /**
     * Plugin streams get code names everywhere in the app (same first letter as the real one): MovieBox → Mercury,
     * Netflix → Nova… Developer accounts see the real names.
     */
    private val codes = listOf(
        "moviebox" to "Mercury", "netflix" to "Nova", "prime" to "Pulsar", "hotstar" to "Helios", "m player" to "Mars", "mplayer" to "Mars",
        "4k hdhub" to "Fornax", "4khdhub" to "Fornax", "hdhub" to "Hydra", "rogmovies" to "Rigel", "vegamovies" to "Vesta", "uhdmovies" to "Umbra",
    )
    private val stars = mapOf('a' to "Antares", 'b' to "Bellatrix", 'c' to "Castor", 'd' to "Deneb", 'e' to "Electra", 'f' to "Fomalhaut",
        'g' to "Gemma", 'h' to "Hadar", 'i' to "Izar", 'j' to "Juno", 'k' to "Kepler", 'l' to "Lyra", 'm' to "Mira", 'n' to "Nashira",
        'o' to "Orion", 'p' to "Pollux", 'q' to "Quasar", 'r' to "Regulus", 's' to "Sirius", 't' to "Titan", 'u' to "Ursa", 'v' to "Vela",
        'w' to "Wezen", 'x' to "Xena", 'y' to "Ymir", 'z' to "Zaurak")
    fun codeName(addon: String): String {
        val real = addon.removePrefix("CloudStream · ").removePrefix("Nuvio · ")
        if (dev) return real
        val n = real.lowercase().replace(Regex("provider(in)?$"), "").trim()
        codes.firstOrNull { (k, _) -> n.startsWith(k) || n == k }?.let { return it.second }
        codes.firstOrNull { (k, _) -> k in n }?.let { return it.second }
        return stars[n.firstOrNull { it.isLetter() } ?: 'n'] ?: "Nova"
    }

    /** "Quick server" / "HD server" for the developer's-choice addons; other addons keep their own name. */
    fun serverName(addon: String?): String {
        if (dev && addon != null) return addonLabel(addon)
        val n = addon?.lowercase() ?: return "Backup server"
        if (addon.startsWith("CloudStream · ") || addon.startsWith("Nuvio · ")) return codeName(addon)
        return when { quickNames.any { it in n } -> "Quick server"; hdNames.any { it in n } -> "HD server"; else -> addonLabel(addon) }
    }

    private val rules = listOf(
        Regex("(?i)\\b(pengu ?play|movie ?box|aio ?streams?(?: stable)?)\\b") to "quick server",
        Regex("(?i)\\b(cnc ?verse(?: bridge)?(?: · \\w+)?|4k ?hd ?hub|hd ?hub|vega ?movies(?:\\.to)?|hub ?cloud)\\b") to "HD server",
        Regex("(?i)\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|dev|to|net|org|io|uk|xyz|me|cc|app|site|top|live|in|ws|lol|cfd|sbs)\\b") to "server",
    )
    /** A message for the screen: server, source and addon names replaced (unchanged for developers). */
    fun say(t: String): String {
        if (dev) return t
        var s = t
        rules.forEach { (r, n) -> s = r.replace(s, n) }
        s = s.replace(Regex("(?i)(quick server|HD server|server)(\\s*[·/]\\s*(quick server|HD server|server))+"), "$1")
        return s.replaceFirstChar { it.uppercase() }
    }
}

object DevAccess {
    fun entries(vm: AppViewModel): List<String> = (vm.texts.value["devSourcesFor"] ?: "").split(",", "\n").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    fun me(vm: AppViewModel): Pair<String?, String?> {
        val email = Cloud.user.value?.email?.lowercase()
        val prof = vm.profiles.value.firstOrNull { it.id == vm.texts.value["lastProfile"] }?.name?.lowercase()
        return email to prof
    }
    fun allowed(vm: AppViewModel): Boolean {
        val (email, prof) = me(vm)
        if (email == null) return false
        return entries(vm).any { e ->
            val parts = e.split("/", limit = 2).map { it.trim() }
            parts[0] == email && (parts.getOrNull(1).isNullOrEmpty() || parts[1] == prof)
        }
    }
}

/**
 * Sources by visual tag: chips (Dolby Vision, HDR10, SDR…) in ranking order; inside, each file shows only its quality,
 * picture tags, sound and size — no server or provider names. Files whose start is already saved show "ready".
 * Only servers that play are listed (dead / keeps-failing / web-page servers are left out).
 */
@Composable
internal fun TagSourcesList(
    vm: AppViewModel, type: String, key: String, playingUrl: String?, extra: List<StreamItem>, ready: Set<String>,
    onPick: (StreamItem, List<StreamItem>) -> Unit,
) {
    var all by remember { mutableStateOf<List<StreamItem>>(emptyList()) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(key) { vm.streamsFlow(type, key).collect { (l, d) -> all = l; done = d } }
    val slow by SlowAddons.links.collectAsStateWithLifecycle()
    val list = remember(all, slow[key], extra) {
        val merged = (extra + all + slow[key].orEmpty()).distinctBy { it.stream.url }
            .filter { !Ranker.isDead(it) && !Ranker.keepsFailing(it) && HostStats.penalty(it.stream.url.orEmpty()) == 0 && !it.unplayableHere() }
        vm.filtered(merged).list.sortedWith(compareByDescending<StreamItem> { Ranker.score(it) }.thenByDescending { it.effectiveRes() })
    }
    val groups = remember(list, vm.texts.collectAsStateWithLifecycle().value["picPref"]) { tagGroups(list, PicturePref.order(vm)) }
    var tag by remember { mutableStateOf<String?>(null) }
    val sel = tag?.takeIf { t -> groups.any { it.first == t } } ?: groups.firstOrNull()?.first
    val first = LocalFirstFocus.current
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEachIndexed { i, (t, files) ->
                val on = t == sel
                Row(
                    Modifier.clip(RoundedCornerShape(18.dp)).then(if (i == 0 && first != null) Modifier.focusRequester(first) else Modifier)
                        .background(if (on) Color(0x33E50914) else Color(0x14FFFFFF))
                        .border(1.dp, if (on) Color(0xCCE50914) else Color(0x1FFFFFFF), RoundedCornerShape(18.dp))
                        .clickable { tag = t }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(t, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold)
                    Text("  ${files.size}", fontSize = 12.sp, color = Color(0xFF9AA3B5))
                    if (files.firstOrNull()?.stream?.url in ready) Box(Modifier.padding(start = 6.dp).size(6.dp).clip(CircleShape).background(Color(0xFFFF3B47)))
                }
            }
        }
        val files = groups.firstOrNull { it.first == sel }?.second.orEmpty()
        when {
            files.isEmpty() && !done -> Box(Modifier.fillMaxWidth().padding(30.dp), Alignment.Center) { CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp) }
            files.isEmpty() -> Text("No sources found.", color = Color(0xFF9AA3B5))
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files, key = { it.stream.url ?: "" }) { s -> TagFileRow(s, s.stream.url == playingUrl, s.stream.url in ready) { onPick(s, list) } }
                if (!done) item { Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp) } }
            }
        }
    }
}

/** Groups in ranking order (the group of the best-ranked file first); each group's files in ranking order. */
internal fun tagGroups(ranked: List<StreamItem>, order: List<String>? = null): List<Pair<String, List<StreamItem>>> =
    ranked.groupBy { VisualTags.primary(it) }.toList().sortedBy { (_, l) -> ranked.indexOf(l.first()) }
        .let { g -> if (order == null) g else g.sortedBy { (t, _) -> order.indexOf(t).let { i -> if (i < 0) 99 else i } } }

@Composable
private fun TagFileRow(s: StreamItem, playing: Boolean, ready: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (playing) Color(0x24E50914) else Color(0x0FFFFFFF))
            .border(1.dp, if (playing) Color(0x99E50914) else Color(0x14FFFFFF), shape)
            .clickable(enabled = !playing) { onClick() }.padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(VisualTags.quality(s), Modifier.width(62.dp), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f)) {
            Text(VisualTags.video(s).joinToString(" · "), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(VisualTags.audio(s), VisualTags.size(s), s.languagesOrOriginal().joinToString(" + ") { langLabel(it) }).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = Color(0xFF9AA3B5), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            playing -> Text("Playing", fontSize = 12.sp, color = Color(0xFFFF6B74), fontWeight = FontWeight.Bold)
            ready -> Text("⚡ Ready", fontSize = 12.sp, color = Color(0xFFFF6B74), fontWeight = FontWeight.SemiBold)
        }
    }
}
