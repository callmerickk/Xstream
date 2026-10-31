package com.example.streamnest

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Xstream Music — laid out like the YouTube Music app: Home / Samples / Explore / Library, a mini player above the bar,
 * Now playing with Song | Video in one player, Up next · Lyrics · Related, and a full-screen video player.
 */

private val MBg = Color(0xFF030303)
private val MCard = Color(0xFF1D1D1D)
private val MSub = Color(0xFFAAAAAA)

private sealed interface MPage {
    data class Browse(val id: String, val title: String, val item: YtmItem?, val params: String? = null) : MPage
    data object Search : MPage
    data class Explore(val which: String) : MPage
    data class Local(val title: String, val items: List<YtmItem>) : MPage
}

private tailrec fun Context.musicActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.musicActivity()
    else -> null
}

/** One song / album / artist row or card was tapped. */
private class MusicNav(
    val open: (YtmItem, List<YtmItem>, String?) -> Unit,
    val menu: (YtmItem) -> Unit,
    val push: (MPage) -> Unit,
)

@Composable
fun MusicApp(
    profileId: String,
    profileName: String,
    profileVm: AppViewModel? = null,
    initiallyNowPlaying: Boolean = false,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("home") }
    val stack = remember { mutableStateListOf<MPage>() }
    var nowOpen by remember { mutableStateOf(initiallyNowPlaying) }
    var fullVideo by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<YtmItem?>(null) }
    var libVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(profileId) { MusicPlayer.init(ctx); MusicLibraryStore.open(ctx, profileId); libVersion++ }
    LaunchedEffect(Unit) { while (true) { MusicPlayer.publish(); delay(500) } }

    val nav = remember {
        MusicNav(
            open = { item, list, label ->
                when (item.kind) {
                    YtmKind.SONG, YtmKind.VIDEO -> {
                        if (label == null) MusicPlayer.startRadio(ctx, item) else MusicPlayer.playList(ctx, list, list.indexOf(item), label)
                        scope.launch { MusicLibraryStore.recordPlay(ctx, item); libVersion++ }
                        if (item.kind == YtmKind.VIDEO) nowOpen = true
                    }
                    else -> stack.add(MPage.Browse(item.id, item.title, item, item.params))
                }
            },
            menu = { menuFor = it },
            push = { stack.add(it) },
        )
    }

    BackHandler {
        when {
            fullVideo -> fullVideo = false
            menuFor != null -> menuFor = null
            nowOpen -> nowOpen = false
            stack.isNotEmpty() -> stack.removeAt(stack.lastIndex)
            tab != "home" -> tab = "home"
            else -> onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(MBg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (val p = stack.lastOrNull()) {
                    is MPage.Browse -> BrowsePage(p, nav) { stack.removeAt(stack.lastIndex) }
                    is MPage.Search -> SearchPage(nav, libVersion) { stack.removeAt(stack.lastIndex) }
                    is MPage.Explore -> ExplorePage(p.which, nav) { stack.removeAt(stack.lastIndex) }
                    is MPage.Local -> LocalPage(p, nav) { stack.removeAt(stack.lastIndex) }
                    null -> when (tab) {
                        "samples" -> SamplesTab(nav) { nowOpen = true }
                        "explore" -> ExploreTab(nav)
                        "library" -> LibraryTab(nav, libVersion) { libVersion++ }
                        else -> HomeTab(profileName, nav, libVersion)
                    }
                }
            }
            if (now.track != null) MiniPlayer(onOpen = { nowOpen = true })
            MusicBottomBar(tab) { stack.clear(); tab = it }
        }
        AnimatedVisibility(nowOpen, enter = slideInVertically(tween(320)) { it } + fadeIn(), exit = slideOutVertically(tween(280)) { it } + fadeOut()) {
            NowPlaying(nav, onClose = { nowOpen = false }, onFull = { fullVideo = true }, onArtist = { name ->
                nowOpen = false
                scope.launch {
                    val a = runCatching { YtMusic.search(name, "artists").items.firstOrNull { it.kind == YtmKind.ARTIST } }.getOrNull()
                    if (a != null) stack.add(MPage.Browse(a.id, a.title, a))
                }
            })
        }
        if (fullVideo) FullVideo { fullVideo = false }
        menuFor?.let { m -> ItemMenu(m, nav, onDone = { menuFor = null; libVersion++ }) }
    }
}

// ─────────────────────────── building blocks ───────────────────────────

@Composable
private fun Art(url: String?, size: Dp, round: Boolean = false, corner: Dp = 6.dp, modifier: Modifier = Modifier) {
    val shape = if (round) CircleShape else RoundedCornerShape(corner)
    Box(modifier.size(size).clip(shape).background(MCard), Alignment.Center) {
        Icon(Icons.Default.MusicNote, null, Modifier.size(size * 0.35f), tint = Color(0x44FFFFFF))
        if (!url.isNullOrBlank()) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun SongRow(item: YtmItem, current: Boolean, onMenu: (() -> Unit)?, number: Int? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (number != null) Text("$number", Modifier.width(28.dp), color = MSub, fontSize = 14.sp, textAlign = TextAlign.Center)
        Art(item.thumb, 50.dp, round = item.kind == YtmKind.ARTIST)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(item.title, color = if (current) Color(0xFFFF4E45) else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle, color = MSub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onMenu != null) IconButton(onMenu) { Icon(Icons.Default.MoreVert, "More", tint = MSub) }
    }
}

@Composable
private fun SectionTitle(title: String, strap: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            if (!strap.isNullOrBlank()) Text(strap.uppercase(), color = MSub, fontSize = 11.sp, letterSpacing = 0.8.sp)
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (action != null && onAction != null)
            Text(action, Modifier.clip(RoundedCornerShape(50)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50)).clickable(onClick = onAction)
                .padding(horizontal = 12.dp, vertical = 5.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** One Home / Explore / Related shelf, drawn the way YouTube Music draws that kind of shelf. */
@Composable
private fun Shelf(shelf: YtmShelf, nav: MusicNav, currentId: String?) {
    val songs = shelf.items.filter { it.kind == YtmKind.SONG || it.kind == YtmKind.VIDEO }
    val ctx = LocalContext.current
    val listLike = shelf.style == YtmStyle.QUICK_PICKS || (shelf.style == YtmStyle.LIST && songs.size >= 4)
    SectionTitle(shelf.title, shelf.strapline, if (songs.size >= 3) "Play all" else null) { MusicPlayer.playList(ctx, songs, 0, shelf.title) }
    val w = LocalConfiguration.current.screenWidthDp.dp
    when {
        listLike -> LazyHorizontalGrid(GridCells.Fixed(4), Modifier.height(256.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
            items(shelf.items.size) { i ->
                val it = shelf.items[i]
                Box(Modifier.width(w * 0.88f)) { SongRow(it, it.id == currentId, { nav.menu(it) }) { nav.open(it, shelf.items, null) } }
            }
        }
        shelf.style == YtmStyle.LIST -> Column { shelf.items.forEach { SongRow(it, it.id == currentId, { nav.menu(it) }) { nav.open(it, shelf.items, null) } } }
        else -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(shelf.items, key = { i, it -> "$i${it.id}" }) { _, it ->
                val wide = it.kind == YtmKind.VIDEO || shelf.style == YtmStyle.WIDE_VIDEO_CAROUSEL
                val big = shelf.style == YtmStyle.BIG_DISCOVER || shelf.style == YtmStyle.COMMUNITY_PLAYLIST
                val cw = when { big -> 280.dp; wide -> 260.dp; else -> 150.dp }
                val ch = when { big -> 280.dp; wide -> 146.dp; else -> 150.dp }
                Column(Modifier.width(cw).combinedClickableCompat({ nav.open(it, shelf.items, null) }, { nav.menu(it) })) {
                    Box(Modifier.width(cw).height(ch).clip(if (it.kind == YtmKind.ARTIST) CircleShape else RoundedCornerShape(if (big) 14.dp else 6.dp)).background(MCard)) {
                        if (it.thumb != null) AsyncImage(it.thumb, it.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (big) Box(Modifier.align(Alignment.BottomEnd).padding(12.dp).size(44.dp).clip(CircleShape).background(Color(0xCCFFFFFF)), Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, "Play", tint = Color.Black)
                        }
                    }
                    Text(it.title, Modifier.padding(top = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = if (it.kind == YtmKind.ARTIST) TextAlign.Center else TextAlign.Start, modifier2 = Modifier.fillMaxWidth())
                    Text(it.subtitle, fontSize = 12.5.sp, color = MSub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Text with a second modifier (keeps call sites short). */
@Composable
private fun Text(text: String, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 14.sp, fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip, textAlign: TextAlign? = null, modifier2: Modifier = Modifier,
    color: Color = Color.White, letterSpacing: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified) =
    androidx.compose.material3.Text(text, modifier.then(modifier2), color = color, fontSize = fontSize, fontWeight = fontWeight, maxLines = maxLines,
        overflow = overflow, textAlign = textAlign, letterSpacing = letterSpacing)

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLong: () -> Unit) = this.combinedClickable(onClick = onClick, onLongClick = onLong)

@Composable
private fun Loading() = Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp) }

@Composable
private fun TopBar(title: String?, onBack: (() -> Unit)?, nav: MusicNav, avatar: String? = null) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = if (onBack != null) 4.dp else 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
        if (title == null) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(Color(0xFFFF0000)), Alignment.Center) { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp), tint = Color.White) }
            Text("Music", Modifier.padding(start = 6.dp), fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
        } else Text(title, Modifier.padding(start = 4.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        IconButton({ nav.push(MPage.Search) }) { Icon(Icons.Default.Search, "Search", tint = Color.White) }
        if (avatar != null) Box(Modifier.padding(start = 4.dp).size(30.dp).clip(CircleShape).background(Color(0xFF7C4DFF)), Alignment.Center) {
            Text(avatar.take(1).uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ─────────────────────────── Home ───────────────────────────

@Composable
private fun HomeTab(profileName: String, nav: MusicNav, libVersion: Int) {
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    var chip by rememberSaveable { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<YtmPage?>(null) }
    var more by remember { mutableStateOf<List<YtmShelf>>(emptyList()) }
    var cont by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var chips by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(chip) {
        page = null; more = emptyList()
        val p = runCatching { YtMusic.home(params = chip) }.getOrNull() ?: YtmPage(emptyList())
        page = p; cont = p.continuation
        if (chip == null && p.chips.isNotEmpty()) chips = p.chips
    }
    val speed = remember(libVersion) { MusicLibraryStore.speedDial() }
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) > list.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, cont) {
        val c = cont ?: return@LaunchedEffect
        if (!nearEnd || loadingMore) return@LaunchedEffect
        loadingMore = true
        val p = runCatching { YtMusic.home(continuation = c) }.getOrNull()
        more = more + p?.shelves.orEmpty(); cont = p?.continuation?.takeIf { it != c }
        loadingMore = false
    }
    val curId = now.track?.id?.removePrefix("yt:")
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 16.dp)) {
        item { TopBar(null, null, nav, profileName) }
        if (chips.isNotEmpty()) item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(chips) { _, (label, params) ->
                    val on = chip == params
                    Text(label, Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Color.White else Color(0x1FFFFFFF))
                        .clickable { chip = if (on) null else params }.padding(horizontal = 13.dp, vertical = 8.dp),
                        fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = if (on) Color.Black else Color.White)
                }
            }
        }
        if (speed.isNotEmpty() && chip == null) item { SpeedDial(profileName, speed, nav) }
        val p = page
        if (p == null) item { Loading() }
        else {
            val shelves = p.shelves + more
            if (shelves.isEmpty()) item { Text("Music couldn't load. Check your connection and pull down to try again.", Modifier.padding(20.dp), color = MSub) }
            shelves.forEachIndexed { i, s -> item(key = "s$i${s.title}") { Shelf(s, nav, curId) } }
            item { ExploreTiles(nav) }
            if (loadingMore) item { Loading() }
        }
    }
}

/** Speed dial: your pinned and most played music, 3 × 3 per page. */
@Composable
private fun SpeedDial(profileName: String, items: List<YtmItem>, nav: MusicNav) {
    val pages = items.chunked(9)
    val pager = rememberPagerState { pages.size }
    Column(Modifier.padding(top = 10.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF7C4DFF)), Alignment.Center) { Text(profileName.take(1).uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            Column(Modifier.padding(start = 10.dp)) {
                Text(profileName.uppercase(), fontSize = 11.sp, color = MSub, letterSpacing = 0.8.sp)
                Text("Speed dial", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        HorizontalPager(pager, Modifier.padding(top = 12.dp), contentPadding = PaddingValues(horizontal = 16.dp), pageSpacing = 12.dp) { pi ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pages[pi].chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { it ->
                            Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(MCard).clickable { nav.open(it, pages[pi], null) }) {
                                if (it.thumb != null) AsyncImage(it.thumb, it.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xCC000000))))
                                Text(it.title, Modifier.align(Alignment.BottomStart).padding(6.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (pages.size > 1) Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i -> Box(Modifier.padding(3.dp).size(6.dp).clip(CircleShape).background(if (i == pager.currentPage) Color.White else Color(0x55FFFFFF))) }
        }
    }
}

@Composable
private fun ExploreTiles(nav: MusicNav) {
    SectionTitle("Explore")
    val tiles = listOf(Triple("New releases", Icons.Default.NewReleases, "new"), Triple("Charts", Icons.AutoMirrored.Filled.TrendingUp, "charts"),
        Triple("Moods & genres", Icons.Default.SentimentSatisfied, "moods"))
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, icon, which) ->
                    Column(Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF181818)).clickable { nav.push(MPage.Explore(which)) }.padding(14.dp),
                        verticalArrangement = Arrangement.SpaceBetween) {
                        Icon(icon, null, tint = Color.White)
                        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ─────────────────────────── Explore ───────────────────────────

@Composable
private fun ExploreTab(nav: MusicNav) {
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf<YtmPage?>(null) }
    LaunchedEffect(Unit) { page = runCatching { YtMusic.explore() }.getOrNull() ?: YtmPage(emptyList()) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { TopBar("Explore", null, nav) }
        item { ExploreTiles(nav) }
        val p = page
        if (p == null) item { Loading() } else p.shelves.forEachIndexed { i, s -> item(key = "e$i") { Shelf(s, nav, now.track?.id?.removePrefix("yt:")) } }
    }
}

@Composable
private fun ExplorePage(which: String, nav: MusicNav, onBack: () -> Unit) {
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    var page by remember(which) { mutableStateOf<YtmPage?>(null) }
    LaunchedEffect(which) {
        page = runCatching { when (which) { "charts" -> YtMusic.charts(); "moods" -> YtMusic.moods(); else -> YtMusic.newReleases() } }.getOrNull() ?: YtmPage(emptyList())
    }
    val title = when (which) { "charts" -> "Charts"; "moods" -> "Moods & genres"; else -> "New releases" }
    LazyColumn(Modifier.fillMaxSize()) {
        item { TopBar(null, onBack, nav) }
        item { Text(title, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 32.sp, fontWeight = FontWeight.Bold) }
        val p = page
        when {
            p == null -> item { Loading() }
            which == "moods" -> {
                // Mood buttons with a coloured edge, two per row.
                val colors = listOf(0xFFE53935, 0xFF8E24AA, 0xFF1E88E5, 0xFF43A047, 0xFFFB8C00, 0xFFFDD835, 0xFF00ACC1, 0xFFD81B60)
                val all = p.items
                items(all.chunked(2).size) { r ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        all.chunked(2)[r].forEachIndexed { k, it ->
                            Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1C1C1C)).clickable { nav.open(it, all, null) }) {
                                Box(Modifier.width(6.dp).fillMaxHeight().background(Color(colors[(r * 2 + k) % colors.size])))
                                Text(it.title, Modifier.align(Alignment.CenterVertically).padding(start = 12.dp), fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            }
                        }
                        if (all.chunked(2)[r].size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            else -> p.shelves.forEachIndexed { i, s -> item(key = "x$i") { Shelf(s, nav, now.track?.id?.removePrefix("yt:")) } }
        }
    }
}

// ─────────────────────────── Album / playlist / artist ───────────────────────────

@Composable
private fun BrowsePage(p: MPage.Browse, nav: MusicNav, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    var page by remember(p.id) { mutableStateOf<YtmPage?>(null) }
    LaunchedEffect(p.id) { page = runCatching { if (p.params != null && p.id.startsWith("FEmusic_moods")) YtMusic.browseWith(p.id, p.params) else YtMusic.browse(p.id) }.getOrNull() ?: YtmPage(emptyList()) }
    val artist = p.id.startsWith("UC")
    val pg = page
    val head = pg?.header
    val songs = pg?.items.orEmpty().filter { it.kind == YtmKind.SONG || it.kind == YtmKind.VIDEO }
    val title = head?.title?.ifBlank { null } ?: p.title
    val art = head?.thumb ?: p.item?.thumb
    LazyColumn(Modifier.fillMaxSize()) {
        if (artist) item {
            Box(Modifier.fillMaxWidth().height(330.dp)) {
                if (art != null) AsyncImage(art, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color(0x33000000), 1f to MBg)))
                Box(Modifier.statusBarsPadding()) { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) } }
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(title, fontSize = 38.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    if (!head?.subtitle.isNullOrBlank()) Text(head!!.subtitle, color = MSub, fontSize = 13.sp, maxLines = 1)
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Subscribe", Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable {
                            p.item?.let { a -> kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch { MusicLibraryStore.toggleSubscription(ctx, a); Snack.show("Saved to your artists") } }
                        }.padding(horizontal = 16.dp, vertical = 8.dp), color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        IconButton({ songs.firstOrNull()?.let { MusicPlayer.startRadio(ctx, it) } }) { Icon(Icons.Default.Radio, "Radio", tint = Color.White) }
                        Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White).clickable { MusicPlayer.playList(ctx, songs, 0, title) }, Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, "Play", Modifier.size(30.dp), tint = Color.Black)
                        }
                    }
                }
            }
        } else {
            item { TopBar(null, onBack, nav) }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Art(art, 240.dp, corner = 8.dp)
                    Text(title, Modifier.padding(top = 16.dp), fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
                    Text(head?.subtitle?.ifBlank { null } ?: p.item?.subtitle.orEmpty(), Modifier.padding(top = 4.dp), color = MSub, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 2)
                    Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        IconButton({ p.item?.let { i -> kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch { MusicLibraryStore.toggleLiked(ctx, i); Snack.show("Saved to library") } } }) { Icon(Icons.Default.BookmarkBorder, "Save", tint = Color.White) }
                        Box(Modifier.size(64.dp).clip(CircleShape).background(Color.White).clickable { MusicPlayer.playList(ctx, songs, 0, title) }, Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, "Play", Modifier.size(36.dp), tint = Color.Black)
                        }
                        IconButton({ MusicPlayer.playList(ctx, songs.shuffled(), 0, title) }) { Icon(Icons.Default.Shuffle, "Shuffle", tint = Color.White) }
                    }
                }
            }
        }
        when {
            pg == null -> item { Loading() }
            artist -> pg.shelves.forEachIndexed { i, s -> item(key = "a$i") { Shelf(s, nav, now.track?.id?.removePrefix("yt:")) } }
            else -> {
                itemsIndexed(songs, key = { i, s -> "t$i${s.id}" }) { i, s ->
                    SongRow(s, now.track?.id == "yt:" + s.id, { nav.menu(s) }, number = i + 1) { nav.open(s, songs, title) }
                }
                if (songs.isEmpty()) item { Text("No songs here.", Modifier.padding(20.dp), color = MSub) }
                pg.shelves.forEachIndexed { i, s -> item(key = "m$i") { Shelf(s, nav, now.track?.id?.removePrefix("yt:")) } }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun LocalPage(p: MPage.Local, nav: MusicNav, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        item { TopBar(p.title, onBack, nav) }
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Play all", Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable { MusicPlayer.playList(ctx, p.items, 0, p.title) }
                    .padding(horizontal = 18.dp, vertical = 9.dp), color = Color.Black, fontWeight = FontWeight.SemiBold)
                Text("Shuffle", Modifier.clip(RoundedCornerShape(50)).background(Color(0x22FFFFFF)).clickable { MusicPlayer.playList(ctx, p.items.shuffled(), 0, p.title) }
                    .padding(horizontal = 18.dp, vertical = 9.dp), fontWeight = FontWeight.SemiBold)
            }
        }
        if (p.items.isEmpty()) item { Text("Nothing here yet.", Modifier.padding(20.dp), color = MSub) }
        itemsIndexed(p.items, key = { i, s -> "l$i${s.id}" }) { _, s -> SongRow(s, now.track?.id == "yt:" + s.id, { nav.menu(s) }) { nav.open(s, p.items, p.title) } }
    }
}

// ─────────────────────────── Search ───────────────────────────

@Composable
private fun SearchPage(nav: MusicNav, libVersion: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var results by remember { mutableStateOf<YtmPage?>(null) }
    val recent = remember(libVersion) { MusicLibraryStore.active().recentSearches }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }
    LaunchedEffect(q) {
        if (q.isBlank() || q == submitted) { suggestions = emptyList(); return@LaunchedEffect }
        delay(220); suggestions = runCatching { YtMusic.searchSuggestions(q) }.getOrDefault(emptyList())
    }
    LaunchedEffect(submitted, filter) {
        val s = submitted ?: return@LaunchedEffect
        results = null
        results = runCatching { YtMusic.search(s, filter) }.getOrNull() ?: YtmPage(emptyList())
    }
    fun go(text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        q = t; submitted = t; keyboard?.hide()
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch { MusicLibraryStore.addRecentSearch(ctx, t) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            BasicTextField(q, { q = it; if (it != submitted) submitted = null }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 17.sp), cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { go(q) }),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF212121)).padding(horizontal = 16.dp, vertical = 11.dp)) {
                        if (q.isEmpty()) Text("Search songs, artists, podcasts", color = MSub, fontSize = 16.sp)
                        inner()
                    }
                })
            if (q.isNotEmpty()) IconButton({ q = ""; submitted = null; results = null }) { Icon(Icons.Default.Close, "Clear", tint = Color.White) }
        }
        val res = results
        when {
            submitted != null -> {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(listOf("all" to "All", "songs" to "Songs", "videos" to "Videos", "albums" to "Albums", "artists" to "Artists", "playlists" to "Playlists")) { _, (k, label) ->
                        val on = filter == k
                        Text(label, Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Color.White else Color(0x1FFFFFFF)).clickable { filter = k }
                            .padding(horizontal = 13.dp, vertical = 7.dp), color = if (on) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (res == null) Loading()
                else LazyColumn(Modifier.fillMaxSize()) {
                    if (res.items.isEmpty()) item { Text("No results for “$submitted”", Modifier.padding(20.dp), color = MSub) }
                    itemsIndexed(res.items, key = { i, s -> "r$i${s.id}" }) { _, s ->
                        SongRow(s, now.track?.id == "yt:" + s.id, if (s.kind == YtmKind.SONG || s.kind == YtmKind.VIDEO) ({ nav.menu(s) }) else null) { nav.open(s, res.items, null) }
                    }
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(if (q.isBlank()) recent else suggestions) { _, s ->
                    Row(Modifier.fillMaxWidth().clickable { go(s) }.padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (q.isBlank()) Icons.Default.History else Icons.Default.Search, null, tint = MSub)
                        Text(s, Modifier.weight(1f).padding(start = 18.dp), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Default.NorthWest, "Use", Modifier.size(20.dp).clickable { q = s }, tint = MSub)
                    }
                }
            }
        }
    }
}


// ─────────────────────────── Library ───────────────────────────

@Composable
private fun LibraryTab(nav: MusicNav, libVersion: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val data = remember(libVersion) { MusicLibraryStore.active() }
    var chip by rememberSaveable { mutableStateOf("Playlists") }
    var newName by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
            item {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Library", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    IconButton({ nav.push(MPage.Local("History", data.history.map { it.item })) }) { Icon(Icons.Default.History, "History", tint = Color.White) }
                    IconButton({ nav.push(MPage.Search) }) { Icon(Icons.Default.Search, "Search", tint = Color.White) }
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(listOf("Playlists", "Songs", "Albums", "Artists")) { _, c ->
                        val on = chip == c
                        Text(c, Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Color.White else Color(0x1FFFFFFF)).clickable { chip = c }
                            .padding(horizontal = 13.dp, vertical = 7.dp), color = if (on) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            item { Text("Recent activity", Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp), color = MSub, fontSize = 13.sp) }
            when (chip) {
                "Playlists" -> {
                    item { LibRow("Liked music", "Auto playlist · ${data.liked.size} songs", null, Icons.Default.ThumbUp) { nav.push(MPage.Local("Liked music", data.liked)) } }
                    itemsIndexed(data.playlists, key = { _, p -> p.id }) { _, pl ->
                        LibRow(pl.name, "Playlist · ${pl.items.size} songs", pl.items.firstOrNull()?.thumb, Icons.AutoMirrored.Filled.QueueMusic) { nav.push(MPage.Local(pl.name, pl.items)) }
                    }
                }
                "Songs" -> itemsIndexed((data.liked + data.history.map { it.item }).filter { it.kind == YtmKind.SONG || it.kind == YtmKind.VIDEO }.distinctBy { it.id }) { _, s ->
                    SongRow(s, false, { nav.menu(s) }) { nav.open(s, emptyList(), null) }
                }
                "Albums" -> itemsIndexed((data.liked + data.pinned).filter { it.kind == YtmKind.ALBUM || it.kind == YtmKind.SINGLE || it.kind == YtmKind.PLAYLIST }.distinctBy { it.id }) { _, s ->
                    SongRow(s, false, null) { nav.open(s, emptyList(), null) }
                }
                else -> itemsIndexed(data.subscriptions) { _, s -> SongRow(s, false, null) { nav.open(s, emptyList(), null) } }
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(18.dp).clip(RoundedCornerShape(50)).background(Color.White).clickable { newName = "" }
            .padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, null, tint = Color.Black); Text("New", Modifier.padding(start = 4.dp), color = Color.Black, fontWeight = FontWeight.SemiBold)
        }
        newName?.let { n ->
            NameDialog("New playlist", n, onDone = { name ->
                newName = null
                if (name != null) kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch { MusicLibraryStore.createPlaylist(ctx, name); onChanged() }
            })
        }
    }
}

@Composable
private fun LibRow(title: String, sub: String, art: String?, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(Brush.linearGradient(listOf(Color(0xFF7C4DFF), Color(0xFFE040FB)))), Alignment.Center) {
            if (art != null) AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Icon(icon, null, tint = Color.White)
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(sub, fontSize = 13.sp, color = MSub)
        }
    }
}

@Composable
private fun NameDialog(title: String, start: String, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(start) }
    androidx.compose.ui.window.Dialog({ onDone(null) }) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF212121)).padding(20.dp)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            BasicTextField(name, { name = it }, Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF303030)).padding(12.dp),
                singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(Color.White))
            Row(Modifier.align(Alignment.End).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Cancel", Modifier.clickable { onDone(null) }.padding(8.dp), color = MSub)
                Text("Create", Modifier.clickable { onDone(name.trim().ifBlank { "New playlist" }) }.padding(8.dp), fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─────────────────────────── Samples ───────────────────────────

/** Samples: music videos, one per screen, swiped up and down. "Play" plays the full song. */
@Composable
private fun SamplesTab(nav: MusicNav, onPlayed: () -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<YtmItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        val home = runCatching { YtMusic.home() }.getOrNull()
        val ex = runCatching { YtMusic.explore() }.getOrNull()
        items = (home?.shelves.orEmpty() + ex?.shelves.orEmpty()).flatMap { it.items }.filter { it.kind == YtmKind.VIDEO || it.kind == YtmKind.SONG }
            .distinctBy { it.id }.sortedByDescending { it.kind == YtmKind.VIDEO }.take(60)
    }
    if (items.isEmpty()) { Loading(); return }
    val pager = rememberPagerState { items.size }
    VerticalPager(pager, Modifier.fillMaxSize()) { i ->
        val it = items[i]
        val zoom = remember { androidx.compose.animation.core.Animatable(1f) }
        LaunchedEffect(pager.currentPage == i) { if (pager.currentPage == i) zoom.animateTo(1.15f, tween(9000)) else zoom.snapTo(1f) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(it.thumb, it.title, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value }, contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x66000000), 0.5f to Color.Transparent, 1f to Color(0xCC000000))))
            Text("Samples", Modifier.statusBarsPadding().padding(16.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Column(Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 90.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SideAction(Icons.Default.ThumbUp, "Like") { kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch { MusicLibraryStore.toggleLiked(ctx, it); Snack.show("Saved to Liked music") } }
                SideAction(Icons.AutoMirrored.Filled.PlaylistAdd, "Save") { nav.menu(it) }
                SideAction(Icons.Default.Share, "Share") { shareSong(ctx, it.id, it.title) }
                SideAction(Icons.Default.PlayArrow, "Play") { nav.open(it, items, null); onPlayed() }
            }
            Row(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 80.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(it.thumb, 44.dp)
                Column(Modifier.padding(start = 10.dp)) {
                    Text(it.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(it.subtitle, fontSize = 12.sp, color = Color(0xFFDDDDDD), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SideAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0x33FFFFFF)), Alignment.Center) { Icon(icon, label, tint = Color.White) }
        Text(label, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

private fun shareSong(ctx: Context, id: String, title: String) = runCatching {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "$title https://music.youtube.com/watch?v=$id"), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ─────────────────────────── Mini player and bar ───────────────────────────

@Composable
private fun MiniPlayer(onOpen: () -> Unit) {
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    val t = now.track ?: return
    Column(Modifier.fillMaxWidth().background(Color(0xFF212121)).pointerInput(t.id) {
        var total = 0f
        detectVerticalDragGestures(onDragEnd = { if (total < -30f) onOpen() else if (total > 30f) MusicPlayer.stop(); total = 0f }) { _, dy -> total += dy }
    }.clickable(onClick = onOpen)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(t.artworkURL, 42.dp, corner = 4.dp)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(t.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist, fontSize = 12.sp, color = MSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (now.buffering) CircularProgressIndicator(Modifier.size(22.dp).padding(2.dp), color = Color.White, strokeWidth = 2.dp)
            else IconButton({ MusicPlayer.toggle() }) { Icon(if (now.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", tint = Color.White) }
            IconButton({ MusicPlayer.next() }) { Icon(Icons.Default.SkipNext, "Next", tint = Color.White) }
        }
        val f = if (now.durMs > 0) (now.posMs.toFloat() / now.durMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0x22FFFFFF))) { Box(Modifier.fillMaxWidth(f).height(2.dp).background(Color.White)) }
    }
}

@Composable
private fun MusicBottomBar(tab: String, onTab: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF0F0F0F)).navigationBarsPadding().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(Triple("home", "Home", Icons.Default.Home), Triple("samples", "Samples", Icons.Default.Slideshow),
            Triple("explore", "Explore", Icons.Default.Explore), Triple("library", "Library", Icons.Default.LibraryMusic)).forEach { (k, label, icon) ->
            val on = tab == k
            Column(Modifier.weight(1f).clickable { onTab(k) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, label, tint = if (on) Color.White else MSub)
                Text(label, fontSize = 11.sp, color = if (on) Color.White else MSub, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

// ─────────────────────────── Now playing ───────────────────────────

/**
 * The song's colour, like YouTube Music: the most vivid colour in the cover, darkened so white text stays readable.
 */
private suspend fun coverColor(ctx: Context, url: String?): Color = withContext(Dispatchers.IO) {
    if (url == null) return@withContext Color(0xFF2A2A2A)
    runCatching {
        val req = coil.request.ImageRequest.Builder(ctx).data(url).size(48).allowHardware(false).build()
        val bmp = (coil.Coil.imageLoader(ctx).execute(req).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@runCatching null
        val buckets = DoubleArray(24); val sums = Array(24) { DoubleArray(3) }
        val hsv = FloatArray(3)
        for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) {
            val p = bmp.getPixel(x, y)
            android.graphics.Color.colorToHSV(p, hsv)
            if (hsv[2] < 0.12f || (hsv[1] < 0.15f && hsv[2] > 0.9f)) continue
            val w = (hsv[1] * hsv[2]).toDouble() + 0.02
            val b = (hsv[0] / 15f).toInt().coerceIn(0, 23)
            buckets[b] += w
            sums[b][0] += android.graphics.Color.red(p) * w; sums[b][1] += android.graphics.Color.green(p) * w; sums[b][2] += android.graphics.Color.blue(p) * w
        }
        val best = buckets.indices.maxByOrNull { buckets[it] } ?: return@runCatching null
        if (buckets[best] <= 0) return@runCatching null
        val c = android.graphics.Color.rgb((sums[best][0] / buckets[best]).toInt(), (sums[best][1] / buckets[best]).toInt(), (sums[best][2] / buckets[best]).toInt())
        android.graphics.Color.colorToHSV(c, hsv)
        hsv[1] = hsv[1].coerceAtMost(0.75f); hsv[2] = 0.42f
        Color(android.graphics.Color.HSVToColor(hsv))
    }.getOrNull() ?: Color(0xFF2A2A2A)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlaying(nav: MusicNav, onClose: () -> Unit, onFull: () -> Unit, onArtist: (String) -> Unit) {
    val ctx = LocalContext.current
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    val videoMode by MusicPlayer.videoMode.collectAsStateWithLifecycle()
    val t = now.track
    val vid = t?.id?.takeIf { it.startsWith("yt:") }?.removePrefix("yt:")
    val isVideo = t?.format == "ytv"
    val counterpart = vid?.let { MusicPlayer.counterparts[it] }
    val videoAvailable = isVideo || counterpart != null
    val showVideo = videoMode && isVideo
    var sheet by remember { mutableStateOf<Int?>(null) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var liked by remember(vid) { mutableStateOf(MusicLibraryStore.active().liked.any { it.id == vid }) }
    val base by produceState(Color(0xFF2A2A2A), t?.artworkURL) { value = coverColor(ctx, t?.artworkURL) }
    val color by animateColorAsState(base, tween(700), label = "cover")
    val wifi = remember { runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false) }
    // The picture only downloads while it is on screen; Song = sound only.
    LaunchedEffect(showVideo) { MusicPlayer.setVideo(showVideo, if (wifi) 720 else 480) }
    DisposableEffect(Unit) { onDispose { MusicPlayer.setVideo(false) } }
    // Turning the phone sideways while the video shows = full screen.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(landscape, showVideo) { if (landscape && showVideo) onFull() }
    val item = t?.let { YtmItem(if (isVideo) YtmKind.VIDEO else YtmKind.SONG, vid ?: it.id, it.title, it.artist, it.artworkURL) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.55f).compositeOverBlack(), MBg)))
        .pointerInput(Unit) { var total = 0f; detectVerticalDragGestures(onDragEnd = { if (total > 120f) onClose() else if (total < -120f && showVideo) onFull(); total = 0f }) { _, dy -> total += dy } }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close", Modifier.size(30.dp), tint = Color.White) }
                Spacer(Modifier.weight(1f))
                if (vid != null) Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0x33000000)).padding(3.dp)) {
                    Seg("Song", !showVideo) { MusicPlayer.videoMode.value = false }
                    Seg("Video", showVideo, enabled = videoAvailable) {
                        if (isVideo) MusicPlayer.videoMode.value = true
                        else if (counterpart != null) MusicPlayer.swapVersion(ctx, counterpart, asVideo = true)
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton({ item?.let(nav.menu) }) { Icon(Icons.Default.MoreVert, "More", tint = Color.White) }
            }
            Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
                if (showVideo) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black), Alignment.Center) {
                        VideoSurface(Modifier.fillMaxSize())
                        if (MusicPlayer.videoSize().width == 0) CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
                        IconButton(onFull, Modifier.align(Alignment.BottomEnd)) { Icon(Icons.Default.Fullscreen, "Full screen", tint = Color.White) }
                    }
                } else {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(MCard)) {
                        if (t?.artworkURL != null) AsyncImage(t.artworkURL, t.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
            Text(t?.title ?: "Nothing playing", Modifier.padding(top = 18.dp).basicMarquee(), fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(t?.artist.orEmpty(), Modifier.clickable { t?.artist?.let(onArtist) }, fontSize = 15.sp, color = Color(0xFFDDDDDD), maxLines = 1, overflow = TextOverflow.Ellipsis)
            LazyRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Pill(if (liked) Icons.Default.ThumbUp else Icons.Default.ThumbUpOffAlt, if (liked) "Liked" else "Like") {
                    item?.let { i -> kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch { liked = MusicLibraryStore.toggleLiked(ctx, i) } }
                } }
                item { Pill(Icons.Default.ThumbDownOffAlt, null) { MusicPlayer.next(); Snack.show("You won't hear this one again for a while") } }
                item { Pill(Icons.AutoMirrored.Filled.PlaylistAdd, "Save") { item?.let(nav.menu) } }
                item { Pill(Icons.Default.Share, "Share") { item?.let { shareSong(ctx, it.id, it.title) } } }
                item { Pill(Icons.Default.Radio, "Mix") { item?.let { MusicPlayer.startRadio(ctx, it) } } }
            }
            val dur = now.durMs.coerceAtLeast(1)
            Slider(drag ?: (now.posMs.toFloat() / dur).coerceIn(0f, 1f), { drag = it }, Modifier.padding(top = 8.dp),
                onValueChangeFinished = { drag?.let { MusicPlayer.seekTo((it * dur).toLong()) }; drag = null },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0x44FFFFFF)))
            Row(Modifier.fillMaxWidth()) {
                Text(clock(drag?.let { (it * dur).toLong() } ?: now.posMs), fontSize = 12.sp, color = Color(0xFFCCCCCC))
                Spacer(Modifier.weight(1f)); Text(clock(now.durMs), fontSize = 12.sp, color = Color(0xFFCCCCCC))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton({ MusicPlayer.shuffle() }) { Icon(Icons.Default.Shuffle, "Shuffle", tint = if (now.shuffle) Color.White else Color(0x99FFFFFF)) }
                IconButton({ MusicPlayer.previous() }, Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(38.dp), tint = Color.White) }
                Box(Modifier.size(74.dp).clip(CircleShape).background(Color.White).clickable { MusicPlayer.toggle() }, Alignment.Center) {
                    if (now.buffering) CircularProgressIndicator(Modifier.size(30.dp), color = Color.Black, strokeWidth = 3.dp)
                    else Icon(if (now.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", Modifier.size(42.dp), tint = Color.Black)
                }
                IconButton({ MusicPlayer.next() }, Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext, "Next", Modifier.size(38.dp), tint = Color.White) }
                IconButton({ MusicPlayer.cycleRepeat() }) {
                    Icon(if (now.repeat == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat",
                        tint = if (now.repeat == androidx.media3.common.Player.REPEAT_MODE_OFF) Color(0x99FFFFFF) else Color.White)
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                listOf("UP NEXT", "LYRICS", "RELATED").forEachIndexed { i, l ->
                    Text(l, Modifier.weight(1f).clickable { sheet = i }.padding(vertical = 12.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, color = Color(0xFFDDDDDD), letterSpacing = 0.8.sp)
                }
            }
        }
        AnimatedVisibility(sheet != null, enter = slideInVertically(tween(300)) { it }, exit = slideOutVertically(tween(260)) { it }) {
            NowSheet(sheet ?: 0, vid, t, color, nav, onTab = { sheet = it }, onClose = { sheet = null })
        }
    }
}

private fun Color.compositeOverBlack(): Color = Color(red * alpha, green * alpha, blue * alpha, 1f)

@Composable
private fun Seg(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Text(label, Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Color.Transparent)
        .clickable { if (enabled) onClick() else android.widget.Toast.makeText(ctx, "This song has no video", android.widget.Toast.LENGTH_SHORT).show() }
        .padding(horizontal = 18.dp, vertical = 7.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        color = when { on -> Color.Black; enabled -> Color.White; else -> Color(0x66FFFFFF) })
}

@Composable
private fun Pill(icon: ImageVector, label: String?, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0x26FFFFFF)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, label, Modifier.size(18.dp), tint = Color.White)
        if (label != null) Text(label, Modifier.padding(start = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/** The player's picture (a TextureView fed by the music service's player). */
@Composable
private fun VideoSurface(modifier: Modifier) {
    AndroidView(factory = { c -> TextureView(c).also { MusicPlayer.attachVideo(it) } }, modifier = modifier,
        onRelease = { MusicPlayer.attachVideo(null) })
}

/** Up next · Lyrics · Related, over the player. */
@Composable
private fun NowSheet(tab: Int, vid: String?, t: LiveServer.Track?, color: Color, nav: MusicNav, onTab: (Int) -> Unit, onClose: () -> Unit) {
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(color.copy(alpha = 0.6f).compositeOverBlack(), Color(0xFF0E0E0E))))
        .statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClose).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(t?.artworkURL, 42.dp, corner = 4.dp)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(t?.title.orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t?.artist.orEmpty(), fontSize = 12.sp, color = MSub, maxLines = 1)
            }
            IconButton({ MusicPlayer.toggle() }) { Icon(if (now.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", tint = Color.White) }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("UP NEXT", "LYRICS", "RELATED").forEachIndexed { i, l ->
                Column(Modifier.weight(1f).clickable { onTab(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(l, Modifier.padding(vertical = 12.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (i == tab) Color.White else MSub, letterSpacing = 0.8.sp)
                    Box(Modifier.fillMaxWidth(0.7f).height(2.dp).background(if (i == tab) Color.White else Color.Transparent))
                }
            }
        }
        when (tab) {
            0 -> UpNext(nav)
            1 -> Lyrics(vid, t)
            else -> Related(vid, nav)
        }
    }
}

@Composable
private fun UpNext(nav: MusicNav) {
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    val queue by MusicPlayer.queue.collectAsStateWithLifecycle()
    val label by MusicPlayer.radioLabel.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { list.scrollToItem((now.index).coerceAtLeast(0)) }
    LazyColumn(Modifier.fillMaxSize(), state = list) {
        if (label.isNotBlank()) item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("Playing from", fontSize = 12.sp, color = MSub)
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        itemsIndexed(queue, key = { i, q -> "q$i${q.id}" }) { i, q ->
            val cur = i == now.index
            Row(Modifier.fillMaxWidth().background(if (cur) Color(0x22FFFFFF) else Color.Transparent)
                .combinedClickableCompat({ MusicPlayer.jumpTo(i) }, { MusicPlayer.removeAt(i) }).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box { Art(q.artworkURL, 48.dp, corner = 4.dp); if (cur && now.playing) Box(Modifier.size(48.dp).background(Color(0x88000000)), Alignment.Center) { Icon(Icons.Default.GraphicEq, null, tint = Color.White) } }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(q.title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(q.artist + if (q.durationMs > 0) " · " + clock(q.durationMs) else "", fontSize = 12.5.sp, color = MSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.DragHandle, "Hold to remove", tint = MSub)
            }
        }
    }
}

/** Lyrics: time-synced from LRCLIB (the current line lights up), else YouTube Music's plain lyrics. */
@Composable
private fun Lyrics(vid: String?, t: LiveServer.Track?) {
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    var lines by remember(vid) { mutableStateOf<List<Pair<Long, String>>?>(null) }
    var plain by remember(vid) { mutableStateOf<String?>(null) }
    LaunchedEffect(vid) {
        if (t == null) { lines = emptyList(); return@LaunchedEffect }
        val synced = withContext(Dispatchers.IO) { runCatching { lrclib(t) }.getOrNull() }
        if (!synced.isNullOrEmpty()) { lines = synced; return@LaunchedEffect }
        lines = emptyList()
        if (vid != null) plain = runCatching { YtMusic.next(vid).lyricsBrowseId?.let { YtMusic.lyrics(it) } }.getOrNull()
    }
    val l = lines
    when {
        l == null -> Loading()
        l.isNotEmpty() -> {
            val cur = l.indexOfLast { it.first <= now.posMs }.coerceAtLeast(0)
            val list = rememberLazyListState()
            LaunchedEffect(cur) { list.animateScrollToItem((cur - 2).coerceAtLeast(0)) }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), state = list, contentPadding = PaddingValues(vertical = 20.dp)) {
                itemsIndexed(l) { i, (at, text) ->
                    Text(text.ifBlank { "♪" }, Modifier.fillMaxWidth().clickable { MusicPlayer.seekTo(at) }.padding(vertical = 9.dp), fontSize = 24.sp,
                        fontWeight = FontWeight.Bold, color = if (i == cur) Color.White else Color(0x66FFFFFF))
                }
            }
        }
        !plain.isNullOrBlank() -> LazyColumn(Modifier.fillMaxSize().padding(20.dp)) { item { Text(plain!!, fontSize = 18.sp, color = Color(0xFFDDDDDD)) } }
        else -> Text("Lyrics aren't available for this song.", Modifier.padding(24.dp), color = MSub)
    }
}

private fun lrclib(t: LiveServer.Track): List<Pair<Long, String>>? {
    val name = t.title.replace(Regex("\\s*[(\\[].*?[)\\]]"), "").trim()
    val artist = t.artist.substringBefore(",").substringBefore("&").substringBefore(" • ").trim()
    val url = "https://lrclib.net/api/search?track_name=" + java.net.URLEncoder.encode(name, "UTF-8") + "&artist_name=" + java.net.URLEncoder.encode(artist, "UTF-8")
    val body = Api.http.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "Xstream Music").build()).execute().use { it.body?.string() } ?: return null
    val arr = Api.json.parseToJsonElement(body) as? JsonArray ?: return null
    val synced = arr.mapNotNull { (it as? JsonObject)?.get("syncedLyrics") as? JsonPrimitive }.firstNotNullOfOrNull { it.contentOrNull?.takeIf { s -> s.isNotBlank() } } ?: return null
    val re = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]\\s*(.*)")
    return synced.lines().mapNotNull { line ->
        re.matchEntire(line.trim())?.let { m ->
            val ms = m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + (m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0)
            ms to m.groupValues[4]
        }
    }
}

@Composable
private fun Related(vid: String?, nav: MusicNav) {
    var page by remember(vid) { mutableStateOf<YtmPage?>(null) }
    LaunchedEffect(vid) {
        page = runCatching { vid?.let { YtMusic.next(it).relatedBrowseId }?.let { YtMusic.related(it) } }.getOrNull() ?: YtmPage(emptyList())
    }
    val p = page
    if (p == null) { Loading(); return }
    LazyColumn(Modifier.fillMaxSize()) {
        if (p.shelves.isEmpty()) item { Text("Nothing related found.", Modifier.padding(20.dp), color = MSub) }
        p.shelves.forEachIndexed { i, s -> item(key = "rel$i") { Shelf(s, nav, vid) } }
    }
}

// ─────────────────────────── ⋮ menu ───────────────────────────

@Composable
private fun ItemMenu(item: YtmItem, nav: MusicNav, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var pick by remember { mutableStateOf(false) }
    var sleep by remember { mutableStateOf(false) }
    fun io(block: suspend () -> Unit) = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch { block(); onDone() }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onDone)) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Color(0xFF212121))
            .clickable(enabled = false) {}.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(item.thumb, 48.dp, round = item.kind == YtmKind.ARTIST)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.subtitle, fontSize = 12.5.sp, color = MSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onDone) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
            }
            when {
                pick -> {
                    val lists = MusicLibraryStore.active().playlists
                    MenuRow(Icons.Default.ThumbUp, "Liked music") { io { MusicLibraryStore.toggleLiked(ctx, item); Snack.show("Saved to Liked music") } }
                    lists.forEach { pl -> MenuRow(Icons.AutoMirrored.Filled.QueueMusic, pl.name) { io { MusicLibraryStore.addToPlaylist(ctx, pl.id, item); Snack.show("Saved to ${pl.name}") } } }
                    MenuRow(Icons.Default.Add, "New playlist") { io { val p = MusicLibraryStore.createPlaylist(ctx, item.title + " mix"); MusicLibraryStore.addToPlaylist(ctx, p.id, item); Snack.show("Saved to ${p.name}") } }
                }
                sleep -> listOf(5, 10, 15, 30, 45, 60).forEach { m ->
                    MenuRow(Icons.Default.Bedtime, "$m minutes") { MusicPlayer.sleepIn(m * 60_000L); Snack.show("Music stops in $m minutes"); onDone() }
                }
                else -> {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BigAction(Icons.AutoMirrored.Filled.PlaylistAdd, "Play next", Modifier.weight(1f)) { MusicPlayer.addNext(ctx, item); Snack.show("Plays next"); onDone() }
                        BigAction(Icons.Default.BookmarkBorder, "Save to playlist", Modifier.weight(1f)) { pick = true }
                        BigAction(Icons.Default.Share, "Share", Modifier.weight(1f)) { shareSong(ctx, item.id, item.title); onDone() }
                    }
                    MenuRow(Icons.Default.Radio, "Start mix") { MusicPlayer.startRadio(ctx, item); onDone() }
                    MenuRow(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue") { MusicPlayer.addToQueue(ctx, item); Snack.show("Added to queue"); onDone() }
                    MenuRow(Icons.Default.LibraryAdd, "Save to library") { io { MusicLibraryStore.toggleLiked(ctx, item); Snack.show("Saved to library") } }
                    MenuRow(Icons.Default.Person, "Go to artist") {
                        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                            val name = item.subtitle.substringAfter("Song • ").substringAfter("Video • ").substringBefore(" • ").substringBefore(",")
                            runCatching { YtMusic.search(name, "artists").items.firstOrNull { it.kind == YtmKind.ARTIST } }.getOrNull()?.let { nav.push(MPage.Browse(it.id, it.title, it)) }
                            onDone()
                        }
                    }
                    MenuRow(Icons.Default.PushPin, "Pin to speed dial") { io { MusicLibraryStore.togglePinned(ctx, item); Snack.show("Pinned to Speed dial") } }
                    MenuRow(Icons.Default.ClearAll, "Dismiss queue") { MusicPlayer.dismissQueue(); onDone() }
                    MenuRow(Icons.Default.Bedtime, "Sleep timer") { sleep = true }
                }
            }
        }
    }
}

@Composable
private fun BigAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF2E2E2E)).clickable(onClick = onClick).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = Color.White)
        Text(label, Modifier.padding(top = 6.dp), fontSize = 12.sp)
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White)
        Text(label, Modifier.padding(start = 20.dp), fontSize = 15.sp)
    }
}

// ─────────────────────────── Full-screen video ───────────────────────────

/**
 * Full screen (landscape), Xstream player style: pinch to zoom, slide the left side for brightness and the right side
 * for volume, double-tap to skip 10 s, quality, lock, and Up next on the side. Same player: nothing reloads.
 */
@Composable
private fun FullVideo(onExit: () -> Unit) {
    val ctx = LocalContext.current
    val act = ctx.musicActivity()
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var fill by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var quality by remember { mutableIntStateOf(Int.MAX_VALUE) }
    var qualityMenu by remember { mutableStateOf(false) }
    var side by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        act?.window?.let { w -> WindowCompat.getInsetsController(w, w.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE } }
        onDispose {
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            act?.window?.let { w -> WindowCompat.getInsetsController(w, w.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
    LaunchedEffect(quality) { MusicPlayer.setVideo(true, quality) }
    LaunchedEffect(controls, now.playing) { if (controls && now.playing) { delay(3500); controls = false } }
    LaunchedEffect(hint) { if (hint != null) { delay(900); hint = null } }
    BackHandler { onExit() }
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val transform = rememberTransformableState { z, _, _ -> if (!locked) { zoom = (zoom * z).coerceIn(1f, 3f); fill = zoom > 1.05f } }
    val vs = MusicPlayer.videoSize()
    val ar = if (vs.width > 0 && vs.height > 0) vs.width * vs.pixelWidthHeightRatio / vs.height else 16f / 9f
    Box(Modifier.fillMaxSize().background(Color.Black)
        .transformable(transform, canPan = { false })
        .pointerInput(locked) {
            detectTapGestures(onTap = { controls = !controls }, onDoubleTap = { o ->
                if (locked) return@detectTapGestures
                val fwd = o.x > size.width / 2f
                MusicPlayer.seekTo((MusicPlayer.now.value.posMs + if (fwd) 10_000 else -10_000).coerceAtLeast(0)); hint = if (fwd) "+10 s" else "−10 s"
            })
        }
        .pointerInput(locked) {
            var left = true
            detectVerticalDragGestures(onDragStart = { left = it.x < size.width / 2f }) { ch, dy ->
                if (locked) return@detectVerticalDragGestures
                ch.consume()
                if (left) act?.window?.let { w ->
                    val lp = w.attributes
                    val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                    lp.screenBrightness = (cur - dy / size.height).coerceIn(0.02f, 1f); w.attributes = lp
                    hint = "Brightness ${(lp.screenBrightness * 100).toInt()}%"
                } else {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val v = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) - dy / size.height * max * 1.5f).toInt().coerceIn(0, max)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0); hint = "Volume ${v * 100 / max.coerceAtLeast(1)}%"
                }
            }
        }, Alignment.Center) {
        Box(Modifier.then(if (fill) Modifier.fillMaxSize() else Modifier.fillMaxHeight().aspectRatio(ar)).graphicsLayer { scaleX = zoom; scaleY = zoom }) {
            VideoSurface(Modifier.fillMaxSize())
        }
        hint?.let { Text(it, Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x99000000)).padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        AnimatedVisibility(controls, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x99000000), 0.3f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color(0xAA000000)))) {
                if (locked) {
                    IconButton({ locked = false }, Modifier.align(Alignment.CenterStart).padding(24.dp)) { Icon(Icons.Default.Lock, "Unlock", tint = Color.White) }
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(now.track?.title.orEmpty(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(now.track?.artist.orEmpty() + if (vs.height > 0) " · ${vs.height}p" else "", fontSize = 12.sp, color = Color(0xFFCCCCCC), maxLines = 1)
                        }
                        Box {
                            IconButton({ qualityMenu = true }) { Icon(Icons.Default.HighQuality, "Quality", tint = Color.White) }
                            androidx.compose.material3.DropdownMenu(qualityMenu, { qualityMenu = false }) {
                                listOf(Int.MAX_VALUE to "Auto", 2160 to "2160p", 1440 to "1440p", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p").forEach { (h, l) ->
                                    androidx.compose.material3.DropdownMenuItem({ androidx.compose.material3.Text(if (h == quality) "✓  $l" else l) }, { quality = h; qualityMenu = false })
                                }
                            }
                        }
                        IconButton({ side = !side }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Up next", tint = Color.White) }
                    }
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                        IconButton({ MusicPlayer.previous() }) { Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(36.dp), tint = Color.White) }
                        Box(Modifier.size(64.dp).clip(CircleShape).background(Color(0x33FFFFFF)).clickable { MusicPlayer.toggle() }, Alignment.Center) {
                            Icon(if (now.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", Modifier.size(40.dp), tint = Color.White)
                        }
                        IconButton({ MusicPlayer.next() }) { Icon(Icons.Default.SkipNext, "Next", Modifier.size(36.dp), tint = Color.White) }
                    }
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                        val dur = now.durMs.coerceAtLeast(1)
                        Slider((now.posMs.toFloat() / dur).coerceIn(0f, 1f), { MusicPlayer.seekTo((it * dur).toLong()) },
                            colors = SliderDefaults.colors(thumbColor = Color(0xFFE50914), activeTrackColor = Color(0xFFE50914), inactiveTrackColor = Color(0x55FFFFFF)))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${clock(now.posMs)} / ${clock(now.durMs)}", fontSize = 13.sp)
                            Spacer(Modifier.weight(1f))
                            Row(Modifier.clickable { locked = true }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.LockOpen, null, tint = Color.White); Text(" Lock", fontSize = 13.sp) }
                            Row(Modifier.clickable { fill = !fill; zoom = 1f }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AspectRatio, null, tint = Color.White); Text(if (fill) " Fit" else " Fill", fontSize = 13.sp) }
                        }
                    }
                }
            }
        }
        if (side) Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.42f).background(Color(0xE6101010))) {
            Column {
                Text("Up next", Modifier.padding(16.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                UpNext(MusicNav({ _, _, _ -> }, {}, {}))
            }
        }
    }
}
