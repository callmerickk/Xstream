package com.example.streamnest

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/**
 * 0.8.18: approval-only access (off until the developer turns it on in Settings › Access requests).
 * Firebase: access/config/required (true/false), access/requests/<uid> {name, email, device, app, version, at, status},
 * access/allowed/<uid> = true. Developer devices are always allowed. An approval is remembered, so the app works offline.
 * Also "Update required" when versions.json says this version is too old ("minPhone").
 */
object Access {
    /** ok · update · signin · request · waiting · denied */
    val state = MutableStateFlow("ok")
    data class Req(val uid: String, val name: String, val email: String, val device: String, val app: String, val version: String, val at: Long, val status: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("access", Context.MODE_PRIVATE)
    private fun url(path: String, t: String) = "${CloudConfig.DB_URL}/access/$path.json?auth=$t"

    /** null = couldn't ask (no sign-in / network / rules refused). */
    private suspend fun call(method: String, path: String, body: String? = null): String? = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext null
        runCatching {
            val b = Request.Builder().url(url(path, t))
            val json = "application/json".toMediaType()
            when (method) { "PUT" -> b.put((body ?: "null").toRequestBody(json)); "DELETE" -> b.delete(); else -> b.get() }
            Api.http.newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull()
    }

    suspend fun refresh(vm: AppViewModel) {
        if (BuildConfig.IS_TV || BuildConfig.LIVE_ONLY) { state.value = "ok"; return }
        val ctx = vm.appContext
        val min = Updater.minPhone(ctx)
        if (min > 0 && Updater.installedCode(ctx) < min) { state.value = "update"; return }
        val user = Cloud.user.value
        val p = prefs(ctx)
        val required = if (user == null) p.getBoolean("required", false)
            else call("GET", "config/required")?.trim()?.let { it == "true" }?.also { p.edit().putBoolean("required", it).apply() } ?: p.getBoolean("required", false)
        if (!required || Public.dev) { state.value = "ok"; return }
        if (user == null) { state.value = "signin"; return }
        if (p.getString("allowedUid", null) == user.uid) {
            state.value = "ok"
            // Still allowed? Removed by the developer → asked again (only on a clear "no", never on a network error).
            if (call("GET", "allowed/${user.uid}")?.trim() == "null") { p.edit().remove("allowedUid").apply(); refresh(vm) }
            return
        }
        when (call("GET", "allowed/${user.uid}")?.trim()) {
            "true" -> { p.edit().putString("allowedUid", user.uid).apply(); state.value = "ok"; ActLog.add("APP", "access: approved"); return }
        }
        val r = call("GET", "requests/${user.uid}")?.trim()
        state.value = when {
            r == null || r == "null" -> "request"
            r.contains("\"denied\"") -> "denied"
            else -> "waiting"
        }
    }

    suspend fun request(vm: AppViewModel): Boolean {
        val u = Cloud.user.value ?: return false
        val name = vm.profiles.value.firstOrNull { it.id == vm.texts.value["lastProfile"] }?.name ?: u.email.substringBefore("@")
        val body = buildJsonObject {
            put("name", name); put("email", u.email)
            put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim())
            put("app", "Xstream"); put("version", BuildConfig.VERSION_NAME)
            put("at", System.currentTimeMillis()); put("status", "pending")
        }.toString()
        val ok = call("PUT", "requests/${u.uid}", body) != null
        ActLog.add("APP", if (ok) "access: request sent" else "access: request failed")
        if (ok) state.value = "waiting"
        return ok
    }

    // ── Developer side ──
    suspend fun requests(): List<Req> {
        val o = call("GET", "requests")?.let { runCatching { Api.json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return emptyList()
        return o.mapNotNull { (uid, v) ->
            val j = (v as? JsonObject) ?: return@mapNotNull null
            fun s(k: String) = (j[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            Req(uid, s("name"), s("email"), s("device"), s("app"), s("version"), (j["at"] as? JsonPrimitive)?.longOrNull ?: 0L, s("status").ifBlank { "pending" })
        }.sortedByDescending { it.at }
    }
    suspend fun allowed(): Set<String> = call("GET", "allowed")?.let { runCatching { (Api.json.parseToJsonElement(it) as? JsonObject)?.keys }.getOrNull() }.orEmpty()
    suspend fun approve(uid: String) { call("PUT", "allowed/$uid", "true"); call("PUT", "requests/$uid/status", "\"approved\"") }
    suspend fun deny(uid: String) { call("DELETE", "allowed/$uid"); call("PUT", "requests/$uid/status", "\"denied\"") }
    suspend fun remove(uid: String) { call("DELETE", "allowed/$uid"); call("PUT", "requests/$uid/status", "\"removed\"") }
    suspend fun required(): Boolean = call("GET", "config/required")?.trim() == "true"
    suspend fun setRequired(on: Boolean): Boolean = call("PUT", "config/required", if (on) "true" else "false") != null
}

/** Covers the app while access isn't granted (nothing shows while it's "ok"). */
@Composable
fun AccessGate(vm: AppViewModel) {
    if (BuildConfig.IS_TV || BuildConfig.LIVE_ONLY) return
    val st by Access.state.collectAsState()
    val user by Cloud.user.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(user?.uid) { runCatching { Access.refresh(vm) } }
    // Waiting: looked at again every 30 s, so an approval lets you in by itself.
    LaunchedEffect(st) { if (st == "waiting") while (true) { kotlinx.coroutines.delay(30_000); runCatching { Access.refresh(vm) } } }
    if (st == "ok") return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0F)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(28.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Xstream", color = Color(0xFFE50914), fontSize = 30.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(18.dp))
            val (title, body) = when (st) {
                "update" -> "Update required" to "This version no longer works. Update to keep watching."
                "signin" -> "Sign in to continue" to "Xstream is for invited people. Sign in, then ask for access."
                "request" -> "Request access" to "Your account (${user?.email.orEmpty()}) isn't activated yet. Send a request — you'll get in as soon as it's approved."
                "waiting" -> "Waiting for approval" to "Your request was sent. This screen opens by itself once it's approved."
                else -> "Access not approved" to "Your request wasn't approved. You can ask again."
            }
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(body, color = Color(0xFFB3B3B3), fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(22.dp))
            @Composable fun Btn(label: String, onClick: () -> Unit) = Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFE50914)).clickable(onClick = onClick).padding(horizontal = 26.dp, vertical = 12.dp)) {
                Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            when (st) {
                "update" -> Btn("Update now") { scope.launch { Updater.check(ctx, force = true)?.let { Updater.downloadAndInstall(ctx, it) } } }
                "signin" -> CloudAccountSection()
                "request", "denied" -> Btn(if (st == "denied") "Ask again" else "Send request") { scope.launch { if (!Access.request(vm)) Snack.show("Couldn't send — check your connection") } }
                "waiting" -> Btn("Check now") { scope.launch { Access.refresh(vm) } }
            }
        }
    }
}

/** Settings › Access requests (developer only): approve / deny requests, remove people, turn approval on or off. */
@Composable
fun AccessAdmin() {
    val scope = rememberCoroutineScope()
    var reqs by remember { mutableStateOf<List<Access.Req>>(emptyList()) }
    var allowed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var required by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { reqs = Access.requests(); allowed = Access.allowed(); required = Access.required() }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Approval required", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("When on, new accounts must be approved here before they can use Xstream.", color = Color(0xFF9A9A9A), fontSize = 12.sp)
            }
            Switch(required, { on -> scope.launch { if (Access.setRequired(on)) required = on else Snack.show("Couldn't change it (check the Firebase rules)") } })
        }
        Text("Your account id (for the Firebase rules): ${Cloud.user.value?.uid ?: "sign in first"}", color = Color(0xFF8A8A8A), fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(14.dp))
        if (reqs.isEmpty()) Text("No requests yet.", color = Color(0xFF9A9A9A))
        reqs.forEach { r ->
            val isIn = r.uid in allowed
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A1B20)).padding(12.dp)) {
                Text(r.name.ifBlank { "Someone" }, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(r.email, color = Color(0xFFB3B3B3), fontSize = 12.sp)
                Text("${r.device} · ${r.app} ${r.version} · ${if (isIn) "approved" else r.status}", color = Color(0xFF8A8A8A), fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    @Composable fun Chip(label: String, c: Color, onClick: () -> Unit) = Box(Modifier.clip(RoundedCornerShape(50)).background(c).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)) { Text(label, color = Color.White, fontSize = 13.sp) }
                    if (!isIn) Chip("Approve", Color(0xFF1F8F4E)) { scope.launch { Access.approve(r.uid); tick++ } }
                    if (!isIn && r.status != "denied") Chip("Deny", Color(0xFF5A2A2A)) { scope.launch { Access.deny(r.uid); tick++ } }
                    if (isIn) Chip("Remove", Color(0xFF5A2A2A)) { scope.launch { Access.remove(r.uid); tick++ } }
                }
            }
        }
    }
}
