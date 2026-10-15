package com.example.streamnest

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The Xstream Firebase project (public identifiers — the database rules are what protect the data). */
object CloudConfig {
    const val API_KEY = "AIzaSyCO9LSPvlSVMYBA0E9Z7M9yTg8Dg1zyUns"
    const val PROJECT_ID = "streamx-d976a"
    const val DB_URL = "https://streamx-d976a-default-rtdb.asia-southeast1.firebasedatabase.app"
    const val WEB_CLIENT_ID = "51125528685-qfne7qo3qihoo3ke6hl1ehi50s6ot03h.apps.googleusercontent.com"
}

/** What the cloud needs from the app: read a field's current local value, and apply a value that came from the cloud. */
interface CloudHost {
    /** All profile ids on this device. */
    fun cloudProfileIds(): List<String>
    /** Local value of an app field ("profiles", "appTexts", "logo", "avatar_<id>") or a profile field. null = nothing. */
    fun cloudRead(scope: String, pid: String?, field: String): String?
    /** Apply a value from the cloud (null = deleted there). Must not mark anything dirty. */
    fun cloudApply(scope: String, pid: String?, field: String, value: String?)
}

data class CloudUser(val uid: String, val email: String, val verified: Boolean, val google: Boolean)

/**
 * Sign-in (Firebase Auth, REST) and live sync (Firebase Realtime Database, REST + server-sent events — works on Fire TV
 * without Google Play Services).
 *
 * Cloud layout:  users/{uid}/app/{field} = {v, at}     users/{uid}/p/{profileId}/{field} = {v, at}
 * Every field is one string. The newer side wins, field by field ("at" = when it was changed).
 * Signing in on a device makes the cloud win for everything that already exists there.
 */
object Cloud {
    val PROFILE_FIELDS = listOf("set", "dev_phone", "dev_tv", "addons", "cols", "list", "prog", "done", "votes")
    val DEVICE_FIELD = if (BuildConfig.IS_TV) "dev_tv" else "dev_phone"

    val user = MutableStateFlow<CloudUser?>(null)
    /** Short status line for the account page ("Synced 10:42", "Offline — will retry"…). */
    val status = MutableStateFlow("")
    val autoSync = MutableStateFlow(true)
    /** The sign-in screen was answered (signed in, or "Skip for now"). */
    val decided = MutableStateFlow(false)

    private lateinit var prefs: SharedPreferences
    private lateinit var atPrefs: SharedPreferences
    private var host: CloudHost? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idToken: String? = null
    private var idExp = 0L
    private var refreshToken: String? = null
    private val tokenLock = Mutex()
    private val dirty = LinkedHashSet<String>() // "app/field" or "p/pid/field"
    private val dirtyLock = Any()
    private var pushJob: Job? = null
    private var streamJob: Job? = null
    private var started = false
    /**
     * Just signed in on this device: nothing is uploaded until the account's cloud copy has been loaded here first.
     * (Before 0.9.40 the fresh app's own defaults — Cinemeta only, empty Quick Start — could be uploaded first and
     * replace the account's addons and settings.)
     */
    @Volatile private var waitingForCloud = false

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http: OkHttpClient get() = Api.http
    private val sse: OkHttpClient by lazy { Api.http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build() }

    fun init(ctx: Context) {
        if (started) return
        started = true
        prefs = ctx.getSharedPreferences("cloud", Context.MODE_PRIVATE)
        atPrefs = ctx.getSharedPreferences("cloud_at", Context.MODE_PRIVATE)
        autoSync.value = prefs.getBoolean("auto", true)
        decided.value = prefs.getBoolean("decided", false)
        refreshToken = prefs.getString("rt", null)?.let { Vault.dec(it) }
        val uid = prefs.getString("uid", null)
        waitingForCloud = prefs.getBoolean("waitCloud", false)
        if (refreshToken != null && uid != null) {
            user.value = CloudUser(uid, prefs.getString("email", "") ?: "", prefs.getBoolean("verified", false), prefs.getBoolean("google", false))
            decided.value = true
        }
    }

    fun attach(h: CloudHost) { host = h; restart() }
    fun detach(h: CloudHost) { if (host === h) { host = null; stopSync() } }

    fun skip() { decided.value = true; prefs.edit().putBoolean("decided", true).apply() }

    fun setAutoSync(on: Boolean) {
        autoSync.value = on
        prefs.edit().putBoolean("auto", on).apply()
        restart()
    }

    // ───────────── Auth (Firebase Identity Toolkit REST) ─────────────

    private suspend fun idt(method: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://identitytoolkit.googleapis.com/v1/accounts:$method?key=${CloudConfig.API_KEY}")
            .post(body.toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { Api.json.parseToJsonElement(text).jsonObject }.getOrElse { JsonObject(emptyMap()) }
            if (!r.isSuccessful) {
                val msg = (o["error"] as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "HTTP ${r.code}"
                throw CloudError(friendly(msg))
            }
            o
        }
    }

    class CloudError(msg: String) : Exception(msg)

    private fun friendly(code: String): String = when {
        code.startsWith("EMAIL_EXISTS") -> "An account with this email already exists. Sign in instead."
        code.startsWith("INVALID_LOGIN_CREDENTIALS") || code.startsWith("INVALID_PASSWORD") || code.startsWith("EMAIL_NOT_FOUND") ->
            "Wrong email or password."
        code.startsWith("WEAK_PASSWORD") -> "Use a password with at least 8 characters."
        code.startsWith("INVALID_EMAIL") -> "That email address doesn't look right."
        code.startsWith("TOO_MANY_ATTEMPTS") -> "Too many tries. Wait a few minutes and try again."
        code.startsWith("USER_DISABLED") -> "This account has been disabled."
        code.startsWith("OPERATION_NOT_ALLOWED") -> "This sign-in method isn't turned on in Firebase."
        code.startsWith("CREDENTIAL_TOO_OLD") || code.startsWith("TOKEN_EXPIRED") -> "Please sign in again first."
        else -> code.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }

    private val Throwaway = setOf(
        "mailinator.com", "10minutemail.com", "guerrillamail.com", "guerrillamail.net", "sharklasers.com", "yopmail.com",
        "temp-mail.org", "tempmail.com", "tempmail.net", "tempail.com", "trashmail.com", "getnada.com", "nada.email",
        "dispostable.com", "maildrop.cc", "throwawaymail.com", "fakeinbox.com", "mintemail.com", "emailondeck.com",
        "mohmal.com", "tmpmail.org", "tmpmail.net", "mailnesia.com", "spamgourmet.com", "burnermail.io", "moakt.com",
    )

    private fun checkEmail(email: String) {
        val e = email.trim().lowercase()
        if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(e)) throw CloudError("That email address doesn't look right.")
        if (e.substringAfter('@') in Throwaway) throw CloudError("Temporary email addresses can't be used. Use your real email.")
    }

    private fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

    private suspend fun onSignedIn(o: JsonObject, google: Boolean) {
        val uid = s(o, "localId") ?: throw CloudError("Sign-in failed")
        tokenLock.withLock {
            idToken = s(o, "idToken")
            idExp = System.currentTimeMillis() + ((s(o, "expiresIn")?.toLongOrNull() ?: 3600L) - 120L) * 1000L
            refreshToken = s(o, "refreshToken")
        }
        val email = s(o, "email") ?: ""
        val verified = google || lookupVerified()
        // A fresh sign-in: whatever the cloud already has wins over this device's data. Changes this device made before
        // (fresh-install defaults) are dropped, and nothing is uploaded until the cloud copy has arrived.
        atPrefs.edit().clear().apply()
        synchronized(dirtyLock) { dirty.clear() }
        waitingForCloud = true
        prefs.edit().putBoolean("waitCloud", true).apply()
        prefs.edit().putString("rt", refreshToken?.let { Vault.enc(it) }).putString("uid", uid).putString("email", email)
            .putBoolean("verified", verified).putBoolean("google", google).putBoolean("decided", true).apply()
        user.value = CloudUser(uid, email, verified, google)
        decided.value = true
        restart()
    }

    suspend fun signUp(email: String, password: String) {
        checkEmail(email)
        if (password.length < 8) throw CloudError("Use a password with at least 8 characters.")
        val o = idt("signUp", buildJsonObject { put("email", email.trim()); put("password", password); put("returnSecureToken", true) })
        onSignedIn(o, google = false)
        runCatching { sendVerification() }
    }

    suspend fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isEmpty()) throw CloudError("Enter your email and password.")
        val o = idt("signInWithPassword", buildJsonObject { put("email", email.trim()); put("password", password); put("returnSecureToken", true) })
        onSignedIn(o, google = false)
    }

    /** [googleIdToken] from Credential Manager (phone). */
    suspend fun signInWithGoogle(googleIdToken: String) {
        val o = idt("signInWithIdp", buildJsonObject {
            put("postBody", "id_token=$googleIdToken&providerId=google.com")
            put("requestUri", "http://localhost")
            put("returnSecureToken", true)
            put("returnIdpCredential", true)
        })
        onSignedIn(o, google = true)
    }

    suspend fun sendVerification() {
        val t = token() ?: throw CloudError("Please sign in again.")
        idt("sendOobCode", buildJsonObject { put("requestType", "VERIFY_EMAIL"); put("idToken", t) })
    }

    suspend fun sendPasswordReset(email: String) {
        checkEmail(email)
        idt("sendOobCode", buildJsonObject { put("requestType", "PASSWORD_RESET"); put("email", email.trim()) })
    }

    /** Sets (or changes) the account password — e.g. a Google account that also wants to sign in on the TV. */
    suspend fun setPassword(pw: String) {
        if (pw.length < 8) throw CloudError("Use a password with at least 8 characters.")
        val t = token() ?: throw CloudError("Please sign in again.")
        val o = idt("update", buildJsonObject { put("idToken", t); put("password", pw); put("returnSecureToken", true) })
        tokenLock.withLock {
            s(o, "idToken")?.let { idToken = it; idExp = System.currentTimeMillis() + 3_400_000L }
            s(o, "refreshToken")?.let { refreshToken = it; prefs.edit().putString("rt", Vault.enc(it)).apply() }
        }
    }

    private suspend fun lookupVerified(): Boolean {
        val t = idToken ?: return false
        val o = runCatching { idt("lookup", buildJsonObject { put("idToken", t) }) }.getOrNull() ?: return false
        val u = runCatching { o["users"]!!.jsonArray[0].jsonObject }.getOrNull() ?: return false
        return (u["emailVerified"] as? JsonPrimitive)?.booleanOrNull == true
    }

    /** After opening the link in the email: refreshes the sign-in so the database sees the verified email. */
    suspend fun recheckVerified(): Boolean {
        tokenLock.withLock { idExp = 0L }
        token() ?: return false
        val v = lookupVerified()
        if (v) {
            tokenLock.withLock { idExp = 0L }
            token() // the token itself must carry email_verified = true for the database rules
            prefs.edit().putBoolean("verified", true).apply()
            user.value = user.value?.copy(verified = true)
            restart()
        }
        return v
    }

    fun signOut() {
        stopSync()
        idToken = null; refreshToken = null; idExp = 0L
        waitingForCloud = false
        prefs.edit().remove("waitCloud").remove("rt").remove("uid").remove("email").remove("verified").remove("google").apply()
        atPrefs.edit().clear().apply()
        synchronized(dirtyLock) { dirty.clear() }
        user.value = null
        status.value = ""
    }

    /** A valid ID token (refreshed when it is about to expire), or null when signed out / offline. */
    suspend fun token(): String? = tokenLock.withLock {
        val now = System.currentTimeMillis()
        if (idToken != null && now < idExp) return@withLock idToken
        val rt = refreshToken ?: return@withLock null
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("https://securetoken.googleapis.com/v1/token?key=${CloudConfig.API_KEY}")
                    .post(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", rt).build()).build()
                http.newCall(req).execute().use { r ->
                    val o = Api.json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                    if (!r.isSuccessful) {
                        val msg = (o["error"] as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
                        // Password changed / account removed: this device is signed out.
                        if (msg.contains("TOKEN_EXPIRED") || msg.contains("USER_NOT_FOUND") || msg.contains("USER_DISABLED") || msg.contains("INVALID_REFRESH_TOKEN")) {
                            withContext(Dispatchers.Main) { signOut(); status.value = "Signed out — please sign in again" }
                        }
                        return@runCatching null
                    }
                    idToken = s(o, "id_token")
                    idExp = System.currentTimeMillis() + ((s(o, "expires_in")?.toLongOrNull() ?: 3600L) - 120L) * 1000L
                    s(o, "refresh_token")?.let { if (it != refreshToken) { refreshToken = it; prefs.edit().putString("rt", Vault.enc(it)).apply() } }
                    idToken
                }
            }.getOrNull()
        }
    }

    // ───────────── Sync ─────────────

    private val canSync: Boolean get() = user.value?.verified == true && autoSync.value && host != null

    private fun stopSync() {
        streamJob?.cancel(); streamJob = null
        pushJob?.cancel(); pushJob = null
    }

    /** (Re)connects the live stream; called after sign-in, verification, toggling auto-sync and "Sync now". */
    fun restart() {
        lastResumeSync = System.currentTimeMillis()
        stopSync()
        if (!started) return
        val u = user.value
        status.value = when {
            u == null -> ""
            !u.verified -> "Verify your email to start syncing"
            !autoSync.value -> "Auto sync is off"
            else -> "Connecting…"
        }
        if (!canSync) return
        streamJob = scope.launch { streamLoop() }
        schedulePush(300)
    }

    fun syncNow() = restart()

    @Volatile private var lastResumeSync = 0L
    /**
     * The app came back to the screen: the live link to the account goes quiet while the app sits in the background (and
     * didn't come back by itself), so reconnect — the fresh snapshot brings what other devices (the TV) saved meanwhile.
     */
    fun onAppForeground() {
        if (!started || !canSync) return
        val now = System.currentTimeMillis()
        if (now - lastResumeSync < 20_000) return
        lastResumeSync = now
        restart()
    }

    private fun atKey(path: String) = path
    private fun localAt(path: String) = atPrefs.getLong(atKey(path), 0L)
    private fun setLocalAt(path: String, at: Long) = atPrefs.edit().putLong(atKey(path), at).apply()

    /** Something changed on this device: it will be uploaded in a moment (newest wins). */
    fun markApp(field: String) = mark("app/$field")
    fun markProfile(pid: String, field: String) = mark("p/$pid/$field")
    fun markProfileAll(pid: String) = PROFILE_FIELDS.forEach { if (it == "set" || it == DEVICE_FIELD || !it.startsWith("dev_")) mark("p/$pid/$it") }

    private fun mark(path: String) {
        if (!started) return
        // While the account's cloud copy is still on its way, a change here doesn't count as newer than the cloud.
        // TV: a change here is always newer than whatever this TV last took from the cloud (its clock can run behind the
        // phone's, and then the phone ignored the TV's Continue Watching as "older").
        if (!waitingForCloud) setLocalAt(path, if (BuildConfig.IS_TV) maxOf(System.currentTimeMillis(), localAt(path) + 1) else System.currentTimeMillis())
        synchronized(dirtyLock) { dirty.add(path) }
        schedulePush(if (path.endsWith("/prog")) 5000 else 1500)
    }

    /** A profile was deleted here: remove it from the cloud too. */
    fun deleteProfile(pid: String) {
        if (!started) return
        scope.launch {
            val u = user.value ?: return@launch
            if (!canSync) return@launch
            val t = token() ?: return@launch
            val body = buildJsonObject { put("p/$pid", JsonNull); put("app/avatar_$pid", JsonNull) }
            runCatching { patch(u.uid, t, body) }
        }
    }

    private fun schedulePush(delayMs: Long) {
        if (!canSync || waitingForCloud) return
        if (pushJob?.isActive == true) return
        pushJob = scope.launch {
            delay(delayMs)
            var backoff = 3000L
            while (isActive) {
                val batch = synchronized(dirtyLock) { dirty.toList().also { dirty.clear() } }
                if (batch.isEmpty()) break
                val ok = runCatching { push(batch) }.getOrDefault(false)
                if (ok) { status.value = "Synced ${clock()}"; backoff = 3000L }
                else {
                    synchronized(dirtyLock) { dirty.addAll(batch) }
                    status.value = "Offline — will retry"
                    delay(backoff); backoff = (backoff * 2).coerceAtMost(60_000L)
                }
            }
        }
    }

    private suspend fun push(paths: List<String>): Boolean {
        val u = user.value ?: return false
        val h = host ?: return false
        val t = token() ?: return false
        val values = withContext(Dispatchers.Main) {
            paths.map { path ->
                val parts = path.split("/")
                path to (if (parts[0] == "app") h.cloudRead("app", null, parts[1]) else h.cloudRead("p", parts[1], parts[2]))
            }
        }
        val by = if (BuildConfig.IS_TV) "tv" else "phone"
        val body = buildJsonObject {
            for ((path, v) in values) {
                val at = localAt(path).takeIf { it > 0 } ?: System.currentTimeMillis().also { setLocalAt(path, it) }
                put(path, buildJsonObject { put("v", v ?: ""); put("at", at); put("by", by) })
            }
        }
        val ok = patch(u.uid, t, body)
        if (BuildConfig.IS_TV) ActLog.add("SYNC", (if (ok) "sent " else "send failed · ") + paths.joinToString(", ") { it.substringAfterLast('/') })
        return ok
    }

    private fun patch(uid: String, token: String, body: JsonObject): Boolean {
        val req = Request.Builder().url("${CloudConfig.DB_URL}/users/$uid.json?auth=$token")
            .patch(body.toString().toRequestBody(JSON)).build()
        return http.newCall(req).execute().use { it.isSuccessful }
    }

    private suspend fun streamLoop() {
        var backoff = 2000L
        while (scope.isActive && canSync) {
            val u = user.value ?: return
            val t = token()
            if (t == null) { status.value = "Offline — will retry"; delay(backoff); backoff = (backoff * 2).coerceAtMost(60_000L); continue }
            val result = runCatching {
                val req = Request.Builder().url("${CloudConfig.DB_URL}/users/${u.uid}.json?auth=$t").header("Accept", "text/event-stream").build()
                sse.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use if (r.code == 401) "denied" else "error"
                    val src = r.body?.source() ?: return@use "error"
                    var event = ""
                    while (true) {
                        val line = src.readUtf8Line() ?: break
                        when {
                            line.startsWith("event:") -> event = line.substringAfter(':').trim()
                            line.startsWith("data:") -> {
                                val data = line.substringAfter(':').trim()
                                when (event) {
                                    "put", "patch" -> { handleEvent(event, data); backoff = 2000L }
                                    "cancel" -> return@use "denied"
                                    "auth_revoked" -> return@use "reauth"
                                }
                            }
                        }
                    }
                    "closed"
                }
            }.getOrElse { "error" }
            when (result) {
                "reauth" -> tokenLock.withLock { idExp = 0L }
                "denied" -> {
                    status.value = "Sync blocked — verify your email (or check the database rules)"
                    tokenLock.withLock { idExp = 0L }
                    delay(30_000)
                }
                else -> { status.value = "Offline — will retry"; delay(backoff); backoff = (backoff * 2).coerceAtMost(60_000L) }
            }
        }
    }

    private suspend fun handleEvent(event: String, data: String) {
        val o = runCatching { Api.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        val path = (o["path"] as? JsonPrimitive)?.contentOrNull ?: "/"
        val d = o["data"] ?: JsonNull
        val base = path.split("/").filter { it.isNotEmpty() }
        if (event == "put" && base.isEmpty()) {
            // Full snapshot (on connect): apply what's newer there, upload what's newer here.
            fullMerge(d as? JsonObject)
            status.value = "Synced ${clock()}"
            return
        }
        if (event == "patch" && d is JsonObject) d.forEach { (k, v) -> visit(base + k.split("/").filter { it.isNotEmpty() }, v) }
        else visit(base, d)
        status.value = "Synced ${clock()}"
    }

    private suspend fun fullMerge(root: JsonObject?) {
        val h = host ?: return
        val app = root?.get("app") as? JsonObject
        val prof = root?.get("p") as? JsonObject
        // App fields first (the profile list decides which profiles exist).
        val localIds = withContext(Dispatchers.Main) { h.cloudProfileIds() }
        val appFields = LinkedHashSet<String>()
        appFields.add("profiles"); appFields.add("appTexts"); appFields.add("logo")
        app?.keys?.let { appFields.addAll(it) }
        localIds.forEach { appFields.add("avatar_$it") }
        for (f in listOf("profiles") + (appFields - "profiles")) mergeField("app/$f", app?.get(f), "app", null, f)
        val ids = withContext(Dispatchers.Main) { h.cloudProfileIds() }
        for (pid in ids) {
            val node = prof?.get(pid) as? JsonObject
            for (f in PROFILE_FIELDS) {
                if (f.startsWith("dev_") && f != DEVICE_FIELD) continue
                mergeField("p/$pid/$f", node?.get(f), "p", pid, f)
            }
        }
        if (waitingForCloud) { waitingForCloud = false; prefs.edit().putBoolean("waitCloud", false).apply() }
        schedulePush(200)
    }

    private suspend fun mergeField(path: String, remote: JsonElement?, scope: String, pid: String?, field: String) {
        val r = remote as? JsonObject
        val rAt = (r?.get("at") as? JsonPrimitive)?.longOrNull ?: 0L
        val lAt = localAt(path)
        when {
            r != null && rAt > lAt -> {
                synchronized(dirtyLock) { dirty.remove(path) } // the cloud copy is newer: nothing of this device's to upload
                apply(scope, pid, field, (r["v"] as? JsonPrimitive)?.contentOrNull, rAt, path)
            }
            lAt > rAt || (r == null && hasLocal(scope, pid, field)) -> synchronized(dirtyLock) { dirty.add(path) }
        }
    }

    private suspend fun hasLocal(scope: String, pid: String?, field: String): Boolean {
        val h = host ?: return false
        return withContext(Dispatchers.Main) { !h.cloudRead(scope, pid, field).isNullOrEmpty() }
    }

    private suspend fun visit(seg: List<String>, el: JsonElement) {
        when {
            seg.size == 2 && seg[0] == "app" -> fieldNode("app", null, seg[1], el)
            seg.size == 3 && seg[0] == "p" -> fieldNode("p", seg[1], seg[2], el)
            seg.size == 2 && seg[0] == "p" && el is JsonNull -> Unit // a profile node removed; the profile list handles it
            seg.size > 3 || (seg.size == 3 && seg[0] == "app") -> Unit // part of a field ("v"/"at"): we always write whole fields
            el is JsonObject -> el.forEach { (k, v) -> visit(seg + k.split("/").filter { it.isNotEmpty() }, v) }
        }
    }

    private suspend fun fieldNode(scope: String, pid: String?, field: String, el: JsonElement) {
        if (field.startsWith("dev_") && field != DEVICE_FIELD) return
        val path = if (scope == "app") "app/$field" else "p/$pid/$field"
        val o = el as? JsonObject ?: return
        val rAt = (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L
        if (rAt > localAt(path)) apply(scope, pid, field, (o["v"] as? JsonPrimitive)?.contentOrNull, rAt, path)
    }

    private suspend fun apply(scope: String, pid: String?, field: String, v: String?, at: Long, path: String) {
        val h = host ?: return
        setLocalAt(path, at)
        withContext(Dispatchers.Main) { runCatching { h.cloudApply(scope, pid, field, v?.takeIf { it.isNotEmpty() }) } }
    }

    private fun clock(): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
}

/** Keeps the sign-in token encrypted with a key that never leaves this device's secure hardware (Android Keystore). */
private object Vault {
    private const val ALIAS = "streamx_cloud"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return g.generateKey()
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    fun enc(s: String): String = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        "k:" + b64(c.iv) + ":" + b64(c.doFinal(s.toByteArray()))
    }.getOrElse { "p:" + b64(s.toByteArray()) } // devices without a working keystore

    fun dec(s: String): String? = runCatching {
        val parts = s.split(":")
        when (parts[0]) {
            "k" -> {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(parts[1])))
                String(c.doFinal(unb64(parts[2])))
            }
            "p" -> String(unb64(parts[1]))
            else -> null
        }
    }.getOrNull()
}
