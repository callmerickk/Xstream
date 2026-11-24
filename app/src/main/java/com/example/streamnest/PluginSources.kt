package com.example.streamnest

import android.content.Context
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * StreamX: streams from CloudStream and Nuvio plugins for "Play manually" (two extra sections on the sources page).
 * Each plugin is asked in parallel; results arrive as they come. Not used by Quick Play or auto-play.
 */
object PluginSources {
    /**
     * One provider of one plugin. Two repositories can each have a "MovieBox" / "Netflix" provider: keyed by name they
     * shared one answer, so the slow (or failing) copy held back the fast one.
     */
    /** Music / audio-only providers (SimpMusic…) are never asked for movies or episodes. */
    fun musicOnly(api: MainAPI): Boolean = runCatching { api.supportedTypes.isNotEmpty() && api.supportedTypes.all { it in MUSIC_TYPES } }.getOrDefault(false)
        || api.name.contains("music", true)
    private val MUSIC_TYPES = setOf(TvType.Music, TvType.AudioBook, TvType.Audio, TvType.Podcast)
    fun apiKey(api: MainAPI): String = "${api.javaClass.name}|${api.name}|${runCatching { api.mainUrl }.getOrDefault("")}"
    private fun words(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(" ").filter { it.isNotBlank() && it !in setOf("the", "a", "an", "of", "and") }.toSet()
    private fun norm(s: String) = deaccent(s).lowercase().replace("aa", "a").replace(Regex("[^a-z0-9]"), "")
    /** 0.9.71 (from Xstream): "Bāhubali" → "Bahubali" (sites spell names without the marks). */
    fun deaccent(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    /** The name without "and" / "&" / "the": "Pritam Pedro" = "Pritam and Pedro" = "Pritam & Pedro". */
    internal fun core(s: String) = deaccent(s).lowercase().replace("aa", "a").replace("&", " ").replace(Regex("[^a-z0-9 ]"), "").split(" ")
        .filter { it.isNotBlank() && it != "and" && it != "the" }.joinToString("")
    /** core() without a studio prefix: "Marvel's Agents of S.H.I.E.L.D." = "Agents of SHIELD". */
    private fun bare(s: String) = core(s).removePrefix("marvels").removePrefix("marvel").removePrefix("dcs").removePrefix("disneys").removePrefix("tomclancys")

    /**
     * The names to search with, best first: as written, without dots/apostrophes ("Agents of SHIELD"), without the part
     * after a colon, and "&" written as "and". Sites often find nothing for "S.H.I.E.L.D." but find "SHIELD".
     */
    fun queries(name: String): List<String> {
        val out = LinkedHashSet<String>()
        out += name
        out += name.replace(Regex("(?<=\\b\\w)\\.(?=\\w)"), "").replace(".", "").replace("'", "").replace("’", "").replace(Regex("\\s+"), " ").trim()
        out += name.replace(Regex("^(?i)(marvel's|marvel|dc's|disney's|tom clancy's)\\s+"), "").replace(".", "").trim()
        if (':' in name) out += name.substringBefore(':').trim()
        if ('&' in name) out += name.replace("&", "and")
        // Dotted letters written out ("S.H.I.E.L.D." → "S H I E L D") and as a normal word ("Shield").
        Regex("\\b(?:[A-Za-z]\\.){2,}").find(name)?.let { m ->
            val letters = m.value.replace(".", "")
            out += name.replace(m.value, letters.toCharArray().joinToString(" ")).trim()
            out += name.replace(m.value, letters.lowercase().replaceFirstChar { it.uppercase() }).replace(Regex("^(?i)(marvel's|marvel)\\s+"), "").trim()
        }
        // 0.9.71: Indian titles are spelt many ways: "Bāhubali" / "Baahubali" / "Bahubali".
        val noMarks = deaccent(name)
        if (noMarks != name) out += noMarks
        if (Regex("(?i)aa").containsMatchIn(noMarks)) out += noMarks.replace(Regex("(?i)aa"), "a").substringBefore(':').trim()
        // Spacing and singular / plural: "Shadowhunters" = "Shadowhunter"; "Shadow Hunters" = "Shadowhunters".
        val plain = name.substringBefore(':').trim()
        if (plain.endsWith("s", true) && plain.length > 4) out += plain.dropLast(1)
        if (' ' in plain && plain.split(' ').size == 2) out += plain.replace(" ", "")
        // The title's other names on TMDB (US / India titles, alternative titles), asked last.
        altOf[core(name)]?.let { out += it }
        return out.filter { it.length >= 2 }.take(9)
    }
    /** core(name) → TMDB's other names for the title (filled before the plugins are asked). */
    val altOf = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    /** Fetches TMDB's alternative names once per title (US, IN, GB and the original title). */
    suspend fun loadAltNames(vm: AppViewModel, imdb: String, name: String, series: Boolean) {
        if (altOf.containsKey(core(name))) return
        val key = vm.tmdbKey().ifEmpty { return }
        runCatching {
            val f = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/find/$imdb?api_key=$key&external_source=imdb_id")) as kotlinx.serialization.json.JsonObject
            val arr = (f[if (series) "tv_results" else "movie_results"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? kotlinx.serialization.json.JsonObject ?: return
            val id = arr["id"]?.toString()?.trim('"') ?: return
            val orig = (arr[if (series) "original_name" else "original_title"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val alt = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/${if (series) "tv" else "movie"}/$id/alternative_titles?api_key=$key")) as kotlinx.serialization.json.JsonObject
            val list = ((alt["results"] ?: alt["titles"]) as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val c = (o["iso_3166_1"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                if (c != null && c !in setOf("US", "IN", "GB")) return@mapNotNull null
                (o["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
            altOf[core(name)] = (listOfNotNull(orig) + list).map { it.trim() }.filter { it.isNotBlank() && core(it) != core(name) }.distinct().take(4)
        }
    }

    /** "This site doesn't have this title" (it searched and nothing matched), kept 3 hours: not asked again 20 times. */
    private val noTitle = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /**
     * Providers whose search always comes back empty (browse-only shelves like Disney / Marvel / Star Wars / Pixar):
     * after 6 empty searches in a row they're left out for 3 days. One hit resets it.
     */
    private val emptyRun = java.util.concurrent.ConcurrentHashMap<String, Int>()
    // Plugins are never switched off any more (they keep being asked; empty answers just mean "not on this site").
    @Suppress("UNUSED_PARAMETER") private fun dead(k: String): Boolean = false
    private fun noteEmpty(k: String, empty: Boolean) {
        if (!empty) { if (emptyRun.remove(k) != null) CsPlugins.prefsOrNull()?.edit()?.remove("dead_$k")?.apply(); return }
        val n = (emptyRun[k] ?: 0) + 1; emptyRun[k] = n
        if (n == 6) ActLog.add("CSPLAY", "${k.substringAfter('|').substringBefore('|')}: 6 empty searches in a row (still asked)")
    }
    private fun yearOf(r: SearchResponse): Int? = (r as? MovieSearchResponse)?.year ?: (r as? TvSeriesSearchResponse)?.year
        ?: Regex("\\b(19|20)\\d{2}\\b").find(r.name)?.value?.toIntOrNull()

    /** The search result that is this title (same matching as Source Test), or null. */
    fun bestMatch(found: List<SearchResponse>, q: String, wantYear: Int?, series: Boolean, season: Int?): SearchResponse? =
        ranked(found, q, wantYear, series, season).firstOrNull()

    /** Every result that could be this title, best first (same name + the right year first: The Office 2005 ≠ 2024). */
    fun ranked(found: List<SearchResponse>, q: String, wantYear: Int?, series: Boolean, season: Int?): List<SearchResponse> =
        found.map { r ->
            // TV 0.9.6: MovieBox names a show with its newest season at the end ("New Girl S7" holds seasons 1–7): the
            // name is compared without it, and the season it names counts as "up to".
            val tailSeason = if (series) Regex("(?i)\\s+(?:S|season\\s*)(\\d{1,2})\\s*$").find(r.name.trim()) else null
            val clean = CsTester.cleanTitle(if (tailSeason != null) r.name.trim().removeRange(tailSeason.range).trim() else r.name)
            val bq = bare(q); val bc = bare(clean)
            var sc = if (norm(clean) == norm(q.replace("&", " and ")) || core(clean) == core(q) || (bq.length >= 4 && bc == bq) ||
                (bq.length >= 6 && bq.trimEnd('s') == bc.trimEnd('s'))) 1.0
            else if (bq.length >= 6 && bc.startsWith(bq) && kotlin.math.abs(bc.length - bq.length) <= 12) 0.85 else {
                val a = words(clean); val b = words(q)
                if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size * 0.9
            }
            val y = yearOf(r)
            // A series' year is when it first aired: a different one is another show with the same name.
            // A series' page often shows its latest season's year (Game of Thrones "2019"): a later year with the exact
            // name is only a small minus; an earlier one is another show.
            // TV 0.9.4: a series' search result often shows the year of its latest season (MovieBox: New Girl "2018"):
            // an exact-name show from a later year stays in (its page decides), only an earlier one is out.
            if (wantYear != null && y != null) sc += if (yearFits(q, wantYear, y, series)) 0.2
                else if (BuildConfig.IS_TV && series && y > wantYear && sc >= 1.0) -0.15 else -1.0
            // "S1-S12" / "Season 1-5": every season in the range is there.
            val range = if (series) Regex("(?i)(?:season|\\bS)\\s*(\\d{1,2})\\s*(?:-|–|to)\\s*(?:S|season)?\\s*(\\d{1,2})\\b").find(r.name) else null
            if (range != null) {
                val a = range.groupValues[1].toIntOrNull() ?: 0; val b = range.groupValues[2].toIntOrNull() ?: 0
                sc += if ((season ?: 1) in a..b) 0.25 else -0.6
            } else if (tailSeason != null) {
                val n = tailSeason.groupValues[1].toIntOrNull() ?: 0
                sc += if (n == (season ?: 1)) 0.25 else if (n > (season ?: 1)) 0.1 else -0.6
            } else if (series) Regex("(?i)season\\s*(\\d+)|\\bS(\\d{1,2})\\b").find(r.name)?.let { m ->
                val n = m.groupValues[1].ifEmpty { m.groupValues[2] }.toIntOrNull()
                if (n != null) sc += if (n == (season ?: 1)) 0.25 else -0.6
            }
            val isSeries = r.type in setOf(TvType.TvSeries, TvType.Anime, TvType.AsianDrama, TvType.Cartoon, TvType.Documentary) || Regex("(?i)season|web series|episode").containsMatchIn(r.name)
            // A series: a result marked as a film is out (unless its name says it's a season).
            // (An exact name is kept: some sites mark every page as a film; a real film is still caught when its page loads.)
            if (series && r.type == TvType.Movie && !Regex("(?i)season|web series|episode|\\bS\\d{1,2}\\b").containsMatchIn(r.name)) sc -= if (sc >= 1.0) 0.0 else 1.0
            if (r.type != null && !(r.type == TvType.Movie && Regex("(?i)season|web series").containsMatchIn(r.name))) sc += if (isSeries == series) 0.05 else -0.2
            r to sc
        }.filter { it.second >= 0.7 }.sortedByDescending { it.second }.map { it.first }

    private fun ExtractorLink.toItem(provider: String, subs: List<Subtitle>): StreamItem {
        val q = Qualities.getStringByInt(quality)
        val item = StreamItem("CloudStream · $provider", StreamInfo(url = url, name = listOf(provider, q).filter { it.isNotBlank() }.joinToString("\n"),
            title = name, subtitles = subs.takeIf { it.isNotEmpty() },
            behaviorHints = BehaviorHints(filename = name, proxyHeaders = ProxyHeaders(getAllHeaders().takeIf { it.isNotEmpty() }))))
        StreamHeaders.remember(item)
        // The plugin already knows the link's kind; telling the player saves a probe and avoids wrong guesses
        // (many plugin playlists have no .m3u8 in the link).
        when (type) {
            ExtractorLinkType.M3U8 -> StreamType.remember(url, StreamType.HLS)
            ExtractorLinkType.DASH -> StreamType.remember(url, StreamType.DASH)
            ExtractorLinkType.VIDEO -> if (StreamType.quick(url) == null) StreamType.remember(url, "")
            else -> {}
        }
        return item
    }

    /**
     * Only 4K, 1080p and 720p (links that say a lower quality are dropped; unlabelled ones stay), and never hubcdn
     * (HDHub4U / 4KHDHub carry those; they answer 403).
     */
    fun keep(s: StreamItem): Boolean {
        val u = s.stream.url ?: return false
        val host = runCatching { java.net.URI(u).host }.getOrNull().orEmpty().lowercase()
        if ("hubcdn" in host || "hubcdn" in u.lowercase().substringBefore('?')) return false
        // Links named after hubcdn (web pages, not videos) go too.
        if (listOfNotNull(s.stream.name, s.stream.title, s.stream.behaviorHints?.filename).any { it.contains("hubcdn", true) }) return false
        val r = s.resolution()
        return r == 0 || r >= 720
    }

    /** One provider's links for this title; every step is logged (Activity log › CSPLAY) so a silent plugin can be traced. */
    /** TV: plugins whose searches keep failing this session (key → titles failed in a row). */
    private val tvFails = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val tvFailAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /**
     * TV 0.8.7: sources that are never left out for failing: Netflix / Prime Video / Hotstar (one service, each has its
     * own catalogue — "0 results" only means "not in ours"), 4K HDHUB (big library: when it fails it's looked into, not
     * hidden) and M Player (small free catalogue, fast).
     */
    fun neverSkip(n: String): Boolean = n.lowercase().let { "netflix" in it || "prime" in it || "hotstar" in it || "4k" in it || "hdhub" in it || "m player" in it || "mplayer" in it }
    /** Disney / Marvel / Star Wars / Pixar shelves: browse-only, never return links — not asked on TV. */
    fun browseShelf(n: String): Boolean = n.trim().lowercase().let { it == "disney" || it == "marvel" || it == "star wars" || it == "pixar" }
    /** Languages of India (TMDB codes): RogMovies is for these titles, VegaMovies for the rest. */
    private val indianLangs = setOf("hi", "ta", "te", "ml", "kn", "bn", "mr", "pa", "gu", "or", "as", "ur", "bh", "sa")
    private val indianOf = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** Is this an Indian title (TMDB original language / country)? null = couldn't tell (both are asked). */
    suspend fun isIndian(vm: AppViewModel, imdb: String): Boolean? {
        indianOf[imdb]?.let { return it }
        val key = vm.tmdbKey().ifEmpty { return null }
        return kotlinx.coroutines.withTimeoutOrNull(2_500) {
            runCatching {
                val f = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/find/$imdb?api_key=$key&external_source=imdb_id")) as kotlinx.serialization.json.JsonObject
                val o = listOf("movie_results", "tv_results").firstNotNullOfOrNull { k -> (f[k] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? kotlinx.serialization.json.JsonObject }
                    ?: return@runCatching null
                val lang = (o["original_language"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val countries = (o["origin_country"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
                (lang in indianLangs || "IN" in countries)
            }.getOrNull()
        }?.also { indianOf[imdb] = it }
    }
    /** At most three file sites fetch links at the same time on a 1 GB stick (each opens many connections); Netflix / Prime / Hotstar never wait. */
    private val tvGate = kotlinx.coroutines.sync.Semaphore(3)

    suspend fun linksFrom(api: MainAPI, name: String, year: Int?, series: Boolean, season: Int?, episode: Int?,
                          retried: Boolean = false, onLink: ((StreamItem) -> Unit)? = null): List<StreamItem> {
        fun log(s: String) = ActLog.add("CSPLAY", "${api.name}: $s")
        val tSearch = System.currentTimeMillis()
        var searchErr = false
        val nsKey = NoStream.key(name, season, episode)
        if (apiKey(api) in noSearch || dead(apiKey(api))) return emptyList()          // a browse-only provider (Disney / Marvel shelves…)
        // TV 0.8.4: a plugin whose every search fails for two titles in a row (NetflixM / HotstarM: "Failed to verify
        // cookie") is left out until the app restarts — a 1 GB Fire TV stick can't afford the wasted work.
        if (BuildConfig.IS_TV && !neverSkip(api.name) && (tvFails[apiKey(api)] ?: 0) >= 2) {
            // TV 0.8.7: left out for 10 minutes, not until a restart.
            if (System.currentTimeMillis() - (tvFailAt[apiKey(api)] ?: 0L) < 10 * 60_000L) return emptyList()
            tvFails.remove(apiKey(api))
        }
        val noKey = "${apiKey(api)}|${core(name)}|$year|$series"
        noTitle[noKey]?.let { if (System.currentTimeMillis() - it < 30 * 60_000L) return emptyList() else noTitle.remove(noKey) }
        // The show's page (search + load) is kept 15 minutes: any other episode then needs only its links (~0.5 s),
        // so episode 4 or 10 starts as fast as episode 1.
        val pageKey = "${apiKey(api)}|$name|$series|$year|${if (series) season else ""}"
        pages[pageKey]?.takeIf { System.currentTimeMillis() - it.first < 15 * 60_000L }?.second?.let { cached ->
            return linksFromPage(api, cached, name, series, season, episode, onLink, ::log)
        }
        // TV 0.9.4: the page this site gave for this title before (kept on the TV) is opened straight away — no search.
        if (BuildConfig.IS_TV) FoundPages.get(api, name, year, series)?.let { url ->
            val l = try { withDeadline(15_000) { api.load(url) } } catch (e: Throwable) { null }
            if (l != null) {
                log("remembered page — no search")
                pages[pageKey] = System.currentTimeMillis() to (name to l)
                return linksFromPage(api, name to l, name, series, season, episode, onLink, ::log)
            }
            FoundPages.forget(api, name, year, series)
        }
        var lastTimeout = false
        suspend fun searchOnce(q: String, ms: Long, page: Int = 1): List<SearchResponse>? = try {
            withDeadline(ms) {
                val paged = try { api.search(q, page) } catch (_: NotImplementedError) { null }
                paged?.items ?: (if (page > 1) null else try { api.search(q) } catch (e: NotImplementedError) { if (paged == null) throw e else null })
            }
        } catch (e: NotImplementedError) { throw e
        } catch (e: Throwable) {
            searchErr = true
            lastTimeout = e is java.net.SocketTimeoutException || e.cause is java.net.SocketTimeoutException || e.javaClass.simpleName.contains("Timeout")
            log("search \"$q\" failed: ${e.javaClass.simpleName} ${e.message ?: ""}".take(200)); null
        }
        // Each name in turn until one finds this title (the first gets 20 s, the others 12 s).
        var found: List<SearchResponse>? = null
        var cands: List<SearchResponse> = emptyList()
        try {
            for ((i, q) in queries(name).withIndex()) {
                // TV 0.9.3: a site that was only slow (Netflix / Prime / Hotstar timing out at 10 s) is asked the same
                // name once more before trying other spellings.
                lastTimeout = false
                val f = searchOnce(q, if (i == 0) 20_000 else 12_000)
                    ?: (if (BuildConfig.IS_TV && i == 0 && lastTimeout) { log("slow — asking \"$q\" again"); searchOnce(q, 20_000) } else null)
                    ?: continue
                found = (found.orEmpty() + f).distinctBy { it.url }
                cands = ranked(found, name, year, series, season)
                if (cands.isNotEmpty()) { if (i > 0) log("found with \"$q\""); break }
            }
        } catch (e: NotImplementedError) {
            // "Has no search" only when the plugin's code really has no search (browse-only Disney / Marvel shelves). An
            // error while searching (a site answering with a web page — VegaMovies, 0.8.2) is just a failed search: the
            // next title asks again, like CloudStream does.
            if (!hasSearchCode(api)) { noSearch += apiKey(api); log("has no search (browse-only) — skipped"); return emptyList() }
            log("search broke (${e.javaClass.simpleName}) — will ask again next time"); searchErr = true
        }
        if (found == null && searchErr && !retried && SiteAddress.handles(api)) {
            // VegaMovies / RogMovies: their site moves. The known addresses are tested and the working one is used.
            if (SiteAddress.fix(api)) return linksFrom(api, name, year, series, season, episode, retried = true, onLink = onLink)
        }
        if (BuildConfig.IS_TV) {
            if (found == null && searchErr) {
                val n = (tvFails[apiKey(api)] ?: 0) + 1; tvFails[apiKey(api)] = n; tvFailAt[apiKey(api)] = System.currentTimeMillis()
                if (n == 2 && !neverSkip(api.name)) log("keeps failing — left out for 10 minutes")
            } else if (found != null) tvFails.remove(apiKey(api))
        }
        if (BuildConfig.IS_TV && series && found != null && cands.isEmpty()) {
            // TV 0.9.3: sites that name a show "New Girl (Season 1-7)" / "New Girl S01" — one more search with the season.
            searchOnce("$name Season ${season ?: 1}", 12_000)?.let { f -> found = (found.orEmpty() + f).distinctBy { it.url }; cands = ranked(found!!, name, year, series, season) }
            if (cands.isNotEmpty()) log("found with \"$name Season ${season ?: 1}\"")
            // TV 0.9.4: and the second page of results.
            if (cands.isEmpty()) searchOnce(name, 12_000, 2)?.let { f -> found = (found.orEmpty() + f).distinctBy { it.url }; cands = ranked(found!!, name, year, series, season); if (cands.isNotEmpty()) log("found on page 2") }
        }
        // TV 0.9.4: for a show, the site's series results are opened first.
        if (BuildConfig.IS_TV && series && cands.size > 1) cands = cands.sortedBy { if (it is MovieSearchResponse || it.type == TvType.Movie) 1 else 0 }
        if (found == null) { log("search gave nothing (timeout or no answer)"); NoStream.note(nsKey, api.name, "no answer from the site"); return emptyList() }
        noteEmpty(apiKey(api), found.isEmpty())
        if (cands.isEmpty()) { noTitle[noKey] = System.currentTimeMillis(); NoStream.note(nsKey, api.name, "${found.size} results, none matched"); log("${found.size} results, none matched \"$name\"" + found.take(if (BuildConfig.IS_TV) 8 else 4).joinToString("; ", " — top: ") { r -> r.name.take(50) + if (BuildConfig.IS_TV) " (${yearOf(r) ?: "?"})" else "" }); return emptyList() }
        // The page tells the real year: a same-name show from another year is skipped for the next result.
        var hit = cands.first()
        var lr: LoadResponse? = null
        var yearSkips = 0
        val tried = HashSet<String>()
        for (c in cands.take(5)) {
            tried += c.url
            // VegaMovies / RogMovies pages on a dead address are opened on the working one (then the original, as before).
            val moved = SiteAddress.rehost(api, c.url)
            val l = (if (moved != null) try { withDeadline(20_000) { api.load(moved) } } catch (e: Throwable) { log("load on ${api.mainUrl} failed: ${e.javaClass.simpleName} ${e.message ?: ""}".take(200)); null } else null)
                ?: try { withDeadline(20_000) { api.load(c.url) } } catch (e: Throwable) { log("load failed: ${e.javaClass.simpleName} ${e.message ?: ""}".take(200)); null } ?: continue
            val ly = runCatching { l.year }.getOrNull()
            // Cast: when both the page and the title list actors, at least one must be the same.
            val want = castOf["${core(name)}|$year"].orEmpty()
            val have = runCatching { l.actors?.mapNotNull { it.actor.name } }.getOrNull().orEmpty()
            fun n(x: String) = x.lowercase().replace(Regex("[^a-z ]"), "").trim()
            val shared = if (want.size >= 3 && have.size >= 3) want.map(::n).toSet().let { w ->
                have.map(::n).count { h -> h in w || w.any { it.split(' ').last() == h.split(' ').last() && it.split(' ').first().take(1) == h.split(' ').first().take(1) } } } else -1
            // Strict: the same year (±1) for films; a series may show the year of its latest season — a later year is
            // fine when its cast is the title's (or no cast is listed and the page really is a series).
            val laterSeries = BuildConfig.IS_TV && series && ly != null && year != null && ly > year && (shared > 0 || (shared < 0 && l !is MovieLoadResponse))
            if (ly != null && year != null && !yearFits(name, year, ly, series) && !laterSeries) { yearSkips++; log("\"${c.name.take(60)}\" (${l.javaClass.simpleName.removeSuffix("LoadResponse")}) is from $ly, not $year — skipped"); continue }
            if (shared == 0) { log("\"${c.name.take(60)}\": none of its cast is in the title's cast — skipped"); continue }
            hit = c; lr = l; break
        }
        // TV 0.9.3: only a same-name title from another year was found (MovieBox: "New Girl" 1985 film instead of the 2011
        // series) — search again with the year, and for a series with "Season N", and try what's new.
        if (lr == null && yearSkips > 0 && year != null && BuildConfig.IS_TV) {
            val more = ArrayList<SearchResponse>()
            // TV 0.9.4: also the next pages of the first search (MovieBox lists many same-name titles).
            for (pg in 2..3) searchOnce(name, 12_000, pg)?.let { more += it }
            for (q in listOfNotNull("$name $year", if (series) "$name Season ${season ?: 1}" else null)) searchOnce(q, 12_000)?.let { more += it }
            val again = ranked(more.distinctBy { it.url }.filter { it.url !in tried }, name, year, series, season)
            if (again.isNotEmpty()) log("another year only — searching with the year found ${again.size} more")
            for (c in again.take(3)) {
                val l = try { withDeadline(20_000) { api.load(c.url) } } catch (e: Throwable) { null } ?: continue
                val ly = runCatching { l.year }.getOrNull()
                val laterSeries = series && ly != null && ly > year && l !is MovieLoadResponse
                if (ly != null && !yearFits(name, year, ly, series) && !laterSeries) { log("\"${c.name.take(60)}\" is from $ly, not $year — skipped"); continue }
                hit = c; lr = l; break
            }
        }
        if (lr == null && BuildConfig.IS_TV) log("results were: " + found.orEmpty().take(8).joinToString("; ") { r -> "${r.name.take(40)} (${yearOf(r) ?: "?"}, ${r.type?.name ?: "?"})" })
        if (lr == null) { NoStream.note(nsKey, api.name, "matched but the page didn't load"); log(if (yearSkips > 0) "only \"${hit.name.take(60)}\" from another year — not this title" else "matched \"${hit.name.take(60)}\" but its page didn't load"); return emptyList() }
        pages[pageKey] = System.currentTimeMillis() to (hit.name to lr)
        if (BuildConfig.IS_TV) FoundPages.put(api, name, year, series, hit.url)
        return linksFromPage(api, hit.name to lr, name, series, season, episode, onLink, ::log)
    }

    private val pages = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Pair<String, LoadResponse>>>()

    private suspend fun linksFromPage(api: MainAPI, page: Pair<String, LoadResponse>, name: String, series: Boolean, season: Int?, episode: Int?,
                                      onLink: ((StreamItem) -> Unit)?, log: (String) -> Unit): List<StreamItem> {
        val (hitName, lr) = page
        val nsKey = NoStream.key(name, season, episode)
        val hit = object { val name = hitName }
        val data = when (lr) {
            // A series asked for and the site gave a film (The Flash / Supergirl have both): never play the film.
            is MovieLoadResponse -> if (series) { log("\"${hit.name.take(60)}\" is a film, not the series — skipped"); return emptyList() } else lr.dataUrl
            is TvSeriesLoadResponse -> if (!series) lr.episodes.firstOrNull()?.data else
                (lr.episodes.firstOrNull { (it.season ?: 1) == (season ?: 1) && it.episode == (episode ?: 1) } ?: lr.episodes.firstOrNull { it.episode == episode && it.season == null })?.data
            is AnimeLoadResponse -> lr.episodes.values.flatten().firstOrNull { it.episode == (episode ?: 1) }?.data
            else -> null
        }
        if (data == null) { log("\"${hit.name.take(60)}\" (${lr.javaClass.simpleName}) has no S${season}E${episode}"); return emptyList() }
        val links = java.util.concurrent.CopyOnWriteArrayList<ExtractorLink>()
        val subs = java.util.concurrent.CopyOnWriteArrayList<Subtitle>()
        val out = java.util.concurrent.CopyOnWriteArrayList<StreamItem>()
        try {
            withDeadline(45_000) {
                api.loadLinks(data, false, { s -> if (subs.none { it.url == s.url }) subs += Subtitle(id = s.url, url = s.url, lang = s.lang) },
                    cb@{ l ->
                        if (l is com.lagradost.cloudstream3.utils.DrmExtractorLink) return@cb   // protected: can't play
                        if (isInstantDownloadName(l.name, l.source) || isDownloadHost(l.url)) return@cb
                        val item = l.toItem(api.name, subs.toList())
                        if (!keep(item)) return@cb
                        links += l; out += item; onLink?.invoke(item)
                    })
            }
        } catch (e: Throwable) { log("links failed: ${e.javaClass.simpleName} ${e.message ?: ""}".take(200)) }
        // Subtitles often arrive after the first links: every link gets all of them.
        val res = if (subs.isEmpty()) out.toList() else links.map { it.toItem(api.name, subs.toList()) }
        log("${res.size} links, ${subs.size} subtitles for \"${hit.name.take(60)}\"")
        NoStream.note(nsKey, api.name, if (res.isEmpty()) "matched \"${hit.name.take(40)}\", 0 links" else "${res.size} links")
        return res
    }

    /** CloudStream: every switched-on plugin provider, results growing as each one answers. */
    fun cloudstream(ctx: Context, meta: Meta, videoId: String): Flow<List<StreamItem>> = channelFlow {
        CsPlugins.init(ctx)
        if (CsPlugins.catalog.value.isEmpty()) runCatching { CsPlugins.refresh() }
        val series = meta.type == "series"
        val season = videoId.split(":").getOrNull(1)?.toIntOrNull()
        val episode = videoId.split(":").getOrNull(2)?.toIntOrNull()
        val year = meta.releaseInfo?.take(4)?.toIntOrNull()
        val acc = ArrayList<StreamItem>(); val lock = Mutex()
        coroutineScope {
            CsPlugins.enabledEntries().forEach { e ->
                launch {
                    val reg = runCatching { CsPlugins.ensureLoaded(e) }.onFailure { x -> ActLog.add("CSPLAY", "${e.name}: plugin didn't load — ${x.javaClass.simpleName} ${x.message ?: ""}".take(200)) }.getOrNull() ?: return@launch
                    reg.apis.filterNot { musicOnly(it) }.forEach { api ->
                        launch {
                            val l = runCatching { linksFrom(api, meta.name, year, series, season, episode) }.getOrDefault(emptyList())
                            if (l.isNotEmpty()) lock.withLock { acc += l; send(acc.toList()) }
                        }
                    }
                }
            }
        }
    }

    /**
     * Quick Play: only the providers picked in Settings › Quick Start › CloudStream ("pluginKey#provider"), each link
     * sent the moment it is found.
     */
    fun selected(ctx: Context, meta: Meta, videoId: String, picked: Set<String>): Flow<StreamItem> = channelFlow {
        CsPlugins.init(ctx)
        if (CsPlugins.catalog.value.isEmpty()) runCatching { CsPlugins.refresh() }
        val series = meta.type == "series"
        val season = videoId.split(":").getOrNull(1)?.toIntOrNull()
        val episode = videoId.split(":").getOrNull(2)?.toIntOrNull()
        val year = meta.releaseInfo?.take(4)?.toIntOrNull()
        val plugins = picked.map { it.substringBefore('#') }.toSet()
        coroutineScope {
            CsPlugins.catalog.value.filter { it.key in plugins }.forEach { e ->
                launch {
                    val reg = runCatching { CsPlugins.ensureLoaded(e) }.getOrNull() ?: return@launch
                    reg.apis.filter { csProviderKey(e.key, it.name) in picked && !musicOnly(it) }.forEach { api ->
                        launch { runCatching { linksFrom(api, meta.name, year, series, season, episode) { trySend(it) } } }
                    }
                }
            }
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    private val noSearch = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Whether the plugin's own class (or a parent other than MainAPI) defines a search. */
    private fun hasSearchCode(api: MainAPI): Boolean = runCatching {
        var c: Class<*>? = api.javaClass
        while (c != null && c != MainAPI::class.java) {
            if (c.declaredMethods.any { it.name == "search" || it.name.startsWith("search\$") }) return@runCatching true
            c = c.superclass
        }
        false
    }.getOrDefault(true)
    private val reloadedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * One answer per provider + title for 10 minutes, shared by everything (title page, Quick Play, Upgrade to 4K, Play
     * manually, the player's sources): asking a plugin twice for the same title is never done, and a second caller waits
     * for the request already running instead of starting another.
     */
    private val answers = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, kotlinx.coroutines.Deferred<List<StreamItem>>>>()
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private suspend fun shared(api: MainAPI, id: String, fetch: suspend () -> List<StreamItem>): List<StreamItem> = sharedK("${apiKey(api)}|$id", fetch)
    /** 0.9.78: the same shared answer for any key ("<source>|<title id>"): Nuvio plugins too, so an answer that comes
     *  after the player stopped listening is kept and the next look-up gets it at once. */
    private suspend fun sharedK(k: String, fetch: suspend () -> List<StreamItem>): List<StreamItem> {
        val now = System.currentTimeMillis()
        val cur = answers[k]
        val d = if (cur != null && now - cur.first < 10 * 60_000L && !(cur.second.isCompleted && runCatching { cur.second.getCompleted() }.getOrNull().isNullOrEmpty() && now - cur.first > 60_000L)) cur.second
            else bg.async { runCatching { fetch() }.getOrDefault(emptyList()) }.also { answers[k] = now to it }
        if (answers.size > 400) answers.entries.filter { now - it.value.first > 10 * 60_000L }.forEach { answers.remove(it.key) }
        // 0.9.77: a search stopped by [focus] (you moved to another title) answers "nothing" to anyone still waiting.
        return try { d.await() } catch (e: kotlinx.coroutines.CancellationException) {
            if (kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive != false) emptyList() else throw e }
    }
    /** 0.9.77: the title you're on now (and the one playing). Searches still running for other titles are stopped. */
    @Volatile var playingKey: String? = null
    fun focus(id: String): Int {
        if (BuildConfig.IS_TV) return 0
        val keep = setOfNotNull(id.substringBefore(':'), playingKey?.substringBefore(':'))
        var n = 0
        answers.entries.toList().forEach { e ->
            val tid = e.key.substringAfter('|').substringBefore(':')
            if (tid !in keep && !e.value.second.isCompleted) { e.value.second.cancel(); answers.remove(e.key); n++ }
        }
        if (n > 0) ActLog.add("PLUGIN", "stopped $n search(es) for titles you left")
        return n
    }
    /** 0.9.77: searches still running (memory lines). */
    fun running(): Int = answers.values.count { !it.second.isCompleted }
    /** 0.9.77: memory short — keep only the answers for the title on screen / playing. */
    fun trimExcept(id: String?) {
        val keep = setOfNotNull(id?.substringBefore(':'), playingKey?.substringBefore(':'))
        answers.keys.filter { it.substringAfter('|').substringBefore(':') !in keep }.forEach { k -> answers.remove(k)?.second?.cancel() }
    }
    /** TV 0.9.14: every plugin answer already in for [id] (the play search's results; nothing is asked again). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun answered(id: String): List<StreamItem> = answers.entries.filter { it.key.endsWith("|$id") && it.value.second.isCompleted }
        .flatMap { e -> runCatching { e.value.second.getCompleted() }.getOrDefault(emptyList()) }
    fun forget(id: String) { answers.keys.filter { it.endsWith("|$id") }.forEach { answers.remove(it) } }
    fun forgetAll() { answers.clear(); noTitle.clear() }
    private val metas = java.util.concurrent.ConcurrentHashMap<String, Meta>()
    /** "name|year" → a series' run (first year, last year): Game of Thrones 2011–2019, New Girl 2011–2018. */
    val runOf = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()
    /**
     * Same title by year: films ±1; series anywhere in their run (±1) — sites often show the year of the latest season
     * (Hotstar: Game of Thrones "2019"). A same-name show from outside the run (The Office 2024 vs 2005–2013) is not it.
     */
    fun yearFits(name: String, want: Int, got: Int, series: Boolean): Boolean {
        if (kotlin.math.abs(got - want) <= 1) return true
        if (!series) return false
        val run = runOf["${core(name)}|$want"] ?: return false
        return got in (run.first - 1)..(run.second + 1)
    }
    /** "name|year" → the title's cast (Cinemeta), for the cast check on plugin pages. */
    val castOf = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    /** The title's name and year (Cinemeta) — plugins search by name. */
    suspend fun metaFor(type: String, id: String): Meta? {
        val imdb = id.substringBefore(':')
        metas[imdb]?.let { return it }
        return runCatching { Api.fetch<MetaResponse>("$CINEMETA/meta/$type/$imdb.json").meta }.getOrNull()?.also { metas[imdb] = it }
    }
    fun noteMeta(m: Meta) { metas[m.id.substringBefore(':')] = m }

    /**
     * Plugin mode's stream source (replaces the addons everywhere): every switched-on CloudStream provider and Nuvio
     * plugin, as (links so far, all answered?). [only] / [except] are link names ("CloudStream · Netflix").
     */
    fun modeStreams(vm: AppViewModel, type: String, id: String, only: Set<String>?, except: Set<String>?): Flow<Pair<List<StreamItem>, Boolean>> = channelFlow {
        val ctx = vm.appContext
        CsPlugins.init(ctx)
        SourceHealth.init(ctx); FoundPages.init(ctx)
        if (CsPlugins.catalog.value.isEmpty()) runCatching { CsPlugins.refresh() }
        val meta = metaFor(type, id)
        if (meta == null) { send(emptyList<StreamItem>() to true); return@channelFlow }
        val series = type == "series"
        val season = id.split(":").getOrNull(1)?.toIntOrNull()
        val episode = id.split(":").getOrNull(2)?.toIntOrNull()
        val year = meta.releaseInfo?.take(4)?.toIntOrNull()
        meta.cast?.takeIf { it.isNotEmpty() }?.let { castOf["${core(meta.name)}|$year"] = it }
        // A series' run from Cinemeta ("2011–2019", or "2016–" while it still airs).
        if (series && year != null) {
            val ys = Regex("(19|20)\\d{2}").findAll(meta.releaseInfo.orEmpty()).map { it.value.toInt() }.toList()
            val end = if (ys.size >= 2) ys.last() else if (meta.releaseInfo.orEmpty().trim().endsWith("–") || meta.releaseInfo.orEmpty().trim().endsWith("-")) java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) else year
            runOf["${core(meta.name)}|$year"] = year to maxOf(year, end)
        }
        // TMDB's other names for the title (a plugin's site may list it under one of them), at most 2 s.
        kotlinx.coroutines.withTimeoutOrNull(2_000) { runCatching { loadAltNames(vm, id.substringBefore(':'), meta.name, series) } }
        // TV 0.8.7: VegaMovies for foreign titles, RogMovies for Indian ones (each is only asked for what it carries).
        val indian = if (BuildConfig.IS_TV) runCatching { isIndian(vm, id.substringBefore(':')) }.getOrNull() else null
        fun tvAsk(n: String): Boolean {
            if (!BuildConfig.IS_TV) return true
            if (browseShelf(n)) return false
            val l = n.lowercase()
            if (indian == true && "vegamovies" in l) return false
            if (indian == false && "rogmovies" in l) return false
            return true
        }
        fun wanted(name: String) = (only == null || name in only || (MB_ANY in only && name.contains("moviebox", true)) || (VEGA_ANY in only && isVegaName(name)) || (OTT_ANY in only && isOttName(name))) && (except == null || name !in except)
        val acc = ArrayList<StreamItem>(); val lock = Mutex()
        val jobs = ArrayList<kotlinx.coroutines.Job>()
        coroutineScope {
            // Each plugin is asked the moment it has loaded (it used to wait until every plugin had loaded: a slow
            // site held up the first link by 20 s or more).
            val entries = CsPlugins.enabledEntries()
            // 0.9.3: a rule that names Nuvio plugins ("Nuvio · …") asks those too.
            val nuvio = if (only != null && only.none { it.startsWith("Nuvio · ") }) emptyList() else runCatching { NuvioPlugins.init(ctx); NuvioPlugins.enabledScrapers() }.getOrDefault(emptyList())
                .filter { s -> wanted("Nuvio · ${s.name}") && (s.types.isEmpty() || (if (series) ("tv" in s.types || "series" in s.types) else "movie" in s.types)) }
            // MovieBox asked directly first (one search, every language at once); the MovieBox plugins wait for it and only
            // run when it found nothing (they're the backup).
            val direct: kotlinx.coroutines.Deferred<List<StreamItem>>? = if (wanted("MovieBox") && MovieBoxDirect.preferred() && entries.any { it.internalName.contains("moviebox", true) })
                async { runCatching { MovieBoxDirect.links(meta.name, year, series, season, episode) }.getOrDefault(emptyList()) } else null
            var pending = entries.size + (if (nuvio.isEmpty()) 0 else 1) + (if (direct != null) 1 else 0)
            if (pending == 0) { send(emptyList<StreamItem>() to true); return@coroutineScope }
            suspend fun add(l: List<StreamItem>, delta: Int = -1) = lock.withLock {
                acc += l.filter { keep(it) && !it.isInstantDownload() && !TitleMatch.wrong(it, type, id) && !vm.extHides(it) }
                pending += delta
                send(acc.distinctBy { it.stream.url } to (pending <= 0))
            }
            val t0 = System.currentTimeMillis()
            if (direct != null) jobs += launch {
                val l = direct.await()
                ActLog.add("PLUGIN", "MovieBox (direct): ${l.size} links in ${System.currentTimeMillis() - t0} ms · $id")
                add(l)
            }
            entries.forEach { e -> jobs += launch {
                val reg = runCatching { CsPlugins.ensureLoaded(e) }.onFailure { x -> ActLog.add("PLUGIN", "${e.name}: not loaded — ${x.message ?: x.javaClass.simpleName}".take(200)) }.getOrNull()
                val apis = reg?.apis?.filter { wanted(csDisplayName(it.name)) && !musicOnly(it) && tvAsk(it.name) }.orEmpty()
                if (apis.isEmpty()) { add(emptyList()); return@launch }
                lock.withLock { pending += apis.size - 1 }
                apis.forEach { api -> launch {
                    val isMb = api.name.contains("moviebox", true)
                    val got = if (isMb && direct != null) direct.await() else null
                    if (got != null && got.isNotEmpty()) { MovieBoxDirect.noteOk(); add(emptyList()); return@launch }
                    val l = shared(api, id) {
                        if (BuildConfig.IS_TV && TvWeak.on && !isOttName(api.name)) tvGate.withPermit { linksFrom(api, meta.name, year, series, season, episode) }
                        else linksFrom(api, meta.name, year, series, season, episode)
                    }
                    if (isMb && got != null && l.isNotEmpty()) MovieBoxDirect.noteBroken("nothing found for \"${meta.name}\" while ${api.name} found ${l.size}")
                    // Timing of each plugin (Activity log → PLUGIN), to see which one is slow.
                    ActLog.add("PLUGIN", "${api.name}: ${l.size} links in ${System.currentTimeMillis() - t0} ms · $id")
                    SourceHealth.answered(api.name, l.size, System.currentTimeMillis() - t0)
                    add(l)
                } }
            } }
            if (nuvio.isNotEmpty()) jobs += launch {
                // Each Nuvio link is passed on the moment it comes (they used to wait for the slowest Nuvio plugin,
                // and arrived after the player had already given up).
                val seen = HashSet<String>()
                runCatching { nuvio(ctx, vm, meta, id).collect { l -> val n = l.filter { it.stream.url?.let { u -> seen.add(u) } == true }; if (n.isNotEmpty()) add(n, delta = 0) } }
                add(emptyList())
            }
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    /** Nuvio: every switched-on plugin, asked with the TMDB id. */
    fun nuvio(ctx: Context, vm: AppViewModel, meta: Meta, videoId: String): Flow<List<StreamItem>> = channelFlow {
        NuvioPlugins.init(ctx)
        val list = NuvioPlugins.enabledScrapers()
        if (list.isEmpty()) return@channelFlow
        val series = meta.type == "series"
        val imdb = videoId.substringBefore(":")
        val tmdb = NuvioTester.tmdbIdFor(vm, imdb, series) ?: return@channelFlow
        val season = videoId.split(":").getOrNull(1)?.toIntOrNull()
        val episode = videoId.split(":").getOrNull(2)?.toIntOrNull()
        val acc = ArrayList<StreamItem>(); val lock = Mutex()
        coroutineScope {
            list.filter { s -> s.types.isEmpty() || (if (series) ("tv" in s.types || "series" in s.types) else "movie" in s.types) }.forEach { s ->
                launch {
                    val items = if (BuildConfig.IS_TV) nuvioOne(ctx, s, meta, tmdb, series, season, episode)
                        else sharedK("nuvio:${s.name}|$videoId") { nuvioOne(ctx, s, meta, tmdb, series, season, episode) }
                    if (items.isNotEmpty()) lock.withLock { acc += items; send(acc.toList()) }
                }
            }
        }
    }

    /** One Nuvio plugin's links (asked on the shared background scope: a late answer is kept, not thrown away). */
    private suspend fun nuvioOne(ctx: Context, s: NuvioScraper, meta: Meta, tmdb: String, series: Boolean, season: Int?, episode: Int?): List<StreamItem> {
                    val res = runCatching {
                        val code = NuvioPlugins.code(s)
                        Api.json.parseToJsonElement(NuvioRuntime.getStreams(ctx, code, listOf(tmdb, if (series) "tv" else "movie", season, episode), 45_000)) as? JsonArray
                    }.getOrNull() ?: return emptyList()
                    val items = res.mapNotNull { it as? JsonObject }.mapNotNull { o ->
                        fun str(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
                        val url = str("url") ?: return@mapNotNull null
                        val name = str("name") ?: s.name; val title = str("title") ?: ""
                        if (isInstantDownloadName(name, title)) return@mapNotNull null
                        val h = (o["headers"] as? JsonObject)?.mapValues { runCatching { it.value.jsonPrimitive.contentOrNull }.getOrNull().orEmpty() }.orEmpty()
                        StreamItem("Nuvio · ${s.name}", StreamInfo(url = url, name = listOfNotNull(name, str("quality")).joinToString("\n"), title = title,
                            behaviorHints = BehaviorHints(proxyHeaders = ProxyHeaders(h.takeIf { it.isNotEmpty() })))).also { StreamHeaders.remember(it) }
                    }
                    ActLog.add("NUVIOPLAY", "${s.name}: ${items.size} streams for ${meta.name}")
                    return items
    }
}

/**
 * TV 0.9.4: the page each site gave for a title (site + name + year → page address), kept on the TV for 30 days, so the
 * next episode, the next day or another profile opens it without searching. A page that no longer opens is forgotten.
 */
object FoundPages {
    private var prefs: android.content.SharedPreferences? = null
    fun init(ctx: android.content.Context) { if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("found_pages", android.content.Context.MODE_PRIVATE) }
    private fun key(api: MainAPI, name: String, year: Int?, series: Boolean) = "${api.name}|${PluginSources.core(name)}|$year|$series"
    fun get(api: MainAPI, name: String, year: Int?, series: Boolean): String? {
        val v = prefs?.getString(key(api, name, year, series), null) ?: return null
        val at = v.substringBefore('|').toLongOrNull() ?: return null
        if (System.currentTimeMillis() - at > 30L * 24 * 3600_000) return null
        return v.substringAfter('|').takeIf { it.isNotBlank() }
    }
    fun put(api: MainAPI, name: String, year: Int?, series: Boolean, url: String) {
        prefs?.edit()?.putString(key(api, name, year, series), "${System.currentTimeMillis()}|$url")?.apply()
    }
    fun forget(api: MainAPI, name: String, year: Int?, series: Boolean) { prefs?.edit()?.remove(key(api, name, year, series))?.apply() }
}
