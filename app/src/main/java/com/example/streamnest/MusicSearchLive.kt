package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** musicSearch2 · what YouTube Music suggested for [q]: text lines and rich results (cover + title + "Song · artist · plays"). */
class LiveSuggestions(val q: String, val texts: List<String>, val rich: List<YtmItem>)

/**
 * musicSearch2 · live suggestions for the text being typed: waits 150 ms after the last key, asks YouTube Music, and drops
 * the call of the earlier text (the coroutine is cancelled the moment [q] changes, so an old answer is never shown).
 * The list on screen stays until the new one is in (no flicker). [active] false (box empty, or a search was just made) = null.
 */
@Composable
fun rememberLiveSuggestions(q: String, active: Boolean): State<LiveSuggestions?> =
    produceState<LiveSuggestions?>(null, q, active) {
        val t = q.trim()
        if (!active || t.isEmpty()) { value = null; return@produceState }
        delay(150)
        val (texts, rich) = YtMusic.suggestionsMixed(t)
        value = LiveSuggestions(t, texts, rich)
    }

/**
 * musicSearch2 · the list under the search bar while typing, in YouTube Music's order: the first 2 text lines,
 * then the rich results, then the other text lines. Text line tap = full search, ↖ = put it in the box without searching.
 * Rich result tap = [onOpen]; ⋮ (songs and videos only, like the old list) = [onMenu].
 */
@Composable
fun MusicSuggestionList(
    s: LiveSuggestions,
    onSearch: (String) -> Unit,
    onFill: (String) -> Unit,
    onOpen: (YtmItem) -> Unit,
    onMenu: (YtmItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(s.texts.take(2), key = { "a:$it" }) { LiveTextRow(it, s.q, { onSearch(it) }, { onFill(it) }) }
        items(s.rich, key = { "r:" + it.kind.name + ":" + it.id }) { LiveRichRow(it, { onOpen(it) }, { onMenu(it) }) }
        items(s.texts.drop(2), key = { "b:$it" }) { LiveTextRow(it, s.q, { onSearch(it) }, { onFill(it) }) }
    }
}

@Composable
private fun LiveTextRow(text: String, typed: String, onTap: () -> Unit, onFill: () -> Unit) {
    val sub = Color(0xFFAAAAAA)
    // What you typed stays plain, the rest YouTube Music added is bold (as in YouTube Music).
    val label = remember(text, typed) {
        buildAnnotatedString {
            if (typed.isNotEmpty() && text.startsWith(typed, ignoreCase = true)) {
                append(text.substring(0, typed.length))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(typed.length)) }
            } else append(text)
        }
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onTap).padding(start = 16.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Search, null, Modifier.size(22.dp), tint = sub)
        Text(label, Modifier.weight(1f).padding(horizontal = 16.dp), color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onFill) { Icon(Icons.Default.NorthWest, "Put in the search box", Modifier.size(20.dp), tint = sub) }
    }
}

@Composable
private fun LiveRichRow(item: YtmItem, onTap: () -> Unit, onMenu: () -> Unit) {
    val sub = Color(0xFFAAAAAA)
    val round = item.kind == YtmKind.ARTIST
    val video = item.kind == YtmKind.VIDEO
    val menu = item.kind == YtmKind.SONG || item.kind == YtmKind.VIDEO
    Row(Modifier.fillMaxWidth().clickable(onClick = onTap).padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(if (video) 92.dp else 52.dp).height(52.dp)
            .clip(if (round) CircleShape else RoundedCornerShape(6.dp)).background(Color(0xFF212121))) {
            HdImage(item.thumb, null, Modifier.fillMaxSize(), wide = video, px = 300)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle.replace(" • ", " · "), color = sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (menu) IconButton(onMenu) { Icon(Icons.Default.MoreVert, "More", Modifier.size(22.dp), tint = Color.White) }
        else Spacer(Modifier.width(16.dp))
    }
}
