package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.Locale

/*
 * Your taste, per profile (asked once on a first start, changed any time in My Space): what you watch (movies, series,
 * anime, K-drama) and your languages, in order. Home then gets "New for you" mixing all your languages, and a pill per
 * language opens that language's own page (trending movies and series, new releases), like Netflix.
 * Everything here is stored with the profile (and synced like the rest of its settings).
 */

data class TasteLang(val code: String, val name: String, val native: String)

val TasteLangs = listOf(
    TasteLang("hi", "Hindi", "हिन्दी"), TasteLang("en", "English", "English"), TasteLang("te", "Telugu", "తెలుగు"),
    TasteLang("ta", "Tamil", "தமிழ்"), TasteLang("ml", "Malayalam", "മലയാളം"), TasteLang("kn", "Kannada", "ಕನ್ನಡ"),
    TasteLang("bn", "Bengali", "বাংলা"), TasteLang("mr", "Marathi", "मराठी"), TasteLang("pa", "Punjabi", "ਪੰਜਾਬੀ"),
    TasteLang("ko", "Korean", "한국어"), TasteLang("ja", "Japanese", "日本語"), TasteLang("es", "Spanish", "Español"),
    TasteLang("fr", "French", "Français"), TasteLang("zh", "Chinese", "中文"), TasteLang("tr", "Turkish", "Türkçe"),
)
val TasteTypes = listOf("movie" to "Movies", "series" to "Series", "anime" to "Anime", "kdrama" to "K-Drama")

fun AppViewModel.tasteLangs(): List<String> = (texts.value["tasteLangs"] ?: "").split(",").filter { it.isNotBlank() }
fun AppViewModel.tasteTypes(): Set<String> = (texts.value["tasteTypes"] ?: "").split(",").filter { it.isNotBlank() }.toSet()

/** First start on a profile: what do you like? (phones). */
@Composable
fun TasteOnboarding(vm: AppViewModel, onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var types by remember { mutableStateOf(vm.tasteTypes().ifEmpty { setOf("movie", "series") }) }
    var langs by remember {
        mutableStateOf(vm.tasteLangs().ifEmpty {
            if (Locale.getDefault().country.equals("IN", true)) listOf("hi", "en") else listOf("en")
        })
    }
    androidx.compose.ui.window.Dialog({}, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A0A10), Color(0xFF0B0B0F)))).statusBarsPadding().navigationBarsPadding().padding(22.dp)) {
            Text(if (step == 0) "What do you like to watch?" else "Your languages", fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
            Text(if (step == 0) "Pick all that fit. Home is made from this." else "Tap them in order: the first is your favourite. Titles start in it when it's there, otherwise in the original language.",
                Modifier.padding(top = 8.dp), fontSize = 14.sp, color = Color(0xFFB9A6AB))
            Column(Modifier.weight(1f).padding(top = 20.dp).verticalScroll(rememberScrollState())) {
                if (step == 0) TasteTypes.forEach { (k, label) ->
                    val on = k in types
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(18.dp)).background(if (on) Color(0x33E50914) else Color(0x14FFFFFF))
                        .border(1.dp, if (on) Color(0xFFE50914) else Color(0x22FFFFFF), RoundedCornerShape(18.dp)).clickable { types = if (on) types - k else types + k }.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        if (on) Icon(Icons.Default.Check, null, tint = Color(0xFFFF5A64))
                    }
                } else {
                    TasteLangs.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { l ->
                                val i = langs.indexOf(l.code)
                                Row(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (i >= 0) Color(0x33E50914) else Color(0x14FFFFFF))
                                    .border(1.dp, if (i >= 0) Color(0xFFE50914) else Color(0x22FFFFFF), RoundedCornerShape(16.dp))
                                    .clickable { langs = if (i >= 0) langs - l.code else langs + l.code }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(l.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                        Text(l.native, fontSize = 12.sp, color = Color(0xFFB9A6AB))
                                    }
                                    if (i >= 0) Text("${i + 1}", Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFE50914)).padding(horizontal = 9.dp, vertical = 3.dp), fontWeight = FontWeight.Bold)
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            Button({
                if (step == 0) step = 1 else {
                    vm.setText("tasteTypes", types.joinToString(","))
                    vm.setText("tasteLangs", langs.joinToString(","))
                    vm.setText("tasteDone", "1")
                    TasteRows.cache.clear()
                    onDone()
                }
            }, Modifier.fillMaxWidth().height(56.dp), enabled = if (step == 0) types.isNotEmpty() else langs.isNotEmpty(), shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914), contentColor = Color.White)) {
                Text(if (step == 0) "Next" else "Done", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            if (step == 0) Text("Skip", Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).clickable { vm.setText("tasteDone", "skip"); onDone() }.padding(8.dp), color = Color(0xFFB9A6AB))
        }
    }
}

/** Rows made from your taste, from TMDB (needs the TMDB key the developer publishes). */
object TasteRows {
    val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Meta>>>()

    private suspend fun discover(vm: AppViewModel, kind: String, q: String): List<Meta> = withContext(Dispatchers.IO) {
        val key = vm.tmdbKey().ifEmpty { return@withContext emptyList() }
        val ck = "$kind?$q"
        cache[ck]?.let { if (System.currentTimeMillis() - it.first < 6 * 3600_000L) return@withContext it.second }
        val list = runCatching {
            val o = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/discover/$kind?api_key=$key&language=en-US&include_adult=false&page=1&$q")).jsonObject
            o["results"]!!.jsonArray.mapNotNull { e ->
                val r = e as? JsonObject ?: return@mapNotNull null
                val id = (r["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val name = ((r["title"] ?: r["name"]) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val poster = (r["poster_path"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                Meta(id = "tmdb:$kind:$id", type = if (kind == "tv") "series" else "movie", name = name,
                    poster = "https://image.tmdb.org/t/p/w500$poster",
                    background = (r["backdrop_path"] as? JsonPrimitive)?.contentOrNull?.let { "https://image.tmdb.org/t/p/w1280$it" },
                    description = (r["overview"] as? JsonPrimitive)?.contentOrNull,
                    releaseInfo = ((r["release_date"] ?: r["first_air_date"]) as? JsonPrimitive)?.contentOrNull?.take(4),
                    imdbRating = (r["vote_average"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it) })
            }
        }.getOrDefault(emptyList())
        if (list.isNotEmpty()) cache[ck] = System.currentTimeMillis() to list
        list
    }

    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
    private fun ago(days: Int) = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(System.currentTimeMillis() - days * 86_400_000L))

    /** "New for you": the newest releases in all your languages, mixed. */
    suspend fun newForYou(vm: AppViewModel): List<Meta> = coroutineScope {
        val langs = vm.tasteLangs().take(4)
        if (langs.isEmpty()) return@coroutineScope emptyList()
        val types = vm.tasteTypes()
        val lists = langs.flatMap { l ->
            buildList {
                if (types.isEmpty() || "movie" in types) add(async { discover(vm, "movie", "with_original_language=$l&primary_release_date.gte=${ago(120)}&primary_release_date.lte=${today()}&sort_by=popularity.desc&vote_count.gte=3") })
                if (types.isEmpty() || "series" in types) add(async { discover(vm, "tv", "with_original_language=$l&first_air_date.gte=${ago(150)}&first_air_date.lte=${today()}&sort_by=popularity.desc&vote_count.gte=2") })
            }
        }.awaitAll()
        // round-robin so every language shows up near the start
        val out = ArrayList<Meta>()
        var i = 0
        while (out.size < 40 && lists.any { it.size > i }) { lists.forEach { l -> l.getOrNull(i)?.let { out += it } }; i++ }
        out.distinctBy { it.id }
    }

    /** One language's own page. */
    suspend fun forLanguage(vm: AppViewModel, l: String): List<Shelf> = coroutineScope {
        val name = TasteLangs.firstOrNull { it.code == l }?.name ?: l
        val types = vm.tasteTypes()
        val jobs = buildList {
            add("Trending $name movies" to async { discover(vm, "movie", "with_original_language=$l&primary_release_date.gte=${ago(730)}&sort_by=popularity.desc&vote_count.gte=20") })
            add("Trending $name series" to async { discover(vm, "tv", "with_original_language=$l&first_air_date.gte=${ago(1100)}&sort_by=popularity.desc&vote_count.gte=10") })
            add("New in $name" to async { discover(vm, "movie", "with_original_language=$l&primary_release_date.gte=${ago(90)}&primary_release_date.lte=${today()}&sort_by=primary_release_date.desc&vote_count.gte=1") })
            add("Top rated $name movies" to async { discover(vm, "movie", "with_original_language=$l&sort_by=vote_average.desc&vote_count.gte=${if (l == "en") 3000 else 150}") })
            add("Top rated $name series" to async { discover(vm, "tv", "with_original_language=$l&sort_by=vote_average.desc&vote_count.gte=${if (l == "en") 1500 else 60}") })
            if ("anime" in types && l == "ja") add("Anime" to async { discover(vm, "tv", "with_original_language=ja&with_genres=16&sort_by=popularity.desc") })
            if ("kdrama" in types && l == "ko") add("K-Drama" to async { discover(vm, "tv", "with_original_language=ko&with_genres=18&sort_by=popularity.desc") })
        }
        jobs.map { (t, d) -> Shelf(t, d.await()) }.filter { it.items.isNotEmpty() }
    }

    /** Anime / K-drama rows when picked (shown on Home). */
    suspend fun extras(vm: AppViewModel): List<Shelf> = coroutineScope {
        val types = vm.tasteTypes()
        val a = if ("anime" in types) async { Shelf("Anime for you", discover(vm, "tv", "with_original_language=ja&with_genres=16&sort_by=popularity.desc&vote_count.gte=50")) } else null
        val k = if ("kdrama" in types) async { Shelf("K-Drama for you", discover(vm, "tv", "with_original_language=ko&with_genres=18&sort_by=popularity.desc&vote_count.gte=30")) } else null
        listOfNotNull(a?.await(), k?.await()).filter { it.items.isNotEmpty() }
    }
}

/** Home: one pill per language you picked; tapping opens that language's page. */
@Composable
fun LanguagePills(vm: AppViewModel, onOpen: (Meta) -> Unit) {
    val texts by vm.texts.collectAsState()
    val langs = remember(texts["tasteLangs"]) { vm.tasteLangs() }
    if (langs.size < 1 || vm.tmdbKey().isEmpty()) return
    var open by remember { mutableStateOf<String?>(null) }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 14.dp)) {
        items(langs) { l ->
            val tl = TasteLangs.firstOrNull { it.code == l }
            Text(tl?.name ?: l, Modifier.clip(RoundedCornerShape(50)).background(Color(0x1FFFFFFF)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50))
                .clickable { open = l }.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    open?.let { l ->
        var shelves by remember(l) { mutableStateOf<List<Shelf>?>(null) }
        LaunchedEffect(l) { shelves = TasteRows.forLanguage(vm, l) }
        androidx.compose.ui.window.Dialog({ open = null }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ open = null }) { Icon(Icons.Default.Close, "Close") }
                    Text(TasteLangs.firstOrNull { it.code == l }?.name ?: l, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                val sh = shelves
                if (sh == null) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Color.White) }
                else if (sh.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Nothing found right now", color = Color(0xFF9AA3B5)) }
                else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
                    items(sh, key = { it.title }) { s ->
                        Text(s.title, Modifier.padding(start = 16.dp, top = 18.dp, bottom = 10.dp), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        PortraitRow(s.items) { m -> open = null; onOpen(m) }
                    }
                }
            }
        }
    }
}
