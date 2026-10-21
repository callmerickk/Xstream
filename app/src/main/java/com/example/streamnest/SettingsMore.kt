package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
private fun SimplePage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().pageScroll().navigationBarsPadding().padding(bottom = 40.dp)) {
        PageHeader(title, onBack)
        content()
    }

@Composable
private fun Chips(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) ->
            val on = id == selected
            Text(label, Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Accent else Color(0xFF2A2A30)).clickable { onPick(id) }
                .padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) OnAccent else Color.White)
        }
    }
}

// ───────────── Content & Discovery > Quick Start ─────────────

@Composable
internal fun QuickStartPage(vm: AppViewModel, onBack: () -> Unit) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    val addons by vm.addons.collectAsStateWithLifecycle()
    fun v(k: String) = texts[k] ?: Defaults[k] ?: ""
    val chosen = v("qsAddons").split("|").filter { it.isNotBlank() }
    val langs = v("qsLangs").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val streamAddons = addons.filter { it.manifest.resources?.toString()?.contains("\"stream\"") == true }.filter { Public.dev || !Public.isDevChoice(it) }.map { it.label() }.distinct()

    SimplePage("Quick Start", onBack) {
        DeviceScopeCard(vm, listOf("qsQuality", "qsPreload", "fastStart"), "quality and fast-start settings")
        Text(
            "Pick your fastest addons. Pressing Play starts straight from them with a quality selector (Auto / Full HD / Mid HD / Low) " +
                "and your preferred language. Every other addon stays under \"All sources\" in the player.",
            Modifier.padding(horizontal = 24.dp, vertical = 4.dp), fontSize = 14.sp, color = Muted,
        )
        SectionLabel("Quick Start")
        SettingsCard {
            ToggleRow("Quick Play", if (Public.dev) "Play from the addons below with the quality selector." else "Starts videos instantly from the fastest servers.", isOn(v("qsOn"))) { vm.setText("qsOn", if (it) "yes" else "no") }
        }

        if (!BuildConfig.IS_TV) SourceModeCard(vm)
        if (!BuildConfig.IS_TV && vm.pluginMode()) {
            QuickCsSection(vm, "qsCs", "Quick Play plugins",
                "Tick the plugins Quick Play uses. Their links are ranked with your quality, audio and picture filters; the best one is opened in the background so Play starts at once.")
            QuickCsSection(vm, "qs4k", "Upgrade to 4K",
                "Tick the plugins whose 4K links \"Upgrade to 4K\" may use while a video plays. The best 4K link is readied in the background; the button shows once it can start instantly.")
        }
        if (!Public.dev) Text("Xstream picks the fastest servers for Quick Play for you — just switch it on or off.",
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp), fontSize = 13.sp, color = Muted)
        if (Public.dev && !vm.pluginMode()) {
        SectionLabel("Quick Start addons (group 1)")
        SettingsCard {
            if (!Public.dev && addons.any { Public.isDevChoice(it) }) Text("The built-in quick server always plays first. Addons you tick here join it.", Modifier.padding(20.dp), color = Muted)
            else if (streamAddons.isEmpty()) Text("No stream addons installed yet. Add one in Addons.", Modifier.padding(20.dp), color = Muted)
            streamAddons.forEachIndexed { i, name ->
                if (i > 0) RowDivider(20.dp)
                val on = name in chosen
                Row(
                    Modifier.fillMaxWidth().clickable {
                        vm.setText("qsAddons", (if (on) chosen - name else chosen + name).joinToString("|"))
                    }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(on, null, colors = CheckboxDefaults.colors(checkedColor = Accent, checkmarkColor = OnAccent))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(addonLabel(name), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Text(if (on) "Quick Start · plays first" else "All sources", fontSize = 12.sp, color = if (on) Accent else Muted)
                    }
                }
            }
        }

        SectionLabel("Addon speed")
        SettingsCard {
            val speeds = remember { AddonSpeed.all().filter { Public.dev || !Public.isDevChoice(it.first) } }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("Average time each addon takes to answer (last 10 lookups). Put the fastest ones in Quick Start.", fontSize = 13.sp, color = Muted)
                if (speeds.isEmpty()) Text("Open a few titles first — speeds show up here.", Modifier.padding(top = 10.dp), fontSize = 14.sp, color = Muted)
                val slowest = (speeds.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L)
                speeds.forEach { (name, ms) ->
                    val c = when { ms < 1500 -> Color(0xFF2BD67B); ms < 4000 -> Color(0xFFFFC145); else -> Color(0xFFFF5A5F) }
                    Column(Modifier.padding(top = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(addonLabel(name), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(if (ms < 1000) "$ms ms" else "%.1f s".format(ms / 1000.0), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c)
                        }
                        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x1FFFFFFF))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth((ms.toFloat() / slowest).coerceIn(0.04f, 1f)).clip(RoundedCornerShape(3.dp))
                                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(c.copy(alpha = 0.6f), c))))
                        }
                    }
                }
            }
        }

        }
        SectionLabel("Preferred audio language")
        SettingsCard {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("Tried in this order. The language is read from the file name, title and description. \"Original audio\" only counts when the file says Original / Org; files with no language written are \"Unlabelled\".", fontSize = 13.sp, color = Muted)
                langs.forEachIndexed { i, l ->
                    Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF222228)).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", Modifier.width(24.dp), fontWeight = FontWeight.Bold, color = Accent)
                        Text(langLabel(l), Modifier.weight(1f), fontSize = 16.sp)
                        if (i > 0) Text("↑", Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            val m = langs.toMutableList(); m.add(i - 1, m.removeAt(i)); vm.setText("qsLangs", m.joinToString(","))
                        }.padding(horizontal = 10.dp, vertical = 2.dp), fontSize = 18.sp)
                        Icon(Icons.Default.Close, "Remove", Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).clickable {
                            vm.setText("qsLangs", (langs - l).joinToString(","))
                        }.padding(4.dp), tint = Muted)
                    }
                }
                val others = QsLanguages.keys.filter { it !in langs }
                if (others.isNotEmpty()) {
                    Text("Add", Modifier.padding(top = 14.dp), fontSize = 13.sp, color = Muted)
                    others.chunked(4).forEach { row ->
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { l ->
                                Row(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF2A2A30)).clickable { vm.setText("qsLangs", (langs + l).joinToString(",")) }
                                    .padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Add, null, Modifier.size(14.dp))
                                    Text(langLabel(l), Modifier.padding(start = 4.dp), fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        SectionLabel("Quality")
        SettingsCard {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("Starting quality", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("Auto picks the best quality up to Full HD that starts fast. Changing it in the player is remembered.", fontSize = 13.sp, color = Muted)
                Chips(QualityLevels.map { it.id to it.label }, v("qsQuality")) { vm.setText("qsQuality", it); vm.setPlayerPref("quality", it) }
            }
            RowDivider(20.dp)
            ToggleRow("Hide duplicate qualities", "If two levels would play the same file, show only one.", isOn(v("qsHideDupes"))) { vm.setText("qsHideDupes", if (it) "yes" else "no") }
        }

        SectionLabel("Instant start")
        SettingsCard {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("Prepare video on the title page", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("While you look at a title, the video gets ready in the background so Play starts almost instantly. Uses a little data.", fontSize = 13.sp, color = Muted)
                Chips(listOf("off" to "Off", "wifi" to "Wi-Fi only", "always" to "Always"), v("qsPreload")) { vm.setText("qsPreload", it) }
            }
        }
    }
}

// ───────────── Layout > Detail Page ─────────────

@Composable
internal fun DetailPageSettings(vm: AppViewModel, onBack: () -> Unit) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    fun v(k: String) = texts[k] ?: Defaults[k] ?: ""
    fun yn(b: Boolean) = if (b) "yes" else "no"
    SimplePage("Detail Page", onBack) {
        SectionLabel("Sections")
        SettingsCard {
            ToggleRow("Ratings", "IMDb, plus Rotten Tomatoes, Metacritic and more with an MDBList key.", isOn(v("detailRatings"))) { vm.setText("detailRatings", yn(it)) }
            RowDivider(20.dp)
            ToggleRow("Cast & crew", "Director and cast row.", isOn(v("detailCast"))) { vm.setText("detailCast", yn(it)) }
            RowDivider(20.dp)
            ToggleRow("More like this", "Similar titles at the bottom.", isOn(v("detailMore"))) { vm.setText("detailMore", yn(it)) }
        }
        Text("Tip: long-press Play or any episode to choose a source yourself.", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), fontSize = 13.sp, color = Muted)
    }
}


/**
 * Quick Start › CloudStream (phones): each repository folds open to its plugins' providers (Netflix, Hotstar, Prime
 * Video…), each with a tick. Ticked providers are asked the moment a title page opens; the best quality that's ready
 * within 20 seconds plays first, else MovieBox.
 */
@Composable
private fun QuickCsSection(vm: AppViewModel, key: String, title: String, desc: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val texts by vm.texts.collectAsStateWithLifecycle()
    val picked = qsCsParse(texts[key])
    fun save(s: Set<String>) = vm.setText(key, s.joinToString("\n"))
    val repos by CsPlugins.repos.collectAsStateWithLifecycle()
    val catalog by CsPlugins.catalog.collectAsStateWithLifecycle()
    val enabled by CsPlugins.enabled.collectAsStateWithLifecycle()
    // Providers of every switched-on plugin (loading a plugin is what tells its providers).
    var providers by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(enabled) {
        CsPlugins.init(ctx)
        loading = true
        val m = LinkedHashMap<String, List<String>>()
        CsPlugins.enabledEntries().forEach { e ->
            val reg = runCatching { CsPlugins.ensureLoaded(e) }.getOrNull()
            m[e.key] = reg?.apis?.map { it.name }.orEmpty()
            providers = m.toMap()
        }
        loading = false
    }
    var open by remember { mutableStateOf<Set<String>>(emptySet()) }
    SectionLabel(title)
    SettingsCard {
        Text(desc, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), fontSize = 13.sp, color = Muted)
        val on = catalog.filter { it.key in enabled }
        if (on.isEmpty()) Text("No CloudStream plugins switched on. Add them in Settings › Plugins › CloudStream.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 14.sp, color = Muted)
        repos.filter { r -> on.any { it.repo == r.url } }.forEach { r ->
            val entries = on.filter { it.repo == r.url }
            val keys = entries.flatMap { e -> providers[e.key].orEmpty().map { csProviderKey(e.key, it) } }
            val nOn = keys.count { it in picked }
            val expanded = r.url in open
            RowDivider(20.dp)
            Row(Modifier.fillMaxWidth().clickable { open = if (expanded) open - r.url else open + r.url }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TriStateCheckbox(
                    when { keys.isEmpty() || nOn == 0 -> androidx.compose.ui.state.ToggleableState.Off; nOn == keys.size -> androidx.compose.ui.state.ToggleableState.On
                        else -> androidx.compose.ui.state.ToggleableState.Indeterminate },
                    onClick = { if (keys.isNotEmpty()) save(if (nOn == keys.size) picked - keys.toSet() else picked + keys) },
                    colors = CheckboxDefaults.colors(checkedColor = Accent, checkmarkColor = OnAccent),
                )
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(r.name.ifBlank { r.url.substringAfter("githubusercontent.com/").substringBefore('/') }, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text(if (keys.isEmpty() && loading) "Loading plugins…" else "$nOn of ${keys.size} picked", fontSize = 12.sp, color = if (nOn > 0) Accent else Muted)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Muted)
            }
            if (expanded) entries.forEach { e ->
                val provs = providers[e.key].orEmpty()
                if (provs.isEmpty()) Text("${e.name}: " + (if (loading) "loading…" else "no providers (didn't load)"),
                    Modifier.padding(start = 56.dp, top = 4.dp, bottom = 4.dp), fontSize = 13.sp, color = Muted)
                provs.forEach { name ->
                    val k = csProviderKey(e.key, name)
                    val sel = k in picked
                    Row(Modifier.fillMaxWidth().clickable { save(if (sel) picked - k else picked + k) }.padding(start = 40.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(sel, { save(if (sel) picked - k else picked + k) }, colors = CheckboxDefaults.colors(checkedColor = Accent, checkmarkColor = OnAccent))
                        Column(Modifier.weight(1f)) {
                            Text(name, fontSize = 15.sp)
                            if (provs.size > 1 || name != e.name) Text(e.name, fontSize = 11.sp, color = Muted)
                        }
                    }
                }
            }
        }
    }
}
