package com.example.streamnest

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
        val alerts = EpisodeAlerts.inbox(ctx).map { BellItem(it.at, it.show, it.text, it.poster) }
        val rec = recs.flatMap { r ->
            buildList {
                if (!r.out) add(BellItem(r.at, "${r.friendName} recommended ${r.name}", "In your room with ${r.friendName}", r.poster, metaId = r.id, type = r.type))
                if (r.out && r.fb != null) add(BellItem(r.at + 1, "${r.friendName} watched ${r.name}", "\"${r.fb}\"", r.poster, metaId = r.id, type = r.type))
            }
        }
        (alerts + rec).sortedByDescending { it.at }.take(40)
    }
    val unread = pending.isNotEmpty() || items.any { it.at > seen }
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
                    }.widthIn(max = 340.dp).fillMaxWidth(0.88f).heightIn(max = 460.dp).shadow(24.dp, shape).clip(shape)
                        .background(Color(0xF7181620)).border(1.dp, Brush.verticalGradient(listOf(Accent.copy(alpha = 0.7f), Accent.copy(alpha = 0.15f))), shape)
                        .padding(10.dp),
                ) {
                    Text("Notifications", Modifier.padding(start = 8.dp, top = 4.dp, bottom = 6.dp), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    if (pending.isNotEmpty()) Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Accent.copy(alpha = 0.22f)).clickable { open = false; onTogether() }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Groups, null, Modifier.size(20.dp))
                        Text("${pending.size} waiting for your answer in Watch together", Modifier.padding(start = 10.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    if (items.isEmpty() && pending.isEmpty()) Text("Nothing new. New episodes and friends' recommendations show up here.", Modifier.padding(10.dp), fontSize = 12.5.sp, color = Color(0xFF9AA3B5))
                    LazyColumn {
                        items(items) { b ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .clickable(enabled = b.metaId != null) { open = false; b.metaId?.let { onOpen(Meta(it, b.type, b.title.substringAfter(" recommended ").substringAfter(" watched "), b.image)) } }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AsyncImage(b.image, null, Modifier.size(36.dp, 52.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF22252E)), contentScale = ContentScale.Crop)
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(b.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                                    Text(b.text, fontSize = 11.5.sp, color = Color(0xFF9AA3B5), maxLines = 2)
                                    Text(SimpleDateFormat("d MMM · h:mm a", Locale.getDefault()).format(java.util.Date(b.at)), fontSize = 10.sp, color = Color(0xFF7C8496))
                                }
                                if (b.at > seen) Box(Modifier.size(7.dp).clip(CircleShape).background(Accent))
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
