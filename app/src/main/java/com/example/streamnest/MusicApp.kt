package com.example.streamnest

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

private val MusicBackground = Color(0xFF090909)
private val MusicAccentColor = Color(0xFFFF3B46)

@Composable
fun MusicApp(
    profileId: String,
    profileName: String,
    profileVm: AppViewModel? = null,
    initiallyNowPlaying: Boolean = false,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    val queue by MusicPlayer.queue.collectAsStateWithLifecycle()
    var library by remember { mutableStateOf(MusicLibraryStore.active()) }
    var page by rememberSaveable { mutableStateOf(if (initiallyNowPlaying) "Player" else "Home") }
    var title by rememberSaveable { mutableStateOf("Music") }
    var items by remember { mutableStateOf<List<YtmItem>>(emptyList()) }
    var shelves by remember { mutableStateOf<List<YtmShelf>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    fun loadHome() {
        scope.launch {
            loading = true
            val result = runCatching { YtMusic.home() }
            shelves = result.getOrNull()?.shelves.orEmpty()
            items = result.getOrNull()?.items.orEmpty()
            error = if (shelves.isEmpty() && items.isEmpty()) "Music catalog could not be loaded. Check your connection and retry." else ""
            loading = false
        }
    }
    LaunchedEffect(profileId) {
        MusicPlayer.init(context)
        library = MusicLibraryStore.open(context, profileId)
        loadHome()
    }
    BackHandler(page != "Home") { page = "Home" }

    fun play(item: YtmItem, list: List<YtmItem>) {
        val playable = list.filter { it.kind == YtmKind.SONG || it.kind == YtmKind.VIDEO }
        val tracks = playable.map { LiveServer.Track(id = "yt:" + it.id, title = it.title, artist = it.subtitle,
            album = title, durationMs = it.durationMs, format = "yt", artworkURL = it.thumb) }
        val index = playable.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        MusicPlayer.play(context, tracks, index)
        scope.launch { MusicLibraryStore.recordPlay(context, item) }
        page = "Player"
    }

    Column(Modifier.fillMaxSize().background(MusicBackground)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page != "Home") {
                IconButton(onClick = { page = "Home" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            }
            Column(Modifier.weight(1f)) {
                Text(if (page == "Home") "Xstream Music" else page, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (page == "Home") Text("Listening as $profileName", color = Color.LightGray, fontSize = 12.sp)
            }
            TextButton(onClick = onClose) { Text("Done") }
        }

        when (page) {
            "Search" -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search songs, albums, artists") }, singleLine = true)
                Button(onClick = {
                    scope.launch {
                        loading = true
                        val result = runCatching { YtMusic.search(query) }.getOrNull()
                        items = result?.items.orEmpty()
                        title = "Search results"
                        error = if (items.isEmpty()) "No results found." else ""
                        loading = false
                    }
                }, Modifier.padding(top = 10.dp)) { Text("Search") }
                MusicItems(items, loading, error, { play(it, items) })
            }
            "Library" -> LazyColumn(Modifier.fillMaxSize()) {
                item { Text("Liked songs", Modifier.padding(16.dp), fontWeight = FontWeight.Bold) }
                items(library.liked, key = { "like:" + it.id }) { MusicRow(it) { play(it, library.liked) } }
                item { Text("Recently played", Modifier.padding(16.dp), fontWeight = FontWeight.Bold) }
                items(library.history.map { it.item }, key = { "history:" + it.id }) { MusicRow(it) { play(it, library.history.map { h -> h.item }) } }
                if (library.liked.isEmpty() && library.history.isEmpty()) item { Text("Your music history and liked songs will appear here.", Modifier.padding(20.dp), color = Color.LightGray) }
            }
            "Player" -> {
                val track = now.track
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (track?.artworkURL != null) AsyncImage(track.artworkURL, null, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
                    else Spacer(Modifier.weight(1f))
                    Text(track?.title ?: "Nothing playing", Modifier.padding(top = 24.dp), fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(track?.artist.orEmpty(), Modifier.padding(top = 6.dp), color = Color.LightGray)
                    Slider(value = if (now.durMs > 0) (now.posMs.toFloat() / now.durMs).coerceIn(0f, 1f) else 0f,
                        onValueChange = { MusicPlayer.seekTo((it * now.durMs).toLong()) }, modifier = Modifier.padding(top = 18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { MusicPlayer.previous() }) { Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White) }
                        IconButton(onClick = { MusicPlayer.toggle() }) { Icon(if (now.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", tint = Color.White) }
                        IconButton(onClick = { MusicPlayer.next() }) { Icon(Icons.Default.SkipNext, "Next", tint = Color.White) }
                    }
                    Text("Up next", Modifier.align(Alignment.Start).padding(top = 18.dp), fontWeight = FontWeight.Bold)
                    queue.drop((now.index + 1).coerceAtLeast(0)).take(4).forEach { Text(it.title, Modifier.fillMaxWidth().padding(vertical = 4.dp), color = Color.LightGray, maxLines = 1) }
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { page = "Search" }) { Text("Search") }
                        OutlinedButton(onClick = { page = "Library" }) { Text("Your Library") }
                        OutlinedButton(onClick = { loadHome() }) { Text("Refresh") }
                    }
                }
                if (loading && shelves.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = MusicAccentColor) }
                if (error.isNotBlank()) item { Text(error, Modifier.padding(18.dp), color = Color.LightGray) }
                shelves.forEach { shelf ->
                    item(key = "header:" + shelf.title) {
                        Text(shelf.title, Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    }
                    items(shelf.items, key = { shelf.title + ":" + it.id }) { song -> MusicRow(song) { title = shelf.title; play(song, shelf.items) } }
                }
                if (shelves.isEmpty() && items.isNotEmpty()) items(items, key = { it.id }) { song -> MusicRow(song) { play(song, items) } }
            }
        }

        if (page != "Player" && now.track != null) {
            Row(Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF252525)).clickable { page = "Player" }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(now.track?.title.orEmpty(), maxLines = 1, fontWeight = FontWeight.SemiBold)
                    Text(now.track?.artist.orEmpty(), maxLines = 1, color = Color.LightGray, fontSize = 12.sp)
                }
                IconButton(onClick = { MusicPlayer.toggle() }) { Icon(if (now.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause") }
                IconButton(onClick = { MusicPlayer.next() }) { Icon(Icons.Default.SkipNext, "Next") }
            }
        }
    }
}

@Composable
private fun MusicItems(items: List<YtmItem>, loading: Boolean, error: String, onPlay: (YtmItem) -> Unit) {
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp), color = MusicAccentColor)
    if (error.isNotBlank()) Text(error, Modifier.padding(16.dp), color = Color.LightGray)
    LazyColumn(Modifier.fillMaxWidth()) { items(items, key = { it.id }) { MusicRow(it) { onPlay(it) } } }
}

@Composable
private fun MusicRow(item: YtmItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(item.thumb, null, Modifier.size(54.dp).clip(RoundedCornerShape(6.dp)).background(Color.DarkGray), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.LightGray, fontSize = 12.sp)
        }
    }
}


