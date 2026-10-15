package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * A Nuvio-style collection on TV: "COLLECTION" + title, a row of folder tabs ("All" first when [NCollection.showAllTab]),
 * and the selected folder's rows. Moving onto a tab selects it. BACK is handled by TvApp (pops this screen).
 */
@Composable
fun TvCollectionScreen(vm: AppViewModel, c: NCollection, start: NFolder, onBack: () -> Unit, onOpen: (Meta) -> Unit) {
    val tabs: List<NFolder?> = remember(c) { (if (c.showAllTab) listOf<NFolder?>(null) else emptyList()) + c.folders.distinctBy { it.id } }
    var sel by remember(c) { mutableIntStateOf(tabs.indexOfFirst { it?.id == start.id }.coerceAtLeast(0)) }
    val folder = tabs.getOrNull(sel)
    var rows by remember(c) { mutableStateOf<List<Shelf>?>(null) }
    val tabFr = remember { FocusRequester() }
    val tabState = rememberLazyListState(initialFirstVisibleItemIndex = sel)
    val firstSel = remember { sel }

    LaunchedEffect(sel) {
        val f = folder
        if (f == null) { rows = emptyList(); return@LaunchedEffect }
        rows = null
        delay(220) // moving across tabs quickly does not start a load for every tab
        rows = try { vm.folderRows(c, f) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        tabFr.tryFocus()
    }

    val bg = Bg
    val heroImg = folder?.hero ?: c.backdrop ?: folder?.cover
    val hero = tvImage(heroImg, 480.dp, 270.dp)
    val fade = remember(bg) { Brush.horizontalGradient(0.2f to bg, 0.55f to bg.copy(alpha = 0.7f), 1f to bg.copy(alpha = 0.2f)) }
    val fadeDown = remember(bg) { Brush.verticalGradient(0.2f to Color.Transparent, 0.7f to bg) }

    Box(Modifier.fillMaxSize().background(bg)) {
        if (hero != null) {
            AsyncImage(hero, null, Modifier.fillMaxWidth().fillMaxHeight(0.75f), contentScale = ContentScale.Crop, alpha = 0.5f)
            Box(Modifier.fillMaxSize().background(fade))
            Box(Modifier.fillMaxSize().background(fadeDown))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 34.dp, bottom = 70.dp)) {
            item(key = "head") {
                Column(Modifier.padding(start = TvEdge, end = TvEdge)) {
                    Text("COLLECTION", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = TvSubText)
                    Text(c.title, Modifier.padding(top = 3.dp), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item(key = "tabs") {
                TvRow(
                    tabs, key = { it?.id ?: "\u0000all" }, state = tabState, spacing = 7.dp,
                    restoreIndex = firstSel, restoreRequester = tabFr,
                ) { i, f, mod ->
                    TvTabChip(f?.title ?: "All", selected = i == sel, modifier = mod, onFocus = { if (it) sel = i }) { sel = i }
                }
            }
            if (folder == null) {
                // "All": every folder as a tile, five per line.
                items(tabs.filterNotNull().chunked(5), key = { line -> "all:" + line.first().id }) { line ->
                    Row(Modifier.padding(start = TvEdge, end = TvEdge, top = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        line.forEach { f ->
                            TvFolderTile(f) { sel = tabs.indexOf(f).coerceAtLeast(0) }
                        }
                    }
                }
            } else {
                val r = rows
                when {
                    r == null -> {
                        item(key = "load1") { TvLoadingRow(180.dp, 101.dp) }
                        item(key = "load2") { TvLoadingRow(180.dp, 101.dp) }
                    }
                    r.isEmpty() -> item(key = "empty") {
                        Text("Nothing to show in ${folder.title} right now.", Modifier.padding(start = TvEdge, top = 24.dp),
                            fontSize = 12.sp, color = TvSubText)
                    }
                    else -> items(r.withIndex().toList(), key = { (i, s) -> "${folder.id}:$i:${s.title}" }) { (_, s) ->
                        val wideArt = s.items.take(6).count { it.background != null || it.id.startsWith("tt") } >= 3
                        TvMetaRow(s.title, s.items, onOpen, backdrops = wideArt && !tvLandscapeRow(s.items))
                    }
                }
            }
        }
    }
}

private val ChipShape = RoundedCornerShape(15.dp)

@Composable
private fun TvTabChip(label: String, selected: Boolean, modifier: Modifier, onFocus: (Boolean) -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        selected -> Color(0x2EFFFFFF)
        else -> Color(0x14FFFFFF)
    }
    val fg = when {
        focused -> Color.Black
        selected -> Color.White
        else -> Color(0xFFC9CED9)
    }
    Box(
        modifier.tvCard(15.dp, scale = 1.05f, ring = false, onFocus = { focused = it; onFocus(it) }, onClick = onClick)
            .clip(ChipShape).background(bg).padding(horizontal = 13.dp, vertical = 7.dp),
        Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}
