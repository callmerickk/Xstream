package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** One row in the long-press menu. */
data class TitleAction(val label: String, val icon: ImageVector, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * Long-press menu for a title (Continue Watching card or the Play button):
 * artwork card with time left and progress, the title, then Go to details / Play manually / Start from beginning / Remove.
 */
@Composable
fun TitleActionsSheet(
    title: String, subtitle: String, image: String?, frac: Float, left: String?,
    actions: List<TitleAction>, onDismiss: () -> Unit,
) = PremiumDialog(onDismiss, bare = true) { close ->
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(22.dp)).background(Surface1)) {
        AsyncImage(image, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xD9000000))))
        left?.let {
            Text(it, Modifier.align(Alignment.TopEnd).padding(12.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xB3000000))
                .padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(title, Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 18.dp), fontSize = 19.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (frac > 0f) Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Color.White))
        }
    }
    Text(title, Modifier.fillMaxWidth().padding(top = 14.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
    Text(subtitle, Modifier.fillMaxWidth().padding(top = 2.dp), fontSize = 15.sp, color = Color(0xFF9AA3B5), textAlign = TextAlign.Center)
    Column(Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xF21A1A1E))) {
        actions.forEachIndexed { i, a ->
            if (i > 0) HorizontalDivider(color = Color(0x14FFFFFF))
            Row(
                Modifier.fillMaxWidth().clickable { close(); a.onClick() }.padding(horizontal = 20.dp, vertical = 17.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val c = if (a.danger) Color(0xFFFF6B8B) else Color.White
                Text(a.label, Modifier.weight(1f), fontSize = 17.sp, color = c)
                Icon(a.icon, null, Modifier.size(24.dp), tint = c)
            }
        }
    }
}

fun timeLeft(p: Progress): String? {
    if (p.durationMs <= 0) return null
    val m = ((p.durationMs - p.positionMs) / 60000).toInt().coerceAtLeast(1)
    return if (m >= 60) "${m / 60}h ${m % 60}m left" else "${m}m left"
}

/** Long-press menu for a Continue Watching item. */
@Composable
fun ContinueActions(p: Progress, onDetails: () -> Unit, onManual: () -> Unit, onStartOver: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val series = p.key.contains(":")
    TitleActionsSheet(
        p.title.substringBefore("  S"), if (series) p.title.substringAfter("  ", "Series").ifBlank { "Series" } else "Movie", p.image,
        if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f, timeLeft(p),
        listOf(
            TitleAction("Go to details", Icons.Default.Info, onClick = onDetails),
            TitleAction("Play manually", Icons.Default.PlayArrow, onClick = onManual),
            TitleAction("Start from beginning", Icons.Default.Replay, onClick = onStartOver),
            TitleAction("Remove", Icons.Default.DeleteOutline, danger = true, onClick = onRemove),
        ),
        onDismiss,
    )
}

/** Meta to open a title page from a Continue Watching item. */
fun Progress.toMeta() = Meta(
    id = key.substringBefore(":"), type = if (key.contains(":")) "series" else "movie",
    name = title.substringBefore("  S"), background = image, poster = image,
)
