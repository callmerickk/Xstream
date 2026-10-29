package com.example.streamnest

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Settings › Sources & Extensions › Live server. */
@Composable
internal fun LiveServerPage(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val st by LiveServer.state.collectAsStateWithLifecycle()
    val note by LiveServer.lastNote.collectAsStateWithLifecycle()
    val texts by vm.texts.collectAsStateWithLifecycle()
    var on by remember { mutableStateOf(LiveServer.enabled(ctx)) }
    var settings by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 40.dp)) {
        PageHeader(LiveServer.NAME, onBack)
        if (!LiveServer.available(ctx)) {
            SettingsCard {
                Text("The Live server is part of the 64-bit Xstream app. This copy is the one for every phone, so it isn't included.",
                    Modifier.padding(20.dp), fontSize = 14.sp, color = Muted)
            }
            return@Column
        }
        SettingsCard {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(when {
                    st.ready -> Color(0xFF3DDC6B); st.status == "Starting" -> Color(0xFFFFCF5A); else -> Color(0xFF6B7385) }))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (st.ready) "Running" else st.status, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (st.ready) "On this phone · version ${st.version}" else "Movies, episodes and music straight from the Live server.",
                        fontSize = 13.sp, color = Muted)
                }
            }
            if (note.isNotBlank()) {
                RowDivider(20.dp)
                Text(note, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), fontSize = 13.sp, color = Color(0xFFFFCF5A))
            }
        }
        SectionLabel("Streams")
        SettingsCard {
            ToggleRow("Live server", "Runs on this phone while Xstream is open and finds streams for every title.", on) { on = it; LiveServer.setEnabled(ctx, it) }
            RowDivider(20.dp)
            ToggleRow("Also in Turbo and Classic", "Live server links join your other streams (Quick Play and instant start use them too). Off: only in Live mode.",
                isOn(texts["liveWithOthers"] ?: "yes")) { vm.setText("liveWithOthers", if (it) "yes" else "no"); forgetAllStreams() }
        }
        SectionLabel("Server")
        SettingsCard {
            SettingRow(Icons.Default.Settings, "Server settings", "Connect your own bot, quality and catalogs (the server's own page).", enabled = st.ready) { settings = true }
            RowDivider()
            SettingRow(Icons.Default.Refresh, "Restart", "Stops the server and starts it again.") { LiveServer.restart(ctx) }
            RowDivider()
            SettingRow(Icons.Default.Subject, "Log", "What the server is doing right now.") { showLog = true }
        }
        Text("Live server: PencariMovie Server (MIT licence), running unchanged on this phone.",
            Modifier.padding(horizontal = 24.dp, vertical = 14.dp), fontSize = 12.sp, color = Muted)
    }
    if (settings) LiveWebDialog(LiveServer.dashboardUrl()) { settings = false }
    if (showLog) LiveLogDialog { showLog = false }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LiveWebDialog(url: String, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Server settings", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            AndroidView(factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    loadUrl(url)
                }
            }, modifier = Modifier.fillMaxSize(), onRelease = { it.destroy() })
        }
    }
}

@Composable
private fun LiveLogDialog(onClose: () -> Unit) {
    val lines by LiveServer.log.collectAsStateWithLifecycle()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Live server log", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            Text(lines.takeLast(200).joinToString("\n").ifBlank { "Nothing yet." },
                Modifier.fillMaxWidth().heightIn(min = 200.dp).padding(14.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF08090C))
                    .verticalScroll(rememberScrollState()).padding(12.dp),
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFCFD3DC), lineHeight = 15.sp)
        }
    }
}
