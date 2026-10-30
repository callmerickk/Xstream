package com.example.streamnest

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Settings
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private val Sub = Color(0xFF9AA3B5)
private val Card = Color(0xFF15161C)

private fun mmss(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/** Home: the "Music" chip (64-bit app with the Live server on). */
@Composable
fun MusicChip(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    if (!LiveServer.enabled(ctx)) return
    Row(modifier.clip(RoundedCornerShape(50)).background(Color(0x1FFFFFFF)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50))
        .clickable { MusicPlayer.openLibrary.value = true }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.MusicNote, null, Modifier.size(17.dp), tint = Color.White)
        Spacer(Modifier.width(6.dp))
        Text("Music", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Art(url: String?, size: Dp, corner: Dp = 8.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(corner)).background(Brush.linearGradient(listOf(Color(0xFF3A1C24), Color(0xFF14161E)))), Alignment.Center) {
        Icon(Icons.Default.MusicNote, null, Modifier.size(size * 0.42f), tint = Color(0x66FFFFFF))
        if (!url.isNullOrBlank()) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun TrackRow(t: LiveServer.Track, current: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(t.artworkURL, 50.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(t.title.ifBlank { "Unknown song" }, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) BtnColor else Color.White)
            Text(listOfNotNull(t.artist.ifBlank { null }, t.format.uppercase().takeIf { it.isNotBlank() && it != "YT" }).joinToString(" · "), fontSize = 13.sp, color = Sub,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (t.durationMs > 0) Text(mmss(t.durationMs), fontSize = 12.sp, color = Sub)
    }
}

/** Xstream Music's own colours (separate from the app theme): background, accent and the now-playing glow. */
object MusicLook {
    data class Look(val name: String, val bg: Color, val accent: Color, val glow: Color)
    val all = listOf(
        Look("Elegant", Color(0xFF0F1014), Color(0xFFE50914), Color(0xFF3A1A1F)),
        Look("Midnight", Color(0xFF0A0F1F), Color(0xFF4F8CFF), Color(0xFF14244A)),
        Look("Forest", Color(0xFF0C140F), Color(0xFF34D17A), Color(0xFF123222)),
        Look("Plum", Color(0xFF140C16), Color(0xFFC86BFF), Color(0xFF2E1636)),
        Look("Sunset", Color(0xFF140B07), Color(0xFFFF7A1A), Color(0xFF3A1E0C)),
    )
    val current = kotlinx.coroutines.flow.MutableStateFlow(all[0])
    fun load(ctx: android.content.Context) {
        val n = ctx.getSharedPreferences("music", android.content.Context.MODE_PRIVATE).getString("look", "Elegant")
        current.value = all.firstOrNull { it.name == n } ?: all[0]
    }
    fun set(ctx: android.content.Context, l: Look) {
        current.value = l; ctx.getSharedPreferences("music", android.content.Context.MODE_PRIVATE).edit().putString("look", l.name).apply()
    }
}

/** "Xstream" with "Music" in a hand-written style under / beside it. */
@Composable
fun MusicWordmark(size: Float = 22f, accent: Color = BtnColor) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("X", fontSize = size.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFE50914))
        Text("stream", fontSize = size.sp, fontWeight = FontWeight.ExtraBold)
        Text(" Music", fontSize = (size * 1.05f).sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Cursive, fontWeight = FontWeight.Bold, color = accent)
    }
}

/** Xstream Music: YouTube Music's shelves through SimpMusic (or the Live server's songs when chosen in its settings). */
@Composable
fun MusicLibrary(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    val recent by MusicPlayer.recent.collectAsStateWithLifecycle()
    val look by MusicLook.current.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    var useLive by remember { mutableStateOf(SimpMusic.useLive(ctx)) }
    var q by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var shelves by remember { mutableStateOf<List<SimpMusic.Shelf>?>(SimpMusic.home) }
    var latest by remember { mutableStateOf<List<LiveServer.Track>?>(null) }
    var results by remember { mutableStateOf<List<SimpMusic.Item>?>(null) }
    var liveResults by remember { mutableStateOf<List<LiveServer.Track>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<SimpMusic.Item?>(null) }
    var settings by remember { mutableStateOf(false) }
    var chip by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(useLive) {
        MusicPlayer.init(ctx); MusicLook.load(ctx)
        if (useLive) latest = runCatching { LiveServer.latestTracks(ctx) }.getOrDefault(emptyList())
        else if (shelves == null) shelves = runCatching { SimpMusic.loadHome() }.getOrDefault(emptyList())
    }
    LaunchedEffect(q, useLive) {
        val t = q.trim()
        if (t.length < 2) { results = null; liveResults = null; searching = false; return@LaunchedEffect }
        delay(350); searching = true
        if (useLive) liveResults = runCatching { LiveServer.searchTracks(ctx, t) }.getOrDefault(emptyList())
        else results = runCatching { SimpMusic.search(t) }.getOrDefault(emptyList())
        searching = false
    }
    fun play(it: SimpMusic.Item, list: List<SimpMusic.Item>) {
        if (it.isSong) {
            val songs = list.filter { s -> s.isSong }
            MusicPlayer.play(ctx, songs.map { s -> s.track() }, songs.indexOf(it).coerceAtLeast(0))
        } else opened = it
    }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(look.bg)) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Box(Modifier.weight(1f)) { MusicWordmark(21f, look.accent) }
                    IconButton({ searchOpen = !searchOpen; if (!searchOpen) q = "" }) { Icon(Icons.Default.Search, "Search") }
                    IconButton({ settings = true }) { Icon(Icons.Default.Settings, "Music settings") }
                }
                if (searchOpen) BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 17.sp), cursorBrush = SolidColor(look.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    decorationBox = { inner ->
                        Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0x14FFFFFF)).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, null, Modifier.size(21.dp), tint = Sub)
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.weight(1f)) { if (q.isEmpty()) Text("Songs, artists, albums", color = Color(0xFF6B7385), fontSize = 17.sp); inner() }
                            if (q.isNotEmpty()) Icon(Icons.Default.Close, "Clear", Modifier.size(20.dp).clickable { q = "" }, tint = Sub)
                        }
                    })
                val bottomPad = if (now.track != null) 100.dp else 24.dp
                if (q.trim().length >= 2) {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottomPad)) {
                        if (searching) item { Box(Modifier.fillMaxWidth().padding(30.dp), Alignment.Center) { CircularProgressIndicator(color = look.accent) } }
                        else if (useLive) {
                            val l = liveResults.orEmpty()
                            if (l.isEmpty()) item { Text("Nothing found for “${q.trim()}”", Modifier.padding(16.dp), color = Sub) }
                            itemsIndexed(l, key = { i, t -> "r$i${t.id}" }) { i, t -> TrackRow(t, now.track?.id == t.id) { keyboard?.hide(); MusicPlayer.play(ctx, l, i) } }
                        } else {
                            val l = results.orEmpty()
                            if (l.isEmpty()) item { Text("Nothing found for “${q.trim()}”", Modifier.padding(16.dp), color = Sub) }
                            itemsIndexed(l, key = { i, t -> "s$i${t.url}" }) { _, t -> ItemRow(t, now.track?.id == "yt:${t.videoId}") { keyboard?.hide(); play(t, l) } }
                        }
                    }
                } else if (useLive) {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottomPad)) {
                        item { Text("Latest from the Live server", Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp), fontSize = 19.sp, fontWeight = FontWeight.Bold) }
                        val l = latest
                        if (l == null) item { Box(Modifier.fillMaxWidth().padding(30.dp), Alignment.Center) { CircularProgressIndicator(color = look.accent) } }
                        else itemsIndexed(l, key = { i, t -> "l$i${t.id}" }) { i, t -> TrackRow(t, now.track?.id == t.id) { MusicPlayer.play(ctx, l, i) } }
                    }
                } else {
                    val sh = shelves
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = bottomPad)) {
                        // Mood / shelf chips: tap one to jump to it.
                        if (!sh.isNullOrEmpty()) item {
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(sh, key = { i, x -> "c$i" }) { i, x ->
                                    val on = chip == x.title
                                    Text(x.title, Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Color.White else Color(0x14FFFFFF))
                                        .clickable { chip = x.title; scope.launch { listState.animateScrollToItem(1 + (if (recent.isNotEmpty()) 2 else 0) + i * 2) } }
                                        .padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on) Color(0xFF111111) else Color.White, maxLines = 1)
                                }
                            }
                        }
                        if (recent.isNotEmpty()) {
                            item { ShelfTitle("Played recently") }
                            item {
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    itemsIndexed(recent.take(20), key = { _, t -> "p" + t.id }) { i, t ->
                                        Column(Modifier.width(132.dp).clickable { MusicPlayer.play(ctx, recent, i) }) {
                                            Art(t.artworkURL, 132.dp, 10.dp)
                                            Text(t.title, Modifier.padding(top = 6.dp), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(t.artist, fontSize = 12.sp, color = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                        when {
                            sh == null -> item { Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator(color = look.accent) } }
                            sh.isEmpty() -> item { Text("Music couldn't load. Check your connection and open it again.", Modifier.padding(16.dp), color = Sub) }
                            else -> sh.forEachIndexed { si, x ->
                                item(key = "t$si") { ShelfTitle(x.title) }
                                item(key = "s$si") {
                                    val songs = x.items.count { it.isSong }
                                    if (si == 0 && songs >= 4) {
                                        // Quick picks: four rows of songs, swiped sideways (like YouTube Music).
                                        androidx.compose.foundation.lazy.grid.LazyHorizontalGrid(androidx.compose.foundation.lazy.grid.GridCells.Fixed(4),
                                            Modifier.height(4 * 62.dp), contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            items(x.items.size) { k ->
                                                val it = x.items[k]
                                                Box(Modifier.width(300.dp)) { ItemRow(it, now.track?.id == "yt:${it.videoId}", pad = 0.dp) { play(it, x.items) } }
                                            }
                                        }
                                    } else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        itemsIndexed(x.items, key = { i, it -> "$si-$i" }) { _, it ->
                                            val w = if (x.wide) 240.dp else 150.dp
                                            Column(Modifier.width(w).clickable { play(it, x.items) }) {
                                                Box(Modifier.width(w).height(if (x.wide) 135.dp else 150.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF))) {
                                                    if (it.art != null) AsyncImage(it.art, it.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                                }
                                                Text(it.title, Modifier.padding(top = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                Text(it.subtitle, fontSize = 12.sp, color = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp)) { MusicMiniBar() }
            opened?.let { o -> MusicCollection(o, look) { opened = null } }
            if (settings) MusicSettings(look, useLive, onLive = { on -> useLive = on; SimpMusic.setUseLive(ctx, on) }) { settings = false }
        }
    }
}

@Composable
private fun ShelfTitle(t: String) =
    Text(t, Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp), fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun ItemRow(t: SimpMusic.Item, current: Boolean, pad: Dp = 16.dp, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(62.dp).clickable(onClick = onClick).padding(horizontal = pad, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(t.art, 52.dp, if (t.subtitle == "Artist") 26.dp else 6.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (current) BtnColor else Color.White)
            Text(t.subtitle, fontSize = 12.5.sp, color = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** An album / playlist / artist: its cover, Play all / Shuffle and the songs. */
@Composable
private fun MusicCollection(it: SimpMusic.Item, look: MusicLook.Look, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    var songs by remember(it.url) { mutableStateOf<List<LiveServer.Track>?>(null) }
    LaunchedEffect(it.url) { songs = runCatching { SimpMusic.songs(it) }.getOrDefault(emptyList()) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(look.glow, look.bg, look.bg)))) {
            LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 110.dp)) {
                item { Row { IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } } }
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Art(it.art, 220.dp, if (it.subtitle == "Artist") 110.dp else 14.dp)
                        Text(it.title, Modifier.padding(top = 16.dp), fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(it.subtitle + (songs?.let { s -> " · ${s.size} songs" } ?: ""), fontSize = 14.sp, color = Sub)
                        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White).clickable { songs?.takeIf { s -> s.isNotEmpty() }?.let { s -> MusicPlayer.play(ctx, s, 0) } }
                                .padding(horizontal = 22.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PlayArrow, null, tint = Color.Black); Text("Play all", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                            Row(Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0x22FFFFFF)).clickable { songs?.takeIf { s -> s.isNotEmpty() }?.let { s -> MusicPlayer.play(ctx, s.shuffled(), 0) } }
                                .padding(horizontal = 22.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Shuffle, null); Text(" Shuffle", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                val l = songs
                if (l == null) item { Box(Modifier.fillMaxWidth().padding(30.dp), Alignment.Center) { CircularProgressIndicator(color = look.accent) } }
                else if (l.isEmpty()) item { Text("No songs here.", Modifier.padding(16.dp), color = Sub) }
                else itemsIndexed(l, key = { i, t -> "a$i${t.id}" }) { i, t -> TrackRow(t, now.track?.id == t.id) { MusicPlayer.play(ctx, l, i) } }
            }
            Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp)) { MusicMiniBar() }
        }
    }
}

/** Music settings: where songs come from, the player's colours, and the home-screen shortcut / widget. */
@Composable
private fun MusicSettings(look: MusicLook.Look, useLive: Boolean, onLive: (Boolean) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    Dialog(onClose) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF1A1C23)).padding(18.dp)) {
            Text("Music settings", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (BuildConfig.HAS_LIVE) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp).clickable { onLive(!useLive) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use Live server for music", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Off: songs come from SimpMusic (YouTube Music: real albums and covers, faster).", fontSize = 12.sp, color = Sub)
                    }
                    androidx.compose.material3.Switch(useLive, { onLive(it) })
                }
            }
            Text("Sound quality: automatic (160 kbps, 128 kbps when the connection is slow).", Modifier.padding(top = 12.dp), fontSize = 12.sp, color = Sub)
            Text("PLAYER COLOURS", Modifier.padding(top = 16.dp, bottom = 8.dp), fontSize = 12.sp, color = Sub, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MusicLook.all.forEach { l ->
                    Box(Modifier.size(38.dp).clip(CircleShape).background(Brush.linearGradient(0.5f to l.bg, 0.5f to l.accent))
                        .border(2.dp, if (l.name == look.name) Color.White else Color.Transparent, CircleShape).clickable { MusicLook.set(ctx, l) })
                }
            }
            Text(look.name, Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Sub)
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add to home screen", Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(Color(0x22FFFFFF)).clickable { MusicShortcut.pin(ctx) }
                    .padding(vertical = 12.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("Add widget", Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(Color(0x22FFFFFF)).clickable { MusicWidget.pin(ctx) }
                    .padding(vertical = 12.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Text("Done", Modifier.align(Alignment.End).padding(top = 14.dp).clip(RoundedCornerShape(20.dp)).background(look.accent).clickable(onClick = onClose)
                .padding(horizontal = 22.dp, vertical = 10.dp), fontWeight = FontWeight.Bold)
        }
    }
}

/** The small player above the dock (and inside Music) while something is in the queue. */
@Composable
fun MusicMiniBar(modifier: Modifier = Modifier) {
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    LaunchedEffect(now.track != null, now.playing) { while (now.track != null) { MusicPlayer.publish(); delay(500) } }
    AnimatedVisibility(now.track != null, modifier, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        val t = now.track ?: return@AnimatedVisibility
        Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xF21C1D25))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp)).clickable { MusicPlayer.openNow.value = true }) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(t.artworkURL, 44.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(now.error ?: t.artist, fontSize = 12.sp, color = if (now.error != null) Color(0xFFFF8A80) else Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (now.buffering) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                else IconButton({ MusicPlayer.toggle() }) { Icon(if (now.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (now.playing) "Pause" else "Play") }
                IconButton({ MusicPlayer.next() }) { Icon(Icons.Default.SkipNext, "Next") }
                IconButton({ MusicPlayer.stop() }) { Icon(Icons.Default.Close, "Stop", Modifier.size(20.dp), tint = Sub) }
            }
            val f = if (now.durMs > 0) (now.posMs.toFloat() / now.durMs).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0x22FFFFFF))) { Box(Modifier.fillMaxWidth(f).height(2.dp).background(BtnColor)) }
        }
    }
}

/** Now playing: big artwork, seek bar, controls, shuffle / repeat and the queue. */
@Composable
fun MusicNowPlaying(onClose: () -> Unit) {
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    val queue by MusicPlayer.queue.collectAsStateWithLifecycle()
    val look by MusicLook.current.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var showQueue by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) { while (true) { MusicPlayer.publish(); delay(500) } }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        val t = now.track
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(look.glow, look.bg, look.bg)))) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close", Modifier.size(30.dp)) }
                    // Song / Video (YouTube Music songs): the music video plays in Xstream's own player.
                    val vid = t?.id?.takeIf { it.startsWith("yt:") }?.removePrefix("yt:")
                    if (vid != null) Box(Modifier.weight(1f), Alignment.Center) {
                        Row(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x1FFFFFFF)).padding(3.dp)) {
                            Text("Song", Modifier.clip(RoundedCornerShape(17.dp)).background(Color.White).padding(horizontal = 18.dp, vertical = 7.dp),
                                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111111))
                            Text("Video", Modifier.clip(RoundedCornerShape(17.dp)).clickable {
                                MusicPlayer.pauseForVideo()
                                TrailerActivity.nextLangs = emptyList()
                                runCatching { ctx.startActivity(TrailerActivity.intent(ctx, listOf(vid), listOf(t?.title.orEmpty()), 0, t?.artist?.ifBlank { null } ?: t?.title.orEmpty())) }
                            }.padding(horizontal = 18.dp, vertical = 7.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    } else Text("Now playing", Modifier.weight(1f), fontSize = 15.sp, color = Sub, fontWeight = FontWeight.SemiBold)
                    IconButton({ showQueue = !showQueue }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue", tint = if (showQueue) BtnColor else Color.White) }
                }
                if (t == null) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Nothing playing", color = Sub) }; return@Column }
                if (showQueue) {
                    LazyColumn(Modifier.weight(1f)) {
                        itemsIndexed(queue, key = { i, q -> "q$i${q.id}" }) { i, q -> TrackRow(q, i == now.index) { MusicPlayer.jumpTo(i) } }
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp))) { Art(t.artworkURL, 1000.dp, 20.dp) }
                    }
                }
                Text(t.title, Modifier.padding(top = 18.dp), fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(now.error ?: listOfNotNull(t.artist.ifBlank { null }, t.album).joinToString(" · "), fontSize = 15.sp,
                    color = if (now.error != null) Color(0xFFFF8A80) else Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val dur = now.durMs.coerceAtLeast(1)
                Slider(drag ?: (now.posMs.toFloat() / dur).coerceIn(0f, 1f), { drag = it }, Modifier.padding(top = 10.dp),
                    onValueChangeFinished = { drag?.let { MusicPlayer.seekTo((it * dur).toLong()) }; drag = null },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = look.accent, inactiveTrackColor = Color(0x33FFFFFF)))
                Row(Modifier.fillMaxWidth()) {
                    Text(mmss(drag?.let { (it * dur).toLong() } ?: now.posMs), fontSize = 12.sp, color = Sub)
                    Spacer(Modifier.weight(1f))
                    Text(mmss(now.durMs), fontSize = 12.sp, color = Sub)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ MusicPlayer.shuffle() }) { Icon(Icons.Default.Shuffle, "Shuffle", tint = if (now.shuffle) BtnColor else Sub) }
                    IconButton({ MusicPlayer.previous() }, Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(36.dp)) }
                    Box(Modifier.size(72.dp).clip(CircleShape).background(Color.White).clickable { MusicPlayer.toggle() }, Alignment.Center) {
                        if (now.buffering) CircularProgressIndicator(Modifier.size(30.dp), color = Color.Black, strokeWidth = 3.dp)
                        else Icon(if (now.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (now.playing) "Pause" else "Play", Modifier.size(40.dp), tint = Color.Black)
                    }
                    IconButton({ MusicPlayer.next() }, Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext, "Next", Modifier.size(36.dp)) }
                    IconButton({ MusicPlayer.cycleRepeat() }) {
                        Icon(if (now.repeat == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat",
                            tint = if (now.repeat == androidx.media3.common.Player.REPEAT_MODE_OFF) Sub else BtnColor)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** Mounted once in the app: opens Music / Now playing when asked (Home chip, mini bar, notification). */
@Composable
fun MusicHost() {
    val lib by MusicPlayer.openLibrary.collectAsStateWithLifecycle()
    val nowOpen by MusicPlayer.openNow.collectAsStateWithLifecycle()
    val splash by MusicPlayer.splash.collectAsStateWithLifecycle()
    if (splash) MusicSplash { MusicPlayer.splash.value = false; MusicPlayer.openLibrary.value = true }
    if (lib) MusicLibrary { MusicPlayer.openLibrary.value = false }
    if (nowOpen) MusicNowPlaying { MusicPlayer.openNow.value = false }
}


/** Opening Xstream Music from its home-screen icon or widget: the app's name pops in with "Music" under it. */
@Composable
private fun MusicSplash(onDone: () -> Unit) {
    val a = remember { androidx.compose.animation.core.Animatable(0f) }
    val b = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        a.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 260f))
        b.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 240f))
        delay(650); onDone()
    }
    Dialog({}, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(MusicLook.current.value.bg), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.graphicsLayer { val k = 0.4f + 0.6f * a.value; scaleX = k; scaleY = k; alpha = a.value.coerceIn(0f, 1f) }) {
                    Text("X", fontSize = 54.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFE50914))
                    Text("stream", fontSize = 54.sp, fontWeight = FontWeight.ExtraBold)
                }
                Text("Music", Modifier.graphicsLayer { val k = 0.4f + 0.6f * b.value; scaleX = k; scaleY = k; alpha = b.value.coerceIn(0f, 1f); translationY = (1f - b.value) * 40f },
                    fontSize = 46.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Cursive, fontWeight = FontWeight.Bold, color = Color(0xFFFF5A4E))
            }
        }
    }
}
