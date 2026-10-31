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
import kotlinx.coroutines.launch
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
        // googlevideo songs are read in pieces (YtChunked), Live server songs straight from this phone.
        val ok = OkHttpDataSource.Factory(LiveServer.http)
        val upstream = DefaultDataSource.Factory(ctx, androidx.media3.datasource.DataSource.Factory { YtChunked(ok.createDataSource()) })
        val resolving = ResolvingDataSource.Factory(upstream) { spec ->
            val uri = spec.uri
            if (uri.scheme == "ytm") {
                // Xstream Music (SimpMusic): the song's audio in the quality the connection can carry right now.
                val vid = uri.lastPathSegment.orEmpty()
                val m = runBlocking { YtDirect.music(vid) } ?: throw IOException("This song isn't available right now")
                val url = (if (MusicQuality.hi) m.hi ?: m.lo else m.lo ?: m.hi) ?: throw IOException("This song isn't available right now")
                return@Factory spec.withUri(Uri.parse(url)).withRequestHeaders(mapOf("User-Agent" to m.ua))
            }
            // DASH pieces of a music video: the client's user agent.
            if (uri.host?.contains("googlevideo") == true && spec.httpRequestHeaders["User-Agent"] == null)
                return@Factory spec.withAdditionalHeaders(mapOf("User-Agent" to YtDirect.musicUa))
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
            // "ytmpd://track/<id>": a song with a video, as one DASH manifest made on the spot (see YtDirect.dashManifest).
            .setMediaSourceFactory(DefaultMediaSourceFactory(androidx.media3.datasource.DataSource.Factory { MpdOrUpstream(resolving.createDataSource()) }))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val open = PendingIntent.getActivity(ctx, 41, Intent(ctx, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("openMusic", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
        // Auto quality: a song that stops to load for more than 1.5 s drops to 128 kbps at the same second; after a
        // minute of smooth play the next songs go back to 160 kbps.
        MusicQuality.start(ctx)
        // Home-screen widget: play state and progress every 3 s while playing; title and cover on each new song.
        val widgetTick = object : Runnable {
            override fun run() {
                MusicWidget.push(ctx, player.isPlaying, player.currentPosition, player.duration)
                if (player.isPlaying) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 3_000)
            }
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { widgetTick.run() }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaMetadata?.let { m -> MusicWidget.update(ctx, m.title?.toString(), m.artist?.toString(), m.artworkUri?.toString()) }
            }
        })
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var stallCheck: Runnable? = null
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                stallCheck?.let(main::removeCallbacks); stallCheck = null
                if (state == Player.STATE_BUFFERING && player.playWhenReady && player.currentPosition > 1_000 && MusicQuality.hi &&
                    player.currentMediaItem?.localConfiguration?.uri?.scheme == "ytm") {
                    stallCheck = Runnable {
                        if (player.playbackState != Player.STATE_BUFFERING) return@Runnable
                        MusicQuality.hi = false; MusicQuality.smoothSince = System.currentTimeMillis()
                        val i = player.currentMediaItemIndex; val pos = player.currentPosition
                        player.currentMediaItem?.let { cur ->
                            // Same song again at 128 kbps (a new address makes the player load it afresh).
                            val u = cur.localConfiguration?.uri?.buildUpon()?.encodedFragment("lo${System.currentTimeMillis()}")?.build()
                            player.replaceMediaItem(i, cur.buildUpon().setUri(u).build()); player.seekTo(i, pos)
                        }
                    }.also { main.postDelayed(it, 1_500) }
                }
                if (state == Player.STATE_READY && !MusicQuality.hi && System.currentTimeMillis() - MusicQuality.smoothSince > 60_000) MusicQuality.hi = true
            }
        })
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
    /** Opened from the Xstream Music icon / widget: the "Xstream Music" opening, then the music page. */
    val splash = MutableStateFlow(false)
    internal val resolved = ConcurrentHashMap<String, String>()

    private var controller: MediaController? = null
    private var connecting = false
    private var pending: (() -> Unit)? = null
    private var appCtx: Context? = null

    private fun item(t: LiveServer.Track): MediaItem = MediaItem.Builder().setMediaId(t.id)
        .setUri(when {
            t.id.startsWith("yt:") && t.format == "ytv" -> "ytmpd://track/${t.id.removePrefix("yt:")}"
            t.id.startsWith("yt:") -> "ytm://track/${t.id.removePrefix("yt:")}"
            else -> "xlive://track/${t.id}"
        })
        .apply { if (t.format == "ytv") setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD) }
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
        if (t != null && t.id != prev) {
            remember(t); appCtx?.let { MusicWidget.update(it, t.title, t.artist, t.artworkURL); ensureMoreRadio(it) }
            if (t.id.startsWith("yt:")) videoMode.value = t.format == "ytv"
        }
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

    /** Add radio results behind the current item without interrupting playback. */
    fun appendRadio(ctx: Context, tracks: List<LiveServer.Track>) = withController(ctx) { mc ->
        val seen = (queue.value.map { it.id } + listOfNotNull(mc.currentMediaItem?.mediaId)).toMutableSet()
        val add = tracks.filter { seen.add(it.id) }
        if (add.isEmpty()) return@withController
        mc.addMediaItems(mc.mediaItemCount, add.map(::item))
        queue.value = (queue.value + add).ifEmpty { add }
    }

    /** Replace future radio items; the song already playing and its position remain intact. */
    fun replaceRadio(ctx: Context, tracks: List<LiveServer.Track>) = withController(ctx) { mc ->
        val at = mc.currentMediaItemIndex.coerceAtLeast(0)
        if (mc.mediaItemCount > at + 1) mc.removeMediaItems(at + 1, mc.mediaItemCount)
        val cur = queue.value.getOrNull(at) ?: now.value.track
        val future = tracks.filterNot { it.id == cur?.id }
        if (future.isNotEmpty()) mc.addMediaItems(mc.mediaItemCount, future.map(::item))
        queue.value = listOfNotNull(cur) + future
    }

    val radioLabel = MutableStateFlow("")

    // ───── Xstream Music: radio, Song ↔ Video ─────
    /** song id ↔ its other version (from YouTube Music's queue): a Song with a music video, or the reverse. */
    val counterparts = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** The Now playing page shows the picture (Video) or the cover (Song). */
    val videoMode = MutableStateFlow(false)
    private var radioJob: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    fun ytTrack(i: YtmItem, from: String? = null) = LiveServer.Track(id = "yt:" + i.id, title = i.title, artist = i.subtitle.substringAfter("Song • ").substringAfter("Video • ").substringBefore(" • "),
        album = from, durationMs = i.durationMs, format = if (i.kind == YtmKind.VIDEO) "ytv" else "yt", artworkURL = i.thumb)

    /** Plays [i] and fills the queue with its YouTube Music radio (like tapping a song in YouTube Music). */
    fun startRadio(ctx: Context, i: YtmItem) {
        videoMode.value = i.kind == YtmKind.VIDEO
        play(ctx, listOf(ytTrack(i)), 0)
        radioLabel.value = i.title + " mix"
        radioJob?.cancel()
        radioJob = scope.launch {
            val n = runCatching { YtMusic.next(i.id, "RDAMVM" + i.id) }.getOrNull() ?: return@launch
            counterparts.putAll(n.counterparts)
            n.title?.let { radioLabel.value = it }
            replaceRadio(ctx, n.queue.filter { it.id != i.id }.map { ytTrack(it) })
        }
    }

    /** Plays an album / playlist as it is (Play all / Shuffle / a song in it). */
    fun playList(ctx: Context, list: List<YtmItem>, index: Int, label: String) {
        val songs = list.filter { it.kind == YtmKind.SONG || it.kind == YtmKind.VIDEO }
        if (songs.isEmpty()) return
        val at = songs.indexOf(list.getOrNull(index)).coerceAtLeast(0)
        videoMode.value = false
        radioLabel.value = label
        play(ctx, songs.map { ytTrack(it, label) }, at)
    }

    /** Adds the radio's next songs when the queue is about to end. */
    fun ensureMoreRadio(ctx: Context) {
        val mc = controller ?: return
        if (mc.mediaItemCount - mc.currentMediaItemIndex > 3 || radioJob?.isActive == true) return
        val last = queue.value.lastOrNull()?.id?.removePrefix("yt:") ?: return
        radioJob = scope.launch {
            val n = runCatching { YtMusic.next(last, "RDAMVM$last") }.getOrNull() ?: return@launch
            counterparts.putAll(n.counterparts)
            appendRadio(ctx, n.queue.map { ytTrack(it) })
        }
    }

    fun addNext(ctx: Context, i: YtmItem) = playNext(ctx, ytTrack(i))
    fun addToQueue(ctx: Context, i: YtmItem) = withController(ctx) { mc ->
        val t = ytTrack(i); mc.addMediaItem(item(t)); queue.value = queue.value + t
    }
    fun removeAt(i: Int) { controller?.let { if (i in 0 until it.mediaItemCount && i != it.currentMediaItemIndex) { it.removeMediaItem(i); queue.value = queue.value.toMutableList().apply { if (i < size) removeAt(i) } } } }
    fun dismissQueue() { controller?.let { mc -> val at = mc.currentMediaItemIndex; if (mc.mediaItemCount > at + 1) mc.removeMediaItems(at + 1, mc.mediaItemCount); queue.value = queue.value.take(at + 1) } }

    /**
     * The picture: on (inline: at most [maxHeight] px tall; full screen: any size the connection carries) or off (Song:
     * only the sound is downloaded). Track selection only: the song never stops.
     */
    fun setVideo(on: Boolean, maxHeight: Int = Int.MAX_VALUE) {
        val mc = controller ?: return
        mc.trackSelectionParameters = mc.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, !on)
            .apply { if (maxHeight == Int.MAX_VALUE) clearVideoSizeConstraints() else setMaxVideoSize(Int.MAX_VALUE, maxHeight) }
            .build()
    }

    /** Switches the song playing to its other version (Song → its music video, Video → the song) at the same second. */
    fun swapVersion(ctx: Context, toId: String, asVideo: Boolean) = withController(ctx) { mc ->
        val i = mc.currentMediaItemIndex; val pos = mc.currentPosition
        val cur = queue.value.getOrNull(i) ?: return@withController
        val t = cur.copy(id = "yt:$toId", format = if (asVideo) "ytv" else "yt")
        mc.replaceMediaItem(i, item(t)); mc.seekTo(i, pos); mc.play()
        queue.value = queue.value.toMutableList().apply { set(i, t) }
        publish()
    }

    private val sleepHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** Sleep timer: music pauses after [ms]. */
    fun sleepIn(ms: Long) { sleepHandler.removeCallbacksAndMessages(null); sleepHandler.postDelayed({ controller?.pause() }, ms) }

    fun attachVideo(tv: android.view.TextureView?) { controller?.let { if (tv != null) it.setVideoTextureView(tv) else it.clearVideoSurface() } }
    fun videoSize(): androidx.media3.common.VideoSize = controller?.videoSize ?: androidx.media3.common.VideoSize.UNKNOWN
    fun hasVideoTrack(): Boolean = controller?.currentTracks?.groups?.any { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO } == true

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
    /** Widget buttons: "prev", "toggle", "next" ([done] is called once it's sent). */
    fun control(ctx: Context, action: String, done: () -> Unit) {
        val timeout = android.os.Handler(android.os.Looper.getMainLooper())
        timeout.postDelayed({ done() }, 8_000)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            withController(ctx) { mc ->
                when (action) {
                    "prev" -> if (mc.currentPosition > 4_000) mc.seekTo(0) else mc.seekToPreviousMediaItem()
                    "next" -> mc.seekToNextMediaItem()
                    else -> if (mc.isPlaying) mc.pause() else { if (mc.playbackState == Player.STATE_IDLE) mc.prepare(); mc.play() }
                }
                timeout.removeCallbacksAndMessages(null); done()
            }
        }
    }

    fun pauseForVideo() { controller?.takeIf { it.isPlaying }?.pause() }
    /** Position of the song playing now (for "Video": the music video starts at the same second). */
    fun positionMs(): Long = controller?.currentPosition ?: 0L
}


/** Serves "ytmpd://track/<id>" (the DASH manifest made by [YtDirect.dashManifest]); everything else goes upstream. */
@OptIn(UnstableApi::class)
private class MpdOrUpstream(private val up: androidx.media3.datasource.DataSource) : androidx.media3.datasource.DataSource {
    private var mem: androidx.media3.datasource.ByteArrayDataSource? = null
    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = up.addTransferListener(transferListener)
    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
        if (dataSpec.uri.scheme == "ytmpd") {
            val id = dataSpec.uri.lastPathSegment.orEmpty()
            val xml = runBlocking { YtDirect.dashManifest(id, MusicQuality.hi) } ?: throw IOException("This video isn't available right now")
            return androidx.media3.datasource.ByteArrayDataSource(xml.toByteArray()).also { mem = it }.open(dataSpec)
        }
        mem = null
        return up.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = mem?.read(buffer, offset, length) ?: up.read(buffer, offset, length)
    override fun getUri(): Uri? = mem?.uri ?: up.uri
    override fun getResponseHeaders(): Map<String, List<String>> = if (mem != null) emptyMap() else up.responseHeaders
    override fun close() { mem?.close(); mem = null; up.close() }
}
