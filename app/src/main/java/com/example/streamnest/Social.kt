package com.example.streamnest

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Someone in your circle: a room friend, or a person who chose to be visible to you. */
data class Person(val uid: String, val name: String)

/** A message in your social inbox (friend requests, introductions, plans, pings). */
@Serializable data class SocialMsg(
    val t: String = "", val from: String = "", val fromName: String = "",
    /** introductions: the person this is about (and who introduced you) */
    val about: String = "", val aboutName: String = "",
    val item: String = "", val plan: String = "", val title: String = "", val at: Long = 0, val code: String = "",
    val sent: Long = 0,
)

/** A plan to watch a shared title at [at] with [with] (uid → name). [room]: the room code once someone started it. */
data class WatchPlan(val id: String, val at: Long, val with: Map<String, String>, val by: String, val byName: String, val room: String?)

/** A title shared with some of your friends (only its members can see it), with its watch plans. */
data class SharedItem(val id: String, val meta: Meta, val by: String, val members: Map<String, String>, val plans: List<WatchPlan>)

/** A chat: one-to-one (photos allowed, keep time 1–7 days) or a group (text only, cleared after 24 hours). */
data class ChatInfo(val id: String, val members: Map<String, String>, val group: Boolean, val name: String, val ttlH: Int, val by: String)

data class SocialLine(val id: String, val from: String, val name: String, val text: String, val img: Boolean, val at: Long, val seen: Boolean, val system: Boolean = false)

/**
 * Watch together's circle of people, kept in the cloud (Firebase, under social/…).
 *
 *  • Room friends: you and the person you invited (or who invited you) — saved, can be rung, planned with, chatted with.
 *    Also anyone whose friend request you accepted.
 *  • Introductions: a friend can introduce you to one of their friends. Each of you is asked separately; when you
 *    accept, that person can see you (in a live room). You can take it back at any time.
 *  • In a live room you see your friends and the people who chose to be visible to you; the rest show as "+N" on the
 *    friend who brought them. Voice, video and chat only work between people who approved each other.
 *
 * Every user only writes their own lists; anything for someone else goes to their inbox (social/inbox/{uid}).
 */
object Social {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val JSONT = "application/json; charset=utf-8".toMediaType()

    val friends = MutableStateFlow<List<Person>>(emptyList())
    /** People you allowed to see you (and can take back). */
    val seeMe = MutableStateFlow<List<Person>>(emptyList())
    /** People who allowed you to see them. */
    val iCanSee = MutableStateFlow<List<Person>>(emptyList())
    /** Friend requests and introductions waiting for your answer. */
    val pending = MutableStateFlow<List<Pair<String, SocialMsg>>>(emptyList())
    val items = MutableStateFlow<List<SharedItem>>(emptyList())
    val chats = MutableStateFlow<List<ChatInfo>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    /** A plan whose room this phone should start when its video plays (item id, plan id). */
    @Volatile var hostPlan: Pair<String, String>? = null

    val me: String get() = Cloud.user.value?.uid ?: ""
    val myName: String get() = Together.myName.ifBlank { Cloud.user.value?.email?.substringBefore('@') ?: "A friend" }

    // ───────────── cloud ─────────────

    private fun url(path: String, t: String) = "${CloudConfig.DB_URL}/social/$path.json?auth=$t"

    suspend fun get(path: String): JsonElement? = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext null
        runCatching {
            Api.http.newCall(Request.Builder().url(url(path, t)).build()).execute().use { r ->
                if (r.code == 401 || r.code == 403) { error.value = "Watch together needs the new Firebase rules (social)."; null }
                else if (!r.isSuccessful) null else r.body?.string()?.let { Api.json.parseToJsonElement(it) }
            }
        }.getOrNull()
    }

    suspend fun send(method: String, path: String, body: JsonElement? = null): String? = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext null
        runCatching {
            val b = Request.Builder().url(url(path, t))
            val rb = (body ?: JsonNull).toString().toRequestBody(JSONT)
            when (method) { "PUT" -> b.put(rb); "PATCH" -> b.patch(rb); "POST" -> b.post(rb); "DELETE" -> b.delete(); else -> b.get() }
            Api.http.newCall(b.build()).execute().use { r ->
                if (r.code == 401 || r.code == 403) error.value = "Watch together needs the new Firebase rules (social)."
                if (r.isSuccessful) r.body?.string() ?: "" else null
            }
        }.getOrNull()
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.s(k: String) = this[k].str()
    private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    private fun names(o: JsonObject?): Map<String, String> = o?.mapValues { (_, v) -> v.obj()?.s("n") ?: v.str() ?: "" } ?: emptyMap()
    private fun nameJson(n: String) = buildJsonObject { put("n", n) }

    /** Your public name card (so others can show your name). */
    fun publishCard() { if (me.isNotBlank()) io.launch { send("PUT", "card/$me", nameJson(myName)) } }
    suspend fun cardName(uid: String): String = get("card/$uid").obj()?.s("n") ?: ""

    /** Xstream: sends a message to someone's inbox (used by recommendations). */
    suspend fun sendRaw(to: String, m: SocialMsg) = inbox(to, m)

    private suspend fun inbox(to: String, m: SocialMsg) {
        if (to.isBlank() || to == me) return
        send("POST", "inbox/$to", Api.json.encodeToJsonElement(SocialMsg.serializer(), m.copy(from = me, fromName = myName, sent = System.currentTimeMillis())))
    }

    /** Reloads your lists (friends, who sees you, who you see, shared titles, chats). */
    suspend fun refresh() {
        if (me.isBlank()) return
        friends.value = names(get("friends/$me").obj()).map { Person(it.key, it.value) }.sortedBy { it.name.lowercase() }
        seeMe.value = names(get("seeme/$me").obj()).map { Person(it.key, it.value) }
        iCanSee.value = names(get("icansee/$me").obj()).map { Person(it.key, it.value) }
        val ids = get("myitems/$me").obj()?.keys.orEmpty()
        items.value = ids.mapNotNull { id -> loadItem(id) }.sortedByDescending { it.plans.maxOfOrNull { p -> p.at } ?: 0L }
        val cids = get("mychats/$me").obj()?.keys.orEmpty()
        chats.value = cids.mapNotNull { id -> loadChat(id) }
    }

    // ───────────── friends ─────────────

    fun isFriend(uid: String) = friends.value.any { it.uid == uid }
    /** Shown to you in a live room: a friend, or someone who chose to be visible to you. */
    fun visible(uid: String) = uid == me || isFriend(uid) || iCanSee.value.any { it.uid == uid }
    /** Voice, video and chat in a room: friends, or people who approved each other both ways. */
    fun approved(uid: String) = uid == me || isFriend(uid) || (iCanSee.value.any { it.uid == uid } && seeMe.value.any { it.uid == uid })

    /** You joined [uid]'s room through their invite (or they joined yours through yours): you become room friends. */
    private val befriended = java.util.Collections.synchronizedSet(HashSet<String>())
    fun befriend(uid: String, name: String) {
        if (uid.isBlank() || uid == me || me.isBlank() || !befriended.add(uid)) return
        io.launch {
            val n = name.ifBlank { cardName(uid) }.ifBlank { "Friend" }
            if (!isFriend(uid)) {
                send("PUT", "friends/$me/$uid", nameJson(n))
                friends.value = (friends.value + Person(uid, n)).distinctBy { it.uid }
                inbox(uid, SocialMsg(t = "fadd"))
            }
        }
    }

    fun requestFriend(p: Person) = io.launch { inbox(p.uid, SocialMsg(t = "freq")); Snack.show("Friend request sent to ${p.name}") }

    fun removeFriend(p: Person) = io.launch {
        send("DELETE", "friends/$me/${p.uid}"); friends.value = friends.value.filter { it.uid != p.uid }
        inbox(p.uid, SocialMsg(t = "fbye"))
    }

    /** Introduce some of your friends to [to]: both sides are asked; each decides for themselves. */
    fun introduce(to: Person, people: List<Person>) = io.launch {
        people.filter { it.uid != to.uid }.forEach { p ->
            inbox(to.uid, SocialMsg(t = "share", about = p.uid, aboutName = p.name))
            inbox(p.uid, SocialMsg(t = "share", about = to.uid, aboutName = to.name))
        }
        Snack.show("Asked ${to.name} and ${people.size} friend${if (people.size == 1) "" else "s"}")
    }

    /** Answer a friend request or an introduction. */
    fun answer(id: String, m: SocialMsg, yes: Boolean) = io.launch {
        send("DELETE", "inbox/$me/$id")
        pending.value = pending.value.filter { it.first != id }
        if (!yes) return@launch
        when (m.t) {
            "freq" -> { send("PUT", "friends/$me/${m.from}", nameJson(m.fromName)); friends.value = (friends.value + Person(m.from, m.fromName)).distinctBy { it.uid }; inbox(m.from, SocialMsg(t = "fadd")) }
            "share" -> {
                // You allow [about] to see you (in a live room), and tell them.
                send("PUT", "seeme/$me/${m.about}", nameJson(m.aboutName))
                seeMe.value = (seeMe.value + Person(m.about, m.aboutName)).distinctBy { it.uid }
                inbox(m.about, SocialMsg(t = "shown"))
            }
        }
    }

    /** Take back being visible to [p]: they can't see, hear or chat with you any more. */
    fun revoke(p: Person) = io.launch {
        send("DELETE", "seeme/$me/${p.uid}"); seeMe.value = seeMe.value.filter { it.uid != p.uid }
        inbox(p.uid, SocialMsg(t = "hidden"))
    }

    // ───────────── inbox ─────────────

    /** Reads the inbox: applies what can be applied, keeps questions for you, shows notifications. */
    suspend fun check(ctx: Context) {
        if (me.isBlank()) return
        val box = get("inbox/$me").obj() ?: run { pending.value = emptyList(); return }
        val keep = ArrayList<Pair<String, SocialMsg>>()
        var changed = false
        for ((id, el) in box) {
            val m = runCatching { Api.json.decodeFromJsonElement(SocialMsg.serializer(), el) }.getOrNull() ?: continue
            var done = true
            when (m.t) {
                "fadd" -> { send("PUT", "friends/$me/${m.from}", nameJson(m.fromName)); SocialNotes.post(ctx, "${m.fromName} is now your watch friend", "You can ring them, plan and chat in Watch together", id) }
                "fbye" -> send("DELETE", "friends/$me/${m.from}")
                "freq" -> { done = false; if (pending.value.none { it.first == id }) SocialNotes.post(ctx, "${m.fromName} wants to be your watch friend", "Open Watch together to answer", id) }
                "share" -> { done = false; if (pending.value.none { it.first == id }) SocialNotes.post(ctx, "${m.fromName} wants to connect you with ${m.aboutName}", "You decide if ${m.aboutName} can see you", id) }
                "shown" -> send("PUT", "icansee/$me/${m.from}", nameJson(m.fromName))
                "hidden" -> send("DELETE", "icansee/$me/${m.from}")
                "item" -> { send("PUT", "myitems/$me/${m.item}", JsonPrimitive(true)); SocialNotes.post(ctx, "${m.fromName} shared ${m.title} with you", "In Watch together › Shared lists", id) }
                "plan", "replan" -> {
                    send("PUT", "myitems/$me/${m.item}", JsonPrimitive(true))
                    PlanAlarm.schedule(ctx, m.item, m.plan, m.at, m.title)
                    SocialNotes.post(ctx, "${m.fromName} planned ${m.title}", "${planTime(m.at)} · you'll get a reminder", id)
                }
                "unplan" -> { PlanAlarm.cancel(ctx, m.item, m.plan); SocialNotes.post(ctx, "${m.fromName} cancelled ${m.title}", "${planTime(m.at)} is off the calendar", id) }
                "watchnow" -> SocialNotes.post(ctx, "${m.fromName} wants to watch ${m.title} now", "Open Watch together › Shared lists to join", id, item = m.item, plan = m.plan)
                "waiting" -> SocialNotes.post(ctx, "${m.fromName} is waiting for you in the room", "${m.title} · join fast", id, code = m.code, by = m.from)
                "msg" -> if (!ChatState.open(m.item)) SocialNotes.post(ctx, if (m.title.isNotBlank()) "${m.fromName} · ${m.title}" else m.fromName, m.aboutName.ifBlank { "New message" }, "chat_" + m.item, chat = m.item)
                "chat" -> send("PUT", "mychats/$me/${m.item}", JsonPrimitive(true))
                "rec" -> { Recs.incoming(ctx, m); SocialNotes.post(ctx, "${m.fromName} recommended ${m.title}", "It's in your library now. Watch it and tell ${m.fromName} what you thought", id) }
                "recfb" -> { Recs.feedback(ctx, m); SocialNotes.post(ctx, "${m.fromName} watched ${m.title}", "“${m.aboutName}”", id) }
                else -> {}
            }
            if (done) { send("DELETE", "inbox/$me/$id"); changed = true } else keep += id to m
        }
        pending.value = keep.sortedByDescending { it.second.sent }
        // Lists are reloaded only when something new came in (the inbox is checked every 20 s).
        if (changed) refresh()
    }

    // ───────────── shared titles and plans ─────────────

    private suspend fun loadItem(id: String): SharedItem? {
        val o = get("items/$id").obj() ?: return null
        val meta = o.s("meta")?.let { runCatching { Api.json.decodeFromString<Meta>(it) }.getOrNull() } ?: return null
        val plans = o["plans"].obj()?.mapNotNull { (pid, pe) ->
            val p = pe.obj() ?: return@mapNotNull null
            WatchPlan(pid, p.l("at") ?: 0L, names(p["with"].obj()), p.s("by") ?: "", p.s("byName") ?: "", p.s("room")?.takeIf { it.length == 4 })
        }.orEmpty().sortedBy { it.at }
        return SharedItem(id, meta, o.s("by") ?: "", names(o["members"].obj()), plans)
    }

    /** Shares [m] with [with] (some of your friends): only they and you can see it. Returns the item id. */
    suspend fun share(m: Meta, with: List<Person>): String? {
        if (me.isBlank()) { Snack.show("Sign in first (Settings › Account & Cloud Sync)."); return null }
        val members = buildJsonObject { put(me, nameJson(myName)); with.forEach { put(it.uid, nameJson(it.name)) } }
        val slim = m.copy(videos = null, description = m.description?.take(300))
        val body = buildJsonObject { put("meta", Api.json.encodeToString(slim)); put("by", me); put("members", members) }
        val r = send("POST", "items", body) ?: return null
        val id = runCatching { Api.json.parseToJsonElement(r).jsonObject["name"].str() }.getOrNull() ?: return null
        send("PUT", "myitems/$me/$id", JsonPrimitive(true))
        with.forEach { inbox(it.uid, SocialMsg(t = "item", item = id, title = m.name)) }
        refresh()
        return id
    }

    /** Plans [item] at [at] with [with] (or only you). Everyone in it gets the reminder at the same time. */
    suspend fun plan(ctx: Context, item: SharedItem, at: Long, with: List<Person>, existing: String? = null): Boolean {
        val pid = existing ?: "p${System.currentTimeMillis()}"
        // Everyone in the plan must be able to see the title.
        val add = with.filter { it.uid !in item.members }
        if (add.isNotEmpty()) send("PATCH", "items/${item.id}/members", buildJsonObject { add.forEach { put(it.uid, nameJson(it.name)) } })
        val body = buildJsonObject {
            put("at", at); put("by", me); put("byName", myName)
            put("with", buildJsonObject { put(me, nameJson(myName)); with.forEach { put(it.uid, nameJson(it.name)) } })
        }
        send("PUT", "items/${item.id}/plans/$pid", body) ?: return false
        PlanAlarm.schedule(ctx, item.id, pid, at, item.meta.name)
        with.forEach { inbox(it.uid, SocialMsg(t = if (existing != null) "replan" else "plan", item = item.id, plan = pid, title = item.meta.name, at = at)) }
        refresh()
        return true
    }

    /** "Watch now" on a plan: tells the others; the room starts when your video plays. */
    fun watchNow(item: SharedItem, plan: WatchPlan) = io.launch {
        hostPlan = item.id to plan.id
        plan.with.keys.filter { it != me }.forEach { inbox(it, SocialMsg(t = "watchnow", item = item.id, plan = plan.id, title = item.meta.name)) }
    }

    /** Your room for a plan has started: saved on the plan and everyone in it is told you're waiting. */
    fun planRoomStarted(code: String) {
        val (itemId, pid) = hostPlan ?: return
        hostPlan = null
        io.launch {
            send("PUT", "items/$itemId/plans/$pid/room", JsonPrimitive(code))
            val it = loadItem(itemId) ?: return@launch
            val p = it.plans.firstOrNull { p -> p.id == pid } ?: return@launch
            p.with.keys.filter { u -> u != me }.forEach { u -> inbox(u, SocialMsg(t = "waiting", item = itemId, plan = pid, title = it.meta.name, code = code)) }
        }
    }

    /**
     * Removes a shared title from your Shared lists (and its plans from your calendar). Shared by you: it goes for
     * everyone in it; shared with you: only you leave it. It doesn't come back on the next sync.
     */
    suspend fun leaveItem(ctx: Context, item: SharedItem): Boolean {
        item.plans.forEach { p -> runCatching { PlanAlarm.cancel(ctx, item.id, p.id) } }
        if (item.by == me) {
            send("DELETE", "items/${item.id}") ?: return false
            item.members.keys.filter { it != me }.forEach { u -> runCatching { send("DELETE", "myitems/$u/${item.id}") }; inbox(u, SocialMsg(t = "unshare", item = item.id, title = item.meta.name)) }
        } else send("DELETE", "items/${item.id}/members/$me")
        send("DELETE", "myitems/$me/${item.id}")
        refresh()
        return true
    }

    /** Takes a plan off the calendar (everyone in it is told), and its reminder is cancelled. */
    suspend fun removePlan(ctx: Context, item: SharedItem, plan: WatchPlan): Boolean {
        send("DELETE", "items/${item.id}/plans/${plan.id}") ?: return false
        PlanAlarm.cancel(ctx, item.id, plan.id)
        plan.with.keys.filter { it != me }.forEach { inbox(it, SocialMsg(t = "unplan", item = item.id, plan = plan.id, title = item.meta.name, at = plan.at)) }
        refresh()
        return true
    }

    /** Ring the others of a plan right now ("let's watch"). */
    fun ring(item: SharedItem, plan: WatchPlan) = io.launch {
        plan.with.keys.filter { it != me }.forEach { inbox(it, SocialMsg(t = "watchnow", item = item.id, plan = plan.id, title = item.meta.name)) }
        Snack.show("Rang ${plan.with.filterKeys { it != me }.values.joinToString(", ")}")
    }

    suspend fun item(id: String): SharedItem? = loadItem(id)

    // ───────────── chats ─────────────

    private suspend fun loadChat(id: String): ChatInfo? {
        val o = get("chats/$id").obj() ?: return null
        return ChatInfo(id, names(o["members"].obj()), (o["group"] as? JsonPrimitive)?.booleanOrNull == true, o.s("name") ?: "", (o.l("ttl") ?: 72L).toInt(), o.s("by") ?: "")
    }

    /** The one-to-one chat with a friend (made the first time). */
    suspend fun dm(p: Person): ChatInfo? {
        val id = "dm_" + listOf(me, p.uid).sorted().joinToString("_")
        loadChat(id)?.let { c -> send("PUT", "mychats/$me/$id", JsonPrimitive(true)); return c }
        val body = buildJsonObject { put("members", buildJsonObject { put(me, nameJson(myName)); put(p.uid, nameJson(p.name)) }); put("group", false); put("ttl", 72); put("by", me) }
        send("PUT", "chats/$id", body) ?: return null
        send("PUT", "mychats/$me/$id", JsonPrimitive(true))
        inbox(p.uid, SocialMsg(t = "chat", item = id))
        return loadChat(id).also { refresh() }
    }

    /** A group chat with some friends: text only, cleared after 24 hours. */
    suspend fun group(name: String, people: List<Person>): ChatInfo? {
        val body = buildJsonObject {
            put("members", buildJsonObject { put(me, nameJson(myName)); people.forEach { put(it.uid, nameJson(it.name)) } })
            put("group", true); put("ttl", 24); put("name", name.ifBlank { "Movie night" }); put("by", me)
        }
        val r = send("POST", "chats", body) ?: return null
        val id = runCatching { Api.json.parseToJsonElement(r).jsonObject["name"].str() }.getOrNull() ?: return null
        send("PUT", "mychats/$me/$id", JsonPrimitive(true))
        people.forEach { inbox(it.uid, SocialMsg(t = "chat", item = id)) }
        return loadChat(id).also { refresh() }
    }

    /** One-to-one chats: how long messages are kept (24, 72, 120 or 168 hours). Both people see a note. */
    suspend fun setKeep(c: ChatInfo, hours: Int) {
        if (c.group) return
        send("PATCH", "chats/${c.id}", buildJsonObject { put("ttl", hours) })
        val label = if (hours == 24) "24 hours" else "${hours / 24} days"
        post(c, "$myName set messages to clear after $label", system = true)
        refresh()
    }

    /** Messages of a chat (oldest first); ones older than the chat's keep time are deleted for everyone. */
    suspend fun lines(c: ChatInfo): List<SocialLine> {
        val o = get("msgs/${c.id}").obj() ?: return emptyList()
        val cut = System.currentTimeMillis() - c.ttlH * 3_600_000L
        val out = ArrayList<SocialLine>()
        for ((id, el) in o) {
            val m = el.obj() ?: continue
            val at = m.l("at") ?: 0L
            if (at < cut) { send("DELETE", "msgs/${c.id}/$id"); send("DELETE", "imgs/${c.id}/$id"); continue }
            out += SocialLine(id, m.s("from") ?: "", m.s("n") ?: "", m.s("text") ?: "", (m["img"] as? JsonPrimitive)?.booleanOrNull == true, at,
                (m["seen"] as? JsonPrimitive)?.booleanOrNull == true, (m["sys"] as? JsonPrimitive)?.booleanOrNull == true)
        }
        return out.sortedBy { it.at }
    }

    suspend fun post(c: ChatInfo, text: String, system: Boolean = false): Boolean {
        val t = text.trim().take(1000); if (t.isEmpty()) return false
        val body = buildJsonObject { put("from", me); put("n", myName); put("text", t); put("at", System.currentTimeMillis()); if (system) put("sys", true) }
        send("POST", "msgs/${c.id}", body) ?: return false
        if (!system) c.members.keys.filter { it != me }.forEach { inbox(it, SocialMsg(t = "msg", item = c.id, title = if (c.group) c.name else "", aboutName = t.take(80))) }
        return true
    }

    /** A view-once photo (one-to-one chats only): kept in the cloud until the other person has opened and closed it. */
    suspend fun postPhoto(c: ChatInfo, jpeg: ByteArray): Boolean {
        if (c.group) return false
        val r = send("POST", "msgs/${c.id}", buildJsonObject { put("from", me); put("n", myName); put("text", ""); put("img", true); put("at", System.currentTimeMillis()) }) ?: return false
        val id = runCatching { Api.json.parseToJsonElement(r).jsonObject["name"].str() }.getOrNull() ?: return false
        send("PUT", "imgs/${c.id}/$id", JsonPrimitive(android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))) ?: return false
        c.members.keys.filter { it != me }.forEach { inbox(it, SocialMsg(t = "msg", item = c.id, aboutName = "📷 Photo · view once")) }
        return true
    }

    suspend fun photo(c: ChatInfo, line: SocialLine): ByteArray? =
        get("imgs/${c.id}/${line.id}").str()?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }

    /** The photo was opened and closed: deleted from the cloud for good. */
    suspend fun photoSeen(c: ChatInfo, line: SocialLine) {
        send("DELETE", "imgs/${c.id}/${line.id}")
        send("PATCH", "msgs/${c.id}/${line.id}", buildJsonObject { put("seen", true) })
    }

    fun planTime(at: Long): String = java.text.SimpleDateFormat("EEE d MMM · h:mm a", java.util.Locale.getDefault()).format(java.util.Date(at))
}

/** Which chat is open on screen (its messages don't also pop up as notifications). */
object ChatState {
    @Volatile var openId: String? = null
    fun open(id: String) = openId == id
}

/** Watch together notifications (friend requests, plans, "waiting for you", messages). */
object SocialNotes {
    private const val CHANNEL = "social"
    fun post(ctx: Context, title: String, text: String, tag: String, code: String = "", by: String = "", item: String = "", plan: String = "", chat: String = "") = runCatching {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "Watch together", android.app.NotificationManager.IMPORTANCE_HIGH))
        val open = android.content.Intent(ctx, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("social", true)
        if (code.isNotBlank()) open.putExtra("joinCode", code).putExtra("joinBy", by).putExtra("joinFrom", title.substringBefore(" is waiting"))
        if (item.isNotBlank()) open.putExtra("planItem", item).putExtra("planId", plan)
        if (chat.isNotBlank()) open.putExtra("chatId", chat)
        val pi = android.app.PendingIntent.getActivity(ctx, tag.hashCode(), open, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val n = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle(title).setContentText(text).setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(pi).build()
        androidx.core.app.NotificationManagerCompat.from(ctx).notify(tag.hashCode(), n)
    }
}

/** What a Watch together notification asked for (open a chat / a plan), read once the profile is picked. */
object SocialIntent {
    val chat = MutableStateFlow<String?>(null)
    val plan = MutableStateFlow<Pair<String, String>?>(null)
    val open = MutableStateFlow(false)
    fun take(i: android.content.Intent?) {
        i ?: return
        i.getStringExtra("chatId")?.let { chat.value = it; open.value = true }
        i.getStringExtra("planItem")?.let { plan.value = it to i.getStringExtra("planId").orEmpty(); open.value = true }
        if (i.getBooleanExtra("social", false)) open.value = true
        i.removeExtra("chatId"); i.removeExtra("planItem"); i.removeExtra("social")
    }
}

/** Reminders for planned watches, at the planned time (also after a restart of the phone they're set again on open). */
object PlanAlarm {
    fun cancel(ctx: Context, item: String, plan: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val i = android.content.Intent(ctx, PlanAlarmReceiver::class.java)
        val pi = android.app.PendingIntent.getBroadcast(ctx, (item + plan).hashCode(), i, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_NO_CREATE)
        if (pi != null) runCatching { am.cancel(pi); pi.cancel() }
    }
    fun schedule(ctx: Context, item: String, plan: String, at: Long, title: String) {
        if (at <= System.currentTimeMillis()) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val i = android.content.Intent(ctx, PlanAlarmReceiver::class.java).putExtra("item", item).putExtra("plan", plan).putExtra("title", title)
        val pi = android.app.PendingIntent.getBroadcast(ctx, (item + plan).hashCode(), i, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi) }
    }
}

class PlanAlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: android.content.Intent) {
        val item = intent.getStringExtra("item") ?: return
        val plan = intent.getStringExtra("plan") ?: return
        val title = intent.getStringExtra("title") ?: "your plan"
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                Cloud.init(ctx)
                val it = Social.item(item)
                val p = it?.plans?.firstOrNull { p -> p.id == plan }
                val others = p?.with?.filterKeys { k -> k != Social.me }?.values.orEmpty()
                val who = if (others.isEmpty()) "You planned to watch it now." else "You, ${others.joinToString(", ")} planned to watch it together now."
                SocialNotes.post(ctx, "$title · it's time", "$who Tap to join the room.", "plan_$item$plan", code = p?.room.orEmpty(), item = if (p?.room == null) item else "", plan = plan)
            }
            pending.finish()
        }
    }
}
