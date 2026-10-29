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
            Text(listOfNotNull(t.artist.ifBlank { null }, t.format.uppercase().ifBlank { null }).joinToString(" · "), fontSize = 13.sp, color = Sub,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (t.durationMs > 0) Text(mmss(t.durationMs), fontSize = 12.sp, color = Sub)
    }
}

/** All music: search, latest songs and what you played recently. */
@Composable
fun MusicLibrary(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val now by MusicPlayer.now.collectAsStateWithLifecycle()
    val recent by MusicPlayer.recent.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    var q by remember { mutableStateOf("") }
    var latest by remember { mutableStateOf<List<LiveServer.Track>?>(null) }
    var results by remember { mutableStateOf<List<LiveServer.Track>?>(null) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { MusicPlayer.init(ctx); latest = runCatching { LiveServer.latestTracks(ctx) }.getOrDefault(emptyList()) }
    LaunchedEffect(q) {
        val t = q.trim()
        if (t.length < 2) { results = null; searching = false; return@LaunchedEffect }
        delay(350); searching = true
        results = runCatching { LiveServer.searchTracks(ctx, t) }.getOrDefault(emptyList()); searching = false
    }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text("Music", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 17.sp), cursorBrush = SolidColor(BtnColor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    decorationBox = { inner ->
                        Row(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp)).background(Color(0xFF1A1D24)).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, null, Modifier.size(21.dp), tint = Sub)
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.weight(1f)) { if (q.isEmpty()) Text("Songs, artists, albums", color = Color(0xFF6B7385), fontSize = 17.sp); inner() }
                            if (q.isNotEmpty()) Icon(Icons.Default.Close, "Clear", Modifier.size(20.dp).clickable { q = "" }, tint = Sub)
                        }
                    })
                val list = results
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = if (now.track != null) 96.dp else 24.dp)) {
                    if (list != null || searching) {
                        item { Text("Results", Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                        if (searching && list == null) item { Box(Modifier.fillMaxWidth().padding(30.dp), Alignment.Center) { CircularProgressIndicator(color = BtnColor) } }
                        else if (list.isNullOrEmpty()) item { Text("Nothing found for “${q.trim()}”", Modifier.padding(16.dp), color = Sub) }
                        else itemsIndexed(list, key = { i, t -> "r$i${t.id}" }) { i, t -> TrackRow(t, now.track?.id == t.id) { keyboard?.hide(); MusicPlayer.play(ctx, list, i) } }
                    } else {
                        if (recent.isNotEmpty()) {
                            item { Text("Played recently", Modifier.padding(start = 16.dp, top = 14.dp, bottom = 10.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                            item {
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    itemsIndexed(recent.take(20), key = { _, t -> "p" + t.id }) { i, t ->
                                        Column(Modifier.width(120.dp).clickable { MusicPlayer.play(ctx, recent, i) }) {
                                            Art(t.artworkURL, 120.dp, 12.dp)
                                            Text(t.title, Modifier.padding(top = 6.dp), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(t.artist, fontSize = 12.sp, color = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                        item { Text("Latest", Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                        val l = latest
                        when {
                            l == null -> item { Box(Modifier.fillMaxWidth().padding(30.dp), Alignment.Center) { CircularProgressIndicator(color = BtnColor) } }
                            l.isEmpty() -> item { Text(if (LiveServer.state.value.ready) "No songs right now. Try a search." else "The Live server is starting… try again in a moment.",
                                Modifier.padding(16.dp), color = Sub) }
                            else -> itemsIndexed(l, key = { i, t -> "l$i${t.id}" }) { i, t -> TrackRow(t, now.track?.id == t.id) { MusicPlayer.play(ctx, l, i) } }
                        }
                    }
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp)) { MusicMiniBar() }
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
    var showQueue by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) { while (true) { MusicPlayer.publish(); delay(500) } }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        val t = now.track
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A1218), Bg, Bg)))) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close", Modifier.size(30.dp)) }
                    Text("Now playing", Modifier.weight(1f), fontSize = 15.sp, color = Sub, fontWeight = FontWeight.SemiBold)
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
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = BtnColor, inactiveTrackColor = Color(0x33FFFFFF)))
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
    if (lib) MusicLibrary { MusicPlayer.openLibrary.value = false }
    if (nowOpen) MusicNowPlaying { MusicPlayer.openNow.value = false }
}

