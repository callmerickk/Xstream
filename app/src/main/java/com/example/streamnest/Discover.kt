package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/*
 * Search › Discover, laid out like Instagram's Explore: blocks of one big rounded tall poster beside two small
 * landscape cards, the big one switching sides every block. Used for the Discover page and for search results (phone).
 */

private val BigShape = RoundedCornerShape(22.dp)
private val SmallShape = RoundedCornerShape(18.dp)
private const val BLOCK_H = 268

/** Adds the mosaic to a LazyColumn: every 3 titles make one block. [extra] = optional line under each title (year…). */
fun LazyListScope.discoverMosaic(items: List<Meta>, keyPrefix: String, wide: (Meta) -> String? = { null }, onOpen: (Meta) -> Unit) {
    val blocks = items.chunked(3)
    blocks.forEachIndexed { bi, block ->
        item(key = "$keyPrefix:${bi}:${block.first().type}${block.first().id}", contentType = "mosaic") {
            MosaicBlock(block, flip = bi % 2 == 1, wide = wide, onOpen = onOpen)
        }
    }
}

@Composable
private fun MosaicBlock(block: List<Meta>, flip: Boolean, wide: (Meta) -> String?, onOpen: (Meta) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).height(BLOCK_H.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val big = block[0]
        if (!flip) BigTile(big, Modifier.weight(1f), onOpen)
        Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            block.drop(1).forEach { m -> SmallTile(m, wide(m), Modifier.weight(1f), onOpen) }
            if (block.size < 3) Box(Modifier.weight((3 - block.size).toFloat()))
        }
        if (flip) BigTile(big, Modifier.weight(1f), onOpen)
    }
}

@Composable
private fun BigTile(m: Meta, modifier: Modifier, onOpen: (Meta) -> Unit) {
    Box(modifier.fillMaxSize().clip(BigShape).border(1.dp, Color(0x1FFFFFFF), BigShape).titleTap(m) { onOpen(m) }) {
        TallPoster(m, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.62f to Color.Transparent, 1f to Color(0xCC000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(m.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(m.releaseInfo?.take(4), if (m.type == "series") "Series" else "Movie").joinToString(" · ")
            Text(sub, fontSize = 11.sp, color = Color(0xFFC4C9D4), maxLines = 1)
        }
    }
}

@Composable
private fun SmallTile(m: Meta, wideUrl: String?, modifier: Modifier, onOpen: (Meta) -> Unit) {
    var failed by remember(m.id) { mutableStateOf(false) }
    val url = if (!failed) wideUrl ?: hiResArt(backdropOf(m)) else null
    Box(modifier.fillMaxWidth().clip(SmallShape).background(Surface1).border(1.dp, Color(0x1FFFFFFF), SmallShape).titleTap(m) { onOpen(m) }) {
        if (url != null) AsyncImage(url, m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { failed = true })
        else TallPoster(m, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD9000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(m.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(m.releaseInfo?.take(4), if (m.type == "series") "Series" else "Movie").joinToString(" · ")
            Text(sub, fontSize = 11.sp, color = Color(0xFFC4C9D4), maxLines = 1)
        }
    }
}
