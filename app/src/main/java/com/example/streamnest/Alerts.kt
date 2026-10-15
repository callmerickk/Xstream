package com.example.streamnest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** A show we watch for new episodes (shows in My List and in Continue Watching). */
@Serializable data class AlertShow(val id: String, val name: String, val poster: String? = null, val bases: List<String> = emptyList())

/** One alert, also kept in the app's own list (Settings › Notifications), so TVs without a notification shade see it too. */
@Serializable data class AlertItem(val showId: String, val show: String, val text: String, val poster: String? = null, val at: Long)

/**
 * New-episode alerts. While the app runs it saves the list of shows to watch; a background job (every 6 hours, only
 * with internet) asks the addons for each show's episodes and posts a notification for episodes that came out since
 * the last check.
 */
object EpisodeAlerts {
    private const val CHANNEL = "episodes"
    private const val WORK = "episode-alerts"

    private fun showsFile(ctx: Context) = File(ctx.filesDir, "alert_shows.json")
    private fun inboxFile(ctx: Context) = File(ctx.filesDir, "alert_inbox.json")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    /** Saves the shows to watch (called when the app starts and whenever My List / Continue Watching change). */
    fun snapshot(ctx: Context, vm: AppViewModel) {
        val fromList = vm.myList.value.filter { it.type == "series" }.map { AlertShow(it.id, it.name, it.poster, vm.metaBases("series", it.id)) }
        val fromCw = vm.progress.value.filter { it.isSeries() }.map {
            val m = it.asMeta(); AlertShow(m.id, m.name, m.poster, vm.metaBases("series", m.id))
        }
        val shows = (fromList + fromCw).filter { it.bases.isNotEmpty() }.distinctBy { it.id }.take(60)
        runCatching { showsFile(ctx).writeText(Api.json.encodeToString(shows)) }
        schedule(ctx, isOn(vm.texts.value["notifyOn"] ?: Defaults["notifyOn"] ?: "yes"))
    }

    fun schedule(ctx: Context, on: Boolean) {
        runCatching {
            val wm = WorkManager.getInstance(ctx.applicationContext)
            if (!on) { wm.cancelUniqueWork(WORK); return }
            val req = PeriodicWorkRequestBuilder<EpisodeAlertWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(20, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    fun inbox(ctx: Context): List<AlertItem> = runCatching {
        Api.json.decodeFromString<List<AlertItem>>(inboxFile(ctx).readText())
    }.getOrDefault(emptyList())

    fun clearInbox(ctx: Context) { runCatching { inboxFile(ctx).delete() } }

    private fun releasedMs(s: String?): Long? {
        val d = s?.take(10) ?: return null
        return runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(d)?.time }.getOrNull()
    }

    /** Looks for new episodes; returns how many alerts were made. */
    suspend fun check(ctx: Context): Int = withContext(Dispatchers.IO) {
        val shows = runCatching { Api.json.decodeFromString<List<AlertShow>>(showsFile(ctx).readText()) }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        // First run: start counting from 2 days ago, so a just-released episode still shows up.
        val last = p.getLong("lastCheck", now - 2 * 86_400_000L)
        val made = mutableListOf<AlertItem>()
        for (s in shows) {
            var meta: Meta? = null
            for (b in s.bases) {
                meta = runCatching { Api.fetch<MetaResponse>("$b/meta/series/${java.net.URLEncoder.encode(s.id, "UTF-8")}.json").meta }.getOrNull()
                if (meta != null) break
            }
            val eps = meta?.videos.orEmpty().filter { (it.season ?: 0) > 0 && it.episode != null }
            val fresh = eps.filter { v -> releasedMs(v.released)?.let { it in (last + 1)..now } == true }
                .sortedWith(compareBy({ it.season }, { it.episode }))
            if (fresh.isEmpty()) continue
            val e = fresh.last()
            val text = if (fresh.size == 1) "S${e.season} E${e.episode} is out now" + (e.name ?: e.title)?.let { " · $it" }.orEmpty()
                       else "${fresh.size} new episodes · up to S${e.season} E${e.episode}"
            made += AlertItem(s.id, meta?.name?.ifBlank { null } ?: s.name, text, s.poster ?: meta?.poster, now)
        }
        p.edit().putLong("lastCheck", now).apply()
        if (made.isNotEmpty()) {
            val all = (made + inbox(ctx)).distinctBy { it.showId + it.text }.take(40)
            runCatching { inboxFile(ctx).writeText(Api.json.encodeToString(all)) }
            made.forEach { post(ctx, it) }
        }
        made.size
    }

    private fun post(ctx: Context, a: AlertItem) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "New episodes", NotificationManager.IMPORTANCE_DEFAULT))
            val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ?: Intent(ctx, MainActivity::class.java)
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(ctx, a.showId.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_signal)
                .setContentTitle(a.show)
                .setContentText(a.text)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            NotificationManagerCompat.from(ctx).notify(a.showId.hashCode(), n)
        }
    }
}

class EpisodeAlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { EpisodeAlerts.check(applicationContext) }
        return Result.success()
    }
}
