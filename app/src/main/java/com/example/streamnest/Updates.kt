package com.example.streamnest

import android.content.Context
import androidx.lifecycle.repeatOnLifecycle
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.io.File

/** A newer build on GitHub Releases. */
data class UpdateInfo(val versionName: String, val versionCode: Int, val notes: String, val apkUrl: String, val size: Long)

/**
 * In-app updates from GitHub Releases: every "Build APK" run publishes a release with both APKs (Xstream-phone.apk,
 * Xstream-TV.apk) and versions.json. The app compares versions, shows what's new, downloads the right APK and opens
 * Android's installer (the first time Android asks to allow installing from Xstream).
 */
object Updater {
    private const val REPO = "callmerickk/Xstream"
    val available = MutableStateFlow<UpdateInfo?>(null)
    /** 0..1 while downloading; null otherwise. */
    val progress = MutableStateFlow<Float?>(null)
    val error = MutableStateFlow<String?>(null)
    @Volatile private var checkedThisRun = false
    /** "Later": hidden until the app is opened again. */
    @Volatile var laterThisRun = false
    /** When "Later" was pressed: the pop-up stays away for 3 hours. */
    @Volatile var laterAt = 0L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun installedCode(ctx: Context): Int = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }.getOrDefault(0)

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull

    @Volatile private var lastCheck = 0L

    /**
     * Looks for a newer build; [force] = now, else at most every 10 minutes (checked every time the app comes to the
     * front). Reads the release files directly (github.com/…/releases/latest/download/…): unlike GitHub's API, that has
     * no hourly limit (shared mobile-network addresses often hit the API's limit, so the pop-up never appeared).
     */
    suspend fun check(ctx: Context, force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (!force && checkedThisRun && now - lastCheck < 2 * 60_000L) return@withContext available.value
        checkedThisRun = true
        lastCheck = now
        error.value = null
        val base = "https://github.com/$REPO/releases/latest/download"
        suspend fun fetch(u: String): String? = runCatching { Api.get(u) }.getOrNull()
            ?: run { kotlinx.coroutines.delay(1500); runCatching { Api.get(u) }.getOrNull() }
        var versions = fetch("$base/versions.json?t=$now")?.let { runCatching { Api.json.parseToJsonElement(it).jsonObject }.getOrNull() }
        var rel: JsonObject? = null
        if (versions == null) {
            // Older releases: through the API.
            rel = runCatching { Api.json.parseToJsonElement(Api.get("https://api.github.com/repos/$REPO/releases/latest")).jsonObject }.getOrNull()
            val assets = runCatching { rel!!["assets"]!!.jsonArray.map { it.jsonObject } }.getOrDefault(emptyList())
            versions = assets.firstOrNull { it.s("name").equals("versions.json", true) }?.s("browser_download_url")
                ?.let { u -> runCatching { Api.json.parseToJsonElement(Api.get(u)).jsonObject }.getOrNull() }
        }
        if (versions == null) { if (force) error.value = "Couldn't reach GitHub. Try again later."; return@withContext null }
        p.edit().putLong("checkedAt", now).apply()
        val tv = BuildConfig.IS_TV
        val code = versions[if (tv) "tv" else "phone"]?.jsonPrimitive?.intOrNull ?: 0
        val name = versions.s(if (tv) "tvName" else "phoneName") ?: ""
        // The APK of exactly this release (its tag), so the version shown is the version installed.
        val tag = versions.s("tag")
        val url = (if (tag != null) "https://github.com/$REPO/releases/download/$tag" else base) + "/${if (tv) "Xstream-TV.apk" else "Xstream-phone.apk"}"
        val info = if (code > installedCode(ctx)) {
            val notes = fetch((if (tag != null) "https://github.com/$REPO/releases/download/$tag" else base) + "/notes.md?t=$now")?.takeIf { !it.trimStart().startsWith("<") }
                ?: rel?.s("body")
                ?: runCatching { Api.json.parseToJsonElement(Api.get("https://api.github.com/repos/$REPO/releases/latest")).jsonObject.s("body") }.getOrNull()
                ?: ""
            UpdateInfo(name, code, notes.substringBefore("\n---").trim(), url, 0L)
        } else null
        available.value = info
        info
    }

    private fun apkFile(ctx: Context) = File(File(ctx.cacheDir, "updates").apply { mkdirs() }, "Xstream-update.apk")

    /** Downloads the APK (progress in [progress]) and opens the installer. */
    suspend fun downloadAndInstall(ctx: Context, info0: UpdateInfo) {
        if (progress.value != null) return
        error.value = null
        progress.value = 0f
        // A newer build may have come out since the pop-up appeared: always the newest.
        val info = runCatching { check(ctx, force = true) }.getOrNull()?.takeIf { it.versionCode >= info0.versionCode } ?: info0
        val f = apkFile(ctx)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                Api.http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { r ->
                    val body = r.body
                    if (!r.isSuccessful || body == null) return@runCatching false
                    val total = body.contentLength().takeIf { it > 0 } ?: info.size
                    body.byteStream().use { i ->
                        f.outputStream().use { o ->
                            val buf = ByteArray(128 * 1024)
                            var done = 0L
                            while (true) {
                                val n = i.read(buf); if (n < 0) break
                                o.write(buf, 0, n); done += n
                                if (total > 0) progress.value = (done.toFloat() / total).coerceIn(0f, 1f)
                            }
                        }
                    }
                    true
                }
            }.getOrDefault(false)
        }
        progress.value = null
        if (!ok) { error.value = "Download failed. Check your connection and try again."; return }
        install(ctx)
    }

    /** Opens Android's installer for the downloaded APK (or the "allow installs" screen first). */
    fun install(ctx: Context) {
        val f = apkFile(ctx)
        if (!f.exists()) return
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            error.value = "Allow Xstream to install updates, then press Update again."
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", f)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { error.value = "Couldn't open the installer." }
    }

    /** "Skip this version": never asked about this version again (a newer one is shown). */
    fun skip(ctx: Context, info: UpdateInfo) { prefs(ctx).edit().putInt("skipped", info.versionCode).apply() }
    fun skipped(ctx: Context): Int = prefs(ctx).getInt("skipped", 0)
}

/**
 * "Update available" pop-up (phone + TV): checks GitHub a little after start (at most every 6 hours), shows the new
 * version's notes, downloads and installs. "Later" hides it until the next newer build.
 */
@Composable
fun UpdatePrompt() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    // Checked shortly after the app opens and every time it comes back to the front (at most every 10 minutes).
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            delay(1500)
            val i = runCatching { Updater.check(ctx) }.getOrNull()
            if (i != null && i.versionCode > Updater.skipped(ctx) && System.currentTimeMillis() - Updater.laterAt > 3 * 3600_000L && Updater.progress.value == null
                && !SourceTester.running.value) info = i
        }
    }
    val i = info ?: return
    UpdateDialog(i, onLater = { Updater.laterThisRun = true; Updater.laterAt = System.currentTimeMillis(); info = null }, onSkip = { Updater.skip(ctx, i); info = null }) {
        scope.launch { Updater.downloadAndInstall(ctx, i) }
    }
}

@Composable
fun UpdateDialog(i: UpdateInfo, onLater: () -> Unit, onSkip: (() -> Unit)? = null, onUpdate: () -> Unit) {
    var p by remember { mutableStateOf<Float?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { Updater.progress.collect { p = it } }
    LaunchedEffect(Unit) { Updater.error.collect { err = it } }
    val updateFr = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(6) { delay(120); runCatching { updateFr.requestFocus() } } }
    AnimatedVisibility(true, enter = fadeIn(tween(250)) + scaleIn(tween(300), initialScale = 0.94f), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            Alignment.Center) {
            val shape = RoundedCornerShape(24.dp)
            Column(
                Modifier.padding(24.dp).widthIn(max = 520.dp).fillMaxWidth().clip(shape).background(Color(0xFF15161C))
                    .border(1.dp, Color(0x22FFFFFF), shape).padding(22.dp),
            ) {
                Text("Update available", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BrandRed, letterSpacing = 1.sp)
                Text("Xstream ${i.versionName}", Modifier.padding(top = 4.dp), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                if (i.size > 0) Text("%.1f MB".format(i.size / 1_048_576f), fontSize = 12.sp, color = Color(0xFF9AA3B5))
                Text("What's new", Modifier.padding(top = 14.dp, bottom = 6.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    Text(i.notes.ifBlank { "Fixes and improvements." }.replace("**", ""), fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFFD0D4DD))
                }
                err?.let { Text(it, Modifier.padding(top = 10.dp), fontSize = 13.sp, color = Color(0xFFFF8A80)) }
                val prog = p
                if (prog != null) {
                    Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF))) {
                        Box(Modifier.fillMaxWidth(prog).height(6.dp).background(BrandRed))
                    }
                    Text("Downloading… ${(prog * 100).toInt()}%", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Color(0xFF9AA3B5))
                }
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    DialogBtn("Later", primary = false, Modifier) { onLater() }
                    DialogBtn(if (prog != null) "Downloading…" else "Update now", primary = true, Modifier.focusRequester(updateFr)) { if (prog == null) onUpdate() }
                }
                if (onSkip != null) Box(Modifier.fillMaxWidth().padding(top = 10.dp), Alignment.CenterEnd) {
                    DialogBtn("Skip this version", primary = false, Modifier) { onSkip() }
                }
                // Every update remembers where it started.
                Box(Modifier.fillMaxWidth().padding(top = 16.dp).height(1.dp).background(Color(0x12FFFFFF)))
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(AppLook.logoRes(AppLook.logo.value)), null, Modifier.size(18.dp))
                    Text("Xstream · born 10 October 2026 · it all started here", Modifier.padding(start = 8.dp), fontSize = 12.5.sp, color = Color(0xFF9AA3B5))
                }
            }
        }
    }
}

@Composable
private fun DialogBtn(text: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    Text(
        text,
        modifier.onFocusChanged { focused = it.isFocused }.clip(shape)
            .background(if (primary) BrandRed else if (focused) Color(0x40FFFFFF) else Color(0x1FFFFFFF))
            .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 11.dp),
        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White,
    )
}
