package com.example.streamnest

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private val HubMuted = Color(0xFF9AA3B5)
private val HubCard = Color(0xFF14171D)
private val HubLine = Color(0xFF262A34)

/** Numbers about you, worked out from your history (shared by My Space and its detail lists). */
private data class MyStats(val tracked: String, val finished: Int, val loved: Int, val streak: Int, val tags: List<String>)

@Composable
private fun rememberStats(vm: AppViewModel): MyStats {
    val profile by vm.current.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val myList by vm.myList.collectAsStateWithLifecycle()
    val doneKeys by vm.doneKeys.collectAsStateWithLifecycle()
    val doneMs by vm.doneMs.collectAsStateWithLifecycle()
    val votes by vm.votes.collectAsStateWithLifecycle()
    return remember(progress, myList, doneKeys, doneMs, votes, profile) {
        val day = 86_400_000L
        val trackedMs = doneMs + progress.sumOf { it.positionMs }
        val hours = trackedMs / 3_600_000L
        val days = progress.map { it.updated / day }.toSet()
        var streak = 0
        run { var d = System.currentTimeMillis() / day; if (d !in days) d -= 1; while (d in days) { streak++; d-- } }
        val genres = myList.flatMap { it.genres.orEmpty() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val series = myList.count { it.type == "series" } + progress.count { it.isSeries() }
        val movies = myList.count { it.type != "series" } + progress.count { !it.isSeries() }
        val tags = buildList {
            if (profile?.kids == true) add("Kids profile")
            else add(when { series + movies == 0 -> "Just getting started"; series > movies * 1.5 -> "Series lover"; movies > series * 1.5 -> "Movie buff"; else -> "Balanced taste" })
            genres.firstOrNull()?.let { add("${it.key} fan") }
            if (progress.count { val h = Calendar.getInstance().apply { timeInMillis = it.updated }.get(Calendar.HOUR_OF_DAY); h >= 23 || h < 4 } >= 2) add("Night owl")
        }
        MyStats(if (hours >= 1) "${hours}h" else "${trackedMs / 60_000L}m", doneKeys.size, votes.values.count { it.v > 0 }, streak, tags)
    }
}

/**
 * My Space: only the profile you're using — picture, name, tags and your numbers; then Library and Watch together, and
 * Watchlist (with its calendar), Notifications and Clear Continue Watching. Switch profiles with "Switch" (or a long
 * press on My Space in the bar).
 */
@Composable
fun MySpaceScreen(
    vm: AppViewModel,
    onSettings: () -> Unit,
    onProfile: () -> Unit,
    onSwitchProfile: () -> Unit,
    onOpen: (Meta) -> Unit,
    onPlay: (Screen.Player) -> Unit,
    onManual: (Meta, String) -> Unit = { _, _ -> },
    onLibrary: () -> Unit = {},
    onTogether: () -> Unit = {},
    onWatchlist: () -> Unit = {},
    onNotifications: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val profile by vm.current.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val myList by vm.myList.collectAsStateWithLifecycle()
    val pending by Social.pending.collectAsStateWithLifecycle()
    val st = rememberStats(vm)
    var confirmClear by remember { mutableStateOf(false) }
    var statOpen by remember { mutableStateOf<String?>(null) }
    val alerts = remember { EpisodeAlerts.inbox(ctx).size }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(520.dp).background(Brush.verticalGradient(listOf(Color(0xFF14091E), Palette.tint.copy(alpha = 0.55f), Bg))))
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppLogo(vm, 40.dp)
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier.tourTarget("settings").clip(RoundedCornerShape(20.dp)).clickable(onClick = onSettings).padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Settings, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t("helpSettings"), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            // You
            item {
                val shape = RoundedCornerShape(22.dp)
                Column(Modifier.padding(16.dp).fillMaxWidth().clip(shape).background(Color(0xCC161B26)).border(1.dp, Color(0x14FFFFFF), shape).padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProfileAvatar(vm, profile, 64.dp, Modifier.clickable(onClick = onProfile))
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(profile?.name ?: "", fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // Xstream: tags wrap onto a new line instead of sliding under each other; no Switch button (long-press
                            // your picture in the dock and slide to another profile).
                            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                st.tags.forEach { Text(it, Modifier.clip(RoundedCornerShape(50)).background(Color(0x1AFFFFFF)).padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                            }
                        }
                    }
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatBox(st.tracked, "Time watched", Modifier.weight(1f)) { statOpen = "time" }
                        StatBox("${st.finished}", "Finished", Modifier.weight(1f)) { statOpen = "done" }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatBox("${st.loved}", "Titles you love", Modifier.weight(1f)) { statOpen = "love" }
                        StatBox("${st.streak} ${if (st.streak == 1) "day" else "days"}", "Watch streak", Modifier.weight(1f)) { statOpen = "time" }
                    }
                }
            }
            // Xstream: Library, Watch together, Watchlist, Notifications and Clear Continue Watching live in Library now;
            // My Space shows your charts instead.
            item { LaunchedEffect(profile?.id) { WatchLog.load(ctx, profile?.id) } ; WatchCharts { k -> progress.firstOrNull { it.key == k }?.let { onOpen(it.toMeta()) } ?: onOpen(Meta(id = k.substringBefore(":"), type = if (k.contains(":")) "series" else "movie", name = "")) } }
            if (!BuildConfig.IS_TV) item { DownloadsSection(onPlay) }
            if (!BuildConfig.IS_TV) item {
                var tasteOpen by remember { mutableStateOf(false) }
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF15161C))
                    .clickable { tasteOpen = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, null, tint = Color(0xFFB6BDCC))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Your taste & languages", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(vm.tasteLangs().joinToString(" · ") { c -> TasteLangs.firstOrNull { it.code == c }?.name ?: c }.ifBlank { "Pick what Home is made from" }, fontSize = 12.sp, color = Color(0xFF9AA3B5))
                    }
                }
                if (tasteOpen) TasteOnboarding(vm) { tasteOpen = false }
            }
            if (!BuildConfig.IS_TV) item {
                val di by Donate.info.collectAsState()
                if (di.on) Row(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x1FE50914)).border(1.dp, Color(0x55E50914), RoundedCornerShape(18.dp))
                    .clickable { Donate.ask.value = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Favorite, null, tint = Color(0xFFFF5A64))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Support Xstream", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Keep the servers running and new features coming", fontSize = 12.sp, color = Color(0xFF9AA3B5))
                    }
                }
            }
            item { Spacer(Modifier.navigationBarsPadding().height(120.dp)) }
        }
    }
    if (confirmClear) PremiumAlert(onDismissRequest = { confirmClear = false }, title = { Text("Clear Continue Watching?") },
        text = { Text("All ${progress.size} titles leave the row on your phones and TV. Your history and numbers stay.", fontSize = 13.sp) },
        confirmButton = { androidx.compose.material3.TextButton({ vm.clearContinue(); confirmClear = false; Snack.show("Continue Watching cleared") }) { Text("Clear", color = Color(0xFFFF8A80)) } },
        dismissButton = { androidx.compose.material3.TextButton({ confirmClear = false }) { Text("Cancel") } })
    statOpen?.let { which -> StatDetails(vm, which) { statOpen = null } }
}

@Composable
private fun StatBox(value: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF12151C)).border(1.dp, HubLine, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(label, fontSize = 11.sp, color = HubMuted, maxLines = 1)
    }
}

@Composable
private fun BigHubButton(title: String, sub: String, icon: ImageVector, bg: Brush, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.height(84.dp).clip(RoundedCornerShape(18.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Icon(icon, null, Modifier.size(22.dp))
        Column {
            Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text(sub, fontSize = 10.5.sp, color = Color(0xCCFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HubRow(icon: ImageVector, tint: Color, title: String, end: String, danger: Boolean = false, last: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(HubCard).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(tint), Alignment.Center) { Icon(icon, null, Modifier.size(17.dp)) }
        Text(title, Modifier.weight(1f).padding(start = 12.dp), fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = if (danger) Color(0xFFFF8A80) else Color.White)
        if (end.isNotBlank()) Text(end, fontSize = 12.sp, color = HubMuted)
        Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = HubMuted)
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(HubLine))
}

/** What's behind a My Space number. */
@Composable
private fun StatDetails(vm: AppViewModel, which: String, onClose: () -> Unit) {
    val progress by vm.progress.collectAsStateWithLifecycle()
    val votes by vm.votes.collectAsStateWithLifecycle()
    val doneKeys by vm.doneKeys.collectAsStateWithLifecycle()
    val day = 86_400_000L
    val rows: List<Pair<String, String>> = when (which) {
        "time" -> (6 downTo 0).map { back ->
            val d = System.currentTimeMillis() / day - back
            val m = progress.filter { it.updated / day == d }.sumOf { (it.positionMs / 60_000L).coerceAtMost(240) }
            SimpleDateFormat("EEEE", Locale.getDefault()).format(java.util.Date(d * day)) to (if (m > 0) "${m / 60}h ${m % 60}m" else "—")
        } + progress.sortedByDescending { it.positionMs }.take(5).map { it.title.substringBefore("  S") to "${it.positionMs / 3_600_000L}h ${(it.positionMs / 60_000L) % 60}m" }
        "done" -> votes.values.filter { v -> doneKeys.any { it.substringBefore(":") == v.id } }.sortedByDescending { it.at }
            .map { it.name to SimpleDateFormat("d MMM", Locale.getDefault()).format(java.util.Date(it.at)) }
            .ifEmpty { listOf("${doneKeys.size} titles watched to the end" to "") }
        else -> votes.values.filter { it.v > 0 }.sortedByDescending { it.at }.map { it.name to (if (it.type == "series") "Series" else "Movie") }
            .ifEmpty { listOf("Nothing yet · give a title a thumbs up after watching" to "") }
    }
    PremiumAlert(onDismissRequest = onClose, title = { Text(when (which) { "time" -> "Time watched"; "done" -> "Titles finished"; else -> "Titles you love" }) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScrollCompat()) {
            rows.forEach { (a, b) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(a, Modifier.weight(1f), fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(b, fontSize = 12.5.sp, color = HubMuted)
                }
            }
        }
    }, confirmButton = { androidx.compose.material3.TextButton(onClose) { Text("Close") } })
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))

// ───────────── Library ─────────────

/** Library: Continue Watching (remove one / clear all), downloads, and your history (finished, loved). */
@Composable
fun LibraryPage(vm: AppViewModel, onBack: (() -> Unit)?, onOpen: (Meta) -> Unit, onPlay: (Screen.Player) -> Unit) =
    XLibraryPage(vm, onBack, onOpen, onPlayRoom = { st ->
        val expected = st.pos + if (st.playing) (System.currentTimeMillis() - st.receivedAt + 300) else 0L
        onPlay(Screen.Player(st.title, st.url, emptyList(), key = st.key, image = st.image, resumeMs = expected, type = st.type))
    }, onPlay = onPlay)

@Suppress("unused")
@Composable
private fun OldLibraryPage(vm: AppViewModel, onBack: (() -> Unit)?, onOpen: (Meta) -> Unit, onPlay: (Screen.Player) -> Unit) {
    val progress by vm.progress.collectAsStateWithLifecycle()
    val votes by vm.votes.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    if (onBack != null) BackHandler { onBack() }
    LazyColumn(Modifier.fillMaxSize().background(Bg), contentPadding = PaddingValues(bottom = 140.dp)) {
        item { PageHead("Library", onBack) }
        item {
            Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Continue Watching", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                if (progress.isNotEmpty()) Text("Clear all", Modifier.clip(RoundedCornerShape(50)).clickable { confirmClear = true }.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.5.sp, color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold)
            }
        }
        if (progress.isEmpty()) item { Text("Nothing in progress.", Modifier.padding(16.dp), fontSize = 13.sp, color = HubMuted) }
        else item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(progress, key = { it.key }) { p ->
                    ContinueCard(p, onClick = { onPlay(Screen.Player(p.title, p.url, p.subs, p.key, p.image, p.positionMs)) }, onRemove = { vm.removeProgress(p.key) })
                }
            }
        }
        if (!BuildConfig.IS_TV) item { DownloadsSection(onPlay) }
        val loved = votes.values.filter { it.v > 0 }.sortedByDescending { it.at }
        if (loved.isNotEmpty()) {
            item { Text("Titles you love", Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(loved, key = { "l" + it.id }) { v -> Poster(Meta(id = v.id, type = v.type, name = v.name, poster = v.poster, genres = v.genres), Modifier.width(110.dp), onOpen) }
                }
            }
        }
        val history = votes.values.sortedByDescending { it.at }.take(30)
        if (history.isNotEmpty()) {
            item { Text("Recently watched", Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold) }
            items(history, key = { "h" + it.id }) { v ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(Meta(id = v.id, type = v.type, name = v.name, poster = v.poster)) }.padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(v.poster, null, Modifier.size(38.dp, 56.dp).clip(RoundedCornerShape(7.dp)).background(HubCard), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(v.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(SimpleDateFormat("d MMM", Locale.getDefault()).format(java.util.Date(v.at)) + (if (v.v > 0) " · loved" else if (v.v < 0) " · not for you" else ""), fontSize = 11.5.sp, color = HubMuted)
                    }
                }
            }
        }
    }
    if (confirmClear) PremiumAlert(onDismissRequest = { confirmClear = false }, title = { Text("Clear Continue Watching?") },
        text = { Text("All ${progress.size} titles leave the row on your phones and TV.", fontSize = 13.sp) },
        confirmButton = { androidx.compose.material3.TextButton({ vm.clearContinue(); confirmClear = false }) { Text("Clear", color = Color(0xFFFF8A80)) } },
        dismissButton = { androidx.compose.material3.TextButton({ confirmClear = false }) { Text("Cancel") } })
}

@Composable
private fun PageHead(title: String, onBack: (() -> Unit)?) {
    Row(Modifier.statusBarsPadding().padding(start = if (onBack != null) 6.dp else 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), Alignment.Center) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
    }
}

// ───────────── Watchlist + calendar ─────────────

private data class CalEvent(val day: Long, val kind: String, val title: String, val sub: String, val poster: String?, val meta: Meta?, val item: SharedItem? = null)

/**
 * Watchlist with a calendar: new episodes of the shows you follow, release days of movies in your watchlist, and the
 * watches you planned (alone or with friends). Tap a day, then "Plan a watch".
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WatchlistPage(vm: AppViewModel, onBack: () -> Unit, onOpen: (Meta) -> Unit, startCalendar: Boolean = false, embedded: Boolean = false) {
    val myList by vm.myList.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val items by Social.items.collectAsStateWithLifecycle()
    val friends by Social.friends.collectAsStateWithLifecycle()
    var cal by remember { mutableStateOf(startCalendar) }
    var share by remember { mutableStateOf<Meta?>(null) }
    if (!embedded) BackHandler { onBack() }
    val day = 86_400_000L
    val today = remember { localDay(System.currentTimeMillis()) }
    var month by remember { mutableStateOf(Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis) }
    var selDay by remember { mutableLongStateOf(today) }
    var planFor by remember { mutableStateOf<Meta?>(null) }
    var pickTitle by remember { mutableStateOf(false) }
    var openItem by remember { mutableStateOf<SharedItem?>(null) }

    // Episodes and releases (looked up once when the calendar opens).
    var found by remember { mutableStateOf<List<CalEvent>>(emptyList()) }
    LaunchedEffect(cal, myList) {
        if (!cal) return@LaunchedEffect
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val out = ArrayList<CalEvent>()
        val shows = (myList.filter { it.type == "series" } + progress.filter { it.isSeries() }.map { it.asMeta() }).distinctBy { it.id }.take(30)
        for (s in shows) {
            val m = runCatching { vm.seriesMeta(s.id) }.getOrNull() ?: continue
            m.videos.orEmpty().forEach { v ->
                val d = v.released?.take(10)?.let { runCatching { fmt.parse(it)?.time }.getOrNull() } ?: return@forEach
                val dd = localDay(d)
                if (dd >= today - 7 && dd <= today + 120 && (v.season ?: 0) > 0)
                    out += CalEvent(dd, "EPISODE", m.name, "S${v.season} E${v.episode}" + ((v.title ?: v.name)?.let { " · $it" } ?: ""), m.poster ?: s.poster, m)
            }
            found = out.toList()
        }
        for (mv in myList.filter { it.type != "series" }.take(30)) {
            val r = runCatching { vm.tmdbInfo(mv.id, "movie")?.release }.getOrNull()?.take(10) ?: continue
            val d = runCatching { fmt.parse(r)?.time }.getOrNull() ?: continue
            val dd = localDay(d)
            if (dd >= today - 7) out += CalEvent(dd, "RELEASE", mv.name, "Release day", mv.poster, mv)
            found = out.toList()
        }
        found = out.toList()
    }
    val planned = remember(items) {
        items.flatMap { it0 -> it0.plans.map { p ->
            val others = p.with.filterKeys { it != Social.me }.values
            CalEvent(localDay(p.at), "PLANNED", it0.meta.name, SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date(p.at)) + if (others.isEmpty()) " · only you" else " · with ${others.joinToString(", ")}", it0.meta.poster, it0.meta, it0)
        } }
    }
    val all = found + planned

    Column(Modifier.fillMaxSize().background(Bg)) {
        if (!embedded) PageHead("Watchlist", onBack)
        if (!embedded) Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HubCard).padding(4.dp)) {
            listOf("Titles" to false, "Calendar" to true).forEach { (n, c) ->
                Text(n, Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (cal == c) Color(0xFFF2F4F8) else Color.Transparent).clickable { cal = c }.padding(vertical = 9.dp),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (cal == c) Color(0xFF0E1014) else HubMuted)
            }
        }
        if (!cal) {
            if (myList.isEmpty()) Text("Nothing here yet. Tap + on any title to save it.", Modifier.padding(16.dp), color = HubMuted, fontSize = 14.sp)
            LazyColumn(contentPadding = PaddingValues(bottom = 140.dp, top = 10.dp)) {
                items(myList.chunked(3)) { row ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { m ->
                            Column(Modifier.weight(1f)) {
                                Poster(m, Modifier.fillMaxWidth(), onOpen)
                                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Plan", Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(HubCard).clickable { planFor = m }.padding(vertical = 5.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                    Text("Share", Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(HubCard).clickable { share = m }.padding(vertical = 5.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                }
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        } else LazyColumn(contentPadding = PaddingValues(bottom = 140.dp)) {
            item { MonthGrid(month, selDay, today, all, onMonth = { month = it }, onDay = { selDay = it }) }
            val dayEvents = all.filter { it.day == selDay }.sortedBy { it.kind }
            item {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(java.util.Date(selDay * day)), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    Text(if (dayEvents.isEmpty()) "Nothing yet" else "${dayEvents.size}", fontSize = 12.sp, color = HubMuted)
                }
            }
            items(dayEvents) { e ->
                // Long-press: remove it from your calendar (a plan, or the title from your watchlist), or open it.
                var menu by remember { mutableStateOf(false) }
                val ctxE = LocalContext.current
                val scopeE = rememberCoroutineScope()
                androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                    if (e.item != null) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Remove plan", color = Color(0xFFFF8A91)) }, onClick = {
                            menu = false
                            val plan = e.item.plans.firstOrNull { localDay(it.at) == e.day }
                            scopeE.launch { if (plan != null && Social.removePlan(ctxE, e.item, plan)) Snack.show("Plan removed") }
                        })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Remove title and its plans", color = Color(0xFFFF8A91)) }, onClick = {
                            menu = false; scopeE.launch { if (Social.leaveItem(ctxE, e.item)) Snack.show("Removed") }
                        })
                    } else e.meta?.let { m ->
                        if (myList.any { it.id == m.id }) androidx.compose.material3.DropdownMenuItem(text = { Text("Remove from my list", color = Color(0xFFFF8A91)) }, onClick = { menu = false; vm.toggleList(m); Snack.show("Removed from your list") })
                    }
                    e.meta?.let { m -> androidx.compose.material3.DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen(m) }) }
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(HubCard)
                    .combinedClickable(onClick = { if (e.item != null) openItem = e.item else e.meta?.let(onOpen) }, onLongClick = { menu = true }).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(e.poster, null, Modifier.size(38.dp, 56.dp).clip(RoundedCornerShape(7.dp)), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(e.title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.sub, fontSize = 11.5.sp, color = HubMuted, maxLines = 2)
                    }
                    val c = kindColor(e.kind)
                    Text(e.kind, Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 3.dp), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = c)
                }
            }
            item {
                Text("＋ Plan a watch on this day", Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(50)).background(Accent).clickable { pickTitle = true }.padding(vertical = 13.dp),
                    fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, color = OnAccent)
            }
        }
    }
    if (pickTitle) PremiumAlert(onDismissRequest = { pickTitle = false }, title = { Text("Plan which title?") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScrollCompat()) {
            // Xstream: search any title, not only the ones in your watchlist.
            TitleSearchBox(vm) { m -> pickTitle = false; planFor = m }
            if (myList.isNotEmpty()) Text("From your watchlist", Modifier.padding(top = 14.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HubMuted)
            if (myList.isEmpty()) Text("Add titles to your watchlist first.", fontSize = 13.sp, color = HubMuted)
            myList.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).clickable { pickTitle = false; planFor = m }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(m.poster, null, Modifier.size(34.dp, 50.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                    Text(m.name, Modifier.padding(start = 10.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                }
            }
        }
    }, confirmButton = {}, dismissButton = { androidx.compose.material3.TextButton({ pickTitle = false }) { Text("Cancel") } })
    planFor?.let { m ->
        val start = if (cal) Calendar.getInstance().apply { timeInMillis = selDay * day; set(Calendar.HOUR_OF_DAY, 21); set(Calendar.MINUTE, 0) }.timeInMillis.takeIf { it > System.currentTimeMillis() } else null
        PlanFlow(m, items.firstOrNull { it.meta.id == m.id && it.by == Social.me }, friends, start, onDone = { planFor = null })
    }
    share?.let { m -> ShareWithFriends(m, friends) { share = null } }
    openItem?.let { it0 -> SharedItemSheet(vm, it0, friends, onClose = { openItem = null }, onOpen = { m -> openItem = null; onOpen(m) },
        onJoin = { code -> openItem = null; JoinIntent.asked = true; JoinIntent.code.value = code }) }
}

private fun localDay(ms: Long): Long { val tz = TimeZone.getDefault(); return (ms + tz.getOffset(ms)) / 86_400_000L }
private fun kindColor(k: String) = when (k) { "EPISODE" -> Color(0xFF7BE0FF); "RELEASE" -> Color(0xFFFFB13B); else -> Color(0xFF3DD68C) }

@Composable
private fun MonthGrid(month: Long, sel: Long, today: Long, events: List<CalEvent>, onMonth: (Long) -> Unit, onDay: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { timeInMillis = month }
    val title = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
    val firstDow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday first
    val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val byDay = events.groupBy { it.day }
    Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(HubCard).border(1.dp, HubLine, RoundedCornerShape(18.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).padding(start = 4.dp), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Icon(Icons.Default.ChevronLeft, "Previous month", Modifier.size(36.dp).clip(CircleShape).clickable { onMonth(Calendar.getInstance().apply { timeInMillis = month; add(Calendar.MONTH, -1) }.timeInMillis) }.padding(6.dp))
            Icon(Icons.Default.ChevronRight, "Next month", Modifier.size(36.dp).clip(CircleShape).clickable { onMonth(Calendar.getInstance().apply { timeInMillis = month; add(Calendar.MONTH, 1) }.timeInMillis) }.padding(6.dp))
        }
        Row(Modifier.padding(top = 6.dp)) { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), fontSize = 10.sp, color = HubMuted, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) } }
        val cells = List(firstDow) { 0 } + (1..days).toList()
        cells.chunked(7).forEach { week ->
            Row(Modifier.padding(top = 4.dp)) {
                week.forEach { d ->
                    if (d == 0) Spacer(Modifier.weight(1f).height(38.dp)) else {
                        val c2 = Calendar.getInstance().apply { timeInMillis = month; set(Calendar.DAY_OF_MONTH, d); set(Calendar.HOUR_OF_DAY, 12) }
                        val key = localDay(c2.timeInMillis)
                        val on = key == sel
                        Column(Modifier.weight(1f).height(38.dp).padding(1.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Color(0xFFF2F4F8) else Color.Transparent)
                            .then(if (key == today && !on) Modifier.border(1.5.dp, Accent, RoundedCornerShape(10.dp)) else Modifier).clickable { onDay(key) },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("$d", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color(0xFF0E1014) else Color.White)
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                byDay[key].orEmpty().map { it.kind }.distinct().take(3).forEach { k -> Box(Modifier.padding(top = 2.dp).size(4.dp).clip(CircleShape).background(kindColor(k))) }
                            }
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Row(Modifier.padding(top = 8.dp, start = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("New episode" to "EPISODE", "Release" to "RELEASE", "Your plan" to "PLANNED").forEach { (n, k) ->
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(6.dp).clip(CircleShape).background(kindColor(k))); Text(" $n", fontSize = 10.sp, color = HubMuted) }
            }
        }
    }
}

// ───────────── Notifications ─────────────

/** New-episode alerts (and a link to Watch together when someone is waiting for your answer). Tap one to open the
 * show; swipe it left to remove it (the bell forgets it too). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun NotificationsPage(onBack: () -> Unit, onTogether: () -> Unit, embedded: Boolean = false, onOpen: (Meta) -> Unit = {}) {
    val ctx = LocalContext.current
    val alertsAll = remember { EpisodeAlerts.inbox(ctx) }
    val pending by Social.pending.collectAsStateWithLifecycle()
    val texts = Public.vm?.texts?.collectAsStateWithLifecycle()?.value.orEmpty()
    val clearedAt = texts["bellCleared"]?.toLongOrNull() ?: 0L
    val gone = remember(texts["bellGone"]) { texts["bellGone"].orEmpty().split("|").filter { it.isNotBlank() }.toSet() }
    fun idOf(a: AlertItem) = "${a.at}:${a.show.hashCode()}"
    var localGone by remember { mutableStateOf(setOf<String>()) }
    val alerts = alertsAll.filter { it.at > clearedAt && idOf(it) !in gone && idOf(it) !in localGone }
    fun dismiss(a: AlertItem) {
        localGone = localGone + idOf(a)
        Public.vm?.setText("bellGone", (gone + idOf(a)).toList().takeLast(120).joinToString("|"))
    }
    if (!embedded) BackHandler { onBack() }
    LazyColumn(Modifier.fillMaxSize().background(Bg), contentPadding = PaddingValues(bottom = 120.dp)) {
        if (!embedded) item { PageHead("Notifications", onBack) }
        if (alerts.isNotEmpty()) item { Text("Tap to open · swipe left to remove", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 12.sp, color = HubMuted) }
        if (pending.isNotEmpty()) item {
            Row(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF3A0B12)).clickable(onClick = onTogether).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Groups, null)
                Text("${pending.size} waiting for your answer in Watch together", Modifier.weight(1f).padding(start = 12.dp), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                Icon(Icons.Default.ChevronRight, null)
            }
        }
        if (alerts.isEmpty()) item { Text("No notifications yet. New episodes of shows you follow show up here.", Modifier.padding(16.dp), fontSize = 13.sp, color = HubMuted) }
        items(alerts, key = { idOf(it) }) { a ->
            val dx = remember { androidx.compose.animation.core.Animatable(0f) }
            val scope = rememberCoroutineScope()
            Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().animateItem().clip(RoundedCornerShape(14.dp)).background(Color(0xFF7A1218))) {
                Text("Remove", Modifier.align(Alignment.CenterEnd).padding(end = 18.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().offset { androidx.compose.ui.unit.IntOffset(dx.value.toInt(), 0) }.background(HubCard)
                    .pointerInput(a) {
                        detectHorizontalDragGestures(
                            onDragEnd = { scope.launch { if (dx.value < -size.width * 0.3f) { dx.animateTo(-size.width.toFloat(), androidx.compose.animation.core.tween(160)); dismiss(a) } else dx.animateTo(0f) } },
                            onDragCancel = { scope.launch { dx.animateTo(0f) } },
                        ) { ch, d -> ch.consume(); scope.launch { dx.snapTo((dx.value + d).coerceAtMost(0f)) } }
                    }
                    .clickable { onOpen(Meta(a.showId, "series", a.show, a.poster)) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(a.poster, null, Modifier.size(38.dp, 56.dp).clip(RoundedCornerShape(7.dp)), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(a.show, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(a.text, fontSize = 12.sp, color = HubMuted, maxLines = 2)
                        Text(SimpleDateFormat("d MMM · h:mm a", Locale.getDefault()).format(java.util.Date(a.at)), fontSize = 10.5.sp, color = HubMuted)
                    }
                }
            }
        }
    }
}
