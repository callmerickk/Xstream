package com.example.streamnest

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Developer's Corner › Logs of every account, organised: 1 the app (Xstream Mobile · Xstream Live · StreamX Mobile · StreamX TV),
 * 2 its versions (newest first), 3 the devices on that version (model + account, one entry each), 4 the log with
 * Share and Copy.
 */
private fun verKey(v: String): List<Int> = v.split('.', '-').map { it.toIntOrNull() ?: 0 }
private val verOrder = Comparator<String> { a, b ->
    val x = verKey(a); val y = verKey(b)
    for (i in 0 until maxOf(x.size, y.size)) { val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 }); if (c != 0) return@Comparator c }
    0
}

@Composable
fun LogBrowser(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<ActLog.LogDev>?>(null) }
    var app by remember { mutableStateOf<String?>(null) }
    var ver by remember { mutableStateOf<String?>(null) }
    var dev by remember { mutableStateOf<ActLog.LogDev?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { all = ActLog.logIndex() }
    LaunchedEffect(dev) { val d = dev ?: return@LaunchedEffect; text = null; text = ActLog.logOf(d) }
    fun back() { when { dev != null -> dev = null; ver != null -> ver = null; app != null -> app = null; else -> onClose() } }
    val fmt = remember { SimpleDateFormat("d MMM HH:mm", Locale.US) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { back() }
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0F)).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", Modifier.clip(RoundedCornerShape(50)).clickable { back() }.padding(horizontal = 10.dp), fontSize = 26.sp)
                Column(Modifier.weight(1f)) {
                    Text("Logs · all accounts", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(listOfNotNull(app, ver, dev?.let { "${it.model} · ${it.who}" }).joinToString(" › ").ifBlank { "Pick an app" },
                        fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (dev != null && text != null) {
                    Text("Copy", Modifier.clip(RoundedCornerShape(50)).background(glassy(Color(0xFF23242B))).clickable {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("log", text)); Snack.show("Log copied")
                    }.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Share", Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(Color(0xFFE50914)).clickable {
                        scope.launch {
                            val d = dev ?: return@launch
                            val f = withContext(Dispatchers.IO) {
                                val dir = java.io.File(ctx.cacheDir, "logs").apply { mkdirs() }
                                java.io.File(dir, "log-${d.app}-${d.ver}-${d.model}".replace(Regex("[^A-Za-z0-9.]+"), "-") + ".txt").apply { writeText(text ?: "") }
                            }
                            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", f)
                            runCatching {
                                ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share the log")
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    }.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            val list = all
            if (list == null || (dev != null && text == null)) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = BrandRed) }
            else if (dev != null) {
                val lines = remember(text) { text.orEmpty().lines() }
                val st = rememberLazyListState()
                LaunchedEffect(lines.size) { if (lines.isNotEmpty()) st.scrollToItem(lines.lastIndex) }
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), st) {
                    items(lines) { l ->
                        Text(l, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp,
                            color = when { l.startsWith("=====") -> Color.White; l.contains("ERROR") || l.contains("failed") -> Color(0xFFFF8A80); l.contains("PLAY") -> Color(0xFFB8E0FF); else -> Color(0xFFC9CCD6) })
                    }
                }
            } else {
                @Composable fun Entry(title: String, sub: String, onClick: () -> Unit) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF17181E)).clickable(onClick = onClick).padding(16.dp)) {
                        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(sub, fontSize = 12.sp, color = Muted)
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    when {
                        app == null -> {
                            val apps = list.groupBy { it.app }
                            val order = listOf("StreamX Mobile", "StreamX TV", "Xstream Mobile", "Xstream Live", "Xstream TV")
                            items(order.filter { it in apps } + apps.keys.filter { it !in order }) { a ->
                                val g = apps[a].orEmpty()
                                Entry(a, "${g.map { it.ver }.distinct().size} versions · ${g.size} devices · last ${fmt.format(Date(g.maxOf { it.at }))}") { app = a }
                            }
                        }
                        ver == null -> {
                            val vs = list.filter { it.app == app }.groupBy { it.ver }
                            items(vs.keys.sortedWith(verOrder.reversed())) { v ->
                                val g = vs[v].orEmpty()
                                Entry(v, "${g.size} devices · last ${fmt.format(Date(g.maxOf { it.at }))}") { ver = v }
                            }
                        }
                        else -> items(list.filter { it.app == app && it.ver == ver }.sortedByDescending { it.at }) { d ->
                            Entry(d.model.ifBlank { "Unknown device" }, "${d.who} · ${fmt.format(Date(d.at))}" + if (d.keys.size > 1) " · ${d.keys.size} merged" else "") { dev = d }
                        }
                    }
                }
            }
        }
    }
}
