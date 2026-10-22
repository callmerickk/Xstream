package com.example.streamnest

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/*
 * Xstream 0.5.1 — "Sources & Extensions" (was Content & Discovery):
 *   Watch mode: Turbo (plugins) / Classic (addons), per profile, asked once on a first start.
 *   Sources = addons, Extensions = Nuvio plugins (anyone can add them; they're a last backup unless picked by hand),
 *   Filters = Quick Play & filters. CloudStream plugins live in Developer's Corner only.
 */

private val Red = Color(0xFFE50914)

/** Turbo / Classic for this profile. */
@Composable
internal fun WatchModeCard(vm: AppViewModel) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    val turbo = texts["srcMode"] == "plugins"
    SettingsCard {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("How you watch", fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(if (turbo) "Turbo: streams straight from big streaming catalogues, starts almost instantly. Your Sources rest."
                else "Classic: your Sources and Quick Play servers, exactly as before. Extensions rest.",
                fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x14FFFFFF)).padding(4.dp)) {
                listOf("plugins" to "Turbo", "addons" to "Classic").forEach { (k, label) ->
                    val sel = (k == "plugins") == turbo
                    Text(label, Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (sel) (if (k == "plugins") Red else Color.White) else Color.Transparent)
                        .clickable { vm.setText("srcMode", k); forgetAllStreams() }.padding(vertical = 11.dp),
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (sel) (if (k == "plugins") Color.White else Color.Black) else Color.White, textAlign = TextAlign.Center)
                }
            }
            Text("Each profile keeps its own choice.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** First start on a profile that never chose: Turbo or Classic (full screen, phones only). */
@Composable
fun FirstWatchChooser(vm: AppViewModel) {
    var pick by remember { mutableStateOf("plugins") }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0A0E)).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row { Text("X", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Red); Text("stream", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold) }
            Column {
                Text("How do you want to watch?", fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
                Text("Pick one. You can switch any time in Settings › Sources & Extensions, and each profile keeps its own choice.",
                    fontSize = 14.sp, color = Color(0xFFA9A4B0), modifier = Modifier.padding(top = 8.dp))
            }
            ModeChoice(Icons.Default.Bolt, "Turbo", "Streams straight from big streaming catalogues. Starts almost instantly, HD and 4K ready.", pick == "plugins", recommended = true) { pick = "plugins" }
            ModeChoice(Icons.Default.GridView, "Classic", "Uses your Sources and Quick Play servers, exactly as Xstream worked before.", pick == "addons", recommended = false) { pick = "addons" }
            Spacer(Modifier.height(8.dp))
            Button({ vm.setText("srcMode", pick); forgetAllStreams() }, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)) {
                Text("Continue with " + if (pick == "plugins") "Turbo" else "Classic", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ModeChoice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String, sel: Boolean, recommended: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(if (sel) Color(0x1FE50914) else Color(0x0DFFFFFF))
        .border(if (sel) 2.dp else 1.5.dp, if (sel) Red else Color(0x24FFFFFF), shape).clickable { onClick() }.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (sel) Red else Color(0x1FFFFFFF)), Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Text(title, Modifier.padding(start = 10.dp).weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (recommended) Text("RECOMMENDED", Modifier.clip(RoundedCornerShape(10.dp)).background(Red).padding(horizontal = 10.dp, vertical = 4.dp),
                fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(desc, fontSize = 13.5.sp, color = Color(0xFFE4DFE6), lineHeight = 20.sp)
    }
}

/** Quality chips an Extensions user can hide (matched on the stream's name / title). */
private val ExtChips = listOf("Auto", "Adaptive", "2160p", "4K", "1080p", "720p", "480p", "360p", "DV", "HDR", "REMUX", "CAM", "TS")

/** Is this Extensions (Nuvio) stream hidden by the user's quality chips / the master switch? */
fun AppViewModel.extHides(s: StreamItem): Boolean {
    if (!s.addonName.startsWith("Nuvio · ")) return false
    if (!isOn(texts.value["extOn"] ?: "yes")) return true
    val ex = (texts.value["extExclude"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (ex.isEmpty()) return false
    val t = listOfNotNull(s.stream.name, s.stream.title, s.stream.behaviorHints?.filename).joinToString(" ")
    return ex.any { c ->
        when (c) {
            "Auto", "Adaptive" -> s.effectiveRes() == 0 && Regex("(?i)\\b$c\\b").containsMatchIn(t)
            "4K" -> Regex("(?i)\\b4k\\b|2160p|uhd").containsMatchIn(t)
            else -> Regex("(?i)(?<![a-z0-9])${Regex.escape(c)}(?![a-z0-9])").containsMatchIn(t)
        }
    }
}

/** Settings › Sources & Extensions › Extensions: Nuvio plugins for everyone (laid out like Nuvio's Plugins page). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtensionsPage(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { NuvioPlugins.init(ctx) }
    val repos by NuvioPlugins.repos.collectAsStateWithLifecycle()
    val names by NuvioPlugins.repoNames.collectAsStateWithLifecycle()
    val catalog by NuvioPlugins.catalog.collectAsStateWithLifecycle()
    val enabled by NuvioPlugins.enabled.collectAsStateWithLifecycle()
    val texts by vm.texts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var newRepo by remember { mutableStateOf("") }
    var repoTab by remember { mutableStateOf<String?>(null) }
    val on = isOn(texts["extOn"] ?: "yes")
    val excluded = (texts["extExclude"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val visible = catalog.filter { repoTab == null || it.repo == repoTab }
    val chip = RoundedCornerShape(50)
    Column(Modifier.fillMaxSize().background(Bg)) {
        PageHeader("Extensions", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 80.dp)) {
            SectionLabel("Overview")
            SettingsCard {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("${repos.size} repos", "${catalog.size} providers", "${enabled.size} on").forEach {
                            Text(it, Modifier.clip(chip).background(Color(0x14FFFFFF)).padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFD5D0DA))
                        }
                    }
                    if (texts["srcMode"] != "plugins") Text("Extensions play in Turbo mode. Switch in Sources & Extensions › How you watch.", fontSize = 13.sp, color = Color(0xFFFF8A9A))
                    Text("Extensions are a backup: Xstream uses them when nothing else plays, or when you pick one of their streams yourself.", fontSize = 13.sp, color = Muted)
                }
                RowDivider(20.dp)
                ToggleRow("Use extensions", "Ask your switched-on extensions while finding streams.", on) { vm.setText("extOn", if (it) "yes" else "no") }
            }
            if (repos.isNotEmpty()) {
                SectionLabel("Repository control")
                SettingsCard {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Switch between repositories, then switch the visible providers on or off without scrolling the full list.", fontSize = 13.sp, color = Muted)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            (listOf<String?>(null) + repos).forEach { r ->
                                val inRepo = catalog.filter { r == null || it.repo == r }
                                val sel = repoTab == r
                                Column(Modifier.clip(RoundedCornerShape(18.dp)).background(if (sel) Color(0xFFF2F2F2) else Color.Transparent)
                                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(18.dp)).clickable { repoTab = r }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                    Text(if (r == null) "All" else (names[r] ?: r.substringAfter("//").substringBefore('/')), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                        color = if (sel) Color.Black else Color.White, maxLines = 1)
                                    Text("${inRepo.count { it.key in enabled }}/${inRepo.size} on", fontSize = 12.sp, color = if (sel) Color(0xFF444444) else Muted)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton({ visible.forEach { NuvioPlugins.setEnabled(it.key, true) }; forgetAllStreams() }, Modifier.weight(1f), shape = chip,
                                enabled = visible.any { it.key !in enabled }) { Text("Enable visible", color = Color.White) }
                            OutlinedButton({ visible.forEach { NuvioPlugins.setEnabled(it.key, false) }; forgetAllStreams() }, Modifier.weight(1f), shape = chip,
                                enabled = visible.any { it.key in enabled }) { Text("Disable visible", color = Color.White) }
                        }
                    }
                }
            }
            SectionLabel("Quality filtering")
            SettingsCard {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Hide video sizes or release tags from extension results. Tap a chip to hide or allow it.", fontSize = 13.sp, color = Muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExtChips.forEach { c ->
                            val hid = c in excluded
                            Text(c, Modifier.clip(chip).background(if (hid) Color(0x33E50914) else Color.Transparent)
                                .border(1.dp, if (hid) Red else Color(0x40FFFFFF), chip)
                                .clickable { vm.setText("extExclude", (if (hid) excluded - c else excluded + c).joinToString(",")); forgetAllStreams() }
                                .padding(horizontal = 16.dp, vertical = 9.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (hid) Color(0xFFFF8A90) else Color.White)
                        }
                    }
                    Text(if (excluded.isEmpty()) "Nothing hidden. Extension results show every quality they return." else "Hidden: ${excluded.joinToString(", ")}", fontSize = 12.5.sp, color = Muted)
                }
            }
            SectionLabel("Add repository")
            SettingsCard {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(newRepo, { newRepo = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
                        placeholder = { Text("Extension repository link (manifest.json)") })
                    Button({ NuvioPlugins.addRepo(newRepo); newRepo = ""; msg = "Reading…"; scope.launch { msg = NuvioPlugins.refresh() ?: "Added" } },
                        Modifier.fillMaxWidth().height(52.dp), enabled = newRepo.startsWith("http"), shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF2F2F2), contentColor = Color.Black)) { Text("Install repository", fontWeight = FontWeight.SemiBold) }
                    msg?.let { Text(it, fontSize = 12.5.sp, color = Muted) }
                }
            }
            if (repos.isNotEmpty()) {
                SectionLabel("Installed repositories")
                repos.forEach { r ->
                    SettingsCard {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(names[r] ?: r.substringAfter("//").substringBefore('/'), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                                Text(r.removePrefix("https://"), fontSize = 12.5.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                Text("${catalog.count { it.repo == r }} providers", Modifier.padding(top = 10.dp).clip(chip).background(Color(0x14FFFFFF)).padding(horizontal = 12.dp, vertical = 6.dp),
                                    fontSize = 12.5.sp, color = Color(0xFFD5D0DA))
                            }
                            IconButton({ msg = "Refreshing…"; scope.launch { msg = NuvioPlugins.refresh() ?: "Up to date" } }) { Icon(Icons.Default.Refresh, "Refresh", tint = Color.White) }
                            IconButton({ NuvioPlugins.removeRepo(r); forgetAllStreams() }) { Icon(Icons.Default.Delete, "Remove", tint = Color(0xFFE87A90)) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                SectionLabel("Providers")
                visible.forEach { s ->
                    SettingsCard {
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Extension, null, tint = Color(0xFF5BC26A), modifier = Modifier.size(30.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(names[s.repo] ?: "", fontSize = 12.sp, color = Color(0xFFD5D0DA), maxLines = 1)
                                Text(s.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                s.description?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOfNotNull(s.types.joinToString(" | ").ifBlank { null }, s.version.takeIf { it.isNotBlank() }?.let { "v$it" }).forEach {
                                        Text(it, Modifier.clip(chip).background(Color(0x14FFFFFF)).padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 12.sp, color = Color(0xFFD5D0DA))
                                    }
                                }
                            }
                            Switch(s.key in enabled, { NuvioPlugins.setEnabled(s.key, it); forgetAllStreams() },
                                colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFFF2F2F2), checkedThumbColor = Color.Black))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}
