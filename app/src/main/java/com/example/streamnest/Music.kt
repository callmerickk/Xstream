package com.example.streamnest

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Music (64-bit app): songs from the Live server's music addon, played in the background with the phone's media
 * notification and lock-screen controls. A queue holds "xlive://track/<id>" placeholders; each song's real address is
 * asked for only when it's about to play (the next one is fetched ahead by the player), so a long queue costs nothing.
 */
@OptIn(UnstableApi::class)
class MusicService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val ctx = applicationContext
        val upstream = DefaultDataSource.Factory(ctx, OkHttpDataSource.Factory(LiveServer.http))
        val resolving = ResolvingDataSource.Factory(upstream) { spec ->
            val uri = spec.uri
            if (uri.scheme != "xlive") return@Factory spec
            val id = uri.lastPathSegment.orEmpty()
            val url = MusicPlayer.resolved[id] ?: runBlocking { LiveServer.trackUrl(ctx, LiveServer.Track(id = id)) }
                ?.also { MusicPlayer.resolved[id] = it } ?: throw IOException("This song isn't available right now")
            spec.withUri(Uri.parse(url))
        }
        // FFmpeg audio decoders too (FLAC / ALAC / Opus files play on every phone).
        val renderers = io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory(ctx)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val player = ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val open = PendingIntent.getActivity(ctx, 41, Intent(ctx, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("openMusic", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

/** The app's side of the music player: the queue, what's playing, and the controls. */
object MusicPlayer {
    data class Now(
        val track: LiveServer.Track? = null, val playing: Boolean = false, val buffering: Boolean = false,
        val posMs: Long = 0, val durMs: Long = 0, val index: Int = 0, val shuffle: Boolean = false,
        val repeat: Int = Player.REPEAT_MODE_OFF, val error: String? = null,
    )

    val now = MutableStateFlow(Now())
    val queue = MutableStateFlow<List<LiveServer.Track>>(emptyList())
    val recent = MutableStateFlow<List<LiveServer.Track>>(emptyList())
    /** Full-screen music page / now-playing page requests (from Home, the mini bar or the notification). */
    val openLibrary = MutableStateFlow(false)
    val openNow = MutableStateFlow(false)
    internal val resolved = ConcurrentHashMap<String, String>()

    private var controller: MediaController? = null
    private var connecting = false
    private var pending: (() -> Unit)? = null
    private var appCtx: Context? = null

    private fun item(t: LiveServer.Track): MediaItem = MediaItem.Builder().setMediaId(t.id).setUri("xlive://track/${t.id}")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
            .setArtworkUri(t.artworkURL?.let(Uri::parse)).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        recent.value = runCatching {
            Api.json.decodeFromString(ListSerializer(LiveServer.Track.serializer()), prefs(ctx).getString("recent", null) ?: "[]")
        }.getOrDefault(emptyList())
    }

    private fun remember(t: LiveServer.Track) {
        val ctx = appCtx ?: return
        recent.value = (listOf(t) + recent.value.filter { it.id != t.id }).take(40)
        prefs(ctx).edit().putString("recent", Api.json.encodeToString(ListSerializer(LiveServer.Track.serializer()), recent.value)).apply()
    }

    private fun withController(ctx: Context, then: (MediaController) -> Unit) {
        init(ctx)
        controller?.let { then(it); return }
        pending = { controller?.let(then) }
        if (connecting) return
        connecting = true
        val c = ctx.applicationContext
        val future = MediaController.Builder(c, SessionToken(c, ComponentName(c, MusicService::class.java))).buildAsync()
        future.addListener({
            connecting = false
            val mc = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = mc
            mc.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish()
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    now.value = now.value.copy(error = "Couldn't play this song")
                    // Skip to the next one instead of stopping.
                    if (mc.hasNextMediaItem()) { mc.seekToNextMediaItem(); mc.prepare(); mc.play() }
                }
            })
            pending?.invoke(); pending = null
            publish()
        }, ContextCompat.getMainExecutor(c))
    }

    /** Keeps [now] fresh (call every half second while a music screen is visible). */
    fun publish() {
        val mc = controller ?: return
        val i = mc.currentMediaItemIndex
        val t = queue.value.getOrNull(i) ?: mc.currentMediaItem?.let { m ->
            LiveServer.Track(m.mediaId, m.mediaMetadata.title?.toString().orEmpty(), m.mediaMetadata.artist?.toString().orEmpty(),
                artworkURL = m.mediaMetadata.artworkUri?.toString())
        }
        val prev = now.value.track?.id
        now.value = Now(t, mc.isPlaying, mc.playbackState == Player.STATE_BUFFERING, mc.currentPosition.coerceAtLeast(0),
            mc.duration.takeIf { it > 0 } ?: (t?.durationMs ?: 0), i, mc.shuffleModeEnabled, mc.repeatMode,
            if (t?.id == prev) now.value.error else null)
        if (t != null && t.id != prev) remember(t)
    }

    /** Plays [list] from [index] (the list becomes the queue). */
    fun play(ctx: Context, list: List<LiveServer.Track>, index: Int) {
        if (list.isEmpty()) return
        queue.value = list
        withController(ctx) { mc ->
            mc.setMediaItems(list.map(::item), index.coerceIn(0, list.lastIndex), 0)
            mc.prepare(); mc.play()
            publish()
        }
    }

    /** Adds a song to play right after the current one. */
    fun playNext(ctx: Context, t: LiveServer.Track) = withController(ctx) { mc ->
        if (mc.mediaItemCount == 0) { play(ctx, listOf(t), 0); return@withController }
        val at = (mc.currentMediaItemIndex + 1).coerceAtMost(mc.mediaItemCount)
        mc.addMediaItem(at, item(t)); queue.value = queue.value.toMutableList().apply { add(at.coerceAtMost(size), t) }
    }

    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }; publish() }
    fun next() { controller?.seekToNextMediaItem(); publish() }
    fun previous() { controller?.let { if (it.currentPosition > 4_000) it.seekTo(0) else it.seekToPreviousMediaItem() }; publish() }
    fun seekTo(ms: Long) { controller?.seekTo(ms); publish() }
    fun jumpTo(i: Int) { controller?.let { it.seekTo(i, 0); it.play() }; publish() }
    fun shuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }; publish() }
    fun cycleRepeat() {
        controller?.let { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }
        publish()
    }
    fun stop() { controller?.let { it.stop(); it.clearMediaItems() }; queue.value = emptyList(); now.value = Now() }
    /** A movie starts: music pauses (audio focus does it too; this also covers players that don't ask for it). */
    fun pauseForVideo() { controller?.takeIf { it.isPlaying }?.pause() }
}
