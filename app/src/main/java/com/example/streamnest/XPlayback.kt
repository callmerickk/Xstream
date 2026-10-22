package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Xstream: Quick Play and Stream Filters as one clean "Playback" page — a big Quick Play switch, then rows of chips
 * (quality, audio, picture, hide). No paragraphs. The addon picker and speeds live in Developer's Corner.
 */
@Composable
fun QuickPlayPage(vm: AppViewModel, onBack: () -> Unit) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    fun v(k: String) = texts[k] ?: Defaults[k] ?: ""
    val langs = v("qsLangs").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    // What you see = Xstream's own picks with your changes on top; a tap changes only your layer.
    val rules = remember(texts["tagRules"], texts["devTagRules"]) { effectiveRules(texts["devTagRules"], texts["tagRules"]) }
    fun setRule(id: String, r: TagRule) = vm.setText("tagRules", encodeUserRules(parseUserRules(texts["tagRules"] ?: "") + (id to r)))
    val red = Accent
    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 60.dp)) {
        PageHeader("Filters", onBack)
        // Quick Play
        val shape = RoundedCornerShape(22.dp)
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF1E1216), Color(0xFF121216)))).border(1.dp, red.copy(alpha = 0.4f), shape).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Quick Play", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Text("Starts in a second from the fastest servers", fontSize = 12.5.sp, color = Muted)
            }
            Switch(isOn(v("qsOn")), { vm.setText("qsOn", if (it) "yes" else "no") }, colors = SwitchDefaults.colors(checkedTrackColor = red))
        }
        ChipBlock("Quality") {
            QualityLevels.forEach { q -> PChip(q.label, v("qsQuality") == q.id) { vm.setText("qsQuality", q.id); vm.setPlayerPref("quality", q.id) } }
        }
        ChipBlock("Audio · tap to add, tap again to remove") {
            (langs + QsLanguages.keys.filter { it !in langs }.take(10)).forEach { l ->
                val on = l in langs
                PChip((if (on) "${langs.indexOf(l) + 1} · " else "") + langLabel(l), on) {
                    vm.setText("qsLangs", (if (on) langs - l else langs + l).joinToString(","))
                }
            }
        }
        ChipBlock("Picture first") {
            val order = (texts["picPref"] ?: "").split("|").filter { it in PicturePref.ALL }
            val first = order.firstOrNull() ?: "SDR"
            PicturePref.ALL.forEach { p -> PChip(p, first == p) { vm.setText("picPref", (listOf(p) + PicturePref.ALL.filter { it != p }).joinToString("|")); vm.setText("picMatch", "no") } }
            PChip("Match my screen", isOn(texts["picMatch"] ?: "yes")) { vm.setText("picMatch", if (isOn(texts["picMatch"] ?: "yes")) "no" else "yes") }
        }
        ChipBlock("Never show") {
            listOf("cam", "ts", "3d", "480p", "av1").mapNotNull { id -> TagDefs.firstOrNull { it.id == id } }.forEach { d ->
                val on = rules[d.id] == TagRule.Exclude
                PChip(d.label, on) { setRule(d.id, if (on) TagRule.Off else TagRule.Exclude) }
            }
        }
        ChipBlock("Prefer") {
            listOf("4k", "1080p", "dv", "hdr10", "atmos", "51", "hevc").mapNotNull { id -> TagDefs.firstOrNull { it.id == id } }.forEach { d ->
                val on = rules[d.id] == TagRule.Include
                PChip(d.label, on) { setRule(d.id, if (on) TagRule.Off else TagRule.Include) }
            }
        }
        // Every tag, for those who want more: tap cycles Prefer → Hide → Only → off (your layer on top of Xstream's).
        var allOpen by remember { mutableStateOf(false) }
        Text(if (allOpen) "Hide all tags" else "All tags…", Modifier.padding(horizontal = 18.dp).padding(top = 16.dp).clip(RoundedCornerShape(14.dp))
            .background(Color(0x14FFFFFF)).clickable { allOpen = !allOpen }.padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (allOpen) TagDefs.groupBy { it.group }.forEach { (g, defs) ->
            ChipBlock("$g · tap: prefer → hide → only → off") {
                defs.forEach { d ->
                    val r = rules[d.id] ?: TagRule.Off
                    val label = when (r) { TagRule.Include -> "${d.label} · prefer"; TagRule.Exclude -> "${d.label} · hide"; TagRule.Forced -> "${d.label} · only"; else -> d.label }
                    PChip(label, r != TagRule.Off) {
                        setRule(d.id, when (r) { TagRule.Off -> TagRule.Include; TagRule.Include -> TagRule.Exclude; TagRule.Exclude -> TagRule.Forced; else -> TagRule.Off })
                    }
                }
            }
        }
        // Regex, one pattern per line: "Hide" lines remove matching streams (unless nothing would be left — the fallback),
        // "Prefer" lines put matching streams first, in line order.
        Column(Modifier.padding(horizontal = 18.dp).padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("REGEX · ONE PATTERN PER LINE", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = Muted)
            var rin by remember { mutableStateOf(texts["userRegexIn"].orEmpty()) }
            var rout by remember { mutableStateOf(texts["userRegexOut"].orEmpty()) }
            androidx.compose.material3.OutlinedTextField(rin, { rin = it; vm.setText("userRegexIn", it) }, Modifier.fillMaxWidth(), minLines = 2,
                label = { Text("Prefer (first line first)") }, placeholder = { Text("hindi.*1080p\nweb-?dl") }, shape = RoundedCornerShape(14.dp))
            androidx.compose.material3.OutlinedTextField(rout, { rout = it; vm.setText("userRegexOut", it) }, Modifier.fillMaxWidth(), minLines = 2,
                label = { Text("Hide") }, placeholder = { Text("hdr|dolby ?vision") }, shape = RoundedCornerShape(14.dp))
        }
        ChipBlock("Get the video ready on the title page") {
            listOf("off" to "Off", "wifi" to "Wi-Fi only", "always" to "Always").forEach { (k, n) -> PChip(n, v("qsPreload") == k) { vm.setText("qsPreload", k) } }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipBlock(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp).padding(top = 18.dp)) {
        Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = Muted)
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun PChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Color.White else Color(0x14FFFFFF)).clickable(onClick = onClick)
        .padding(horizontal = 13.dp, vertical = 8.dp), fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) Color.Black else Color.White)
}
