package com.example.streamnest

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Locale

/** One line in the bell: a new episode, a friend's recommendation or feedback, or a Watch together request. */
private data class BellItem(val at: Long, val title: String, val text: String, val image: String?, val together: Boolean = false, val metaId: String? = null, val type: String = "movie")

private fun seenAt(ctx: Context) = ctx.getSharedPreferences("bell", Context.MODE_PRIVATE).getLong("seen", 0L)
private fun markSeen(ctx: Context) = ctx.getSharedPreferences("bell", Context.MODE_PRIVATE).edit().putLong("seen", System.currentTimeMillis()).apply()

/**
 * Xstream: the notification bell (top right of Home). A small red dot when something new came in; tap it and a bubble
 * grows out of the bell with everything, newest first.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun NotificationBell(onTogether: () -> Unit, onOpen: (Meta) -> Unit) {
    val ctx = LocalContext.current
    val recs by Recs.all.collectAsStateWithLifecycle()
    val pending by Social.pending.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var seen by remember { mutableLongStateOf(seenAt(ctx)) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { Recs.init(ctx); while (true) { kotlinx.coroutines.delay(60_000); tick++ } }
    val items = remember(recs, pending, tick, open) {
        val alerts = EpisodeAlerts.inbox(ctx).map { BellItem(it.at, it.show, it.text, it.poster, metaId = it.showId, type = "series") }
        val rec = recs.flatMap { r ->
            buildList {
                if (!r.out) add(BellItem(r.at, "${r.friendName} recommended ${r.name}", "In your room with ${r.friendName}", r.poster, metaId = r.id, type = r.type))
                if (r.out && r.fb != null) add(BellItem(r.at + 1, "${r.friendName} watched ${r.name}", "\"${r.fb}\"", r.poster, metaId = r.id, type = r.type))
            }
        }
        (alerts + rec).sortedByDescending { it.at }.take(40)
    }
    // Removed one by one (swipe) or all at once (Clear all): kept with your settings, so they stay gone everywhere.
    val texts = Public.vm?.texts?.collectAsStateWithLifecycle()?.value.orEmpty()
    val clearedAt = texts["bellCleared"]?.toLongOrNull() ?: 0L
    val gone = remember(texts["bellGone"]) { texts["bellGone"].orEmpty().split("|").filter { it.isNotBlank() }.toSet() }
    fun idOf(b: BellItem) = "${b.at}:${b.title.hashCode()}"
    fun dismiss(b: BellItem) { Public.vm?.setText("bellGone", (gone + idOf(b)).toList().takeLast(120).joinToString("|")) }
    fun clearAll() { Public.vm?.setText("bellCleared", System.currentTimeMillis().toString()); Public.vm?.setText("bellGone", "") }
    val shown = items.filter { it.at > clearedAt && idOf(it) !in gone }
    val unread = pending.isNotEmpty() || shown.any { it.at > seen }
    // Something new arrived while the app is open: a banner drops from the top for a few seconds.
    var banner by remember { mutableStateOf<BellItem?>(null) }
    var announced by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(shown.firstOrNull()?.at) {
        val top = shown.firstOrNull() ?: return@LaunchedEffect
        if (top.at > announced && top.at > seen) { announced = top.at; banner = top; kotlinx.coroutines.delay(4000); banner = null }
    }
    banner?.let { b ->
        Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, 40), onDismissRequest = { banner = null }) {
            Row(Modifier.padding(horizontal = 16.dp).shadow(12.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(Color(0xF21A1C24))
                .border(1.dp, Color(0x66E50914), RoundedCornerShape(18.dp)).clickable { banner = null; open = true; markSeen(ctx) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Notifications, null, Modifier.size(18.dp), tint = Accent)
                Spacer(Modifier.width(10.dp))
                Column { Text("New notification", fontSize = 11.sp, color = Color(0xFF9AA3B5)); Text(b.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
            }
        }
    }
    Box {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(Color(0x99101420)).border(1.dp, Color(0x33FFFFFF), CircleShape)
                .clickable { open = !open; if (open) { markSeen(ctx) } else seen = seenAt(ctx) },
            Alignment.Center,
        ) {
            Icon(Icons.Default.Notifications, "Notifications", Modifier.size(21.dp), tint = Color.White)
            if (unread) Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 9.dp).size(9.dp).clip(CircleShape).background(Accent).border(1.5.dp, Color.Black, CircleShape))
        }
        if (open) {
            val below = remember {
                object : PopupPositionProvider {
                    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                        IntOffset((anchorBounds.right - popupContentSize.width).coerceAtLeast(16), anchorBounds.bottom + 12)
                }
            }
            Popup(popupPositionProvider = below, onDismissRequest = { open = false; seen = seenAt(ctx) }, properties = PopupProperties(focusable = true)) {
                val pop = remember { Animatable(0f) }
                LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 420f)) }
                val shape = RoundedCornerShape(24.dp)
                Column(
                    Modifier.graphicsLayer {
                        alpha = pop.value.coerceIn(0f, 1f); scaleX = 0.3f + 0.7f * pop.value; scaleY = scaleX
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.95f, 0f)
                    }.widthIn(max = 300.dp).fillMaxWidth(0.8f)
                        // Small by default (it fits what's there); with lots of messages it grows up to the full size.
                        .heightIn(max = if (shown.size + (if (pending.isNotEmpty()) 1 else 0) > 4) 460.dp else 330.dp).shadow(24.dp, shape).clip(shape)
                        .background(Color(0xF7181620)).border(1.dp, Brush.verticalGradient(listOf(Accent.copy(alpha = 0.7f), Accent.copy(alpha = 0.15f))), shape)
                        .padding(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Notifications", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                        if (shown.isNotEmpty()) Text("Clear all", Modifier.clip(RoundedCornerShape(50)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50))
                            .clickable { clearAll() }.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                    if (pending.isNotEmpty()) Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Accent.copy(alpha = 0.22f)).clickable { open = false; onTogether() }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Groups, null, Modifier.size(20.dp))
                        Text("${pending.size} waiting for your answer in Watch together", Modifier.padding(start = 10.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    if (shown.isEmpty() && pending.isEmpty()) Text("Nothing new. New episodes and friends' recommendations show up here.", Modifier.padding(10.dp), fontSize = 12.5.sp, color = Color(0xFF9AA3B5))
                    LazyColumn {
                        items(shown, key = { idOf(it) }) { b ->
                            // Swipe left to remove · tap to open · long-press for Go to details.
                            val dx = remember { Animatable(0f) }
                            val scope = rememberCoroutineScope()
                            var menu by remember { mutableStateOf(false) }
                            val openIt = { open = false; b.metaId?.let { onOpen(Meta(it, b.type, b.title.substringAfter(" recommended ").substringAfter(" watched "), b.image)) }; Unit }
                            Box(Modifier.fillMaxWidth().animateItem()) {
                                Text("Remove", Modifier.align(Alignment.CenterEnd).padding(end = 14.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFB3B8))
                                Row(
                                    Modifier.fillMaxWidth().offset { IntOffset(dx.value.toInt(), 0) }.clip(RoundedCornerShape(14.dp)).background(Color(0xFF1B1922))
                                        .pointerInput(b) {
                                            detectHorizontalDragGestures(
                                                onDragEnd = { scope.launch { if (dx.value < -size.width * 0.35f) { dx.animateTo(-size.width.toFloat(), androidx.compose.animation.core.tween(160)); dismiss(b) } else dx.animateTo(0f) } },
                                                onDragCancel = { scope.launch { dx.animateTo(0f) } },
                                            ) { ch: androidx.compose.ui.input.pointer.PointerInputChange, d: Float -> ch.consume(); scope.launch { dx.snapTo((dx.value + d).coerceAtMost(0f)) } }
                                        }
                                        .combinedClickable(onClick = { openIt() }, onLongClick = { menu = true })
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AsyncImage(b.image, null, Modifier.size(32.dp, 46.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF22252E)), contentScale = ContentScale.Crop)
                                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                        Text(b.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                                        Text(b.text, fontSize = 11.5.sp, color = Color(0xFF9AA3B5), maxLines = 2)
                                        Text(SimpleDateFormat("d MMM · h:mm a", Locale.getDefault()).format(java.util.Date(b.at)), fontSize = 10.sp, color = Color(0xFF7C8496))
                                    }
                                    if (b.at > seen) Box(Modifier.size(7.dp).clip(CircleShape).background(Accent))
                                }
                                androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                                    if (b.metaId != null) androidx.compose.material3.DropdownMenuItem(text = { Text("Go to details") }, onClick = { menu = false; openIt() })
                                    androidx.compose.material3.DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; dismiss(b) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The bell asks App to open Watch together. */
object BellNav { val together = kotlinx.coroutines.flow.MutableStateFlow(false) }
