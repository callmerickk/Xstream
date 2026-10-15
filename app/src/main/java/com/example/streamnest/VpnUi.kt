package com.example.streamnest

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

private val VMuted = Color(0xFF9AA3B5)
private val VCard = Color(0xFF15161C)
private val VGreen = Color(0xFF3DDC84)

@Composable
private fun VCardBox(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(VCard), content = content)

@Composable
private fun VLabel(t: String, new: Boolean = false) = Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(t, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = VMuted)
    if (new) Text("NEW", Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFE50914)).padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun VDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0DFFFFFF)))

/** Settings › Network & DNS › VPN: on/off with the IP check, how Xstream uses it, your VPN accounts, countries. */
@Composable
fun VpnSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Vpn.init(ctx); Vpn.checkOtherVpn(ctx); if (Vpn.direct.value == null) Vpn.direct.value = withContext(Dispatchers.IO) { Vpn.trace("1.1.1.1") } }
    val st by Vpn.state.collectAsState()
    val msg by Vpn.message.collectAsState()
    val cur by Vpn.current.collectAsState()
    val direct by Vpn.direct.collectAsState()
    val via by Vpn.via.collectAsState()
    val mode by Vpn.mode.collectAsState()
    val auto by Vpn.auto.collectAsState()
    val pick by Vpn.pick.collectAsState()
    val guard by Vpn.guard.collectAsState()
    val servers by Vpn.servers.collectAsState()
    val testing by Vpn.testing.collectAsState()
    val other by Vpn.otherVpn.collectAsState()
    val hosts by Vpn.smartHosts.collectAsState()
    var sheet by remember { mutableStateOf(false) }
    val permit = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) scope.launch { Vpn.connect() } else Snack.show("The VPN needs Android's OK to start")
    }
    fun turnOn() {
        val i = android.net.VpnService.prepare(ctx)
        if (i != null) permit.launch(i) else scope.launch { Vpn.connect() }
    }

    VLabel("VPN", new = true)
    VCardBox {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            when (st) {
                Vpn.St.CONNECTING -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                else -> Box(Modifier.size(12.dp).clip(CircleShape).background(when (st) { Vpn.St.ON -> VGreen; Vpn.St.ERROR -> Color(0xFFFF6B6B); else -> Color(0xFF5A5F6B) }))
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                val c = cur
                Text(when (st) { Vpn.St.ON -> "Connected · ${c?.name ?: ""}"; Vpn.St.CONNECTING -> "Connecting…"; Vpn.St.ERROR -> "Couldn't connect"; else -> if (other) "Your VPN app is on" else "Off" },
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = when (st) { Vpn.St.ON -> VGreen; Vpn.St.ERROR -> Color(0xFFFF6B6B); else -> Color.White })
                Text(when {
                    st == Vpn.St.ON && c != null -> listOfNotNull(c.ping.takeIf { it > 0 }?.let { "$it ms" }, c.mbps.takeIf { it > 0 }?.let { "${it.toInt()} Mbps" },
                        if (mode == "smart") "only blocked sources" else "all of Xstream", if (c.src != "sx") "via ${c.src}" else null).joinToString(" · ")
                    st == Vpn.St.ERROR || st == Vpn.St.CONNECTING -> msg
                    other -> "Xstream goes through it. Android allows one VPN at a time."
                    else -> "Xstream uses your normal internet"
                }, fontSize = 12.5.sp, color = VMuted)
            }
            Switch(st == Vpn.St.ON || st == Vpn.St.CONNECTING, { on -> if (on) turnOn() else scope.launch { Vpn.disconnect() } },
                enabled = !testing, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFFE50914), checkedThumbColor = Color.White))
        }
        VDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable fun ipBox(label: String, ip: String?, place: String, m: Modifier) = Column(m.clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E0F13)).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = VMuted)
                Text(ip ?: "…", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(place, fontSize = 11.sp, color = VMuted, maxLines = 1)
            }
            val d = direct
            ipBox("YOUR IP", d?.first, d?.second?.let { "${Vpn.flag(it)} ${Vpn.countryName(it) ?: it}" } ?: "", Modifier.weight(1f))
            Text("→", Modifier.padding(horizontal = 8.dp), color = VMuted)
            val v = if (st == Vpn.St.ON) via else d
            ipBox(if (mode == "smart" && st == Vpn.St.ON) "BLOCKED SOURCES SEE" else "WHAT SOURCES SEE", v?.first,
                v?.second?.let { "${Vpn.flag(it)} ${Vpn.countryName(it) ?: it}" + if (st != Vpn.St.ON) " (no VPN)" else "" } ?: "", Modifier.weight(1f))
        }
    }

    VLabel("How Xstream uses it")
    VCardBox {
        @Composable fun opt(k: String, t: String, s: String) = Row(Modifier.fillMaxWidth().clickable { Vpn.setMode(k) }.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(t, fontSize = 16.sp, fontWeight = FontWeight.Medium); Text(s, fontSize = 12.5.sp, color = VMuted) }
            RadioButton(mode == k, { Vpn.setMode(k) })
        }
        opt("smart", "Smart (recommended)", "Only sources that don't work in India go through the VPN. Everything else stays direct and fast." +
            if (hosts.isNotEmpty()) " Now: ${hosts.size} source${if (hosts.size > 1) "s" else ""}." else "")
        VDivider()
        opt("all", "Everything in Xstream", "Hides your IP from every source. A little slower to start.")
        VDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Never slower than direct", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("If a country is much slower than your own internet, only blocked sources use it", fontSize = 12.5.sp, color = VMuted)
            }
            Switch(guard, { Vpn.setGuard(it) }, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFFE50914), checkedThumbColor = Color.White))
        }
        if (hosts.isNotEmpty()) { VDivider(); Text("Reset the Smart list", Modifier.fillMaxWidth().clickable { Vpn.forgetHosts() }.padding(horizontal = 18.dp, vertical = 12.dp), fontSize = 13.sp, color = VMuted) }
    }

    VLabel("Your VPN accounts")
    VCardBox {
        val accts = remember(servers) { Vpn.ownAccounts() }
        if (accts.isEmpty()) Text("Use a VPN you already have (Proton VPN, Mullvad…). Sign in once on its own website, inside Xstream.",
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp), fontSize = 13.sp, color = VMuted)
        accts.forEachIndexed { i, (src, n) ->
            if (i > 0) VDivider()
            val p = VpnProviders.firstOrNull { it.name == src }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(p?.color ?: 0xFF3A3B44)), Alignment.Center) { Text(p?.letter ?: "W", fontWeight = FontWeight.Bold) }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(src, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("$n countr${if (n == 1) "y" else "ies"} · connects without signing in again", fontSize = 12.5.sp, color = VMuted)
                }
                Text("Remove", Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF23242B)).clickable { Vpn.removeAccount(src) }.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        VDivider()
        Text("＋ Connect a VPN", Modifier.padding(horizontal = 18.dp, vertical = 12.dp).clip(RoundedCornerShape(50)).background(Color(0xFF23242B))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(50)).clickable { sheet = true }.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }

    VLabel("Countries")
    VCardBox {
        val best = servers.filter { it.mbps >= Vpn.MIN_MBPS }.maxByOrNull { it.mbps / Math.sqrt(it.ping.coerceAtLeast(1).toDouble()) }
        Row(Modifier.fillMaxWidth().clickable { Vpn.setAuto() }.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⚡", Modifier.width(30.dp), fontSize = 20.sp)
            Column(Modifier.weight(1f)) { Text("Auto — fastest", fontSize = 16.sp, fontWeight = FontWeight.Medium); Text(best?.let { "Now: ${it.name}" } ?: "Test speed to find the fastest", fontSize = 12.5.sp, color = VMuted) }
            RadioButton(auto, { Vpn.setAuto() })
        }
        if (servers.isEmpty()) { VDivider(); Text("No countries yet. Connect your VPN account above (Xstream's own servers appear here once they're added).", Modifier.padding(horizontal = 18.dp, vertical = 14.dp), fontSize = 13.sp, color = VMuted) }
        servers.groupBy { it.src }.toList().sortedBy { if (it.first == "sx") 0 else 1 }.forEach { (src, list) ->
            VDivider()
            Text(if (src == "sx") "XSTREAM" else "${src.uppercase()} · YOUR ACCOUNT", Modifier.padding(start = 18.dp, top = 10.dp, bottom = 2.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VMuted)
            list.forEach { s ->
                val slow = s.mbps in 0.1..(Vpn.MIN_MBPS - 0.01)
                Row(Modifier.fillMaxWidth().clickable { Vpn.setPick(s.id) }.padding(horizontal = 18.dp, vertical = 10.dp).then(if (slow) Modifier.background(Color.Transparent) else Modifier),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(Vpn.flag(s.cc), Modifier.width(30.dp), fontSize = 20.sp)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (slow) VMuted else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (s == best) Text("FASTEST", Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(VGreen).padding(horizontal = 5.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                        Text(when { slow -> "Too slow for HD — Auto skips it"; s.ping > 0 -> "${s.ping} ms away"; else -> "Not tested yet" }, fontSize = 12.sp, color = VMuted)
                    }
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(80.dp)) {
                        Text(if (testing && s.testedAt == 0L) "…" else if (s.mbps > 0) "${s.mbps.toInt()} Mbps" else "—", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Box(Modifier.padding(top = 3.dp).width(80.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF2A2B33))) {
                            Box(Modifier.fillMaxWidth((s.mbps / 100.0).coerceIn(0.0, 1.0).toFloat()).height(4.dp)
                                .background(if (slow) Color(0xFFFF6B6B) else if (s.mbps > 60) VGreen else Color(0xFFFFB547)))
                        }
                    }
                    RadioButton(!auto && pick == s.id, { Vpn.setPick(s.id) })
                }
            }
        }
        VDivider()
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (testing) "Testing…" else "↻ Test speed", Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF23242B)).clickable(enabled = !testing && servers.isNotEmpty()) {
                val i = android.net.VpnService.prepare(ctx)
                if (i != null) permit.launch(i) else scope.launch { Vpn.testAll() }
            }.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
    Text("Only Xstream uses the VPN; your other apps keep your normal internet. Android shows a 🔑 key in the status bar while it's on.",
        Modifier.padding(horizontal = 24.dp, vertical = 10.dp), fontSize = 12.5.sp, color = VMuted)

    if (sheet) ConnectVpnSheet { sheet = false }
}

/** "Connect a VPN": pick yours → its website opens here (sign in once) → the WireGuard files it gives are taken. */
@Composable
private fun ConnectVpnSheet(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var site by remember { mutableStateOf<VpnProvider?>(null) }
    var paste by remember { mutableStateOf(false) }
    fun added(n: Int, src: String) { Snack.show(if (n > 0) "$src: $n countr${if (n == 1) "y" else "ies"} added" else "No WireGuard connection found in that file"); if (n > 0) onDone() }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val files = withContext(Dispatchers.IO) { uris.flatMap { u -> runCatching { readConfs(ctx.contentResolver.openInputStream(u)!!.use { it.readBytes() }, u.lastPathSegment ?: "") }.getOrDefault(emptyList()) } }
            added(Vpn.importConfs("Your VPN", files), "Your VPN")
        }
    }
    val qr = rememberLauncherForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { r ->
        val t = r.contents ?: return@rememberLauncherForActivityResult
        scope.launch { added(Vpn.importConfs("Your VPN", listOf("qr" to t)), "Your VPN") }
    }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.94f).heightIn(max = 640.dp).clip(RoundedCornerShape(24.dp)).background(VCard).verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
            Text("Connect a VPN", Modifier.padding(horizontal = 18.dp), fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("Sign in once on your VPN's own website, right here in Xstream. Xstream never sees your password; it only keeps the connection files the site gives you (on this phone only).",
                Modifier.padding(horizontal = 18.dp, vertical = 6.dp), fontSize = 12.5.sp, color = VMuted)
            VpnProviders.forEach { p ->
                val off = p.tag == "no"
                Row(Modifier.fillMaxWidth().clickable(enabled = !off) { site = p }.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(p.color).copy(alpha = if (off) 0.4f else 1f)), Alignment.Center) { Text(p.letter, fontWeight = FontWeight.Bold) }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (off) VMuted else Color.White)
                        Text(p.note, fontSize = 12.sp, color = VMuted)
                    }
                    if (p.tag == "free") Text("FREE", Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x263DDC84)).padding(horizontal = 7.dp, vertical = 2.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = VGreen)
                    if (p.tag == "paid") Text("PAID", Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF23242B)).padding(horizontal = 7.dp, vertical = 2.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFCFD3DC))
                }
            }
            Text("Other WireGuard VPN", Modifier.padding(start = 18.dp, top = 10.dp, bottom = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VMuted)
            Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable fun chip(t: String, a: () -> Unit) = Text(t, Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF23242B)).clickable { a() }.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                chip("File") { file.launch(arrayOf("*/*")) }
                chip("QR code") { qr.launch(com.journeyapps.barcodescanner.ScanOptions().setPrompt("Scan a WireGuard QR code").setBeepEnabled(false).setOrientationLocked(false)) }
                chip("Paste") { paste = true }
            }
            Text("Close", Modifier.align(Alignment.End).padding(end = 12.dp, top = 8.dp).clip(RoundedCornerShape(10.dp)).clickable { onDone() }.padding(10.dp), fontWeight = FontWeight.Bold)
        }
    }
    site?.let { p -> VpnSiteDialog(p, onClose = { site = null }) { files -> scope.launch { val n = Vpn.importConfs(p.name, files); site = null; added(n, p.name) } } }
    if (paste) {
        var text by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { paste = false }, title = { Text("Paste a WireGuard file") },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp).tvOkKeyboard(), placeholder = { Text("[Interface]\nPrivateKey = …\n\n[Peer]\n…") }, keyboardOptions = tvKeyboard()) },
            confirmButton = { TextButton({ paste = false; scope.launch { added(Vpn.importConfs("Your VPN", listOf("pasted" to text)), "Your VPN") } }) { Text("Add") } },
            dismissButton = { TextButton({ paste = false }) { Text("Cancel") } })
    }
}

/** A downloaded file → WireGuard files (a .conf, or several inside a .zip). */
private fun readConfs(bytes: ByteArray, name: String): List<Pair<String, String>> {
    if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
        val out = mutableListOf<Pair<String, String>>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && e.name.endsWith(".conf", true)) out += e.name to z.readBytes().decodeToString()
            }
        }
        return out
    }
    val t = bytes.decodeToString()
    return if ("[Interface]" in t && "PrivateKey" in t) listOf(name to t) else emptyList()
}

/** The VPN's own website inside Xstream. Downloads (WireGuard files) are caught; nothing typed on the page is read. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun VpnSiteDialog(p: VpnProvider, onClose: () -> Unit, onFiles: (List<Pair<String, String>>) -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(p.url) }
    var got by remember { mutableIntStateOf(0) }
    val collected = remember { mutableListOf<Pair<String, String>>() }
    fun take(name: String, bytes: ByteArray) {
        val confs = readConfs(bytes, name)
        if (confs.isEmpty()) return
        collected += confs; got = collected.size
        Snack.show("${confs.size} connection file${if (confs.size > 1) "s" else ""} caught — download more, or tap Done")
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0F)).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✕", Modifier.clip(CircleShape).clickable { onClose() }.padding(10.dp), fontSize = 16.sp)
                Text("🔒 " + (runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: url), Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B1C23))
                    .padding(horizontal = 10.dp, vertical = 8.dp), fontSize = 13.sp, color = Color(0xFFCFD3DC), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (got > 0) "Done ($got)" else "Done", Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(if (got > 0) Color(0xFFE50914) else Color(0xFF23242B))
                    .clickable { if (got > 0) onFiles(collected.toList()) else onClose() }.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            AndroidView(factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() { override fun onPageFinished(v: WebView, u: String) { url = u } }
                    webChromeClient = WebChromeClient()
                    addJavascriptInterface(object {
                        @JavascriptInterface fun file(name: String, dataUrl: String) {
                            val b = runCatching { android.util.Base64.decode(dataUrl.substringAfter("base64,"), android.util.Base64.DEFAULT) }.getOrNull() ?: return
                            post { take(name, b) }
                        }
                    }, "SXGrab")
                    setDownloadListener { dUrl, ua, cd, mime, _ ->
                        val name = URLUtil.guessFileName(dUrl, cd, mime)
                        when {
                            // Files the page makes itself (most VPN sites): read them through the page.
                            dUrl.startsWith("blob:") -> evaluateJavascript("""(function(){var x=new XMLHttpRequest();x.open('GET','$dUrl',true);x.responseType='blob';x.onload=function(){var r=new FileReader();r.onloadend=function(){SXGrab.file(${org.json.JSONObject.quote(name)},r.result)};r.readAsDataURL(x.response)};x.send();})()""", null)
                            dUrl.startsWith("data:") -> take(name, runCatching { if (";base64," in dUrl) android.util.Base64.decode(dUrl.substringAfter(";base64,"), android.util.Base64.DEFAULT) else java.net.URLDecoder.decode(dUrl.substringAfter(','), "UTF-8").toByteArray() }.getOrDefault(ByteArray(0)))
                            else -> scope.launch {
                                val b = withContext(Dispatchers.IO) { runCatching {
                                    Api.http.newCall(Request.Builder().url(dUrl).header("User-Agent", ua).header("Cookie", CookieManager.getInstance().getCookie(dUrl) ?: "").build()).execute().use { it.body?.bytes() }
                                }.getOrNull() }
                                if (b != null) take(name, b)
                            }
                        }
                    }
                    loadUrl(p.url)
                }
            }, modifier = Modifier.weight(1f).fillMaxWidth(), onRelease = { it.destroy() })
            Text("🛡 Sign in as usual. When the site offers a WireGuard file, download it; Xstream takes it. Xstream can't read what you type here.",
                Modifier.padding(12.dp), fontSize = 12.sp, color = VMuted)
        }
    }
}
