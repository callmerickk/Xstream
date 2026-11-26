package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ArBg = Color(0xFF030303)
private val ArSub = Color(0xFFAAAAAA)
private val ArChip = Color(0xFF212121)

private enum class ArSec { TOP, ALBUMS, SINGLES, VIDEOS, FANS, OTHER }

/** Sorts a shelf into a section by its items' kinds first, then by its title. */
private fun secOf(sh: YtmShelf): ArSec {
    val t = sh.title.orEmpty().lowercase()
    val k = sh.items.map { it.kind }.toSet()
    return when {
        k.isNotEmpty() && k.all { it == YtmKind.ARTIST } -> ArSec.FANS
        t.contains("fans") || t.contains("similar") -> ArSec.FANS
        t.contains("video") || (k.isNotEmpty() && k.all { it == YtmKind.VIDEO }) -> ArSec.VIDEOS
        t.contains("single") || (k.isNotEmpty() && k.all { it == YtmKind.SINGLE }) -> ArSec.SINGLES
        t.contains("album") || (k.isNotEmpty() && k.all { it == YtmKind.ALBUM }) -> ArSec.ALBUMS
        t.contains("song") && k.all { it == YtmKind.SONG || it == YtmKind.VIDEO } -> ArSec.TOP
        else -> ArSec.OTHER
    }
}

@Composable
fun MusicArtistPage(
    page: YtmPage,
    fallbackTitle: String,
    fallbackThumb: String?,
    onBack: () -> Unit,
    onPlay: (YtmItem, List<YtmItem>) -> Unit,
    onRadio: (YtmItem) -> Unit,
    onOpen: (YtmItem) -> Unit,
    onMenu: (YtmItem) -> Unit,
    subscribed: Boolean,
    onSubscribe: () -> Unit,
    currentId: String? = null,
    modifier: Modifier = Modifier,
) {
    val head = page.header
    val name = head?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
    val cover = head?.thumb ?: fallbackThumb
    val groups = remember(page) { page.shelves.filter { it.items.isNotEmpty() }.groupBy { secOf(it) } }
    val top = remember(groups) { groups[ArSec.TOP].orEmpty().flatMap { it.items }.distinctBy { it.id } }
    // Songs used by Shuffle / Radio: top songs, or any song on the page.
    val playable = remember(page) {
        top.ifEmpty {
            (page.items + page.shelves.flatMap { it.items })
                .filter { it.kind == YtmKind.SONG || it.kind == YtmKind.VIDEO }
                .distinctBy { it.id }
        }
    }
    var showAll by remember(page) { mutableStateOf(false) }

    LaunchedEffect(page) {
        MusicLog.add(
            "Artist page: top songs ${top.size}, " +
                "albums ${groups[ArSec.ALBUMS].orEmpty().sumOf { it.items.size }}, " +
                "singles ${groups[ArSec.SINGLES].orEmpty().sumOf { it.items.size }}, " +
                "videos ${groups[ArSec.VIDEOS].orEmpty().sumOf { it.items.size }}, " +
                "fans also like ${groups[ArSec.FANS].orEmpty().sumOf { it.items.size }}, " +
                "other shelves ${groups[ArSec.OTHER].orEmpty().size}"
        )
    }

    LazyColumn(modifier.fillMaxSize().background(ArBg)) {
        // Header image with a fade into the page colour, back arrow, name.
        item {
            Box(Modifier.fillMaxWidth().height(330.dp).background(ArChip)) {
                HdImage(url = cover.orEmpty(), description = name, modifier = Modifier.fillMaxSize(), wide = true)
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent, ArBg))
                    )
                )
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 4.dp)) {
                    Icon(Icons.Rounded.ArrowBack, "Back", tint = Color.White)
                }
                Text(
                    name, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                )
            }
        }
        // Subscribe row.
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    val sub = head?.subtitle.orEmpty()
                    if (sub.isNotBlank()) {
                        Text(sub, color = ArSub, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(
                    if (subscribed) "Subscribed" else "Subscribe",
                    color = if (subscribed) Color.White else Color.Black,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .then(
                            if (subscribed) Modifier.border(1.dp, Color(0xFF535353), RoundedCornerShape(50))
                            else Modifier.background(Color.White)
                        )
                        .clickable(onClick = onSubscribe)
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        }
        // Shuffle / Radio.
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PillBtn(Icons.Rounded.Shuffle, "Shuffle", filled = true, modifier = Modifier.weight(1f)) {
                    if (playable.isEmpty()) {
                        MusicLog.add("Artist page: Shuffle tapped but there are no songs")
                    } else {
                        val sh = playable.shuffled()
                        MusicLog.add("Artist page: shuffle ${sh.size} songs of $name")
                        onPlay(sh.first(), sh)
                    }
                }
                PillBtn(Icons.Rounded.Radio, "Radio", filled = false, modifier = Modifier.weight(1f)) {
                    if (playable.isEmpty()) {
                        MusicLog.add("Artist page: Radio tapped but there is no seed song")
                    } else {
                        MusicLog.add("Artist page: radio from '${playable.first().title}'")
                        onRadio(playable.first())
                    }
                }
            }
        }
        // Top songs (5, then "Show all").
        if (top.isNotEmpty()) {
            item { ArTitle("Top songs") }
            val shown = if (showAll) top else top.take(5)
            items(shown, key = { "top_" + it.id }) { s ->
                ArtistSongRow(s, s.id == currentId, onClick = { onPlay(s, top) }, onMenu = { onMenu(s) })
            }
            if (top.size > 5) {
                item {
                    Text(
                        if (showAll) "Show less" else "Show all",
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(start = 20.dp, top = 6.dp, bottom = 6.dp)
                            .clip(RoundedCornerShape(50))
                            .border(1.dp, Color(0xFF535353), RoundedCornerShape(50))
                            .clickable { showAll = !showAll }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
        // Albums, Singles, Videos, Fans also like, then anything else in the answer's order.
        for (sec in listOf(ArSec.ALBUMS, ArSec.SINGLES, ArSec.VIDEOS, ArSec.FANS, ArSec.OTHER)) {
            val shelves = groups[sec].orEmpty()
            items(shelves, key = { "sec_${sec.name}_" + it.title.orEmpty() + it.items.first().id }) { sh ->
                val label = sh.title.orEmpty().ifBlank {
                    when (sec) {
                        ArSec.ALBUMS -> "Albums"
                        ArSec.SINGLES -> "Singles"
                        ArSec.VIDEOS -> "Videos"
                        ArSec.FANS -> "Fans also like"
                        else -> ""
                    }
                }
                Column(Modifier.padding(top = 14.dp)) {
                    if (label.isNotBlank()) ArTitle(label)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(sh.items, key = { it.kind.name + it.id }) { it2 ->
                            ArtistCard(it2, wide = sec == ArSec.VIDEOS, onOpen = { onOpen(it2) }, onMenu = { onMenu(it2) })
                        }
                    }
                }
            }
        }
        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ArTitle(text: String) {
    Text(
        text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun PillBtn(icon: ImageVector, text: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) Color.White else ArChip)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, text, tint = if (filled) Color.Black else Color.White, modifier = Modifier.size(20.dp))
        Text(
            text, color = if (filled) Color.Black else Color.White,
            fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ArtistSongRow(s: YtmItem, now: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(ArChip)) {
            HdImage(url = s.thumb.orEmpty(), description = s.title, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                s.title, color = Color.White, fontSize = 16.sp,
                fontWeight = if (now) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val line = trackLine(s)  // from MusicAlbumPage.kt: "artist · 4:14 · 396M plays"
            if (line.isNotBlank()) Text(line, color = ArSub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onMenu) { Icon(Icons.Rounded.MoreVert, "More", tint = ArSub) }
    }
}

@Composable
private fun ArtistCard(item: YtmItem, wide: Boolean, onOpen: () -> Unit, onMenu: () -> Unit) {
    val w = if (wide) 220.dp else 140.dp
    val shape = if (item.kind == YtmKind.ARTIST) CircleShape else RoundedCornerShape(6.dp)
    Column(Modifier.width(w).clickable(onClick = onOpen)) {
        Box(
            Modifier.width(w).then(if (wide) Modifier.aspectRatio(16f / 9f) else Modifier.size(w))
                .clip(shape).background(ArChip)
        ) {
            HdImage(url = item.thumb.orEmpty(), description = item.title, modifier = Modifier.fillMaxSize(), wide = wide)
        }
        Text(
            item.title, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = if (item.kind == YtmKind.ARTIST) androidx.compose.ui.text.style.TextAlign.Center
            else androidx.compose.ui.text.style.TextAlign.Start,
            modifier = Modifier.padding(top = 6.dp).then(if (item.kind == YtmKind.ARTIST) Modifier.fillMaxWidth() else Modifier),
        )
        if (!item.subtitle.isNullOrBlank() && item.kind != YtmKind.ARTIST) {
            Text(item.subtitle.orEmpty(), color = ArSub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
