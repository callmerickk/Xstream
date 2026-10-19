package com.example.streamnest

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.SentimentVerySatisfied
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What everyone in a watch party should be seeing. [rev] grows with every change; [by] = who changed it;
 *  [sat] = the cloud server's time when it was written (one clock for every phone). */
@Serializable
data class RoomState(
    val rev: Long = 0, val by: String = "", val url: String = "", val key: String = "", val title: String = "",
    val image: String? = null, val type: String = "", val pos: Long = 0, val playing: Boolean = false, val host: String = "",
    val sat: Long = 0,
    @Transient val receivedAt: Long = 0,
)

/** Someone in the room: [seen] in server time; [mic]/[cam] on; [rtc] = their Xstream can do voice/video. */
/** [by]: who brought this person into the room (their inviter), used to show "+N" on that person for the others. */
@Serializable data class RoomMember(val name: String = "", val seen: Long = 0, val mic: Boolean = false, val cam: Boolean = false, val rtc: Boolean = false, val by: String = "")
@Serializable data class RoomReact(val k: String = "", val by: String = "", val at: Long = 0)
@Serializable data class ChatMsg(val by: String = "", val name: String = "", val text: String = "", val at: Long = 0)

/**
 * Watch together: a room (4-letter code) in the Xstream cloud, followed live (a streaming connection, ~0.2 s).
 * Play, pause and seeking by anyone happen for everyone; the host's player is the clock (its position goes out every
 * 2 s, stamped with the server's time). Pause stops everyone on the same frame; small gaps are closed by playing a
 * little faster / slower; someone buffering makes everyone wait. Voice / video signalling (Call) goes through the room.
 */
object Together {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = "application/json".toMediaType()
    private var poll: Job? = null
    private var ticker: Job? = null
    private var beat: Job? = null
    private val seenReacts = HashSet<String>()
    private val seenSig = HashSet<String>()
    private var joinedAt = 0L
    private var tree: JsonElement = JsonNull
    private val sse by lazy { Api.http.newBuilder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build() }

    val code = MutableStateFlow<String?>(null)
    val remote = MutableStateFlow<RoomState?>(null)
    val members = MutableStateFlow<Map<String, RoomMember>>(emptyMap())
    val reacts = MutableSharedFlow<RoomReact>(extraBufferCapacity = 16)
    /** Who is buffering right now (uid → server time it started). */
    val buf = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Someone else has been buffering for over 1.5 s: everyone waits (their name). */
    val waitingFor = MutableStateFlow<String?>(null)
    /** Live link to the room is up. */
    val live = MutableStateFlow(false)
    /** Chat in the room (oldest first), new messages from others, and how many are unread. */
    val chat = MutableStateFlow<List<ChatMsg>>(emptyList())
    val chatIn = MutableSharedFlow<ChatMsg>(extraBufferCapacity = 16)
    val unread = MutableStateFlow(0)
    @Volatile var chatOpen = false
    private val seenChat = HashSet<String>()
    /** The room ended (who ended it / why): the player closes. */
    val ended = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Voice / video signalling for this phone: (from, message). */
    val sig = MutableSharedFlow<Pair<String, JsonObject>>(extraBufferCapacity = 64)
    @Volatile var isHost = false
    /** True while the player applies a state that came from the room (so it isn't sent back). */
    @Volatile var applying = false
    @Volatile var myName = ""
    /** The open player: (position, playing, url, key, title, image, type). */
    @Volatile var provider: (() -> RoomState)? = null
    /** Server time − this phone's time (learned from our own writes). */
    @Volatile var offset = 0L
    private var bestRtt = Long.MAX_VALUE
    private var bestAt = 0L

    val me: String get() = Cloud.user.value?.uid ?: ""
    fun active() = code.value != null
    fun serverNow() = System.currentTimeMillis() + offset

    /** Where the room's video is right now. */
    fun expected(st: RoomState): Long =
        if (!st.playing) st.pos
        else if (st.sat > 0) st.pos + (serverNow() - st.sat).coerceIn(0, 120_000)
        else st.pos + (System.currentTimeMillis() - st.receivedAt + 300)

    private fun base(c: String) = "${CloudConfig.DB_URL}/rooms/$c"

    private suspend fun call(method: String, path: String, body: String? = null): String? = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext null
        runCatching {
            val b = Request.Builder().url("$path.json?auth=$t")
            when (method) {
                "PUT" -> b.put(body!!.toRequestBody(json))
                "POST" -> b.post(body!!.toRequestBody(json))
                "PATCH" -> b.patch(body!!.toRequestBody(json))
                "DELETE" -> b.delete()
                else -> b.get()
            }
            Api.http.newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull()
    }

    /** A write stamped with the server's time: also teaches this phone the server clock. Returns that time. */
    private suspend fun stamped(path: String, obj: JsonObject, field: String): Long? {
        val body = JsonObject(obj + (field to buildJsonObject { put(".sv", "timestamp") }))
        val t0 = System.currentTimeMillis()
        val r = call("PUT", path, body.toString()) ?: return null
        val t1 = System.currentTimeMillis()
        val sat = runCatching { Api.json.parseToJsonElement(r).jsonObject[field]?.jsonPrimitive?.longOrNull }.getOrNull() ?: return null
        val rtt = t1 - t0
        // Keep the sample from the fastest round trip (the most exact one); refresh it every 2 minutes.
        if (rtt <= bestRtt * 3 / 2 || t1 - bestAt > 120_000) { offset = sat - (t0 + t1) / 2; bestRtt = minOf(bestRtt, rtt); bestAt = t1 }
        return sat
    }

    /** Who invited this phone into the current room (the host when it was a plain code). */
    @Volatile var invitedBy = ""
    private fun memberJson(): JsonObject = Api.json.encodeToJsonElement(RoomMember.serializer(),
        RoomMember(myName.ifBlank { "Guest" }, 0, Call.micOn.value, Call.camOn.value, rtc = true, by = invitedBy)).jsonObject

    /** Tell the room this phone is here (and its mic / camera). */
    fun announce() { val c = code.value ?: return; io.launch { stamped("${base(c)}/members/$me", memberJson(), "seen") } }

    /** Starts a room with what is playing now. Null = done, else why not. */
    suspend fun create(state: RoomState): String? {
        if (Cloud.user.value == null) return "Sign in first (Settings › Account & Cloud Sync)."
        if (!state.url.startsWith("http")) return "Downloaded videos can't be shared — play it from a source."
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        repeat(5) {
            val c = (1..4).map { alphabet.random() }.joinToString("")
            if (call("GET", "${base(c)}/state") == "null") {
                val st = state.copy(rev = System.currentTimeMillis(), by = me, host = me)
                stamped("${base(c)}/state", Api.json.encodeToJsonElement(RoomState.serializer(), st).jsonObject, "sat")
                    ?: return "Couldn't create the room. Check the Firebase rules (rooms) and your connection."
                isHost = true
                start(c)
                return null
            }
        }
        return "Couldn't create a room, try again."
    }

    /** Joins a room. Returns its state (to open its video), or an error message. */
    suspend fun join(c0: String): Pair<RoomState?, String?> {
        if (Cloud.user.value == null) return null to "Sign in first (Settings › Account & Cloud Sync)."
        val c = c0.trim().uppercase()
        if (c.length != 4) return null to "Room codes have 4 letters."
        val body = call("GET", "${base(c)}/state") ?: return null to "Couldn't reach the room."
        if (body == "null") return null to "No room with code $c."
        val st = runCatching { Api.json.decodeFromString<RoomState>(body) }.getOrNull() ?: return null to "That room looks broken."
        isHost = st.host == me
        // You and whoever invited you (the link / ring sender, else the room's host) become watch friends.
        invitedBy = JoinIntent.by.takeIf { it.isNotBlank() && it != me } ?: st.host.takeIf { it != me }.orEmpty()
        JoinIntent.by = ""
        if (invitedBy.isNotBlank()) Social.befriend(invitedBy, JoinIntent.from.value.takeIf { st.host == invitedBy || JoinIntent.from.value.isNotBlank() }.orEmpty())
        // Learn the server clock before the video opens (so it starts at the right second).
        stamped("${base(c)}/members/$me", memberJson(), "seen")
        start(c)
        return st.copy(receivedAt = System.currentTimeMillis()) to null
    }

    // ── The room, followed live: a local copy of rooms/<code>, updated by the stream's put / patch events ──

    private fun putAt(root: JsonElement, segs: List<String>, v: JsonElement): JsonElement {
        if (segs.isEmpty()) return v
        val o = ((root as? JsonObject) ?: JsonObject(emptyMap())).toMutableMap()
        val child = putAt(o[segs[0]] ?: JsonNull, segs.drop(1), v)
        if (child is JsonNull) o.remove(segs[0]) else o[segs[0]] = child
        return JsonObject(o)
    }

    private suspend fun onTree(c: String) {
        val o = tree as? JsonObject
        if (o == null || o["end"] != null) {
            val who = o?.get("end")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            val msg = if (who.isNullOrBlank()) "The watch party ended" else "$who ended the watch party"
            withContext(Dispatchers.Main) { Snack.show(msg) }
            if (!isHost) ended.tryEmit(msg)
            leave(); return
        }
        runCatching {
            o["state"]?.let { e ->
                val st = Api.json.decodeFromJsonElement(RoomState.serializer(), e)
                if (st.rev > (remote.value?.rev ?: 0)) remote.value = st.copy(receivedAt = System.currentTimeMillis())
            }
        }
        val m = (o["members"] as? JsonObject)?.mapValues { (_, v) -> runCatching { Api.json.decodeFromJsonElement(RoomMember.serializer(), v) }.getOrDefault(RoomMember()) } ?: emptyMap()
        if (m != members.value) {
            members.value = m
            // People who came in through you become your watch friends (both ways).
            m.forEach { (uid, mm) -> if (uid != me && mm.by == me) Social.befriend(uid, mm.name) }
            // Voice / video only with people who approved each other.
            Call.onMembers(m.filterKeys { Social.approved(it) })
        }
        (o["reacts"] as? JsonObject)?.forEach { (k, v) ->
            if (seenReacts.add(k)) {
                val r = runCatching { Api.json.decodeFromJsonElement(RoomReact.serializer(), v) }.getOrNull()
                if (r != null && r.by != me && r.at >= joinedAt - 2000) reacts.tryEmit(r)
            }
        }
        (o["chat"] as? JsonObject)?.let { c ->
            val all = c.entries.mapNotNull { (k, v) -> runCatching { k to Api.json.decodeFromJsonElement(ChatMsg.serializer(), v) }.getOrNull() }.sortedBy { it.second.at }
            chat.value = all.map { it.second }.takeLast(200)
            all.forEach { (k, m) -> if (seenChat.add(k) && m.by != me && m.at >= joinedAt - 60_000 && Social.approved(m.by)) { chatIn.tryEmit(m); if (!chatOpen) unread.value = unread.value + 1 } }
        }
        buf.value = (o["buf"] as? JsonObject)?.mapNotNull { (k, v) -> v.jsonPrimitive.longOrNull?.let { k to it } }?.toMap() ?: emptyMap()
        ((o["sig"] as? JsonObject)?.get(me) as? JsonObject)?.forEach { (k, v) ->
            if (seenSig.add(k)) {
                val msg = v as? JsonObject ?: return@forEach
                val from = msg["from"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                sig.tryEmit(from to msg)
                io.launch { call("DELETE", "${base(c)}/sig/$me/$k") }
            }
        }
    }

    private suspend fun follow(c: String) {
        var backoff = 1000L
        while (code.value == c) {
            val t = Cloud.token()
            if (t == null) { delay(backoff); backoff = (backoff * 2).coerceAtMost(15_000); continue }
            val ok = runCatching {
                sse.newCall(Request.Builder().url("${base(c)}.json?auth=$t").header("Accept", "text/event-stream").build()).execute().use { r ->
                    if (!r.isSuccessful) return@use false
                    val src = r.body?.source() ?: return@use false
                    live.value = true
                    var event = ""
                    while (code.value == c) {
                        val line = src.readUtf8Line() ?: break
                        if (line.startsWith("event:")) event = line.substringAfter(':').trim()
                        else if (line.startsWith("data:") && (event == "put" || event == "patch")) {
                            val d = runCatching { Api.json.parseToJsonElement(line.substringAfter(':').trim()).jsonObject }.getOrNull() ?: continue
                            val segs = (d["path"]?.jsonPrimitive?.contentOrNull ?: "/").split("/").filter { it.isNotEmpty() }
                            val data = d["data"] ?: JsonNull
                            tree = if (event == "patch" && data is JsonObject) data.entries.fold(tree) { acc, (k, v) -> putAt(acc, segs + k.split("/").filter { it.isNotEmpty() }, v) }
                                else putAt(tree, segs, data)
                            onTree(c)
                            backoff = 1000L
                        } else if (event == "cancel" || event == "auth_revoked") break
                    }
                    true
                }
            }.getOrDefault(false)
            live.value = false
            if (code.value != c) break
            // Couldn't stream (old rules / network): one plain read so nothing is missed, then try again.
            if (!ok) call("GET", base(c))?.let { b -> tree = runCatching { Api.json.parseToJsonElement(b) }.getOrDefault(JsonNull); onTree(c) }
            delay(backoff); backoff = (backoff * 2).coerceAtMost(15_000)
        }
    }

    private fun start(c: String) {
        leaveQuietly()
        code.value = c
        joinedAt = System.currentTimeMillis()
        seenReacts.clear(); seenSig.clear(); seenChat.clear(); tree = JsonNull; unread.value = 0
        poll = io.launch { follow(c) }
        beat = io.launch {
            while (isActive) {
                stamped("${base(c)}/members/$me", memberJson(), "seen")
                // Someone else buffering for over 1.5 s (and still here): everyone waits for them.
                repeat(20) {
                    val now = serverNow()
                    val here = members.value
                    val w = buf.value.entries.firstOrNull { (uid, at) -> uid != me && now - at in 1_500..60_000 && here[uid]?.let { now - it.seen < 40_000 } == true }
                    waitingFor.value = w?.let { here[it.key]?.name?.ifBlank { "a friend" } }
                    // The room's creator went silent for 45 s (phone off, app closed, no internet): the room is over.
                    val host = remote.value?.host
                    if (!isHost && host != null && joinedAt > 0 && System.currentTimeMillis() - joinedAt > 50_000) {
                        val hm = here[host]
                        if (hm == null || now - hm.seen > 45_000) {
                            val n = hm?.name?.ifBlank { null } ?: "The host"
                            withContext(Dispatchers.Main) { Snack.show("$n left — the watch party ended") }
                            ended.tryEmit("$n left"); leave(); return@launch
                        }
                    }
                    delay(500)
                }
            }
        }
        // The host's player is the clock: its position goes out every 2 s while it plays.
        ticker = io.launch {
            while (isActive) {
                delay(2000)
                if (!isHost) continue
                // The player may only be read on the main thread (reading it from here crashed the app).
                val p = withContext(Dispatchers.Main) { runCatching { provider?.invoke() }.getOrNull() } ?: continue
                if (!p.playing || waitingFor.value != null) continue
                // Someone else just changed something: let it settle first.
                val r = remote.value
                if (r != null && r.by != me && System.currentTimeMillis() - r.receivedAt < 2500) continue
                send(p)
            }
        }
        Call.onRoom(c)
    }

    /** This player changed something (play / pause / seek / new video): tell the room. */
    fun local(p: RoomState) {
        if (applying || !active()) return
        io.launch { send(p) }
    }

    private suspend fun send(p: RoomState) {
        val c = code.value ?: return
        if (!p.url.startsWith("http")) return
        val st = p.copy(rev = maxOf(System.currentTimeMillis(), (remote.value?.rev ?: 0) + 1), by = me, host = remote.value?.host?.ifBlank { null } ?: me, sat = serverNow())
        remote.value = st.copy(receivedAt = System.currentTimeMillis())
        stamped("${base(c)}/state", Api.json.encodeToJsonElement(RoomState.serializer(), st).jsonObject, "sat")
    }

    private var bufNow = false
    /** This player is buffering (or not): the others wait for it after 1.5 s. */
    fun setBuffering(b: Boolean) {
        val c = code.value ?: return
        if (b == bufNow) return
        bufNow = b
        io.launch {
            if (b) call("PUT", "${base(c)}/buf/$me", buildJsonObject { put(".sv", "timestamp") }.toString())
            else {
                call("DELETE", "${base(c)}/buf/$me")
                // The host was the one everyone waited for: share where it is right away.
                if (isHost) withContext(Dispatchers.Main) { runCatching { provider?.invoke() }.getOrNull() }?.takeIf { it.playing }?.let { send(it) }
            }
        }
    }

    /** Voice / video signalling to one person in the room. */
    fun signal(to: String, msg: JsonObject) {
        val c = code.value ?: return
        io.launch { call("POST", "${base(c)}/sig/$to", JsonObject(msg + ("from" to JsonPrimitive(me))).toString()) }
    }

    fun say(text: String) {
        val c = code.value ?: return
        val t = text.trim().take(500)
        if (t.isEmpty()) return
        val m = ChatMsg(me, myName.ifBlank { "Guest" }, t, serverNow())
        chat.value = chat.value + m
        io.launch { call("POST", "${base(c)}/chat", Api.json.encodeToString(m)) }
    }

    /** The player closed: if this phone made the room and no video opens again in a few seconds, the room ends. */
    fun playerGone() {
        if (!isHost || !active()) return
        io.launch { delay(4000); if (provider == null && isHost && active()) endRoom() }
    }

    /** The creator ends the room: everyone sees who ended it, and their video closes. */
    fun endRoom() {
        val c = code.value ?: return
        io.launch { call("PUT", "${base(c)}/end", Api.json.encodeToString(myName.ifBlank { "The host" })); delay(1500); call("DELETE", base(c)) }
        leaveQuietly(); Call.stop()
        code.value = null; remote.value = null; members.value = emptyMap(); buf.value = emptyMap(); isHost = false; chat.value = emptyList(); unread.value = 0
    }

    fun react(kind: String) {
        val c = code.value ?: return
        val r = RoomReact(kind, me, System.currentTimeMillis())
        reacts.tryEmit(r)
        io.launch { call("POST", "${base(c)}/reacts", Api.json.encodeToString(r)) }
    }

    private fun leaveQuietly() { poll?.cancel(); ticker?.cancel(); beat?.cancel(); poll = null; ticker = null; beat = null; live.value = false; waitingFor.value = null; bufNow = false }

    fun leave() {
        val c = code.value
        val host = isHost
        leaveQuietly()
        Call.stop()
        code.value = null; remote.value = null; members.value = emptyMap(); buf.value = emptyMap(); isHost = false; chat.value = emptyList(); unread.value = 0
        if (c != null) io.launch { if (host) { call("PUT", "${base(c)}/end", Api.json.encodeToString(myName.ifBlank { "The host" })); delay(1500); call("DELETE", base(c)) } else { call("DELETE", "${base(c)}/members/$me"); call("DELETE", "${base(c)}/buf/$me") } }
    }
}

private val ReactIcons: Map<String, Pair<ImageVector, Color>> = mapOf(
    "love" to (Icons.Default.Favorite to Color(0xFFFF5A7A)),
    "star" to (Icons.Default.Star to Color(0xFFF5B942)),
    "laugh" to (Icons.Default.SentimentVerySatisfied to Color(0xFF7DE2B8)),
    "fire" to (Icons.Default.LocalFireDepartment to Color(0xFFFF8A3D)),
)

/** Reactions floating up the right side of the player. */
@Composable
fun ReactionsOverlay(modifier: Modifier = Modifier) {
    val live = remember { mutableStateListOf<Pair<Long, RoomReact>>() }
    LaunchedEffect(Unit) {
        var n = 0L
        Together.reacts.collect { r -> val id = n++; live.add(id to r); launch { delay(2200); live.removeAll { it.first == id } } }
    }
    Box(modifier) {
        live.forEach { (id, r) ->
            key(id) {
                val y = remember { Animatable(0f) }
                val x = remember { (id * 37 % 60).toFloat() }
                LaunchedEffect(Unit) { y.animateTo(1f, tween(2000)) }
                val (icon, tint) = ReactIcons[r.k] ?: (Icons.Default.Favorite to Color.White)
                Icon(icon, null, Modifier.align(Alignment.BottomEnd).padding(end = (24 + x).dp, bottom = 40.dp).size(34.dp)
                    .graphicsLayer { translationY = -y.value * 260.dp.toPx(); alpha = 1f - y.value * y.value; val s = 0.7f + 0.5f * minOf(1f, y.value * 4); scaleX = s; scaleY = s },
                    tint = tint)
            }
        }
    }
}

/** Player panel: start / join a room, the code, who's watching, reactions, leave. */
@Composable
fun TogetherPanel(current: () -> RoomState, onJoined: (RoomState) -> Unit) {
    val scope = rememberCoroutineScope()
    val code by Together.code.collectAsState()
    val mem by Together.members.collectAsState()
    var input by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
        val c = code
        if (c == null) {
            Text("Watch with friends, in sync: pause, seek or skip — it happens for everyone.", fontSize = 13.sp, color = Color(0xFF9AA3B5))
            TextButton({
                busy = true
                scope.launch { msg = Together.create(current()); busy = false; msg?.let { Snack.show(it) } }
            }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text("Start a room with this video") }
            OutlinedTextField(input, { input = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(4) }, Modifier.fillMaxWidth().padding(top = 8.dp).tvOkKeyboard(top = 8.dp),
                singleLine = true, label = { Text("Room code") }, shape = RoundedCornerShape(12.dp), keyboardOptions = tvKeyboard())
            TextButton({
                busy = true
                scope.launch {
                    val (st, err) = Together.join(input)
                    busy = false
                    msg = err
                    if (st != null) onJoined(st)
                }
            }, enabled = !busy && input.length == 4) { Text("Join") }
        } else {
            TogetherRoomControls(c, mem)
        }
        TogetherInvite(code, current().title.ifBlank { "a video" }) {
            busy = true
            val err = Together.create(current())
            busy = false
            if (err != null) { msg = err; null } else Together.code.value
        }
        msg?.let { Text(it, Modifier.padding(top = 6.dp), fontSize = 13.sp, color = Color(0xFFFFB4A8)) }
        if (c != null) TextButton({ Together.leave() }, Modifier.padding(top = 6.dp)) { Text(if (Together.isHost) "End the room" else "Leave the room", color = Color(0xFFFF8A80)) }
    }
}

/** New chat messages pop up briefly on the video (Watch together › Chat › "Show messages on the video"). */
@Composable
fun ChatToasts(modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val live = remember { mutableStateListOf<Pair<Long, ChatMsg>>() }
    LaunchedEffect(Unit) {
        var n = 0L
        Together.chatIn.collect { m ->
            if (!ctx.getSharedPreferences("call", android.content.Context.MODE_PRIVATE).getBoolean("chatPop", true) || Together.chatOpen) return@collect
            val id = n++; live.add(id to m); if (live.size > 3) live.removeAt(0)
            launch { delay(5000); live.removeAll { it.first == id } }
        }
    }
    Column(modifier.widthIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
        live.forEach { (id, m) ->
            key(id) {
                val a = remember { Animatable(0f) }
                LaunchedEffect(Unit) { a.animateTo(1f, tween(220)) }
                Row(Modifier.graphicsLayer { alpha = a.value; translationX = (1f - a.value) * 40.dp.toPx() }
                    .clip(RoundedCornerShape(16.dp)).background(Color(0xCC111216)).border(1.dp, Color(0x40E50914), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(m.name.ifBlank { "Friend" } + "  ", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF9AA1))
                    Text(m.text, fontSize = 13.sp, maxLines = 3)
                }
            }
        }
    }
}

/** Watch together › Chat: the messages and a box to write. */
@Composable
private fun ChatSection() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val msgs by Together.chat.collectAsState()
    var text by remember { mutableStateOf("") }
    var pop by remember { mutableStateOf(ctx.getSharedPreferences("call", android.content.Context.MODE_PRIVATE).getBoolean("chatPop", true)) }
    DisposableEffect(Unit) { Together.chatOpen = true; Together.unread.value = 0; onDispose { Together.chatOpen = false } }
    Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF15161C)).verticalScroll(rememberScrollState(Int.MAX_VALUE)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (msgs.isEmpty()) Text("No messages yet. Say hi 👋", fontSize = 12.5.sp, color = Color(0xFF9AA3B5))
        // Only messages from people you approved (and who approved you) show.
        msgs.filter { Social.approved(it.by) }.forEach { m ->
            val mine = m.by == Together.me
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                if (!mine) Text(m.name.ifBlank { "Friend" }, fontSize = 11.sp, color = Color(0xFFFF9AA1), fontWeight = FontWeight.Bold)
                Text(m.text, Modifier.clip(RoundedCornerShape(12.dp)).background(if (mine) Color(0x33E50914) else Color(0x14FFFFFF)).padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it.take(500) }, Modifier.weight(1f).tvOkKeyboard(), singleLine = true, placeholder = { Text("Message") }, shape = RoundedCornerShape(14.dp),
            keyboardOptions = tvKeyboard(androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send)),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { Together.say(text); text = "" }))
        Text("Send", Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(Color(0xFFE50914)).clickable { Together.say(text); text = "" }
            .padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Show new messages on the video", Modifier.weight(1f), fontSize = 12.5.sp, color = Color(0xFF9AA3B5))
        androidx.compose.material3.Switch(pop, { pop = it; ctx.getSharedPreferences("call", android.content.Context.MODE_PRIVATE).edit().putBoolean("chatPop", it).apply() },
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Color(0xFFE50914), checkedThumbColor = Color.White))
    }
}

/** In a room: code + sync, who's watching (mic / camera / talking), Audio, Video, reactions. */
@Composable
private fun TogetherRoomControls(c: String, mem: Map<String, RoomMember>) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val live by Together.live.collectAsState()
    val waiting by Together.waitingFor.collectAsState()
    val mic by Call.micOn.collectAsState()
    val cam by Call.camOn.collectAsState()
    val hear by Call.hearOn.collectAsState()
    val duck by Call.duckOn.collectAsState()
    val bubbles by Call.bubblesOn.collectAsState()
    val front by Call.frontCam.collectAsState()
    val size by Call.bubbleDp.collectAsState()
    val talking by Call.talking.collectAsState()
    val videos by Call.videos.collectAsState()
    var askFor by remember { mutableStateOf<String?>(null) }
    fun granted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        val what = askFor; askFor = null
        if (ok) { if (what == "mic") Call.setMic(true) else if (what == "cam") Call.setCam(true) }
        else {
            Snack.show("Allow it in Xstream's settings page to use it")
            runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
    fun want(kind: String, on: Boolean) {
        val perm = if (kind == "mic") android.Manifest.permission.RECORD_AUDIO else android.Manifest.permission.CAMERA
        if (on && !granted(perm)) { askFor = kind; ask.launch(perm); return }
        if (kind == "mic") Call.setMic(on) else Call.setCam(on)
    }
    LaunchedEffect(Unit) { Call.init(ctx) }
    val grey = Color(0xFF9AA3B5)

    // Room code + sync state
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF191A20)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("Room code", fontSize = 11.sp, color = grey)
            Text(c, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
        }
        Text("Copy", Modifier.padding(start = 10.dp).clip(RoundedCornerShape(50)).background(Color(0xFF23242B)).clickable {
            runCatching { (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Room code", c)) }
            Snack.show("Room code copied")
        }.padding(horizontal = 11.dp, vertical = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        val (dot, label) = when { waiting != null -> Color(0xFFFFB547) to "Waiting for $waiting"; live -> Color(0xFF3CCF7A) to "In sync"; else -> Color(0xFFFFB547) to "Connecting…" }
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(label, Modifier.padding(start = 5.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = dot, maxLines = 1)
    }

    // Who's watching
    val now = Together.serverNow()
    val everyone = mem.filterValues { now - it.seen < 40_000 }
    // You see your friends and the people who chose to be visible to you; everyone else shows as "+N" on the person
    // who brought them (tap to see their names only).
    val here = everyone.filterKeys { Social.visible(it) }
    val hidden = everyone.filterKeys { !Social.visible(it) }
    val hiddenBy = hidden.entries.groupBy { (_, m) -> m.by.takeIf { it in here } ?: here.keys.firstOrNull { it != Together.me } ?: Together.me }
    var namesFor by remember { mutableStateOf<String?>(null) }
    Text("Watching now · ${everyone.size}", Modifier.padding(top = 14.dp, bottom = 6.dp), fontSize = 12.sp, color = grey, fontWeight = FontWeight.SemiBold)
    namesFor?.let { k -> hiddenBy[k]?.let { l ->
        Text("Also here with ${here[k]?.name?.ifBlank { "your friend" } ?: "your friend"}: " + l.joinToString(", ") { it.value.name.ifBlank { "Guest" } },
            Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF191A20)).clickable { namesFor = null }.padding(10.dp), fontSize = 12.sp, color = grey)
    } }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        here.entries.sortedBy { if (it.key == Together.me) 0 else 1 }.forEach { (uid, m) ->
            val extra = hiddenBy[uid]?.size ?: 0
            val mine = uid == Together.me
            val mOn = if (mine) mic else m.mic
            val cOn = if (mine) cam else m.cam
            val speaking = uid in talking
            Column(Modifier.width(58.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    Box(Modifier.size(46.dp).clip(CircleShape).background(if (mine) Color(0xFF3A3B44) else Color(0xFF4A4F78))
                        .then(if (speaking) Modifier.border(2.5.dp, Color(0xFF3CCF7A), CircleShape) else Modifier), Alignment.Center) {
                        Text(m.name.take(1).uppercase().ifBlank { "?" }, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    Text((if (mOn) "🎙" else "🔇") + if (cOn) " 📷" else "", Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 3.dp)
                        .clip(RoundedCornerShape(50)).background(Color.Black).border(1.dp, Color(0xFF333333), RoundedCornerShape(50)).padding(horizontal = 3.dp), fontSize = 8.sp)
                    if (extra > 0) Text("+$extra", Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp).clip(RoundedCornerShape(50)).background(Color(0xFFE50914))
                        .clickable { namesFor = if (namesFor == uid) null else uid }.padding(horizontal = 5.dp, vertical = 1.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text(if (mine) "You" else m.name.ifBlank { "Guest" }, Modifier.padding(top = 4.dp), fontSize = 11.sp, maxLines = 1)
            }
        }
    }

    @Composable fun section(t: String) = Text(t, Modifier.padding(top = 16.dp, bottom = 2.dp), fontSize = 12.sp, color = grey, fontWeight = FontWeight.SemiBold)
    @Composable fun row(icon: String, title: String, sub: String, end: @Composable () -> Unit) =
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF1F2027)), Alignment.Center) { Text(icon, fontSize = 14.sp) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(sub, fontSize = 11.5.sp, color = grey)
            }
            end()
        }
    @Composable fun sw(on: Boolean, set: (Boolean) -> Unit) {
        // TV: a switch the remote can land on and toggle with OK (with the TV focus box).
        if (BuildConfig.IS_TV) TxButton(shape = RoundedCornerShape(16.dp), idle = Color.Transparent, onClick = { set(!on) }) { _ -> TxSwitch(on, Modifier.padding(6.dp)) }
        else androidx.compose.material3.Switch(on, set,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Color(0xFFE50914), checkedThumbColor = Color.White))
    }
    @Composable fun chip(t: String, act: () -> Unit) = Text(t, Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF23242B)).border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(50))
        .clickable { act() }.padding(horizontal = 11.dp, vertical = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)

    section("Chat")
    ChatSection()

    section("Audio")
    row("🎙", "My mic", if (mic) "Friends hear you" else "Off — friends can't hear you") { sw(mic) { want("mic", it) } }
    row("🔊", "Hear friends", if (hear) "Their voices play over the movie" else "Muted for you") { sw(hear) { Call.setHear(it) } }
    row("🎚", "Lower the movie while someone talks", "Comes back up when they stop") { sw(duck) { Call.setDuck(it) } }

    section("Video")
    row("📷", "My camera", if (cam) "Friends see you in their bubble" else "Off — friends see your initial") { sw(cam) { want("cam", it) } }
    row("◐", "Show friends' bubbles", if (bubbles) "Round frames on your screen" else "Hidden (you still hear them)") { sw(bubbles) { Call.setBubbles(it) } }
    row("◯", "Bubble size", "${size} dp · drag a bubble to move it, double-tap it to resize") {}
    if (BuildConfig.IS_TV) TvSlider(size.toFloat(), { Call.setSize(it.roundToInt()) }, 64f..170f, 6f, accent = Color.White)
    else androidx.compose.material3.Slider(size.toFloat(), { Call.setSize(it.roundToInt()) }, valueRange = 64f..170f,
        colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFFE50914)))
    if (cam) row("🔄", "Camera", if (front) "Front camera" else "Back camera") { chip("Flip") { Call.flip() } }
    val withVideo = here.filter { (uid, m) -> uid != Together.me && m.cam && videos[uid] != null }
    row("⌖", "Frame a friend", if (withVideo.isEmpty()) "When a friend's camera is on" else "Choose what part of their camera fills the circle") {
        withVideo.entries.firstOrNull()?.let { (uid, m) -> chip("${m.name.ifBlank { "Friend" }} ▾") { Call.framing.value = if (withVideo.size == 1) uid else uid } }
    }
    if (withVideo.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        withVideo.forEach { (uid, m) -> chip(m.name.ifBlank { "Friend" }) { Call.framing.value = uid } }
    }

    section("React")
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ReactIcons.forEach { (k, v) ->
            Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0x1FFFFFFF)).border(1.dp, Color(0x22FFFFFF), CircleShape).clickable { Together.react(k) }, Alignment.Center) {
                Icon(v.first, k, Modifier.size(23.dp), tint = v.second)
            }
        }
    }
    val fr by Call.framing.collectAsState()
    fr?.let { FrameDialog(it) { Call.framing.value = null } }
}

/** On "Who's watching?" while joining from a ring or a link: who invited you, to what. */
@Composable
fun InviteBanner() {
    val from by JoinIntent.from.collectAsState()
    val title by JoinIntent.title.collectAsState()
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 14.dp), Alignment.TopCenter) {
        Text((if (from.isNotBlank()) "$from invited you" else "You're invited") + (if (title.isNotBlank()) " to watch $title" else " to a watch party") + " · pick your profile",
            Modifier.clip(RoundedCornerShape(50)).background(Color(0xE6111216)).border(1.dp, Color(0x80E50914), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

/** My Space › Watch together: join a room by its code and open its video. */
@Composable
fun JoinRoomDialog(onJoined: (RoomState) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    PremiumAlert(
        onDismissRequest = onDismiss,
        title = { Text("Watch together") },
        text = {
            Column {
                Text("Enter the 4-letter room code your friend sees in their player (More › Together).", fontSize = 13.sp, color = Color(0xFF9AA3B5))
                OutlinedTextField(input, { input = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(4) }, Modifier.fillMaxWidth().padding(top = 12.dp).tvOkKeyboard(top = 8.dp),
                    singleLine = true, label = { Text("Room code") }, shape = RoundedCornerShape(12.dp), keyboardOptions = tvKeyboard())
                msg?.let { Text(it, Modifier.padding(top = 8.dp), fontSize = 13.sp, color = Color(0xFFFFB4A8)) }
            }
        },
        confirmButton = {
            TextButton({
                busy = true
                scope.launch {
                    val (st, err) = Together.join(input)
                    busy = false; msg = err
                    if (st != null) onJoined(st)
                }
            }, enabled = !busy && input.length == 4) { Text("Join") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

// ───────────── Share / invite, watch buddies and the 🔔 ring ─────────────

/** Someone who joined one of your rooms (kept on this phone, so you can ring them next time). */
@Serializable data class Buddy(val uid: String, val name: String, val seen: Long = 0)
@Serializable data class Invite(val from: String = "", val fromName: String = "", val title: String = "", val code: String = "", val at: Long = 0)

/** A room code to join (from an invite notification or a streamx://join/CODE link). */
object JoinIntent {
    val code = MutableStateFlow<String?>(null)
    /** Who invited and to what (shown on "Who's watching?" before joining). */
    val from = MutableStateFlow("")
    val title = MutableStateFlow("")
    /** "Who's watching?" was already shown for this invite. */
    @Volatile var asked = false
    /** Who sent the invite (link ?by=…, a ring or a "waiting for you"): you two become watch friends. */
    @Volatile var by = ""
    fun take(intent: android.content.Intent?) {
        // streamx://join/CODE (old invites) or https://streamx-watch.pages.dev/join/CODE (WhatsApp links)
        val c = intent?.getStringExtra("joinCode") ?: intent?.data?.let { u ->
            when {
                u.scheme == "streamx" -> u.lastPathSegment ?: u.host
                u.host?.endsWith("streamx-watch.pages.dev") == true -> u.getQueryParameter("c") ?: u.pathSegments.getOrNull(1)
                else -> null
            }
        }
        val code = c?.trim()?.uppercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.length == 4 } ?: return
        from.value = intent?.getStringExtra("joinFrom").orEmpty(); title.value = intent?.getStringExtra("joinTitle").orEmpty(); asked = false
        by = intent?.getStringExtra("joinBy") ?: intent?.data?.getQueryParameter("by").orEmpty()
        ActLog.add("TOGETHER", "invite opened: $code")
        JoinIntent.code.value = code
        // Used once: a recreated screen must not ask "Who's watching?" again for the same invite.
        intent?.removeExtra("joinCode")
        intent?.data = null
    }
}

object Buddies {
    private var prefs: android.content.SharedPreferences? = null
    val list = MutableStateFlow<List<Buddy>>(emptyList())
    fun init(ctx: android.content.Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences("buddies", android.content.Context.MODE_PRIVATE)
        list.value = runCatching { Api.json.decodeFromString<List<Buddy>>(prefs?.getString("all", null) ?: "[]") }.getOrDefault(emptyList())
    }
    /** Everyone seen in your room is remembered (newest first). */
    fun note(members: Map<String, RoomMember>, me: String) {
        val now = System.currentTimeMillis()
        val add = members.filter { (uid, m) -> uid != me && uid.isNotBlank() && now - m.seen < 120_000 }.map { (uid, m) -> Buddy(uid, m.name.ifBlank { "Friend" }, now) }
        if (add.isEmpty()) return
        val merged = (add + list.value).distinctBy { it.uid }.take(30)
        if (merged == list.value) return
        list.value = merged
        prefs?.edit()?.putString("all", Api.json.encodeToString(merged))?.apply()
    }
    fun remove(uid: String) { list.value = list.value.filter { it.uid != uid }; prefs?.edit()?.putString("all", Api.json.encodeToString(list.value))?.apply() }
}

object Invites {
    private const val CHANNEL = "together"

    /** The text shared by "Share / Invite" (also copied to the clipboard). */
    fun shareText(code: String, title: String): String =
        "🎬 Watch \"$title\" with me on Xstream!\nhttps://streamx-watch.pages.dev/join/$code" + (Together.me.takeIf { it.isNotBlank() }?.let { "?by=$it" } ?: "") + "\nRoom code: $code"

    /** Rings a buddy: their Xstream shows "<you> started watching <title> — tap to join". */
    suspend fun ring(b: Buddy, code: String, title: String): String? = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext "Sign in first (Settings › Account & Cloud Sync)."
        val inv = Invite(Together.me, Together.myName.ifBlank { "A friend" }, title, code, System.currentTimeMillis())
        runCatching {
            val req = Request.Builder().url("${CloudConfig.DB_URL}/invites/${b.uid}.json?auth=$t").post(Api.json.encodeToString(inv).toRequestBody("application/json".toMediaType())).build()
            Api.http.newCall(req).execute().use { r -> if (r.isSuccessful) null else if (r.code == 401 || r.code == 403) "Not allowed — add the \"invites\" rule in Firebase" else "HTTP ${r.code}" }
        }.getOrElse { "Couldn't reach the cloud" }
    }

    /** New invites for you: shown as notifications (tap = join), then removed. */
    suspend fun check(ctx: android.content.Context): Int = withContext(Dispatchers.IO) {
        Cloud.init(ctx)
        val uid = Cloud.user.value?.uid ?: return@withContext 0
        // Watch together: friend requests, introductions, plans, messages (phone only).
        if (!BuildConfig.IS_TV) runCatching { Social.check(ctx) }
        val t = Cloud.token() ?: return@withContext 0
        val body = runCatching { Api.get("${CloudConfig.DB_URL}/invites/$uid.json?auth=$t") }.getOrNull() ?: return@withContext 0
        if (body.trim() == "null") return@withContext 0
        val all = runCatching { Api.json.decodeFromString<Map<String, Invite>>(body) }.getOrDefault(emptyMap())
        val now = System.currentTimeMillis()
        var shown = 0
        all.forEach { (id, inv) ->
            if (now - inv.at < 2 * 3600_000L && inv.code.length == 4) { post(ctx, inv); shown++ }
            runCatching { Api.http.newCall(Request.Builder().url("${CloudConfig.DB_URL}/invites/$uid/$id.json?auth=$t").delete().build()).execute().close() }
        }
        shown
    }

    private fun post(ctx: android.content.Context, inv: Invite) = runCatching {
        val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "Watch together invites", android.app.NotificationManager.IMPORTANCE_HIGH))
        val open = android.content.Intent(ctx, MainActivity::class.java).putExtra("joinCode", inv.code).putExtra("joinFrom", inv.fromName).putExtra("joinTitle", inv.title).putExtra("joinBy", inv.from)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = android.app.PendingIntent.getActivity(ctx, inv.code.hashCode(), open, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val n = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle("${inv.fromName} started watching ${inv.title}")
            .setContentText("Waiting for you to join · tap to watch together")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(pi).build()
        androidx.core.app.NotificationManagerCompat.from(ctx).notify(inv.code.hashCode(), n)
    }

    /** Checks while the app is open (every 20 s) and in the background (every 15 minutes). */
    fun start(ctx: android.content.Context) {
        val app = ctx.applicationContext
        runCatching {
            val req = androidx.work.PeriodicWorkRequestBuilder<InviteWorker>(15, java.util.concurrent.TimeUnit.MINUTES)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()).build()
            androidx.work.WorkManager.getInstance(app).enqueueUniquePeriodicWork("streamx-invites", androidx.work.ExistingPeriodicWorkPolicy.KEEP, req)
        }
        if (loop != null) return
        loop = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { while (isActive) { runCatching { check(app) }; delay(20_000) } }
    }
    private var loop: Job? = null
}

class InviteWorker(ctx: android.content.Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result { runCatching { Invites.check(applicationContext) }; return Result.success() }
}

/** Player › Watch together: Share / Invite (share sheet + link copied) and your buddies with a 🔔 to ring them. */
@Composable
fun TogetherInvite(code: String?, title: String, ensureRoom: suspend () -> String?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val buddies by Buddies.list.collectAsState()
    var msg by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        TextButton({
            scope.launch {
                val c = code ?: ensureRoom()
                // No room (not signed in / Firebase rule missing): still share an invite with the app link.
                if (c == null) Snack.show("Couldn't open a room (sign in, and add the \"rooms\" rule in Firebase) — sharing the app link")
                val text = if (c != null) Invites.shareText(c, title)
                    else "🎬 Let's watch \"$title\" together on Xstream!\nGet Xstream: https://github.com/callmerickk/Xstream/releases/latest"
                runCatching {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Xstream invite", text))
                }
                Snack.show("Invite copied — paste it in any chat")
                runCatching {
                    ctx.startActivity(android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text), "Invite to watch together",
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }) { Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Share / Invite") }
        if (buddies.isNotEmpty()) {
            Text("Your watch buddies", Modifier.padding(top = 10.dp, bottom = 4.dp), fontSize = 12.sp, color = Color(0xFF9AA3B5))
            buddies.take(8).forEach { b ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0x22FFFFFF)), Alignment.Center) {
                        Text(b.name.take(1).uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(b.name, Modifier.weight(1f).padding(start = 10.dp), fontSize = 14.sp)
                    // 🔔 Ring: "<you> started watching <title> and is waiting for you".
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Color(0x1FE50914)).border(1.dp, Color(0x8CE50914), CircleShape)
                            .clickable {
                                scope.launch {
                                    val c = code ?: ensureRoom()
                                    if (c == null) { Snack.show("Couldn't open a room — sign in and check the Firebase \"rooms\" rule"); return@launch }
                                    val r = Invites.ring(b, c, title) ?: "Rang ${b.name} 🔔"
                                    msg = r; Snack.show(r)
                                }
                            },
                        Alignment.Center,
                    ) { Icon(Icons.Default.NotificationsActive, "Ring ${b.name}", Modifier.size(18.dp), tint = Color(0xFFFF8A80)) }
                }
            }
        }
        msg?.let { Text(it, Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Color(0xFFB6BDCC)) }
    }
}
