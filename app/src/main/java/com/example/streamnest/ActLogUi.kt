package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Developer's Corner › Activity log: this phone, or a TV on the account; filters; Share all 3 days as one file. */
@Composable
fun ActLogScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(listOf("phone" to "This phone")) }
    var dev by remember { mutableStateOf("phone") }
    var text by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("ALL") }
    var query by remember { mutableStateOf("") }
    // 0.9.73 (from Xstream): developer's log browser — every account, organised by app › version › device.
    var browser by remember { mutableStateOf(false) }
    if (browser) LogBrowser { browser = false }
    LaunchedEffect(Unit) {
        // Phones: only Xstream's own logs (the account's TV logs there are written by StreamX TV as well).
        devices = listOf("phone" to "This phone") + if (BuildConfig.IS_TV) ActLog.cloudDevices() else emptyList()
        // Developer: every account's devices (errors, crashes, play results; 15 days)
        if (Public.dev) devices = devices + ActLog.allAccountsDevices().map { (k, n) -> k to "☁ $n" }
    }
    LaunchedEffect(dev) { text = null; text = if (dev == "phone") withContext(Dispatchers.IO) { ActLog.allText() } else if (dev.startsWith("x:")) ActLog.allAccountsText(dev) else ActLog.cloudText(dev) }
    val lines = remember(text, filter, query) {
        (text ?: "").lines().filter { l -> l.isNotBlank() && (filter == "ALL" || l.startsWith("=====") || l.drop(13).startsWith(filter)) && (query.isBlank() || l.contains(query, true)) }
    }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0F)).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Activity log", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (Public.dev && !BuildConfig.IS_TV) Text("All accounts", Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).background(glassy(Color(0xFF23242B))).clickable { browser = true }
                    .padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("Share", Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFE50914)).clickable {
                    scope.launch {
                        val f = withContext(Dispatchers.IO) {
                            val d = java.io.File(ctx.cacheDir, "logs").apply { mkdirs() }
                            val name = devices.firstOrNull { it.first == dev }?.second?.replace(Regex("[^A-Za-z0-9]+"), "-") ?: "phone"
                            java.io.File(d, "streamx-log-$name.txt").apply { writeText(text ?: "") }
                        }
                        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", f)
                        runCatching {
                            ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share the log")
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }.padding(horizontal = 14.dp, vertical = 7.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("Close", Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(glassy(Color(0xFF23242B))).clickable { onClose() }.padding(horizontal = 14.dp, vertical = 7.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            @Composable fun chip(t: String, on: Boolean, act: () -> Unit) = Text(t, Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else glassy(Color(0xFF23242B)))
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(50)).clickable { act() }.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (on) Color.Black else Color.White)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                devices.forEach { (id, n) -> chip(n, dev == id) { dev = id } }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL" to "All", "TAP" to "Taps", "KEY" to "Remote", "SCREEN" to "Screens", "NET" to "Network", "PLAY" to "Playback", "ROOM" to "Watch together", "SYNC" to "Sync", "VPN" to "VPN", "ERROR" to "Errors", "APP" to "App")
                    .forEach { (k, l) -> chip(l, filter == k) { filter = k } }
            }
            androidx.compose.material3.OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 14.dp), singleLine = true,
                placeholder = { Text("Find in the log") }, shape = RoundedCornerShape(12.dp))
            if (text == null) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), state = list) {
                itemsIndexed(lines) { i, l ->
                    val type = l.drop(13).take(6).trim()
                    val c = when (type) { "TAP" -> Color(0xFF7CC4FF); "KEY" -> Color(0xFFC9A2FF); "SCREEN" -> Color(0xFF3CCF7A); "PLAY" -> Color(0xFFFFB547); "ROOM" -> Color(0xFFF2A5C8); "ERROR" -> Color(0xFFFF6B6B); "VPN" -> Color(0xFF5AB0FF); else -> Color(0xFFB6BDCC) }
                    Text(l, Modifier.fillMaxWidth().background(if (i % 2 == 0) Color(0xFF0E0F13) else Color.Transparent).padding(horizontal = 6.dp, vertical = 3.dp),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (l.startsWith("=====")) Color.White else c, lineHeight = 15.sp)
                }
            }
        }
    }
}

/**
 * 0.9.8 (music): Settings › Account › Xstream Music logs: only the MUSIC lines (songs, taps, pauses, buffering, errors,
 * library, sleep timer, widget / lock screen) of the last days — view them here or share them as one file.
 */
@Composable
fun MusicLogsCard() {
    if (!Feature.music || BuildConfig.IS_TV) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf<List<String>>(emptyList()) }
    var day by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(open) { if (open) { days = withContext(Dispatchers.IO) { ActLog.days() }; day = days.firstOrNull() } }
    LaunchedEffect(day) { text = day?.let { d -> withContext(Dispatchers.IO) { ActLog.musicText(d) } }.orEmpty() }
    fun share() = scope.launch {
        val all = withContext(Dispatchers.IO) { ActLog.days().joinToString("\n\n") { d -> "── $d ──\n" + ActLog.musicText(d) } }
        val f = withContext(Dispatchers.IO) { java.io.File(java.io.File(ctx.cacheDir, "logs").apply { mkdirs() }, "${Brand.NAME}-music-log.txt").apply { writeText(all) } }
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", f)
            ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share the Music log")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Snack.show("Couldn't share the Music log") }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(glassy(Color(0xFF15161C))).padding(14.dp)) {
        Text("${Brand.NAME} Music logs", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text("What Music did on this phone: songs, pauses, skips, loading and errors. Only Music — nothing from the video app.",
            Modifier.padding(top = 3.dp), fontSize = 12.sp, color = Color(0xFF9AA3B5))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (open) "Hide" else "View", Modifier.clip(RoundedCornerShape(50)).background(glassy(Color(0xFF23242B))).clickable { open = !open }
                .padding(horizontal = 14.dp, vertical = 7.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("Share", Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFE50914)).clickable { share() }
                .padding(horizontal = 14.dp, vertical = 7.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        if (open) {
            Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                days.forEach { d ->
                    Text(d, Modifier.clip(RoundedCornerShape(50)).background(if (d == day) Color.White else glassy(Color(0xFF23242B))).clickable { day = d }
                        .padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (d == day) Color.Black else Color.White)
                }
            }
            val lines = remember(text) { text.lines().filter { it.isNotBlank() }.takeLast(400) }
            Column(Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B0B0F))
                .verticalScroll(rememberScrollState(Int.MAX_VALUE)).padding(8.dp)) {
                if (lines.isEmpty()) Text("Nothing from Music on this day.", fontSize = 12.sp, color = Color(0xFF9AA3B5))
                lines.forEach { Text(it, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFD5D9E2)) }
            }
        }
    }
}
