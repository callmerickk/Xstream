package com.example.streamnest

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/*
 * Search › Discover, laid out like Instagram's Explore: blocks of one big rounded tall poster beside two small
 * landscape cards, the big one switching sides every block. Used for the Discover page and for search results (phone).
 */

private val BigShape = RoundedCornerShape(22.dp)
private val SmallShape = RoundedCornerShape(18.dp)
private const val BLOCK_H = 268

/** Adds the mosaic to a LazyColumn: every 3 titles make one block. [extra] = optional line under each title (year…). */
fun LazyListScope.discoverMosaic(items: List<Meta>, keyPrefix: String, wide: (Meta) -> String? = { null }, onOpen: (Meta) -> Unit) {
    val blocks = items.chunked(3)
    blocks.forEachIndexed { bi, block ->
        item(key = "$keyPrefix:${bi}:${block.first().type}${block.first().id}", contentType = "mosaic") {
            MosaicBlock(block, flip = bi % 2 == 1, wide = wide, onOpen = onOpen)
        }
    }
}

@Composable
private fun MosaicBlock(block: List<Meta>, flip: Boolean, wide: (Meta) -> String?, onOpen: (Meta) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).height(BLOCK_H.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val big = block[0]
        if (!flip) BigTile(big, Modifier.weight(1f), onOpen)
        Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            block.drop(1).forEach { m -> SmallTile(m, wide(m), Modifier.weight(1f), onOpen) }
            if (block.size < 3) Box(Modifier.weight((3 - block.size).toFloat()))
        }
        if (flip) BigTile(big, Modifier.weight(1f), onOpen)
    }
}

@Composable
private fun BigTile(m: Meta, modifier: Modifier, onOpen: (Meta) -> Unit) {
    Box(modifier.fillMaxSize().clip(BigShape).border(1.dp, Color(0x1FFFFFFF), BigShape).titleTap(m) { onOpen(m) }) {
        TallPoster(m, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.62f to Color.Transparent, 1f to Color(0xCC000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(m.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(m.releaseInfo?.take(4), if (m.type == "series") "Series" else "Movie").joinToString(" · ")
            Text(sub, fontSize = 11.sp, color = Color(0xFFC4C9D4), maxLines = 1)
        }
    }
}

@Composable
private fun SmallTile(m: Meta, wideUrl: String?, modifier: Modifier, onOpen: (Meta) -> Unit) {
    var failed by remember(m.id) { mutableStateOf(false) }
    val url = if (!failed) wideUrl ?: hiResArt(backdropOf(m)) else null
    Box(modifier.fillMaxWidth().clip(SmallShape).background(Surface1).border(1.dp, Color(0x1FFFFFFF), SmallShape).titleTap(m) { onOpen(m) }) {
        if (url != null) AsyncImage(url, m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { failed = true })
        else TallPoster(m, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD9000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(m.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(m.releaseInfo?.take(4), if (m.type == "series") "Series" else "Movie").joinToString(" · ")
            Text(sub, fontSize = 11.sp, color = Color(0xFFC4C9D4), maxLines = 1)
        }
    }
}

/**
 * Discover, made from what you watch: titles from your Home rows that share genres with what you watched or liked
 * (Continue Watching, your votes), TMDB's "more like this" for your latest titles, never what you voted down or finished.
 */
object DiscoverPool {
    @Volatile var because: String? = null
    /** Built once in the background when the app opens; stays the same until the app is opened again. */
    val ready = kotlinx.coroutines.flow.MutableStateFlow<List<Meta>?>(null)
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    fun ensure(vm: AppViewModel, shelves: List<Shelf>) {
        if (shelves.sumOf { it.items.size } < 20 || !started.compareAndSet(false, true)) return
        vm.viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val built = runCatching { kotlinx.coroutines.withTimeoutOrNull(15_000) { build(vm, shelves) } }.getOrNull()?.takeIf { it.size >= 6 }
            ready.value = built ?: shelves.flatMap { it.items }.filter { it.poster != null || it.background != null }
                .distinctBy { it.id.substringBefore(":") }.shuffled(java.util.Random(HomeShuffle.seed)).take(45)
        }
    }

    suspend fun build(vm: AppViewModel, shelves: List<Shelf>): List<Meta> = kotlinx.coroutines.coroutineScope {
        val all = shelves.flatMap { it.items }.filter { it.poster != null || it.background != null }.distinctBy { it.id.substringBefore(":") }
        val byId = all.associateBy { it.id.substringBefore(":") }
        val prog = vm.progress.value
        val votes = vm.votes.value.values
        val watched = (prog.map { it.key.substringBefore(":") } + vm.doneKeys.value.map { it.substringBefore(":") }).toSet()
        val disliked = votes.filter { it.v < 0 }.map { it.id.substringBefore(":") }.toSet()
        // How much each genre is yours: liked votes count most, then what you are watching.
        val w = HashMap<String, Float>()
        votes.filter { it.v > 0 }.forEach { v -> v.genres.forEach { g -> w[g.lowercase()] = (w[g.lowercase()] ?: 0f) + 2f } }
        prog.take(12).forEach { p -> byId[p.key.substringBefore(":")]?.genres.orEmpty().forEach { g -> w[g.lowercase()] = (w[g.lowercase()] ?: 0f) + 1f } }
        val rnd = java.util.Random(HomeShuffle.seed)
        val local = all.filter { it.id.substringBefore(":") !in watched && it.id.substringBefore(":") !in disliked }
            .map { m -> m to (m.genres.orEmpty().sumOf { g -> (w[g.lowercase()] ?: 0f).toDouble() } + rnd.nextDouble() * 1.5) }
            .sortedByDescending { it.second }.map { it.first }
        // "More like this" from TMDB for the 3 latest titles you watched.
        val recent = prog.filter { it.key.startsWith("tt") }.take(3)
        because = recent.firstOrNull()?.title?.substringBefore("  S")?.trim()
        val more = recent.map { p ->
            async {
                runCatching {
                    val type = if (p.key.contains(":")) "series" else "movie"
                    val info = kotlinx.coroutines.withTimeoutOrNull(6_000) { vm.tmdbInfo(p.key.substringBefore(":"), type) } ?: return@runCatching emptyList<Meta>()
                    info.recs.take(5).map { r ->
                        async {
                            runCatching {
                                val imdb = vm.tmdbToImdb(r) ?: return@runCatching null
                                Meta(id = imdb, type = if (r.kind == "tv") "series" else "movie", name = r.title, poster = r.poster, releaseInfo = r.year)
                            }.getOrNull()
                        }
                    }.awaitAll().filterNotNull()
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().filter { it.id !in watched && it.id !in disliked }.distinctBy { it.id }
        // Mixed: two of yours from Home, one "more like this", and so on.
        val out = ArrayList<Meta>(); val li = local.iterator(); val mi = more.iterator()
        while (out.size < 45 && (li.hasNext() || mi.hasNext())) {
            repeat(2) { if (li.hasNext()) out += li.next() }
            if (mi.hasNext()) out += mi.next()
        }
        out.distinctBy { it.id.substringBefore(":") }
    }
}

/** Surprise me: a well-rated title from your kind of genres that it hasn't surprised you with before (last 50). */
object Surprise {
    fun pick(vm: AppViewModel, pool: List<Meta>): Meta? {
        val seen = (vm.store("surpriseSeen") ?: "").split("|").filter { it.isNotBlank() }
        val fresh = pool.filter { it.id.substringBefore(":") !in seen }
        val good = fresh.filter { (it.imdbRating?.toDoubleOrNull() ?: 7.0) >= 6.5 }.ifEmpty { fresh }.ifEmpty { pool }
        // Earlier in the pool = closer to your taste: pick among the best third, at random.
        val m = good.take(maxOf(6, good.size / 3)).randomOrNull() ?: return null
        vm.storePut("surpriseSeen", (seen + m.id.substringBefore(":")).takeLast(50).joinToString("|"))
        return m
    }
}
