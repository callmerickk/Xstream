package com.example.streamnest

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/** Opens Xstream straight into Xstream Music (with its own opening animation). */
private fun musicIntent(ctx: Context) = Intent(ctx, MainActivity::class.java).apply {
    action = Intent.ACTION_VIEW
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    putExtra("musicHome", true)
}

/**
 * "Xstream Music" on the phone's home screen: holding the Xstream icon shows it as a shortcut (drag it out to place it),
 * and Music › Settings › Add to home screen pins it directly.
 */
object MusicShortcut {
    private const val ID = "xstream_music"
    private fun info(ctx: Context) = ShortcutInfoCompat.Builder(ctx, ID)
        .setShortLabel("Xstream Music").setLongLabel("Xstream Music")
        .setIcon(IconCompat.createWithResource(ctx, R.drawable.ic_shortcut_music))
        .setIntent(musicIntent(ctx)).build()

    /** Listed when the Xstream icon is held (phones only). */
    fun publish(ctx: Context) {
        if (BuildConfig.IS_TV) return
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(ctx, info(ctx)) }
    }

    fun pin(ctx: Context) {
        val ok = runCatching { ShortcutManagerCompat.isRequestPinShortcutSupported(ctx) && ShortcutManagerCompat.requestPinShortcut(ctx, info(ctx), null) }.getOrDefault(false)
        if (!ok) Snack.show("Hold the Xstream icon and drag “Xstream Music” to your home screen")
    }
}

/** Home screen widget "Xstream Music": the song playing (or a nudge to start one); a tap opens Xstream Music. */
class MusicWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { mgr.updateAppWidget(it, views(ctx)) } }

    companion object {
        fun pin(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ok = runCatching { android.os.Build.VERSION.SDK_INT >= 26 && mgr.isRequestPinAppWidgetSupported &&
                mgr.requestPinAppWidget(ComponentName(ctx, MusicWidget::class.java), null, null) }.getOrDefault(false)
            if (!ok) Snack.show("Hold your home screen › Widgets › Xstream Music")
        }

        /** The song changed: every Xstream Music widget shows it. */
        fun update(ctx: Context, title: String?, artist: String?) {
            ctx.getSharedPreferences("music", Context.MODE_PRIVATE).edit().putString("w_title", title).putString("w_artist", artist).apply()
            runCatching {
                val mgr = AppWidgetManager.getInstance(ctx)
                mgr.getAppWidgetIds(ComponentName(ctx, MusicWidget::class.java)).forEach { mgr.updateAppWidget(it, views(ctx)) }
            }
        }

        private fun views(ctx: Context): RemoteViews {
            val p = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
            val v = RemoteViews(ctx.packageName, R.layout.widget_music)
            v.setTextViewText(R.id.wm_title, p.getString("w_title", null) ?: "Xstream Music")
            v.setTextViewText(R.id.wm_sub, p.getString("w_artist", null) ?: "Tap to play songs, albums and videos")
            val pi = PendingIntent.getActivity(ctx, 77, musicIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.wm_root, pi)
            return v
        }
    }
}
