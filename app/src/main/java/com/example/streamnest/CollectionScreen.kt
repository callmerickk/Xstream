package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch

private const val ALL_TAB = "__all__"

/** Home: one row of folder tiles per imported collection (shown right below Continue Watching). */
@Composable
fun CollectionsRows(vm: AppViewModel, onOpenFolder: (NCollection, NFolder) -> Unit) {
    val flow = remember(vm) { vm.collections() }
    val list by flow.collectAsStateWithLifecycle()
    if (list.isEmpty() || !isOn(t("showCollections"))) return
    Column {
        list.forEach { c ->
            Text(c.title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE6E9F2),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 12.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(c.folders, key = { i, f -> "$i:${f.id}" }, contentType = { _, _ -> "folder" }) { _, f ->
                    ColFolderTile(f, Modifier.width(200.dp).height(112.dp)) { onOpenFolder(c, f) }
                }
            }
        }
    }
}

/** A folder tile: cover art, and the title logo (or title) bottom-left unless the folder hides its title. */
@Composable
private fun ColFolderTile(f: NFolder, modifier: Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val corner = posterCorner().coerceAtLeast(10.dp)
    val shape = remember(corner) { RoundedCornerShape(corner) }
    val interaction = remember { MutableInteractionSource() }
    val img = f.cover ?: f.hero
    val req = remember(img) { ImageRequest.Builder(ctx).data(img).crossfade(true).build() }
    Box(modifier.clip(shape).background(Surface1).clickable(interactionSource = interaction, indication = null, onClick = onClick)) {
        if (img != null) AsyncImage(req, f.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (!f.hideTitle || img == null) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xCC000000))))
            Box(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                if (f.logo != null && img != null) AsyncImage(f.logo, f.title, Modifier.height(30.dp).widthIn(max = 120.dp), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart)
                else Text(f.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Phone: a collection opened on one folder — folder tabs on top, the folder's rows below. */
@Composable
fun CollectionScreen(vm: AppViewModel, collectionId: String, folderId: String, onBack: () -> Unit, onOpen: (Meta) -> Unit) {
    val flow = remember(vm) { vm.allCollections() }
    val all by flow.collectAsStateWithLifecycle()
    val c = all.firstOrNull { it.id == collectionId }
    val scope = rememberCoroutineScope()
    var sel by rememberSaveable(collectionId) { mutableStateOf(folderId) }
    var opening by remember { mutableStateOf(false) }
    val openTitle: (Meta) -> Unit = { m ->
        if (!opening) {
            opening = true
            scope.launch {
                val r = vm.resolveForOpen(m)
                opening = false
                if (r != null) onOpen(r) else Snack.show(if (vm.tmdbKey().isEmpty()) "Add a TMDB key in Integrations to open this title" else "Couldn't open this title")
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(c?.title ?: "Collection", fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (c == null) {
            if (all.isNotEmpty()) ColNote("This collection isn't available anymore.")
            else Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Accent) }
            return@Column
        }
        val folder = c.folders.firstOrNull { it.id == sel }

        // Folder tabs
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c.showAllTab) ColChip("All", folder == null) { sel = ALL_TAB }
            c.folders.forEach { f -> ColChip(f.title, f.id == sel) { sel = f.id } }
        }

        if (folder == null) {
            LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(c.folders, key = { i, f -> "$i:${f.id}" }) { _, f ->
                    ColFolderTile(f, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { sel = f.id }
                }
            }
        } else {
            ColFolderRows(vm, c, folder, openTitle)
        }
    }
}

@Composable
private fun ColFolderRows(vm: AppViewModel, c: NCollection, f: NFolder, onOpen: (Meta) -> Unit) {
    var rows by remember(c.id, f.id) { mutableStateOf<List<Shelf>?>(null) }
    LaunchedEffect(c.id, f.id) { rows = runCatching { vm.folderRows(c, f) }.getOrDefault(emptyList()) }
    val r = rows
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item(key = "hero", contentType = "hero") { ColFolderHero(f) }
        when {
            r == null -> item(key = "loading", contentType = "loading") {
                Box(Modifier.fillMaxWidth().padding(top = 60.dp), Alignment.Center) { CircularProgressIndicator(color = Accent) }
            }
            r.isEmpty() -> item(key = "empty", contentType = "empty") {
                ColNote(buildString {
                    append("Nothing to show here yet.")
                    if (vm.tmdbKey().isEmpty()) append("\nAdd a TMDB key in Help & Settings > Integrations for TMDB lists.")
                    if ((vm.texts.value["traktClientId"] ?: "").isBlank()) append("\nAdd a Trakt client ID there for Trakt lists.")
                    append("\nAddon rows need that addon installed.")
                })
            }
            else -> itemsIndexed(r, key = { i, s -> "row:$i:${s.title}" }, contentType = { _, _ -> "row" }) { _, s ->
                Text(s.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE6E9F2),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp))
                ShelfRow(s.items, onOpen)
            }
        }
    }
}

@Composable
private fun ColFolderHero(f: NFolder) {
    val ctx = LocalContext.current
    val img = f.hero ?: f.cover
    if (img == null) { Spacer(Modifier.height(4.dp)); return }
    val req = remember(img) { ImageRequest.Builder(ctx).data(img).crossfade(true).build() }
    val corner: Dp = posterCorner().coerceAtLeast(10.dp)
    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().aspectRatio(16f / 7f).clip(RoundedCornerShape(corner)).background(Surface1)) {
        AsyncImage(req, f.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xD9000000))))
        Box(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            if (f.logo != null) AsyncImage(f.logo, f.title, Modifier.height(44.dp).widthIn(max = 180.dp), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart)
            else Text(f.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ColChip(label: String, on: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Text(label, Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) Accent else Color(0xFF22232A))
        .clickable(interactionSource = interaction, indication = null, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) OnAccent else Color.White, maxLines = 1, softWrap = false)
}

@Composable
private fun ColNote(text: String) =
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), fontSize = 15.sp, color = Color(0xFF9AA3B5), textAlign = TextAlign.Center)
