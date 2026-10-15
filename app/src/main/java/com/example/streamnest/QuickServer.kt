package com.example.streamnest

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Connect PenguPlay: the user signs in on PenguPlay's own page (named there — they are making an account with it),
 * taps "Copy URL", comes back, and Xstream adds that personal link as one more quick server account.
 */
object QuickServer {
    const val DEFAULT_CONNECT = "https://pengu.uk/configure"
    private val linkRx = Regex("https?://(?:www\\.)?pengu\\.uk/\\S+?/manifest\\.json", RegexOption.IGNORE_CASE)

    fun connectUrl(): String = BuiltIns.value("penguConnect") ?: DEFAULT_CONNECT

    /** A PenguPlay personal link in [text], or null. */
    fun find(text: String?): String? {
        val m = linkRx.find(text ?: return null)?.value ?: return null
        val plain = runCatching { java.net.URLDecoder.decode(m, "UTF-8") }.getOrDefault(m)
        return m.takeIf { plain.contains("auth_token", true) }
    }

    fun hasAny(vm: AppViewModel) = vm.addons.value.any { it.baseUrl.contains("pengu", true) || it.manifest.name.contains("pengu", true) }
    fun hasOwn(vm: AppViewModel) = vm.texts.value["penguOwn"]?.let { own -> vm.addons.value.any { it.baseUrl == own } } == true

    /** Adds the link as a quick server account (its own, next to any shared one). Null = done. */
    suspend fun add(vm: AppViewModel, link: String): String? {
        val err = vm.installAddon(link) ?: run {
            val base = Api.normalize(link)
            val name = vm.addons.value.firstOrNull { it.baseUrl == base }?.manifest?.name
            if (name != null && name !in vm.qsAddons()) vm.setText("qsAddons", (vm.qsAddons() + name).joinToString("|"))
            vm.setText("qsOn", "yes")
            vm.setText("penguOwn", base)
            null
        }
        return err
    }
}

/**
 * Built-in servers for everyone, published by the developer from Developer's Corner (shared/devAddons in the cloud):
 * the CNCVerse link, the HdHub link and the Connect page. Every phone installs / updates them when the app opens.
 */
object BuiltIns {
    @Volatile private var cur: Map<String, String> = emptyMap()
    val keys = listOf("cncverse" to "CNCVerse link", "hdhub" to "HdHub link", "penguConnect" to "PenguPlay connect page")
    fun value(k: String): String? = cur[k]?.takeIf { it.isNotBlank() }

    suspend fun fetch(): Map<String, String> = withContext(Dispatchers.IO) {
        val txt = runCatching { Api.get("${CloudConfig.DB_URL}/shared/devAddons.json") }.getOrNull() ?: return@withContext cur
        val o = runCatching { Api.json.parseToJsonElement(txt).jsonObject }.getOrNull() ?: return@withContext cur
        cur = keys.mapNotNull { (k, _) -> o[k]?.jsonPrimitive?.contentOrNull?.let { k to it } }.toMap()
        cur
    }

    /** Installs the published CNCVerse / HdHub links (replacing older ones of the same kind). */
    suspend fun sync(vm: AppViewModel) {
        val m = fetch()
        for (k in listOf("cncverse", "hdhub")) {
            val url = m[k]?.takeIf { it.startsWith("http") } ?: continue
            if (vm.addons.value.none { it.baseUrl == Api.normalize(url) }) vm.installAddon(url, family = k)
        }
    }

    /** Developer's Corner: publishes the links for everyone (only the owner's account may write). Null = done. */
    suspend fun publish(values: Map<String, String>): String? = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext "Sign in first."
        val body = buildJsonObject { values.forEach { (k, v) -> put(k, v.trim()) }; put("at", System.currentTimeMillis()) }
        runCatching {
            Api.http.newCall(Request.Builder().url("${CloudConfig.DB_URL}/shared/devAddons.json?auth=$t")
                .put(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                if (r.isSuccessful) { cur = values; null } else if (r.code == 401 || r.code == 403) "Not allowed: add the Firebase rule for shared/devAddons, and use the owner's account." else "HTTP ${r.code}"
            }
        }.getOrElse { "Couldn't reach the cloud." }
    }
}

/** Shown once after signing in (again after 3 days if "Not now"), on Home, when there is no PenguPlay at all. */
@Composable
fun PenguConnectPrompt(vm: AppViewModel) {
    val user by Cloud.user.collectAsState()
    val texts by vm.texts.collectAsState()
    val addons by vm.addons.collectAsState()
    if (BuildConfig.IS_TV || user == null || addons.isEmpty()) return
    val askedAt = texts["penguAskAt"]?.toLongOrNull() ?: 0L
    if (QuickServer.hasAny(vm) || System.currentTimeMillis() - askedAt < 3L * 24 * 3600_000) return
    var open by remember { mutableStateOf(true) }
    if (open) PenguConnectSheet(vm) { open = false; vm.setText("penguAskAt", System.currentTimeMillis().toString()) }
}

/** The connect flow: card → PenguPlay's page → back here → "Quick server connected". */
@Composable
fun PenguConnectSheet(vm: AppViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf("ask") } // ask, waiting, adding, done, missing
    var err by remember { mutableStateOf<String?>(null) }
    val focused = LocalWindowInfo.current.isWindowFocused

    fun clip(): String? = runCatching {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
    }.getOrNull()
    fun clearClip() = runCatching {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
    }
    fun tryAdd(text: String?) {
        val link = QuickServer.find(text)
        if (link == null) { step = "missing"; return }
        step = "adding"; err = null
        scope.launch {
            val e = QuickServer.add(vm, link)
            if (e == null) { clearClip(); step = "done" } else { err = e; step = "missing" }
        }
    }
    // Back from PenguPlay's page: the copied link is read as soon as Xstream has the screen again.
    LaunchedEffect(focused, step) { if (focused && step == "waiting") { kotlinx.coroutines.delay(400); tryAdd(clip()) } }

    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
        if (step != "adding") onClose()
    }, Alignment.BottomCenter) {
        AnimatedVisibility(true, enter = fadeIn(tween(200)) + slideInVertically(tween(320)) { it / 3 }, exit = fadeOut()) {
            val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                Modifier.fillMaxWidth().widthIn(max = 560.dp).clip(shape).background(Color(0xFF121318)).border(1.dp, Color(0x1FFFFFFF), shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .navigationBarsPadding().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val n = when (step) { "ask" -> 1; "done" -> 3; else -> 2 }
                    repeat(3) { i -> Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i < n) BrandRed else Color(0x1FFFFFFF))) }
                }
                when (step) {
                    "ask" -> {
                        Text("FASTER PLAY", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = BrandRed, letterSpacing = 1.5.sp)
                        Text("Connect PenguPlay with Xstream", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 27.sp)
                        Text("Get your own quick server account. Videos start instantly, even when it's busy.", fontSize = 14.sp, color = Color(0xFFC9CED9), lineHeight = 20.sp)
                        listOf("Instant start on most titles", "Your own account, so busy hours don't slow you down", "Set up automatically, nothing to configure").forEach {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(20.dp).clip(CircleShape).background(Color(0x29E50914)), Alignment.Center) { Text("✓", fontSize = 11.sp, color = BrandRed, fontWeight = FontWeight.Bold) }
                                Text(it, Modifier.padding(start = 10.dp), fontSize = 13.sp, color = Color(0xFFC9CED9))
                            }
                        }
                        SheetBtn("Connect", true) {
                            step = "waiting"
                            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(QuickServer.connectUrl())).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                .onFailure { step = "ask"; Snack.show("No browser found to open the sign-in page") }
                        }
                        SheetBtn("Not now", false) { onClose() }
                        Text("Opens PenguPlay's sign-in page. Sign in, tap Copy URL, then come back here. PenguPlay keeps only an anonymous Google account ID, not your email or password.",
                            fontSize = 11.5.sp, color = Color(0xFF8A94A8), lineHeight = 16.sp)
                    }
                    "waiting", "adding" -> {
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(34.dp), color = BrandRed, strokeWidth = 3.dp)
                            Text(if (step == "adding") "Connecting…" else "Sign in on PenguPlay, tap Copy URL, then come back", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        }
                        if (step == "waiting") SheetBtn("Open the sign-in page again", false) {
                            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(QuickServer.connectUrl())).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                    "missing" -> {
                        Text("The link didn't come through", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                        Text(err ?: "On PenguPlay's page, tap Copy URL, then come back and tap Paste link.", fontSize = 14.sp, color = Color(0xFFC9CED9), lineHeight = 20.sp)
                        SheetBtn("Paste link", true) { tryAdd(clip()) }
                        SheetBtn("Open the sign-in page", false) {
                            step = "waiting"
                            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(QuickServer.connectUrl())).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                        SheetBtn("Not now", false) { onClose() }
                    }
                    "done" -> {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(68.dp).clip(CircleShape).background(Color(0x243CCF7A)), Alignment.Center) { Text("✓", fontSize = 32.sp, color = Color(0xFF3CCF7A), fontWeight = FontWeight.ExtraBold) }
                            Text("Quick server connected", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                            Text("Your account is set up and joined to the quick server. Enjoy!", fontSize = 14.sp, color = Color(0xFFC9CED9), textAlign = TextAlign.Center)
                        }
                        SheetBtn("Start watching", true) { onClose() }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetBtn(text: String, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Text(
        text,
        Modifier.fillMaxWidth().clip(shape).background(if (primary) BrandRed else Color.Transparent)
            .border(1.dp, if (primary) BrandRed else Color(0x29FFFFFF), shape).clickable(onClick = onClick).padding(vertical = 14.dp),
        fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = if (primary) Color.White else Color(0xFFC9CED9), textAlign = TextAlign.Center,
    )
}
