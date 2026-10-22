package com.example.streamnest

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun TabRow3(tabs: List<String>, sel: Int, onSel: (Int) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x14FFFFFF)).padding(4.dp)) {
        tabs.forEachIndexed { i, t ->
            Text(t, Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (i == sel) Color.White else Color.Transparent).clickable { onSel(i) }.padding(vertical = 9.dp),
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (i == sel) Color.Black else Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** Developer's Corner › Source Test: Addons (as before), CloudStream plugins and Nuvio plugins, each in its own tab. */
@Composable
fun SourceTestHub(vm: AppViewModel, onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    if (tab == 0) { Column(Modifier.fillMaxSize().background(Bg)) { Box(Modifier.statusBarsPadding()) { TabRow3(listOf("Addons", "CloudStream", "Nuvio"), tab) { tab = it } }; Box(Modifier.weight(1f)) { SourceTestScreen(vm, onBack) } }; return }
    BackHandler { tab = 0 }
    Column(Modifier.fillMaxSize().background(Bg)) {
        PageHeader("Source Test", onBack)
        TabRow3(listOf("Addons", "CloudStream", "Nuvio"), tab) { tab = it }
        Box(Modifier.weight(1f)) { if (tab == 1) CsTestTab(vm) else NuvioTestTab(vm) }
    }
}

/** Settings › Plugins: JavaScript (Xstream format), CloudStream and Nuvio. */
@Composable
fun XPluginsPage(vm: AppViewModel, onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(1) }
    if (tab == 0) { Column(Modifier.fillMaxSize().background(Bg)) { Box(Modifier.statusBarsPadding()) { TabRow3(listOf("JavaScript", "CloudStream", "Nuvio"), tab) { tab = it } }; Box(Modifier.weight(1f)) { PluginsPage(onBack) } }; return }
    Column(Modifier.fillMaxSize().background(Bg)) {
        PageHeader("Plugins", onBack)
        TabRow3(listOf("JavaScript", "CloudStream", "Nuvio"), tab) { tab = it }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 80.dp)) {
            Text("Test them in Developer's Corner › Source Test. Pick the ones Quick Play tries first in Settings › Quick Start › CloudStream.", Modifier.padding(16.dp), fontSize = 12.5.sp, color = Muted)
            if (tab == 1) CsPluginList(vm) else NuvioPluginList(vm)
        }
        if (tab == 1) PublishBar("Publish these plugins for everyone") { PublishAll.plugins(vm) }
    }
}
