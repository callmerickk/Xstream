package com.example.streamnest

import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** Live state of one of the app's own downloads. */
data class DlLive(val bytes: Long, val total: Long, val speed: Long, val paused: Boolean, val done: Boolean, val failed: Boolean)

/**
 * StreamX's own downloader (new downloads): the file is fetched by the app itself, so it can be paused and resumed
 * from the same byte and shows its live speed. A small notification keeps it going while the app is in the background.
 */
object DlEngine {
    /** Bumped when a download notification is tapped: Home opens the Downloads bubble. */
    val openReq = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun take(i: Intent?) { if (i?.getBooleanExtra("openDownloads", false) == true) openReq.value = openReq.value + 1 }

    val live = MutableStateFlow<Map<Long, DlLive>>(emptyMap())
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: android.content.SharedPreferences? = null

    private fun p(ctx: Context) = prefs ?: ctx.applicationContext.getSharedPreferences("dl_engine", Context.MODE_PRIVATE).also { prefs = it }
    fun isDone(ctx: Context, id: Long) = p(ctx).getBoolean("done_$id", false)
    fun isPaused(ctx: Context, id: Long) = p(ctx).getBoolean("paused_$id", false)
    private fun total(ctx: Context, id: Long) = p(ctx).getLong("total_$id", 0L)

    fun state(ctx: Context, d: Dl): DlLive {
        live.value[d.dmId]?.let { return it }
        val f = File(d.file)
        val done = isDone(ctx, d.dmId)
        return DlLive(f.length(), if (done) f.length() else total(ctx, d.dmId), 0, isPaused(ctx, d.dmId), done, false)
    }

    private fun put(id: Long, s: DlLive) { live.value = live.value + (id to s) }

    fun start(ctx: Context, d: Dl) {
        if (d.url.isBlank() || jobs[d.dmId]?.isActive == true || isDone(ctx, d.dmId)) return
        p(ctx).edit().putBoolean("paused_${d.dmId}", false).apply()
        val app = ctx.applicationContext
        DlService.start(app)
        jobs[d.dmId] = scope.launch { run(app, d) }
    }

    fun pause(ctx: Context, d: Dl) {
        p(ctx).edit().putBoolean("paused_${d.dmId}", true).apply()
        jobs.remove(d.dmId)?.cancel()
        val s = state(ctx, d)
        put(d.dmId, s.copy(paused = true, speed = 0))
        if (jobs.values.none { it.isActive }) DlService.stop(ctx)
    }

    fun cancel(ctx: Context, d: Dl) {
        jobs.remove(d.dmId)?.cancel()
        live.value = live.value - d.dmId
        p(ctx).edit().remove("done_${d.dmId}").remove("paused_${d.dmId}").remove("total_${d.dmId}").apply()
        if (jobs.values.none { it.isActive }) DlService.stop(ctx)
    }

    /** At app start: downloads that were running carry on (paused ones stay paused). */
    fun resumeAll(ctx: Context, list: List<Dl>) {
        list.filter { it.dmId < 0 && it.url.isNotBlank() && !isDone(ctx, it.dmId) && !isPaused(ctx, it.dmId) }.forEach { start(ctx, it) }
    }

    private suspend fun run(ctx: Context, d: Dl) {
        val f = File(d.file).apply { parentFile?.mkdirs() }
        var tries = 0
        while (scope.isActive && tries < 6) {
            val have = if (f.exists()) f.length() else 0L
            try {
                val b = Request.Builder().url(d.url).header("Range", "bytes=$have-")
                StreamHeaders.forUrl(d.url).forEach { (k, v) -> b.header(k, v) }
                if (StreamHeaders.forUrl(d.url).keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", PlayerUserAgent)
                Api.http.newBuilder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build().newCall(b.build()).execute().use { r ->
                    if (r.code == 416) { markDone(ctx, d, f); return }
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val resumed = r.code == 206
                    val body = r.body ?: error("empty")
                    val len = body.contentLength()
                    val total = if (len > 0) (if (resumed) have + len else len) else total(ctx, d.dmId)
                    if (total > 0) p(ctx).edit().putLong("total_${d.dmId}", total).apply()
                    RandomAccessFile(f, "rw").use { out ->
                        if (resumed) out.seek(have) else out.setLength(0)
                        var done = if (resumed) have else 0L
                        val buf = ByteArray(256 * 1024)
                        var lastT = System.currentTimeMillis(); var lastB = done; var speed = 0L
                        body.byteStream().use { inp ->
                            while (true) {
                                if (!kotlinx.coroutines.currentCoroutineContext().isActive) return
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                val now = System.currentTimeMillis()
                                if (now - lastT >= 700) {
                                    speed = ((done - lastB) * 1000 / (now - lastT)).coerceAtLeast(0)
                                    lastT = now; lastB = done
                                    put(d.dmId, DlLive(done, total, speed, false, false, false))
                                    DlService.progress(ctx, d.title, if (total > 0) done.toFloat() / total else -1f, speed)
                                }
                            }
                        }
                        if (total > 0 && done < total) error("cut off at $done of $total")
                    }
                    markDone(ctx, d, f); return
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                tries++
                ActLog.add("ERROR", "download ${d.title}: ${e.message} (try $tries)")
                kotlinx.coroutines.delay(3_000L * tries)
            }
        }
        put(d.dmId, state(ctx, d).copy(failed = true, speed = 0))
        jobs.remove(d.dmId); if (jobs.values.none { it.isActive }) DlService.stop(ctx)
    }

    private fun markDone(ctx: Context, d: Dl, f: File) {
        p(ctx).edit().putBoolean("done_${d.dmId}", true).putLong("total_${d.dmId}", f.length()).apply()
        put(d.dmId, DlLive(f.length(), f.length(), 0, false, true, false))
        jobs.remove(d.dmId)
        ActLog.add("APP", "download finished: ${d.title} (${f.length() / 1_048_576} MB)")
        if (jobs.values.none { it.isActive }) DlService.stop(ctx)
        DlService.finished(ctx, d.title)
    }
}

/** Keeps the app's own downloads going in the background, with their progress and speed in a notification. */
class DlService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(this, "Downloads", -1f, 0)
        runCatching { if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n) }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 7302
        private const val CH = "downloads"
        @Volatile private var lastAt = 0L

        private fun build(ctx: Context, title: String, p: Float, speed: Long): Notification {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && mgr.getNotificationChannel(CH) == null)
                mgr.createNotificationChannel(NotificationChannel(CH, "Downloads", NotificationManager.IMPORTANCE_LOW))
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH) else @Suppress("DEPRECATION") Notification.Builder(ctx)
            return b.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Downloading $title")
                .setContentText(if (p < 0) "Starting…" else "${(p * 100).toInt()}% · ${fmtSpeed(speed)}")
                .setContentIntent(tapOpens(ctx)).setOngoing(true).setOnlyAlertOnce(true).setProgress(100, if (p < 0) 0 else (p * 100).toInt(), p < 0).build()
        }

        fun start(ctx: Context) {
            if (BuildConfig.IS_TV) return
            runCatching { val i = Intent(ctx, DlService::class.java); if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
        }
        private fun tapOpens(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 7303,
            Intent(ctx, MainActivity::class.java).putExtra("openDownloads", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun finished(ctx: Context, title: String) = runCatching {
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH) else @Suppress("DEPRECATION") Notification.Builder(ctx)
            ctx.getSystemService(NotificationManager::class.java).notify(7304, b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Downloaded: $title").setContentText("Ready to watch offline · tap to open")
                .setContentIntent(tapOpens(ctx)).setAutoCancel(true).build())
        }
        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, DlService::class.java)) } }
        fun progress(ctx: Context, title: String, p: Float, speed: Long) {
            val now = System.currentTimeMillis(); if (now - lastAt < 1500) return; lastAt = now
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, title, p, speed)) }
        }
    }
}

fun fmtSpeed(bps: Long): String = when {
    bps >= 1_048_576 -> "%.1f MB/s".format(bps / 1_048_576.0)
    bps >= 1024 -> "${bps / 1024} KB/s"
    else -> "—"
}
