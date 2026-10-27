package com.example.streamnest

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Calendar
import java.util.Collections

/* ───────────── Premium search (TMDB + addon catalogs) ───────────── */

data class SearchHit(
    val meta: Meta,
    val backdrop: String?,
    val logo: String?,
    val year: String?,
    /** "2h 22m" or "5 Seasons" */
    val info: String?,
    val isNew: Boolean,
)

/** A film series or a studio found for the search (Harry Potter, Marvel…): its titles in release order. */
data class SearchGroup(val name: String, val sub: String, val posters: List<String>, val backdrop: String?, val items: List<Meta>)

object SearchState {
    val hits = MutableStateFlow<List<SearchHit>>(emptyList())
    /** Collections / studios matching the search, shown as stacked cards above the results. */
    val groups = MutableStateFlow<List<SearchGroup>>(emptyList())
    /** "Did you mean …?" when the search looks misspelled. */
    val didYouMean = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)
    val suggestions = MutableStateFlow<List<String>>(emptyList())
    /** The query whose results are currently shown ("" = none). */
    val query = MutableStateFlow("")
    internal var job: Job? = null
}

private const val SIMG = "https://image.tmdb.org/t/p/"
private val detailCache: MutableMap<String, JsonObject> = Collections.synchronizedMap(HashMap())

private fun JsonObject.sStr(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
private fun JsonObject.sArr(k: String) = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
private fun JsonObject.sObj(k: String) = this[k] as? JsonObject

/** "yyyy-MM-dd" → millis at local midnight, or null. (No java.time: minSdk 24.) */
private fun dateMillis(s: String?): Long? {
    if (s == null || s.length < 10) return null
    val y = s.substring(0, 4).toIntOrNull() ?: return null
    val m = s.substring(5, 7).toIntOrNull() ?: return null
    val d = s.substring(8, 10).toIntOrNull() ?: return null
    return Calendar.getInstance().apply {
        clear()
        set(y, m - 1, d, 0, 0, 0)
    }.timeInMillis
}

private fun isRecent(date: String?): Boolean {
    val at = dateMillis(date) ?: return false
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val days = (today - at) / (24L * 60 * 60 * 1000)
    return days in 0..30
}

private fun runtimeText(min: Int?): String? = min?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }

private suspend fun tmdbDetail(key: String, kind: String, id: String): JsonObject {
    val ck = "$kind:$id"
    detailCache[ck]?.let { return it }
    val body = Api.get("https://api.themoviedb.org/3/$kind/$id?api_key=$key&append_to_response=external_ids,images&include_image_language=en,null")
    val o = withContext(Dispatchers.Default) { Api.json.parseToJsonElement(body).jsonObject }
    detailCache[ck] = o
    return o
}

private fun hitFromDetail(kind: String, d: JsonObject): SearchHit? {
    val imdb = (d.sObj("external_ids")?.sStr("imdb_id") ?: d.sStr("imdb_id"))?.takeIf { it.startsWith("tt") } ?: return null
    val tv = kind == "tv"
    val logos = d.sObj("images")?.sArr("logos").orEmpty()
    fun vote(o: JsonObject) = o.sStr("vote_average")?.toDoubleOrNull() ?: 0.0
    val logoPath = (logos.filter { it.sStr("iso_639_1") == "en" }.maxByOrNull { vote(it) } ?: logos.maxByOrNull { vote(it) })?.sStr("file_path")
    val date = if (tv) d.sStr("first_air_date") else d.sStr("release_date")
    val year = date?.take(4)
    val info = if (tv) {
        d.sStr("number_of_seasons")?.toIntOrNull()?.takeIf { it > 0 }?.let { if (it == 1) "1 Season" else "$it Seasons" }
    } else runtimeText(d.sStr("runtime")?.toIntOrNull())
    val backdrop = d.sStr("backdrop_path")?.let { "${SIMG}w500$it" }
    val poster = d.sStr("poster_path")?.let { "${SIMG}w342$it" }
    val logo = logoPath?.let { "${SIMG}w300$it" }
    val meta = Meta(
        id = imdb,
        type = if (tv) "series" else "movie",
        name = d.sStr(if (tv) "name" else "title") ?: d.sStr("original_name") ?: d.sStr("original_title") ?: "",
        poster = poster,
        background = backdrop,
        logo = logo,
        description = d.sStr("overview"),
        releaseInfo = year,
        runtime = if (tv) null else info,
        genres = d.sArr("genres").mapNotNull { it.sStr("name") }.takeIf { it.isNotEmpty() },
    )
    return SearchHit(meta, backdrop, logo, year, info, isRecent(date))
}

/** TMDB search; the best matches (first 6) are handed over as soon as they are ready, then the rest. */
private suspend fun tmdbSearch(key: String, q: String, onFirst: (List<SearchHit>) -> Unit = {}): List<SearchHit> = coroutineScope {
    val body = Api.get("https://api.themoviedb.org/3/search/multi?api_key=$key&query=${Uri.encode(q)}&include_adult=false&page=1")
    val results = withContext(Dispatchers.Default) { Api.json.parseToJsonElement(body).jsonObject.sArr("results") }
        .filter { val mt = it.sStr("media_type"); mt == "movie" || mt == "tv" }
        .take(16)
    val jobs = results.map { r ->
        async {
            runCatching {
                val kind = r.sStr("media_type")!!
                val id = r.sStr("id")!!
                withTimeoutOrNull(6_000) { hitFromDetail(kind, tmdbDetail(key, kind, id)) }
            }.getOrNull()
        }
    }
    val first = jobs.take(6).awaitAll().filterNotNull()
    if (first.isNotEmpty()) onFirst(first)
    (first + jobs.drop(6).awaitAll().filterNotNull()).distinctBy { it.meta.id }
}

/** Exact title first, then titles starting with it, then ones containing every word, then the rest (order kept). */
private fun matchRank(q: String, name: String): Int {
    fun norm(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
    val qq = norm(q).replace(Regex("\\b(19|20)\\d{2}\\b"), "").trim()
    val n = norm(name)
    if (qq.isEmpty()) return 3
    return when {
        n == qq || n.removePrefix("the ") == qq.removePrefix("the ") -> 0
        n.startsWith(qq) -> 1
        qq.split(' ').all { w -> n.split(' ').any { it.startsWith(w) } } -> 2
        else -> 3
    }
}

private fun hitFromAddon(m: Meta): SearchHit {
    val tt = m.id.startsWith("tt")
    return SearchHit(
        meta = m,
        backdrop = m.background ?: if (tt) "https://images.metahub.space/background/medium/${m.id}/img" else m.poster,
        logo = m.logo ?: if (tt) "https://images.metahub.space/logo/medium/${m.id}/img" else null,
        year = m.releaseInfo?.take(4),
        info = if (m.type == "movie") m.runtime else null,
        isNew = false,
    )
}

private val SearchCache = object : LinkedHashMap<String, List<SearchHit>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SearchHit>>?) = size > 20
}

/**
 * Debounced (~250 ms) search; needs 3+ characters. Cancels the previous search. Results show as soon as the first
 * source (your addons or TMDB) answers; the other one is added below when it arrives. Recent queries are remembered.
 */
fun AppViewModel.searchPremium(q: String) {
    val query = q.trim()
    SearchState.job?.cancel()
    if (query.length < 3) {
        SearchState.hits.value = emptyList()
        SearchState.groups.value = emptyList()
        SearchState.didYouMean.value = null
        SearchState.suggestions.value = emptyList()
        SearchState.query.value = ""
        SearchState.loading.value = false
        return
    }
    val ck = query.lowercase()
    fun publish(hits: List<SearchHit>, final: Boolean) {
        SearchState.hits.value = hits
        SearchState.suggestions.value = hits.asSequence()
            .map { it.meta.name.trim().lowercase() }
            .filter { it.isNotEmpty() && it != ck }
            .distinct().take(4).toList()
        SearchState.query.value = query
        if (final) SearchState.loading.value = false
    }
    synchronized(SearchCache) { SearchCache[ck] }?.let { publish(it, true); synchronized(GroupCache) { GroupCache[ck] }?.let { g -> SearchState.groups.value = g.first; SearchState.didYouMean.value = g.second }; return }
    SearchState.loading.value = true
    SearchState.groups.value = emptyList(); SearchState.didYouMean.value = null
    SearchState.job = viewModelScope.launch {
        delay(250)
        val key = tmdbKey()
        val parts = mutableListOf<List<SearchHit>>() // in the order they arrived
        fun merged(): List<SearchHit> {
            val seen = HashSet<String>()
            val yearQ = Regex("\\b(19|20)\\d{2}\\b").find(query)?.value
            return parts.flatten().filter { seen.add(it.meta.id) }.distinctBy { it.meta.type + it.meta.id }
                .sortedWith(compareBy<SearchHit>({ matchRank(query, it.meta.name) }, { if (yearQ != null && it.year != yearQ) 1 else 0 })
                    // same kind of match (a whole film series): oldest first, so the parts come in order
                    .thenBy { h -> if (matchRank(query, h.meta.name) <= 2) (h.year?.toIntOrNull() ?: 9999) else 0 })
        }
        coroutineScope {
            launch {
                val r = runCatching { searchNow(query) }.getOrDefault(emptyList<Meta>())
                    .filter { it.type == "movie" || it.type == "series" }.map { hitFromAddon(it) }
                parts += r
                if (r.isNotEmpty()) publish(merged(), false)
            }
            if (key.isNotEmpty()) launch {
                val g = runCatching { searchGroups(key, query) }.getOrDefault(emptyList())
                if (SearchState.query.value == query || SearchState.hits.value.isEmpty()) SearchState.groups.value = g
            }
            if (key.isNotEmpty()) launch {
                // TMDB's richer cards first when they arrive before anything is on screen; the top 6 show at once.
                var slot = -1
                fun put(r: List<SearchHit>) {
                    if (slot >= 0) parts[slot] = r
                    else if (SearchState.hits.value.isEmpty() || SearchState.query.value != query) { parts.add(0, r); slot = 0 }
                    else { parts += r; slot = parts.lastIndex }
                    if (r.isNotEmpty()) publish(merged(), false)
                }
                val r = runCatching { tmdbSearch(key, query) { first -> put(first) } }.getOrDefault(emptyList<SearchHit>())
                put(r)
            }
        }
        val all = merged()
        publish(all, true)
        // Looks misspelled (nothing close to what was typed): suggest the nearest real title.
        val best = all.minOfOrNull { matchRank(query, it.meta.name) } ?: 9
        val dym = if (best >= 3 && key.isNotEmpty()) runCatching { didYouMean(key, query, all) }.getOrNull() else null
        SearchState.didYouMean.value = dym
        if (all.isNotEmpty()) synchronized(SearchCache) { SearchCache[ck] = all }
        synchronized(GroupCache) { GroupCache[ck] = SearchState.groups.value to dym }
    }
}

private val GroupCache = object : LinkedHashMap<String, Pair<List<SearchGroup>, String?>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<List<SearchGroup>, String?>>?) = size > 20
}

private fun tmdbMetaOf(o: JsonObject, kindHint: String): Meta? {
    val kind = o.sStr("media_type") ?: kindHint
    if (kind != "movie" && kind != "tv") return null
    val id = o.sStr("id") ?: return null
    val poster = o.sStr("poster_path") ?: return null
    return Meta(id = "tmdb:$kind:$id", type = if (kind == "tv") "series" else "movie", name = o.sStr("title") ?: o.sStr("name") ?: return null,
        poster = "${SIMG}w342$poster", background = o.sStr("backdrop_path")?.let { "${SIMG}w780$it" }, description = o.sStr("overview"),
        releaseInfo = (o.sStr("release_date") ?: o.sStr("first_air_date"))?.take(4))
}

/** Film series (TMDB collections) and studios / franchises matching the search, each with its titles oldest first. */
private suspend fun searchGroups(key: String, q: String): List<SearchGroup> = coroutineScope {
    val words = q.lowercase().split(Regex("\\s+")).filter { it.length > 1 && it !in setOf("the", "movies", "movie", "films", "series", "all") }
    if (words.isEmpty()) return@coroutineScope emptyList()
    val clean = words.joinToString(" ")
    val col = async {
        runCatching {
            val res = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/search/collection?api_key=$key&query=${Uri.encode(clean)}&page=1")).jsonObject.sArr("results")
            res.filter { r -> val n = r.sStr("name").orEmpty().lowercase(); words.all { n.contains(it) } }.take(2).mapNotNull { r ->
                val id = r.sStr("id") ?: return@mapNotNull null
                val d = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/collection/$id?api_key=$key")).jsonObject
                val parts = d.sArr("parts").sortedBy { it.sStr("release_date") ?: "9999" }.mapNotNull { tmdbMetaOf(it, "movie") }
                if (parts.size < 2) null
                else SearchGroup(d.sStr("name") ?: r.sStr("name") ?: q, "${parts.size} films · in order", parts.take(3).mapNotNull { it.poster }, d.sStr("backdrop_path")?.let { "${SIMG}w780$it" }, parts)
            }
        }.getOrDefault(emptyList())
    }
    val studio = async {
        runCatching {
            val res = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/search/company?api_key=$key&query=${Uri.encode(clean)}&page=1")).jsonObject.sArr("results")
            val c = res.firstOrNull { r -> r.sStr("name").orEmpty().lowercase().let { n -> n == clean || n.startsWith(clean) || (n.contains(clean) && n.contains("studio")) } } ?: return@runCatching emptyList<SearchGroup>()
            val cid = c.sStr("id") ?: return@runCatching emptyList<SearchGroup>()
            val parts = (1..2).flatMap { pg ->
                Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/discover/movie?api_key=$key&with_companies=$cid&sort_by=popularity.desc&vote_count.gte=200&page=$pg")).jsonObject.sArr("results")
            }.mapNotNull { tmdbMetaOf(it, "movie") }.distinctBy { it.id }.sortedBy { it.releaseInfo ?: "9999" }
            if (parts.size < 3) emptyList() else listOf(SearchGroup(c.sStr("name") ?: q, "${parts.size} films · oldest first", parts.takeLast(3).mapNotNull { it.poster }, parts.lastOrNull()?.background, parts))
        }.getOrDefault(emptyList())
    }
    col.await() + studio.await()
}

private fun lev(a: String, b: String): Int {
    val d = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var prev = d[0]; d[0] = i
        for (j in 1..b.length) { val t = d[j]; d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1); prev = t }
    }
    return d[b.length]
}

/** The real title closest to what was typed (TMDB's own search tolerates small typos; shorter tries catch the rest). */
private suspend fun didYouMean(key: String, q: String, have: List<SearchHit>): String? {
    val qq = q.lowercase().trim()
    val names = have.map { it.meta.name }.toMutableList()
    if (names.size < 3) {
        // try the first word(s) and the query without its last letters
        listOf(qq.dropLast(1), qq.dropLast(2), qq.split(' ').first()).filter { it.length >= 3 && it != qq }.distinct().forEach { v ->
            runCatching {
                Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/search/multi?api_key=$key&query=${Uri.encode(v)}&page=1")).jsonObject.sArr("results")
                    .take(8).mapNotNull { it.sStr("title") ?: it.sStr("name") }.let { names += it }
            }
        }
    }
    val best = names.distinct().minByOrNull { lev(qq, it.lowercase().take(qq.length + 3)) } ?: return null
    val dist = lev(qq, best.lowercase().take(qq.length + 3))
    return best.takeIf { dist in 1..maxOf(2, qq.length / 3) && !best.equals(q, true) }
}

/* ───────────── UI ───────────── */

private val FieldBg = Color(0xFF1A1D24)
private val SubText = Color(0xFF9AA3B5)
private val ChipText = Color(0xFFB6BDCC)

@Composable
fun PremiumSearch(vm: AppViewModel, initial: String, onOpen: (Meta) -> Unit) {
    var q by rememberSaveable(initial) { mutableStateOf(initial) }
    val hits by SearchState.hits.collectAsStateWithLifecycle()
    val loading by SearchState.loading.collectAsStateWithLifecycle()
    val suggestions by SearchState.suggestions.collectAsStateWithLifecycle()
    val shownQuery by SearchState.query.collectAsStateWithLifecycle()
    val groups by SearchState.groups.collectAsStateWithLifecycle()
    val dym by SearchState.didYouMean.collectAsStateWithLifecycle()
    var openGroup by remember { mutableStateOf<SearchGroup?>(null) }
    openGroup?.let { g -> GroupPage(g, onClose = { openGroup = null }) { m -> openGroup = null; onOpen(m) } }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var recent by remember { mutableStateOf(vm.store("searchRecent").orEmpty().split("\n").filter { it.isNotBlank() }) }
    fun addRecent(v: String) {
        val t = v.trim()
        if (t.length < 3) return
        recent = (listOf(t) + recent.filter { !it.equals(t, ignoreCase = true) }).take(8)
        vm.storePut("searchRecent", recent.joinToString("\n"))
    }

    LaunchedEffect(Unit) {
        val tq = q.trim()
        if (tq.length >= 3 && tq != shownQuery) vm.searchPremium(q)
        else if (tq.length < 3) vm.searchPremium("")
    }

    fun setQuery(v: String) { q = v; vm.searchPremium(v) }
    // Voice search: the phone's speech dialog; what you said becomes the search.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!said.isNullOrEmpty()) { setQuery(said); addRecent(said); keyboard?.hide(); focus.clearFocus() }
    }
    // Xstream: the in-app voice panel (falls back to the phone's dialog when the phone has no speech service).
    var voiceSheet by remember { mutableStateOf(false) }
    fun startVoice() {
        if (android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)) { voiceSheet = true; return }
        val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a movie or show")
        runCatching { voice.launch(i) }.onFailure { Snack.show("Voice search isn't available on this phone") }
    }
    if (voiceSheet) VoiceSearchSheet(
        onSearch = { said -> voiceSheet = false; setQuery(said); addRecent(said); keyboard?.hide(); focus.clearFocus() },
        onAsk = { said -> voiceSheet = false; VoiceAskBus.pending.value = said },
        onClose = { voiceSheet = false },
    )

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Search field
        BasicTextField(
            value = q,
            onValueChange = { setQuery(it) },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
            cursorBrush = SolidColor(Accent),
            keyboardOptions = tvKeyboard(KeyboardOptions(imeAction = ImeAction.Search)),
            keyboardActions = KeyboardActions(onSearch = { addRecent(q); keyboard?.hide(); focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp).tvOkKeyboard(),
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().height(52.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(FieldBg)
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, null, tint = SubText, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (q.isEmpty()) Text(t("searchHint"), color = Color(0xFF6B7385), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                    if (q.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Close, null, tint = SubText,
                            modifier = Modifier.size(22.dp).clickable(
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                            ) { setQuery("") },
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(Color(0x1FE50914))
                            .border(1.dp, Color(0x59E50914), RoundedCornerShape(18.dp)).clickable { startVoice() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Mic, "Voice search", tint = Color.White, modifier = Modifier.size(20.dp)) }
                }
            },
        )

        // Suggestions
        if (q.trim().length >= 3 && suggestions.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            ) {
                items(suggestions, key = { it }) { s ->
                    Row(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x14FFFFFF))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                setQuery(s); keyboard?.hide()
                            }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Search, null, tint = ChipText, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(s, color = ChipText, fontSize = 16.sp, maxLines = 1)
                    }
                }
            }
        }

        val tq = q.trim()
        val wide = LocalConfiguration.current.screenWidthDp >= 600
        val cells = if (wide) GridCells.Adaptive(minSize = 220.dp) else GridCells.Fixed(2)
        when {
            tq.length < 3 && recent.isNotEmpty() -> Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 18.dp, bottom = 140.dp)) {
                if (!BuildConfig.IS_TV) MoodDiscover(vm, onOpen)
                if (!BuildConfig.IS_TV) Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent searches", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = SubText)
                    Text("Clear", Modifier.clip(RoundedCornerShape(10.dp)).clickable { recent = emptyList(); vm.storePut("searchRecent", null) }
                        .padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 14.sp, color = Accent)
                }
                recent.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { q = r; vm.searchPremium(r); keyboard?.hide() }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.History, null, tint = SubText, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(r, Modifier.weight(1f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            tq.length < 3 && !BuildConfig.IS_TV -> Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 140.dp)) {
                MoodDiscover(vm, onOpen)
                Text("Type at least 3 letters to search", Modifier.fillMaxWidth().padding(top = 24.dp), fontSize = 14.sp, color = SubText,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            tq.length < 3 -> CenterNote("Type at least 3 letters to search")
            loading && hits.isEmpty() -> ShimmerGrid(cells)
            !loading && hits.isEmpty() && shownQuery.isNotEmpty() && dym != null -> Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No results for “$tq”", color = SubText, fontSize = 16.sp)
                Text("Did you mean $dym?", Modifier.padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).clickable { setQuery(dym!!); addRecent(dym!!) }.padding(10.dp),
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFF8A80))
            }
            !loading && hits.isEmpty() && shownQuery.isNotEmpty() -> CenterNote("No results for “$tq”")
            else -> Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = cells,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 140.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (dym != null) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "dym") {
                        Text(buildString { append("Did you mean "); append(dym); append("?") }, Modifier.clip(RoundedCornerShape(14.dp)).clickable { setQuery(dym!!); addRecent(dym!!) }
                            .padding(vertical = 6.dp), fontSize = 16.sp, color = Color(0xFFFF8A80), fontWeight = FontWeight.SemiBold)
                    }
                    if (groups.isNotEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "groups") {
                        GroupWidgets(groups) { openGroup = it }
                    }
                    items(hits, key = { it.meta.type + it.meta.id }) { h -> SearchCard(h) { addRecent(q); onOpen(h.meta) } }
                }
                if (loading) LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp).align(Alignment.TopCenter),
                    color = Accent, trackColor = Color.Transparent,
                )
            }
        }
    }
}

@Composable
private fun CenterNote(text: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.TopCenter) {
        Text(text, color = SubText, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 120.dp))
    }
}

@Composable
private fun SearchCard(h: SearchHit, onClick: () -> Unit) {
    val id = h.meta.type + h.meta.id
    val appear = remember(id) { Animatable(0f) }
    LaunchedEffect(id) { appear.animateTo(1f, tween(380)) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "press")
    var logoFailed by remember(id) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth()
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 24.dp.toPx()
                scaleX = scale; scaleY = scale
            }
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Surface1)) {
            AsyncImage(h.backdrop, h.meta.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xB3000000))))
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.4f to Color.Transparent, 1f to Color(0x66000000))))
            if (h.logo != null && !logoFailed) {
                AsyncImage(
                    h.logo, h.meta.name,
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).fillMaxWidth(0.55f).height(40.dp),
                    contentScale = ContentScale.Fit, alignment = Alignment.BottomEnd,
                    onError = { logoFailed = true },
                )
            }
            if (h.isNew) {
                Text(
                    "NEW RELEASE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFF8E2DE2), Color(0xFFE5197A))))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(h.meta.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = listOfNotNull(h.year, h.info).joinToString("  •  ")
        if (sub.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(sub, color = SubText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ShimmerGrid(cells: GridCells) {
    val tr = rememberInfiniteTransition(label = "shimmer")
    val a by tr.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
    LazyVerticalGrid(
        columns = cells,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 140.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        userScrollEnabled = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(6) {
            Column(Modifier.fillMaxWidth().graphicsLayer { alpha = a }) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Surface1))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(0.7f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(Surface1))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(0.45f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(Surface1))
            }
        }
    }
}


/**
 * Collections like phone widgets: the first one big (wide, glass name plate, its posters fanned like a deck of cards
 * with white borders), the next ones small side by side.
 */
@Composable
private fun GroupWidgets(groups: List<SearchGroup>, onOpen: (SearchGroup) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        groups.firstOrNull()?.let { GroupCard(it, Modifier.fillMaxWidth().height(190.dp), big = true) { onOpen(it) } }
        groups.drop(1).chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { g -> GroupCard(g, Modifier.weight(1f).height(150.dp), big = false) { onOpen(g) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GroupCard(g: SearchGroup, modifier: Modifier, big: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(modifier.clip(shape).background(Color(0xFF15161C)).border(1.dp, Color(0x26FFFFFF), shape).clickable(onClick = onClick)) {
        g.backdrop?.let { AsyncImage(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f }, contentScale = ContentScale.Crop) }
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xE6000000), Color(0x33000000)))))
        // the posters, fanned like a deck of cards
        Box(Modifier.align(Alignment.CenterEnd).padding(end = if (big) 18.dp else 10.dp).fillMaxHeight().width(if (big) 170.dp else 92.dp)) {
            g.posters.take(3).forEachIndexed { i, p ->
                val w = if (big) 82.dp else 50.dp
                AsyncImage(p, null, Modifier.align(Alignment.Center).offset(x = ((i - 1) * (if (big) 34 else 18)).dp, y = (if (i == 1) -4 else 6).dp)
                    .graphicsLayer { rotationZ = (i - 1) * 8f }
                    .width(w).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).border(2.dp, Color.White, RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x59FFFFFF)).border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = if (big) 200.dp else 120.dp)) {
            Text(g.name, fontSize = if (big) 17.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.White)
            Text(g.sub, fontSize = 11.sp, color = Color(0xFFE6E8EE), maxLines = 1)
        }
    }
}

/** A collection opened: every title in release order. */
@Composable
private fun GroupPage(g: SearchGroup, onClose: () -> Unit, onOpen: (Meta) -> Unit) {
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0C10)).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.IconButton(onClose) { Icon(Icons.Default.Close, "Close") }
                Column {
                    Text(g.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(g.sub, fontSize = 12.sp, color = SubText)
                }
            }
            LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 60.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(g.items.size) { i ->
                    val m = g.items[i]
                    Column(Modifier.clickable { onOpen(m) }) {
                        AsyncImage(m.poster, m.name, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                        Text("${i + 1}. ${m.name}", Modifier.padding(top = 6.dp), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        m.releaseInfo?.let { Text(it, fontSize = 11.sp, color = SubText) }
                    }
                }
            }
        }
    }
}
