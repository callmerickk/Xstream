package com.example.streamnest

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private const val REPO_URL = "https://github.com/callmerickk/Xstream"

// ───────────── Help & Settings: page stack ─────────────

@Composable
fun SettingsHost(vm: AppViewModel, start: String = "root", onClose: () -> Unit) {
    val pages = remember { mutableStateListOf(start) }
    var devUnlocked by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") } // kept while you open a result and come back
    val back: () -> Unit = {
        if (pages.size > 1) {
            if (pages.last() == "dev") devUnlocked = false
            pages.removeAt(pages.lastIndex)
        } else onClose()
    }
    val open: (String) -> Unit = { pages.add(it) }
    BackHandler(pages.size > 1) { back() }

    when (pages.last()) {
        "profile" -> ProfileScreen(vm, back)
        "account" -> AccountScreen(vm, back)
        "layout" -> LayoutPage(vm, back, open)
        "homeLayout" -> HomeLayoutPage(vm, back)
        "cw" -> ContinueWatchingPage(vm, back)
        "poster" -> PosterStylePage(vm, back)
        "streams" -> StreamsPage(vm, back)
        "content" -> ContentPage(back, open)
        "addons" -> AddonsPage(vm, back)
        "integrations" -> IntegrationsPage(vm, back)
        "quickStart" -> QuickStartPage(vm, back)
        "detailPage" -> DetailPageSettings(vm, back)
        "streamFilters" -> StreamFiltersPage(vm, back)
        "collections" -> CollectionsSettingsPage(vm, back)
        "about" -> LicensesPage(back)
        "manual" -> ManualPage(back)
        "plugins" -> PluginsPage(back)
        "notifications" -> NotificationsPage(vm, back)
        "updates" -> UpdateLogPage(vm, back, open)
        "playback" -> PlaybackPage(vm, back)
        "network" -> NetworkPage(vm, back)
        "advanced" -> AdvancedPage(vm, back, open)
        "dev" -> if (devUnlocked) DevCorner(vm, back) else DevGate(vm, onUnlocked = { devUnlocked = true; vm.setText("devEver", "yes") }, onBack = back)
        else -> SettingsRootPage(vm, onClose, open, query) { query = it }
    }
}

@Composable
private fun Page(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().pageScroll().navigationBarsPadding().padding(bottom = 40.dp)) {
        PageHeader(title, onBack)
        content()
    }

@Composable
private fun SettingsRootPage(vm: AppViewModel, onClose: () -> Unit, open: (String) -> Unit, query: String, onQuery: (String) -> Unit) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    Page("Settings", onClose) {
        SettingsSearchBar(query, onQuery)
        if (query.isNotBlank()) SettingsSearchResults(query, open) else {
            Spacer(Modifier.height(4.dp))
            SettingsCard {
                SettingRow(Icons.Default.AccountCircle, "Account & Cloud Sync", "Sign in, auto sync across your phone and TV, watch history.") { open("account") }
            }
            SectionLabel("General")
            SettingsCard {
                SettingRow(Icons.Default.Palette, "Layout", "Themes, home, posters and navigation bar.") { open("layout") }
                RowDivider()
                SettingRow(Icons.Default.Extension, "Content & Discovery", "Manage addons and discovery sources.") { open("content") }
                RowDivider()
                SettingRow(Icons.Default.PlayArrow, "Playback", "Player, subtitles, and auto-play.") { open("playback") }
                RowDivider()
                SettingRow(Icons.Default.Dns, "Network & DNS", DnsProviders[texts["dns"] ?: "system"]?.first ?: "System default") { open("network") }
                RowDivider()
                SettingRow(Icons.Default.Link, "Integrations", "AI assistant, recommendations, TMDB and ratings.") { open("integrations") }
                RowDivider()
                SettingRow(Icons.Default.Notifications, "Notifications", "New episode alerts for shows in My List.") { open("notifications") }
            }
            SectionLabel("About")
            SettingsCard {
                SettingRow(Icons.Default.Info, "Licenses & Attribution", "Data sources, acknowledgements, and licenses.") { open("about") }
                RowDivider()
                SettingRow(Icons.Default.TouchApp, "Replay the visual tour", "Shows the guide on Home, title pages, the player and My Space again.") {
                    Tour.reset(); Snack.show("The visual tour will show again on each screen")
                }
                RowDivider()
                run {
                    // "Update" badge while a newer version is waiting (e.g. after "Later").
                    val newer by Updater.available.collectAsStateWithLifecycle()
                    SettingRow(Icons.Default.NewReleases, "Update Log", if (newer != null) "New version ${newer?.versionName} is ready to install." else "What's new in v${UserLog.first().version}, past versions and updates.",
                        badge = if (newer != null) "Update" else "v${UserLog.first().version}") { open("updates") }
                }
            }
            SectionLabel("Advanced")
            SettingsCard {
                SettingRow(Icons.Default.Tune, "Advanced", "Startup, cache and Developer's Corner.") { open("advanced") }
            }
            Column(Modifier.fillMaxWidth().padding(top = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppLogo(vm, 26.dp)
                    Spacer(Modifier.width(8.dp))
                    Wordmark(t("appName"))
                    Text("by Rick", Modifier.padding(start = 6.dp, top = 4.dp), fontSize = 13.sp, color = Muted, fontWeight = FontWeight.Medium)
                }
                Text("Version ${vm.appVersion}", Modifier.padding(top = 6.dp), fontSize = 14.sp, color = Muted)
            }
        }
    }
}

// ───────────── Layout ─────────────

private data class ThemeOpt(val name: String, val c1: Long, val c2: Long, val enhanced: Boolean = false)

private val Themes = listOf(
    ThemeOpt("White", 0xFFF2F2F2, 0xFFFFFFFF), ThemeOpt("Crimson", 0xFFE8323C, 0xFFFF6A5C),
    ThemeOpt("Ocean", 0xFF2E86DE, 0xFF5AB0FF), ThemeOpt("Violet", 0xFF9B3FD1, 0xFFC27BFF),
    ThemeOpt("Emerald", 0xFF2EAD5B, 0xFF5CD98A), ThemeOpt("Amber", 0xFFF5A20A, 0xFFFFC857),
    ThemeOpt("Rose", 0xFFE3376F, 0xFFFF7AA6),
    ThemeOpt("Messenger", 0xFF0A84FF, 0xFF00C6FF, true), ThemeOpt("Amethyst", 0xFF8B4DFF, 0xFFD08CFF, true),
    ThemeOpt("Blossom", 0xFFE3376F, 0xFFFF9AC2, true), ThemeOpt("Lagoon", 0xFF1294C8, 0xFF3EE6C1, true),
    ThemeOpt("Sunset", 0xFFFF5E62, 0xFFFFB347, true),
)

private fun hex(c: Color) = String.format("#%06X", c.toArgb() and 0xFFFFFF)

private fun applyTheme(vm: AppViewModel, name: String, c1: Color, c2: Color) {
    vm.setText("theme", name)
    vm.setText("accent", hex(c1))
    vm.setText("accent2", hex(c2))
    vm.setText("deckTint", hex(lerp(Color(0xFF0B0F1A), c1, 0.22f)))
}

@Composable
private fun ThemeDot(name: String, c1: Color, c2: Color, enhanced: Boolean, custom: Boolean, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(96.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(74.dp).then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(18.dp)) else Modifier).padding(6.dp),
            Alignment.Center,
        ) {
            val fill = when {
                custom -> Modifier.border(4.dp, Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)), CircleShape).background(Color.Black, CircleShape)
                enhanced -> Modifier.background(Brush.sweepGradient(listOf(c2, c1, c2)), CircleShape)
                else -> Modifier.background(c1, CircleShape)
            }
            Box(Modifier.size(58.dp).clip(CircleShape).then(fill), Alignment.Center) {
                if (selected) Icon(Icons.Default.Check, null, tint = if (c1.red + c1.green + c1.blue > 2.2f) Color.Black else Color.White)
            }
        }
        Text(name, fontSize = 14.sp, letterSpacing = 1.sp, color = if (selected) Color.White else Muted, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        Box(Modifier.padding(top = 6.dp).width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Brush.horizontalGradient(listOf(c1, c2))))
    }
}

@Composable
private fun LayoutPage(vm: AppViewModel, onBack: () -> Unit, open: (String) -> Unit) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    val cur = texts["theme"] ?: Defaults["theme"]!!
    var navDialog by remember { mutableStateOf(false) }
    var customDialog by remember { mutableStateOf(false) }
    val navStyle = texts["navStyle"] ?: "adaptive"
    val navNames = linkedMapOf("adaptive" to "Adaptive", "expanded" to "Always Expanded", "compact" to "Always Compact")

    Page("Layout", onBack) {
        SectionLabel("Theme")
        SettingsCard {
            Text("Classic themes", Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp), fontSize = 15.sp, color = Muted, letterSpacing = 1.sp)
            Themes.filter { !it.enhanced }.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { th -> ThemeDot(th.name, Color(th.c1), Color(th.c2), false, false, cur == th.name) { applyTheme(vm, th.name, Color(th.c1), Color(th.c2)) } }
                    repeat(3 - row.size) { Spacer(Modifier.width(96.dp)) }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Color(0x12FFFFFF))
            Text("Enhanced themes", Modifier.padding(start = 20.dp, top = 6.dp, bottom = 4.dp), fontSize = 15.sp, color = Muted, letterSpacing = 1.sp)
            val enhanced = Themes.filter { it.enhanced }
            (enhanced.map { it.name } + "Custom").chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { name ->
                        val th = enhanced.firstOrNull { it.name == name }
                        if (th != null) ThemeDot(th.name, Color(th.c1), Color(th.c2), true, false, cur == th.name) { applyTheme(vm, th.name, Color(th.c1), Color(th.c2)) }
                        else ThemeDot("Custom", Palette.accent, Palette.accent2, false, true, cur == "Custom") { customDialog = true }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.width(96.dp)) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        SectionLabel("Display")
        SettingsCard {
            ToggleRow("AMOLED Black", "Use pure black backgrounds for OLED screens.", isOn(texts["amoled"] ?: "no")) { vm.setText("amoled", if (it) "yes" else "no") }
            RowDivider(20.dp)
            SettingRow(null, "Navigation Bar", navNames[navStyle] ?: "Adaptive") { navDialog = true }
        }
        if (!BuildConfig.IS_TV) {
            SectionLabel("App Icon")
            AppIconPicker()
        }
        SectionLabel("Home")
        SettingsCard {
            SettingRow(null, "Home Layout", "Hero cards, row order and visibility.") { open("homeLayout") }
            RowDivider(20.dp)
            SettingRow(null, "Continue Watching", "Settings for the Continue Watching section.") { open("cw") }
            RowDivider(20.dp)
            SettingRow(null, "Poster Card Style", "Tune card width and corner radius.") { open("poster") }
        }
        SectionLabel("Streams")
        SettingsCard { SettingRow(null, "Streams", "How stream sources are listed.") { open("streams") } }
        SectionLabel("Detail Page")
        SettingsCard { SettingRow(null, "Detail Page", "Ratings, cast and more-like-this on title pages.") { open("detailPage") } }
    }

    if (navDialog) PremiumAlert(
        onDismissRequest = { navDialog = false },
        containerColor = Surface1,
        title = { Text("Navigation Bar Style") },
        text = {
            Column {
                navNames.forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.setText("navStyle", key); navDialog = false }.padding(vertical = 14.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        if (navStyle == key) Icon(Icons.Default.Check, null)
                    }
                }
                Text("Adaptive: the capsule hides and the bar shrinks while you scroll down.", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {},
    )

    if (customDialog) {
        var a by remember { mutableStateOf(hex(Palette.accent)) }
        var b by remember { mutableStateOf(hex(Palette.accent2)) }
        PremiumAlert(
            onDismissRequest = { customDialog = false },
            containerColor = Surface1,
            title = { Text("Custom theme") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(a, { a = it }, singleLine = true, label = { Text("Main colour (hex)") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.tvOkKeyboard(), keyboardOptions = tvKeyboard())
                    OutlinedTextField(b, { b = it }, singleLine = true, label = { Text("Second colour (hex)") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.tvOkKeyboard(), keyboardOptions = tvKeyboard())
                    val c1 = parseColor(a); val c2 = parseColor(b)
                    Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (c1 != null && c2 != null) Brush.horizontalGradient(listOf(c1, c2)) else Brush.horizontalGradient(listOf(Color.DarkGray, Color.DarkGray))))
                }
            },
            confirmButton = {
                TextButton({
                    val c1 = parseColor(a); val c2 = parseColor(b)
                    if (c1 != null && c2 != null) { applyTheme(vm, "Custom", c1, c2); customDialog = false }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton({ customDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HomeLayoutPage(vm: AppViewModel, onBack: () -> Unit) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    val all by vm.shelves.collectAsStateWithLifecycle()
    fun v(k: String) = texts[k] ?: Defaults[k] ?: ""
    val hidden = v("rowsHidden").split("|").filter { it.isNotBlank() }.toSet()
    val rows = arrangeShelves(all, v("rowsOrder"))
    val visibleCount = rows.count { it.title !in hidden }

    Page("Home Layout", onBack) {
        SettingsCard {
            ToggleRow("Recommended right after Continue Watching", "Off: \"Recommended for you\" moves to the end of Home.", isOn(v("recsAfterCw"))) {
                vm.setText("recsAfterCw", if (it) "yes" else "no")
            }
        }
        Column(Modifier.padding(16.dp).fillMaxWidth().clip(CardShape).background(CardBg).border(1.dp, Color(0x12FFFFFF), CardShape).padding(20.dp)) {
            Text("Keep Home focused", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("$visibleCount of ${rows.size} rows visible • Hero ${if (isOn(v("showHero"))) "on" else "off"}", fontSize = 16.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
            Text("Hide rows you don't need and move your favourites up.", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
        }
        SectionLabel("Home layout")
        SettingsCard {
            ToggleRow("Show Hero Section", "Display the sliding cards at the top of Home.", isOn(v("showHero"))) { vm.setText("showHero", if (it) "yes" else "no") }
            RowDivider(20.dp)
            ToggleRow("Trailers on cards", "When you swipe to a card yourself, its trailer plays muted (tap the speaker for sound).", isOn(v("cardTrailers"))) { vm.setText("cardTrailers", if (it) "yes" else "no") }
            RowDivider(20.dp)
            ToggleRow("Auto-slide", "Cards move on their own every few seconds.", v("autoSlide").trim().lowercase() != "no") { vm.setText("autoSlide", if (it) "yes" else "no") }
            Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 5, 8, 10).forEach { sec ->
                    FilterChip(v("slideSeconds").trim() == sec.toString(), { vm.setText("slideSeconds", sec.toString()) }, { Text("$sec sec") })
                }
            }
        }
        SettingsCard {
            ToggleRow("Addon poster designs", "Show posters exactly as your catalog addon designs them (rank numbers, ratings, providers), in their own shape — wide, square or tall.", isOn(v("addonPosters"))) {
                vm.setText("addonPosters", if (it) "yes" else "no")
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel("Rows") }
            TextButton({ vm.setText("rowsHidden", ""); vm.setText("rowsOrder", "") }, Modifier.padding(top = 12.dp)) {
                Text("Reset to Default", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        if (rows.isEmpty()) Text("Rows appear here once Home has loaded.", Modifier.padding(horizontal = 24.dp), color = Muted)
        else SettingsCard {
            rows.forEachIndexed { i, shelf ->
                if (i > 0) RowDivider(20.dp)
                val visible = shelf.title !in hidden
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(shelf.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${shelf.items.size} titles · ${if (visible) "Visible" else "Hidden"}", fontSize = 13.sp, color = Muted)
                    }
                    Switch(visible, { on ->
                        val h = hidden.toMutableSet(); if (on) h.remove(shelf.title) else h.add(shelf.title)
                        vm.setText("rowsHidden", h.joinToString("|"))
                    })
                    IconButton({ move(vm, rows, i, -1) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                    IconButton({ move(vm, rows, i, 1) }, enabled = i < rows.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                }
            }
        }
    }
}

private fun move(vm: AppViewModel, rows: List<Shelf>, i: Int, by: Int) {
    val list = rows.map { it.title }.toMutableList()
    val j = i + by
    if (j !in list.indices) return
    val x = list.removeAt(i); list.add(j, x)
    vm.setText("rowsOrder", list.joinToString("|"))
}

@Composable
private fun StyleTile(title: String, sub: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit, art: @Composable () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.clip(shape).background(CardBg).border(if (selected) 2.dp else 1.dp, if (selected) Color.White else Color(0x12FFFFFF), shape)
            .clickable(onClick = onClick).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF26262C)), Alignment.Center) { art() }
        Text(title, Modifier.padding(top = 10.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(sub, fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ContinueWatchingPage(vm: AppViewModel, onBack: () -> Unit) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    val style = texts["cwStyle"] ?: "card"
    var confirm by remember { mutableStateOf(false) }
    Page("Continue Watching", onBack) {
        SectionLabel("Visibility")
        SettingsCard {
            ToggleRow("Show Continue Watching", "Display the Continue Watching shelf on the Home screen.", isOn(texts["cwShow"] ?: "yes")) { vm.setText("cwShow", if (it) "yes" else "no") }
        }
        SectionLabel("Card style")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StyleTile("Card", "TV-style landscape card", style == "card", Modifier.weight(1f), { vm.setText("cwStyle", "card") }) {
                Column(Modifier.width(70.dp)) {
                    Box(Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF55555E)))
                    Box(Modifier.padding(top = 6.dp).width(46.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF8A8A94)))
                }
            }
            StyleTile("Wide", "Info on the artwork", style == "wide", Modifier.weight(1f), { vm.setText("cwStyle", "wide") }) {
                Box(Modifier.width(78.dp).height(42.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF55555E))) {
                    Box(Modifier.align(Alignment.BottomStart).padding(5.dp).width(40.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
                }
            }
            StyleTile("Poster", "Artwork-first tall card", style == "poster", Modifier.weight(1f), { vm.setText("cwStyle", "poster") }) {
                Box(Modifier.width(34.dp).height(50.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF55555E))) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Accent))
                }
            }
        }
        SettingsCard {
            SettingRow(null, "Replay the app guide", "Shows the quick tips again (home, menu, player, subtitles).") { vm.setText("guideDone", "") }
        }
        SectionLabel("Cache")
        SettingsCard { SettingRow(Icons.Default.DeleteSweep, "Clear Continue Watching", "Remove everything from this profile's Continue Watching.") { confirm = true } }
    }
    if (confirm) PremiumAlert(
        onDismissRequest = { confirm = false }, containerColor = Surface1,
        title = { Text("Clear Continue Watching?") },
        text = { Text("Your watchlist stays. Only resume points for this profile are removed.") },
        confirmButton = { TextButton({ vm.progress.value.map { it.key }.forEach { vm.removeProgress(it) }; confirm = false }) { Text("Clear") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun PosterStylePage(vm: AppViewModel, onBack: () -> Unit) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    fun v(k: String) = texts[k] ?: Defaults[k] ?: ""
    val wName = v("posterWidth"); val cName = v("posterCorner")
    val w = PosterWidths[wName] ?: 126; val c = PosterCorners[cName] ?: 12
    val landscape = isOn(v("landscapePosters"))
    Page("Poster Card Style", onBack) {
        Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel("Poster card style") }
            TextButton({ listOf("posterWidth", "posterCorner", "landscapePosters", "hideLabels").forEach { vm.setText(it, Defaults[it]!!) } }, Modifier.padding(top = 12.dp)) {
                Text("Reset to Default", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        SettingsCard {
            Column(Modifier.padding(20.dp)) {
                Text("Tune card width and corner radius.", color = Muted)
                Text("Live Preview", Modifier.padding(top = 12.dp, bottom = 10.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.Top) {
                    val pw = if (landscape) (w * 1.87f).coerceAtMost(200f) else w.toFloat()
                    Box(
                        Modifier.width(pw.dp).aspectRatio(if (landscape) 16f / 9f else 2f / 3f).clip(RoundedCornerShape(c.dp))
                            .background(Brush.linearGradient(listOf(Palette.accent.copy(alpha = 0.55f), Color(0xFF1C1C22)))),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Width: ${if (landscape) (w * 1.87f).toInt() else w}dp", fontSize = 16.sp)
                        Text("Corner radius: ${c}dp", fontSize = 16.sp)
                        Text("Height: ${if (landscape) (w * 1.87f * 9 / 16).toInt() else w * 3 / 2}dp", fontSize = 14.sp, color = Muted)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 16.dp), color = Color(0x12FFFFFF))
                Text("Width ($wName)", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                ChipGrid(PosterWidths.keys.toList(), wName) { vm.setText("posterWidth", it) }
                Text("Corner Radius ($cName)", Modifier.padding(top = 12.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                ChipGrid(PosterCorners.keys.toList(), cName) { vm.setText("posterCorner", it) }
            }
            ToggleRow("Landscape Posters", "Wide Hotstar-style cards on Home.", landscape) { vm.setText("landscapePosters", if (it) "yes" else "no") }
            ToggleRow("Hide labels", "Hide title logos on wide cards.", isOn(v("hideLabels"))) { vm.setText("hideLabels", if (it) "yes" else "no") }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ChipGrid(options: List<String>, selected: String, onPick: (String) -> Unit) {
    options.chunked(3).forEach { row ->
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { o ->
                val on = o == selected
                val shape = RoundedCornerShape(14.dp)
                Text(
                    o, Modifier.clip(shape).background(if (on) Color(0xFF2E2E36) else Color.Transparent)
                        .border(1.dp, if (on) Color.Transparent else Color(0x33FFFFFF), shape).clickable { onPick(o) }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    fontSize = 16.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) Color.White else Muted,
                )
            }
        }
    }
}

@Composable
private fun StreamsPage(vm: AppViewModel, onBack: () -> Unit) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    val pack by vm.badgePack.collectAsStateWithLifecycle()
    fun v(k: String) = texts[k] ?: Defaults[k] ?: ""
    LaunchedEffect(v("badgePack")) { vm.loadBadgePack(v("badgePack")) }
    val sample = remember {
        StreamItem("Sample Addon", StreamInfo(url = "x", name = "FROM • S03E09",
            description = "1080p WEBRip HEVC 10bit\nDD+ 5.1 · AMZN\n💾 2.73 GB"))
    }
    Page("Streams", onBack) {
        SectionLabel("Preview")
        Box(Modifier.padding(horizontal = 16.dp)) { StreamCard(sample, isOn(v("fusionBadges")), isOn(v("showAddonName")), pack) {} }
        SectionLabel("Badges")
        SettingsCard {
            ToggleRow("Fusion badges", "Show quality, HDR, audio, source, codec and service badges on every stream.", isOn(v("fusionBadges"))) { vm.setText("fusionBadges", if (it) "yes" else "no") }
            RowDivider(20.dp)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("Badge pack URL", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("Optional. A JSON link of pattern → image (for example service logos). Leave empty for the built-in badges.", fontSize = 13.sp, color = Muted)
                var url by remember { mutableStateOf(v("badgePack")) }
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().padding(top = 10.dp).tvOkKeyboard(), singleLine = true, shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("https://…/badges.json") },
                    trailingIcon = { if (url != v("badgePack")) TextButton({ vm.setText("badgePack", url.trim()) }) { Text("Save") } }, keyboardOptions = tvKeyboard())
                if (v("badgePack").isNotBlank()) Text(if (pack.isEmpty()) "No badges loaded from this link yet." else "${pack.size} badges loaded.", Modifier.padding(top = 6.dp), fontSize = 13.sp, color = if (pack.isEmpty()) Muted else Accent)
            }
        }
        SectionLabel("Display")
        SettingsCard {
            ToggleRow("Addon name", "Show which addon each stream source comes from.", isOn(v("showAddonName"))) { vm.setText("showAddonName", if (it) "yes" else "no") }
        }
    }
}

// ───────────── App icon picker ─────────────

@Composable
private fun AppIconPicker() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var active by remember { mutableStateOf(AppIcons.active(ctx)) }
    var pending by remember { mutableStateOf(AppIcons.pending(ctx)) }
    var ask by remember { mutableStateOf<String?>(null) }
    val chosen = pending ?: active
    SettingsCard {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AppIcons.all.forEach { o ->
                val on = chosen == o.id
                Column(
                    Modifier.width(100.dp).clip(RoundedCornerShape(18.dp)).clickable {
                        if (o.id == active && pending != null) { AppIcons.setPending(ctx, null); pending = null }
                        else if (o.id != chosen) ask = o.id
                    }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(o.res), o.label,
                        Modifier.size(68.dp).clip(RoundedCornerShape(16.dp))
                            .then(if (on) Modifier.border(2.dp, Color.White, RoundedCornerShape(16.dp)) else Modifier),
                    )
                    Text(o.label, Modifier.padding(top = 8.dp), fontSize = 14.sp, color = if (on) Color.White else Muted,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    when {
                        o.id == pending -> Text("Applies after restart", Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(Accent.copy(alpha = 0.18f))
                            .padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = Accent, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        o.id == "signal" -> Text("Default", Modifier.padding(top = 4.dp), fontSize = 11.sp, color = Muted)
                    }
                }
            }
        }
    }
    ask?.let { id ->
        val name = AppIcons.all.first { it.id == id }.label
        PremiumAlert(
            onDismissRequest = { ask = null },
            title = { Text("Restart Xstream to apply the new icon?") },
            text = { Text("$name will replace your current app icon. Restart now, or choose Later and it changes the next time Xstream is fully closed and opened again.") },
            confirmButton = {
                Button({ AppIcons.markPicked(ctx); (ctx as? android.app.Activity)?.let { AppIcons.applyAndRestart(it, id) } }, shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)) { Text("Restart now", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton({ AppIcons.markPicked(ctx); AppIcons.setPending(ctx, id); pending = id; active = AppIcons.active(ctx); ask = null }) { Text("Later", color = Color.White) }
            },
        )
    }
}

// ───────────── Content & Discovery ─────────────

@Composable
private fun ContentPage(onBack: () -> Unit, open: (String) -> Unit) {
    Page("Content & Discovery", onBack) {
        SectionLabel("Sources")
        SettingsCard {
            SettingRow(null, "Addons", "Install, remove, refresh, and sort your content sources.") { open("addons") }
            RowDivider(20.dp)
            SettingRow(null, "Stream Filters", "Include, exclude or force HDR, Dolby Vision, 4K and more.") { open("streamFilters") }
            RowDivider(20.dp)
            SettingRow(null, "Quick Start", "Fast addons for instant play, quality selector and audio language.") { open("quickStart") }
            if (!BuildConfig.IS_TV) {
                RowDivider(20.dp)
                SettingRow(null, "Plugins", "Install JavaScript plugins that find streams, next to your addons.") { open("plugins") }
            }
        }
        SectionLabel("Discovery")
        SettingsCard {
            SettingRow(null, "Collections", "Import Nuvio collections (studios, networks, genres…) for Home.") { open("collections") }
        }
    }
}

// ───────────── Stream filters (visual tags) ─────────────

@Composable
private fun StreamFiltersPage(vm: AppViewModel, onBack: () -> Unit) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    val rules = remember(texts["tagRules"]) { parseTagRules(texts["tagRules"] ?: "") }
    fun set(id: String, r: TagRule) = vm.setText("tagRules", encodeTagRules(rules + (id to r)))
    Page("Stream Filters", onBack) {
        DeviceScopeCard(vm, listOf("tagRules"), "stream filters")
        Text("Applies to every addon's streams, on the sources page, in the player and for auto-play.",
            Modifier.padding(horizontal = 24.dp, vertical = 4.dp), fontSize = 14.sp, color = Muted)
        SettingsCard {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LegendLine("Include", "Shown first and preferred by auto-play. Others still show.")
                LegendLine("Exclude", "Never shown and never auto-played.")
                LegendLine("Forced", "ONLY streams with this tag are shown and loaded. Forcing several keeps streams with any of them.")
            }
        }
        if (rules.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(end = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton({ vm.setText("tagRules", "") }) { Text("Reset all filters", color = Color.White, fontWeight = FontWeight.SemiBold) }
        }
        TagDefs.groupBy { it.group }.forEach { (group, defs) ->
            SectionLabel(group)
            SettingsCard {
                defs.forEachIndexed { i, d ->
                    if (i > 0) RowDivider(20.dp)
                    val cur = rules[d.id] ?: TagRule.Off
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Text(d.label, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF222228)).padding(3.dp)) {
                            TagRule.entries.forEach { r ->
                                val on = r == cur
                                val c = when (r) { TagRule.Exclude -> Color(0xFFE5484D); TagRule.Forced -> Accent; TagRule.Include -> Color(0xFF3FB27F); else -> Color(0xFF3A3A42) }
                                Text(r.label, Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) c else Color.Transparent)
                                    .clickable { set(d.id, r) }.padding(vertical = 8.dp),
                                    fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                    color = if (on) (if (r == TagRule.Forced) OnAccent else Color.White) else Muted, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
        Text("SDR also matches streams that don't mention HDR or Dolby Vision at all.",
            Modifier.padding(horizontal = 24.dp, vertical = 12.dp), fontSize = 13.sp, color = Muted)
    }
}

@Composable
private fun LegendLine(name: String, desc: String) = Row {
    Text(name, Modifier.width(78.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Text(desc, fontSize = 14.sp, color = Muted)
}

@Composable
private fun AddonsPage(vm: AppViewModel, onBack: () -> Unit) {
    val addons by vm.addons.collectAsStateWithLifecycle()
    val texts by vm.texts.collectAsStateWithLifecycle()
    val error by vm.addError.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    val activeCount = remember(addons, texts) { addons.count { vm.isAddonOn(it) } }
    val catalogs = addons.sumOf { it.manifest.catalogs.size }

    Page(t("addonsTitle"), onBack) {
        SectionLabel("Overview")
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(CardShape).background(CardBg).padding(vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("${addons.size}" to "Addons", "$activeCount" to "Active", "$catalogs" to "Catalogs").forEachIndexed { i, (n, l) ->
                if (i > 0) Box(Modifier.width(1.dp).height(44.dp).background(Color(0x22FFFFFF)))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(n, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(l, fontSize = 16.sp, color = Muted)
                }
            }
        }
        SectionLabel("Add addon")
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(CardShape).background(CardBg).padding(18.dp)) {
            Text(t("addonsHint"), fontSize = 13.sp, color = Muted, modifier = Modifier.padding(bottom = 10.dp))
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().tvOkKeyboard(), singleLine = true, placeholder = { Text(t("addonUrlHint")) }, shape = RoundedCornerShape(16.dp), keyboardOptions = tvKeyboard())
            Spacer(Modifier.height(12.dp))
            Button(
                { vm.addAddon(url); url = "" }, Modifier.fillMaxWidth().height(54.dp), enabled = url.isNotBlank(), shape = RoundedCornerShape(27.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF2F2F2), contentColor = Color.Black),
            ) { Text("Install Addon", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            error?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        }
        if (!BuildConfig.IS_TV) {
            SectionLabel("Quick server")
            var connect by remember { mutableStateOf(false) }
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(CardShape).background(CardBg).padding(18.dp)) {
                val own = QuickServer.hasOwn(vm)
                Text(if (own) "Your own account is connected ✓" else "Connect your own account", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(if (own) "Videos start from your own quick server account." else "Sign in to PenguPlay once — your own account joins the quick server, so busy hours don't slow you down.",
                    fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                if (!own) Button({ connect = true }, Modifier.padding(top = 12.dp).fillMaxWidth().height(48.dp), shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)) { Text("Connect PenguPlay", fontWeight = FontWeight.SemiBold) }
            }
            if (connect) androidx.compose.ui.window.Dialog({ connect = false }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                PenguConnectSheet(vm) { connect = false }
            }
        }
        SectionLabel("Backup & move")
        AddonBackupCard(vm)
        SectionLabel("Installed addons")
        val devView = Public.dev
        // Normal users: the built-in servers are looked after by Xstream (no names, nothing to change).
        if (!devView && addons.any { Public.isDevChoice(it) }) Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(CardShape).background(CardBg).padding(18.dp)) {
            Text("Built-in servers", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("The quick server and HD servers are set up and looked after by Xstream.", fontSize = 14.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
        }
        addons.forEachIndexed { i, a ->
            if (!devView && Public.isDevChoice(a)) return@forEachIndexed
            val on = vm.isAddonOn(a)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(CardShape).background(CardBg).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF26262C)), Alignment.Center) {
                        Icon(Icons.Default.Extension, null, Modifier.size(32.dp), tint = Color(0xFF6FB6FF))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.label(), fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (a.label() != a.manifest.name) "${a.manifest.name} · Version ${a.manifest.version}" else "Version ${a.manifest.version}", fontSize = 15.sp, color = Muted)
                    }
                    Switch(on, { vm.toggleAddon(a) })
                }
                val pinned = vm.isPinned(a)
                val fixed = vm.isPinnedFixed(a)
                val addonCtx = androidx.compose.ui.platform.LocalContext.current
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    // Configure: opens the addon's own settings page.
                    TextButton({
                        runCatching { addonCtx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(vm.configureUrl(a))).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { Snack.show("No browser to open the addon's settings") }
                    }) { Icon(Icons.Default.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Configure") }
                    // Rename: the name shown everywhere in Xstream (the addon itself doesn't change).
                    var renaming by remember { mutableStateOf(false) }
                    IconButton({ renaming = true }) { Icon(Icons.Default.Edit, "Rename") }
                    if (renaming) {
                        var nm by remember { mutableStateOf(a.label()) }
                        // Saves the name for THIS addon (by its link) and moves its Quick Start tick to the new name.
                        fun saveName(newName: String) {
                            val old = a.label()
                            vm.setText("addonLabel:${a.baseUrl}", newName)
                            val now = a.label()
                            val qs = vm.qsAddons()
                            if (old != now && old in qs) vm.setText("qsAddons", (qs - old + now).joinToString("|"))
                            renaming = false
                        }
                        AlertDialog(
                            onDismissRequest = { renaming = false }, containerColor = Surface1,
                            title = { Text("Rename addon", fontWeight = FontWeight.Bold) },
                            text = {
                                Column {
                                    Text("Only this addon. Its name everywhere in Xstream; a name with Pengu makes it a quick server, with HdHub the HD step.", fontSize = 13.sp, color = Muted)
                                    OutlinedTextField(nm, { nm = it.take(40) }, Modifier.fillMaxWidth().padding(top = 10.dp).tvOkKeyboard(), singleLine = true, label = { Text("Name") }, keyboardOptions = tvKeyboard())
                                }
                            },
                            confirmButton = { TextButton({ saveName(nm.trim()) }) { Text("Save") } },
                            dismissButton = { TextButton({ saveName("") }) { Text("Original name") } },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // Pin: stays at the top (Cinemeta and OpenSubtitles always).
                    IconButton({ if (fixed) Snack.show("${a.label()} always stays at the top") else vm.togglePin(a) }) {
                        Icon(Icons.Default.PushPin, if (pinned) "Unpin" else "Pin", tint = if (pinned) Accent else Color(0xFF8A94A8))
                    }
                    val prevPinned = addons.getOrNull(i - 1)?.let { vm.isPinned(it) } == true
                    val nextPinned = addons.getOrNull(i + 1)?.let { vm.isPinned(it) } == true
                    IconButton({ vm.moveAddon(a, -1) }, enabled = i > 0 && !fixed && (pinned || !prevPinned)) { Icon(Icons.Default.ArrowUpward, "Move up") }
                    IconButton({ vm.moveAddon(a, 1) }, enabled = i < addons.lastIndex && !fixed && (!pinned || nextPinned)) { Icon(Icons.Default.ArrowDownward, "Move down") }
                    IconButton({ vm.reloadAddon(a) }) { Icon(Icons.Default.Refresh, "Refresh") }
                    if (a.baseUrl != CINEMETA) IconButton({ vm.removeAddon(a) }) { Icon(Icons.Default.Delete, "Remove", tint = Color(0xFFE86F8A)) }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0x12FFFFFF))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(if (on) "Active" else "Off", "${vm.resourceCount(a)} resources", "${a.manifest.catalogs.size} catalogs").forEach {
                        Text(it, Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF26262C)).padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                a.manifest.description?.let { Text(it, Modifier.padding(top = 10.dp), fontSize = 15.sp, color = Color(0xFFC9CEDB), maxLines = 3, overflow = TextOverflow.Ellipsis) }
                if (a.manifest.types.isNotEmpty()) Text(a.manifest.types.joinToString(" / ") { it.replaceFirstChar { c -> c.uppercase() } }, Modifier.padding(top = 6.dp), fontSize = 13.sp, color = Muted)
            }
        }
    }
}

// ───────────── About & Advanced ─────────────

@Composable
private fun LicensesPage(onBack: () -> Unit) {
    val items = listOf(
        "Cinemeta" to "Movie and series metadata, provided by the Stremio Cinemeta addon.",
        "Stremio addon protocol" to "Catalogs, streams and subtitles come from the addons you install.",
        "AndroidX Media3 / ExoPlayer" to "Video playback. Apache License 2.0.",
        "Jetpack Compose & Material 3" to "User interface. Apache License 2.0.",
        "Coil" to "Image, GIF and video-frame loading. Apache License 2.0.",
        "OkHttp" to "Networking. Apache License 2.0.",
        "Kotlinx Serialization" to "JSON parsing. Apache License 2.0.",
        "Poppins" to "Wordmark font. SIL Open Font License 1.1.",
        "Inter" to "Player font, by The Inter Project Authors. SIL Open Font License 1.1.",
        "introdb.app" to "Intro, recap and outro timestamps for Skip Intro.",
        "Android speech services" to "Voice assistant uses the speech recognition and text-to-speech built into your phone.",
    )
    Page("Licenses & Attribution", onBack) {
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            items.forEachIndexed { i, (name, desc) ->
                if (i > 0) RowDivider(20.dp)
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Text(name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(desc, fontSize = 14.sp, color = Muted)
                }
            }
        }
        Text("This app does not host any content. Everything shown comes from the addons you add.", Modifier.padding(24.dp), fontSize = 13.sp, color = Muted)
    }
}

@Composable
private fun AdvancedPage(vm: AppViewModel, onBack: () -> Unit, open: (String) -> Unit) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    Page("Advanced", onBack) {
        SectionLabel("Startup")
        SettingsCard {
            ToggleRow("Startup animation", "Play the Xstream logo animation when the app opens.", isOn(texts["splash"] ?: "yes")) {
                vm.setText("splash", if (it) "yes" else "no")
            }
            RowDivider(20.dp)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("Auto-open last profile", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("On \"Who's watching?\", a ring counts down around the last profile and opens it if nobody picks another.", fontSize = 13.sp, color = Muted)
                val cur = texts["autoProfileSecs"] ?: "5"
                ChipGrid(listOf("Off", "3 s", "5 s", "10 s"), if (cur == "0") "Off" else "$cur s") { vm.setText("autoProfileSecs", if (it == "Off") "0" else it.removeSuffix(" s")) }
            }
            RowDivider(20.dp)
            ToggleRow("Skip \"Who's watching?\"", "Open the last profile straight away, without the profile screen.", isOn(texts["rememberProfile"] ?: "no")) {
                vm.setText("rememberProfile", if (it) "yes" else "no")
            }
        }
        SectionLabel("Cache")
        SettingsCard {
            SettingRow(null, "Clear Continue Watching Cache", "Remove cached Continue Watching data and refresh watch progress.") { confirm = true }
        }
        // Only for the Dev access list and phones where it was opened before (7 taps on the version in Update Log).
        if (Public.dev) {
            SectionLabel("Developer")
            SettingsCard {
                SettingRow(Icons.Default.Code, "Developer's Corner", "App name, logo, password and UI editing.") { open("dev") }
            }
        }
    }
    if (confirm) PremiumAlert(
        onDismissRequest = { confirm = false }, containerColor = Surface1,
        title = { Text("Clear Continue Watching?") },
        text = { Text("Resume points and watch stats for this profile will be reset. Your watchlist stays.") },
        confirmButton = { TextButton({ vm.clearHistory(); confirm = false }) { Text("Clear") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}

// ───────────── Update Log ─────────────

@Composable
internal fun ReleaseCard(r: Release, current: Boolean, footer: (@Composable () -> Unit)? = null) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(CardShape).background(CardBg)
        .border(1.dp, if (current) Accent.copy(alpha = 0.6f) else Color(0x12FFFFFF), CardShape).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("v${r.version}", Modifier.clip(RoundedCornerShape(10.dp)).background(if (current) Accent else Color(0xFF2A2A30))
                .padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (current) OnAccent else Color.White)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(r.date + if (current) "  ·  Installed" else "", fontSize = 13.sp, color = Muted)
            }
        }
        Spacer(Modifier.height(12.dp))
        r.changes.forEach { c ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Text("•", Modifier.width(18.dp), color = Accent, fontWeight = FontWeight.Bold)
                Text(c, fontSize = 15.sp, color = Color(0xFFDDE1EA), lineHeight = 21.sp)
            }
        }
        footer?.invoke()
    }
}

@Composable
private fun UpdateLogPage(vm: AppViewModel, onBack: () -> Unit, open: (String) -> Unit = {}) {
    val uri = LocalUriHandler.current
    var showOld by remember { mutableStateOf(false) }
    var taps by remember { mutableIntStateOf(0) }
    Page("Update Log", onBack) {
        SectionLabel("What's new")
        ReleaseCard(UserLog.first(), current = true) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton({ showOld = !showOld }) {
                    Text(if (showOld) "Hide previous logs" else "Previous logs", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Icon(if (showOld) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                }
            }
        }
        if (showOld) {
            SectionLabel("Previous logs")
            UserLog.drop(1).forEach { ReleaseCard(it, current = false) }
        }
        // The first page of the story, at the bottom of the version list.
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0x24E50914), Color(0x08E50914)))).border(1.dp, Color(0x59E50914), RoundedCornerShape(18.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(AppLook.logoRes(AppLook.logo.value)), null, Modifier.size(44.dp))
            Column(Modifier.padding(start = 14.dp)) {
                Text("10 October 2026 · Xstream was born ✦", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Where it all started. Made by Rick.", Modifier.padding(top = 2.dp), fontSize = 12.5.sp, color = Muted)
            }
        }
        // Seven taps on the version: Developer's Corner (password or fingerprint still needed).
        Text("Xstream ${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
            taps++; if (taps >= 7) { taps = 0; open("dev") }
        }.padding(top = 8.dp), textAlign = TextAlign.Center, fontSize = 12.sp, color = Muted)
        // In-app updates from GitHub Releases.
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val scope = rememberCoroutineScope()
        var checking by remember { mutableStateOf(false) }
        var note by remember { mutableStateOf<String?>(null) }
        var found by remember { mutableStateOf<UpdateInfo?>(null) }
        Button(
            {
                if (!checking) {
                    checking = true; note = null
                    scope.launch {
                        val i = runCatching { Updater.check(ctx, force = true) }.getOrNull()
                        checking = false
                        if (i != null) found = i
                        else note = Updater.error.value ?: "You have the newest version."
                    }
                }
            },
            Modifier.padding(16.dp).fillMaxWidth().height(54.dp), shape = RoundedCornerShape(27.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF2F2F2), contentColor = Color.Black),
        ) {
            Icon(Icons.Default.CloudDownload, null); Spacer(Modifier.width(8.dp))
            Text(if (checking) "Checking…" else "Check for Updates", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        note?.let { Text(it, Modifier.fillMaxWidth().padding(bottom = 6.dp), textAlign = TextAlign.Center, fontSize = 14.sp, color = Color.White) }
        found?.let { i ->
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { found = null },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) { UpdateDialog(i, onLater = { found = null }, onSkip = { Updater.skip(ctx, i); found = null }) { scope.launch { Updater.downloadAndInstall(ctx, i) } } }
        }
        Text("Installed: Version ${vm.appVersion}", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 13.sp, color = Muted)
    }
}

// ───────────── Playback ─────────────

private val RegexPresets = listOf(
    "Any 1080p+" to "(?i)(2160p|4k|1080p)",
    "4K / Remux" to "(?i)(2160p|4k|remux)",
    "1080p, no HDR" to "(?is)^(?!.*\\b(hdr|hdr10|dv|dolby ?vision)\\b).*1080p.*",
    "Small size (720p)" to "(?i)720p",
)

@Composable
private fun ColorChips(selected: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (name, hexv) ->
            val c = parseColor(hexv) ?: Color.Transparent
            val on = selected.equals(hexv, ignoreCase = true)
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(if (c.alpha == 0f) Color(0xFF2A2A30) else c)
                    .border(if (on) 3.dp else 1.dp, if (on) Accent else Color(0x44FFFFFF), CircleShape).clickable { onPick(hexv) },
                Alignment.Center,
            ) { if (c.alpha == 0f) Text("∅", fontSize = 14.sp, color = Muted) }
        }
    }
}

@Composable
private fun PlaybackPage(vm: AppViewModel, onBack: () -> Unit) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    fun v(k: String) = texts[k] ?: Defaults[k] ?: ""
    fun yn(b: Boolean) = if (b) "yes" else "no"
    var modeDialog by remember { mutableStateOf(false) }
    var regexDialog by remember { mutableStateOf(false) }
    val modes = linkedMapOf("manual" to ("Manual (choose stream)" to "Always show the source list and let me choose."),
        "first" to ("Auto-play first source" to "Play the first available source automatically."),
        "regex" to ("Auto-play regex match" to "Play the first source whose text matches your pattern."))
    val textColors = listOf("White" to "#FFFFFF", "Yellow" to "#FFE14D", "Cyan" to "#6FE3FF", "Green" to "#7CFF8A", "Pink" to "#FF8FB1")
    val bgColors = listOf("None" to "#00000000", "Shadow" to "#80000000", "Black" to "#FF000000", "Grey" to "#B3333333")
    val outlineColors = listOf("Black" to "#000000", "Dark grey" to "#333333", "Red" to "#B00020", "Blue" to "#0A3D91")

    Page("Playback", onBack) {
        StreamingModeSection(vm)
        DeviceScopeCard(vm, listOf("playerEngine", "playerChoice", "autoPlayMode", "autoRegex", "subSize", "subBold", "subColor", "subBg",
            "subOutline", "subOutlineColor", "seekThumbs", "startBright", "startBrightLevel", "startBrightMode", "startVol", "startVolLevel", "startVolMode"),
            "player, auto-play, regex, subtitle and start-volume settings")
        SectionLabel("Picture preference")
        SettingsCard {
            ToggleRow("Match my screen", "HDR moves up only when this screen can show HDR (HDR on a normal screen looks washed out).", isOn(v("picMatch").ifBlank { "yes" })) { vm.setText("picMatch", yn(it)) }
            RowDivider(20.dp)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("Which picture comes first in every quality, the upgrade and Sources.", fontSize = 13.sp, color = Muted)
                val order = (v("picPref").split("|").filter { it in PicturePref.ALL } + PicturePref.ALL).distinct()
                fun save(l: List<String>) = vm.setText("picPref", l.joinToString("|"))
                order.forEachIndexed { i, tag ->
                    Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF222228)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", Modifier.width(24.dp), fontWeight = FontWeight.Bold, color = Accent)
                        Text(tag, Modifier.weight(1f), fontSize = 15.sp)
                        IconButton({ if (i > 0) save(order.toMutableList().also { java.util.Collections.swap(it, i, i - 1) }) }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, "Move up") }
                        IconButton({ if (i < order.lastIndex) save(order.toMutableList().also { java.util.Collections.swap(it, i, i + 1) }) }, enabled = i < order.lastIndex) { Icon(Icons.Default.ArrowDownward, "Move down") }
                    }
                }
                Text("Default: SDR first · HDR and HDR10 last", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = Muted)
            }
        }
        SectionLabel("Player")
        SettingsCard {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("Default player", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("External opens VLC, MX Player or any app you pick.", fontSize = 13.sp, color = Muted)
                ChipGrid(listOf("Built-in", "External"), if (v("playerChoice") == "external") "External" else "Built-in") {
                    vm.setText("playerChoice", if (it == "External") "external" else "builtin")
                }
            }
            RowDivider(20.dp)
            ToggleRow("Content advisory", "Show Violence / Profanity / Nudity levels when a title starts (from IMDb's parental guide).", isOn(v("showAdvisory"))) { vm.setText("showAdvisory", yn(it)) }
            RowDivider(20.dp)
            ToggleRow("Info when paused", "Show title, rating and story after pausing for a few seconds.", isOn(v("pauseInfo"))) { vm.setText("pauseInfo", yn(it)) }
        }

        SectionLabel("Brightness")
        SettingsCard {
            ToggleRow("Set brightness when a video starts", "Applied once playback begins. Swipe up/down on the left side to change it while watching.", isOn(v("startBright"))) {
                vm.setText("startBright", yn(it))
            }
            RowDivider(20.dp)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                val lvl = (v("startBrightLevel").toFloatOrNull() ?: 50f).coerceIn(10f, 100f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Starting brightness", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text("How bright the screen is when a video begins.", fontSize = 13.sp, color = Muted)
                    }
                    Text("${lvl.toInt()}%", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    lvl, { vm.setText("startBrightLevel", it.toInt().toString()) }, valueRange = 10f..100f,
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Color(0x33FFFFFF)),
                )
            }
            RowDivider(20.dp)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                val lvl = (v("startBrightLevel").toFloatOrNull() ?: 50f).coerceIn(10f, 100f).toInt()
                Text("After I change it", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("Start every video at the level above, or keep the brightness you last swiped to.", fontSize = 13.sp, color = Muted)
                val fixedLabel = "Always start at $lvl%"
                val lastLabel = "Remember my last"
                ChipGrid(listOf(fixedLabel, lastLabel), if (v("startBrightMode") == "last") lastLabel else fixedLabel) {
                    vm.setText("startBrightMode", if (it == lastLabel) "last" else "fixed")
                }
            }
        }

        SectionLabel("Volume")
        SettingsCard {
            ToggleRow("Set volume when a video starts", "Only with earphones, headphones or a Bluetooth speaker; on the phone's own speaker your phone's volume is kept. Your volume comes back when you leave the player.", isOn(v("startVol"))) {
                vm.setText("startVol", yn(it))
            }
            RowDivider(20.dp)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                val lvl = (v("startVolLevel").toFloatOrNull() ?: 50f).coerceIn(10f, 100f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Starting volume", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text("How loud a video is when it begins.", fontSize = 13.sp, color = Muted)
                    }
                    Text("${lvl.toInt()}%", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    lvl, { vm.setText("startVolLevel", it.toInt().toString()) }, valueRange = 10f..100f,
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Color(0x33FFFFFF)),
                )
            }
            RowDivider(20.dp)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                val lvl = (v("startVolLevel").toFloatOrNull() ?: 50f).coerceIn(10f, 100f).toInt()
                Text("After I change it", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("Start every video at the level above, or keep the volume you last swiped to.", fontSize = 13.sp, color = Muted)
                val fixedLabel = "Always start at $lvl%"
                val lastLabel = "Remember my last"
                ChipGrid(listOf(fixedLabel, lastLabel), if (v("startVolMode") == "last") lastLabel else fixedLabel) {
                    vm.setText("startVolMode", if (it == lastLabel) "last" else "fixed")
                }
            }
        }

        SectionLabel("Subtitles")
        SettingsCard {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                Text("Subtitle languages", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("Online subtitles in these languages are loaded in the player (Audio & Subtitles). Tap to add or remove.", fontSize = 13.sp, color = Muted)
                val chosen = v("subLangs").split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                val all = listOf("english", "hindi", "tamil", "telugu", "malayalam", "kannada", "bengali", "marathi", "spanish", "french", "german", "arabic", "japanese", "korean")
                all.chunked(3).forEach { row ->
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { lang ->
                            val on = lang in chosen
                            val shape = RoundedCornerShape(14.dp)
                            Text(
                                lang.replaceFirstChar { it.uppercase() },
                                Modifier.clip(shape).background(if (on) Color(0xFF2E2E36) else Color.Transparent)
                                    .border(1.dp, if (on) Accent.copy(alpha = 0.7f) else Color(0x33FFFFFF), shape)
                                    .clickable {
                                        val next = if (on) chosen - lang else chosen + lang
                                        vm.setText("subLangs", next.ifEmpty { listOf("english") }.joinToString(","))
                                    }
                                    .padding(horizontal = 16.dp, vertical = 9.dp),
                                fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) Color.White else Muted,
                            )
                        }
                    }
                }
            }
            Column(Modifier.padding(20.dp)) {
                val size = v("subSize").toFloatOrNull() ?: 20f
                // Live preview
                Box(Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF3A2A20), Color(0xFF14181F)))), Alignment.BottomCenter) {
                    val bg = parseColor(v("subBg")) ?: Color.Transparent
                    Text(
                        "This is how subtitles will look", Modifier.padding(bottom = 12.dp).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = (size * 0.8f).sp, color = parseColor(v("subColor")) ?: Color.White,
                        fontWeight = if (isOn(v("subBold"))) FontWeight.Bold else FontWeight.Normal,
                        style = androidx.compose.ui.text.TextStyle(shadow = if (isOn(v("subOutline"))) androidx.compose.ui.graphics.Shadow(parseColor(v("subOutlineColor")) ?: Color.Black, blurRadius = 6f) else null),
                    )
                }
                Text("Size  ${size.toInt()}", Modifier.padding(top = 14.dp), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Slider(size, { vm.setText("subSize", it.toInt().toString()) }, valueRange = 12f..40f)
            }
            ToggleRow("Bold", "Use a heavier subtitle font weight.", isOn(v("subBold"))) { vm.setText("subBold", yn(it)) }
            RowDivider(20.dp)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("Text Color", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                ColorChips(v("subColor"), textColors) { vm.setText("subColor", it) }
                Text("Background Color", Modifier.padding(top = 16.dp), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                ColorChips(v("subBg"), bgColors) { vm.setText("subBg", it) }
            }
            RowDivider(20.dp)
            ToggleRow("Outline", "Draw a border around subtitle text.", isOn(v("subOutline"))) { vm.setText("subOutline", yn(it)) }
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
                Text("Outline Color", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                ColorChips(v("subOutlineColor"), outlineColors) { vm.setText("subOutlineColor", it) }
            }
        }

        SectionLabel("Player engine")
        SettingsCard {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("Engine", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("Auto (recommended) plays with ExoPlayer and the phone's own video decoders — the smoothest, like Nuvio — with FFmpeg for Dolby / DTS sound. If a file won't start, VLC takes over automatically. \"VLC first\" uses VLC for everything (subtitle styles apply only partly).",
                    fontSize = 13.sp, color = Muted)
                val engines = linkedMapOf("exo" to "Auto (ExoPlayer first)", "vlc" to "VLC first")
                ChipGrid(engines.values.toList(), if (v("playerEngine") == "vlc") "VLC first" else "Auto (ExoPlayer first)") { label -> vm.setText("playerEngine", engines.entries.first { it.value == label }.key); vm.setText("engineMigrated", "1") }
            }
        }

        SectionLabel("Stream start")
        SettingsCard {
            ToggleRow("Fast start", "Start playing after about 1 second of video instead of waiting for a bigger buffer.", isOn(v("fastStart"))) { vm.setText("fastStart", yn(it)) }
            RowDivider(20.dp)
            ToggleRow("Reuse last link", "Play the link that worked last time for this title straight away (kept for 6 hours; refreshed automatically if it expired).", isOn(v("reuseLastLink"))) { vm.setText("reuseLastLink", yn(it)) }
        }
        Text("Start-up speed also depends on the addon's server and your internet, so a little buffering can still happen.",
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp), fontSize = 13.sp, color = Muted)

        SectionLabel("Stream auto-play")
        SettingsCard {
            SettingRow(null, "Auto Stream Selection", modes[v("autoPlayMode")]?.first ?: "Manual (choose stream)") { modeDialog = true }
            if (v("autoPlayMode") == "regex") {
                RowDivider(20.dp)
                val pl = regexList(v("autoRegex"))
                SettingRow(null, "Regex Patterns", when (pl.size) { 0 -> "Not set (plays the first source)"; 1 -> pl[0]; else -> "${pl[0]}  +${pl.size - 1} fallback" + if (pl.size > 2) "s" else "" }) { regexDialog = true }
            }
        }

        SectionLabel("Skip segments")
        SettingsCard {
            ToggleRow("Skip Intro", "Use introdb.app to detect intros and recaps.", isOn(v("skipIntro"))) { vm.setText("skipIntro", yn(it)) }
            if (isOn(v("skipIntro"))) ToggleRow("Auto-skip intros & recaps", "Jump over them by themselves (each once — seek back to watch).", isOn(v("autoSkipIntro"))) { vm.setText("autoSkipIntro", yn(it)) }
            if (!BuildConfig.IS_TV) {
                val pipCtx = androidx.compose.ui.platform.LocalContext.current
                var autoPip by remember { mutableStateOf(AutoPip.on(pipCtx)) }
                ToggleRow("Picture-in-picture", "Going Home while a video plays keeps it playing in a small window.", autoPip) { autoPip = it; AutoPip.set(pipCtx, it) }
                ToggleRow("Parallel download", "Like a download manager: the start of a video (and the part ahead while you watch) comes in several pieces at once — faster starts, smoother 4K.",
                    isOn(v("parallelDl"))) { vm.setText("parallelDl", yn(it)); StreamCache.parallel = it }
                ToggleRow("Quick start + upgrade", "The quick server starts at once; when a sharper file (4K or a better 1080p) is ready, \"Upgrade\" appears — one tap switches.",
                    isOn(v("qsUpgrade"))) { vm.setText("qsUpgrade", yn(it)) }
                ToggleRow("Subtitle auto sync", "Online subtitles are lined up with the speech in the video by themselves.",
                    isOn(v("subAutoSync"))) { vm.setText("subAutoSync", yn(it)) }
                ToggleRow("Online subtitles by default", "A video without subtitles inside shows online ones in your subtitle language at once (turn subtitles Off in the player to stop it).",
                    isOn(v("autoOnlineSubs"))) { vm.setText("autoOnlineSubs", yn(it)) }
                Text("Audio & Subtitles layout", Modifier.padding(top = 14.dp), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("How the panel opens in the player.", fontSize = 13.sp, color = Muted)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("bubble" to "Bubble", "wide" to "Full width", "compact" to "Compact").forEach { (id, label) ->
                        val on = (v("avLayout").ifBlank { "bubble" }) == id
                        val shape = RoundedCornerShape(14.dp)
                        Text(label, Modifier.clip(shape).background(if (on) Color(0xFF2E2E36) else Color.Transparent)
                            .border(1.dp, if (on) Accent.copy(alpha = 0.7f) else Color(0x33FFFFFF), shape)
                            .clickable { vm.setText("avLayout", id) }.padding(horizontal = 14.dp, vertical = 9.dp),
                            fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) Color.White else Muted)
                    }
                }
            }
        }

        SectionLabel("Next episode")
        SettingsCard {
            ToggleRow("Auto-play Next Episode", "Start the next episode automatically near the end.", isOn(v("autoNext"))) { vm.setText("autoNext", yn(it)) }
            RowDivider(20.dp)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                val th = v("nextThreshold").toFloatOrNull() ?: 95f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Threshold Percentage", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text("When to jump to the next episode.", fontSize = 13.sp, color = Muted)
                    }
                    Text("${th.toInt()}%", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Slider(th, { vm.setText("nextThreshold", it.toInt().toString()) }, valueRange = 80f..100f)
            }
        }
    }

    if (modeDialog) PremiumAlert(
        onDismissRequest = { modeDialog = false }, containerColor = Surface1,
        title = { Text("Auto Stream Selection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { (key, pair) ->
                    val on = v("autoPlayMode") == key
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (on) Color(0xFF34343C) else Color(0xFF222228))
                        .clickable { vm.setText("autoPlayMode", key); modeDialog = false }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pair.first, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text(pair.second, fontSize = 13.sp, color = Muted)
                        }
                        if (on) Icon(Icons.Default.Check, null)
                    }
                }
            }
        },
        confirmButton = {},
    )

    if (regexDialog) {
        val pats = remember { mutableStateListOf<String>().apply { addAll(regexList(v("autoRegex")).ifEmpty { listOf("") }) } }
        var sel by remember { mutableIntStateOf(0) }
        val valid = pats.all { it.isBlank() || runCatching { Regex(it) }.isSuccess }
        PremiumAlert(
            onDismissRequest = { regexDialog = false },
            title = { Text("Regex Patterns") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Pattern 1 is tried first. If no stream matches it, the next pattern is tried, and so on. If none match, the first source plays.", fontSize = 13.sp, color = Muted)
                    Text("Presets (fill the selected pattern)", Modifier.padding(top = 12.dp, bottom = 6.dp), fontWeight = FontWeight.SemiBold)
                    RegexPresets.chunked(2).forEach { row ->
                        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (name, rx) ->
                                Text(name, Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF2A2A30)).clickable { if (sel in pats.indices) pats[sel] = rx }
                                    .padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    pats.forEachIndexed { i, pat ->
                        val ok = pat.isBlank() || runCatching { Regex(pat) }.isSuccess
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                pat, { pats[i] = it },
                                Modifier.weight(1f).onFocusChanged { f -> if (f.isFocused) sel = i }.tvOkKeyboard(),
                                singleLine = true, isError = !ok, shape = RoundedCornerShape(12.dp),
                                label = { Text(if (i == 0) "1 · Primary" else "${i + 1} · Fallback") },
                                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = if (sel == i) Accent.copy(alpha = 0.6f) else Color(0x33FFFFFF)), keyboardOptions = tvKeyboard())
                            if (pats.size > 1) IconButton({ pats.removeAt(i); sel = sel.coerceAtMost(pats.lastIndex) }) { Icon(Icons.Default.Close, "Remove") }
                        }
                        if (!ok) Text("This pattern isn't valid.", color = Color(0xFFFF6B6B), fontSize = 13.sp)
                    }
                    if (pats.size < 6) TextButton({ pats.add(""); sel = pats.lastIndex }, Modifier.padding(top = 6.dp)) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add fallback pattern")
                    }
                }
            },
            confirmButton = { TextButton({ if (valid) { vm.setText("autoRegex", pats.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")); regexDialog = false } }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton({ pats.clear(); pats.add(""); sel = 0 }) { Text("Clear") }
                    TextButton({ regexDialog = false }) { Text("Cancel") }
                }
            },
        )
    }
}

// ───────────── Network & DNS ─────────────

@Composable
private fun NetworkPage(vm: AppViewModel, onBack: () -> Unit) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    val cur = texts["dns"] ?: "system"
    Page("Network & DNS", onBack) {
        Text(
            "Choose which DNS the app uses to find catalogs, addons, images and video streams. It uses secure DNS-over-HTTPS and only affects this app. " +
                "If a provider can't be reached, the app automatically falls back to your normal DNS.",
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp), fontSize = 14.sp, color = Muted,
        )
        // Status: is the chosen DNS really answering (checked now, and again after each change)?
        var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
        var checkTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(cur, checkTick) { status = null; status = Api.dnsStatus(cur) }
        SectionLabel("Status")
        SettingsCard {
            Row(Modifier.fillMaxWidth().clickable { checkTick++ }.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                val st = status
                val pulse = rememberInfiniteTransition(label = "dnsPulse")
                val a by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dnsA")
                Box(Modifier.size(12.dp).graphicsLayer { alpha = if (st == null) a else 1f }.clip(CircleShape)
                    .background(when { st == null -> Color(0xFF9AA3B5); st.first -> Color(0xFF3DDC84); else -> Color(0xFFFF6B6B) }))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(when { st == null -> "Checking…"; st.first -> "Activated"; else -> "Not active" }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        color = when { st == null -> Color.White; st.first -> Color(0xFF3DDC84); else -> Color(0xFFFF6B6B) })
                    Text(st?.second ?: (DnsProviders[cur]?.first ?: "System default"), fontSize = 13.sp, color = Muted)
                }
                Icon(Icons.Default.Refresh, "Check again", tint = Muted)
            }
        }
        // VPN (Xstream only): on/off, IP check, Smart / Everything, your VPN accounts, countries.
        if (!BuildConfig.IS_TV) VpnSection()
        SectionLabel("DNS provider")
        SettingsCard {
            DnsProviders.entries.forEachIndexed { i, (key, pair) ->
                if (i > 0) RowDivider(20.dp)
                Row(Modifier.fillMaxWidth().clickable { vm.setDns(key) }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pair.first, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text(pair.second, fontSize = 13.sp, color = Muted)
                    }
                    RadioButton(cur == key, { vm.setDns(key) })
                }
            }
        }
        Text("Images may switch to the new DNS after you restart the app.", Modifier.padding(24.dp), fontSize = 13.sp, color = Muted)
    }
}

// ───────────── Collections (Nuvio import) ─────────────

@Composable
private fun CollectionsSettingsPage(vm: AppViewModel, onBack: () -> Unit) {
    val flow = remember(vm) { vm.allCollections() }
    val list by flow.collectAsStateWithLifecycle()
    val texts by vm.texts.collectAsStateWithLifecycle()
    val hidden = remember(texts) { (texts["colHidden"] ?: "").split("|").filter { it.isNotBlank() }.toSet() }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var pasteOpen by remember { mutableStateOf(false) }
    var linkOpen by remember { mutableStateOf(false) }
    var clearAsk by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun done(r: Result<Int>): Boolean {
        r.fold({ Snack.show("Imported $it collection" + if (it == 1) "" else "s") }, { Snack.show(it.message ?: "Import failed") })
        return r.isSuccess
    }
    fun setOrder(ids: List<String>) = vm.setText("colOrder", ids.joinToString("|"))
    fun toggleHidden(id: String) = vm.setText("colHidden", (if (id in hidden) hidden - id else hidden + id).joinToString("|"))

    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u: android.net.Uri? ->
        if (u != null) scope.launch {
            busy = true
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Couldn't read that file") }
                    .fold({ vm.importCollections(it) }, { Result.failure<Int>(it) })
            }
            busy = false
            done(r)
        }
    }

    Page("Collections", onBack) {
        Text("Import collections exported from Nuvio (JSON). Each collection shows as a row of folders on Home, right below Continue Watching. " +
            "TMDB rows need a TMDB key and Trakt rows a Trakt client ID (Integrations); addon rows need that addon installed.",
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp), fontSize = 14.sp, color = Muted)
        SectionLabel("Overview")
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(CardShape).background(CardBg).padding(vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("${list.size}" to "Collections", "${list.sumOf { it.folders.size }}" to "Folders", "${list.count { it.id !in hidden }}" to "Shown").forEachIndexed { i, (n, l) ->
                if (i > 0) Box(Modifier.width(1.dp).height(44.dp).background(Color(0x22FFFFFF)))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(n, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(l, fontSize = 16.sp, color = Muted)
                }
            }
        }
        SectionLabel("Import")
        SettingsCard {
            ToggleRow("Show on Home", "Collection rows below Continue Watching.", isOn(texts["showCollections"] ?: Defaults["showCollections"] ?: "yes")) {
                vm.setText("showCollections", if (it) "yes" else "no")
            }
            RowDivider(20.dp)
            SettingRow(Icons.Default.ContentPaste, "Paste JSON", "Paste a Nuvio collections export.", enabled = !busy) { pasteOpen = true }
            RowDivider(20.dp)
            SettingRow(Icons.Default.Link, "Import from link", "A link to the JSON file (GitHub, Pastebin raw…).", enabled = !busy) { linkOpen = true }
            RowDivider(20.dp)
            SettingRow(Icons.Default.FileOpen, "Import from file", "Pick the exported .json / .txt file.", enabled = !busy) { pickFile.launch(arrayOf("*/*")) }
            if (list.isNotEmpty()) {
                RowDivider(20.dp)
                SettingRow(Icons.Default.DeleteSweep, "Delete all", "Remove every imported collection.") { clearAsk = true }
            }
        }
        if (busy) Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(color = Accent) }
        if (list.isNotEmpty()) {
            SectionLabel("Your collections")
            list.forEachIndexed { i, c ->
                val shown = c.id !in hidden
                Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().clip(CardShape).background(CardBg).padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (shown) Color.White else Muted)
                        Text("${c.folders.size} folder" + (if (c.folders.size == 1) "" else "s") + if (shown) "" else " · Hidden", fontSize = 13.sp, color = Muted)
                    }
                    IconButton({ setOrder(list.map { it.id }.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, "Move up") }
                    IconButton({ setOrder(list.map { it.id }.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < list.lastIndex) { Icon(Icons.Default.ArrowDownward, "Move down") }
                    IconButton({ toggleHidden(c.id) }) {
                        Icon(if (shown) Icons.Default.Visibility else Icons.Default.VisibilityOff, if (shown) "Hide" else "Show", tint = if (shown) Color.White else Muted)
                    }
                }
            }
        }
    }

    if (pasteOpen) {
        var text by remember { mutableStateOf("") }
        PremiumDialog(onDismissRequest = { pasteOpen = false }) { close ->
            Text("Paste JSON", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text("Paste the collections JSON exported from Nuvio.", Modifier.padding(top = 6.dp), fontSize = 13.sp, color = Muted)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 140.dp, max = 260.dp).tvOkKeyboard(),
                minLines = 6, maxLines = 10, shape = RoundedCornerShape(12.dp), placeholder = { Text("[ { \"title\": … } ]") }, keyboardOptions = tvKeyboard())
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(close) { Text("Cancel", color = Color.White) }
                Spacer(Modifier.width(8.dp))
                Button({
                    val json = text
                    busy = true
                    scope.launch {
                        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.importCollections(json) }
                        busy = false
                        if (done(r)) close()
                    }
                }, enabled = text.isNotBlank() && !busy, shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)) { Text("Import", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
    if (linkOpen) {
        var url by remember { mutableStateOf("") }
        PremiumDialog(onDismissRequest = { linkOpen = false }) { close ->
            Text("Import from link", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            OutlinedTextField(url, { url = it.trim() }, Modifier.fillMaxWidth().padding(top = 12.dp).tvOkKeyboard(), singleLine = true,
                shape = RoundedCornerShape(12.dp), placeholder = { Text("https://…/collections.json") }, keyboardOptions = tvKeyboard())
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(close) { Text("Cancel", color = Color.White) }
                Spacer(Modifier.width(8.dp))
                Button({
                    val u = url
                    busy = true
                    scope.launch {
                        val r = vm.importCollectionsFromUrl(u)
                        busy = false
                        if (done(r)) close()
                    }
                }, enabled = url.startsWith("http") && !busy, shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)) { Text(if (busy) "Importing…" else "Import", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
    if (clearAsk) PremiumAlert(
        onDismissRequest = { clearAsk = false },
        title = { Text("Delete all collections?") },
        text = { Text("Every imported collection will be removed from this device. You can import them again any time.") },
        confirmButton = { TextButton({ vm.clearCollections(); clearAsk = false; Snack.show("Collections deleted") }) { Text("Delete", color = Color(0xFFFF6B6B)) } },
        dismissButton = { TextButton({ clearAsk = false }) { Text("Cancel") } },
    )
}

// ───────────── Notifications ─────────────

@Composable
private fun NotificationsPage(vm: AppViewModel, onBack: () -> Unit) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val on = isOn(texts["notifyOn"] ?: Defaults["notifyOn"] ?: "yes")
    var inbox by remember { mutableStateOf(EpisodeAlerts.inbox(ctx)) }
    var checking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val askPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (!granted) note = "Notifications are blocked for Xstream — alerts still show in the list below." }
    fun setOn(v: Boolean) {
        vm.setText("notifyOn", if (v) "yes" else "no")
        EpisodeAlerts.schedule(ctx, v)
        if (v) {
            EpisodeAlerts.snapshot(ctx, vm)
            if (android.os.Build.VERSION.SDK_INT >= 33) askPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Page("Notifications", onBack) {
        SectionLabel("Episode alerts")
        SettingsCard {
            ToggleRow("New episode alerts", "A notification when a new episode of a show in My List or Continue Watching comes out (checked every few hours).", on) { setOn(it) }
            RowDivider(20.dp)
            SettingRow(null, if (checking) "Checking…" else "Check now", note ?: "Look for new episodes right away.", enabled = !checking) {
                checking = true
                scope.launch {
                    EpisodeAlerts.snapshot(ctx, vm)
                    val n = runCatching { EpisodeAlerts.check(ctx) }.getOrDefault(0)
                    inbox = EpisodeAlerts.inbox(ctx)
                    note = if (n == 0) "No new episodes since the last check." else "$n new — see below."
                    checking = false
                }
            }
        }
        SectionLabel("Recent alerts")
        SettingsCard {
            if (inbox.isEmpty()) Text("Nothing yet. New episodes of your shows will appear here.", Modifier.padding(20.dp), fontSize = 14.sp, color = Muted)
            inbox.forEachIndexed { i, a ->
                if (i > 0) RowDivider(20.dp)
                Row(Modifier.fillMaxWidth().clickable { }.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    coil.compose.AsyncImage(a.poster, null, Modifier.size(42.dp, 60.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A30)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.show, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(a.text, fontSize = 13.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(a.at)), fontSize = 11.sp, color = Muted)
                    }
                }
            }
            if (inbox.isNotEmpty()) {
                RowDivider(20.dp)
                SettingRow(null, "Clear the list", "Remove these alerts.") { EpisodeAlerts.clearInbox(ctx); inbox = emptyList() }
            }
        }
    }
}
