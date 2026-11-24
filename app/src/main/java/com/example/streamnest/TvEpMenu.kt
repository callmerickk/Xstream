package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TV 0.9.10: hold OK on an episode. The release after the hold doesn't also click.
 * Put it BEFORE .clickable so the key events are seen first.
 */
fun Modifier.okLongPress(onLong: () -> Unit): Modifier = composed {
    val fired = remember { mutableStateOf(false) }
    val cb = rememberUpdatedState(onLong)
    onPreviewKeyEvent { e ->
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        when {
            ok && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 -> { if (!fired.value) { fired.value = true; cb.value() }; true }
            ok && e.type == KeyEventType.KeyUp && fired.value -> { fired.value = false; true }
            else -> false
        }
    }
}

/** TV 0.9.10: set by the TV shell while a player is open. */
object TvEpNav { @Volatile var manual: ((Meta, String) -> Unit)? = null }

/** Free space for downloads (bytes). */
fun dlFreeBytes(ctx: android.content.Context): Long = runCatching {
    val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
    android.os.StatFs(dir.absolutePath).availableBytes
}.getOrDefault(0L)

/** TV 0.9.10: an episode's menu (player Episodes and the title page): Play, Play manually, Download, Mark watched / unwatched. */
@Composable
fun TvEpisodeMenu(
    ep: Video, showName: String, watched: Boolean, started: Boolean, onDismiss: () -> Unit,
    onPlay: () -> Unit, onManual: () -> Unit, onDownload: () -> Unit, onMark: (Boolean) -> Unit,
) = PremiumSheet(onDismiss) { close ->
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(8) { if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect; delay(60) } }
    // TV 0.9.11: the menu opens while OK is still held: that hold and its release are ignored (they used to press Play);
    // only a new press acts.
    var armed by remember { mutableStateOf(false) }
    Column(Modifier.onPreviewKeyEvent { e ->
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        when {
            !ok || armed -> false
            e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> { armed = true; false }
            e.type == KeyEventType.KeyUp -> { armed = true; true }
            else -> true
        }
    }) {
    Text("S${ep.season} E${ep.episode} · ${ep.title ?: ep.name ?: "Episode ${ep.episode}"}", fontSize = 16.sp, fontWeight = FontWeight.Bold,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (showName.isNotBlank()) Text(showName, fontSize = 12.sp, color = Color(0xFF9AA3B5), maxLines = 1)
    Spacer(Modifier.height(10.dp))
    EpMenuItem("Play", Icons.Default.PlayArrow, first) { close(); onPlay() }
    EpMenuItem("Play manually (choose source)", Icons.Default.List) { close(); onManual() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dl = remember(ep.id) { Downloads.init(ctx); Downloads.list.value.lastOrNull { it.key == ep.id } }
    if (dl == null) EpMenuItem("Download", Icons.Default.Download) { close(); onDownload() }
    else EpMenuItem(if (Downloads.status(ctx, dl).done) "Remove download" else "Cancel download", Icons.Default.Delete) {
        close(); Downloads.remove(ctx, dl); Snack.show("Download removed")
    }
    if (!watched) EpMenuItem("Mark watched", Icons.Default.Check) { close(); onMark(true) }
    if (watched || started) EpMenuItem("Mark unwatched", Icons.Default.Replay) { close(); onMark(false) }
    }
}

@Composable
private fun EpMenuItem(label: String, icon: ImageVector, req: FocusRequester? = null, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sh = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(sh)
            .background(if (f) Color(0x29FFFFFF) else Color(0x0FFFFFFF))
            .border(if (f) 2.dp else 0.dp, if (f) Color.White else Color.Transparent, sh)
            .then(if (req != null) Modifier.focusRequester(req) else Modifier)
            .onFocusChanged { f = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = Color.White)
        Text(label, Modifier.padding(start = 12.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** TV 0.9.10: download one episode (needs 3 GB free; the Fire TV Stick has little space). */
fun tvDownloadEp(vm: AppViewModel, ctx: android.content.Context, scope: kotlinx.coroutines.CoroutineScope, meta: Meta, ep: Video) {
    val free = dlFreeBytes(ctx)
    if (free < 3L * 1024 * 1024 * 1024) { Snack.show("Not enough space to download (${free / (1024 * 1024)} MB free)"); return }
    val t = SourceTarget(ep.id, "${meta.name}  S${ep.season}E${ep.episode}", "S${ep.season} E${ep.episode}", ep.title ?: ep.name, ep.thumbnail)
    Snack.show("Finding a file to download…")
    scope.launch {
        val msg = Downloads.start(vm, ctx, meta, t)
        ActLog.add("APP", "TV download S${ep.season}E${ep.episode}: $msg")
        Snack.show(msg, millis = 4500)
    }
}
