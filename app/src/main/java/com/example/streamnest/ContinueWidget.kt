package com.example.streamnest

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home screen widget "Continue watching": your latest title with its progress and a Resume button. Tapping it opens the
 * title in StreamX and presses Play (it resumes where you stopped).
 */
class ContinueWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, views(ctx)) }
    }

    companion object {
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("widget_cw", Context.MODE_PRIVATE)

        /** Called whenever progress is saved: remember the latest title and refresh every widget. */
        private var lastAt = 0L
        private var lastKey: String? = null

        fun update(ctx: Context, p: Progress) {
            if (BuildConfig.IS_TV) return
            // At most every 30 s for the same title (progress is saved often while you watch).
            val now = System.currentTimeMillis()
            if (p.key == lastKey && now - lastAt < 30_000) return
            lastAt = now; lastKey = p.key
            prefs(ctx).edit()
                .putString("key", p.key).putString("title", p.title).putLong("pos", p.positionMs).putLong("dur", p.durationMs)
                .apply()
            refresh(ctx)
        }

        fun refresh(ctx: Context) = runCatching {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, ContinueWidget::class.java))
            if (ids.isNotEmpty()) ids.forEach { mgr.updateAppWidget(it, views(ctx)) }
        }

        private fun views(ctx: Context): RemoteViews {
            val pr = prefs(ctx)
            val key = pr.getString("key", null)
            val title = pr.getString("title", null)
            val pos = pr.getLong("pos", 0); val dur = pr.getLong("dur", 0)
            val v = RemoteViews(ctx.packageName, R.layout.widget_continue)
            if (key != null && title != null) {
                v.setTextViewText(R.id.widget_title, title.substringBefore("  S").ifBlank { title })
                val ep = Regex("S(\\d+)E(\\d+)").find(title.replace(" ", ""))?.let { "S${it.groupValues[1]} E${it.groupValues[2]} · " }.orEmpty()
                val left = if (dur > 0) ((dur - pos).coerceAtLeast(0) / 60_000).let { m -> if (m >= 60) "${m / 60}h ${m % 60}m left" else "${m}m left" } else ""
                v.setTextViewText(R.id.widget_sub, ep + left)
                v.setProgressBar(R.id.widget_progress, 1000, if (dur > 0) (pos * 1000 / dur).toInt().coerceIn(0, 1000) else 0, false)
            }
            val open = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (key != null) { putExtra("resumeKey", key); putExtra("resumeTitle", title) }
            }
            val pi = PendingIntent.getActivity(ctx, 41, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.widget_root, pi)
            v.setOnClickPendingIntent(R.id.widget_resume, pi)
            return v
        }
    }
}

/** A title the widget asked to resume (handled by the app once a profile is open). */
object WidgetOpen {
    val pending = androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null)
    fun take(intent: Intent?) {
        val k = intent?.getStringExtra("resumeKey") ?: return
        pending.value = k to (intent.getStringExtra("resumeTitle") ?: "")
        intent.removeExtra("resumeKey")
    }
}
