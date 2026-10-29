package com.example.streamnest

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * Quick Start (Content & Discovery > Quick Start):
 *  Group 1 = fast addons used when you press Play, with a Hotstar-style quality selector (Auto / Full HD / Mid HD / Low)
 *  and language versions in the Audio panel. Group 2 = every other addon, in the Sources panel.
 */

data class QualityLevel(val id: String, val label: String, val res: Int)

val QualityLevels = listOf(
    QualityLevel("auto", "Auto", 0),
    QualityLevel("fhd", "Full HD", 1080),
    QualityLevel("mhd", "HD 720p", 720),
    QualityLevel("low", "Low", 480),
)

/** 4K: offered in the player's quality menu only when a 2160p file exists. */
val Quality4K = QualityLevel("4k", "4K", 2160)
val AllQualityLevels = listOf(QualityLevels.first(), Quality4K) + QualityLevels.drop(1)

val QsLanguages = linkedMapOf(
    // "Original audio" only when the file really says so (Original / Orig / Org).
    "original" to listOf("original", "orig", "org", "orginal", "original audio", "org audio"),
    "hindi" to listOf("hindi", "hin", "hi", "हिन्दी", "हिंदी", "🇮🇳"), "english" to listOf("english", "eng", "en", "🇺🇸", "🇬🇧"),
    "tamil" to listOf("tamil", "tam"), "telugu" to listOf("telugu", "tel"), "malayalam" to listOf("malayalam", "mal"),
    "kannada" to listOf("kannada", "kan"), "bengali" to listOf("bengali", "bangla"), "marathi" to listOf("marathi"),
    "punjabi" to listOf("punjabi"), "japanese" to listOf("japanese", "jpn", "🇯🇵"), "korean" to listOf("korean", "kor", "🇰🇷"),
    "spanish" to listOf("spanish", "spa", "español", "latino", "🇪🇸"), "french" to listOf("french", "fre", "fra", "🇫🇷"),
    "german" to listOf("german", "ger", "deu", "🇩🇪"), "chinese" to listOf("chinese", "chi", "mandarin", "🇨🇳"),
    "russian" to listOf("russian", "rus", "🇷🇺"), "italian" to listOf("italian", "ita", "🇮🇹"), "portuguese" to listOf("portuguese", "por", "🇧🇷", "🇵🇹"),
    "arabic" to listOf("arabic", "ara"), "turkish" to listOf("turkish", "tur"), "thai" to listOf("thai"), "indonesian" to listOf("indonesian"),
)

private val langRegex: Map<String, List<Regex>> = QsLanguages.mapValues { (_, words) ->
    words.map { w ->
        // Short words must stand alone ("hin", "org"); longer ones may be part of a word ("hindi-dubbed").
        if (w.length <= 4 && w.all { it.isLetter() && it.code < 128 }) Regex("(?<![a-z])${Regex.escape(w)}(?![a-z])") else Regex(Regex.escape(w))
    }
}

/** Resolution of a stream from its text (0 = unknown). */
fun StreamItem.resolution(): Int {
    // The formatter's exact value first, then the file name (an addon may label a 1080p file "2160p"), then any text.
    sx()["r"]?.let { resIn(it) }?.let { return it }
    resIn(fileName())?.let { return it }
    val t = tags()
    return when { "4k" in t -> 2160; "1440p" in t -> 1440; "1080p" in t -> 1080; "720p" in t -> 720; "480p" in t -> 480; else -> 0 }
}

private val res4k = Regex("(?i)2160p|(?<![a-z0-9])(4k|uhd)(?![a-z0-9])")
private fun resIn(t: String): Int? = when {
    t.isBlank() -> null
    res4k.containsMatchIn(t) -> 2160
    t.contains("1440p", true) -> 1440
    t.contains("1080p", true) -> 1080
    t.contains("720p", true) -> 720
    t.contains("480p", true) || t.contains("576p", true) || t.contains("360p", true) -> 480
    else -> null
}

private val langCodes = mapOf("en" to "english", "hi" to "hindi", "fr" to "french", "es" to "spanish", "de" to "german", "ta" to "tamil",
    "te" to "telugu", "ml" to "malayalam", "kn" to "kannada", "bn" to "bengali", "mr" to "marathi", "pa" to "punjabi", "ja" to "japanese",
    "ko" to "korean", "zh" to "chinese", "ru" to "russian", "it" to "italian", "pt" to "portuguese", "ar" to "arabic", "tr" to "turkish",
    "th" to "thai", "id" to "indonesian")

/** The text a stream's languages are read from: its name, title, description and file name (not the addon's name). */
private fun StreamItem.langText(): String = " " + listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename)
    .joinToString(" \n ").lowercase().replace('_', ' ').replace('.', ' ') + " "

/** Languages named in a stream's text, e.g. ["hindi","english"]. "multi" when it says Multi / Dual audio. */
fun StreamItem.languages(): List<String> {
    val t = langText()
    val out = langRegex.filter { (_, res) -> res.any { it.containsMatchIn(t) } }.keys.toMutableList()
    // "hi"/"en" alone are too common in titles; only count them inside a language list like "hi, en" / "[hi]".
    if ("hindi" in out && !Regex("hindi|hin(?![a-z])|हिन्दी|हिंदी|🇮🇳").containsMatchIn(t) && !Regex("[\\[(|/,]\\s*hi\\s*[\\])|/,]").containsMatchIn(t)) out -= "hindi"
    if ("english" in out && !Regex("english|eng(?![a-z])|🇺🇸|🇬🇧").containsMatchIn(t) && !Regex("[\\[(|/,]\\s*en\\s*[\\])|/,]").containsMatchIn(t)) out -= "english"
    if (Regex("multi|dual[ -]?audio").containsMatchIn(t)) out += "multi"
    // The formatter's language codes (exact).
    sx()["l"]?.split(',')?.mapNotNull { langCodes[it.trim().lowercase().take(2)] }?.forEach { if (it !in out) out += it }
    // MovieBox names every dub; a MovieBox file that names no language is the original audio.
    if (out.none { it != "multi" } && isMovieBox()) out += "original"
    return out
}

/**
 * Plugin mode (Xstream phones, Settings › Sources: Addons / Plugins): every stream comes from CloudStream / Nuvio plugins
 * and no addon is asked. Addon mode (default): addons only, plugins are never loaded.
 */
fun AppViewModel.pluginMode(): Boolean = !BuildConfig.IS_TV && texts.value["srcMode"] == "plugins"
/** Live mode (64-bit app): only the Live server's links. */
fun AppViewModel.liveMode(): Boolean = !BuildConfig.IS_TV && texts.value["srcMode"] == "live" && LiveServer.available(appContext)
/** The name a CloudStream provider's links carry (StreamItem.addonName). */
fun csDisplayName(provider: String) = "CloudStream · $provider"
/** Quick Start: in plugin mode the picked CloudStream providers (by their link name), else the picked addons. */
fun AppViewModel.qsAddons(): Set<String> = if (pluginMode()) qsCs().map { csDisplayName(it.substringAfter('#')) }.toSet() + MB_ANY + OTT_ANY + VEGA_ANY
    else (texts.value["qsAddons"] ?: "").split("|").filter { it.isNotBlank() }.toSet()
/** Plugin mode: providers whose 4K links "Upgrade to 4K" may use ("pluginKey#provider", one per line). */
fun AppViewModel.qs4k(): Set<String> = if (!pluginMode()) emptySet() else qsCsParse(texts.value["qs4k"])
fun AppViewModel.qs4kNames(): Set<String> = if (!pluginMode()) emptySet() else qs4k().map { csDisplayName(it.substringAfter('#')) }.toSet() + VEGA_ANY
/** VegaMovies always joins Upgrade to 4K (first in line), whether ticked or not. */
const val VEGA_ANY = "CloudStream · *vega*"
/** Plugin mode: Quick Play always includes the MovieBox plugins (the instant backup), whether ticked or not. */
const val MB_ANY = "CloudStream · *moviebox*"
/** Plugin mode: every Netflix / Prime Video / Hotstar / M Player / AllOtt provider is part of Quick Play. */
const val OTT_ANY = "CloudStream · *ott*"
fun isOttName(n: String) = n.lowercase().let { "netflix" in it || "prime" in it || "hotstar" in it || "mplayer" in it || "m player" in it || "allott" in it || "disney" in it }
/** CloudStream providers picked for Quick Play (Settings › Quick Start › CloudStream), as "pluginKey#provider". Phones only. */
fun AppViewModel.qsCs(): Set<String> = if (BuildConfig.IS_TV) emptySet() else qsCsParse(texts.value["qsCs"])
/** Saved one per line (plugin keys contain "|" themselves, so "|" can't separate them). */
fun qsCsParse(raw: String?): Set<String> = (raw ?: "").split("\n").filter { it.isNotBlank() }.toSet()
fun csProviderKey(pluginKey: String, provider: String) = "$pluginKey#$provider"
fun AppViewModel.qsActive(): Boolean = isOn(texts.value["qsOn"] ?: "yes") && (qsAddons().isNotEmpty() || LiveServer.enabled(appContext))
fun AppViewModel.qsLangs(): List<String> = (texts.value["qsLangs"] ?: "hindi,english,original").split(",").map { it.trim() }.filter { it.isNotEmpty() }

/** Does this stream come from a Quick Start addon? */
fun AppViewModel.isQuick(s: StreamItem) = s.addonName == LiveServer.NAME || (pluginMode() && (s.isMovieBox() || s.isOtt() || s.isVega())) || s.addonName in qsAddons() &&
    // An all-in-one addon (AIOStreams) in Quick Start: only its MovieBox links count for quick play (its other servers
    // mostly answer nothing in time); everything else of it stays in All sources.
    (!(s.addonName.contains("aio", ignoreCase = true) || addons.value.firstOrNull { it.label() == s.addonName }?.baseUrl?.contains("aiostreams", true) == true) || s.isMovieBox())

/** Languages of a stream for grouping; a file that names no language is "unlabelled" (never assumed to be the original). */
fun StreamItem.languagesOrOriginal(): List<String> = languages().filter { it != "multi" }.ifEmpty { listOf(if ("multi" in languages()) "multi" else "unlabelled") }

fun langLabel(l: String) = when (l) { "original" -> "Original audio"; "multi" -> "Multi audio"; "unlabelled" -> "Unlabelled"; else -> l.replaceFirstChar { it.uppercase() } }

/** Language score: lower is better. Your order first (Hindi → English → Original audio), then multi-audio, then unlabelled files, then other languages. */
/** The audio language you last picked for the title that is open (kept per title / series), put before your usual order. */
object LangPick { @Volatile var current: String? = null }
fun AppViewModel.titleLang(key: String): String? = playerPref("lang_" + key.substringBefore(':')).ifBlank { null }
fun AppViewModel.setTitleLang(key: String, lang: String) {
    if (lang.isBlank() || lang == "multi" || lang == "unlabelled") return
    setPlayerPref("lang_" + key.substringBefore(':'), lang)
    LangPick.current = lang
}

fun AppViewModel.langRank(s: StreamItem): Int {
    val langs = s.languages()
    val pref = LangPick.current?.let { c -> listOf(c) + qsLangs().filter { it != c } } ?: qsLangs()
    val best = pref.indexOfFirst { it in langs }
    return when { best >= 0 -> best; "multi" in langs -> pref.size; langs.isEmpty() -> pref.size + 1; else -> pref.size + 2 }
}

/**
 * Quick play = MovieBox first: its streaming playlists start in a fraction of a second, so when one is in a language as
 * good as the best link's, it goes ahead of every other server (HdHub / CNCVerse files take 3–8 s to open). The HD
 * files come afterwards through "Upgrade quality".
 */
fun AppViewModel.movieBoxFirst(l: List<StreamItem>): List<StreamItem> {
    if (l.isEmpty()) return l
    val bestLang = l.minOf { langRank(it) }
    val mb = l.filter { it.isMovieBox() && it.isPlaylist() && !Ranker.isDead(it) && langRank(it) <= bestLang }
    if (mb.isEmpty()) return l
    val set = mb.toSet()
    return mb + l.filter { it !in set }
}

/**
 * Best Quick Start stream for a quality level. A chosen level (4K / Full HD / Mid HD / Low) plays a file of THAT quality
 * (your language first inside it, then the Source Test's most reliable provider); Auto = your language first, then up to
 * Full HD. Groups under observation go last.
 */
fun AppViewModel.pickQuick(list: List<StreamItem>, level: String): StreamItem? {
    val quick = list.filter { isQuick(it) }
    if (quick.isEmpty()) return null
    val target = AllQualityLevels.firstOrNull { it.id == level }?.res ?: 0
    if (target == 0) {
        val observedLast = compareBy<StreamItem>({ if (Ranker.score(it) <= -1000.0) 1 else 0 }, { if (it.unplayableHere()) 1 else 0 })
        // TV: Auto never plays above 1080p (4K files stutter on TV sticks), unless there is nothing else.
        if (BuildConfig.IS_TV) {
            val upTo1080 = quick.filter { it.resolution() <= 1080 }
            if (upTo1080.isNotEmpty()) return upTo1080.sortedWith(observedLast.thenBy { langRank(it) }
                .thenBy { r -> val res = r.resolution(); if (res in 1..1080) -res else 10_000 }.thenBy { if (it.isHeavy()) 1 else 0 }.thenByDescending { Ranker.score(it) }).first()
        }
        // Auto: MovieBox's instant playlist first (when it has your best language); else best language, then the highest
        // quality up to Full HD (fast to start), then the most reliable provider.
        movieBoxFirst(quick.filter { Ranker.score(it) > -1000.0 }).firstOrNull()?.takeIf { it.isMovieBox() && it.isPlaylist() }?.let { return it }
        return quick.sortedWith(observedLast.thenBy { langRank(it) }
            .thenBy { r -> val res = r.resolution(); if (res in 1..1080) -res else 10_000 - res }.thenBy { if (it.isHeavy()) 1 else 0 }.thenByDescending { Ranker.score(it) }).first()
    }
    return rankedCandidates(quick, level, quickOnly = false).firstOrNull()
}

/** Which quality levels exist for this title, and which file each one plays (duplicates point to the same file). */
fun AppViewModel.qualityMap(list: List<StreamItem>, show4k: Boolean = true): List<Pair<QualityLevel, StreamItem?>> =
    // 4K is listed only when it is playing / chosen (else up to Full HD). Each level holds only a file of exactly that
    // quality (by the file's own name / playlist / size), so a 720p file sits under HD 720p, never under Full HD.
    (if (show4k && list.any { it.effectiveRes() >= 2160 }) AllQualityLevels else QualityLevels).map { q ->
        q to pickQuick(list, q.id)?.takeIf { q.id == "auto" || Ranker.tier(it.effectiveRes()) == Ranker.tier(q.res) }
    }

data class LangVersion(val lang: String, val stream: StreamItem, val current: Boolean, val preferred: Boolean)

/**
 * Language versions of what's playing, for the Audio panel: one entry per language (closest quality to the current file),
 * your preferred languages first (e.g. Hindi, Original audio), the rest after.
 */
fun AppViewModel.languageVersions(pool: List<StreamItem>, currentUrl: String?): List<LangVersion> {
    val current = pool.firstOrNull { it.stream.url == currentUrl }
    val addon = current?.addonName
    val res = current?.resolution() ?: 0
    val prefs = qsLangs()
    val same = pool.filter { addon == null || it.addonName == addon }
    return same.flatMap { s -> s.languagesOrOriginal().map { it to s } }
        .groupBy { it.first }
        .map { (lang, list) ->
            val best = list.map { it.second }.sortedWith(compareBy({ if (it.stream.url == currentUrl) 0 else 1 }, { kotlin.math.abs(it.resolution() - res) }, { -it.resolution() })).first()
            LangVersion(lang, best, best.stream.url == currentUrl, lang in prefs)
        }
        .sortedWith(compareBy({ prefs.indexOf(it.lang).let { i -> if (i < 0) 99 else i } }, { it.lang }))
}

// ───────────── Prefetch every addon's streams when a title page opens (all modes) ─────────────

/** Found streams are remembered for 20 minutes (provider links expire quickly), so reopening a title starts almost instantly. */
const val STREAM_CACHE_MS = 20L * 60_000

private val prefetchAll = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<StreamItem>>>()

/** All streams (filters applied) for a video id, fetched in the background and cached for 10 minutes. */
suspend fun AppViewModel.allStreams(type: String, id: String): List<StreamItem> {
    SlowAddons.watch(this, type, id)
    prefetchAll[id]?.let { (at, l) -> if (System.currentTimeMillis() - at < STREAM_CACHE_MS && l.isNotEmpty()) return l }
    val all = streamsFlow(type, id).first { it.second }.first
    val l = filtered(all).list
    if (l.isNotEmpty()) prefetchAll[id] = System.currentTimeMillis() to l
    return l
}

/** Source mode changed: every remembered list is from the other mode. */
fun forgetAllStreams() { prefetchAll.clear(); prefetch.clear() }

/** Forget the remembered streams of a title (e.g. when all of them failed). */
fun forgetStreams(id: String) { prefetchAll.remove(id); prefetch.remove(id); AddonCalls.forgetTitle(id); PluginSources.forget(id) }

fun cachedAll(id: String): List<StreamItem>? = prefetchAll[id]?.takeIf { System.currentTimeMillis() - it.first < STREAM_CACHE_MS }?.second

// ───────────── Prefetch: look up Quick Start streams as soon as a title page opens ─────────────

private val prefetch = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<StreamItem>>>()

/** Quick Start streams for a video id, cached for 10 minutes. */
suspend fun AppViewModel.quickStreams(type: String, id: String): List<StreamItem> {
    SlowAddons.watch(this, type, id)
    prefetch[id]?.let { (at, l) -> if (System.currentTimeMillis() - at < STREAM_CACHE_MS && l.isNotEmpty()) return l }
    val names = qsAddons()
    val all = streamsFlow(type, id, only = names).first { it.second }.first
    val l = filtered(all).list
    if (l.isNotEmpty()) prefetch[id] = System.currentTimeMillis() to l
    return l
}

/**
 * Streams as they arrive, addon by addon (filters applied): (list so far, all addons answered?). Uses the remembered list
 * when there is one. The final list is remembered like [quickStreams] / [allStreams].
 */
fun AppViewModel.progressiveStreams(type: String, id: String, quick: Boolean): kotlinx.coroutines.flow.Flow<Pair<List<StreamItem>, Boolean>> = kotlinx.coroutines.flow.flow {
    SlowAddons.watch(this@progressiveStreams, type, id)
    val cached = if (quick) cachedQuick(id) else cachedAll(id)
    if (!cached.isNullOrEmpty()) { emit(cached to true); return@flow }
    val src = if (quick) streamsFlow(type, id, only = qsAddons()) else streamsFlow(type, id)
    src.collect { (all, done) ->
        val l = filtered(all).list
        if (done && l.isNotEmpty()) { if (quick) prefetch[id] = System.currentTimeMillis() to l else prefetchAll[id] = System.currentTimeMillis() to l }
        emit(l to done)
    }
}

/** Links that arrived later (SlowAddons): added to the remembered lists of this title. */
fun appendCached(id: String, items: List<StreamItem>, quickNames: Set<String>) {
    prefetchAll[id]?.let { (at, l) -> prefetchAll[id] = at to (l + items).distinctBy { it.stream.url } }
    val q = items.filter { it.addonName in quickNames }
    if (q.isNotEmpty()) prefetch[id]?.let { (at, l) -> prefetch[id] = at to (l + q).distinctBy { it.stream.url } }
}

fun cachedQuick(id: String): List<StreamItem>? = prefetch[id]?.takeIf { System.currentTimeMillis() - it.first < STREAM_CACHE_MS }?.second

fun putQuick(id: String, l: List<StreamItem>) { if (l.isNotEmpty()) prefetch[id] = System.currentTimeMillis() to l }

fun isOnWifi(ctx: Context): Boolean = runCatching {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    cm.getNetworkCapabilities(cm.activeNetwork)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } == true
}.getOrDefault(false)

// ───────────── TMDB artwork & MDBList ratings (optional keys in Integrations) ─────────────

data class Art(val backdrop: String?, val logo: String?)
data class Rating(val source: String, val value: String)

private val artCache = HashMap<String, Art>()
private val ratingCache = HashMap<String, List<Rating>>()

suspend fun AppViewModel.tmdbArt(imdb: String, type: String): Art? {
    val key = (texts.value["tmdbKey"] ?: "").trim()
    if (key.isEmpty() || !imdb.startsWith("tt")) return null
    artCache[imdb]?.let { return it }
    return runCatching {
        val find = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/find/$imdb?api_key=$key&external_source=imdb_id")).jsonObject
        val kind = if (type == "series") "tv" else "movie"
        val hit = (find[if (kind == "tv") "tv_results" else "movie_results"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return null
        val tid = hit["id"]!!.jsonPrimitive.content
        val imgs = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/$kind/$tid/images?api_key=$key&include_image_language=en,null")).jsonObject
        fun best(arr: String, filter: (JsonObject) -> Boolean = { true }) = (imgs[arr] as? JsonArray)?.map { it.jsonObject }?.filter(filter)
            ?.maxByOrNull { it["vote_average"]?.jsonPrimitive?.doubleOrNull ?: 0.0 }?.get("file_path")?.jsonPrimitive?.contentOrNull
        val back = best("backdrops") { it["iso_639_1"]?.jsonPrimitive?.contentOrNull == null }
            ?: best("backdrops")
        val logo = best("logos") { it["iso_639_1"]?.jsonPrimitive?.contentOrNull == "en" } ?: best("logos")
        Art(back?.let { "https://image.tmdb.org/t/p/w1280$it" }, logo?.let { "https://image.tmdb.org/t/p/w500$it" }).also { artCache[imdb] = it }
    }.getOrNull()
}

suspend fun AppViewModel.mdbRatings(imdb: String): List<Rating> {
    val key = (texts.value["mdbKey"] ?: "").trim()
    if (key.isEmpty() || !imdb.startsWith("tt")) return emptyList()
    ratingCache[imdb]?.let { return it }
    return runCatching {
        val o = Api.json.parseToJsonElement(Api.get("https://mdblist.com/api/?apikey=$key&i=$imdb")).jsonObject
        val names = mapOf("imdb" to "IMDb", "tomatoes" to "Rotten Tomatoes", "tomatoesaudience" to "Audience", "metacritic" to "Metacritic",
            "tmdb" to "TMDB", "letterboxd" to "Letterboxd", "trakt" to "Trakt")
        (o["ratings"] as? JsonArray)?.mapNotNull { e ->
            val r = e.jsonObject
            val src = r["source"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val label = names[src] ?: return@mapNotNull null
            val value = r["value"]?.jsonPrimitive
            val txt = when {
                value == null || value.contentOrNull == null -> return@mapNotNull null
                src == "tomatoes" || src == "tomatoesaudience" -> "${value.intOrNull ?: value.doubleOrNull?.toInt() ?: return@mapNotNull null}%"
                src == "metacritic" -> "${value.intOrNull ?: value.doubleOrNull?.toInt() ?: return@mapNotNull null}"
                else -> value.doubleOrNull?.let { if (it > 10) "${it.toInt()}" else "%.1f".format(it) } ?: return@mapNotNull null
            }
            Rating(label, txt)
        }.orEmpty().also { ratingCache[imdb] = it }
    }.getOrDefault(emptyList())
}

// ───────────── Addon speed (Help & Settings > Quick Start > Addon speed) ─────────────

/** Average stream response time per addon, from the last 10 lookups. */
object AddonSpeed {
    private var prefs: android.content.SharedPreferences? = null
    private val times = java.util.concurrent.ConcurrentHashMap<String, List<Long>>()
    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences("addon_speed", Context.MODE_PRIVATE)
        prefs?.all?.forEach { (k, v) -> (v as? String)?.split(",")?.mapNotNull { it.toLongOrNull() }?.let { times[k] = it } }
    }
    fun record(addon: String, ms: Long, ok: Boolean) {
        val l = (times[addon].orEmpty() + (if (ok) ms else maxOf(ms, 20_000L))).takeLast(10)
        times[addon] = l
        runCatching { prefs?.edit()?.putString(addon, l.joinToString(","))?.apply() }
    }
    /** addon name → average milliseconds, fastest first. */
    fun all(): List<Pair<String, Long>> = times.mapNotNull { (k, v) -> if (v.isEmpty()) null else k to v.average().toLong() }.sortedBy { it.second }
}
