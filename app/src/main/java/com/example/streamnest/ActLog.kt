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
import kotlinx.coroutines.async
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
import okio.buffer
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
 * Activity log: everything StreamX does — every tap and remote key (with the screen and the button), screens opened,
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
    /** 0.9.1 (hostDown): the network in use now (home-network servers are judged per network). */
    fun netHandle(): Long = runCatching {
        (app?.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)?.activeNetwork?.networkHandle ?: 0L
    }.getOrDefault(0L)
    /**
     * 0.9.79 (phone): light logging by default — the per-plugin timings, memory every 5 s, ranking notes and the button
     * under every tap (a walk through the screen's tree) are written only with Developer's Corner › Detailed logs on.
     * Errors, playback, switches, sync and rooms are always written. The TV is unchanged (always detailed).
     */
    @Volatile var detailed = BuildConfig.IS_TV
    private val verboseTypes = setOf("PLUGIN", "NUVIOPLAY", "CSWARM", "RANK", "MBDIRECT", "TRAILER")
    fun turnDetailed(on: Boolean) {
        if (BuildConfig.IS_TV) return
        detailed = on
        app?.getSharedPreferences("actlog", Context.MODE_PRIVATE)?.edit()?.putBoolean("detailed", on)?.apply()
        add("APP", "detailed logs ${if (on) "on" else "off"}")
    }

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        if (!BuildConfig.IS_TV) detailed = ctx.getSharedPreferences("actlog", Context.MODE_PRIVATE).getBoolean("detailed", false)
        dir = File(ctx.filesDir, "actlog").apply { mkdirs() }
        prune()
        io.launch { while (isActive) { delay(1500); flush() } }
        if (BuildConfig.IS_TV) io.launch { while (isActive) { delay(120_000); TvIdle.waitQuiet(); runCatching { uploadTv() } } }
        // 0.9.68: the phone also removes old TV logs left under the account's synced data (every phone start downloaded them).
        if (!BuildConfig.IS_TV) io.launch { delay(45_000); runCatching { val uid = Cloud.user.value?.uid; val t = Cloud.token(); if (uid != null && t != null) dropOldTvLogs(uid, t) } }
        // Every device of every account: errors, crashes, freezes and play summaries go to the developer's cloud logs
        // (Developer's Corner › Activity log › All accounts), kept 15 days.
        io.launch { delay(20_000); while (isActive) { TvIdle.waitQuiet(); runCatching { uploadImportant() }; delay(180_000) } }
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
        if (!detailed && type in verboseTypes && !importantRx.containsMatchIn(msg)) return
        val line = mask(msg).replace('\n', ' ')
        queue.add("${timeFmt.format(Date())} ${type.padEnd(6)} $line")
        // 0.9.2: important lines also go into the next crash report (Crashlytics), so a crash shows what led to it.
        if (CrashReports.on) CrashReports.line(type, line, type in CrashReports.mirrorTypes || importantRx.containsMatchIn(line))
        // 0.9.73 (from Xstream): trouble on an old version → "Update to the latest" (shown later, never during playback).
        if (!BuildConfig.IS_TV && (type == "PLAYFAIL" || (type == "ERROR" && (msg.startsWith("player") || msg.contains("crash"))) || (type == "SWITCH" && msg.contains("failed"))))
            app?.let { runCatching { Updater.onTrouble(it) } }
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
    /**
     * TV 0.9.6: newest first — the days newest first, and inside a day the app sessions newest first (each one keeps its
     * own lines in time order), with a header naming the version. The latest is always at the top.
     */
    fun allText(): String {
        flush()
        return days().sortedDescending().joinToString("\n") { d ->
            val sessions = ArrayList<StringBuilder>()
            read(d).lineSequence().forEach { line ->
                if (sessions.isEmpty() || " APP    opened · " in line) sessions.add(StringBuilder())
                if (line.isNotEmpty()) sessions.last().append(line).append('\n')
            }
            "===== $d =====\n" + sessions.asReversed().filter { it.isNotEmpty() }.joinToString("\n") { sb ->
                val first = sb.lineSequence().firstOrNull().orEmpty()
                val head = Regex("^(\\S+) APP    opened · ([^·]+)").find(first)?.let { "----- ${it.groupValues[2].trim()} · opened ${it.groupValues[1].substringBefore('.')} -----\n" }.orEmpty()
                head + sb
            }
        }
    }

    // ── Taps and remote keys, with the button they hit ──

    fun onTouch(root: View, e: MotionEvent) {
        if (e.actionMasked != MotionEvent.ACTION_UP) return
        val label = if (detailed) runCatching { labelAt(root, e.x, e.y) }.getOrNull() else "·"
        add("TAP", "$screen › ${label ?: "?"} (${e.x.toInt()},${e.y.toInt()})")
    }

    fun onKey(root: View, e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_UP) return
        val name = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
        val focus = runCatching { focusedLabel(root) }.getOrNull()
        add("KEY", "$name · $screen" + (focus?.let { " › $it" } ?: ""))
        if (BuildConfig.IS_TV) TvSmall.lastKey = "$name · $screen" + (focus?.let { " › ${it.take(40)}" } ?: "")
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
        private val mediaRx = Regex("(?i)\\.(m4s|ts|mp4|mkv|m3u8|mpd|aac|vtt|srt)$|/dash/|/hls/|/videoplayback")
        private val imgRx = Regex("(?i)\\.(jpe?g|png|webp|gif|svg|avif)(\\?|$)|image\\.tmdb\\.org|images\\.metahub|/t/p/w\\d+/|/poster/|/background/|/logo/")
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val u = req.url
            val skip = imgRx.containsMatchIn(u.toString()) || u.host.endsWith("firebaseio.com") || u.host.endsWith("firebasedatabase.app")
            val t0 = SystemClock.elapsedRealtime()
            val what = "${req.method} ${u.host}${u.encodedPath.take(90)}"
            // 0.9.1 (hostDown): a server that can't be reached twice in a row (while others answer) is skipped for
            // 10 minutes, so dead mirrors stop costing 15–45 s on every search. Off = every request as before.
            val hd = Feature.hostDown && !mediaRx.containsMatchIn(u.encodedPath)
            if (hd) HostDown.check(u.host)?.let { left ->
                if (!skip) add("NET", "$what · skipped (server down, retry in ${left / 1000} s)")
                throw java.io.IOException("skipped: ${u.host} is down")
            }
            return try {
                val r = chain.proceed(req)
                if (hd) HostDown.ok(u.host)
                if (!skip) add("NET", "$what · ${r.code} · ${SystemClock.elapsedRealtime() - t0} ms" + (r.body?.contentLength()?.takeIf { it > 0 }?.let { " · ${it / 1024} KB" } ?: ""))
                if (BuildConfig.IS_TV) capPage(r, what) else r
            } catch (e: Exception) {
                if (!skip) add("NET", "$what · failed (${e.javaClass.simpleName}: ${e.message?.take(80)}) · ${SystemClock.elapsedRealtime() - t0} ms")
                if (hd) HostDown.failed(u.host, e, SystemClock.elapsedRealtime() - t0)
                throw e
            }
        }
    }

    /**
     * TV 0.9.1 (from Xstream 0.8.14): a web page / JSON answer over 5 MB is not read (a normal plugin page is under 1 MB; a
     * huge broken page read into memory takes many times its size). Video, audio, subtitles and pictures are never touched.
     */
    private const val PAGE_MAX = 5L * 1024 * 1024
    private fun capPage(r: Response, what: String): Response {
        val body = r.body ?: return r
        val type = body.contentType()?.toString()?.lowercase().orEmpty()
        if (!(type.startsWith("text/html") || type.startsWith("application/json"))) return r
        if (body.contentLength() > PAGE_MAX) { body.close(); add("NET", "$what · page too big (${body.contentLength() / 1048576} MB) — not read"); throw java.io.IOException("page too big") }
        val limited = object : okio.ForwardingSource(body.source()) {
            var n = 0L
            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                val got = super.read(sink, byteCount)
                if (got > 0) { n += got; if (n > PAGE_MAX) { add("NET", "$what · page over 5 MB — stopped reading"); throw java.io.IOException("page too big") } }
                return got
            }
        }
        val nb = object : okhttp3.ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            private val src = limited.buffer()
            override fun source(): okio.BufferedSource = src
        }
        return r.newBuilder().body(nb).build()
    }

    // ── TV → account (so the phone can read it); phone ← account ──

    private fun devId(): String {
        val p = app!!.getSharedPreferences("actlog", Context.MODE_PRIVATE)
        return p.getString("dev", null) ?: ((if (BuildConfig.IS_TV) "tv-" else "ph-") + java.util.UUID.randomUUID().toString().take(6)).also { p.edit().putString("dev", it).apply() }
    }

    // ── Cloud logs for the developer (all accounts) ──

    const val CLOUD_KEEP_DAYS = 15
    /**
     * Every account's cloud logs. "shared/" only takes writes from the developer's account, so friends' logs never
     * arrived; "xshared/logs" takes every signed-in account's (as in Xstream). Each device says its app and version.
     */
    private val LOG_ROOT = "${CloudConfig.DB_URL}/xshared/logs"
    private val importantRx = Regex("(?i)(\\bTOUR\\b|\\bTOGETHER\\b|\\bROOM\\b|\\bCALL\\b|length check|\\bERROR\\b|crash|app ended|froze|not responding|exception|failed|refused|can't play|cannot play|no streams|no links|nothing found|first picture|= ✓|stopped|gave up|timeout|timed out|HTTP [45]\\d\\d|wake word|live (closed|failed)|APP +opened|SWITCH|ladder|swap:|wrong title|MBDIRECT|SITE |plugins changed|NOSTREAM|PLAYFAIL|buffering at|playing again after|Watch next|93 %|episode ended|4K:|start · |opening the player|nothing played|screen rebuilt|display change|re-attached)")

    /** Sends the important lines written since the last upload (errors, crashes, play results) to shared/logs. */
    /** "Fire TV Stick 4K (TV)" instead of Amazon's codes (AFTMM…), so the TV is easy to find in the developer list. */
    fun tvName(): String {
        val m = android.os.Build.MODEL.orEmpty()
        val fire = mapOf("AFTMM" to "Fire TV Stick 4K", "AFTKA" to "Fire TV Stick 4K Max", "AFTKM" to "Fire TV Stick 4K (2nd gen)",
            "AFTKRT" to "Fire TV Stick 4K Max (2nd gen)", "AFTSSS" to "Fire TV Stick (3rd gen)", "AFTSS" to "Fire TV Stick Lite",
            "AFTT" to "Fire TV Stick (2nd gen)", "AFTR" to "Fire TV Cube (2nd gen)", "AFTGAZL" to "Fire TV Cube (3rd gen)")
        val name = fire[m] ?: if (m.startsWith("AFT")) "Fire TV ($m)" else "${android.os.Build.MANUFACTURER} $m"
        return "$name (TV)"
    }

    /** 0.9.75 (from Xstream): right before signing out, what hasn't been sent yet goes now, with the account that is leaving. */
    fun sendBeforeSignOut(u: CloudUser, token: String?) {
        if (token == null) return
        io.launch { runCatching { uploadImportant(u, token) } }
    }

    private suspend fun uploadImportant(u0: CloudUser? = null, t0: String? = null) {
        flush()
        val user = u0 ?: Cloud.user.value ?: return
        val t = t0 ?: Cloud.token() ?: return
        val p = app!!.getSharedPreferences("actlog", Context.MODE_PRIVATE)
        val day = today()
        val f = fileFor(day)
        val sent = if (p.getString("cUpDay", "") == day) p.getLong("cUpAt", 0) else 0L
        val len = f.length()
        val base = "$LOG_ROOT/${user.uid}/${devId()}"
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
        // TV 0.8.3: the TV sends its whole log (only cancelled requests left out), so the phone's developer section can read it.
        val keep = chunk.lines().filter { it.isNotBlank() && (if (BuildConfig.IS_TV) !it.contains("Canceled") else importantRx.containsMatchIn(it)) }.joinToString("\n").take(120_000)
        if (keep.isNotBlank()) {
            val body = buildJsonObject { put("t", keep); put("at", System.currentTimeMillis()) }
            val ok = Api.http.newCall(Request.Builder().url("$base/days/$day.json?auth=$t").post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { it.isSuccessful }
            if (!ok) return
        }
        p.edit().putString("cUpDay", day).putLong("cUpAt", len).apply()
        val info = buildJsonObject {
            put("model", if (BuildConfig.IS_TV) tvName() else "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            put("os", "Android ${android.os.Build.VERSION.RELEASE}"); put("ver", BuildConfig.VERSION_NAME)
            put("who", user.email ?: user.uid); put("at", System.currentTimeMillis())
            put("app", appName())
        }
        Api.http.newCall(Request.Builder().url("$base/info.json?auth=$t").put(info.toString().toRequestBody("application/json".toMediaType())).build()).execute().close()
    }

    /** Developer: every device of every account that sent logs: (uid/dev, label). */
    suspend fun allAccountsDevices(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext emptyList()
        val root = LOG_ROOT
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

    /** 0.9.73 (from Xstream): which app wrote a log, saved with every upload. */
    fun appName(): String = if (BuildConfig.IS_TV) "${Brand.NAME} TV" else "${Brand.NAME} Mobile"

    /** One entry of the developer's log browser: a device + account + version (the same ones merged). */
    data class LogDev(val keys: List<String>, val app: String, val ver: String, val model: String, val who: String, val at: Long)

    /** Developer: every account's devices, sorted into app › version › device (one entry per model + account + version). */
    suspend fun logIndex(): List<LogDev> = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext emptyList()
        val root = LOG_ROOT
        val uids = runCatching { Api.json.parseToJsonElement(Api.get("$root.json?shallow=true&auth=$t")).jsonObject.keys.toList() }.getOrDefault(emptyList())
        val raw = kotlinx.coroutines.coroutineScope {
            uids.map { uid ->
                async {
                    val devs = runCatching { Api.json.parseToJsonElement(Api.get("$root/$uid.json?shallow=true&auth=$t")).jsonObject.keys.toList() }.getOrDefault(emptyList())
                    devs.mapNotNull { d ->
                        val o = runCatching { Api.json.parseToJsonElement(Api.get("$root/$uid/$d/info.json?auth=$t")).jsonObject }.getOrNull() ?: return@mapNotNull null
                        fun f(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val model = f("model")
                        val ver = f("ver").ifBlank { "?" }
                        // Older uploads have no "app": tell it from the version / model.
                        val app = f("app").ifBlank { when {
                            ver.startsWith("TV") -> "StreamX TV"
                            model.endsWith("(TV)") -> "Xstream TV"
                            ver.startsWith("1.0") -> "Xstream Live"
                            ver.startsWith("0.9") -> "StreamX Mobile"
                            else -> "Xstream Mobile"
                        } }
                        LogDev(listOf("x:$uid/$d"), app, ver, model.removeSuffix(" (TV)"), f("who").ifBlank { uid.take(8) }, f("at").toLongOrNull() ?: 0L)
                    }
                }
            }.map { it.await() }.flatten()
        }
        raw.groupBy { Triple(it.app, it.ver, it.model + "|" + it.who) }.map { (_, g) -> g.first().copy(keys = g.flatMap { it.keys }, at = g.maxOf { it.at }) }
            .sortedByDescending { it.at }
    }

    /** Developer: the merged log of one browser entry. */
    suspend fun logOf(d: LogDev): String = d.keys.map { allAccountsText(it) }.filter { it.isNotBlank() }.joinToString("\n")

    /** Developer: one device's cloud log (15 days). */
    suspend fun allAccountsText(key: String): String = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext ""
        val path = key.removePrefix("x:")
        val txt = runCatching { Api.get("$LOG_ROOT/$path/days.json?auth=$t") }.getOrNull() ?: return@withContext ""
        val o = runCatching { Api.json.parseToJsonElement(txt).jsonObject }.getOrNull() ?: return@withContext ""
        o.entries.sortedBy { it.key }.joinToString("\n") { (day, v) ->
            "===== $day =====\n" + (runCatching { v.jsonObject.values.mapNotNull { e -> runCatching { e.jsonObject }.getOrNull() }
                .sortedBy { it["at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L }.joinToString("\n") { it["t"]?.jsonPrimitive?.contentOrNull.orEmpty() } }.getOrDefault(""))
        }
    }

    // TV 0.9.12: the TV's own log lives outside the account's synced data. Under users/<uid> every 600 KB upload was sent
    // to every device of the account, and each app start downloaded all of it (about 200 MB of memory for a moment).
    private fun tvLogRoot(uid: String) = "${CloudConfig.DB_URL}/xshared/tvlogs/$uid"

    /** Once: the old copy under the account's synced data is removed. */
    private fun dropOldTvLogs(uid: String, t: String) {
        val p = app!!.getSharedPreferences("actlog", Context.MODE_PRIVATE)
        if (p.getBoolean("oldLogsGone", false)) return
        val ok = runCatching { Api.http.newCall(Request.Builder().url("${CloudConfig.DB_URL}/users/$uid/logs.json?auth=$t").delete().build()).execute().use { it.isSuccessful } }.getOrDefault(false)
        if (ok) { p.edit().putBoolean("oldLogsGone", true).apply(); add("APP", "TV log moved out of the synced account data") }
    }

    private suspend fun uploadTv() {
        flush()
        val uid = Cloud.user.value?.uid ?: return
        val t = Cloud.token() ?: return
        dropOldTvLogs(uid, t)
        val p = app!!.getSharedPreferences("actlog", Context.MODE_PRIVATE)
        val day = today()
        val f = fileFor(day)
        val sent = if (p.getString("upDay", "") == day) p.getLong("upAt", 0) else 0L
        val len = f.length()
        if (len <= sent || sent > TV_UPLOAD_MAX) return
        val chunk = runCatching { f.inputStream().use { it.skip(sent); it.readBytes() } }.getOrNull()?.decodeToString() ?: return
        val base = "${tvLogRoot(uid)}/${devId()}"
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
        val txt = runCatching { Api.get("${tvLogRoot(uid)}.json?shallow=true&auth=$t") }.getOrNull() ?: return@withContext emptyList()
        val ids = runCatching { Api.json.parseToJsonElement(txt).jsonObject.keys.toList() }.getOrDefault(emptyList())
        ids.map { id -> id to (runCatching { Api.json.parseToJsonElement(Api.get("${tvLogRoot(uid)}/$id/name.json?auth=$t")).jsonPrimitive.contentOrNull }.getOrNull() ?: id) }
    }

    /** A device's log from the account (last 3 days); older days there are deleted. */
    suspend fun cloudText(dev: String): String = withContext(Dispatchers.IO) {
        val uid = Cloud.user.value?.uid ?: return@withContext ""
        val t = Cloud.token() ?: return@withContext ""
        val txt = runCatching { Api.get("${tvLogRoot(uid)}/$dev/days.json?auth=$t") }.getOrNull() ?: return@withContext ""
        val o = runCatching { Api.json.parseToJsonElement(txt).jsonObject }.getOrNull() ?: return@withContext ""
        val keep = (0 until KEEP_DAYS).map { dayFmt.format(Date(System.currentTimeMillis() - it * 86_400_000L)) }.toSet()
        o.keys.filter { it !in keep }.forEach { old ->
            runCatching { Api.http.newCall(Request.Builder().url("${tvLogRoot(uid)}/$dev/days/$old.json?auth=$t").delete().build()).execute().close() }
        }
        o.entries.filter { it.key in keep }.sortedBy { it.key }.joinToString("\n") { (day, v) ->
            "===== $day =====\n" + ((v as? JsonObject)?.values?.mapNotNull { c -> (c as? JsonObject)?.let { (it["at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L) to (it["t"]?.jsonPrimitive?.contentOrNull ?: "") } }
                ?.sortedBy { it.first }?.joinToString("") { it.second } ?: "")
        }
    }
}


/** Servers that can't be reached: after 2 connection failures in a row they are skipped for 10 minutes.
 *  0.9.1: on the shared code behind Feature.hostDown (ActLog.Net calls it only when on). */
object HostDown {
    private val fails = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val recent = java.util.ArrayDeque<Pair<String, Long>>()
    /** The app's own servers and the local network are never skipped. */
    private fun local(h: String) = h == "localhost" || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") ||
        Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(h) || h == "::1"
    private val until = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var lastOkAt = 0L
    private val never = Regex("(?i)(firebase|googleapis|google\\.com|gstatic|themoviedb|strem\\.io|github|tmdb|metahub|googlevideo|youtube)")
    /** Milliseconds left while [host] is skipped, or null. */
    /** 0.8.12: a home-network server (10.x / 192.168.x) that can't be reached here is skipped until the network changes
     *  (away from home Wi-Fi every search and subtitle request waited 4 s for it). */
    private fun lan(h: String) = local(h) && !h.startsWith("127.") && h != "localhost" && h != "::1"
    private val lanFails = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile private var lanNet = Long.MIN_VALUE
    private fun sameNet() { val id = ActLog.netHandle(); if (id != lanNet) { lanNet = id; lanFails.clear() } }

    fun check(host: String): Long? {
        if (lan(host)) { sameNet(); return if ((lanFails[host] ?: 0) >= 2) 60_000L else null }
        if (local(host)) return null
        val t = until[host] ?: return null
        val left = t - SystemClock.elapsedRealtime()
        if (left <= 0) { until.remove(host); fails.remove(host); return null }
        return left
    }
    fun ok(host: String) { fails.remove(host); if (lan(host)) lanFails.remove(host); lastOkAt = SystemClock.elapsedRealtime() }
    @Synchronized fun failed(host: String, e: Exception, ms: Long = 0) {
        // 0.8.15: "no address" (a DNS answer, often a blocked or slow lookup) never marks a server as down.
        if (e is java.net.UnknownHostException && !lan(host)) return
        val connect = e is java.net.ConnectException ||
            (e is java.net.SocketTimeoutException && e.message?.contains("failed to connect") == true) ||
            e is javax.net.ssl.SSLHandshakeException
        if (lan(host)) {
            sameNet()
            if (connect || e is java.net.SocketTimeoutException || (e is java.net.SocketException && ms >= 3_000)) {
                val n = (lanFails[host] ?: 0) + 1; lanFails[host] = n
                if (n == 2) ActLog.add("NET", "$host (home network server) can't be reached on this network — skipped until the network changes")
            }
            return
        }
        // 0.8.12: servers that reset every connection (blocked) or ask for a proxy password count too, after 3 in a row.
        val soft = (e is java.net.SocketException && e.message?.contains("reset", true) == true) ||
            (e is java.net.ProtocolException && e.message?.contains("407") == true)
        if ((!connect && !soft) || never.containsMatchIn(host) || local(host)) return
        val now = SystemClock.elapsedRealtime()
        // Only when the internet itself works (something else answered in the last minute)…
        if (now - lastOkAt > 60_000) return
        // …and not when many servers fail together (that is the phone's network dropping, not the servers): 0.8.6 blocked
        // ~20 servers at once at 19:16 and even the app's own server (127.0.0.1), so The Office failed.
        recent.addLast(host to now)
        while (recent.isNotEmpty() && now - recent.first().second > 5_000) recent.removeFirst()
        if (recent.map { it.first }.distinct().size >= 3) { fails.clear(); return }
        val n = (fails[host] ?: 0) + 1
        fails[host] = n
        if (n >= (if (soft) 3 else 2) && until[host] == null) {
            until[host] = SystemClock.elapsedRealtime() + 10 * 60_000L
            ActLog.add("NET", "$host can't be reached twice — skipped for 10 min")
        }
    }
}
