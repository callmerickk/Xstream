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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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
import android.app.DownloadManager
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/** A newer build on GitHub Releases. */
data class UpdateInfo(val versionName: String, val versionCode: Int, val notes: String, val apkUrl: String, val size: Long, val backupUrl: String? = null)

/**
 * In-app updates from GitHub Releases: every "Build APK" run publishes a release with both APKs (StreamX-phone.apk,
 * StreamX-TV.apk) and versions.json. The app compares versions, shows what's new, downloads the right APK and opens
 * Android's installer (the first time Android asks to allow installing from StreamX).
 */
object Updater {
    private const val REPO = Brand.UPDATE_REPO
    /**
     * TV 0.9.5: StreamX's download relay on Cloudflare (a Worker that passes GitHub's release files on). On some networks
     * (Airtel without a VPN) GitHub's file servers crawl at ~15 KB/s while Cloudflare is fast. GitHub stays the backup.
     */
    private const val CDN = Brand.UPDATE_CDN
    val available = MutableStateFlow<UpdateInfo?>(null)
    /** 0..1 while downloading; null otherwise. */
    val progress = MutableStateFlow<Float?>(null)
    /** Bytes per second of the update download (0 = unknown). */
    val speed = MutableStateFlow(0L)
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
        fun parse(t: String?) = t?.let { runCatching { Api.json.parseToJsonElement(it).jsonObject }.getOrNull() }
        // No relay for this app (CDN blank): straight to GitHub.
        var versions = parse(if (CDN.isEmpty()) null else runCatching { Api.get("$CDN/versions.json?t=$now") }.getOrNull()) ?: parse(fetch("$base/versions.json?t=$now"))
        var rel: JsonObject? = null
        if (versions == null) {
            // Older releases: through the API.
            rel = runCatching { Api.json.parseToJsonElement(Api.get("https://api.github.com/repos/$REPO/releases/latest")).jsonObject }.getOrNull()
            val assets = runCatching { rel!!["assets"]!!.jsonArray.map { it.jsonObject } }.getOrDefault(emptyList())
            versions = assets.firstOrNull { it.s("name").equals("versions.json", true) }?.s("browser_download_url")
                ?.let { u -> runCatching { Api.json.parseToJsonElement(Api.get(u)).jsonObject }.getOrNull() }
        }
        if (versions == null) { if (force) error.value = "Couldn't check for updates. Try again later."; return@withContext null }
        p.edit().putLong("checkedAt", now).apply()
        val tv = BuildConfig.IS_TV
        val code = versions[if (tv) "tv" else "phone"]?.jsonPrimitive?.intOrNull ?: 0
        val name = versions.s(if (tv) "tvName" else "phoneName") ?: ""
        // The APK of exactly this release (its tag), so the version shown is the version installed.
        val tag = versions.s("tag")
        val apkName = if (tv) Brand.APK_TV else Brand.APK_PHONE
        val gh = (if (tag != null) "https://github.com/$REPO/releases/download/$tag" else base) + "/$apkName"
        val url = if (CDN.isEmpty()) gh else (if (tag != null) "$CDN/$tag" else CDN) + "/$apkName"
        val info = if (code > installedCode(ctx)) {
            val notes = fetch((if (tag != null) "https://github.com/$REPO/releases/download/$tag" else base) + "/notes.md?t=$now")?.takeIf { !it.trimStart().startsWith("<") }
                ?: rel?.s("body")
                ?: runCatching { Api.json.parseToJsonElement(Api.get("https://api.github.com/repos/$REPO/releases/latest")).jsonObject.s("body") }.getOrNull()
                ?: ""
            UpdateInfo(name, code, notesFor(notes, tv), url, 0L, backupUrl = gh.takeIf { it != url })
        } else null
        available.value = info
        // 0.9.73 (from Xstream): remembered, so "Update to the latest" can still be offered when GitHub can't be reached.
        prefs(ctx).edit().apply { if (info != null) putString("kName", info.versionName).putInt("kCode", info.versionCode).putString("kNotes", info.notes).putString("kUrl", info.apkUrl).putString("kBak", info.backupUrl) else remove("kCode") }.apply()
        info
    }

    /** A newer version we know of (now or from the last check), or null. */
    fun latestKnown(ctx: Context): UpdateInfo? = available.value ?: prefs(ctx).let { p ->
        val c = p.getInt("kCode", 0)
        if (c > installedCode(ctx)) UpdateInfo(p.getString("kName", "") ?: "", c, p.getString("kNotes", "") ?: "", p.getString("kUrl", "") ?: "", 0L, backupUrl = p.getString("kBak", null)) else null
    }
    /** Something went wrong (a stream failed, an error): set when a newer version exists and we haven't asked today. */
    val trouble = MutableStateFlow(false)
    fun onTrouble(ctx: Context) {
        if (BuildConfig.IS_TV || trouble.value) return
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        if (prefs(ctx).getString("nudgeDay", "") == day || latestKnown(ctx) == null) return
        trouble.value = true
    }
    fun nudged(ctx: Context) { prefs(ctx).edit().putString("nudgeDay", java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())).apply() }

    /** The release notes block for this device (phone or TV): blocks are separated by "---"; the newest matching one. */
    private fun notesFor(all: String, tv: Boolean): String {
        // TV 0.9.11: a new block also starts at each version heading ("StreamX TV 0.9.10 …", "TV 0.8.7", "Phone 0.9.66"),
        // so the pop-up shows only the version you're updating to, not every older one.
        val head = Regex("^(${Brand.NAME}\\b.*|TV \\d.*|Phone \\d.*)$")
        val blocks = mutableListOf<StringBuilder>()
        for (line in all.lines()) {
            val t = line.trim()
            if (Regex("^-{3,}$").matches(t)) { blocks.add(StringBuilder()); continue }
            if (head.matches(t) || blocks.isEmpty()) blocks.add(StringBuilder())
            blocks.last().appendLine(line)
        }
        return pick(blocks.map { it.toString().trim() }.filter { it.isNotBlank() }, tv)
    }

    private fun pick(blocks: List<String>, tv: Boolean): String {
        fun head(b: String) = b.lineSequence().first().trim()
        val mine = blocks.firstOrNull { if (tv) head(it).contains("TV") else !head(it).contains("TV") }
        return mine ?: blocks.firstOrNull().orEmpty()
    }

    private fun apkFile(ctx: Context) = File(File(ctx.cacheDir, "updates").apply { mkdirs() }, "${Brand.NAME}-update.apk")
    /** Where Android's download manager saves the update (keeps going when StreamX is in the background or closed). */
    private fun dmFile(ctx: Context) = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(ctx.filesDir, "dl").apply { mkdirs() }, "${Brand.NAME}-update.apk")
    /** Downloads outlive the pop-up and the screen: they run here, not in the dialog. */
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The app is on screen (the installer can only open then); otherwise it opens when StreamX comes back. */
    @Volatile var foreground = false
    @Volatile var checkedOnce = false
    @Volatile private var installWaiting = false
    /** Background download chosen: the pop-up stays hidden while it runs. */
    val inBackground = MutableStateFlow(false)

    /** Starts the download in the background (safe to minimise StreamX); the installer opens when it's done. */
    fun start(ctx: Context, info: UpdateInfo) {
        val app = ctx.applicationContext
        bg.launch { downloadAndInstall(app, info) }
    }

    /** StreamX came to the front: open the installer for a download that finished meanwhile. */
    fun onResume(ctx: Context) {
        foreground = true
        if (installWaiting) { installWaiting = false; install(ctx.applicationContext) }
    }

    /** Android's download manager: survives minimising and closing the app, shows its own notification. */
    private suspend fun dmDownload(ctx: Context, info: UpdateInfo): Boolean? = withContext(Dispatchers.IO) {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return@withContext null
        val f = dmFile(ctx); runCatching { f.delete() }
        val id = runCatching {
            dm.enqueue(DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("${Brand.NAME} ${info.versionName}")
                .setDescription("Downloading the update")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true).setAllowedOverRoaming(true)
                .setDestinationUri(Uri.fromFile(f)))
        }.getOrNull() ?: return@withContext null
        prefs(ctx).edit().putLong("dmId", id).apply()
        var lastBytes = -1L; var still = 0
        while (true) {
            delay(500)
            val c = runCatching { dm.query(DownloadManager.Query().setFilterById(id)) }.getOrNull() ?: return@withContext null
            c.use {
                if (!it.moveToFirst()) return@withContext null
                val st = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { t -> t > 0 } ?: info.size
                if (total > 0) progress.value = (done.toFloat() / total).coerceIn(0f, 1f)
                when (st) {
                    DownloadManager.STATUS_SUCCESSFUL -> return@withContext true
                    DownloadManager.STATUS_FAILED -> return@withContext false
                }
                // Nothing at all for 40 s (stuck before the first byte): let the app's own downloader try.
                if (done == lastBytes && done <= 0L) { if (++still > 80) { runCatching { dm.remove(id) }; return@withContext null } } else still = 0
                lastBytes = done
            }
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    /** Downloads the APK (progress in [progress]) and opens the installer. */
    suspend fun downloadAndInstall(ctx: Context, info0: UpdateInfo) {
        if (progress.value != null) return
        error.value = null
        progress.value = 0f
        // A newer build may have come out since the pop-up appeared: always the newest.
        val info = runCatching { check(ctx, force = true) }.getOrNull()?.takeIf { it.versionCode >= info0.versionCode } ?: info0
        // TV 0.9.12: this version is already fully downloaded (the installer was closed, or opened behind the app):
        // open the installer again instead of downloading it all a second time.
        val pf = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
        val have = apkFile(ctx)
        if (pf.getInt("dlCode", -1) == info.versionCode && have.exists() && have.length() > 1_000_000 && have.length() == pf.getLong("dlLen", -1)) {
            progress.value = null; inBackground.value = false
            ActLog.add("UPDATE", "${info.versionName} already downloaded — reusing the file")
            if (foreground) install(ctx) else installWaiting = true
            return
        }
        UpdateService.start(ctx, info.versionName)
        try {
            // 1) Fast: the APK in small pieces over 6 connections at once (GitHub's file servers give each connection its own speed).
            // TV 0.9.5: through StreamX's Cloudflare relay first, then straight from GitHub.
            val fast = runCatching { fastDownload(ctx, info) }.getOrDefault(false) ||
                (info.backupUrl != null && runCatching { ActLog.add("UPDATE", "Relay failed — downloading from GitHub"); fastDownload(ctx, info.copy(apkUrl = info.backupUrl)) }.getOrDefault(false))
            // 2) Android's downloader (slow but sure), 3) a plain single download.
            val ok = fast || runCatching { dmDownload(ctx, info) }.getOrNull() == true || run { progress.value = 0f; plainDownload(info, apkFile(ctx)) }
            progress.value = null; inBackground.value = false
            if (!ok) { error.value = "Download failed. Check your connection and try again."; return }
            if (fast) pf.edit().putInt("dlCode", info.versionCode).putLong("dlLen", apkFile(ctx).length()).apply()
            ActLog.add("UPDATE", "${info.versionName} downloaded")
            if (foreground) install(ctx) else installWaiting = true
        } finally { UpdateService.stop(ctx) }
    }

    private fun client() = Api.http.newBuilder().readTimeout(25, java.util.concurrent.TimeUnit.SECONDS).build()

    /** Parallel ranged download into [apkFile]; resumes each part a few times if a connection drops. */
    private suspend fun fastDownload(ctx: Context, info: UpdateInfo): Boolean = withContext(Dispatchers.IO) {
        val c = client()
        // Follow GitHub's redirect once, then ask the file server for the size.
        val (url, total) = c.newCall(Request.Builder().url(info.apkUrl).header("Range", "bytes=0-0").build()).execute().use { r ->
            if (r.code != 206) return@withContext false
            val len = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: return@withContext false
            r.request.url.toString() to len
        }
        if (total < 1_000_000) return@withContext false
        val f = apkFile(ctx); runCatching { f.delete() }
        java.io.RandomAccessFile(f, "rw").use { it.setLength(total) }
        // Small 2 MB pieces shared by 6 connections: a fast connection simply takes more pieces, so one slow
        // connection (common without a VPN on some networks) no longer holds back the end of the download.
        val piece = 2L * 1024 * 1024
        val pieces = ((total + piece - 1) / piece).toInt()
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val doneBytes = java.util.concurrent.atomic.AtomicLong(0)
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        val t0 = System.currentTimeMillis()
        var lastT = t0; var lastB = 0L
        fun report() {
            val sum = doneBytes.get()
            val pr = (sum.toFloat() / total).coerceIn(0f, 1f)
            val now = System.currentTimeMillis()
            if (now - lastT >= 800) { speed.value = (sum - lastB) * 1000 / (now - lastT); lastT = now; lastB = sum }
            if (pr - (progress.value ?: 0f) > 0.005f || pr >= 1f) { progress.value = pr; UpdateService.progress(ctx, pr) }
        }
        val workers = (0 until 6).map {
            async {
                java.io.RandomAccessFile(f, "rw").use { raf ->
                    val buf = ByteArray(64 * 1024)
                    while (!failed.get()) {
                        val k = next.getAndIncrement(); if (k >= pieces) break
                        val from = k * piece; val to = minOf(total, from + piece) - 1
                        var got = 0L; var tries = 0
                        while (from + got <= to) {
                            if (++tries > 6) { failed.set(true); break }
                            runCatching {
                                c.newCall(Request.Builder().url(url).header("Range", "bytes=${from + got}-$to").build()).execute().use { r ->
                                    if (r.code != 206) error("no range")
                                    val inp = r.body!!.byteStream()
                                    raf.seek(from + got)
                                    while (from + got <= to) {
                                        val n = inp.read(buf, 0, minOf(buf.size.toLong(), to - from - got + 1).toInt()); if (n < 0) break
                                        raf.write(buf, 0, n); got += n; doneBytes.addAndGet(n.toLong()); report()
                                    }
                                }
                            }.onFailure { delay(400L * tries) }
                        }
                    }
                }
            }
        }
        workers.awaitAll()
        speed.value = 0
        !failed.get() && doneBytes.get() >= total && f.length() == total
    }

    private suspend fun plainDownload(info: UpdateInfo, f: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client().newCall(Request.Builder().url(info.apkUrl).build()).execute().use { r ->
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

    /** Opens Android's installer for the downloaded APK (or the "allow installs" screen first). */
    fun install(ctx: Context) {
        val f = listOf(dmFile(ctx), apkFile(ctx)).filter { it.exists() && it.length() > 0 }.maxByOrNull { it.lastModified() } ?: return
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            error.value = "Allow ${Brand.NAME} to install updates, then press Update again."
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", f)
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // TV 0.9.12: opened from the screen you're on, so the installer comes up on top of StreamX (from the app's
            // background side, Fire TV put its "Install?" screen behind StreamX and the update was downloaded twice).
            val act = com.lagradost.cloudstream3.CommonActivity.activity
            if (act != null && foreground && !act.isFinishing) act.startActivity(view)
            else ctx.startActivity(view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActLog.add("UPDATE", "installer opened" + if (act != null && foreground) "" else " (from the background)")
        }.onFailure { error.value = "Couldn't open the installer."; ActLog.add("UPDATE", "installer failed: ${it.javaClass.simpleName}") }
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
            Updater.onResume(ctx)
            try { kotlinx.coroutines.awaitCancellation() } finally { Updater.foreground = false }
        }
    }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            // TV 0.9.13: the first check waits until Home has settled (8 s) and the remote is quiet.
            if (BuildConfig.IS_TV && !Updater.checkedOnce) { delay(8_000); TvIdle.waitQuiet(); Updater.checkedOnce = true } else delay(1500)
            // Finished even if the app pauses meanwhile (it used to be cut off at start-up and not tried again).
            val i = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { Updater.check(ctx) }.getOrNull() }
            if (i != null && i.versionCode > Updater.skipped(ctx) && System.currentTimeMillis() - Updater.laterAt > 3 * 3600_000L && Updater.progress.value == null
                && !SourceTester.running.value) info = i
        }
    }
    // 0.9.73 (from Xstream): after a problem on an old version — once a day, never while a video plays (phones only).
    val trouble by Updater.trouble.collectAsState()
    var nudge by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(trouble) {
        if (!trouble) return@LaunchedEffect
        while (PlayingSrc.open > 0) delay(1500)
        delay(800)
        Updater.trouble.value = false
        val k = Updater.latestKnown(ctx) ?: return@LaunchedEffect
        if (info == null && Updater.progress.value == null && k.apkUrl.isNotBlank()) { Updater.nudged(ctx); nudge = k; ActLog.add("APP", "update nudge shown (${k.versionName})") }
    }
    nudge?.let { k ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { nudge = null },
            title = { androidx.compose.material3.Text("This is fixed in the latest ${Brand.NAME}") },
            text = { androidx.compose.material3.Text("You're on ${BuildConfig.VERSION_NAME}. Version ${k.versionName} fixes problems like the one you just had.") },
            confirmButton = { androidx.compose.material3.TextButton({ nudge = null; info = k }) { androidx.compose.material3.Text("Update now") } },
            dismissButton = { androidx.compose.material3.TextButton({ nudge = null }) { androidx.compose.material3.Text("Later") } })
    }
    val i = info ?: return
    UpdateDialog(i, onLater = { Updater.laterThisRun = true; Updater.laterAt = System.currentTimeMillis(); info = null }, onSkip = { Updater.skip(ctx, i); info = null },
        onBackground = { Updater.inBackground.value = true; if (Updater.progress.value == null) Updater.start(ctx, i); info = null
            android.widget.Toast.makeText(ctx, "Downloading in the background — the installer opens when it's done.", android.widget.Toast.LENGTH_LONG).show() }) {
        Updater.start(ctx, i)
    }
}

@Composable
fun UpdateDialog(i: UpdateInfo, onLater: () -> Unit, onSkip: (() -> Unit)? = null, onBackground: (() -> Unit)? = null, onUpdate: () -> Unit) {
    var p by remember { mutableStateOf<Float?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { Updater.progress.collect { p = it } }
    LaunchedEffect(Unit) { Updater.error.collect { err = it } }
    val updateFr = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(6) { delay(120); runCatching { updateFr.requestFocus() } } }
    // TV 0.9.6: a real pop-up window on TV — the remote stays inside it (it used to act on the page behind), Back = Later.
    if (BuildConfig.IS_TV) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onLater,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
            UpdateCard(i, p, err, updateFr, onLater, onSkip, onBackground, onUpdate)
        }
        return
    }
    UpdateCard(i, p, err, updateFr, onLater, onSkip, onBackground, onUpdate)
}

@Composable
private fun UpdateCard(i: UpdateInfo, p: Float?, err: String?, updateFr: FocusRequester, onLater: () -> Unit, onSkip: (() -> Unit)?,
                       onBackground: (() -> Unit)?, onUpdate: () -> Unit) {
    AnimatedVisibility(true, enter = fadeIn(tween(250)) + scaleIn(tween(300), initialScale = 0.94f), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            Alignment.Center) {
            val shape = RoundedCornerShape(24.dp)
            Column(
                Modifier.padding(24.dp).widthIn(max = 520.dp).fillMaxWidth().clip(shape).background(glassy(Color(0xFF15161C)))
                    .border(1.dp, Color(0x22FFFFFF), shape).padding(22.dp),
            ) {
                Text("Update available", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BrandRed, letterSpacing = 1.sp)
                Text("${Brand.NAME} ${i.versionName}", Modifier.padding(top = 4.dp), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                if (i.size > 0) Text("%.1f MB".format(i.size / 1_048_576f), fontSize = 12.sp, color = Color(0xFF9AA3B5))
                Text("What's new", Modifier.padding(top = 14.dp, bottom = 6.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                // TV: the notes take focus (▲ from the buttons) and ▲ ▼ scroll them; ▼ at the end goes back to the buttons.
                val notesScroll = rememberScrollState()
                val notesScope = rememberCoroutineScope()
                var notesFocused by remember { mutableStateOf(false) }
                Column(Modifier.heightIn(max = if (BuildConfig.IS_TV) 150.dp else 240.dp)
                    .then(if (BuildConfig.IS_TV) Modifier
                        .onFocusChanged { notesFocused = it.isFocused }
                        .onPreviewKeyEvent { e ->
                            if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                androidx.compose.ui.input.key.Key.DirectionDown ->
                                    if (notesScroll.value < notesScroll.maxValue) { notesScope.launch { notesScroll.animateScrollBy(120f) }; true } else false
                                androidx.compose.ui.input.key.Key.DirectionUp -> { if (notesScroll.value > 0) notesScope.launch { notesScroll.animateScrollBy(-120f) }; true }
                                else -> false
                            }
                        }
                        .focusable()
                        .border(1.5.dp, if (notesFocused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                        .padding(8.dp) else Modifier)
                    .verticalScroll(notesScroll)) {
                    Text(i.notes.ifBlank { "Fixes and improvements." }.replace("**", ""), fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFFD0D4DD))
                }
                err?.let { Text(it, Modifier.padding(top = 10.dp), fontSize = 13.sp, color = Color(0xFFFF8A80)) }
                val prog = p
                if (prog != null) {
                    Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF))) {
                        Box(Modifier.fillMaxWidth(prog).height(6.dp).background(BrandRed))
                    }
                    Text("Downloading… ${(prog * 100).toInt()}%" + Updater.speed.collectAsState().value.let { if (it > 0) " · " + fmtSpeed(it) else "" }, Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Color(0xFF9AA3B5))
                }
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    // TV 0.9.11: every button on one row, so none is pushed off the bottom of the screen.
                    if (BuildConfig.IS_TV && onSkip != null && prog == null) DialogBtn("Skip this version", primary = false, Modifier) { onSkip() }
                    DialogBtn("Later", primary = false, Modifier) { onLater() }
                    DialogBtn(if (prog != null) "Downloading…" else "Update now", primary = true, Modifier.focusRequester(updateFr)) { if (prog == null) onUpdate() }
                }
                if (onBackground != null && !BuildConfig.IS_TV) Box(Modifier.fillMaxWidth().padding(top = 10.dp), Alignment.CenterEnd) {
                    DialogBtn(if (prog != null) "Continue in background" else "Download in background", primary = false, Modifier) { onBackground() }
                }
                if (!BuildConfig.IS_TV && onSkip != null && prog == null) Box(Modifier.fillMaxWidth().padding(top = 10.dp), Alignment.CenterEnd) {
                    DialogBtn("Skip this version", primary = false, Modifier) { onSkip() }
                }
                // Every update remembers where it started.
                if (!BuildConfig.IS_TV) Box(Modifier.fillMaxWidth().padding(top = 16.dp).height(1.dp).background(Color(0x12FFFFFF)))
                if (!BuildConfig.IS_TV) Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(AppLook.logoRes(AppLook.logo.value)), null, Modifier.size(18.dp))
                    Text("${Brand.NAME} · born 10 October 2026 · it all started here", Modifier.padding(start = 8.dp), fontSize = 12.5.sp, color = Color(0xFF9AA3B5))
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
