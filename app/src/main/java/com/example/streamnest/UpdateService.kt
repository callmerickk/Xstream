package com.example.streamnest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps Xstream alive while an update downloads in the background (Android would otherwise stop the app a little
 * after it's minimised), with the progress in a notification.
 */
class UpdateService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(this, intent?.getStringExtra("name").orEmpty(), -1f)
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 7301
        private const val CH = "updates"
        @Volatile private var name = ""
        @Volatile private var lastPct = -1

        private fun build(ctx: Context, nm: String, p: Float): Notification {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && mgr.getNotificationChannel(CH) == null)
                mgr.createNotificationChannel(NotificationChannel(CH, "App updates", NotificationManager.IMPORTANCE_LOW))
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH) else @Suppress("DEPRECATION") Notification.Builder(ctx)
            return b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading Xstream ${nm.ifBlank { name }}")
                .setContentText(if (p < 0) "Starting…" else "${(p * 100).toInt()}%")
                .setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(100, if (p < 0) 0 else (p * 100).toInt(), p < 0)
                .build()
        }

        fun start(ctx: Context, versionName: String) {
            if (BuildConfig.IS_TV) return
            name = versionName; lastPct = -1
            runCatching {
                val i = Intent(ctx, UpdateService::class.java).putExtra("name", versionName)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }

        fun progress(ctx: Context, p: Float) {
            if (BuildConfig.IS_TV) return
            val pct = (p * 100).toInt(); if (pct == lastPct) return; lastPct = pct
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, name, p)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, UpdateService::class.java)) }
            runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(ID) }
        }
    }
}
