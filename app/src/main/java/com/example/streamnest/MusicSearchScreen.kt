package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val chipOrder = listOf("Songs", "Videos", "Albums", "Artists", "Playlists", "Community playlists", "Podcasts")

/**
 * musicSearch2 · the whole search page. [onOpen] plays a song / video or opens an album, artist or playlist (give it the
 * item and the list it came from); [onMenu] opens the ⋮ menu of a song. The caller adds the status-bar padding in
 * [modifier] if its other pages have it. Back = [onBack] (the arrow in the bar).
 */
@Composable
fun MusicSearchScreen(
    onBack: () -> Unit,
    onOpen: (YtmItem, List<YtmItem>) -> Unit,
    onMenu: (YtmItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var q by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var chip by rememberSaveable { mutableStateOf<String?>(null) }          // code of the chip picked; null = All
    var chips by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var page by remember { mutableStateOf<YtmPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var ideas by remember { mutableStateOf<List<String>>(emptyList()) }
    val typed = q.trim()
    val live by rememberLiveSuggestions(q, typed.isNotEmpty() && typed != submitted)

    LaunchedEffect(Unit) { delay(150); if (submitted == null) runCatching { focus.requestFocus() } }
    LaunchedEffect(Unit) { ideas = MusicSearchIdeas.load() }
    // Back on the results goes back to typing, then leaves the page.
    androidx.activity.compose.BackHandler(enabled = submitted != null) { submitted = null }

    fun go(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        q = t; submitted = t; chip = null; chips = emptyList(); keyboard?.hide()
        CoroutineScope(Dispatchers.Main).launch { MusicLibraryStore.addRecentSearch(ctx, t) }
    }
    fun open(item: YtmItem, from: List<YtmItem>) {
        keyboard?.hide()
        CoroutineScope(Dispatchers.Main).launch { MusicLibraryStore.addRecentItem(ctx, item) }
        onOpen(item, from)
    }

    LaunchedEffect(submitted, chip) {
        val s = submitted ?: run { page = null; return@LaunchedEffect }
        loading = true; page = null
        val p = YtMusic.searchFull(s, chip)
        if (chip == null) {
            chips = p.chips
            if (p.chips.isEmpty()) MusicLog.add("search \"${s.take(40)}\": the answer had no chips, the chip row stays hidden")
        }
        page = p; loading = false
    }

    Column(modifier.fillMaxSize().background(Color(0xFF030303))) {
        MusicSearchBar(q, { q = it }, { go(it) }, onBack, focus)
        when {
            typed.isEmpty() -> {
                val d = MusicLibraryStore.active()
                MusicSearchEmpty(d.recentItems, d.recentSearches, ideas, { open(it, listOf(it)) }, { go(it) }, { q = it })
            }
            typed != submitted -> live?.let { s ->
                MusicSuggestionList(s, { go(it) }, { q = it }, { open(it, listOf(it)) }, onMenu)
            }
            else -> {
                val shown = chipOrder.mapNotNull { w ->
                    chips.firstOrNull { (if (it.first.equals("Featured playlists", true)) "Playlists" else it.first).equals(w, true) }?.let { w to it.second }
                }
                if (shown.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "all") { ChipPill("All", chip == null) { chip = null } }
                    items(shown, key = { it.first }) { (label, code) -> ChipPill(label, chip == code) { chip = code } }
                }
                val p = page
                if (loading || p == null) Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator(color = Color.White) }
                else ResultsList(p, chip == null, ::open, onMenu, typed)
            }
        }
    }
}

@Composable
private fun ChipPill(label: String, on: Boolean, onTap: () -> Unit) {
    Text(label, Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Color.White else Color(0x22FFFFFF)).clickable(onClick = onTap)
        .padding(horizontal = 14.dp, vertical = 8.dp), color = if (on) Color(0xFF111111) else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
}

@Composable
private fun ResultsList(p: YtmPage, all: Boolean, onOpen: (YtmItem, List<YtmItem>) -> Unit, onMenu: (YtmItem) -> Unit, typed: String) {
    val top = if (all) p.header else null
    val sections = if (all) p.shelves else emptyList()
    val flat = if (sections.isEmpty()) p.items else emptyList()
    if (top == null && sections.isEmpty() && flat.isEmpty()) {
        Text("Nothing found for “$typed”", Modifier.padding(16.dp), color = Color(0xFFAAAAAA)); return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (top != null) item(key = "top") { TopResultCard(top) { onOpen(top, listOf(top)) } }
        sections.forEach { sh ->
            item(key = "h:" + sh.title) {
                Text(sh.title, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            items(sh.items, key = { sh.title + ":" + it.kind.name + ":" + it.id }) { ResultRow(it, { onOpen(it, sh.items) }, { onMenu(it) }) }
        }
        items(flat, key = { it.kind.name + ":" + it.id }) { ResultRow(it, { onOpen(it, flat) }, { onMenu(it) }) }
    }
}

/** The "Top result" card: big cover (round for an artist), name, and "Song · artist · plays" / "Artist · subscribers". */
@Composable
private fun TopResultCard(item: YtmItem, onTap: () -> Unit) {
    val round = item.kind == YtmKind.ARTIST
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Top result", Modifier.padding(bottom = 8.dp), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1D1D1D)).clickable(onClick = onTap).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp).clip(if (round) CircleShape else RoundedCornerShape(8.dp)).background(Color(0xFF212121))) {
                HdImage(item.thumb, item.title, Modifier.fillMaxSize(), wide = item.kind == YtmKind.VIDEO, px = 500)
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(item.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.subtitle.replace(" • ", " · "), Modifier.padding(top = 4.dp), color = Color(0xFFAAAAAA), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ResultRow(item: YtmItem, onTap: () -> Unit, onMenu: () -> Unit) {
    val round = item.kind == YtmKind.ARTIST
    val video = item.kind == YtmKind.VIDEO
    val menu = item.kind == YtmKind.SONG || item.kind == YtmKind.VIDEO
    Row(Modifier.fillMaxWidth().clickable(onClick = onTap).padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(if (video) 92.dp else 52.dp).height(52.dp).clip(if (round) CircleShape else RoundedCornerShape(6.dp)).background(Color(0xFF212121))) {
            HdImage(item.thumb, null, Modifier.fillMaxSize(), wide = video, px = 300)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle.replace(" • ", " · "), color = Color(0xFFAAAAAA), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (menu) IconButton(onMenu) { Icon(Icons.Default.MoreVert, "More", Modifier.size(22.dp), tint = Color.White) }
        else Spacer(Modifier.width(16.dp))
    }
}
