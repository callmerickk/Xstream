package com.example.streamnest

import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// 0.9.8 (wt2): everything visual that is new in Watch together 2 lives here.

/** 0.9.8: built-in emoji (no library). Old-Android-safe ones only. */
val WT_EMOJI: List<Pair<String, List<String>>> = listOf(
    "😀" to "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 😉 😊 😇 🥰 😍 🤩 😘 😋 😛 😜 🤪 🤗 🤔 🤨 😐 🙄 😏 😮 😲 😳 🥺 😢 😭 😤 😡 🤯 😱 😴 😎 🥳 🤓 😬 🤐 😷".split(" "),
    "❤️" to "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 💕 💞 💓 💗 💖 💘 💝 😻 💋 ✨ 💫 ⭐ 🌟 🔥 💯".split(" "),
    "👋" to "👍 👎 👏 🙌 🙏 🤝 👌 ✌️ 🤞 🤟 🤘 🤙 👆 👇 👈 👉 ✋ 👋 💪 ✊ 👊 🤲".split(" "),
    "🎬" to "🎬 🍿 📺 🎥 🎞️ 🎧 🎵 🎶 🎮 🏆 🎉 🎊 🎁 🍕 🍔 🍟 🥤 🍺 ☕ 🍫 🍩 🌙 ☀️ 🌈 💡 📷 📱 💻 ⏰ 🚀 👻 💀 👀".split(" "),
)

/** 0.9.8: favourites (★), recents and the react button's place, kept on this phone. */
object WtEmoji {
    private var sp: android.content.SharedPreferences? = null
    val favs = MutableStateFlow(QuickEmoji)
    val recent = MutableStateFlow<List<String>>(emptyList())
    private fun split(s: String?) = s.orEmpty().split("|").filter { it.isNotEmpty() }
    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("emoji", Context.MODE_PRIVATE); sp = p
        if (p.contains("favs")) favs.value = split(p.getString("favs", null))
        recent.value = split(p.getString("recent", null))
    }
    /** Adds or removes a favourite; true = added. */
    fun toggleFav(e: String): Boolean {
        val on = e !in favs.value
        favs.value = if (on) favs.value + e else favs.value - e
        sp?.edit()?.putString("favs", favs.value.joinToString("|"))?.apply()
        return on
    }
    fun note(e: String) {
        recent.value = (listOf(e) + recent.value.filter { it != e }).take(24)
        sp?.edit()?.putString("recent", recent.value.joinToString("|"))?.apply()
    }
    fun pos(): Pair<Float, Float> = (sp?.getFloat("px", 0.03f) ?: 0.03f) to (sp?.getFloat("py", 0.55f) ?: 0.55f)
    fun setPos(p: Pair<Float, Float>) { sp?.edit()?.putFloat("px", p.first)?.putFloat("py", p.second)?.apply() }
}

private fun Modifier.wtPill(bg: Color = Color(0xB3000000)) = this.clip(RoundedCornerShape(22.dp)).background(bg).padding(horizontal = 14.dp, vertical = 7.dp)

/** One small emoji button, drag it anywhere (remembered). Tap = a row of your favourites; pick one = reaction. */
@Composable
fun WtMovableReact(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    remember { WtEmoji.init(ctx) }
    val favs by WtEmoji.favs.collectAsState()
    var open by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(WtEmoji.pos()) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val b = with(density) { 44.dp.toPx() }
        val rx = (w - b).coerceAtLeast(1f); val ry = (h - b).coerceAtLeast(1f)
        val bx = (pos.first * rx).roundToInt(); val by = (pos.second * ry).roundToInt()
        if (open && favs.isNotEmpty()) {
            val n = minOf(favs.size, 6)
            val pw = with(density) { (n * 40 + 12).dp.toPx() }.roundToInt()
            val bi = b.roundToInt()
            val px = (if (bx + bi / 2 < w / 2) bx + bi + 8 else bx - pw - 8).coerceIn(0, (w.roundToInt() - pw).coerceAtLeast(0))
            Row(Modifier.offset { IntOffset(px, by) }.width((n * 40 + 12).dp).clip(RoundedCornerShape(26.dp)).background(Color(0xCC14151B))
                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(26.dp)).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp)) {
                favs.forEach { e ->
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable { Together.react(e); WtEmoji.note(e); open = false }, Alignment.Center) { Text(e, fontSize = 22.sp) }
                }
            }
        }
        // Tap and drag are two pointerInputs on the same box: the drag takes over after the touch slop and consumes, so a drag never taps.
        Box(Modifier.offset { IntOffset(bx, by) }.size(44.dp).clip(CircleShape).background(Color(0xCC14151B)).border(1.dp, Color(0x26FFFFFF), CircleShape)
            .pointerInput(Unit) { detectTapGestures { open = !open } }
            .pointerInput(w, h) {
                detectDragGestures(onDragStart = { open = false }, onDragEnd = { WtEmoji.setPos(pos) }) { ch, d ->
                    ch.consume()
                    pos = (pos.first + d.x / rx).coerceIn(0f, 1f) to (pos.second + d.y / ry).coerceIn(0f, 1f)
                }
            }, Alignment.Center) { Text(if (open) "✕" else "🙂", fontSize = 22.sp) }
    }
}

/** 💬 at the top right of the player (while in a room) with the unread count. */
@Composable
fun WtChatButton(modifier: Modifier, onClick: () -> Unit) {
    val unread by Together.unread.collectAsState()
    Box(modifier.size(44.dp).clip(CircleShape).background(Color(0x99000000)).clickable { onClick() }, Alignment.Center) {
        Text("💬", fontSize = 20.sp)
        if (unread > 0) Text(if (unread > 9) "9+" else "$unread", Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 2.dp).clip(RoundedCornerShape(50))
            .background(Color(0xFFE50914)).padding(horizontal = 5.dp, vertical = 1.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun WtEmojiCell(e: String, fav: Boolean, onTap: () -> Unit, onLong: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).pointerInput(e) { detectTapGestures(onTap = { onTap() }, onLongPress = { onLong() }) }, Alignment.Center) {
        Text(e, fontSize = 22.sp)
        if (fav) Text("★", Modifier.align(Alignment.TopEnd).padding(end = 3.dp), fontSize = 9.sp, color = Color(0xFFF5B942))
    }
}

/** The floating glass chat: members with a speaker button each, messages, emoji (Recent / ★ / groups), input.
 *  Landscape: right side, 45 % wide. Portrait: bottom sheet. */
@Composable
fun BoxScope.WtChatPanel(onClose: () -> Unit) {
    val ctx = LocalContext.current
    remember { WtEmoji.init(ctx) }
    val cfg = LocalConfiguration.current
    val land = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
    val msgs by Together.chat.collectAsState()
    val mem by Together.members.collectAsState()
    val muted by Call.peerMuted.collectAsState()
    val favs by WtEmoji.favs.collectAsState()
    val recent by WtEmoji.recent.collectAsState()
    var text by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(2) }
    BackHandler { onClose() }
    DisposableEffect(Unit) { Together.chatOpen = true; Together.unread.value = 0; onDispose { Together.chatOpen = false } }
    val shape = if (land) RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp) else RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    val grey = Color(0xFF9AA3B5)
    Column(
        Modifier.align(if (land) Alignment.CenterEnd else Alignment.BottomCenter)
            .then(if (land) Modifier.fillMaxHeight().fillMaxWidth(0.45f) else Modifier.fillMaxWidth().fillMaxHeight(0.7f))
            .clip(shape).background(Color(0xEB101116)).border(1.dp, Color(0x26FFFFFF), shape)
            .pointerInput(Unit) { detectTapGestures { } }   // taps on the panel don't reach the video
            .navigationBarsPadding().imePadding().padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Chat", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("✋ Raise hand", Modifier.clip(RoundedCornerShape(50)).background(Color(0x26FFFFFF)).clickable { Together.raiseHand() }.padding(horizontal = 12.dp, vertical = 6.dp),
                fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("✕", Modifier.padding(start = 8.dp).clip(CircleShape).clickable { onClose() }.padding(8.dp), fontSize = 16.sp)
        }
        // Members: a speaker button per friend (only for you: their voice off / on).
        val now = Together.serverNow()
        val here = mem.filter { (uid, m) -> now - m.seen < 40_000 && Social.visible(uid) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            here.forEach { (uid, m) ->
                val mine = uid == Together.me
                Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0x14FFFFFF)).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (mine) "You" else m.name.ifBlank { "Guest" }, fontSize = 12.sp, maxLines = 1)
                    if (!mine) {
                        val mu = uid in muted
                        Text(if (mu) "🔇" else "🔊", Modifier.clip(CircleShape).clickable { Call.setPeerMuted(uid, !mu) }.padding(horizontal = 8.dp, vertical = 6.dp), fontSize = 14.sp)
                    } else Spacer(Modifier.width(8.dp))
                }
            }
        }
        // Messages (only from people you and they approved, as before).
        val sc = rememberScrollState()
        LaunchedEffect(msgs.size) { delay(60); sc.animateScrollTo(sc.maxValue) }
        Column(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x0FFFFFFF)).verticalScroll(sc).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (msgs.isEmpty()) Text("No messages yet. Say hi 👋", fontSize = 12.5.sp, color = grey)
            msgs.filter { Social.approved(it.by) }.forEach { m ->
                val mine = m.by == Together.me
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                    if (!mine) Text(m.name.ifBlank { "Friend" }, fontSize = 11.sp, color = Color(0xFFFF9AA1), fontWeight = FontWeight.Bold)
                    Text(m.text, Modifier.clip(RoundedCornerShape(12.dp)).background(if (mine) Color(0x33E50914) else Color(0x14FFFFFF)).padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp)
                }
            }
        }
        if (picker) {
            val tabs = listOf("🕘", "★") + WT_EMOJI.map { it.first }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                tabs.forEachIndexed { i, ic ->
                    Text(ic, Modifier.clip(CircleShape).background(if (tab == i) Color(0x33FFFFFF) else Color.Transparent).clickable { tab = i }.padding(8.dp), fontSize = 18.sp)
                }
            }
            val items = when (tab) { 0 -> recent; 1 -> favs; else -> WT_EMOJI[tab - 2].second }
            val panelW = if (land) cfg.screenWidthDp * 0.45f else cfg.screenWidthDp.toFloat()
            val cols = ((panelW - 24f) / 40f).toInt().coerceIn(5, 9)
            Column(Modifier.fillMaxWidth().height(150.dp).verticalScroll(rememberScrollState())) {
                if (items.isEmpty()) Text(if (tab == 1) "Long-press any emoji to add it here ★" else "Nothing yet", Modifier.padding(8.dp), fontSize = 12.sp, color = grey)
                items.chunked(cols).forEach { r ->
                    Row { r.forEach { e ->
                        WtEmojiCell(e, fav = tab != 1 && e in favs, onTap = { text = (text + e).take(500); WtEmoji.note(e) },
                            onLong = { Snack.show(if (WtEmoji.toggleFav(e)) "$e added to ★ Favourites" else "$e removed from ★ Favourites") })
                    } }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (picker) "⌨" else "😀", Modifier.clip(CircleShape).clickable { picker = !picker }.padding(8.dp), fontSize = 20.sp)
            OutlinedTextField(text, { text = it.take(500) }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Message") }, shape = RoundedCornerShape(14.dp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { Together.say(text); text = "" }))
            Text("Send", Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(Color(0xFFE50914)).clickable { Together.say(text); text = "" }
                .padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

/** Top centre: "Riya is loading…" then "In sync ✓" (2 s), "Riya joined" (2 s), "✋ Riya wants to pause" + Pause (6 s). */
@Composable
fun BoxScope.WtRoomNotices(onPause: () -> Unit) {
    val waiting by Together.waitingFor.collectAsState()
    val hand by Together.handFrom.collectAsState()
    val mem by Together.members.collectAsState()
    var joinedLine by remember { mutableStateOf<String?>(null) }
    var syncOk by remember { mutableStateOf(false) }
    var hadWait by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Together.joined.collectLatest { n -> joinedLine = "$n joined"; delay(2000); joinedLine = null } }
    LaunchedEffect(waiting) {
        if (waiting != null) { hadWait = true; syncOk = false }
        else if (hadWait) { hadWait = false; syncOk = true; delay(2000); syncOk = false }
    }
    Column(Modifier.align(Alignment.TopCenter).padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val w = waiting
        val line = if (w != null) (if ("(reconnecting)" in w) "$w…" else "$w is loading…") else if (syncOk) "In sync ✓" else null
        line?.let { Text(it, Modifier.wtPill(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (w == null) Color(0xFF3CCF7A) else Color.White) }
        joinedLine?.let { Text(it, Modifier.wtPill(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        hand?.let { (uid, _) ->
            val mine = uid == Together.me
            val who = if (mine) "You" else if (Social.visible(uid)) mem[uid]?.name?.ifBlank { null } ?: "A friend" else "Someone"
            Row(Modifier.wtPill(Color(0xE6111216)).border(1.dp, Color(0x80F5B942), RoundedCornerShape(22.dp)), verticalAlignment = Alignment.CenterVertically) {
                Text("✋ $who ${if (mine) "want" else "wants"} to pause", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (!mine) Text("Pause", Modifier.padding(start = 10.dp).clip(RoundedCornerShape(50)).background(Color(0xFFE50914))
                    .clickable { onPause(); Together.handFrom.value = null }.padding(horizontal = 12.dp, vertical = 5.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

/** Room panel: open the chat panel (with unread count) and the "Show messages on the video" switch. */
@Composable
fun WtChatEntry() {
    val ctx = LocalContext.current
    val unread by Together.unread.collectAsState()
    val sp = remember { ctx.getSharedPreferences("call", Context.MODE_PRIVATE) }
    var pop by remember { mutableStateOf(sp.getBoolean("chatPop", true)) }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(glassy(Color(0xFF191A20))).clickable { Together.chatShown.value = true }
        .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("💬  Open chat", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (unread > 0) Text("$unread", Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFE50914)).padding(horizontal = 7.dp, vertical = 2.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Show messages on the video", Modifier.weight(1f), fontSize = 12.5.sp, color = Color(0xFF9AA3B5))
        Switch(pop, { pop = it; sp.edit().putBoolean("chatPop", it).apply() }, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFFE50914), checkedThumbColor = Color.White))
    }
}

// ───────── Title page ─────────

/** Title page: returns the click action for "Watch together". Tapping creates the room for this title, opens the share
 *  sheet at once and shows a small dialog with Share and 🔔 Ring a friend. Tapping again with a room open shows it again. */
@Composable
fun rememberWatchTogether(key: String, title: String, image: String?, type: String): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var room by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var ringOpen by remember { mutableStateOf(false) }
    remember { Buddies.init(ctx) }
    val buddies by Buddies.list.collectAsState()
    room?.let { c ->
        PremiumAlert(
            onDismissRequest = { room = null; ringOpen = false },
            title = { Text("Watch together") },
            text = {
                Column {
                    Text("Room $c · it closes by itself if nobody joins in 15 minutes. Pick a stream when your friends are in.", fontSize = 13.sp, color = Color(0xFF9AA3B5))
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("📤 Share invite" to { Invites.shareNow(ctx, c, title) }, "🔔 Ring a friend" to { ringOpen = !ringOpen }).forEach { (t, act) ->
                            Text(t, Modifier.clip(RoundedCornerShape(50)).background(Color(0x1FFFFFFF)).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(50))
                                .clickable { act() }.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (ringOpen) {
                        if (buddies.isEmpty()) Text("Friends you watch with show up here after they join a room.", Modifier.padding(top = 10.dp), fontSize = 12.sp, color = Color(0xFF9AA3B5))
                        buddies.take(8).forEach { b ->
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x14FFFFFF)).clickable {
                                scope.launch { Snack.show(Invites.ring(b, c, title) ?: "Rang ${b.name} 🔔") }
                            }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(b.name, Modifier.weight(1f), fontSize = 14.sp)
                                Text("🔔", fontSize = 16.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ room = null; ringOpen = false }) { Text("Done") } },
            dismissButton = { TextButton({ Together.endRoom(); room = null; ringOpen = false }) { Text("Close room", color = Color(0xFFFF8A80)) } },
        )
    }
    return {
        if (!busy) {
            val open = Together.code.value
            if (open != null) room = open
            else {
                busy = true
                scope.launch {
                    val err = Together.create(RoomState(key = key, title = title, image = image, type = type), fromTitle = true)
                    busy = false
                    val c = Together.code.value
                    if (err != null || c == null) Snack.show(err ?: "Couldn't create the room, try again.")
                    else { room = c; Invites.shareNow(ctx, c, title) }
                }
            }
        }
    }
}

/** A plain icon + label for the title page's action row. Swap for the page's own action-button composable if it has one. */
@Composable
fun WatchTogetherAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0x1FFFFFFF)).border(1.dp, Color(0x26FFFFFF), CircleShape), Alignment.Center) {
            Icon(Icons.Default.Groups, "Watch together", Modifier.size(24.dp), tint = Color.White)
        }
        Text("Watch together", Modifier.padding(top = 4.dp), fontSize = 11.sp, maxLines = 1)
    }
}
