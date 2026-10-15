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
    LaunchedEffect(Unit) { devices = listOf("phone" to "This phone") + ActLog.cloudDevices() }
    LaunchedEffect(dev) { text = null; text = if (dev == "phone") withContext(Dispatchers.IO) { ActLog.allText() } else ActLog.cloudText(dev) }
    val lines = remember(text, filter, query) {
        (text ?: "").lines().filter { l -> l.isNotBlank() && (filter == "ALL" || l.startsWith("=====") || l.drop(13).startsWith(filter)) && (query.isBlank() || l.contains(query, true)) }
    }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0F)).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Activity log", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Share", Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFE50914)).clickable {
                    scope.launch {
                        val f = withContext(Dispatchers.IO) {
                            val d = java.io.File(ctx.cacheDir, "logs").apply { mkdirs() }
                            val name = devices.firstOrNull { it.first == dev }?.second?.replace(Regex("[^A-Za-z0-9]+"), "-") ?: "phone"
                            java.io.File(d, "streamx-log-$name-3days.txt").apply { writeText(text ?: "") }
                        }
                        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", f)
                        runCatching {
                            ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share the log")
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }.padding(horizontal = 14.dp, vertical = 7.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("Close", Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(Color(0xFF23242B)).clickable { onClose() }.padding(horizontal = 14.dp, vertical = 7.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            @Composable fun chip(t: String, on: Boolean, act: () -> Unit) = Text(t, Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Color(0xFF23242B))
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
