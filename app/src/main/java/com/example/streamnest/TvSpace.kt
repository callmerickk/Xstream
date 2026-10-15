package com.example.streamnest

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private val SpMuted = Color(0xFF9AA3B5)

// ───────────── My Space (TV): your profile dashboard ─────────────

/**
 * TV My Space: your profile and what you watch, as a dashboard — time watched, finished titles, streak, this week,
 * top genres and milestones. Edit profile and Switch profile live here; Continue Watching and the watchlist are in
 * Library.
 */
@Composable
fun TvMySpaceScreen(vm: AppViewModel, onSwitchProfile: () -> Unit, onPlay: (Screen.Player) -> Unit, onLeftEdge: (FocusRequester) -> Unit) {
    val profile by vm.current.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val myList by vm.myList.collectAsStateWithLifecycle()
    val doneKeys by vm.doneKeys.collectAsStateWithLifecycle()
    val doneMs by vm.doneMs.collectAsStateWithLifecycle()
    val votes by vm.votes.collectAsStateWithLifecycle()
    val p = profile ?: return
    var editing by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; repeat(6) { if (first.tryFocus()) return@LaunchedEffect; delay(60) } }

    val now = System.currentTimeMillis()
    val day = 86_400_000L
    val trackedMs = doneMs + progress.sumOf { it.positionMs }
    val hours = trackedMs / 3_600_000L
    val tracked = if (hours >= 1) "${hours}h" else "${trackedMs / 60_000L}m"
    // Days in a row with something watched (from Continue Watching's last-watched times).
    val days = progress.map { (it.updated / day) }.toSet()
    var streak = 0
    run { var d = now / day; if (d !in days) d -= 1; while (d in days) { streak++; d-- } }
    val week = (6 downTo 0).map { back ->
        val d = now / day - back
        val label = SimpleDateFormat("EEE", Locale.getDefault()).format(java.util.Date(d * day))
        label to progress.filter { it.updated / day == d }.sumOf { (it.positionMs / 60_000L).coerceAtMost(240) }.toInt()
    }
    val genres = (myList.flatMap { it.genres.orEmpty() }).groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(4)
    val gTotal = genres.sumOf { it.value }.coerceAtLeast(1)
    val series = myList.count { it.type == "series" } + progress.count { it.isSeries() }
    val movies = myList.count { it.type != "series" } + progress.count { !it.isSeries() }
    val taste = when { series + movies == 0 -> "Just getting started"; series > movies * 1.5 -> "Series lover"; movies > series * 1.5 -> "Movie buff"; else -> "Balanced taste" }
    val liked = votes.values.count { it.v > 0 }

    val weekMin = week.sumOf { it.second }
    val lastWeekMin = (13 downTo 7).sumOf { back -> val d = now / day - back; progress.filter { it.updated / day == d }.sumOf { (it.positionMs / 60_000L).coerceAtMost(240) }.toInt() }
    val tags = buildList {
        if (p.kids) add("Kids profile") else add(taste)
        genres.firstOrNull()?.let { add("${it.key} fan") }
        if (progress.count { val h = java.util.Calendar.getInstance().apply { timeInMillis = it.updated }.get(java.util.Calendar.HOUR_OF_DAY); h >= 23 || h < 4 } >= 2) add("Night owl")
    }
    // Full screen: a big header with you and your buttons, four large stats, then the week, your genres and milestones.
    Column(Modifier.fillMaxSize().padding(start = TvEdge, top = 22.dp, end = 30.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(26.dp))
                .background(Brush.horizontalGradient(listOf(Color(0x47E50914), Color(0x8C1E1428), Color(0x08FFFFFF))))
                .padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(vm, p, 104.dp, Modifier.border(3.dp, Color(0x1FFFFFFF), CircleShape), animate = false)
            Column(Modifier.weight(1f).padding(horizontal = 24.dp)) {
                Text(p.name, fontFamily = BrandFont, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${myList.size} in your watchlist · ${progress.size} in progress", fontSize = 13.sp, color = Color(0xFFD0D4DD))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { Text(it, Modifier.clip(RoundedCornerShape(50)).background(Color(0x1AFFFFFF)).padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
            }
            HeaderBtn(Icons.Default.Edit, "Edit profile", true, Modifier.focusRequester(first).tvLeftEdge { onLeftEdge(first) }) { editing = true }
            Spacer(Modifier.width(10.dp))
            HeaderBtn(Icons.Default.Groups, "Join a party", false) { joining = true }
            Spacer(Modifier.width(10.dp))
            HeaderBtn(Icons.Default.SwitchAccount, "Switch", false) { onSwitchProfile() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BigStat(tracked, "Time watched", if (weekMin > 0) "${weekMin / 60}h ${weekMin % 60}m this week" else "Nothing yet this week", Modifier.weight(1f).tvLeftEdge { onLeftEdge(first) })
            BigStat("${doneKeys.size}", "Titles finished", if (doneKeys.size >= 10) "10+ to the end" else "${10 - doneKeys.size} more to reach 10", Modifier.weight(1f))
            BigStat("$streak", if (streak == 1) "Day streak" else "Days in a row", if (weekMin > lastWeekMin) "▲ more than last week" else "Keep it going", Modifier.weight(1f))
            BigStat("$liked", "Titles you loved", if (liked > 0) "From your votes" else "Vote after watching", Modifier.weight(1f))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            FocusTile(Modifier.weight(1.25f).fillMaxHeight().tvLeftEdge { onLeftEdge(first) }, pad = 18.dp) {
                Text("This week · minutes watched", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                val mx = (week.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
                Row(Modifier.fillMaxSize().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    week.forEach { (d, m) ->
                        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            if (m > 0) Text("$m", fontSize = 11.sp, color = SpMuted)
                            Box(Modifier.fillMaxWidth().fillMaxHeight((m.toFloat() / mx * 0.78f).coerceAtLeast(0.02f)).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(Brush.verticalGradient(listOf(Color(0xFFFF5A5F), Color(0xFFA80710)))))
                            Text(d, Modifier.padding(top = 5.dp), fontSize = 12.sp, color = SpMuted)
                        }
                    }
                }
            }
            FocusTile(Modifier.weight(1f).fillMaxHeight(), pad = 18.dp) {
                Text("Your top genres", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (genres.isEmpty()) Text("Add titles to your watchlist and your genres show up here.", Modifier.padding(top = 12.dp), fontSize = 13.sp, color = SpMuted)
                genres.forEach { (g, n) ->
                    val pct = n * 100 / gTotal
                    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(g, Modifier.width(100.dp), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.weight(1f).height(9.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x1AFFFFFF))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(pct / 100f).background(Color(0xFFE50914)))
                        }
                        Text("$pct%", Modifier.width(44.dp), fontSize = 12.sp, color = SpMuted, textAlign = TextAlign.End)
                    }
                }
            }
            FocusTile(Modifier.weight(1f).fillMaxHeight(), pad = 18.dp) {
                Text("Milestones", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                listOf(
                    Triple("${doneKeys.size.coerceAtMost(99)}", "Finished", if (doneKeys.size >= 10) "10+ titles to the end" else "${10 - doneKeys.size} more to reach 10"),
                    Triple("${hours}h", "Hours watched", if (hours >= 24) "A full day of watching" else "Tracked so far"),
                    Triple("$series", "Series", "Shows you follow"),
                    Triple("$movies", "Movies", "Films you watch or saved"),
                ).forEach { (n, t, sub) ->
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFF5C518), Color(0xFFB8860B)))), Alignment.Center) {
                            Text(n, fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color.Black, maxLines = 1)
                        }
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(t, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(sub, fontSize = 11.sp, color = SpMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    if (editing) TvProfileEditor(vm, p) { editing = false; first.tryFocus() }
    if (joining) TvJoinDialog(onDismiss = { joining = false; first.tryFocus() }) { st ->
        joining = false
        val expected = st.pos + if (st.playing) (System.currentTimeMillis() - st.receivedAt + 300) else 0L
        onPlay(Screen.Player(st.title, st.url, emptyList(), key = st.key, image = st.image, resumeMs = expected, type = st.type))
    }
}

@Composable
private fun SpaceBtn(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    TxButton(modifier.fillMaxWidth(), idle = Color(0x14FFFFFF), onClick = onClick) { _ ->
        Icon(icon, null, Modifier.padding(start = 14.dp).size(18.dp), tint = Color.White)
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 11.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }

@Composable
private fun HeaderBtn(icon: ImageVector, label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
    TxButton(modifier, shape = RoundedCornerShape(16.dp), idle = if (primary) Color(0xFFD10A14) else Color(0x59000000), onClick = onClick) { _ ->
        Icon(icon, null, Modifier.padding(start = 16.dp).size(19.dp), tint = Color.White)
        Text(label, Modifier.padding(start = 8.dp, end = 18.dp, top = 13.dp, bottom = 13.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }

@Composable
private fun BigStat(value: String, label: String, note: String, modifier: Modifier) = FocusTile(modifier, pad = 18.dp) {
    Text(value, fontFamily = BrandFont, fontSize = 36.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    Text(label, fontSize = 13.sp, color = SpMuted, fontWeight = FontWeight.SemiBold, maxLines = 1)
    Text(note, Modifier.padding(top = 4.dp), fontSize = 11.sp, color = Color(0xFF7FE7AB), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier) = FocusTile(modifier) {
    Text(value, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    Text(label, fontSize = 11.sp, color = SpMuted, maxLines = 1)
}

/** A dashboard tile the remote can move onto (so every part of the page is reachable and readable). */
@Composable
private fun FocusTile(modifier: Modifier, pad: androidx.compose.ui.unit.Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.onFocusChanged { f = it.isFocused }
            .clip(shape).background(if (f) TvFocusFill else Color(0x0DFFFFFF)).border(if (f) 2.dp else 1.dp, if (f) TvFocusEdge else Color(0x14FFFFFF), shape)
            .focusable()
            .padding(pad),
        content = content,
    )
}

/** Watch together › join by code, with the remote. */
@Composable
fun TvJoinDialog(onDismiss: () -> Unit, onJoined: (RoomState) -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(10) { delay(60); if (first.tryFocus()) return@LaunchedEffect } }
    BackHandler { onDismiss() }
    Box(Modifier.fillMaxSize().background(Color(0xA6000000)), Alignment.Center) {
        val shape = RoundedCornerShape(26.dp)
        Column(Modifier.width(480.dp).clip(shape).background(Color(0xF5111216)).border(1.dp, Color(0x47E50914), shape).padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Join a watch party", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Enter the 4-letter room code your friend sees in their player. OK opens the keyboard.", fontSize = 12.sp, color = SpMuted)
            var nf by remember { mutableStateOf(false) }
            androidx.compose.foundation.text.BasicTextField(
                input, { input = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(4) },
                Modifier.fillMaxWidth().focusRequester(first).onFocusChanged { nf = it.isFocused }.tvOkKeyboard()
                    .clip(RoundedCornerShape(14.dp)).background(if (nf) TvFocusFill else Color(0x14FFFFFF))
                    .border(2.dp, if (nf) TvFocusEdge else Color.Transparent, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                singleLine = true, keyboardOptions = tvKeyboard(),
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 8.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
            )
            msg?.let { Text(it, fontSize = 12.sp, color = Color(0xFFFFB4A8)) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TxButton(Modifier.weight(1f), idle = Color(0xFFD10A14), onClick = {
                    if (busy || input.length != 4) { msg = "The code has 4 letters or numbers."; return@TxButton }
                    busy = true
                    scope.launch { val (st, err) = Together.join(input); busy = false; msg = err; if (st != null) onJoined(st) }
                }) { _ -> Text(if (busy) "Joining…" else "Join", Modifier.fillMaxWidth().padding(vertical = 12.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                TxButton(Modifier.weight(1f), onClick = onDismiss) { _ ->
                    Text("Cancel", Modifier.fillMaxWidth().padding(vertical = 12.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

// ───────────── Library (TV): Watchlist · Calendar · Notifications · Continue watching ─────────────

private enum class LibTab(val label: String, val icon: ImageVector) {
    List("Watchlist", Icons.Default.Bookmark), Calendar("Calendar", Icons.Default.CalendarMonth),
    Alerts("Notifications", Icons.Default.Notifications), Cw("Continue watching", Icons.Default.History),
}

private data class CalEp(val day: Long, val show: Meta, val ep: Video)

/**
 * TV Library: the sections on the left (focus = open), the section on the right. Calendar = new episodes coming for
 * the shows in your watchlist and Continue Watching; Continue watching has Remove per title and Clear all.
 */
@Composable
fun TvLibraryHub(vm: AppViewModel, onOpen: (Meta) -> Unit, onPlay: (Screen.Player) -> Unit, onLeftEdge: (FocusRequester) -> Unit) {
    val ctx = LocalContext.current
    val progress by vm.progress.collectAsStateWithLifecycle()
    val myList by vm.myList.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(LibTab.List) }
    val tabFrs = remember { LibTab.entries.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) { withFrameNanos { }; repeat(6) { if (tabFrs.getValue(tab).tryFocus()) return@LaunchedEffect; delay(60) } }
    var alerts by remember { mutableStateOf(EpisodeAlerts.inbox(ctx)) }
    var confirmClear by remember { mutableStateOf(false) }

    // Calendar: next 14 days for watchlist / Continue Watching shows (episode lists are cached for 6 h).
    val shows = remember(myList, progress) {
        (myList.filter { it.type == "series" } + progress.filter { it.isSeries() }.map { it.asMeta() }).distinctBy { it.id }.take(30)
    }
    var cal by remember { mutableStateOf<List<CalEp>?>(null) }
    LaunchedEffect(shows, tab == LibTab.Calendar) {
        if (tab != LibTab.Calendar || cal != null) return@LaunchedEffect
        val day = 86_400_000L
        val today = System.currentTimeMillis() / day
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val out = ArrayList<CalEp>()
        for (s in shows) {
            val m = runCatching { vm.seriesMeta(s.id) }.getOrNull() ?: continue
            m.videos.orEmpty().forEach { v ->
                val d = v.released?.take(10)?.let { runCatching { fmt.parse(it)?.time }.getOrNull() } ?: return@forEach
                val dd = d / day
                if (dd in today..(today + 30) && (v.season ?: 0) > 0) out += CalEp(dd, m.copy(poster = m.poster ?: s.poster), v)
            }
            cal = out.sortedBy { it.day }
        }
        cal = out.sortedBy { it.day }
    }

    Row(Modifier.fillMaxSize().padding(start = TvEdge, top = 26.dp, end = 30.dp)) {
        Column(Modifier.width(230.dp)) {
            Text("Library", Modifier.padding(start = 10.dp, bottom = 12.dp), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            LibTab.entries.forEach { t ->
                val on = t == tab
                TxButton(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).focusRequester(tabFrs.getValue(t)).tvLeftEdge { onLeftEdge(tabFrs.getValue(t)) },
                    idle = if (on) Color(0x14FFFFFF) else Color.Transparent,
                    onFocus = { if (it) tab = t }, onClick = { tab = t },
                ) { _ ->
                    Icon(t.icon, null, Modifier.padding(start = 12.dp).size(18.dp), tint = if (on) Color.White else Color(0xFFC9CED8))
                    Text(t.label, Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 11.dp), fontSize = 13.5.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, color = if (on) Color.White else Color(0xFFC9CED8), maxLines = 1)
                    val n = when (t) { LibTab.Alerts -> alerts.size; LibTab.Cw -> progress.size; LibTab.List -> myList.size; else -> 0 }
                    if (n > 0 && t != LibTab.Calendar) Text("$n", Modifier.padding(end = 12.dp).clip(RoundedCornerShape(9.dp))
                        .background(if (t == LibTab.Alerts) Color(0xFFE50914) else Color(0x24FFFFFF)).padding(horizontal = 7.dp, vertical = 1.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp)) {
            when (tab) {
                LibTab.List -> {
                    if (myList.isEmpty()) LibEmpty("Titles you add with + show up here.")
                    else LazyVerticalGrid(GridCells.Fixed(6), Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 50.dp, bottom = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        itemsIndexed(myList, key = { _, m -> m.type + m.id }) { _, m ->
                            TvPosterCard(m, Modifier, 112.dp, 168.dp) { onOpen(m) }
                        }
                    }
                }
                LibTab.Calendar -> {
                    val c = cal
                    when {
                        shows.isEmpty() -> LibEmpty("Add series to your watchlist — their new episodes show up here.")
                        c == null -> LibEmpty("Checking new episodes…")
                        c.isEmpty() -> LibEmpty("No new episodes in the next 30 days for your shows.")
                        else -> CalendarWeek(c) { onOpen(it.show) }
                    }
                }
                LibTab.Alerts -> Column(Modifier.fillMaxSize().padding(top = 50.dp)) {
                    if (alerts.isEmpty()) LibEmpty("New-episode alerts show up here.")
                    else {
                        TxButton(Modifier.padding(bottom = 10.dp), onClick = { EpisodeAlerts.clearInbox(ctx); alerts = emptyList(); tabFrs.getValue(LibTab.Alerts).tryFocus() }) { _ ->
                            Text("Clear all", Modifier.padding(horizontal = 16.dp, vertical = 9.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                            items(alerts.sortedByDescending { it.at }, key = { it.showId + it.at }) { a ->
                                TxButton(Modifier.fillMaxWidth(), idle = Color(0x0AFFFFFF), onClick = { onOpen(Meta(id = a.showId, type = "series", name = a.show, poster = a.poster)) }) { _ ->
                                    AsyncImage(a.poster, null, Modifier.padding(8.dp).size(44.dp, 64.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                        Text(a.show, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text(a.text, fontSize = 12.sp, color = SpMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(SimpleDateFormat("d MMM", Locale.getDefault()).format(java.util.Date(a.at)), Modifier.padding(end = 14.dp), fontSize = 11.sp, color = SpMuted)
                                }
                            }
                        }
                    }
                }
                LibTab.Cw -> Column(Modifier.fillMaxSize().padding(top = 50.dp)) {
                    if (progress.isEmpty()) LibEmpty("Continue Watching is empty. Start something and it shows up here.")
                    else {
                        TxButton(Modifier.padding(bottom = 10.dp), onClick = { confirmClear = true }) { _ ->
                            Text("Clear all", Modifier.padding(horizontal = 16.dp, vertical = 9.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                            items(progress, key = { it.key }) { pr ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TxButton(Modifier.weight(1f), idle = Color(0x0AFFFFFF), onClick = {
                                        if (pr.finished()) onOpen(pr.asMeta()) else onPlay(Screen.Player(pr.title, pr.url, pr.subs, pr.key, pr.image, pr.positionMs))
                                    }) { _ ->
                                        Box(Modifier.padding(8.dp).size(112.dp, 63.dp).clip(RoundedCornerShape(8.dp)).background(Surface1)) {
                                            AsyncImage(pr.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x40FFFFFF))) {
                                                Box(Modifier.fillMaxHeight().fillMaxWidth(pr.fraction()).background(BrandRed))
                                            }
                                        }
                                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                            Text(pr.title.replace("  ", " · "), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(cwTimeLeft(pr), fontSize = 12.sp, color = SpMuted)
                                        }
                                    }
                                    TxButton(Modifier.padding(start = 8.dp), onClick = { vm.removeProgress(pr.key) }) { _ ->
                                        Text("Remove", Modifier.padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) TvConfirm(
        "Clear Continue Watching?", "Removes every title from Continue Watching on this profile (TV and phone). Your watchlist stays.", "Clear all",
        onYes = { vm.clearContinue(); confirmClear = false; tabFrs.getValue(LibTab.Cw).tryFocus() },
        onNo = { confirmClear = false; tabFrs.getValue(LibTab.Cw).tryFocus() },
    )
}

@Composable
private fun LibEmpty(text: String) = Text(text, Modifier.padding(top = 60.dp), fontSize = 14.sp, color = SpMuted)

/** The next days as columns, each with the episodes coming that day. */
@Composable
private fun CalendarWeek(list: List<CalEp>, onPick: (CalEp) -> Unit) {
    val day = 86_400_000L
    val today = System.currentTimeMillis() / day
    val byDay = list.groupBy { it.day }
    val days = (0 until 7).map { today + it }
    val later = list.filter { it.day >= today + 7 }
    Column(Modifier.fillMaxSize().padding(top = 50.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEach { d ->
                val isToday = d == today
                val cal = Calendar.getInstance().apply { timeInMillis = d * day }
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp))
                        .background(if (isToday) Color(0x14E50914) else Color(0x08FFFFFF))
                        .border(1.dp, if (isToday) Color(0x80E50914) else Color(0x14FFFFFF), RoundedCornerShape(14.dp)).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isToday) "TODAY" else SimpleDateFormat("EEE", Locale.getDefault()).format(cal.time).uppercase(), Modifier.weight(1f),
                            fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = SpMuted)
                        Text("${cal.get(Calendar.DAY_OF_MONTH)}", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    val eps = byDay[d].orEmpty()
                    if (eps.isEmpty()) Text("Nothing new", fontSize = 10.sp, color = Color(0xFF6E7687))
                    eps.take(3).forEach { e ->
                        TxButton(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), idle = Color(0x12FFFFFF), onClick = { onPick(e) }) { _ ->
                            Column(Modifier.padding(6.dp)) {
                                AsyncImage(e.ep.thumbnail ?: e.show.background ?: e.show.poster, null, Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                                Text(e.show.name, Modifier.padding(top = 4.dp), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("S${e.ep.season} E${e.ep.episode}", fontSize = 9.5.sp, color = Color(0xFFC9CED8), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        if (later.isNotEmpty()) Text(
            "Later: " + later.take(5).joinToString("   ·   ") { e ->
                "${e.show.name} S${e.ep.season} E${e.ep.episode} · " + SimpleDateFormat("d MMM", Locale.getDefault()).format(java.util.Date(e.day * day))
            },
            Modifier.padding(top = 10.dp, bottom = 24.dp), fontSize = 11.5.sp, color = Color(0xFFC9CED8), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A remote-friendly yes / no. Back = no. */
@Composable
fun TvConfirm(title: String, text: String, yes: String, onYes: () -> Unit, onNo: () -> Unit) {
    val no = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(10) { delay(60); if (no.tryFocus()) return@LaunchedEffect } }
    BackHandler { onNo() }
    Box(Modifier.fillMaxSize().background(Color(0xA6000000)), Alignment.Center) {
        val shape = RoundedCornerShape(24.dp)
        Column(Modifier.width(460.dp).clip(shape).background(Color(0xF7111216)).border(1.dp, Color(0x66E50914), shape).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(text, Modifier.padding(top = 8.dp, bottom = 16.dp), fontSize = 13.sp, color = SpMuted, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TxButton(Modifier.weight(1f), idle = Color(0xFFD10A14), onClick = onYes) { _ ->
                    Text(yes, Modifier.fillMaxWidth().padding(vertical = 11.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                TxButton(Modifier.weight(1f).focusRequester(no), onClick = onNo) { _ ->
                    Text("Keep them", Modifier.fillMaxWidth().padding(vertical = 11.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
