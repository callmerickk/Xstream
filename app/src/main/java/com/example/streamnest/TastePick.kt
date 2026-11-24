package com.example.streamnest

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "What do you love watching?" — shown once to every profile (new ones and existing ones), skippable. Languages, genres
 * and 3+ titles you like (tap posters, or search for any title). No ratings or votes: the picks become this profile's
 * "liked" titles, the AI reads them with the languages and genres, and "Picked for you" (Home and the profile page) is
 * built from them. Later, what you finish or drop keeps updating the taste quietly.
 * 0.9.1: on the shared code behind Feature.tastePick; the glowing ground is StreamX's PhoneHorizonGround.
 */

private data class TasteItem(val key: String, val title: String, val poster: String?, val type: String, val meta: Meta? = null, val rec: TmRec? = null)

private val TasteGenres = listOf("Action", "Comedy", "Drama", "Thriller", "Romance", "Horror", "Sci-Fi", "Crime", "Mystery", "Fantasy", "Animation", "Family", "Documentary")
private val MovieGenreIds = mapOf("Action" to 28, "Comedy" to 35, "Drama" to 18, "Thriller" to 53, "Romance" to 10749, "Horror" to 27, "Sci-Fi" to 878,
    "Crime" to 80, "Mystery" to 9648, "Fantasy" to 14, "Animation" to 16, "Family" to 10751, "Documentary" to 99)
private val TvGenreIds = mapOf("Action" to 10759, "Comedy" to 35, "Drama" to 18, "Thriller" to 9648, "Romance" to 18, "Horror" to 9648, "Sci-Fi" to 10765,
    "Crime" to 80, "Mystery" to 9648, "Fantasy" to 10765, "Animation" to 16, "Family" to 10751, "Documentary" to 99)

/** True when this profile hasn't been asked for its favourite titles yet (shown once, after languages). */
fun tasteTitlesDue(texts: Map<String, String>, p: Profile?): Boolean =
    Feature.tastePick && !BuildConfig.IS_TV && p != null && !p.kids && !texts["tasteDone"].isNullOrBlank() && texts["tasteTitles"].isNullOrBlank()

private suspend fun AppViewModel.tasteSuggestions(langs: List<String>, genres: List<String>): List<TasteItem> = coroutineScope {
    if (tmdbKey().isNotEmpty()) {
        val codes = langs.take(3).map<String, String?> { it }.ifEmpty { listOf<String?>(null) }
        val lists = codes.flatMap { c ->
            listOf(
                async { tmdbDiscover("movie", c, genres.mapNotNull { MovieGenreIds[it] }.distinct()) },
                async { tmdbDiscover("tv", c, genres.mapNotNull { TvGenreIds[it] }.distinct()) },
            )
        }.awaitAll()
        val out = ArrayList<TmRec>()
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { l -> l.getOrNull(i)?.let { out += it } }
        out.distinctBy { it.kind + it.tmdbId }.filter { it.poster != null }.take(42)
            .map { r -> TasteItem("t:${r.kind}${r.tmdbId}", r.title, r.poster, if (r.kind == "tv") "series" else "movie", rec = r) }
    } else {
        runCatching { genreTitles(genres.ifEmpty { listOf("Action", "Drama", "Comedy") }, listOf("movie", "series")) }.getOrDefault(emptyList())
            .filter { it.poster != null }.take(42).map { m -> TasteItem("m:${m.id}", m.name, m.poster, m.type, meta = m) }
    }
}

@Composable
fun TasteTitlesScreen(vm: AppViewModel, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Languages: the codes chosen on the first taste step (hi, te…), changeable here.
    val langs = remember { mutableStateListOf<String>().apply { addAll(vm.tasteLangs()) } }
    val genres = remember { mutableStateListOf<String>() }
    val picked = remember { mutableStateMapOf<String, TasteItem>() }
    val searchedPicks = remember { mutableStateListOf<String>() }
    var grid by remember { mutableStateOf<List<TasteItem>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TasteItem>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    val name = vm.current.collectAsState().value?.name.orEmpty()

    LaunchedEffect(langs.toList(), genres.toList()) {
        delay(350); loading = true
        grid = runCatching { vm.tasteSuggestions(langs.toList(), genres.toList()) }.getOrDefault(emptyList())
        loading = false
    }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = null; return@LaunchedEffect }
        delay(450)
        results = runCatching { vm.searchNow(q) }.getOrDefault(emptyList()).filter { it.type == "movie" || it.type == "series" }
            .take(24).map { m -> TasteItem("m:${m.id}", m.name, m.poster, m.type, meta = m) }
    }
    fun finish(skip: Boolean) {
        if (saving) return
        if (skip) { vm.setText("tasteTitles", "skip"); onDone(); return }
        saving = true
        scope.launch {
            if (langs.isNotEmpty()) vm.setText("tasteLangs", langs.joinToString(","))
            TasteRows.cache.clear()
            vm.setText("tasteGenres", genres.joinToString(", "))
            vm.setText("tasteSearched", searchedPicks.joinToString(", "))
            // Each pick becomes a liked title of this profile (the AI and "Picked for you" read them).
            picked.values.toList().forEach { it2 ->
                val m = it2.meta ?: it2.rec?.let { r ->
                    vm.tmdbToImdb(r)?.let { id -> Meta(id = id, type = if (r.kind == "tv") "series" else "movie", name = r.title, poster = r.poster,
                        genres = r.genreIds.mapNotNull { g -> TmdbGenres[g] }.distinct()) }
                }
                if (m != null) vm.vote(m, 1)
            }
            ActLog.add("APP", "taste picked: ${picked.size} titles · ${langs.joinToString("/")} · ${genres.joinToString("/")}")
            vm.setText("tasteTitles", "1")
            onDone()
        }
    }
    BackHandler { finish(skip = true) }

    Box(Modifier.fillMaxSize().background(Color(0xFF0F1014)).background(Brush.radialGradient(listOf(Color(0x5523306E), Color.Transparent), radius = 1400f))) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Wordmark(Brand.NAME, 20.sp)
                Spacer(Modifier.weight(1f))
                Text("Skip", Modifier.clip(RoundedCornerShape(14.dp)).clickable { finish(true) }.padding(horizontal = 12.dp, vertical = 8.dp), color = Color(0xFFB8BED3), fontSize = 15.sp)
            }
            Text(if (name.isNotBlank()) "$name, what do you love watching?" else "What do you love watching?",
                Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
            Text("Pick languages, genres and 3 or more titles. Search for any title you love.", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), fontSize = 12.5.sp, color = Muted)
            // Search: any title, added to your picks.
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(Color(0xFF1A1C23))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(23.dp)).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = Muted)
                Box(Modifier.weight(1f).padding(start = 10.dp)) {
                    if (query.isEmpty()) Text("Search a movie or series you love", color = Color(0xFF6D7388), fontSize = 14.sp)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(BrandRed), modifier = Modifier.fillMaxWidth())
                }
                if (query.isNotEmpty()) Icon(Icons.Default.Close, "Clear", Modifier.size(20.dp).clickable { query = "" }, tint = Muted)
            }
            @Composable fun Chips(all: List<String>, on: MutableList<String>) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    all.forEach { c ->
                        val sel = c in on
                        Text(c, Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) BrandRed else Color.Transparent)
                            .border(1.dp, if (sel) BrandRed else Color(0x44FFFFFF), RoundedCornerShape(16.dp))
                            .clickable { if (sel) on.remove(c) else on.add(c) }.padding(horizontal = 14.dp, vertical = 7.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            if (results == null) {
                // Languages show their names; stored as codes.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TasteLangs.forEach { l ->
                        val sel = l.code in langs
                        Text(l.name, Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) BrandRed else Color.Transparent)
                            .border(1.dp, if (sel) BrandRed else Color(0x44FFFFFF), RoundedCornerShape(16.dp))
                            .clickable { if (sel) langs.remove(l.code) else langs.add(l.code) }.padding(horizontal = 14.dp, vertical = 7.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Chips(TasteGenres, genres)
            }
            val shown = results ?: grid
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (shown.isEmpty() && (loading || results == null)) CircularProgressIndicator(Modifier.align(Alignment.Center).size(30.dp), color = BrandRed, strokeWidth = 2.5.dp)
                else if (shown.isEmpty()) Text("Nothing found", Modifier.align(Alignment.Center), color = Muted)
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 150.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(shown, key = { it.key }) { t ->
                        val sel = picked.containsKey(t.key) || (t.meta != null && picked.values.any { it.meta?.id == t.meta.id })
                        Box(Modifier.aspectRatio(0.68f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A1C23))
                            .then(if (sel) Modifier.border(3.dp, BrandRed, RoundedCornerShape(12.dp)) else Modifier.border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp)))
                            .clickable {
                                if (sel) { picked.remove(t.key); searchedPicks.remove(t.title) }
                                else { picked[t.key] = t; if (results != null) searchedPicks.add(t.title) }
                            }) {
                            AsyncImage(t.poster, t.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (t.poster == null) Text(t.title, Modifier.align(Alignment.Center).padding(8.dp), fontSize = 12.sp, textAlign = TextAlign.Center)
                            if (sel) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(BrandRed), Alignment.Center) {
                                Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
        // The glowing horizon at the bottom with the Continue button.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(150.dp)) {
            PhoneHorizonGround(Modifier.matchParentSize())
            Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when (val n = picked.size) { 0 -> "Pick at least 3"; 1, 2 -> "$n picked — pick at least 3"; else -> "$n picked" }, fontSize = 12.5.sp, color = Color(0xFFC3C7D6))
                Box(Modifier.padding(top = 10.dp).width(280.dp).height(48.dp).clip(RoundedCornerShape(24.dp))
                    .background(if (picked.size >= 3) Color.White else Color(0x33FFFFFF)).clickable(enabled = picked.size >= 3 && !saving) { finish(false) }, Alignment.Center) {
                    if (saving) CircularProgressIndicator(Modifier.size(22.dp), color = Color.Black, strokeWidth = 2.dp)
                    else Text("Continue", color = if (picked.size >= 3) Color.Black else Color(0x99FFFFFF), fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
