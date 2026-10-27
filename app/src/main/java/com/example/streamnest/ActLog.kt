package com.example.streamnest

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Activity log: everything Xstream does — every tap and remote key (with the screen and the button), screens opened,
 * network requests (host, answer, time), playback steps, Watch together, sync, VPN, errors. One file per day, the last
 * 3 days kept (older ones deleted), each day capped at 20 MB. Typed text is never logged; tokens in links are hidden.
 * The TV sends its log to the account, so it can be read on the phone (Developer's Corner › Activity log).
 */
object ActLog {
    const val KEEP_DAYS = 3
    private const val MAX_DAY_BYTES = 20L * 1024 * 1024
    private const val TV_UPLOAD_MAX = 4L * 1024 * 1024
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ConcurrentLinkedQueue<String>()
    private var dir: File? = null
    private var app: Context? = null
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile var screen = "Start"

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        dir = File(ctx.filesDir, "actlog").apply { mkdirs() }
        prune()
        io.launch { while (isActive) { delay(1500); flush() } }
        if (BuildConfig.IS_TV) io.launch { while (isActive) { delay(120_000); runCatching { uploadTv() } } }
        // Every device of every account: errors, crashes, freezes and play summaries go to the developer's cloud logs
        // (Developer's Corner › Activity log › All accounts), kept 15 days.
        io.launch { delay(20_000); while (isActive) { runCatching { uploadImportant() }; delay(180_000) } }
        // State changes worth a line: sync, Watch together, call, VPN.
        io.launch { Cloud.status.collect { if (it.isNotBlank()) add("SYNC", it) } }
        io.launch { Together.code.collect { add("ROOM", if (it == null) "not in a room" else "room $it · ${if (Together.isHost) "host" else "joined"}") } }
        io.launch { var last = emptySet<String>(); Together.members.collect { m -> val now = m.values.map { it.name }.toSet(); (now - last).forEach { add("ROOM", "$it is here") }; (last - now).forEach { add("ROOM", "$it left") }; last = now } }
        io.launch { Together.waitingFor.collect { if (it != null) add("ROOM", "waiting for $it (buffering)") } }
        io.launch { var lp: Boolean? = null; var lu = ""; Together.remote.collect { st -> if (st != null && st.by != Together.me && (st.playing != lp || st.url != lu)) { lp = st.playing; lu = st.url; add("ROOM", "room says ${if (st.playing) "play" else "pause"} at ${st.pos / 1000}s") } } }
        io.launch { Call.micOn.collect { add("ROOM", "my mic ${if (it) "on" else "off"}") } }
        io.launch { Call.camOn.collect { add("ROOM", "my camera ${if (it) "on" else "off"}") } }
        io.launch { Vpn.state.collect { add("VPN", it.name.lowercase() + (Vpn.current.value?.let { s -> " · ${s.name}" } ?: "") + Vpn.message.value.takeIf { m -> m.isNotBlank() }?.let { m -> " · $m" }.orEmpty()) } }
        add("APP", "opened · ${BuildConfig.VERSION_NAME} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
    }

    /** One line: [type] TAP, KEY, SCREEN, NET, PLAY, ROOM, SYNC, VPN, ERROR, APP. */
    fun add(type: String, msg: String) {
        if (app == null) return
        queue.add("${timeFmt.format(Date())} ${type.padEnd(6)} ${mask(msg).replace('\n', ' ')}")
    }

    private val maskRx = listOf(
        Regex("(?i)(token|auth|key|apikey|api_key|sig|signature|password|pass|secret|session)=([^&\\s]+)") to "$1=•••",
        Regex("(?i)(auth_token|refresh_token|access_token)[\"'%:A-Za-z0-9]*?([A-Za-z0-9._-]{12,})") to "$1:•••",
        Regex("(?i)pengu\\.uk/[^/\\s]{20,}/") to "pengu.uk/•••/",
        Regex("/[A-Za-z0-9_\\-%]{48,}") to "/•••",
    )
    fun mask(s: String): String = maskRx.fold(s) { acc, (rx, r) -> rx.replace(acc, r) }

    private fun today() = dayFmt.format(Date())
    fun fileFor(day: String) = File(dir, "$day.log")

    private fun flush() {
        val d = dir ?: return
        if (queue.isEmpty()) return
        val f = fileFor(today())
        if (f.length() > MAX_DAY_BYTES) { queue.clear(); return }
        val sb = StringBuilder()
        while (true) { val l = queue.poll() ?: break; sb.append(l).append('\n') }
        runCatching { f.appendText(sb.toString()) }
        if (!d.exists()) d.mkdirs()
    }

    /** Days older than 3 are deleted (on this device and, from the phone, in the account). */
    private fun prune() {
        val keep = (0 until KEEP_DAYS).map { dayFmt.format(Date(System.currentTimeMillis() - it * 86_400_000L)) }.toSet()
        dir?.listFiles()?.forEach { f -> if (f.name.removeSuffix(".log") !in keep) f.delete() }
    }

    fun days(): List<String> { flush(); return dir?.listFiles()?.map { it.name.removeSuffix(".log") }?.sortedDescending().orEmpty() }
    fun read(day: String): String = runCatching { fileFor(day).readText() }.getOrDefault("")

    fun clear() { dir?.listFiles()?.forEach { it.delete() } }

    /** The app is crashing: written straight away (the queue wouldn't be flushed). */
    fun crash(text: String) { runCatching { flush(); fileFor(today()).appendText("${timeFmt.format(Date())} ERROR  crash · ${text.take(4000).replace("\n", " | ")}\n") } }

    /** All 3 days of this device in one text (for Share). */
    fun allText(): String { flush(); return days().sorted().joinToString("\n") { d -> "===== $d =====\n" + read(d) } }

    // ── Taps and remote keys, with the button they hit ──

    fun onTouch(root: View, e: MotionEvent) {
        if (e.actionMasked != MotionEvent.ACTION_UP) return
        val label = runCatching { labelAt(root, e.x, e.y) }.getOrNull()
        add("TAP", "$screen › ${label ?: "?"} (${e.x.toInt()},${e.y.toInt()})")
    }

    fun onKey(root: View, e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_UP) return
        val name = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
        val focus = runCatching { focusedLabel(root) }.getOrNull()
        add("KEY", "$name · $screen" + (focus?.let { " › $it" } ?: ""))
    }

    private fun label(n: androidx.compose.ui.semantics.SemanticsNode): String? {
        val c = n.config
        val d = c.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { null }?.firstOrNull()
        val t = c.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString(" ") { it.text }
        // Typed text is never logged.
        if (c.contains(androidx.compose.ui.semantics.SemanticsProperties.EditableText) || c.contains(androidx.compose.ui.semantics.SemanticsProperties.Password)) return "text box"
        return (d ?: t)?.trim()?.takeIf { it.isNotEmpty() }?.take(60)
    }

    private fun rootNode(root: View): androidx.compose.ui.semantics.SemanticsNode? =
        (findCompose(root) as? androidx.compose.ui.platform.ViewRootForTest)?.semanticsOwner?.rootSemanticsNode

    /** The labelled button / text under the finger (window coordinates). */
    private fun labelAt(root: View, x: Float, y: Float): String? {
        val r = rootNode(root) ?: return null
        var best: String? = null
        var bestArea = Float.MAX_VALUE
        var clickBest: String? = null
        var clickArea = Float.MAX_VALUE
        val t0 = SystemClock.uptimeMillis()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode, depth: Int) {
            if (depth > 60 || SystemClock.uptimeMillis() - t0 > 30) return
            val b = n.boundsInWindow
            if (!b.contains(androidx.compose.ui.geometry.Offset(x, y))) return
            val area = b.width * b.height
            label(n)?.let { l ->
                if (area < bestArea) { best = l; bestArea = area }
                if (n.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) && area < clickArea) { clickBest = l; clickArea = area }
            }
            n.children.forEach { walk(it, depth + 1) }
        }
        walk(r, 0)
        return clickBest ?: best
    }

    /** What the remote's focus is on. */
    private fun focusedLabel(root: View): String? {
        val r = rootNode(root) ?: return null
        val t0 = SystemClock.uptimeMillis()
        fun find(n: androidx.compose.ui.semantics.SemanticsNode, depth: Int): androidx.compose.ui.semantics.SemanticsNode? {
            if (depth > 60 || SystemClock.uptimeMillis() - t0 > 30) return null
            if (n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Focused) { null } == true) return n
            n.children.forEach { c -> find(c, depth + 1)?.let { return it } }
            return null
        }
        val f = find(r, 0) ?: return null
        fun any(n: androidx.compose.ui.semantics.SemanticsNode, d: Int): String? = label(n) ?: if (d > 6) null else n.children.firstNotNullOfOrNull { any(it, d + 1) }
        return any(f, 0)
    }

    private fun findCompose(v: View): View? {
        if (v is androidx.compose.ui.platform.ViewRootForTest) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findCompose(v.getChildAt(i))?.let { return it }
        return null
    }

    // ── Network: every request (images left out), its answer and time ──

    object Net : Interceptor {
        private val imgRx = Regex("(?i)\\.(jpe?g|png|webp|gif|svg|avif)(\\?|$)|image\\.tmdb\\.org|images\\.metahub|/t/p/w\\d+/|/poster/|/background/|/logo/")
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val u = req.url
            val skip = imgRx.containsMatchIn(u.toString()) || u.host.endsWith("firebaseio.com") || u.host.endsWith("firebasedatabase.app")
            val t0 = SystemClock.elapsedRealtime()
            val what = "${req.method} ${u.host}${u.encodedPath.take(90)}"
            return try {
                val r = chain.proceed(req)
                if (!skip) add("NET", "$what · ${r.code} · ${SystemClock.elapsedRealtime() - t0} ms" + (r.body?.contentLength()?.takeIf { it > 0 }?.let { " · ${it / 1024} KB" } ?: ""))
                r
            } catch (e: Exception) {
                if (!skip) add("NET", "$what · failed (${e.javaClass.simpleName}: ${e.message?.take(80)}) · ${SystemClock.elapsedRealtime() - t0} ms")
                throw e
            }
        }
    }

    // ── TV → account (so the phone can read it); phone ← account ──

    private fun devId(): String {
        val p = app!!.getSharedPreferences("actlog", Context.MODE_PRIVATE)
        return p.getString("dev", null) ?: ((if (BuildConfig.IS_TV) "tv-" else "ph-") + java.util.UUID.randomUUID().toString().take(6)).also { p.edit().putString("dev", it).apply() }
    }

    // ── Cloud logs for the developer (all accounts) ──

    const val CLOUD_KEEP_DAYS = 15
    private val importantRx = Regex("(?i)(\\bERROR\\b|crash|app ended|froze|not responding|exception|failed|refused|can't play|cannot play|no streams|no links|nothing found|first picture|= ✓|stopped|gave up|timeout|timed out|HTTP [45]\\d\\d|wake word|live (closed|failed)|APP +opened)")

    /** Sends the important lines written since the last upload (errors, crashes, play results) to xshared/logs. */
    private suspend fun uploadImportant() {
        flush()
        val user = Cloud.user.value ?: return
        val t = Cloud.token() ?: return
        val p = app!!.getSharedPreferences("actlog", Context.MODE_PRIVATE)
        val day = today()
        val f = fileFor(day)
        val sent = if (p.getString("cUpDay", "") == day) p.getLong("cUpAt", 0) else 0L
        val len = f.length()
        val base = XNs.url("logs/${user.uid}/${devId()}").removeSuffix(".json")
        // once a day: days older than 15 are deleted
        if (p.getString("cPruned", "") != day) {
            runCatching {
                val txt = Api.get("$base/days.json?shallow=true&auth=$t")
                val keys = runCatching { Api.json.parseToJsonElement(txt).jsonObject.keys }.getOrDefault(emptySet())
                val keep = (0 until CLOUD_KEEP_DAYS).map { dayFmt.format(Date(System.currentTimeMillis() - it * 86_400_000L)) }.toSet()
                keys.filter { it !in keep }.forEach { old -> Api.http.newCall(Request.Builder().url("$base/days/$old.json?auth=$t").delete().build()).execute().close() }
            }
            p.edit().putString("cPruned", day).apply()
        }
        if (len <= sent) return
        val chunk = runCatching { f.inputStream().use { it.skip(sent); it.readBytes() } }.getOrNull()?.decodeToString() ?: return
        val keep = chunk.lines().filter { it.isNotBlank() && importantRx.containsMatchIn(it) }.joinToString("\n").take(120_000)
        if (keep.isNotBlank()) {
            val body = buildJsonObject { put("t", keep); put("at", System.currentTimeMillis()) }
            val ok = Api.http.newCall(Request.Builder().url("$base/days/$day.json?auth=$t").post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { it.isSuccessful }
            if (!ok) return
        }
        p.edit().putString("cUpDay", day).putLong("cUpAt", len).apply()
        val info = buildJsonObject {
            put("model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" + if (BuildConfig.IS_TV) " (TV)" else "")
            put("os", "Android ${android.os.Build.VERSION.RELEASE}"); put("ver", BuildConfig.VERSION_NAME)
            put("who", user.email ?: user.uid); put("at", System.currentTimeMillis())
        }
        Api.http.newCall(Request.Builder().url("$base/info.json?auth=$t").put(info.toString().toRequestBody("application/json".toMediaType())).build()).execute().close()
    }

    /** Developer: every device of every account that sent logs: (uid/dev, label). */
    suspend fun allAccountsDevices(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext emptyList()
        val root = XNs.url("logs").removeSuffix(".json")
        val uids = runCatching { Api.json.parseToJsonElement(Api.get("$root.json?shallow=true&auth=$t")).jsonObject.keys.toList() }.getOrDefault(emptyList())
        uids.flatMap { uid ->
            val devs = runCatching { Api.json.parseToJsonElement(Api.get("$root/$uid.json?shallow=true&auth=$t")).jsonObject.keys.toList() }.getOrDefault(emptyList())
            devs.mapNotNull { d ->
                val o = runCatching { Api.json.parseToJsonElement(Api.get("$root/$uid/$d/info.json?auth=$t")).jsonObject }.getOrNull()
                val at = o?.get("at")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val label = listOfNotNull(o?.get("model")?.jsonPrimitive?.contentOrNull, o?.get("who")?.jsonPrimitive?.contentOrNull?.substringBefore('@'), o?.get("ver")?.jsonPrimitive?.contentOrNull).joinToString(" · ").ifBlank { d }
                Triple("x:$uid/$d", label, at)
            }
        }.sortedByDescending { it.third }.map { it.first to it.second }
    }

    /** Developer: one device's cloud log (15 days). */
    suspend fun allAccountsText(key: String): String = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext ""
        val path = key.removePrefix("x:")
        val txt = runCatching { Api.get(XNs.url("logs/$path/days", t)) }.getOrNull() ?: return@withContext ""
        val o = runCatching { Api.json.parseToJsonElement(txt).jsonObject }.getOrNull() ?: return@withContext ""
        o.entries.sortedBy { it.key }.joinToString("\n") { (day, v) ->
            "===== $day =====\n" + (runCatching { v.jsonObject.values.mapNotNull { e -> runCatching { e.jsonObject }.getOrNull() }
                .sortedBy { it["at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L }.joinToString("\n") { it["t"]?.jsonPrimitive?.contentOrNull.orEmpty() } }.getOrDefault(""))
        }
    }

    private suspend fun uploadTv() {
        flush()
        val uid = Cloud.user.value?.uid ?: return
        val t = Cloud.token() ?: return
        val p = app!!.getSharedPreferences("actlog", Context.MODE_PRIVATE)
        val day = today()
        val f = fileFor(day)
        val sent = if (p.getString("upDay", "") == day) p.getLong("upAt", 0) else 0L
        val len = f.length()
        if (len <= sent || sent > TV_UPLOAD_MAX) return
        val chunk = runCatching { f.inputStream().use { it.skip(sent); it.readBytes() } }.getOrNull()?.decodeToString() ?: return
        val base = "${CloudConfig.DB_URL}/users/$uid/logs/${devId()}"
        val name = "${android.os.Build.MODEL} (TV)"
        val body = buildJsonObject { put("t", chunk.take(600_000)); put("at", System.currentTimeMillis()) }
        val ok = Api.http.newCall(Request.Builder().url("$base/days/$day.json?auth=$t").post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { it.isSuccessful }
        if (ok) {
            p.edit().putString("upDay", day).putLong("upAt", len).apply()
            Api.http.newCall(Request.Builder().url("$base/name.json?auth=$t").put("\"$name\"".toRequestBody("application/json".toMediaType())).build()).execute().close()
        }
    }

    /** Devices that sent their log to this account: (id, name). */
    suspend fun cloudDevices(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val uid = Cloud.user.value?.uid ?: return@withContext emptyList()
        val t = Cloud.token() ?: return@withContext emptyList()
        val txt = runCatching { Api.get("${CloudConfig.DB_URL}/users/$uid/logs.json?shallow=true&auth=$t") }.getOrNull() ?: return@withContext emptyList()
        val ids = runCatching { Api.json.parseToJsonElement(txt).jsonObject.keys.toList() }.getOrDefault(emptyList())
        ids.map { id -> id to (runCatching { Api.json.parseToJsonElement(Api.get("${CloudConfig.DB_URL}/users/$uid/logs/$id/name.json?auth=$t")).jsonPrimitive.contentOrNull }.getOrNull() ?: id) }
    }

    /** A device's log from the account (last 3 days); older days there are deleted. */
    suspend fun cloudText(dev: String): String = withContext(Dispatchers.IO) {
        val uid = Cloud.user.value?.uid ?: return@withContext ""
        val t = Cloud.token() ?: return@withContext ""
        val txt = runCatching { Api.get("${CloudConfig.DB_URL}/users/$uid/logs/$dev/days.json?auth=$t") }.getOrNull() ?: return@withContext ""
        val o = runCatching { Api.json.parseToJsonElement(txt).jsonObject }.getOrNull() ?: return@withContext ""
        val keep = (0 until KEEP_DAYS).map { dayFmt.format(Date(System.currentTimeMillis() - it * 86_400_000L)) }.toSet()
        o.keys.filter { it !in keep }.forEach { old ->
            runCatching { Api.http.newCall(Request.Builder().url("${CloudConfig.DB_URL}/users/$uid/logs/$dev/days/$old.json?auth=$t").delete().build()).execute().close() }
        }
        o.entries.filter { it.key in keep }.sortedBy { it.key }.joinToString("\n") { (day, v) ->
            "===== $day =====\n" + ((v as? JsonObject)?.values?.mapNotNull { c -> (c as? JsonObject)?.let { (it["at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L) to (it["t"]?.jsonPrimitive?.contentOrNull ?: "") } }
                ?.sortedBy { it.first }?.joinToString("") { it.second } ?: "")
        }
    }
}
