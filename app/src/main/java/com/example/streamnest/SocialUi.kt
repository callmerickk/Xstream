package com.example.streamnest

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val WtCard = Color(0xFF14171D)
private val WtLine = Color(0xFF262A34)
private val WtMuted = Color(0xFF9AA1AF)
private val WtRed = Color(0xFFE50914)
private val WtGreen = Color(0xFF3DD68C)

private val avatarColors = listOf(Color(0xFFFFB46B), Color(0xFF7BE0FF), Color(0xFFB9F27C), Color(0xFFFF9BD2), Color(0xFFC7A6FF), Color(0xFFFFE07A))
private fun colorFor(uid: String) = avatarColors[(uid.hashCode() and 0x7fffffff) % avatarColors.size]

@Composable
fun Initial(p: Person, size: Int = 36) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(colorFor(p.uid)), Alignment.Center) {
        Text(p.name.take(1).uppercase().ifBlank { "?" }, fontSize = (size * 0.42f).sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0E1014))
    }
}

@Composable
private fun WtButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    Text(text, modifier.clip(RoundedCornerShape(50)).background(when { primary -> WtRed; danger -> Color(0xFF3A1418); else -> Color(0xFF1F232C) })
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = if (danger) Color(0xFFFF8A80) else Color.White, maxLines = 1)
}

@Composable
private fun Section(title: String, sub: String? = null) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        sub?.let { Text(it, Modifier.padding(top = 2.dp), fontSize = 12.sp, color = WtMuted, lineHeight = 16.sp) }
    }
}

@Composable
private fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(WtCard).border(1.dp, WtLine, RoundedCornerShape(16.dp)).padding(12.dp), content = content)
}

/**
 * Watch together, a full page: Friends (requests, introductions, your room friends), Shared lists (titles with their
 * plans), Chats, and Privacy (who can see you). Joining with a code is at the top of Friends.
 */
@Composable
fun WatchTogetherPage(vm: AppViewModel, onBack: () -> Unit, onOpen: (Meta) -> Unit, onPlayRoom: (RoomState) -> Unit, startChat: String? = null, startItem: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable0(if (startChat != null) 2 else if (startItem != null) 1 else 0)
    val friends by Social.friends.collectAsStateWithLifecycle()
    val pending by Social.pending.collectAsStateWithLifecycle()
    val items by Social.items.collectAsStateWithLifecycle()
    val chats by Social.chats.collectAsStateWithLifecycle()
    val seeMe by Social.seeMe.collectAsStateWithLifecycle()
    val iCanSee by Social.iCanSee.collectAsStateWithLifecycle()
    val err by Social.error.collectAsStateWithLifecycle()
    val user by Cloud.user.collectAsStateWithLifecycle()
    var chatOpen by remember { mutableStateOf<ChatInfo?>(null) }
    var itemOpen by remember { mutableStateOf<SharedItem?>(null) }
    var introduceTo by remember { mutableStateOf<Person?>(null) }
    var newGroup by remember { mutableStateOf(false) }
    var shareTitle by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var removeFriend by remember { mutableStateOf<Person?>(null) }
    LaunchedEffect(Unit) { runCatching { Social.check(ctx) }; while (true) { delay(15_000); runCatching { Social.refresh() } } }
    LaunchedEffect(startChat, chats) { if (startChat != null && chatOpen == null) chats.firstOrNull { it.id == startChat }?.let { chatOpen = it } }
    LaunchedEffect(startItem, items) { if (startItem != null && itemOpen == null) items.firstOrNull { it.id == startItem }?.let { itemOpen = it } }

    chatOpen?.let { c -> ChatScreen(c, onBack = { chatOpen = null }); return }
    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(Bg)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            item {
                Row(Modifier.statusBarsPadding().padding(start = 6.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), Alignment.Center) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text("Watch together", Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    WtButton("Join with code") { joining = true }
                }
            }
            if (user == null) item { CardBox { Text("Sign in (Settings › Account & Cloud Sync) to use friends, shared lists and chats.", fontSize = 13.sp, color = WtMuted) } }
            err?.let { e -> item { CardBox { Text(e, fontSize = 12.5.sp, color = Color(0xFFFFB4A8)); Text("Ask the app owner to add the \"social\" rules in Firebase.", fontSize = 11.5.sp, color = WtMuted) } } }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Friends" + if (pending.isNotEmpty()) " · ${pending.size}" else "", "Shared lists", "Chats", "Privacy").forEachIndexed { i, n ->
                        Text(n, Modifier.clip(RoundedCornerShape(50)).background(if (tab == i) Color(0xFFF2F4F8) else WtCard).border(1.dp, if (tab == i) Color.Transparent else WtLine, RoundedCornerShape(50))
                            .clickable { tab = i }.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = if (tab == i) Color(0xFF0E1014) else WtMuted)
                    }
                }
            }
            when (tab) {
                0 -> {
                    if (pending.isNotEmpty()) {
                        item { Section("Waiting for your answer") }
                        items(pending, key = { it.first }) { (id, m) ->
                            CardBox {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Initial(Person(m.from, m.fromName))
                                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                        if (m.t == "freq") {
                                            Text("${m.fromName} wants to be your watch friend", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                                            Text("Friends can ring you, plan with you and chat any time.", fontSize = 11.5.sp, color = WtMuted)
                                        } else {
                                            Text("${m.fromName} wants to connect you with ${m.aboutName}", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                                            Text("If you accept, ${m.aboutName} can see you in a live room (your name, voice, video, chat). They decide the same about you. You can take it back any time.", fontSize = 11.5.sp, color = WtMuted, lineHeight = 15.sp)
                                        }
                                    }
                                }
                                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    WtButton("Accept", primary = true) { Social.answer(id, m, true) }
                                    WtButton("Decline") { Social.answer(id, m, false) }
                                }
                            }
                        }
                    }
                    item { Section("Your watch friends", "You and the people you invited, or who invited you. Friends can be rung, planned with and chatted with.") }
                    if (friends.isEmpty()) item { CardBox { Text("No friends yet. Start a room in the player (More › Together) and share the invite: whoever joins through it becomes your watch friend.", fontSize = 12.5.sp, color = WtMuted, lineHeight = 17.sp) } }
                    items(friends, key = { it.uid }) { f ->
                        CardBox {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Initial(f, 40)
                                Text(f.name, Modifier.weight(1f).padding(start = 12.dp), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                WtButton("Chat", primary = true) { scope.launch { Social.dm(f)?.let { chatOpen = it } } }
                                if (friends.size > 1) WtButton("Introduce friends") { introduceTo = f }
                                WtButton("Remove", danger = true) { removeFriend = f }
                            }
                        }
                    }
                }
                1 -> {
                    item { Section("Shared lists", "Titles you share with some of your friends. Only the people in each one can see it.") }
                    item { Box(Modifier.padding(horizontal = 16.dp)) { WtButton("＋ Share a title", primary = true) { shareTitle = true } } }
                    if (items.isEmpty()) item { CardBox { Text("Nothing shared yet. Share a title from your watchlist, or open any title › ⋯ › Share with friends.", fontSize = 12.5.sp, color = WtMuted) } }
                    items(items.chunked(3)) { row ->
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { it2 ->
                                Column(Modifier.weight(1f).clickable { itemOpen = it2 }) {
                                    AsyncImage(it2.meta.poster, it2.meta.name, Modifier.fillMaxWidth().aspectRatio(0.68f).clip(RoundedCornerShape(12.dp)).background(WtCard), contentScale = ContentScale.Crop)
                                    Text(it2.meta.name, Modifier.padding(top = 4.dp), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val next = it2.plans.firstOrNull { p -> p.at > System.currentTimeMillis() - 3 * 3600_000L }
                                    Text(next?.let { p -> Social.planTime(p.at) } ?: "${it2.members.size} people", fontSize = 10.sp, color = if (next != null) WtGreen else WtMuted, maxLines = 1)
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                2 -> {
                    item { Section("Chats", "One-to-one chats with your friends (photos are view-once) and groups (text only, cleared after 24 hours).") }
                    item { Box(Modifier.padding(horizontal = 16.dp)) { WtButton("＋ New group", primary = true) { newGroup = true } } }
                    if (chats.isEmpty()) item { CardBox { Text("No chats yet. Tap Chat on a friend in Friends.", fontSize = 12.5.sp, color = WtMuted) } }
                    items(chats, key = { it.id }) { c ->
                        val others = c.members.filterKeys { it != Social.me }
                        CardBox {
                            Row(Modifier.fillMaxWidth().clickable { chatOpen = c }, verticalAlignment = Alignment.CenterVertically) {
                                if (c.group) Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF2B3550)), Alignment.Center) { Icon(Icons.Default.Groups, null) }
                                else others.entries.firstOrNull()?.let { Initial(Person(it.key, it.value), 40) }
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(if (c.group) c.name else others.values.firstOrNull() ?: "Chat", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text(if (c.group) "${c.members.size} people · clears after 24 hours" else "Messages clear after ${keepLabel(c.ttlH)}", fontSize = 11.5.sp, color = WtMuted)
                                }
                            }
                        }
                    }
                }
                else -> {
                    item { Section("People who can see you", "You allowed them through an introduction. Take it back and they can't see, hear or chat with you any more.") }
                    if (seeMe.isEmpty()) item { CardBox { Text("Nobody besides your friends.", fontSize = 12.5.sp, color = WtMuted) } }
                    items(seeMe, key = { "s" + it.uid }) { p ->
                        CardBox {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Initial(p); Text(p.name, Modifier.weight(1f).padding(start = 10.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                WtButton("Take back", danger = true) { Social.revoke(p) }
                            }
                        }
                    }
                    item { Section("People you can see", "They allowed you. In a live room you can send them a friend request.") }
                    if (iCanSee.isEmpty()) item { CardBox { Text("Nobody yet.", fontSize = 12.5.sp, color = WtMuted) } }
                    items(iCanSee, key = { "c" + it.uid }) { p ->
                        CardBox {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Initial(p); Text(p.name, Modifier.weight(1f).padding(start = 10.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                if (!Social.isFriend(p.uid)) WtButton("Add friend") { Social.requestFriend(p) }
                            }
                        }
                    }
                }
            }
        }
    }
    itemOpen?.let { it0 -> SharedItemSheet(vm, it0, friends, onClose = { itemOpen = null }, onOpen = { m -> itemOpen = null; onOpen(m) },
        onJoin = { code -> itemOpen = null; JoinIntent.asked = true; JoinIntent.code.value = code }) }
    introduceTo?.let { to ->
        PeoplePicker("Introduce friends to ${to.name}", "Each person is asked first. They decide who can see them.", friends.filter { it.uid != to.uid }, "Ask them") { picked ->
            introduceTo = null; if (picked.isNotEmpty()) Social.introduce(to, picked)
        }
    }
    if (newGroup) GroupMaker(friends) { name, picked ->
        newGroup = false
        if (picked.isNotEmpty()) scope.launch { Social.group(name, picked)?.let { chatOpen = it } }
    }
    if (shareTitle) {
        val myList by vm.myList.collectAsStateWithLifecycle()
        ShareTitleFlow(vm, myList, friends) { shareTitle = false }
    }
    removeFriend?.let { f ->
        PremiumAlert(onDismissRequest = { removeFriend = null }, title = { Text("Remove ${f.name}?") },
            text = { Text("You stop being watch friends: no rings, plans or chats between you. You can become friends again with a new invite.", fontSize = 13.sp) },
            confirmButton = { androidx.compose.material3.TextButton({ Social.removeFriend(f); removeFriend = null }) { Text("Remove", color = Color(0xFFFF8A80)) } },
            dismissButton = { androidx.compose.material3.TextButton({ removeFriend = null }) { Text("Cancel") } })
    }
    if (joining) JoinRoomDialog(onJoined = { st -> joining = false; onPlayRoom(st) }, onDismiss = { joining = false })
}

@Composable
private fun rememberSaveable0(v: Int): MutableState<Int> = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(v) }

fun keepLabel(h: Int) = if (h <= 24) "24 hours" else "${h / 24} days"

/** Pick people from a list (with a title, a note and the button's words). Empty = cancelled. */
@Composable
fun PeoplePicker(title: String, note: String, people: List<Person>, action: String, onDone: (List<Person>) -> Unit) {
    val picked = remember { mutableStateListOf<String>() }
    PremiumAlert(onDismissRequest = { onDone(emptyList()) }, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            Text(note, fontSize = 12.5.sp, color = WtMuted)
            if (people.isEmpty()) Text("Nobody to pick yet.", Modifier.padding(top = 12.dp), fontSize = 13.sp)
            people.forEach { p ->
                val on = p.uid in picked
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).clickable { if (on) picked.remove(p.uid) else picked.add(p.uid) }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Initial(p, 32)
                    Text(p.name, Modifier.weight(1f).padding(start = 10.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (on) WtGreen else Color.Transparent).border(2.dp, if (on) WtGreen else Color(0xFF4A5060), RoundedCornerShape(6.dp)), Alignment.Center) {
                        if (on) Icon(Icons.Default.Check, null, Modifier.size(15.dp), tint = Color(0xFF06150D))
                    }
                }
            }
        }
    }, confirmButton = { androidx.compose.material3.TextButton({ onDone(people.filter { it.uid in picked }) }, enabled = picked.isNotEmpty()) { Text(action) } },
        dismissButton = { androidx.compose.material3.TextButton({ onDone(emptyList()) }) { Text("Cancel") } })
}

@Composable
private fun GroupMaker(friends: List<Person>, onDone: (String, List<Person>) -> Unit) {
    var name by remember { mutableStateOf("Movie night") }
    var step by remember { mutableIntStateOf(0) }
    if (step == 0) PremiumAlert(onDismissRequest = { onDone("", emptyList()) }, title = { Text("New group") }, text = {
        Column {
            Text("Groups are text only and clear after 24 hours.", fontSize = 12.5.sp, color = WtMuted)
            OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth().padding(top = 10.dp).tvOkKeyboard(top = 8.dp), singleLine = true, label = { Text("Group name") }, shape = RoundedCornerShape(12.dp), keyboardOptions = tvKeyboard())
        }
    }, confirmButton = { androidx.compose.material3.TextButton({ step = 1 }) { Text("Next") } }, dismissButton = { androidx.compose.material3.TextButton({ onDone("", emptyList()) }) { Text("Cancel") } })
    else PeoplePicker("Who's in $name?", "Pick from your watch friends.", friends, "Create group") { onDone(name, it) }
}

/** Share a title from your watchlist with some friends (then plan it from Shared lists). */
@Composable
private fun ShareTitleFlow(vm: AppViewModel, myList: List<Meta>, friends: List<Person>, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf<Meta?>(null) }
    val t = title
    if (t == null) PremiumAlert(onDismissRequest = onDone, title = { Text("Share which title?") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            if (myList.isEmpty()) Text("Your watchlist is empty. Open a title › ⋯ › Share with friends.", fontSize = 13.sp, color = WtMuted)
            myList.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).clickable { title = m }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(m.poster, null, Modifier.size(34.dp, 50.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                    Text(m.name, Modifier.padding(start = 10.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                }
            }
        }
    }, confirmButton = {}, dismissButton = { androidx.compose.material3.TextButton(onDone) { Text("Cancel") } })
    else ShareWithFriends(t, friends, onDone)
}

/** "Share with friends" for one title: pick who can see it (only them and you). */
@Composable
fun ShareWithFriends(m: Meta, friends: List<Person>, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    PeoplePicker("Share ${m.name}", "Only the friends you pick (and you) will see it in Shared lists.", friends, "Share") { picked ->
        if (picked.isEmpty()) { onDone(); return@PeoplePicker }
        scope.launch { val id = Social.share(m, picked); Snack.show(if (id != null) "Shared with ${picked.joinToString(", ") { it.name }}" else "Couldn't share. Check your connection."); onDone() }
    }
}

/** A shared title: who it's shared with and every plan for it (with whom, when), with Watch now, Reschedule, Ring and Join room. */
@Composable
fun SharedItemSheet(vm: AppViewModel, item: SharedItem, friends: List<Person>, onClose: () -> Unit, onOpen: (Meta) -> Unit, onJoin: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var planning by remember { mutableStateOf<WatchPlan?>(null) }
    var newPlan by remember { mutableStateOf(false) }
    PremiumAlert(onDismissRequest = onClose, title = { Text(item.meta.name) }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(item.meta.poster, null, Modifier.size(54.dp, 80.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Shared with " + item.members.filterKeys { it != Social.me }.values.joinToString(", ").ifBlank { "only you" }, fontSize = 12.5.sp, color = WtMuted)
                    Text("Open title ›", Modifier.padding(top = 6.dp).clickable { onOpen(item.meta) }, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF8A80))
                }
            }
            Text("Plans", Modifier.padding(top = 14.dp), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
            if (item.plans.isEmpty()) Text("No plan yet.", Modifier.padding(top = 4.dp), fontSize = 12.5.sp, color = WtMuted)
            item.plans.forEach { p ->
                val others = p.with.filterKeys { it != Social.me }.values
                Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1B1E26)).padding(10.dp)) {
                    Text(Social.planTime(p.at), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    Text(if (others.isEmpty()) "Only you" else "With " + others.joinToString(", "), fontSize = 12.sp, color = WtMuted)
                    if (p.room != null) Text("Room ${p.room} is open", fontSize = 11.5.sp, color = WtGreen, fontWeight = FontWeight.Bold)
                    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (p.room != null) WtButton("Join room", primary = true) { onJoin(p.room) }
                        WtButton("Watch now", primary = p.room == null) { Social.watchNow(item, p); pendingDetailPlay.value = item.meta.id; onOpen(item.meta) }
                        WtButton("Reschedule") { planning = p }
                        if (others.isNotEmpty()) WtButton("Ring") { Social.ring(item, p) }
                    }
                }
            }
        }
    }, confirmButton = { androidx.compose.material3.TextButton({ newPlan = true }) { Text("Plan a watch") } },
        dismissButton = { androidx.compose.material3.TextButton(onClose) { Text("Close") } })
    if (newPlan || planning != null) PlanFlow(item.meta, item, friends, planning?.at, onDone = { newPlan = false; planning = null }, existing = planning)
}

/**
 * Plan a watch: date and time, then Only me / With friends (pick them). The plan goes into everyone's calendar and
 * everyone gets the reminder at the same time.
 */
@Composable
fun PlanFlow(meta: Meta, item: SharedItem?, friends: List<Person>, startAt: Long?, onDone: () -> Unit, existing: WatchPlan? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var at by remember { mutableStateOf<Long?>(null) }
    var who by remember { mutableStateOf<String?>(if (existing != null) "keep" else null) }
    LaunchedEffect(Unit) {
        val c = java.util.Calendar.getInstance().apply { startAt?.let { timeInMillis = it } ?: run { add(java.util.Calendar.HOUR_OF_DAY, 2); set(java.util.Calendar.MINUTE, 0) } }
        android.app.DatePickerDialog(ctx, { _, y, mo, d ->
            android.app.TimePickerDialog(ctx, { _, h, mi ->
                at = java.util.Calendar.getInstance().apply { set(y, mo, d, h, mi, 0) }.timeInMillis
            }, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), false).apply { setOnCancelListener { onDone() } }.show()
        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = System.currentTimeMillis() - 60_000; setOnCancelListener { onDone() }
        }.show()
    }
    val time = at ?: return
    fun save(with: List<Person>) {
        scope.launch {
            val it0 = item ?: Social.share(meta, with)?.let { id -> Social.item(id) }
            if (it0 == null) { Snack.show("Couldn't save the plan. Check your connection and sign-in."); onDone(); return@launch }
            val ok = Social.plan(ctx, it0, time, with, existing?.id)
            Snack.show(if (ok) "Planned for ${Social.planTime(time)}" + (if (with.isNotEmpty()) " with ${with.joinToString(", ") { it.name }}" else "") else "Couldn't save the plan")
            onDone()
        }
    }
    when (who) {
        "keep" -> { LaunchedEffect(time) { save(existing?.with.orEmpty().filterKeys { it != Social.me }.map { Person(it.key, it.value) }) } }
        null -> PremiumAlert(onDismissRequest = onDone, title = { Text("Who's watching?") }, text = {
            Column {
                Text("${meta.name} · ${Social.planTime(time)}", fontSize = 13.sp, color = WtMuted)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WtButton("Only me") { who = "me"; save(emptyList()) }
                    WtButton("With friends", primary = true) { who = "friends" }
                }
            }
        }, confirmButton = {}, dismissButton = { androidx.compose.material3.TextButton(onDone) { Text("Cancel") } })
        "friends" -> PeoplePicker("Watch ${meta.name} with…", "They get it in their calendar, and everyone is reminded at the same time.", friends, "Save plan") { picked ->
            if (picked.isEmpty()) onDone() else save(picked)
        }
        else -> {}
    }
}

// ───────────── Chat ─────────────

/** A chat: messages (refreshed every few seconds), text box, view-once photos (one-to-one), and how long messages are kept. */
@Composable
fun ChatScreen(c0: ChatInfo, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var c by remember { mutableStateOf(c0) }
    var lines by remember { mutableStateOf<List<SocialLine>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<Pair<SocialLine, ByteArray>?>(null) }
    var keepMenu by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    BackHandler { onBack() }
    DisposableEffect(c.id) { ChatState.openId = c.id; onDispose { if (ChatState.openId == c.id) ChatState.openId = null } }
    LaunchedEffect(c.id) { while (true) { lines = Social.lines(c); delay(4_000) } }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.lastIndex) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        sending = true
        scope.launch {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { photoUpTo3Mb(ctx, uri) }
            val ok = bytes != null && Social.postPhoto(c, bytes)
            sending = false
            if (!ok) Snack.show("Couldn't send the photo") else lines = Social.lines(c)
        }
    }
    val others = c.members.filterKeys { it != Social.me }
    Column(Modifier.fillMaxSize().background(Bg).imePadding()) {
        Row(Modifier.statusBarsPadding().padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), Alignment.Center) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(if (c.group) c.name else others.values.firstOrNull() ?: "Chat", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Text(if (c.group) "${c.members.size} people · clears after 24 hours" else "Messages clear after ${keepLabel(c.ttlH)}", fontSize = 11.5.sp, color = WtMuted)
            }
            if (!c.group) Box {
                Icon(Icons.Default.Timer, "Keep messages for", Modifier.size(40.dp).clip(CircleShape).clickable { keepMenu = true }.padding(9.dp))
                androidx.compose.material3.DropdownMenu(keepMenu, { keepMenu = false }) {
                    listOf(24, 72, 120, 168).forEach { h ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Clear after ${keepLabel(h)}" + if (h == c.ttlH) "  ✓" else "") },
                            onClick = { keepMenu = false; scope.launch { Social.setKeep(c, h); c = c.copy(ttlH = h); lines = Social.lines(c) } })
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(lines, key = { it.id }) { l ->
                val mine = l.from == Social.me
                if (l.system) {
                    Text(l.text, Modifier.fillMaxWidth().padding(vertical = 4.dp), fontSize = 11.sp, color = WtMuted, textAlign = TextAlign.Center)
                    return@items
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                    if (!mine && c.group) Text(l.name, fontSize = 10.5.sp, color = Color(0xFFFF9AA1), fontWeight = FontWeight.Bold)
                    val shape = RoundedCornerShape(16.dp)
                    if (l.img) {
                        val label = when { mine && l.seen -> "📷 Photo · opened"; mine -> "📷 Photo · not opened yet"; l.seen -> "📷 Photo · opened"; else -> "📷 Tap to view once" }
                        Text(label, Modifier.clip(shape).background(if (mine) Color(0x55E50914) else Color(0xFF1F232C))
                            .clickable(enabled = !mine && !l.seen) { scope.launch { Social.photo(c, l)?.let { viewing = l to it } ?: Snack.show("This photo is no longer available") } }
                            .padding(horizontal = 12.dp, vertical = 9.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    } else Text(l.text, Modifier.widthIn(max = 280.dp).clip(shape).background(if (mine) WtRed else Color(0xFF1F232C)).padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 13.5.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!c.group) Box(Modifier.size(44.dp).clip(CircleShape).background(WtCard).clickable(enabled = !sending) { pick.launch("image/*") }, Alignment.Center) {
                if (sending) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Icon(Icons.Default.Image, "Send a view-once photo", Modifier.size(20.dp))
            }
            OutlinedTextField(text, { text = it.take(1000) }, Modifier.weight(1f).padding(horizontal = 8.dp).tvOkKeyboard(), placeholder = { Text("Message") },
                shape = RoundedCornerShape(22.dp), maxLines = 4, keyboardOptions = tvKeyboard())
            Box(Modifier.size(44.dp).clip(CircleShape).background(WtRed).clickable {
                val t = text; text = ""
                scope.launch { if (Social.post(c, t)) lines = Social.lines(c) }
            }, Alignment.Center) { Icon(Icons.AutoMirrored.Filled.Send, "Send", Modifier.size(20.dp)) }
        }
    }
    viewing?.let { (l, bytes) -> OnceViewer(bytes) { viewing = null; scope.launch { Social.photoSeen(c, l); lines = Social.lines(c) } } }
}

/** Full-screen view-once photo: screenshots and screen recording are blocked while it's open; deleted when closed. */
@Composable
private fun OnceViewer(bytes: ByteArray, onClose: () -> Unit) {
    val bmp = remember(bytes) { runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(Unit) {
            val w = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        }
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose), Alignment.Center) {
            if (bmp != null) Image(bmp, "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text("Couldn't open this photo", color = WtMuted)
            Text("View once · tap to close", Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000))
                .padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** The picked photo as it is when it's 3 MB or less; else re-saved (and if needed made smaller) until it fits 3 MB. */
fun photoUpTo3Mb(ctx: android.content.Context, uri: android.net.Uri): ByteArray? = runCatching {
    val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val max = 3 * 1024 * 1024
    if (raw.size <= max) return raw
    var bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
    var q = 92
    while (true) {
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, out)
        if (out.size() <= max) return out.toByteArray()
        if (q > 70) q -= 8 else bmp = android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * 0.85f).toInt(), (bmp.height * 0.85f).toInt(), true)
    }
    @Suppress("UNREACHABLE_CODE") null
}.getOrNull()
