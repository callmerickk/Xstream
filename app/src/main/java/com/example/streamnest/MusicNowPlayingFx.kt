package com.example.streamnest

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Music prompt 5 (musicLook2), part 6: Now playing polish.
 * Four pieces, all plain values and lambdas, because NowPlaying lives in MusicApp.kt:
 *  - MusicCoverGlow       : soft glow in the cover's own colour, breathing slowly
 *  - MusicNowBackdrop     : slow, blurred, drifting copy of the cover behind everything, cross-fading per song
 *  - MusicSongCrossfade   : 600 ms cross-fade for anything that depends on the song (the cover itself)
 *  - MusicCanvasClip      : muted looping 6 s video behind the cover (Wi-Fi only, second small player)
 * Only called when Feature.musicLook2 is on. The heartbeat seek bar is not touched.
 */

/** Soft glow behind [content] (the cover). [color] is normally rememberMusicCover(url).deep, or the old cover colour. */
@Composable
fun MusicCoverGlow(color: Color, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c by animateColorAsState(color, tween(600), label = "glowColor")
    val t = rememberInfiniteTransition(label = "glowBreath")
    val breath by t.animateFloat(
        0.50f, 0.82f,
        infiniteRepeatable(tween(4500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    Box(
        modifier = modifier.drawBehind {
            // Read inside the draw block, so only drawing is redone, not the layout.
            val r = size.maxDimension * 0.85f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(c.copy(alpha = breath), c.copy(alpha = breath * 0.35f), Color.Transparent),
                    center = center, radius = r,
                ),
                radius = r, center = center,
            )
        },
        content = content,
    )
}

/**
 * Slow blurred cover backdrop for Now playing. Put it as the FIRST child of the Now playing root, under the content.
 * The copy drifts and zooms very slowly (40 s and 55 s loops) and cross-fades in 600 ms when the song changes.
 * [tint] is the dark scrim colour (the cover's dark tone, or the old gradient base).
 * Android 11 or older cannot blur: the cover stays sharp and a heavier scrim goes over it (logged once in the Glass backdrop).
 */
@Composable
fun MusicNowBackdrop(artworkUrl: String?, tint: Color, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val canBlur = MusicLook2.canBlur
    val t = rememberInfiniteTransition(label = "drift")
    val zoom by t.animateFloat(1.25f, 1.45f, infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Reverse), label = "zoom")
    val dx by t.animateFloat(-24f, 24f, infiniteRepeatable(tween(55_000, easing = LinearEasing), RepeatMode.Reverse), label = "dx")
    val scrimTop = if (canBlur) 0.55f else 0.80f
    val scrimBottom = if (canBlur) 0.92f else 0.96f
    Box(modifier.fillMaxSize().clipToBounds()) {
        Crossfade(targetState = artworkUrl, animationSpec = tween(600), label = "nowCover") { url ->
            if (url != null) {
                val req = remember(url) { ImageRequest.Builder(ctx).data(url).size(256).build() }
                Box(Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = dx }) {
                    AsyncImage(
                        model = req, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().then(
                            if (canBlur) Modifier.blur(40.dp, BlurredEdgeTreatment.Unbounded) else Modifier
                        ),
                    )
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint.copy(alpha = scrimTop), tint.copy(alpha = scrimBottom)))))
    }
}

/** 600 ms cross-fade when [key] (normally the artwork URL or video id) changes. */
@Composable
fun <T> MusicSongCrossfade(key: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    Crossfade(targetState = key, modifier = modifier, animationSpec = tween(600), label = "songFade") { content(it) }
}

/**
 * The canvas clip: a muted, looping 6 s piece of the song's music video, full-bleed behind the cover, under a dark scrim.
 *
 * Rules (all logged in plain words):
 *  - only on Wi-Fi (MusicLook2.onWifi); on mobile data nothing is loaded;
 *  - [resolveUrl] must give a direct video stream URL for [videoId]; no URL means the clip is skipped;
 *  - the video track only (audio switched off, volume 0) and at most 480p, so it is light on data;
 *  - it never takes audio focus, so the real song is not disturbed;
 *  - it pauses when the song is paused or the app is in the background, and the player is released when the screen closes;
 *  - the clip starts 15 s into the video (intros are often black). If that fails, it tries once from 0:00, then gives up.
 *
 * [enabled] false (for example while NowPlaying shows the real video) draws nothing and loads nothing.
 * [mediaSourceFactory]: pass the one the main player uses if its streams need special headers; null uses the default.
 */
@Composable
fun MusicCanvasClip(
    videoId: String?,
    playing: Boolean,
    enabled: Boolean,
    resolveUrl: suspend (String) -> String?,
    modifier: Modifier = Modifier,
    mediaSourceFactory: MediaSource.Factory? = null,
) {
    if (!Feature.musicLook2) return
    val ctx = LocalContext.current
    val url by produceState<String?>(null, videoId, enabled) {
        value = null
        if (!enabled || videoId == null) return@produceState
        if (!MusicLook2.onWifi(ctx)) {
            MusicLog.add("Canvas clip: not on Wi-Fi, so no clip for $videoId")
            return@produceState
        }
        val u = runCatching { resolveUrl(videoId) }.getOrNull()
        if (u.isNullOrBlank()) {
            MusicLog.add("Canvas clip: no video stream URL for $videoId, so the clip is skipped")
        } else {
            MusicLog.add("Canvas clip: got a video stream for $videoId, starting the muted 6 s loop")
            value = u
        }
    }
    val u = url ?: return
    CanvasPlayer(u, videoId.orEmpty(), playing, mediaSourceFactory, modifier)
}

@OptIn(UnstableApi::class)
@Composable
private fun CanvasPlayer(url: String, id: String, playing: Boolean, factory: MediaSource.Factory?, modifier: Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var shown by remember(url) { mutableStateOf(false) }
    var ratio by remember(url) { mutableFloatStateOf(16f / 9f) }
    var foreground by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }

    val player = remember(url) {
        val b = if (factory != null) ExoPlayer.Builder(ctx, factory) else ExoPlayer.Builder(ctx)
        b.build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setMaxVideoSize(854, 480)
                .build()
        }
    }

    DisposableEffect(player) {
        val tries = intArrayOf(0)
        fun load(startMs: Long) {
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs).setEndPositionMs(startMs + 6_000).build()
            player.setMediaItem(MediaItem.Builder().setUri(url).setClippingConfiguration(clip).build())
            player.prepare()
        }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { shown = true }
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width > 0 && v.height > 0) ratio = v.width * v.pixelWidthHeightRatio / v.height
            }
            override fun onPlayerError(e: PlaybackException) {
                if (tries[0] == 0) {
                    tries[0] = 1
                    MusicLog.add("Canvas clip: could not start at 0:15 for $id (${e.errorCodeName}), trying from 0:00")
                    load(0)
                } else {
                    shown = false
                    player.release()
                    MusicLog.add("Canvas clip: gave up for $id (${e.errorCodeName}), the cover stays without a clip")
                }
            }
        }
        player.addListener(listener)
        load(15_000)
        val obs = LifecycleEventObserver { _, _ ->
            foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, playing, foreground) {
        // release() may already have run after a failure; then this does nothing.
        runCatching { player.playWhenReady = playing && foreground }
    }

    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(600), label = "canvasFade")
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        // Fill the box like "crop": the video is scaled to cover it, the overflow is cut off.
        val boxRatio = maxWidth / maxHeight
        val w = if (ratio > boxRatio) maxHeight * ratio else maxWidth
        val h = if (ratio > boxRatio) maxHeight else maxWidth / ratio
        AndroidView(
            factory = { c ->
                TextureView(c).also { tv ->
                    tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { runCatching { player.setVideoTextureView(tv) } }
                        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
                        override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
                    }
                    runCatching { player.setVideoTextureView(tv) }
                }
            },
            modifier = Modifier.requiredSize(w, h).alpha(alpha),
        )
        // Dark scrim so the cover, title and buttons stay easy to read.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f * alpha)))
    }
}
