package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AlbBg = Color(0xFF030303)
private val AlbSub = Color(0xFFAAAAAA)
private val AlbChip = Color(0xFF212121)

private val yearRe = Regex("""\b(19|20)\d{2}\b""")
private val timeRe = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")
private val countRe = Regex("""\d+\s+(songs?|tracks?|minutes?|hours?)""", RegexOption.IGNORE_CASE)
private val albumKinds = setOf("album", "ep", "single", "playlist")

private fun fmtMs(ms: Long): String {
    val s = ms / 1000
    return String.format("%d:%02d", s / 60, s % 60)
}

/** "Album · artist · year" from the header subtitle ("Album • Artist • 2019 • 12 songs"). */
internal fun albumLine(subtitle: String?): String {
    val raw = subtitle.orEmpty()
    val parts = raw.split("•", "·").map { it.trim() }.filter { it.isNotEmpty() }
    val year = parts.firstOrNull { yearRe.matches(it) } ?: yearRe.find(raw)?.value
    val type = parts.firstOrNull { it.lowercase() in albumKinds } ?: "Album"
    val artist = parts.filter {
        it != type && it != year && it.lowercase() !in albumKinds && !countRe.containsMatchIn(it)
    }.joinToString(", ")
    return listOf(type, artist, year.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
}

/** "artist · 4:14 · 396M plays": keeps what the row already has, adds the duration if it is missing. */
internal fun trackLine(s: YtmItem): String {
    val parts = s.subtitle.orEmpty().split("•", "·").map { it.trim() }.filter { it.isNotEmpty() }
    val plays = parts.firstOrNull { it.contains("play", true) || it.contains("view", true) }
    var time = parts.firstOrNull { timeRe.matches(it) }
    if (time == null) {
        val ms = (s.durationMs as Number?)?.toLong() ?: 0L
        if (ms > 0) time = fmtMs(ms)
    }
    val artist = parts.filter { it != plays && !timeRe.matches(it) }.joinToString(", ")
    return listOf(artist, time.orEmpty(), plays.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
fun MusicAlbumPage(
    page: YtmPage,
    fallbackTitle: String,
    fallbackThumb: String?,
    onBack: () -> Unit,
    onPlay: (YtmItem, List<YtmItem>) -> Unit,
    onOpen: (YtmItem) -> Unit,
    onMenu: (YtmItem) -> Unit,
    saved: Boolean,
    onSave: () -> Unit,
    onShare: (() -> Unit)? = null,
    description: String? = null,
    currentId: String? = null,
    modifier: Modifier = Modifier,
) {
    val head = page.header
    val title = head?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
    val cover = head?.thumb ?: fallbackThumb
    val songs = remember(page) {
        (page.items + page.shelves.flatMap { it.items })
            .filter { it.kind == YtmKind.SONG || it.kind == YtmKind.VIDEO }
            .distinctBy { it.id }
    }
    val others = remember(page) {
        page.shelves.filter { sh ->
            sh.items.isNotEmpty() && sh.items.none { it.kind == YtmKind.SONG || it.kind == YtmKind.VIDEO }
        }
    }
    val totalMs = remember(songs) { songs.sumOf { ((it.durationMs as Number?)?.toLong() ?: 0L) } }

    LaunchedEffect(page) {
        MusicLog.add(
            "Album page: ${songs.size} tracks, ${others.size} extra shelves, " +
                "description ${if (description.isNullOrBlank()) "missing" else "found"}, " +
                "plays on ${songs.count { trackLine(it).contains("play", true) }} tracks"
        )
    }

    LazyColumn(modifier.fillMaxSize().background(AlbBg)) {
        item {
            IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
                Icon(Icons.Rounded.ArrowBack, "Back", tint = Color.White)
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth(0.72f).aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(AlbChip)) {
                    HdImage(url = cover.orEmpty(), description = title, modifier = Modifier.fillMaxSize())
                }
                Text(
                    title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 16.dp),
                )
                val line = albumLine(head?.subtitle)
                if (line.isNotBlank()) {
                    Text(line, color = AlbSub, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (!description.isNullOrBlank()) {
            item { Box(Modifier.padding(top = 14.dp)) { AlbumDescription(description) } }
        }
        item {
            var menu by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundBtn(Icons.Rounded.Download, "Download") {
                    MusicLog.add("Album page: download tapped (not built yet)")
                    Snack.show("Downloads are coming soon")
                }
                RoundBtn(if (saved) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd, "Save", onSave)
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Color.White)
                        .clickable(enabled = songs.isNotEmpty()) { onPlay(songs.first(), songs) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PlayArrow, "Play", tint = Color.Black, modifier = Modifier.size(38.dp)) }
                RoundBtn(Icons.Outlined.ChatBubbleOutline, "Comments") {
                    MusicLog.add("Album page: comments tapped (not built yet)")
                    Snack.show("Comments are coming soon")
                }
                Box {
                    RoundBtn(Icons.Rounded.MoreVert, "More") { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Shuffle") },
                            onClick = {
                                menu = false
                                if (songs.isNotEmpty()) { val sh = songs.shuffled(); onPlay(sh.first(), sh) }
                            },
                        )
                        if (onShare != null) {
                            DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; onShare() })
                        }
                    }
                }
            }
        }
        if (songs.isEmpty()) {
            item { Text("No songs here.", color = AlbSub, modifier = Modifier.padding(20.dp)) }
        }
        itemsIndexed(songs, key = { i, s -> "${i}_${s.id}" }) { i, s ->
            AlbumTrackRow(i + 1, s, s.id == currentId, onClick = { onPlay(s, songs) }, onMenu = { onMenu(s) })
        }
        if (songs.isNotEmpty()) {
            item {
                val mins = (totalMs / 60000).toInt()
                val txt = buildString {
                    append("${songs.size} ${if (songs.size == 1) "song" else "songs"}")
                    if (mins > 0) append(" · $mins ${if (mins == 1) "minute" else "minutes"}")
                }
                Text(txt, color = AlbSub, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp))
            }
        }
        items(others, key = { "sh_" + it.title.orEmpty() + it.items.firstOrNull()?.id.orEmpty() }) { sh ->
            Column(Modifier.padding(top = 18.dp)) {
                Text(sh.title.orEmpty(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(sh.items, key = { it.kind.name + it.id }) { it2 ->
                        Column(Modifier.width(130.dp).clickable { onOpen(it2) }) {
                            val shape = if (it2.kind == YtmKind.ARTIST) CircleShape else RoundedCornerShape(6.dp)
                            Box(Modifier.size(130.dp).clip(shape).background(AlbChip)) {
                                HdImage(url = it2.thumb.orEmpty(), description = it2.title, modifier = Modifier.fillMaxSize())
                            }
                            Text(it2.title, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            if (!it2.subtitle.isNullOrBlank()) {
                                Text(it2.subtitle.orEmpty(), color = AlbSub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RoundBtn(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(AlbChip).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = Color.White) }
}

@Composable
private fun AlbumDescription(text: String) {
    var open by remember(text) { mutableStateOf(false) }
    var over by remember(text) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().clickable(enabled = over || open) { open = !open }.padding(horizontal = 20.dp)) {
        Text(
            text, color = AlbSub, fontSize = 14.sp, lineHeight = 20.sp,
            maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!open) over = it.hasVisualOverflow },
        )
        if (over && !open) {
            Text(
                "…More", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd).background(AlbBg).padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun AlbumTrackRow(n: Int, s: YtmItem, now: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$n", color = if (now) Color.White else AlbSub, fontSize = 14.sp, modifier = Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, color = Color.White, fontSize = 16.sp, fontWeight = if (now) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = trackLine(s)
            if (line.isNotBlank()) Text(line, color = AlbSub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onMenu) { Icon(Icons.Rounded.MoreVert, "More", tint = AlbSub) }
    }
}
