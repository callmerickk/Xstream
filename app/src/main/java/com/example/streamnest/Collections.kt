package com.example.streamnest

import android.net.Uri
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.lang.ref.WeakReference
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/* Nuvio-style Collections: imported from a Nuvio JSON export (Help & Settings > Content & Discovery > Collections).
 * A collection holds folders (tiles); each folder holds sources (TMDB lists / discover, Trakt lists, addon catalogs)
 * that are resolved into rows when the folder is opened. */

data class NFolder(val id: String, val title: String, val cover: String?, val logo: String?, val hero: String?,
                   val tileShape: String = "LANDSCAPE", val hideTitle: Boolean = false)
data class NCollection(val id: String, val title: String, val folders: List<NFolder>, val viewMode: String = "TABBED_GRID",
                       val showAllTab: Boolean = true, val backdrop: String? = null)

/** One row source inside a folder, as exported by Nuvio. Everything optional. */
private data class NSource(
    val provider: String, val title: String?, val mediaType: String?, val sortBy: String?, val sortHow: String?,
    val tmdbId: String?, val tmdbSourceType: String?, val filters: JsonObject?, val traktListId: String?,
    val addonId: String?, val catalogId: String?, val type: String?, val genre: String?,
)

private const val COL_IMG = "https://image.tmdb.org/t/p/"
private const val ROW_MAX = 20
private const val ROW_TTL = 30 * 60_000L

/** Process-wide store (there is one AppViewModel per app process). */
private object ColStore {
    val lock = Any()
    @Volatile var loaded = false
    /** All imported collections in file order (before the user's order/hidden settings). */
    val raw = MutableStateFlow<List<NCollection>>(emptyList())
    /** "collectionId/folderId" -> sources */
    @Volatile var sources: Map<String, List<NSource>> = emptyMap()
    val rows = ConcurrentHashMap<String, Pair<Long, List<Shelf>>>()
    val imdb = ConcurrentHashMap<String, String>()
    var owner: WeakReference<AppViewModel>? = null
    /** Whose collections are loaded. */
    @Volatile var pid: String = "p1"
    var ordered: StateFlow<List<NCollection>>? = null
    var visible: StateFlow<List<NCollection>>? = null
}

/** Each profile has its own imported collections. */
internal fun AppViewModel.colFileFor(pid: String) = File(appContext.filesDir, "collections_$pid.json")
private fun AppViewModel.colFile() = colFileFor(ColStore.pid)

/** Loads [pid]'s collections (called when the profile whose data is shown changes, or its collections arrive from the cloud). */
internal fun AppViewModel.switchCollections(pid: String) {
    synchronized(ColStore.lock) {
        ColStore.pid = pid
        ColStore.sources = emptyMap()
        ColStore.rows.clear()
        ColStore.raw.value = emptyList()
        ColStore.loaded = false
    }
    viewModelScope.launch(Dispatchers.IO) { loadOnce() }
}

// ───────────── JSON helpers (lenient) ─────────────

private fun JsonElement?.cs(): String? = (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
private fun JsonObject.cs(k: String): String? = this[k].cs()
private fun JsonObject.cb(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.co(k: String): JsonObject? = this[k] as? JsonObject
private fun JsonObject.ca(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** Numbers stay as written ("8658757", not "8658757.0"). */
private fun JsonElement?.idStr(): String? {
    val p = this as? JsonPrimitive ?: return null
    val s = p.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
    return if (!p.isString && s.endsWith(".0")) s.removeSuffix(".0") else s
}

private fun cleanUrl(u: String?): String? = u?.trim()?.takeIf { it.startsWith("http") }

private fun parseCollections(text: String): Pair<List<NCollection>, Map<String, List<NSource>>> {
    val root = Api.json.parseToJsonElement(text.trim())
    val arr: List<JsonObject> = when (root) {
        is JsonArray -> root.mapNotNull { it as? JsonObject }
        is JsonObject -> when {
            root["collections"] is JsonArray -> root.ca("collections")
            root["folders"] is JsonArray -> listOf(root)
            else -> emptyList()
        }
        else -> emptyList()
    }
    val srcMap = HashMap<String, List<NSource>>()
    val seenCol = HashSet<String>()
    val out = arr.mapIndexedNotNull { ci, o ->
        var cid = o.cs("id") ?: "collection-$ci"
        if (!seenCol.add(cid)) cid = "$cid-$ci"
        val seenFolder = HashSet<String>()
        val folders = o.ca("folders").mapIndexedNotNull { fi, f ->
            var fid = f.cs("id") ?: "folder-$fi"
            if (!seenFolder.add(fid)) fid = "$fid-$fi"
            val srcs = ArrayList<NSource>()
            f.ca("sources").forEach { s -> parseSource(s)?.let { srcs.add(it) } }
            // catalogSources usually repeat the addon sources; add only the ones that are new.
            f.ca("catalogSources").forEach { s ->
                val cat = s.co("catalog")
                val addonId = s.cs("addonId") ?: return@forEach
                val catId = s.cs("catalogId") ?: cat?.cs("id") ?: return@forEach
                if (srcs.none { it.provider == "addon" && it.addonId == addonId && it.catalogId == catId }) {
                    srcs += NSource("addon", s.cs("title") ?: cat?.cs("name") ?: s.cs("catalogName"), null, null, null, null, null, null, null,
                        addonId, catId, s.cs("type") ?: cat?.cs("type"), s.cs("genre"))
                }
            }
            srcMap["$cid/$fid"] = srcs
            NFolder(
                id = fid,
                title = f.cs("title") ?: "Folder ${fi + 1}",
                cover = cleanUrl(f.cs("coverImageUrl")) ?: cleanUrl(f.cs("heroBackdropUrl")),
                logo = cleanUrl(f.cs("titleLogoUrl")),
                hero = cleanUrl(f.cs("heroBackdropUrl")) ?: cleanUrl(f.cs("coverImageUrl")),
                tileShape = f.cs("tileShape") ?: "LANDSCAPE",
                hideTitle = f.cb("hideTitle") ?: false,
            )
        }
        NCollection(
            id = cid,
            title = o.cs("title") ?: "Collection ${ci + 1}",
            folders = folders,
            viewMode = o.cs("viewMode") ?: "TABBED_GRID",
            showAllTab = o.cb("showAllTab") ?: true,
            backdrop = cleanUrl(o.cs("backdropImageUrl")),
        )
    }
    return out to srcMap
}

private fun parseSource(s: JsonObject): NSource? {
    val cat = s.co("catalog")
    val provider = s.cs("provider")?.lowercase() ?: when {
        s.cs("addonId") != null -> "addon"
        s.cs("traktListId") != null -> "trakt"
        s.cs("tmdbId") != null || s.cs("tmdbSourceType") != null -> "tmdb"
        else -> return null
    }
    return NSource(
        provider = provider,
        title = s.cs("title") ?: s.co("display")?.cs("name") ?: s.cs("catalogName") ?: cat?.cs("name"),
        mediaType = s.cs("mediaType")?.uppercase(),
        sortBy = s.cs("sortBy"), sortHow = s.cs("sortHow"),
        tmdbId = s["tmdbId"].idStr(), tmdbSourceType = s.cs("tmdbSourceType")?.uppercase(),
        filters = s.co("filters"), traktListId = s["traktListId"].idStr(),
        addonId = s.cs("addonId"), catalogId = s.cs("catalogId") ?: cat?.cs("id"),
        type = s.cs("type") ?: cat?.cs("type"), genre = s.cs("genre"),
    )
}

// ───────────── Storage ─────────────

private fun AppViewModel.loadOnce() {
    if (ColStore.loaded) return
    synchronized(ColStore.lock) {
        if (ColStore.loaded) return
        val f = colFile()
        if (f.exists()) runCatching { parseCollections(f.readText()) }.getOrNull()?.let { (list, src) ->
            ColStore.sources = src
            ColStore.raw.value = list
        }
        ColStore.loaded = true
    }
}

private fun ids(s: String?) = s.orEmpty().split("|").filter { it.isNotBlank() }

private fun AppViewModel.ensureFlows() {
    synchronized(ColStore.lock) {
        if (ColStore.owner?.get() === this && ColStore.ordered != null) return
        val ordered = combine(ColStore.raw, texts) { list, tx ->
            val order = ids(tx["colOrder"])
            list.withIndex().sortedBy { (i, c) -> order.indexOf(c.id).let { if (it < 0) 10_000 + i else it } }.map { it.value }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ColStore.raw.value)
        val visible = combine(ordered, texts) { list, tx ->
            val hidden = ids(tx["colHidden"]).toSet()
            list.filter { it.id !in hidden && it.folders.isNotEmpty() }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
        ColStore.ordered = ordered
        ColStore.visible = visible
        ColStore.owner = WeakReference(this)
    }
    if (!ColStore.loaded) viewModelScope.launch(Dispatchers.IO) { loadOnce() }
}

/** Visible collections in the user's order (hidden ones left out). Loads the saved JSON on first call. */
fun AppViewModel.collections(): StateFlow<List<NCollection>> {
    ensureFlows()
    return ColStore.visible!!
}

/** Every imported collection in the user's order, hidden ones included (for the settings page). */
fun AppViewModel.allCollections(): StateFlow<List<NCollection>> {
    ensureFlows()
    return ColStore.ordered!!
}

/** Replaces the imported collections with [json] (a Nuvio export). Returns how many collections were imported. */
fun AppViewModel.importCollections(json: String): Result<Int> = runCatching {
    val (list, src) = try { parseCollections(json) } catch (e: Exception) { throw IllegalArgumentException("That isn't valid collections JSON.") }
    require(list.isNotEmpty()) { "No collections found in that JSON." }
    colFile().writeText(json.trim())
    synchronized(ColStore.lock) {
        ColStore.sources = src
        ColStore.rows.clear()
        ColStore.raw.value = list
        ColStore.loaded = true
    }
    Cloud.markProfile(ColStore.pid, "cols")
    list.size
}

fun AppViewModel.clearCollections() {
    synchronized(ColStore.lock) {
        runCatching { colFile().delete() }
        ColStore.sources = emptyMap()
        ColStore.rows.clear()
        ColStore.raw.value = emptyList()
        ColStore.loaded = true
    }
    Cloud.markProfile(ColStore.pid, "cols")
    setText("colOrder", "")
    setText("colHidden", "")
}

suspend fun AppViewModel.importCollectionsFromUrl(url: String): Result<Int> {
    var u = url.trim()
    if (!u.startsWith("http")) return Result.failure(IllegalArgumentException("Enter a link starting with https://"))
    // github.com/.../blob/... -> the raw file
    if (u.contains("://github.com/") && u.contains("/blob/")) u = u.replace("://github.com/", "://raw.githubusercontent.com/").replace("/blob/", "/")
    val body = runCatching { Api.get(u) }.getOrElse { return Result.failure(IllegalStateException("Couldn't download that link (${it.message ?: "error"}).")) }
    return withContext(Dispatchers.IO) { importCollections(body) }
}

// ───────────── Rows ─────────────

/** Rows (title + titles) of one folder, in source order. Cached for 30 minutes. */
suspend fun AppViewModel.folderRows(c: NCollection, f: NFolder): List<Shelf> {
    val k = "${c.id}/${f.id}"
    ColStore.rows[k]?.let { (at, rows) -> if (System.currentTimeMillis() - at < ROW_TTL) return rows }
    withContext(Dispatchers.IO) { loadOnce() }
    val srcs = ColStore.sources[k].orEmpty()
    if (srcs.isEmpty()) return emptyList()
    val sem = Semaphore(4)
    val rows = coroutineScope {
        srcs.map { s -> async { sem.withPermit { runCatching { resolveSource(s) }.getOrNull() } } }.awaitAll()
    }.filterNotNull().filter { it.items.isNotEmpty() }
    if (rows.isNotEmpty()) ColStore.rows[k] = System.currentTimeMillis() to rows
    return rows
}

private suspend fun AppViewModel.resolveSource(s: NSource): Shelf? {
    val items = when (s.provider) {
        "tmdb" -> if (s.tmdbSourceType == "DISCOVER" || (s.tmdbSourceType == null && s.tmdbId == null)) tmdbDiscover(s) else tmdbList(s)
        "trakt" -> traktList(s)
        "addon" -> addonCatalog(s)
        else -> null
    } ?: return null
    val unique = items.distinctBy { it.type + it.id }.take(ROW_MAX)
    return Shelf(s.title ?: "Titles", unique)
}

private fun tmdbMeta(o: JsonObject, kindHint: String): Meta? {
    val kind = o.cs("media_type") ?: kindHint
    if (kind != "movie" && kind != "tv") return null
    val id = o["id"].idStr() ?: return null
    val name = o.cs("title") ?: o.cs("name") ?: return null
    val poster = o.cs("poster_path") ?: return null
    val vote = (o["vote_average"] as? JsonPrimitive)?.doubleOrNull
    return Meta(
        id = "tmdb:$kind:$id",
        type = if (kind == "tv") "series" else "movie",
        name = name,
        poster = COL_IMG + "w342" + poster,
        background = o.cs("backdrop_path")?.let { COL_IMG + "w780" + it },
        description = o.cs("overview"),
        releaseInfo = (o.cs("release_date") ?: o.cs("first_air_date"))?.take(4),
        imdbRating = vote?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it) },
    )
}

private fun kindOf(mediaType: String?) = if (mediaType == "TV" || mediaType == "SERIES" || mediaType == "SHOW") "tv" else "movie"

private suspend fun AppViewModel.tmdbList(s: NSource): List<Meta>? {
    val key = tmdbKey().ifEmpty { return null }
    val id = s.tmdbId ?: return null
    val o = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/list/$id?api_key=$key&language=en-US")) as? JsonObject ?: return null
    val want = s.mediaType?.let { kindOf(it) }
    var items = o.ca("items").filter { want == null || (it.cs("media_type") ?: want) == want }.mapNotNull { tmdbMeta(it, want ?: "movie") }
    when (s.sortBy) {
        "vote_average.desc" -> items = items.sortedByDescending { it.imdbRating?.toDoubleOrNull() ?: 0.0 }
        "vote_average.asc" -> items = items.sortedBy { it.imdbRating?.toDoubleOrNull() ?: 0.0 }
        "release_date.desc", "primary_release_date.desc", "first_air_date.desc" -> items = items.sortedByDescending { it.releaseInfo.orEmpty() }
        "release_date.asc", "primary_release_date.asc", "first_air_date.asc" -> items = items.sortedBy { it.releaseInfo.orEmpty() }
    }
    return items
}

/** A filter value as a TMDB query value: strings as-is, numbers without ".0", arrays joined with "|" (OR). */
private fun filterValue(e: JsonElement?): String? = when (e) {
    null -> null
    is JsonArray -> e.mapNotNull { it.idStr() }.takeIf { it.isNotEmpty() }?.joinToString("|")
    is JsonPrimitive -> e.idStr()
    else -> null
}

private suspend fun AppViewModel.tmdbDiscover(s: NSource): List<Meta>? {
    val key = tmdbKey().ifEmpty { return null }
    val kind = kindOf(s.mediaType)
    val tv = kind == "tv"
    var sort = s.sortBy?.takeIf { it.contains(".") } ?: "popularity.desc"
    if (tv) sort = sort.replace("primary_release_date", "first_air_date").replace("release_date", "first_air_date")
    else sort = sort.replace("first_air_date", "primary_release_date")
    val p = LinkedHashMap<String, String>()
    p["sort_by"] = sort
    p["include_adult"] = "false"
    val f = s.filters
    if (f != null) {
        val dGte = if (tv) "first_air_date.gte" else "primary_release_date.gte"
        val dLte = if (tv) "first_air_date.lte" else "primary_release_date.lte"
        val yr = if (tv) "first_air_date_year" else "primary_release_year"
        val map = linkedMapOf(
            "withGenres" to "with_genres", "withoutGenres" to "without_genres",
            "withOriginalLanguage" to "with_original_language", "withOriginCountry" to "with_origin_country",
            "voteCountGte" to "vote_count.gte", "voteAverageGte" to "vote_average.gte", "voteAverageLte" to "vote_average.lte",
            "withKeywords" to "with_keywords", "withoutKeywords" to "without_keywords",
            "withCompanies" to "with_companies", "withoutCompanies" to "without_companies", "withNetworks" to "with_networks",
            "withWatchProviders" to "with_watch_providers", "withoutWatchProviders" to "without_watch_providers",
            "watchRegion" to "watch_region",
            "releaseDateGte" to dGte, "releaseDateLte" to dLte, "year" to yr,
        )
        map.forEach { (from, to) -> filterValue(f[from])?.let { p[to] = it } }
        if ((p.containsKey("with_watch_providers") || p.containsKey("without_watch_providers")) && !p.containsKey("watch_region")) p["watch_region"] = "US"
        if (!tv) p.remove("with_networks")
    }
    val q = p.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
    val o = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/discover/$kind?api_key=$key&language=en-US&page=1&$q")) as? JsonObject ?: return null
    return o.ca("results").mapNotNull { tmdbMeta(it, kind) }
}

private suspend fun AppViewModel.traktList(s: NSource): List<Meta>? {
    val client = (texts.value["traktClientId"] ?: "").trim().ifEmpty { return null }
    val id = s.traktListId ?: return null
    val path = when (s.mediaType) { null -> ""; "TV", "SERIES", "SHOW" -> "/shows"; else -> "/movies" }
    val body = Api.getWith("https://api.trakt.tv/lists/${Uri.encode(id)}/items$path?extended=full",
        mapOf("trakt-api-version" to "2", "trakt-api-key" to client, "Content-Type" to "application/json"))
    var entries = (Api.json.parseToJsonElement(body) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    if (s.sortBy == "rank") {
        entries = entries.sortedBy { (it["rank"] as? JsonPrimitive)?.doubleOrNull ?: Double.MAX_VALUE }
        if (s.sortHow == "desc") entries = entries.reversed()
    }
    return entries.mapNotNull { e ->
        val show = e.co("show")
        val item = e.co("movie") ?: show ?: return@mapNotNull null
        val imdb = item.co("ids")?.cs("imdb")?.takeIf { it.startsWith("tt") } ?: return@mapNotNull null
        val rating = (item["rating"] as? JsonPrimitive)?.doubleOrNull
        Meta(
            id = imdb,
            type = if (show != null) "series" else "movie",
            name = item.cs("title") ?: return@mapNotNull null,
            poster = "https://images.metahub.space/poster/medium/$imdb/img",
            background = "https://images.metahub.space/background/medium/$imdb/img",
            description = item.cs("overview"),
            releaseInfo = item["year"].idStr(),
            imdbRating = rating?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it) },
        )
    }
}

private fun colEnc(id: String) = Uri.encode(id, ":")

private suspend fun AppViewModel.addonCatalog(s: NSource): List<Meta>? {
    val addonId = s.addonId ?: return null
    val catId = s.catalogId ?: return null
    val a = addons.value.firstOrNull { it.manifest.id == addonId && isAddonOn(it) } ?: return null
    val type = s.type ?: a.manifest.catalogs.firstOrNull { it.id == catId }?.type ?: if (kindOf(s.mediaType) == "tv") "series" else "movie"
    val extra = s.genre?.let { "/genre=${Uri.encode(it)}" } ?: ""
    return Api.fetch<CatalogResponse>("${a.baseUrl}/catalog/$type/${colEnc(catId)}$extra.json").metas
}

// ───────────── Opening a title ─────────────

/** Collection items may have TMDB ids ("tmdb:movie:603"); this turns them into the IMDb id the rest of the app uses. */
suspend fun AppViewModel.resolveForOpen(m: Meta): Meta? {
    if (!m.id.startsWith("tmdb:")) return m
    ColStore.imdb[m.id]?.let { return m.copy(id = it) }
    val parts = m.id.split(":")
    val kind = parts.getOrNull(1) ?: return null
    val tid = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return null
    val key = tmdbKey().ifEmpty { return null }
    val imdb = runCatching {
        val o = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/${if (kind == "tv") "tv" else "movie"}/$tid/external_ids?api_key=$key")) as? JsonObject
        o?.cs("imdb_id")
    }.getOrNull()?.takeIf { it.startsWith("tt") } ?: return null
    ColStore.imdb[m.id] = imdb
    return m.copy(id = imdb, type = if (kind == "tv") "series" else "movie")
}
