package com.example.streamnest

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/** One downloaded (or downloading) video. */
@Serializable
data class Dl(
    val dmId: Long, val key: String, val title: String, val epLabel: String? = null, val image: String? = null,
    val type: String = "movie", val metaId: String = "", val file: String = "", val quality: String = "", val at: Long = 0,
)

/** Live state of a download from Android's download manager. */
data class DlStatus(val done: Boolean, val failed: Boolean, val paused: Boolean, val bytes: Long, val total: Long)

/**
 * Downloads for offline viewing: the best plain video file of the title (by the Source Test ranking, your quality and
 * language) is saved by Android's download manager into the app's own folder (no storage permission needed; removed
 * with the app). Play on a downloaded title plays the file — no internet needed.
 */
object Downloads {
    private var prefs: android.content.SharedPreferences? = null
    val list = kotlinx.coroutines.flow.MutableStateFlow<List<Dl>>(emptyList())

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences("downloads", Context.MODE_PRIVATE)
        list.value = runCatching { Api.json.decodeFromString<List<Dl>>(prefs?.getString("all", null) ?: "[]") }.getOrDefault(emptyList())
    }

    private fun save(l: List<Dl>) {
        list.value = l
        prefs?.edit()?.putString("all", Api.json.encodeToString(l))?.apply()
    }

    private fun dm(ctx: Context) = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun status(ctx: Context, d: Dl): DlStatus = runCatching {
        dm(ctx).query(DownloadManager.Query().setFilterById(d.dmId)).use { c ->
            if (!c.moveToFirst()) return@use DlStatus(File(d.file).exists(), !File(d.file).exists(), false, 0, 0)
            val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            DlStatus(st == DownloadManager.STATUS_SUCCESSFUL, st == DownloadManager.STATUS_FAILED, st == DownloadManager.STATUS_PAUSED, bytes, total)
        }
    }.getOrDefault(DlStatus(false, true, false, 0, 0))

    /** The finished file of this video id, if it was downloaded. */
    fun readyFile(ctx: Context, key: String): File? {
        val d = list.value.lastOrNull { it.key == key } ?: return null
        val f = File(d.file)
        return f.takeIf { it.exists() && it.length() > 0 && status(ctx, d).done }
    }

    fun has(key: String): Boolean = list.value.any { it.key == key }

    fun remove(ctx: Context, d: Dl) {
        runCatching { dm(ctx).remove(d.dmId) }
        runCatching { File(d.file).delete() }
        save(list.value.filter { it.dmId != d.dmId })
    }

    /** Bytes used by finished downloads. */
    fun usedBytes(): Long = list.value.sumOf { runCatching { File(it.file).length() }.getOrDefault(0L) }

    /**
     * Finds the best downloadable file of [target] and starts the download. Returns a message for the user.
     * HLS / DASH playlists can't be downloaded as one file, so only plain video files are picked.
     */
    suspend fun start(vm: AppViewModel, ctx: Context, meta: Meta, target: SourceTarget): String {
        init(ctx)
        if (has(target.videoId)) return "Already in your downloads"
        val all = runCatching { vm.allStreams(meta.type, target.videoId) }.getOrDefault(emptyList())
        if (all.isEmpty()) return "No streams found to download"
        val level = vm.playerPref("quality").ifEmpty { vm.texts.value["qsQuality"] ?: "auto" }
        val cands = vm.rankedCandidates(all, level, quickOnly = false)
            .filter { s -> s.stream.url?.let { u -> u.startsWith("http") && !isPlaylistLink(s, u) } == true }
            .take(8)
        for (s in cands) {
            val url = s.stream.url ?: continue
            val pr = StreamCache.probe(url)
            if (!pr.ok || pr.size <= 0 || pr.type?.startsWith("text/html", true) == true) continue
            val ext = (s.stream.behaviorHints?.filename ?: url.substringBefore('?')).substringAfterLast('.', "mkv").lowercase()
                .takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: "mkv"
            val name = (target.title + (target.epLabel?.let { " $it" } ?: "")).replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().take(60)
                .ifBlank { "video" } + "-" + System.currentTimeMillis() % 100000 + "." + ext
            val id = withContext(Dispatchers.Main) {
                runCatching {
                    val req = DownloadManager.Request(Uri.parse(url))
                        .setTitle(target.title + (target.epLabel?.let { " · $it" } ?: ""))
                        .setDescription("Xstream download")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_MOVIES, name)
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(true)
                    val h = StreamHeaders.forUrl(url)
                    h.forEach { (k, v) -> req.addRequestHeader(k, v) }
                    if (h.keys.none { it.equals("User-Agent", true) }) req.addRequestHeader("User-Agent", PlayerUserAgent)
                    dm(ctx).enqueue(req)
                }.getOrNull()
            } ?: continue
            val file = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), name).absolutePath
            save(list.value + Dl(id, target.videoId, target.title, target.epLabel, target.image ?: meta.poster, meta.type, meta.id, file,
                Prepare.qualityName(s.resolution()), System.currentTimeMillis()))
            return "Downloading ${Prepare.qualityName(s.resolution())} · ${"%.1f".format(pr.size / 1_073_741_824.0)} GB — see Downloads (the ↓ button on Home)"
        }
        return "No downloadable file found (only streaming playlists or the servers refused)"
    }
}

/** My Space › Downloads: progress, play offline, delete. */
@Composable
fun DownloadsSection(onPlay: (Screen.Player) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Downloads.init(ctx) }
    val items by Downloads.list.collectAsState()
    if (items.isEmpty()) return
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Downloads", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("%.1f GB".format(Downloads.usedBytes() / 1_073_741_824.0), fontSize = 13.sp, color = Color(0xFF9AA3B5))
        }
        items.asReversed().forEach { d ->
            val st = remember(d.dmId, tick) { Downloads.status(ctx, d) }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF15161C))
                    .clickable(enabled = st.done) {
                        onPlay(Screen.Player(d.title + (d.epLabel?.let { "  $it" } ?: ""), Uri.fromFile(File(d.file)).toString(), emptyList(),
                            key = d.key, image = d.image, type = d.type, source = "Downloaded · ${d.quality}", addon = "Downloads"))
                    }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(d.image, null, Modifier.size(width = 52.dp, height = 74.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF22252E)), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(d.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(d.epLabel, d.quality.ifBlank { null }).joinToString(" · "), fontSize = 12.sp, color = Color(0xFF9AA3B5))
                    when {
                        st.done -> Text("Ready to watch offline", fontSize = 12.sp, color = Color(0xFF7DE2B8))
                        st.failed -> Text("Failed — delete and try again", fontSize = 12.sp, color = Color(0xFFFF8A80))
                        else -> {
                            val f = if (st.total > 0) st.bytes.toFloat() / st.total else 0f
                            Text((if (st.paused) "Waiting for network · " else "Downloading · ") + "${(f * 100).toInt()}%" +
                                (if (st.total > 0) " of %.1f GB".format(st.total / 1_073_741_824.0) else ""), fontSize = 12.sp, color = Color(0xFF9AA3B5))
                            LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).clip(RoundedCornerShape(2.dp)))
                        }
                    }
                }
                if (st.done) Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0x22FFFFFF)), Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, "Play", Modifier.size(22.dp))
                }
                Box(Modifier.padding(start = 8.dp).size(40.dp).clip(CircleShape).clickable { Downloads.remove(ctx, d) }, Alignment.Center) {
                    Icon(Icons.Default.Delete, "Delete download", Modifier.size(20.dp), tint = Color(0xFFB6BDCC))
                }
            }
        }
    }
}

/**
 * Home header: the downloads button left of the bell. While files download, a red number (how many) blinks on it.
 * Tap: your downloads, with progress, play offline and delete.
 */
@Composable
fun DownloadsButton(onPlay: (Screen.Player) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Downloads.init(ctx) }
    val items by Downloads.list.collectAsState()
    var active by remember { mutableIntStateOf(0) }
    LaunchedEffect(items) {
        while (true) {
            active = items.count { d -> val st = Downloads.status(ctx, d); !st.done && !st.failed }
            if (active == 0) break
            delay(2000)
        }
    }
    var open by remember { mutableStateOf(false) }
    val blink = androidx.compose.animation.core.rememberInfiniteTransition(label = "dl")
    val a by blink.animateFloat(1f, 0.25f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(650), androidx.compose.animation.core.RepeatMode.Reverse), label = "dlA")
    Box {
        Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0x99101420)).border(1.dp, Color(0x33FFFFFF), CircleShape).clickable { open = true }, Alignment.Center) {
            Icon(Icons.Default.Download, "Downloads", Modifier.size(21.dp), tint = Color.White)
        }
        if (active > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).graphicsLayer { alpha = a }
            .size(18.dp).clip(CircleShape).background(Color(0xFFE50914)).border(1.5.dp, Color.Black, CircleShape), Alignment.Center) {
            Text("$active", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
    // A bubble under the button, like the notifications one: small when there's little in it, up to the full size.
    if (open) {
        val below = remember {
            object : androidx.compose.ui.window.PopupPositionProvider {
                override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                    layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize) =
                    androidx.compose.ui.unit.IntOffset((windowSize.width - popupContentSize.width - 24).coerceAtLeast(16), anchorBounds.bottom + 12)
            }
        }
        androidx.compose.ui.window.Popup(popupPositionProvider = below, onDismissRequest = { open = false }, properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
            val pop = remember { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(Unit) { pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 420f)) }
            val shape = RoundedCornerShape(24.dp)
            Column(
                Modifier.graphicsLayer {
                    alpha = pop.value.coerceIn(0f, 1f); scaleX = 0.3f + 0.7f * pop.value; scaleY = scaleX
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.85f, 0f)
                }.widthIn(max = 340.dp).fillMaxWidth(0.88f).heightIn(max = if (items.size > 3) 480.dp else 340.dp)
                    .shadow(24.dp, shape).clip(shape).background(Color(0xF7181620))
                    .border(1.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xB3E50914), Color(0x26E50914))), shape)
                    .padding(vertical = 10.dp),
            ) {
                if (items.isEmpty()) Text("Downloads", Modifier.padding(start = 18.dp, top = 4.dp, bottom = 4.dp), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                if (items.isEmpty()) Text("Nothing downloaded yet. Tap Download on a title.", Modifier.padding(horizontal = 18.dp, vertical = 10.dp), fontSize = 12.5.sp, color = Color(0xFF9AA3B5))
                else Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    DownloadsSection { p -> open = false; onPlay(p) }
                }
            }
        }
    }
}
