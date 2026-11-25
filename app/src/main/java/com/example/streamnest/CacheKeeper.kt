@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.example.streamnest

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 0.9.7 (smartCache): cleans the saved video (StreamCache, cacheDir/vcache): pieces older than 24 h go, and the whole
 * stays under min(1 GB, 10 % of the free space). Never touched: downloads (another folder), the keep folder, the start
 * (first 8 MB) of the playing / last-played link, anything used in the last 10 minutes, and nothing at all while a
 * player is on screen. Runs ~10 s after each start and from WorkManager (idle or charging, at most every 6 h).
 */
object CacheKeeper {
    private const val DAY = 24 * 3600_000L
    private const val HEAD_KEEP = 8L * 1024 * 1024
    private fun prefs(c: Context) = c.getSharedPreferences("cachekeeper", Context.MODE_PRIVATE)

    fun notePlayed(url: String) { Public.vm?.appContext?.let { prefs(it).edit().putString("last", url).apply() } }

    fun start(app: Context) {
        if (!Feature.smartCache || BuildConfig.IS_TV) return
        runCatching {
            val wm = WorkManager.getInstance(app)
            fun req(c: Constraints) = PeriodicWorkRequestBuilder<CacheCleanWorker>(6, TimeUnit.HOURS).setConstraints(c).build()
            // Idle OR charging = two jobs; the pass itself keeps "at most once every 6 h".
            wm.enqueueUniquePeriodicWork("cache-clean-idle", ExistingPeriodicWorkPolicy.KEEP, req(Constraints.Builder().setRequiresDeviceIdle(true).build()))
            wm.enqueueUniquePeriodicWork("cache-clean-charging", ExistingPeriodicWorkPolicy.KEEP, req(Constraints.Builder().setRequiresCharging(true).build()))
        }.onFailure { ActLog.add("CACHE", "cache · cleaning job not scheduled (${it.javaClass.simpleName})") }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Thread({ runCatching { pass(app, "start", force = true) } }, "cache-keeper").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        }, 10_000)
    }

    fun pass(ctx: Context, why: String, force: Boolean) {
        if (!Feature.smartCache || BuildConfig.IS_TV) return
        if (PlayingSrc.open > 0) return
        val p = prefs(ctx); val now = System.currentTimeMillis()
        if (!force && now - p.getLong("at", 0L) < 6 * 3600_000L) return
        p.edit().putLong("at", now).apply()
        val cache = runCatching { StreamCache.get(ctx) }.getOrNull() ?: return
        val protect = listOfNotNull(PlayingSrc.url, p.getString("last", null)).map { StreamCache.keyFor(Uri.parse(it)) }.toSet()
        var removed = 0L
        val recent = now - 10 * 60_000L
        fun keep(key: String, s: androidx.media3.datasource.cache.CacheSpan) = s.lastTouchTimestamp > recent || (key in protect && s.position < HEAD_KEEP)
        // 1) older than 24 h
        for (key in cache.keys.toList()) for (s in cache.getCachedSpans(key).toList()) {
            if (PlayingSrc.open > 0) return
            if (!s.isCached || keep(key, s)) continue
            if (s.lastTouchTimestamp < now - DAY) runCatching { cache.removeSpan(s); removed += s.length }
        }
        // 2) size cap
        val cap = minOf(1L shl 30, ctx.cacheDir.usableSpace / 10)
        if (cache.cacheSpace > cap) {
            val all = cache.keys.toList().flatMap { k -> cache.getCachedSpans(k).toList().filter { it.isCached && !keep(k, it) } }.sortedBy { it.lastTouchTimestamp }
            for (s in all) { if (cache.cacheSpace <= cap || PlayingSrc.open > 0) break; runCatching { cache.removeSpan(s); removed += s.length } }
        }
        ActLog.add("CACHE", "cache · $why · removed ${removed / 1048576} MB · now ${cache.cacheSpace / 1048576} MB (cap ${cap / 1048576} MB)")
    }
}

/** 0.9.7 (smartCache): the WorkManager side of CacheKeeper. */
class CacheCleanWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result { runCatching { CacheKeeper.pass(applicationContext, "idle/charging", force = false) }; return Result.success() }
}
