package com.example.streamnest

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val LMuted = Color(0xFF9AA3B5)
private val LCard = Color(0xFF14171D)
private val LLine = Color(0xFF262A34)

// ───────────── Recommendations between friends ─────────────

/** A title recommended between you and a friend ([out] = you sent it). [fb] = the feedback about it, once given. */
@Serializable data class Rec(
    val id: String, val type: String, val name: String, val poster: String? = null,
    val friend: String, val friendName: String, val out: Boolean, val at: Long,
    val fb: String? = null, val asked: Boolean = false,
)

/** Recommendations, kept on this phone (per account) and filled from the Watch together inbox. */
object Recs {
    val all = MutableStateFlow<List<Rec>>(emptyList())
    /** Puts a recommended title in your watchlist (set by the app). */
    @Volatile var addToList: ((Meta) -> Unit)? = null
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences("recs", Context.MODE_PRIVATE)
        all.value = runCatching { Api.json.decodeFromString<List<Rec>>(prefs?.getString("all", null) ?: "[]") }.getOrDefault(emptyList())
    }
    private fun save(l: List<Rec>) { all.value = l.take(300); prefs?.edit()?.putString("all", Api.json.encodeToString(all.value))?.apply() }

    /** Sends [m] to [p]'s room: it lands in their watchlist with a note from you. */
    suspend fun send(m: Meta, p: Person) {
        Social.sendRaw(p.uid, SocialMsg(t = "rec", item = m.id, title = m.name, plan = m.type, code = m.poster.orEmpty()))
        save(listOf(Rec(m.id, m.type, m.name, m.poster, p.uid, p.name, out = true, at = System.currentTimeMillis())) + all.value.filterNot { it.out && it.id == m.id && it.friend == p.uid })
    }

    /** A friend's recommendation arrived. */
    fun incoming(ctx: Context, m: SocialMsg) {
        init(ctx)
        save(listOf(Rec(m.item, m.plan.ifBlank { "movie" }, m.title, m.code.ifBlank { null }, m.from, m.fromName, out = false, at = System.currentTimeMillis()))
            + all.value.filterNot { !it.out && it.id == m.item && it.friend == m.from })
        addToList?.invoke(Meta(id = m.item, type = m.plan.ifBlank { "movie" }, name = m.title, poster = m.code.ifBlank { null }))
    }

    /** A friend's feedback about something you recommended. */
    fun feedback(ctx: Context, m: SocialMsg) {
        init(ctx)
        save(all.value.map { if (it.out && it.id == m.item && it.friend == m.from) it.copy(fb = m.aboutName) else it })
    }

    /** You tell [r]'s sender what you thought. */
    suspend fun answer(r: Rec, text: String) {
        Social.sendRaw(r.friend, SocialMsg(t = "recfb", item = r.id, title = r.name, aboutName = text))
        save(all.value.map { if (it === r || (it.id == r.id && it.friend == r.friend && !it.out)) it.copy(fb = text, asked = true) else it })
    }
}

/** Xstream: adding to the watchlist anywhere asks "my library or a friend's room?" (shown by the app's root). */
object WatchlistAsk {
    val pending = MutableStateFlow<Meta?>(null)
    /** Removes when already saved; otherwise asks where to add it. */
    fun toggle(vm: AppViewModel, m: Meta) { if (vm.myList.value.any { it.id == m.id }) vm.toggleList(m) else pending.value = m }
}

/** "Add to watchlist": your own library, or a friend's room (it goes into their watchlist and they're told it's from you). */
@Composable
fun AddToWatchlistSheet(vm: AppViewModel, m: Meta, onDone: () -> Unit) {
    val friends by Social.friends.collectAsStateWithLifecycle()
    val myList by vm.myList.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val inMine = myList.any { it.id == m.id }
    PremiumAlert(onDismissRequest = onDone, title = { Text("Add ${m.name} to…") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScrollCompatX()) {
            Text("Your library, or a friend's room. A friend gets it in their watchlist with a note that you recommended it.", fontSize = 12.5.sp, color = LMuted)
            ChoiceRow(Icons.Default.Bookmark, if (inMine) "Already in my library" else "My library", "Only in your watchlist") {
                if (!inMine) vm.toggleList(m); Snack.show("${m.name} · in your watchlist"); onDone()
            }
            friends.forEach { p ->
                ChoiceRow(Icons.Default.Person, "${p.name}'s room", "Recommend it to ${p.name}") {
                    scope.launch { Recs.send(m, p); Snack.show("Sent to ${p.name}'s room") }; onDone()
                }
            }
            if (friends.isEmpty()) Text("Add friends in Watch together to recommend titles to them.", Modifier.padding(top = 10.dp), fontSize = 12.sp, color = LMuted)
        }
    }, confirmButton = {}, dismissButton = { androidx.compose.material3.TextButton(onDone) { Text("Cancel") } })
}

@Composable
private fun ChoiceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LCard).border(1.dp, LLine, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF232733)), Alignment.Center) { Icon(icon, null, Modifier.size(18.dp)) }
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(sub, fontSize = 11.5.sp, color = LMuted)
        }
    }
}

@Composable
private fun Modifier.verticalScrollCompatX(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))

/** A friend's room: what you recommended to them, what they recommended to you, and feedback both ways. */
@Composable
private fun FriendRoom(vm: AppViewModel, p: Person, onOpen: (Meta) -> Unit) {
    val all by Recs.all.collectAsStateWithLifecycle()
    val done by vm.doneKeys.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val mine = all.filter { it.friend == p.uid }
    val toThem = mine.filter { it.out }
    val toMe = mine.filter { !it.out }
    LazyColumn(contentPadding = PaddingValues(bottom = 140.dp)) {
        // You finished something they recommended: tell them.
        val ask = toMe.filter { r -> r.fb == null && done.any { it.substringBefore(":") == r.id } }
        items(ask, key = { "ask" + it.id }) { r ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1E1810)).border(1.dp, Color(0xFF4A3A1E), RoundedCornerShape(16.dp)).padding(12.dp)) {
                Text("You finished ${r.name}, which ${p.name} recommended. What did you think?", fontSize = 13.sp, color = Color(0xFFF3E3C6))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Loved it", "It was okay", "Not for me").forEach { f ->
                        Text(f, Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF2E2414)).clickable { scope.launch { Recs.answer(r, f); Snack.show("Sent to ${p.name}") } }
                            .padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF3E3C6))
                    }
                }
            }
        }
        toThem.filter { it.fb != null }.forEach { r ->
            item(key = "fb" + r.id) {
                Text("${p.name} watched ${r.name}: “${r.fb}”", Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LCard).padding(12.dp), fontSize = 13.sp)
            }
        }
        item { RoomShelf("${p.name} recommended to you", toMe, onOpen) }
        item { RoomShelf("You recommended to ${p.name}", toThem, onOpen) }
        if (mine.isEmpty()) item { Text("Nothing here yet. Long-press any poster › Add to watchlist › ${p.name}'s room.", Modifier.padding(16.dp), fontSize = 13.sp, color = LMuted) }
    }
}

@Composable
private fun RoomShelf(title: String, recs: List<Rec>, onOpen: (Meta) -> Unit) {
    if (recs.isEmpty()) return
    Text(title, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(recs, key = { it.id + it.at }) { r ->
            Column(Modifier.width(110.dp).clickable { onOpen(Meta(id = r.id, type = r.type, name = r.name, poster = r.poster)) }) {
                AsyncImage(r.poster, r.name, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(LCard), contentScale = ContentScale.Crop)
                Text(r.name, Modifier.padding(top = 4.dp), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(SimpleDateFormat("d MMM", Locale.getDefault()).format(java.util.Date(r.at)), fontSize = 10.sp, color = LMuted)
            }
        }
    }
}

/**
 * Xstream Library: Watchlist (my room and one room per friend), Calendar, Notifications and Watch together, picked
 * from a row of tabs you can slide.
 */
@Composable
fun XLibraryPage(vm: AppViewModel, onBack: (() -> Unit)?, onOpen: (Meta) -> Unit, onPlayRoom: (RoomState) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Recs.init(ctx) }
    val friends by Social.friends.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf("watchlist") }
    var room by remember { mutableStateOf<String?>(null) }
    if (onBack != null) BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.statusBarsPadding().padding(start = if (onBack != null) 6.dp else 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), Alignment.Center) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Library", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("watchlist" to "Watchlist", "calendar" to "Calendar", "alerts" to "Notifications", "together" to "Watch together").forEach { (k, n) ->
                val on = tab == k
                Text(n, Modifier.clip(RoundedCornerShape(50)).background(if (on) Color(0xFFF2F4F8) else LCard).border(1.dp, if (on) Color(0xFFF2F4F8) else LLine, RoundedCornerShape(50))
                    .clickable { tab = k }.padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on) Color(0xFF0E1014) else LMuted)
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                "watchlist" -> Column {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (listOf<Person?>(null) + friends).forEach { p ->
                            val on = room == p?.uid
                            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Color(0xFF1F232C) else Color(0xFF12151B)).border(1.dp, if (on) Color(0xFFF2F4F8) else LLine, RoundedCornerShape(12.dp))
                                .clickable { room = p?.uid }.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (p == null) Icons.Default.Home else Icons.Default.Person, null, Modifier.size(15.dp))
                                Text(if (p == null) "My room" else p.name, Modifier.padding(start = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    val p = friends.firstOrNull { it.uid == room }
                    if (p == null) WatchlistPage(vm, onBack = {}, onOpen = onOpen, startCalendar = false, embedded = true)
                    else FriendRoom(vm, p, onOpen)
                }
                "calendar" -> WatchlistPage(vm, onBack = {}, onOpen = onOpen, startCalendar = true, embedded = true)
                "alerts" -> NotificationsPage(onBack = { tab = "watchlist" }, onTogether = { tab = "together" }, embedded = true)
                else -> WatchTogetherPage(vm, onBack = { tab = "watchlist" }, onOpen = onOpen, onPlayRoom = onPlayRoom)
            }
        }
    }
}

// ───────────── In-app date and time picker ─────────────

/** Pick a day (next 3 weeks) and a time (every 30 minutes) inside the app. */
@Composable
fun DateTimeSheet(startAt: Long?, title: String, onPicked: (Long) -> Unit, onCancel: () -> Unit) {
    val now = System.currentTimeMillis()
    val base = remember { Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis }
    val start = startAt ?: Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 2); set(Calendar.MINUTE, 0) }.timeInMillis
    var day by remember { mutableIntStateOf(((start - base) / 86_400_000L).toInt().coerceIn(0, 20)) }
    var minute by remember { mutableIntStateOf(Calendar.getInstance().apply { timeInMillis = start }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + (it.get(Calendar.MINUTE) / 30) * 30 }) }
    fun atOf(d: Int, m: Int) = Calendar.getInstance().apply { timeInMillis = base; add(Calendar.DAY_OF_MONTH, d); set(Calendar.HOUR_OF_DAY, m / 60); set(Calendar.MINUTE, m % 60) }.timeInMillis
    val at = atOf(day, minute)
    PremiumAlert(onDismissRequest = onCancel, title = { Text(title) }, text = {
        Column {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items((0..20).toList()) { d ->
                    val c = Calendar.getInstance().apply { timeInMillis = base; add(Calendar.DAY_OF_MONTH, d) }
                    val on = d == day
                    Column(Modifier.width(52.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Color(0xFFF2F4F8) else LCard).border(1.dp, if (on) Color(0xFFF2F4F8) else LLine, RoundedCornerShape(12.dp))
                        .clickable { day = d }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (d == 0) "TODAY" else SimpleDateFormat("EEE", Locale.getDefault()).format(c.time).uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (on) Color(0xFF0E1014) else LMuted)
                        Text("${c.get(Calendar.DAY_OF_MONTH)}", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = if (on) Color(0xFF0E1014) else Color.White)
                        Text(SimpleDateFormat("MMM", Locale.getDefault()).format(c.time), fontSize = 9.sp, color = if (on) Color(0xFF0E1014) else LMuted)
                    }
                }
            }
            val times = (0 until 48).map { it * 30 }.filter { day > 0 || atOf(0, it) > now }
            val listState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            LaunchedEffect(day) { val i = times.indexOf(minute); if (i > 3) listState.scrollToItem(i - 3) }
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Fixed(4), Modifier.padding(top = 12.dp).height(170.dp), state = listState,
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(times.size) { i ->
                    val m = times[i]; val on = m == minute
                    val label = SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date(atOf(0, m)))
                    Text(label, Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Color(0xFFF2F4F8) else LCard).clickable { minute = m }.padding(vertical = 9.dp),
                        fontSize = 11.5.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (on) Color(0xFF0E1014) else Color.White)
                }
            }
        }
    }, confirmButton = {
        androidx.compose.material3.TextButton({ if (at > now) onPicked(at) else Snack.show("Pick a time later than now") }) {
            Text("Next · " + SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(java.util.Date(at)))
        }
    }, dismissButton = { androidx.compose.material3.TextButton(onCancel) { Text("Cancel") } })
}

/** Search any title (your addons) to plan it: used by the calendar. */
@Composable
fun TitleSearchBox(vm: AppViewModel, onPick: (Meta) -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<Meta>>(emptyList()) }
    fun go() { val t = q.trim(); if (t.length < 2) return; busy = true; scope.launch { hits = runCatching { vm.searchNow(t) }.getOrDefault(emptyList()).filter { it.type == "movie" || it.type == "series" }.take(12); busy = false } }
    Column {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search any title") },
            leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { go() }))
        if (busy) Text("Searching…", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = LMuted)
        hits.forEach { m ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).clickable { onPick(m) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(m.poster, null, Modifier.size(34.dp, 50.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                Column(Modifier.padding(start = 10.dp)) {
                    Text(m.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(listOfNotNull(if (m.type == "series") "Series" else "Movie", m.releaseInfo?.take(4)).joinToString(" · "), fontSize = 11.sp, color = LMuted)
                }
            }
        }
    }
}
