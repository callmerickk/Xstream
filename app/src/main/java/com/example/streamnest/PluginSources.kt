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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Xstream: streams from CloudStream and Nuvio plugins for "Play manually" (two extra sections on the sources page).
 * Each plugin is asked in parallel; results arrive as they come. Not used by Quick Play or auto-play.
 */
object PluginSources {
    /**
     * One provider of one plugin. Two repositories can each have a "MovieBox" / "Netflix" provider: keyed by name they
     * shared one answer, so the slow (or failing) copy held back the fast one.
     */
    fun apiKey(api: MainAPI): String = "${api.javaClass.name}|${api.name}|${runCatching { api.mainUrl }.getOrDefault("")}"
    private fun words(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(" ").filter { it.isNotBlank() && it !in setOf("the", "a", "an", "of", "and") }.toSet()
    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
    private fun yearOf(r: SearchResponse): Int? = (r as? MovieSearchResponse)?.year ?: (r as? TvSeriesSearchResponse)?.year
        ?: Regex("\\b(19|20)\\d{2}\\b").find(r.name)?.value?.toIntOrNull()

    /** The search result that is this title (same matching as Source Test), or null. */
    fun bestMatch(found: List<SearchResponse>, q: String, wantYear: Int?, series: Boolean, season: Int?): SearchResponse? =
        found.map { r ->
            val clean = CsTester.cleanTitle(r.name)
            var sc = if (norm(clean) == norm(q.replace("&", " and "))) 1.0 else {
                val a = words(clean); val b = words(q)
                if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size * 0.9
            }
            val y = yearOf(r)
            if (wantYear != null && y != null) sc += when { kotlin.math.abs(y - wantYear) <= 1 -> 0.2; series -> 0.0; else -> -0.4 }
            if (series) Regex("(?i)season\\s*(\\d+)|\\bS(\\d{1,2})\\b").find(r.name)?.let { m ->
                val n = m.groupValues[1].ifEmpty { m.groupValues[2] }.toIntOrNull()
                if (n != null) sc += if (n == (season ?: 1)) 0.25 else -0.6
            }
            val isSeries = r.type in setOf(TvType.TvSeries, TvType.Anime, TvType.AsianDrama, TvType.Cartoon, TvType.Documentary) || Regex("(?i)season|web series|episode").containsMatchIn(r.name)
            // A series: a result marked as a film is out (unless its name says it's a season).
            // (An exact name is kept: some sites mark every page as a film; a real film is still caught when its page loads.)
            if (series && r.type == TvType.Movie && !Regex("(?i)season|web series|episode|\\bS\\d{1,2}\\b").containsMatchIn(r.name)) sc -= if (sc >= 1.0) 0.0 else 1.0
            if (r.type != null && !(r.type == TvType.Movie && Regex("(?i)season|web series").containsMatchIn(r.name))) sc += if (isSeries == series) 0.05 else -0.2
            r to sc
        }.maxByOrNull { it.second }?.takeIf { it.second >= 0.7 }?.first

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
    suspend fun linksFrom(api: MainAPI, name: String, year: Int?, series: Boolean, season: Int?, episode: Int?,
                          onLink: ((StreamItem) -> Unit)? = null): List<StreamItem> {
        fun log(s: String) = ActLog.add("CSPLAY", "${api.name}: $s")
        if (apiKey(api) in noSearch) return emptyList()          // a browse-only provider (Disney / Marvel shelves…)
        // The show's page (search + load) is kept 15 minutes: any other episode then needs only its links (~0.5 s),
        // so episode 4 or 10 starts as fast as episode 1.
        val pageKey = "${apiKey(api)}|$name|$series|${if (series) season else year}"
        pages[pageKey]?.takeIf { System.currentTimeMillis() - it.first < 15 * 60_000L }?.second?.let { cached ->
            return linksFromPage(api, cached, name, series, season, episode, onLink, ::log)
        }
        val found = try {
            withDeadline(20_000) {
                val paged = try { api.search(name, 1) } catch (_: NotImplementedError) { null }
                paged?.items ?: try { api.search(name) } catch (e: NotImplementedError) { if (paged == null) throw e else null }
            }
        } catch (e: NotImplementedError) { noSearch += apiKey(api); log("has no search — skipped from now on"); null
        } catch (e: Throwable) { log("search failed: ${e.javaClass.simpleName} ${e.message ?: ""}".take(200)); null }
        if (found == null) { log("search gave nothing (timeout or no answer)"); return emptyList() }
        val hit = bestMatch(found, name, year, series, season)
        if (hit == null) { log("${found.size} results, none matched \"$name\"" + found.take(4).joinToString("; ", " — top: ") { it.name.take(60) }); return emptyList() }
        val lr = try { withDeadline(20_000) { api.load(hit.url) } } catch (e: Throwable) { log("load failed: ${e.javaClass.simpleName} ${e.message ?: ""}".take(200)); null }
        if (lr == null) { log("matched \"${hit.name.take(60)}\" but its page didn't load"); return emptyList() }
        pages[pageKey] = System.currentTimeMillis() to (hit.name to lr)
        return linksFromPage(api, hit.name to lr, name, series, season, episode, onLink, ::log)
    }

    private val pages = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Pair<String, LoadResponse>>>()

    private suspend fun linksFromPage(api: MainAPI, page: Pair<String, LoadResponse>, name: String, series: Boolean, season: Int?, episode: Int?,
                                      onLink: ((StreamItem) -> Unit)?, log: (String) -> Unit): List<StreamItem> {
        val (hitName, lr) = page
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
                        if (isInstantDownloadName(l.name, l.source)) return@cb
                        val item = l.toItem(api.name, subs.toList())
                        if (!keep(item)) return@cb
                        links += l; out += item; onLink?.invoke(item)
                    })
            }
        } catch (e: Throwable) { log("links failed: ${e.javaClass.simpleName} ${e.message ?: ""}".take(200)) }
        // Subtitles often arrive after the first links: every link gets all of them.
        val res = if (subs.isEmpty()) out.toList() else links.map { it.toItem(api.name, subs.toList()) }
        log("${res.size} links, ${subs.size} subtitles for \"${hit.name.take(60)}\"")
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
                    reg.apis.forEach { api ->
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
                    reg.apis.filter { csProviderKey(e.key, it.name) in picked }.forEach { api ->
                        launch { runCatching { linksFrom(api, meta.name, year, series, season, episode) { trySend(it) } } }
                    }
                }
            }
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    private val noSearch = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * One answer per provider + title for 10 minutes, shared by everything (title page, Quick Play, Upgrade to 4K, Play
     * manually, the player's sources): asking a plugin twice for the same title is never done, and a second caller waits
     * for the request already running instead of starting another.
     */
    private val answers = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, kotlinx.coroutines.Deferred<List<StreamItem>>>>()
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private suspend fun shared(api: MainAPI, id: String, fetch: suspend () -> List<StreamItem>): List<StreamItem> {
        val k = "${apiKey(api)}|$id"
        val now = System.currentTimeMillis()
        val cur = answers[k]
        val d = if (cur != null && now - cur.first < 10 * 60_000L && !(cur.second.isCompleted && runCatching { cur.second.getCompleted() }.getOrNull().isNullOrEmpty() && now - cur.first > 60_000L)) cur.second
            else bg.async { runCatching { fetch() }.getOrDefault(emptyList()) }.also { answers[k] = now to it }
        if (answers.size > 400) answers.entries.filter { now - it.value.first > 10 * 60_000L }.forEach { answers.remove(it.key) }
        return d.await()
    }
    fun forget(id: String) { answers.keys.filter { it.endsWith("|$id") }.forEach { answers.remove(it) } }
    private val metas = java.util.concurrent.ConcurrentHashMap<String, Meta>()
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
        if (CsPlugins.catalog.value.isEmpty()) runCatching { CsPlugins.refresh() }
        val meta = metaFor(type, id)
        if (meta == null) { send(emptyList<StreamItem>() to true); return@channelFlow }
        val series = type == "series"
        val season = id.split(":").getOrNull(1)?.toIntOrNull()
        val episode = id.split(":").getOrNull(2)?.toIntOrNull()
        val year = meta.releaseInfo?.take(4)?.toIntOrNull()
        fun wanted(name: String) = (only == null || name in only || (MB_ANY in only && name.contains("moviebox", true)) || (VEGA_ANY in only && name.contains("vega", true)) || (OTT_ANY in only && isOttName(name))) && (except == null || name !in except)
        val acc = ArrayList<StreamItem>(); val lock = Mutex()
        val jobs = ArrayList<kotlinx.coroutines.Job>()
        coroutineScope {
            val regs = CsPlugins.enabledEntries().map { e -> async { runCatching { CsPlugins.ensureLoaded(e) }.getOrNull() } }.awaitAll().filterNotNull()
            val apis = regs.flatMap { it.apis }.filter { wanted(csDisplayName(it.name)) }
            val nuvio = if (only != null) emptyList() else runCatching { NuvioPlugins.init(ctx); NuvioPlugins.enabledScrapers() }.getOrDefault(emptyList())
                .filter { s -> wanted("Nuvio · ${s.name}") && (s.types.isEmpty() || (if (series) ("tv" in s.types || "series" in s.types) else "movie" in s.types)) }
            val total = apis.size + (if (nuvio.isEmpty()) 0 else 1)
            if (total == 0) { send(emptyList<StreamItem>() to true); return@coroutineScope }
            var done = 0
            suspend fun add(l: List<StreamItem>) = lock.withLock {
                acc += l.filter { keep(it) && !it.isInstantDownload() && !TitleMatch.wrong(it, type, id) && !vm.extHides(it) }
                done++
                send(acc.distinctBy { it.stream.url } to (done == total))
            }
            val t0 = System.currentTimeMillis()
            apis.forEach { api -> jobs += launch {
                val l = shared(api, id) { linksFrom(api, meta.name, year, series, season, episode) }
                // Timing of each plugin (Activity log → PLUGIN), to see which one is slow.
                ActLog.add("PLUGIN", "${api.name}: ${l.size} links in ${System.currentTimeMillis() - t0} ms · $id")
                add(l)
            } }
            if (nuvio.isNotEmpty()) jobs += launch {
                val all = ArrayList<StreamItem>()
                runCatching { nuvio(ctx, vm, meta, id).collect { all.clear(); all += it } }
                add(all.toList())
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
                    val res = runCatching {
                        val code = NuvioPlugins.code(s)
                        Api.json.parseToJsonElement(NuvioRuntime.getStreams(ctx, code, listOf(tmdb, if (series) "tv" else "movie", season, episode), 45_000)) as? JsonArray
                    }.getOrNull() ?: return@launch
                    val items = res.mapNotNull { it as? JsonObject }.mapNotNull { o ->
                        fun str(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
                        val url = str("url") ?: return@mapNotNull null
                        val name = str("name") ?: s.name; val title = str("title") ?: ""
                        if (isInstantDownloadName(name, title)) return@mapNotNull null
                        val h = (o["headers"] as? JsonObject)?.mapValues { runCatching { it.value.jsonPrimitive.contentOrNull }.getOrNull().orEmpty() }.orEmpty()
                        StreamItem("Nuvio · ${s.name}", StreamInfo(url = url, name = listOfNotNull(name, str("quality")).joinToString("\n"), title = title,
                            behaviorHints = BehaviorHints(proxyHeaders = ProxyHeaders(h.takeIf { it.isNotEmpty() })))).also { StreamHeaders.remember(it) }
                    }
                    if (items.isNotEmpty()) lock.withLock { acc += items; send(acc.toList()) }
                    ActLog.add("NUVIOPLAY", "${s.name}: ${items.size} streams for ${meta.name}")
                }
            }
        }
    }
}
