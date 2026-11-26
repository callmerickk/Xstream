package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * musicSearch2 · "You may also like" lines for an empty search box. First YouTube Music's own suggestions for an empty
 * box (kept 10 min); if it gives none, ideas from this profile: subscribed artists, then artists of recent plays.
 */
object MusicSearchIdeas {
    private var at = 0L
    private var kept: List<String> = emptyList()

    suspend fun load(): List<String> {
        if (kept.isNotEmpty() && System.currentTimeMillis() - at < 10 * 60_000L) return kept
        val live = runCatching { YtMusic.suggestionTexts("") }.getOrDefault(emptyList())
        if (live.isNotEmpty()) {
            MusicLog.add("search ideas: ${live.size} from YouTube Music")
            kept = live; at = System.currentTimeMillis()
            return live
        }
        val d = MusicLibraryStore.active()
        val artists = d.subscriptions.map { it.title } + d.history.take(40).mapNotNull { h ->
            val i = h.item
            if (i.kind == YtmKind.SONG || i.kind == YtmKind.VIDEO)
                i.subtitle.substringAfter("Song • ").substringAfter("Video • ").substringBefore(" • ").trim().takeIf { it.isNotBlank() }
            else null
        }
        val out = artists.filter { it.isNotBlank() }.distinct().take(8)
        MusicLog.add(if (out.isEmpty()) "search ideas: none (YouTube Music gave nothing and the profile has no history yet)"
            else "search ideas: YouTube Music gave none, ${out.size} from this profile's artists")
        return out
    }
}

private fun kindLabel(k: YtmKind) = when (k) {
    YtmKind.SONG -> "Song"; YtmKind.VIDEO -> "Video"; YtmKind.ALBUM -> "Album"; YtmKind.SINGLE -> "Single"
    YtmKind.PLAYLIST -> "Playlist"; YtmKind.ARTIST -> "Artist"; YtmKind.PODCAST -> "Podcast"; YtmKind.EPISODE -> "Episode"
}

/**
 * musicSearch2 · what an empty search box shows: "Recent searches" (a row of covers: tap = open it again, then the old text
 * searches as lines) and "You may also like" (🔍 line; tap = search it; ↖ = put it in the box without searching).
 */
@Composable
fun MusicSearchEmpty(
    recent: List<YtmItem>,
    texts: List<String>,
    ideas: List<String>,
    onOpen: (YtmItem) -> Unit,
    onSearch: (String) -> Unit,
    onFill: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sub = Color(0xFFAAAAAA)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (recent.isNotEmpty() || texts.isNotEmpty()) item(key = "h1") { SectionHead("Recent searches") }
        if (recent.isNotEmpty()) item(key = "covers") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recent, key = { it.kind.name + ":" + it.id }) { it ->
                    val round = it.kind == YtmKind.ARTIST
                    Column(Modifier.width(104.dp).clickable { onOpen(it) }, horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start) {
                        Box(Modifier.size(104.dp).clip(if (round) CircleShape else RoundedCornerShape(8.dp)).background(Color(0xFF212121))) {
                            HdImage(it.thumb, it.title, Modifier.fillMaxSize(), px = 400)
                        }
                        Text(it.title, Modifier.padding(top = 6.dp).fillMaxWidth(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (round) TextAlign.Center else TextAlign.Start)
                        Text(kindLabel(it.kind), Modifier.fillMaxWidth(), color = sub, fontSize = 12.sp, maxLines = 1,
                            textAlign = if (round) TextAlign.Center else TextAlign.Start)
                    }
                }
            }
        }
        items(texts.take(5), key = { "t:$it" }) { s ->
            SuggestionRow(Icons.Default.History, s, sub, { onSearch(s) }, { onFill(s) })
        }
        if (ideas.isNotEmpty()) {
            item(key = "h2") { SectionHead("You may also like") }
            items(ideas, key = { "i:$it" }) { s -> SuggestionRow(Icons.Default.Search, s, sub, { onSearch(s) }, { onFill(s) }) }
        }
    }
}

@Composable
private fun SectionHead(t: String) =
    Text(t, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

@Composable
private fun SuggestionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, sub: Color, onTap: () -> Unit, onFill: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onTap).padding(start = 16.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = sub)
        Text(text, Modifier.weight(1f).padding(horizontal = 16.dp), color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onFill) { Icon(Icons.Default.NorthWest, "Put in the search box", Modifier.size(20.dp), tint = sub) }
    }
}
