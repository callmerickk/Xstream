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
import kotlinx.coroutines.launch

/** Opens Xstream straight into Xstream Music (with its own opening animation). */
private fun musicIntent(ctx: Context) = Intent(ctx, if (BuildConfig.HAS_LIVE) MusicActivity::class.java else MainActivity::class.java).apply {
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
        if (BuildConfig.IS_TV || !BuildConfig.HAS_LIVE) return
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(ctx, info(ctx)) }
    }

    fun pin(ctx: Context) {
        val ok = runCatching { ShortcutManagerCompat.isRequestPinShortcutSupported(ctx) && ShortcutManagerCompat.requestPinShortcut(ctx, info(ctx), null) }.getOrDefault(false)
        if (!ok) Snack.show("Hold the Xstream icon and drag “Xstream Music” to your home screen")
    }
}

/**
 * Home screen widget "Xstream Music": the cover, song, artist, a progress bar and previous / play-pause / next.
 * Tapping the cover or text opens Xstream Music. The service updates it while music plays ([MusicWidget.push]).
 */
class MusicWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { mgr.updateAppWidget(it, views(ctx)) } }

    companion object {
        @Volatile private var art: android.graphics.Bitmap? = null
        @Volatile private var artUrl: String? = null
        @Volatile private var playing = false
        @Volatile private var progress = 0

        fun pin(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ok = runCatching { android.os.Build.VERSION.SDK_INT >= 26 && mgr.isRequestPinAppWidgetSupported &&
                mgr.requestPinAppWidget(ComponentName(ctx, MusicWidget::class.java), null, null) }.getOrDefault(false)
            if (!ok) Snack.show("Hold your home screen › Widgets › Xstream Music")
        }

        /** The song changed (title / artist / cover). */
        fun update(ctx: Context, title: String?, artist: String?, artwork: String? = null) {
            ctx.getSharedPreferences("music", Context.MODE_PRIVATE).edit().putString("w_title", title).putString("w_artist", artist).apply()
            if (artwork != null && artwork != artUrl) {
                artUrl = artwork
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    val req = coil.request.ImageRequest.Builder(ctx).data(artwork).size(320).allowHardware(false).build()
                    val bmp = (coil.Coil.imageLoader(ctx).execute(req).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    art = bmp?.let { rounded(it) }
                    refresh(ctx)
                }
            }
            refresh(ctx)
        }

        /** Play state and position (called by the music service every few seconds while playing). */
        fun push(ctx: Context, isPlaying: Boolean, posMs: Long, durMs: Long) {
            val p = if (durMs > 0) (posMs * 1000 / durMs).toInt().coerceIn(0, 1000) else 0
            if (p == progress && isPlaying == playing) return
            playing = isPlaying; progress = p
            refresh(ctx)
        }

        private fun rounded(src: android.graphics.Bitmap): android.graphics.Bitmap {
            val size = minOf(src.width, src.height)
            val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.shader = android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).apply {
                setLocalMatrix(android.graphics.Matrix().apply { setTranslate(-(src.width - size) / 2f, -(src.height - size) / 2f) })
            }
            val r = size * 0.1f
            c.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), r, r, paint)
            return out
        }

        private fun refresh(ctx: Context) = runCatching {
            val mgr = AppWidgetManager.getInstance(ctx)
            mgr.getAppWidgetIds(ComponentName(ctx, MusicWidget::class.java)).forEach { mgr.updateAppWidget(it, views(ctx)) }
        }

        private fun control(ctx: Context, action: String, code: Int) = PendingIntent.getBroadcast(ctx, code,
            Intent(ctx, MusicWidgetControl::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun views(ctx: Context): RemoteViews {
            val p = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
            val v = RemoteViews(ctx.packageName, R.layout.widget_music)
            v.setTextViewText(R.id.wm_title, p.getString("w_title", null) ?: "Xstream Music")
            v.setTextViewText(R.id.wm_sub, p.getString("w_artist", null) ?: "Tap to play songs, albums and videos")
            art?.let { v.setImageViewBitmap(R.id.wm_art, it) }
            v.setProgressBar(R.id.wm_progress, 1000, progress, false)
            v.setImageViewResource(R.id.wm_play, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
            val open = PendingIntent.getActivity(ctx, 77, musicIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.wm_art, open)
            v.setOnClickPendingIntent(R.id.wm_title, open)
            v.setOnClickPendingIntent(R.id.wm_prev, control(ctx, "prev", 78))
            v.setOnClickPendingIntent(R.id.wm_play, control(ctx, "toggle", 79))
            v.setOnClickPendingIntent(R.id.wm_next, control(ctx, "next", 80))
            return v
        }
    }
}

/** The widget's buttons: previous, play / pause, next (through the music service). */
class MusicWidgetControl : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val done = goAsync()
        MusicPlayer.control(ctx.applicationContext, intent.action.orEmpty()) { done.finish() }
    }
}
