package com.example.streamnest

import androidx.compose.foundation.gestures.detectDragGestures
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.util.TypedValue
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.rounded.Pause as RoundedPause
import androidx.compose.material.icons.rounded.PlayArrow as RoundedPlay
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.animation.core.animateDp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Inter: the clean geometric sans used by the player (bundled, SIL Open Font License). */
val InterFont = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private fun subMime(url: String) = when {
    url.contains(".vtt", true) -> MimeTypes.TEXT_VTT
    url.contains(".ass", true) || url.contains(".ssa", true) -> MimeTypes.TEXT_SSA
    else -> MimeTypes.APPLICATION_SUBRIP
}

/** Opens a stream in another installed player (VLC, MX Player…) through Android's app chooser. */
fun openExternal(ctx: android.content.Context, url: String, title: String) {
    try {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*").putExtra("title", title)
        ctx.startActivity(Intent.createChooser(i, "Open with"))
    } catch (e: Exception) {
        Snack.show("No other video player found")
    }
}

const val PlayerUserAgent = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

/** Builds the video player: fast-start buffering, the app's HTTP client (DNS setting), FFmpeg audio, remembered languages. */
@OptIn(UnstableApi::class)
/** A key press from a real remote, keyboard or game pad (not a virtual / injected one). */
/** Sound goes to wired / USB earphones, a Bluetooth headset or speaker, or a hearing aid (not the phone's speaker). */
fun externalAudio(am: AudioManager): Boolean = runCatching {
    val ext = setOfNotNull(
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
        if (android.os.Build.VERSION.SDK_INT >= 28) android.media.AudioDeviceInfo.TYPE_HEARING_AID else null,
        if (android.os.Build.VERSION.SDK_INT >= 31) android.media.AudioDeviceInfo.TYPE_BLE_HEADSET else null,
        if (android.os.Build.VERSION.SDK_INT >= 31) android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER else null,
    )
    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in ext }
}.getOrDefault(false)

/** "hi" → "hindi" (the names the links use). */
fun langName(code: String): String? = runCatching { java.util.Locale(code).getDisplayLanguage(java.util.Locale.ENGLISH).lowercase() }.getOrNull()?.takeIf { it.isNotBlank() && it != code }

private fun realKeys(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
    val n = e.nativeKeyEvent
    if (n.deviceId == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD || n.deviceId < 0) return false
    if (n.flags and android.view.KeyEvent.FLAG_FROM_SYSTEM == 0) return false
    val dev = n.device ?: return false
    if (dev.isVirtual) return false
    val src = n.source
    return (src and android.view.InputDevice.SOURCE_DPAD) == android.view.InputDevice.SOURCE_DPAD ||
        (src and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
        ((src and android.view.InputDevice.SOURCE_KEYBOARD) == android.view.InputDevice.SOURCE_KEYBOARD && (android.os.Build.VERSION.SDK_INT < 29 || dev.isExternal))
}

fun buildPlayer(ctx: android.content.Context, vm: AppViewModel, fast: Boolean, cache: Boolean = !BuildConfig.IS_TV, meter: Boolean = !BuildConfig.IS_TV, hold: HeaderHold? = null): ExoPlayer {
    // Smooth like Nuvio: start after ~1.5 s of video, then keep up to a minute ready (capped at ~200 MB so
    // big 4K files can't run the device out of memory), plus 20 s behind you so short rewinds don't reload.
    // TV sticks (Fire TV: 1 GB RAM) get a smaller budget: 64 MB, up to 40 s ahead and 10 s behind.
    val tv = BuildConfig.IS_TV
    // Memory budget by the phone's RAM (like Nuvio): small phones keep less video in memory, big ones more.
    val ramGb = runCatching {
        val mi = android.app.ActivityManager.MemoryInfo()
        (ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        mi.totalMem / (1024.0 * 1024 * 1024)
    }.getOrDefault(4.0)
    // TV 0.7: a little more ahead (30–60 s, 96 MB on 2 GB sticks) — the log showed short stalls every ~30 s with only a
    // few seconds buffered.
    val budgetMb = when { tv -> if (ramGb >= 1.6) 96 else 72; ramGb < 3.2 -> 96; ramGb < 4.8 -> 160; ramGb < 6.8 -> 220; else -> 300 }
    val load = DefaultLoadControl.Builder()
        .setBufferDurationsMs(if (tv) 30_000 else 25_000, if (tv) 60_000 else 60_000, if (fast) 1_500 else 2_500, if (fast) 3_000 else 5_000)
        .setTargetBufferBytes(budgetMb * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false)
        .setBackBuffer(if (tv) 10_000 else 20_000, true)
        .build()
    // Streams go through the app's HTTP client (DNS setting) with the addon's request headers.
    val http = OkHttpDataSource.Factory(Api.http).setUserAgent(PlayerUserAgent)
    // Phones: read the saved video first (instant play), then the network. (Source Test measures without it.)
    val dataSource = DefaultDataSource.Factory(ctx, withStreamHeaders(http, hold)).let { if (cache) StreamCache.factory(ctx, it) else it }
    // The phone's own hardware decoders first (smoothest); FFmpeg only for audio the phone can't decode
    // (Dolby Digital / DD+ / DTS / TrueHD). Asynchronous decoder feeding = steadier frame pacing.
    // Dolby Vision on phones without a Dolby Vision decoder (like Nuvio): play the picture layer underneath with the
    // normal HEVC decoder (DV profiles 7 and 8 carry an HDR10 picture; profile 9 an H.264 one).
    val dvFallback = androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mime, secure, tunneling ->
        val list = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
        if (mime == androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION && list.isEmpty())
            androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(androidx.media3.common.MimeTypes.VIDEO_H265, secure, tunneling)
        else list
    }
    // Phones: the sound also passes a meter (subtitle auto sync), unchanged.
    // NextLib renderers (FFmpeg audio + video decoders as a fallback after the phone's own).
    val renderers = if (!meter) io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory(ctx) else object : io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory(ctx) {
        override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink? =
            androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(SpeechMeter()))
                .build()
    }
        .setMediaCodecSelector(dvFallback)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
        .forceEnableMediaCodecAsynchronousQueueing()
    return ExoPlayer.Builder(ctx, renderers).setLoadControl(load).setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
        // Seeking may land up to 0.3 s off the exact spot: much faster jumps on big files (as CloudStream does).
        .setSeekParameters(androidx.media3.exoplayer.SeekParameters(300_000L, 300_000L))
        .build().apply {
        val aLang = vm.playerPref("audio")
        val sLang = vm.playerPref("subs")
        trackSelectionParameters = trackSelectionParameters.buildUpon().apply {
            // Inside multi-audio files: your remembered language, else your Quick Start order (Hindi → English…).
            val codes = mapOf("hindi" to "hi", "english" to "en", "tamil" to "ta", "telugu" to "te", "malayalam" to "ml", "kannada" to "kn",
                "bengali" to "bn", "marathi" to "mr", "punjabi" to "pa", "japanese" to "ja", "korean" to "ko", "spanish" to "es", "french" to "fr",
                "german" to "de", "chinese" to "zh", "russian" to "ru", "italian" to "it", "portuguese" to "pt", "arabic" to "ar", "turkish" to "tr")
            val prefs = (listOf(aLang).filter { it.isNotEmpty() && it != "off" } + vm.qsLangs().mapNotNull { codes[it] }).distinct()
            if (prefs.isNotEmpty()) setPreferredAudioLanguages(*prefs.toTypedArray())
            when {
                sLang == "off" -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                sLang.isNotEmpty() -> setPreferredTextLanguage(sLang)
            }
        }.build()
    }
}

fun subtitleConfigs(subs: List<Subtitle>) = subs.filter { it.url.isNotBlank() }.map {
    MediaItem.SubtitleConfiguration.Builder(Uri.parse(it.url)).setId("side:" + it.url.hashCode()).setMimeType(subMime(it.url)).setLanguage(it.lang).setLabel(it.lang).build()
}

/** A subtitle sent along with the link or by a subtitle addon (not one inside the video file). */
fun androidx.media3.common.Format.isSidecar(): Boolean = id?.contains("side:") == true

/** A video prepared in the background on the title page, handed to the player when you press Play. */
class Preloaded(val key: String, val url: String, val subs: List<Subtitle>, val source: String?, val addon: String?, val quick: List<StreamItem>, val player: ExoPlayer)

object Preloader {
    private var cur: Preloaded? = null
    fun set(p: Preloaded) { cur?.let { if (it !== p) runCatching { it.player.release() } }; cur = p }
    fun has(key: String) = cur?.key == key
    fun take(key: String): Preloaded? = cur?.takeIf { it.key == key }?.also { cur = null }
    fun release(key: String? = null) { cur?.let { if (key == null || it.key == key) { runCatching { it.player.release() }; cur = null } } }
}

/** One way of playing a link. */
data class Way(val vlc: Boolean, val hw: Boolean, val relay: Boolean) {
    fun label() = if (!vlc) "ExoPlayer" else "VLC " + (if (hw) "HW" else "SW") + (if (relay) " + relay" else "")
}

/**
 * The order in which a link is tried. Links that need more headers than VLC can send go through the relay straight away.
 * Big files ([big]) stay on hardware decoding: software decoding is only the very last resort for them.
 */
fun waysFor(url: String, engine: String, big: Boolean = false, manifest: Boolean = false): List<Way> {
    val h = StreamHeaders.forUrl(url)
    // DASH / HLS: ExoPlayer first whatever the engine setting (it plays manifests best), and never through the relay
    // (a manifest's relative segment links can't be followed through it).
    if (manifest) return listOf(Way(false, true, false), Way(true, true, false), Way(true, false, false))
    val needsRelay = h.keys.any { !it.equals("User-Agent", true) && !it.equals("Referer", true) }
    val vlcWays = when {
        needsRelay -> listOf(Way(true, true, true), Way(true, false, true))
        h.isNotEmpty() -> listOf(Way(true, true, false), Way(true, false, false), Way(true, true, true))
        else -> listOf(Way(true, true, false), Way(true, false, false))
    }
    val exo = Way(false, true, false)
    val all = if (engine == "exo") listOf(exo) + vlcWays else vlcWays + exo
    if (!big) return all
    val software = all.filter { it.vlc && !it.hw }
    return all.filter { !(it.vlc && !it.hw) } + software
}

/** VLC network cache for a stream: small files start fast (1 s), big / heavy files get more room so they don't stutter. */
fun cachingFor(s: StreamItem?): Int {
    if (s == null) return 1000
    val size = s.stream.behaviorHints?.videoSize ?: 0L
    val t = s.tags()
    val hevc = "hevc" in t
    val gb = 1024L * 1024L * 1024L
    return when {
        size > 6L * gb || "4k" in t || "remux" in t || "10bit" in t || (hevc && s.resolution() > 1080) -> 4000
        size > 5L * gb / 2L || hevc -> 2500
        else -> 1000
    }
}

/**
 * The next episode's link, found while the current one is still playing (last quarter), so it starts at once.
 * Consumed by the next episode's "Finding stream source" step (also in Quick Start mode).
 */
object NextUp {
    var key: String? = null
    var url: String? = null
    var source: String? = null
    var addon: String? = null
    var subs: List<Subtitle> = emptyList()
    var at: Long = 0

    fun set(k: String, u: String, src: String?, add: String?, s: List<Subtitle>) {
        key = k; url = u; source = src; addon = add; subs = s; at = System.currentTimeMillis()
    }
    fun fresh(k: String): Boolean = key == k && url != null && System.currentTimeMillis() - at < STREAM_CACHE_MS
    fun clear(k: String? = null) {
        if (k != null && key != k) return
        key = null; url = null; source = null; addon = null; subs = emptyList(); at = 0
    }
}

/** Opens the first megabyte of a link in the background, so the server (and DNS / TLS) is warm when it plays. */
fun warmUpLink(url: String) {
    if (!url.startsWith("http")) return
    runCatching {
        val b = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-1048575").header("User-Agent", PlayerUserAgent)
        StreamHeaders.forUrl(url).forEach { (k, v) -> runCatching { b.header(k, v) } }
        Api.http.newCall(b.build()).execute().use { r ->
            r.body?.byteStream()?.use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0
                while (total < 1_048_576) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
            }
        }
    }
}

/** Copies the frame on screen (VLC or ExoPlayer surface) so it can stay visible while another link loads. */
fun captureFrame(root: View, onDone: (android.graphics.Bitmap?) -> Unit) {
    fun find(v: View, pick: (View) -> Boolean): View? {
        if (pick(v)) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), pick)?.let { return it }
        return null
    }
    try {
        (find(root) { it is android.view.TextureView && it.isAvailable } as? android.view.TextureView)?.let { tv ->
            onDone(tv.getBitmap(tv.width / 2, tv.height / 2)); return
        }
        val sv = find(root) { it is android.view.SurfaceView && it.width > 0 && it.visibility == View.VISIBLE } as? android.view.SurfaceView
        if (sv == null || !sv.holder.surface.isValid || android.os.Build.VERSION.SDK_INT < 24) { onDone(null); return }
        val bmp = android.graphics.Bitmap.createBitmap((sv.width / 2).coerceAtLeast(1), (sv.height / 2).coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        android.view.PixelCopy.request(sv, bmp, { r -> onDone(if (r == android.view.PixelCopy.SUCCESS) bmp else null) }, android.os.Handler(android.os.Looper.getMainLooper()))
    } catch (e: Throwable) { onDone(null) }
}

private data class TrackOpt(val label: String, val group: Tracks.Group, val index: Int, val selected: Boolean)

/** What is actually playing (the link can change: finding, fallbacks, switching source). */
private data class Now(val url: String, val subs: List<Subtitle>, val source: String?, val addon: String?)

private val noRipple = MutableInteractionSource()

@OptIn(UnstableApi::class)
@kotlin.OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
fun PlayerScreen(
    vm: AppViewModel,
    p: Screen.Player,
    onBack: () -> Unit,
    onReplace: (Screen.Player) -> Unit,
    onProgress: (pos: Long, dur: Long, url: String, subs: List<Subtitle>) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val texts by vm.texts.collectAsStateWithLifecycle()
    fun opt(k: String) = texts[k] ?: Defaults[k] ?: ""

    // The audio language you last chose for this title (or series) goes first while it plays.
    remember(p.key) { LangPick.current = vm.titleLang(p.key); 0 }
    val type = p.type.ifBlank { if (p.key.contains(":")) "series" else "movie" }
    var meta by remember { mutableStateOf(p.meta) }
    // A video already prepared on the title page starts instantly.
    val pre = remember {
        Preloader.take(p.key)?.let { pl ->
            val bad = pl.player.playerError != null || pl.player.playbackState == androidx.media3.common.Player.STATE_IDLE
            // A link you picked yourself always wins over the one prepared in the background.
            val other = p.url.isNotBlank() && p.url != pl.url
            if (other) { runCatching { pl.player.release() }; PlayTiming.mark("paused player was for another link — opening the one you chose"); null }
            else if (bad) { runCatching { pl.player.release() }; PlayTiming.mark("paused player was broken — opening the link again"); null }
            else pl.also { StreamHeaders.use(it.url) }
        }
    }
    var now by remember {
        mutableStateOf(
            pre?.let { Now(it.url, it.subs, it.source, it.addon) } ?: if (p.url.isNotBlank()) Now(p.url, p.subs, p.source, p.addon) else null,
        )
    }
    var quickList by remember { mutableStateOf(pre?.quick ?: emptyList()) }
    var quality by remember { mutableStateOf(vm.playerPref("quality").ifEmpty { vm.texts.value["qsQuality"] ?: "auto" }) }
    var qualityMenu by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf(p.candidates) }
    // Every stream we know for this title (for language versions in the Audio panel).
    var known by remember { mutableStateOf(p.candidates) }
    var otherLangsOpen by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var playing by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(p.resumeMs) }
    var dur by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var buffering by remember { mutableStateOf(true) }
    /** After "Upgrade": the MovieBox link it came from (if the HD file fails, playback goes back to it) and the HD link. */
    var upgradedFrom by remember { mutableStateOf<StreamItem?>(null) }
    var upgradedUrl by remember { mutableStateOf<String?>(null) }
    // Plugin mode's quiet swap: the second player shown above the main picture (it fades in, then stays as the screen).
    var shadow by remember { mutableStateOf<ExoPlayer?>(null) }
    var shadowAlpha by remember { mutableFloatStateOf(0f) }
    var ottSwapping by remember { mutableStateOf(false) }
    /** When the viewer last jumped: a 4K file loading after a jump gets a full minute before any fallback. */
    var seekedAt by remember { mutableLongStateOf(0L) }
    /** HD files made ready during a quick play (start + index saved): "4k" → 1 file, "fhd" → up to 2. Quality menu + upgrade. */
    var hdReady by remember { mutableStateOf<Map<String, List<StreamItem>>>(emptyMap()) }
    /** Files whose start + index are saved for the top visual-tag groups in Sources (first file of the top 3 groups). */
    var tagReady by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** The link each language last played from (switching back to a language reuses it). */
    val urlByLang = remember { HashMap<String, String>() }
    /** After a fallback to MovieBox, the next upgrade offer waits until then. */
    var upCooldownUntil by remember { mutableLongStateOf(0L) }
    var firstFrame by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (p.url.isBlank() && pre == null) "Finding stream source" else "Buffering…") }
    var failure by remember { mutableStateOf<String?>(null) }
    var findRequest by remember { mutableIntStateOf(if (p.url.isBlank() && pre == null) 1 else 0) }
    var controls by remember { mutableStateOf(false) }
    var poke by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    /** Intros / recaps already auto-skipped in this video (each only once, so you can seek back into them). */
    val autoSkipped = remember { mutableSetOf<String>() }
    /** A finger is on the controls (they stay up until it lifts). */
    var touchingControls by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<String?>(null) }
    /** Where the Audio & Subtitles button is (the bubble grows out of it). */
    var avBtnCenter by remember { mutableStateOf<Offset?>(null) }
    /** Where each player button is: every pop-up grows out of the button that opened it. */
    val btnCenters = remember { mutableStateMapOf<String, Offset>() }
    fun at(k: String): Modifier = Modifier.onGloballyPositioned { c -> btnCenters[k] = c.boundsInRoot().center }
    var lastPanel by remember { mutableStateOf("sources") }
    var showEpisodes by remember { mutableStateOf(false) }
    // Remembered per profile: aspect ratio, audio language, subtitle language (Help: auto-applied next time).
    var aspectIdx by remember { mutableIntStateOf(vm.playerPref("aspect").toIntOrNull()?.coerceIn(0, AspectModes.lastIndex) ?: 0) }
    var resize by remember { mutableIntStateOf(AspectModes[aspectIdx].second) }
    var holdSpeed by remember { mutableStateOf<Float?>(null) }
    var tapJob by remember { mutableStateOf<Job?>(null) }
    val haptics = LocalHapticFeedback.current
    var speed by remember { mutableFloatStateOf(1f) }
    var advisory by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showAdvisory by remember { mutableStateOf(false) }
    var segs by remember { mutableStateOf<Segments?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    /** Info opened by hand (› More › Info): stays while the video plays, until you tap it away. */
    var infoManual by remember { mutableStateOf(false) }
    LaunchedEffect(showInfo) { if (!showInfo) infoManual = false }
    var seekHint by remember { mutableStateOf<String?>(null) }
    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    /** An online subtitle drawn by the app (no reload), its timing shift, and the size preview while dragging Size. */
    var ext by remember { mutableStateOf<ExtActive?>(null) }
    var subOffsetMs by remember { mutableLongStateOf(0L) }
    /** Follow the speaker (beta): where the talking person is across the picture (0.5 = centre). */
    var speakerAt by remember { mutableFloatStateOf(0.5f) }
    var subPreview by remember { mutableStateOf(false) }
    var manualSync by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Speech.clear() }
    var aspectLabel by remember { mutableStateOf<String?>(null) }
    // Cinematic zoom (phones): a second pinch-out after "Zoomed to fill" zooms a little further (like YouTube), cutting
    // the black bars that are part of the picture in wide films. Pinch in steps back.
    var cinematic by remember { mutableStateOf(false) }
    val cineScale by animateFloatAsState(if (cinematic && !BuildConfig.IS_TV) 1.2f else 1f, tween(260), label = "cine")
    var hud by remember { mutableStateOf<Pair<String, Float>?>(null) }
    // ───── Remote / keyboard (TV): a focusable root that reads the keys; D-pad seeking moves a pending target first ─────
    val rootFocus = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val qualityFocus = remember { FocusRequester() }
    val failFocus = remember { FocusRequester() }
    var rootFocused by remember { mutableStateOf(false) }
    var rootHasFocus by remember { mutableStateOf(false) }
    var panelHasFocus by remember { mutableStateOf(false) }
    var menuHasFocus by remember { mutableStateOf(false) }
    var pendingSeek by remember { mutableStateOf<Long?>(null) }
    // TV controls: which row the remote is on (timeline / Quality+Audio / Watch Next+More) and which button.
    var tvZone by remember { mutableStateOf("bar") }
    var tvTop by remember { mutableIntStateOf(0) }
    var tvBot by remember { mutableIntStateOf(0) }
    // TV: the simple Quality bubble, the "better quality ready" pill and the last seek bubble (so it never flips sides).
    var tvQ by remember { mutableStateOf(false) }
    var tvUp by remember { mutableStateOf<StreamItem?>(null) }
    var tvUpFallback by remember { mutableStateOf(false) }
    var tvUpAt by remember { mutableLongStateOf(0L) }
    var tvUpHidden by remember { mutableStateOf(false) }
    var tvPlayTitle by remember { mutableStateOf<((Meta) -> Unit)?>(null) }
    // TV: the press that opened a bubble must not also pick its first row when OK is let go.
    var swallowOkUp by remember { mutableStateOf(false) }
    // TV: a panel opened from ❯ More goes back to More on Back.
    var panelFromMore by remember { mutableStateOf(false) }
    // TV: the pills at the bottom right (Skip Intro · Upgrade quality · Next episode) the remote can reach with ▲.
    var tvSkipOn by remember { mutableStateOf(false) }
    var tvNextOn by remember { mutableStateOf(false) }
    var tvPillMode by remember { mutableStateOf(false) }
    var tvPillIdx by remember { mutableIntStateOf(0) }
    var tvSkipAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var seekKeyTick by remember { mutableIntStateOf(0) }
    var focusRowTick by remember { mutableIntStateOf(0) }
    // Hotstar-style remote: ▲ shows the controls and pauses; ▼ (from the buttons) hides them and plays on.
    var pausedByUp by remember { mutableStateOf(false) }
    val seekFocus = remember { FocusRequester() }
    var seekFocused by remember { mutableStateOf(false) }
    var topHasFocus by remember { mutableStateOf(false) }
    var rowHasFocus by remember { mutableStateOf(false) }
    var watchNext by remember { mutableStateOf(false) }
    // Watch Next follows the finger while you swipe it up (-1 = not dragging), then settles open or closed.
    var wnDrag by remember { mutableFloatStateOf(-1f) }
    val wnAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(watchNext) {
        if (wnDrag < 0f) wnAnim.animateTo(if (watchNext) 1f else 0f, if (watchNext) spring(dampingRatio = 0.86f, stiffness = 420f) else tween(240))
    }
    // Seek bubble while the controls are hidden: direction and seconds jumped so far (◀ ▶ = 30 s each).
    var bubble by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    var bubbleTick by remember { mutableIntStateOf(0) }
    // Seek-bar preview frames for the link that is playing (made only while you seek).
    val nowUrl = now?.url
    val thumbsOn = isOn(opt("seekThumbs"))
    val thumbs = remember(nowUrl, thumbsOn) { if (thumbsOn && nowUrl != null) SeekThumbs(nowUrl, StreamHeaders.forUrl(nowUrl)) else null }
    DisposableEffect(thumbs) { onDispose { thumbs?.close() } }
    // Open the file for previews a little after playback starts, so the first seek-bar frame shows quickly.
    LaunchedEffect(thumbs, firstFrame) { if (firstFrame && thumbs != null) { delay(8000); thumbs.warmUp() } }
    val audio = remember { ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var autoNextDone by remember { mutableStateOf(false) }
    var linkSaved by remember { mutableStateOf(false) }
    // Loading scenes: how many addons answered the search, the best quality seen, and the "serving" progress.
    var answered by remember { mutableIntStateOf(0) }
    var sourceTotal by remember { mutableIntStateOf(0) }
    var bestRes by remember { mutableIntStateOf(0) }
    var bufProgress by remember { mutableFloatStateOf(0f) }
    var brightApplied by remember { mutableStateOf(false) }
    // Starting volume (Playback > Volume): the phone's own volume before we changed it (-1 = untouched), restored on leaving.
    var volApplied by remember { mutableStateOf(false) }
    var volBefore by remember { mutableIntStateOf(-1) }
    // Refused links: fresh links are looked up once per title; links that already failed are never tried again.
    var refreshedOnce by remember { mutableStateOf(false) }
    var extendedAll by remember { mutableStateOf(false) }
    var recovering by remember { mutableStateOf(false) }
    // Links the title page already found broken are never tried again here.
    val tried = remember { Prepare.failedFor(p.key).toMutableSet() }
    /** Files that failed to open (not a refusal): their other links are skipped. */
    val badFiles = remember { mutableSetOf<String>() }
    /** Servers that refused once, or sent nothing twice, during this play: their other links go last. */
    val skipHosts = remember { mutableSetOf<String>() }
    val silentCount = remember { mutableMapOf<String, Int>() }
    var loadStartedAt by remember { mutableLongStateOf(0L) }
    /** Saving the backup link's first seconds (after a race), stopped when the player closes. */
    var backupJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { backupJob?.cancel() } }
    /** The same file behind different links: name + size when the addon gives them, else server + path (no signature). */
    fun fileId(url: String): String {
        val s0 = known.firstOrNull { it.stream.url == url } ?: candidates.firstOrNull { it.stream.url == url }
        val f = s0?.stream?.behaviorHints?.filename?.lowercase(); val z = s0?.stream?.behaviorHints?.videoSize
        if (!f.isNullOrBlank() && z != null && z > 0) return "f:$f:$z"
        return runCatching { val u = Uri.parse(url); "u:${u.host}${u.path}" }.getOrDefault(url)
    }
    // Play timing log: start a record when the player opens without a Play press (next episode, Continue Watching…).
    remember {
        if (!PlayTiming.active()) PlayTiming.begin(p.title, "player opened")
        PlayTiming.mark("player screen open" + (if (pre != null) " · using the ready paused player" else "") + (if (p.instant) " · prepared link" else ""))
        true
    }
    val seenAddons = remember { mutableSetOf<String>() }
    fun streamAddonCount(only: Set<String>? = null): Int =
        vm.addons.value.count { vm.isAddonOn(it) && it.supports("stream", type, p.key) && (only == null || it.label() in only) }
    fun noteAnswer(list: List<StreamItem>, done: Boolean) {
        // Play timing log: which addons answered, and when.
        list.groupBy { it.addonName }.forEach { (a, l) -> if (seenAddons.add(a)) PlayTiming.mark("links from $a (${l.size})") }
        if (done) PlayTiming.mark("all addons answered (${list.size} links)", once = "allAnswered")
        answered = if (done) sourceTotal else (answered + 1).coerceAtMost(sourceTotal)
        val r = list.maxOfOrNull { it.resolution() } ?: 0
        if (r > bestRes) bestRes = r
    }

    // Faster start: begin playing after ~1 s of video instead of the default 2.5 s (Playback > Fast start).
    // A var: plugin mode's quiet swap (MovieBox → Netflix-type link) hands the screen over to a second player.
    var player by remember { mutableStateOf(pre?.player ?: buildPlayer(ctx, vm, isOn(texts["fastStart"] ?: Defaults["fastStart"] ?: "yes"))) }

    // ───── Sound (Audio boost / Night mode), Sleep timer, Picture-in-picture ─────
    var boost by remember { mutableStateOf(vm.playerPref("boost") == "yes") }
    var night by remember { mutableStateOf(vm.playerPref("night") == "yes") }
    LaunchedEffect(boost, night, player) { SoundFx.apply(player.audioSessionId, boost, night) }
    DisposableEffect(Unit) { onDispose { SoundFx.release() } }
    /** Sleep timer: stops at this time (elapsed realtime), or at the end of this video. */
    var sleepAt by remember { mutableStateOf<Long?>(null) }
    var sleepEnd by remember { mutableStateOf(false) }
    var inPip by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        PipState.playerOpen = true
        val act = ctx as? androidx.activity.ComponentActivity
        val l = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        act?.addOnPictureInPictureModeChangedListener(l)
        onDispose { PipState.playerOpen = false; PipState.playing = false; act?.removeOnPictureInPictureModeChangedListener(l) }
    }

    // ───── Engines: VLC (default) and ExoPlayer (Playback > Player engine). Same controls for both. ─────
    // Every link is tried several ways before giving up on it:
    //   VLC hardware decoding → VLC software decoding → VLC through the header relay → ExoPlayer
    // ("exo" setting: ExoPlayer first). Only then the next source is tried.
    // Instant play (saved video) needs ExoPlayer: only it reads the saved part.
    val engine = if (p.instant) "exo" else opt("playerEngine").let { if (it == "exo") "exo" else "vlc" }
    val openedAt = remember { SystemClock.elapsedRealtime() }
    var vlc by remember { mutableStateOf<VlcEngine?>(null) }
    var usingVlc by remember { mutableStateOf(false) }
    var vlcTick by remember { mutableIntStateOf(0) } // bumps when VLC tracks may have changed
    var ways by remember { mutableStateOf<List<Way>>(emptyList()) }
    var wayIdx by remember { mutableIntStateOf(0) }
    var loadTick by remember { mutableIntStateOf(0) }
    var midRetries by remember { mutableIntStateOf(0) }
    // Where the video should start / continue (kept while a new link loads, so a switch never restarts at 0:00).
    var resumeAt by remember { mutableLongStateOf(p.resumeMs) }
    // Switching while playing: keep the last frame on screen (blurred) instead of the loading screen.
    var switching by remember { mutableStateOf(false) }
    var switchLabel by remember { mutableStateOf<String?>(null) }
    var frozen by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var videoView by remember { mutableStateOf<View?>(null) }

    fun curPos(): Long = if (usingVlc) vlc?.time ?: 0L else player.currentPosition
    fun curDur(): Long = if (usingVlc) vlc?.length ?: 0L else player.duration.coerceAtLeast(0L)
    // Seek previews for the whole video, made ahead in the background (phones; on mobile data a little later).
    LaunchedEffect(thumbs, firstFrame) {
        val th = thumbs ?: return@LaunchedEffect
        if (!firstFrame || BuildConfig.IS_TV) return@LaunchedEffect
        delay(10_000)
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        if (cm.isActiveNetworkMetered) delay(30_000)
        val d = curDur(); if (d > 0) th.prefetch(d)
    }
    fun curBuffered(): Long = if (usingVlc) curPos() else player.bufferedPosition
    fun isPlayingNow(): Boolean = if (usingVlc) vlc?.isPlaying == true else player.isPlaying
    /** What this player shows, for Watch together. */
    fun roomState(play: Boolean, pos: Long = curPos()) =
        RoomState(url = now?.url.orEmpty(), key = p.key, title = p.title, image = p.image, type = type, pos = pos, playing = play)
    fun seekAbs(ms: Long) {
        seekedAt = SystemClock.uptimeMillis()
        if (usingVlc) vlc?.seek(ms) else player.seekTo(ms)
        if (firstFrame) Together.local(roomState(isPlayingNow(), ms))
        // A jump that lands back at the start: this server can't seek — remembered, it becomes a last backup.
        val u0 = now?.url
        if (!usingVlc && u0 != null && ms > 90_000 && StreamType.quick(u0).let { it != StreamType.HLS && it != StreamType.DASH }) scope.launch {
            delay(6_000)
            if (now?.url == u0 && !buffering && curPos() < ms - 60_000 && curPos() < 60_000) {
                Ranker.markUnseekable(u0); PlayTiming.late("jump to ${fmtTime(ms)} landed at ${fmtTime(curPos())} — server can't seek")
            }
        }
    }
    fun setRate(f: Float) { if (usingVlc) vlc?.rate = f else player.setPlaybackSpeed(f) }
    fun rateNow(): Float = if (usingVlc) vlc?.rate ?: 1f else player.playbackParameters.speed
    fun pauseNow() { if (usingVlc) vlc?.pause() else player.pause(); if (firstFrame) Together.local(roomState(false)) }
    fun playNow() { if (usingVlc) vlc?.play() else player.play() }
    fun togglePlay() { if (isPlayingNow()) pauseNow() else { playNow(); if (firstFrame) Together.local(roomState(true)) } }
    /** Position to keep when the link changes. */
    fun keepPos(): Long = if (firstFrame) curPos().takeIf { it > 0 } ?: resumeAt else resumeAt

    var onEngineError: (String) -> Unit = {}
    val probeSeq = remember { intArrayOf(0) }

    /** The stream entry behind a link, when we know it (for its size and tags). */
    fun itemFor(url: String): StreamItem? =
        known.firstOrNull { it.stream.url == url } ?: candidates.firstOrNull { it.stream.url == url } ?: quickList.firstOrNull { it.stream.url == url }

    fun startVlc(n: Now, startMs: Long, w: Way) {
        runCatching { player.stop() }
        usingVlc = true
        firstFrame = false; failure = null; status = "Buffering…"; linkSaved = false
        val v = vlc ?: VlcEngine(ctx, object : VlcListener {
            override fun onPlaying(p0: Boolean) { playing = p0; if (p0) vlcTick++ }
            override fun onBuffering(b: Boolean) { buffering = b }
            override fun onFirstFrame() {
                firstFrame = true; buffering = false; vlcTick++
                if (!linkSaved) { linkSaved = true; now?.let { nn -> if (nn.url.startsWith("http")) vm.rememberLink(p.key, nn.url, nn.source, nn.addon) } }
            }
            override fun onError() { onEngineError("VLC_ERROR") }
            override fun onEnded() { playing = false }
        }).also { vlc = it }
        v.preferAudio = vm.qsLangs().filter { it != "original" } + listOfNotNull(vm.playerPref("audio").takeIf { it.isNotBlank() && it != "off" }?.let { runCatching { java.util.Locale(it).displayLanguage.lowercase() }.getOrNull() })
        val url = if (w.relay) Relay.wrap(n.url) else n.url
        // Small files start after 1 s of cache; big / 4K / HEVC files get more so they don't stutter.
        v.load(url, if (w.relay) emptyMap() else StreamHeaders.forUrl(n.url), n.subs, startMs, hw = w.hw, cachingMs = cachingFor(itemFor(n.url)))
        now = n
    }

    fun startExo(n: Now, startMs: Long) {
        if (usingVlc) { vlc?.stop(); usingVlc = false }
        val subs = subtitleConfigs(n.subs)
        firstFrame = false; failure = null; status = "Buffering…"; linkSaved = false
        // Tell ExoPlayer what the link is (DASH / HLS / file) when the link itself doesn't say.
        val mime = StreamType.mimeFor(n.url, itemFor(n.url)?.fileName())
        Speech.anchorHintMs = startMs
        player.setMediaItem(MediaItem.Builder().setUri(n.url).apply { if (mime != null) setMimeType(mime) }.setSubtitleConfigurations(subs).build(), startMs)
        player.prepare(); player.playWhenReady = true
        now = n
    }

    fun startWay(n: Now, startMs: Long) {
        loadTick++
        loadStartedAt = SystemClock.elapsedRealtime()
        val w = ways.getOrNull(wayIdx) ?: Way(vlc = engine == "vlc", hw = true, relay = false)
        PlayTiming.mark("opening link with ${w.label()} · ${runCatching { Uri.parse(n.url).host }.getOrNull() ?: "?"}" +
            (itemFor(n.url)?.let { " · ${Prepare.qualityName(it.resolution())}" } ?: "") + (if (startMs > 0) " · from ${fmtTime(startMs)}" else ""))
        if (w.vlc) startVlc(n, startMs, w) else startExo(n, startMs)
    }

    fun load(n: Now, startMs: Long, label: String? = null) {
        // Mid-playback switch (audio language / source / quality): freeze the last frame and continue from the same second.
        if (firstFrame) {
            switching = true
            switchLabel = label
            videoView?.let { vv -> captureFrame(vv) { bmp -> if (switching) frozen = bmp } }
        }
        StreamHeaders.active = StreamHeaders.forUrl(n.url)
        resumeAt = startMs
        ways = waysFor(n.url, engine, big = cachingFor(itemFor(n.url)) > 1000, manifest = StreamType.mimeFor(n.url, itemFor(n.url)?.fileName()) != null)
        wayIdx = 0
        midRetries = 0
        // A link that doesn't show its type: one quick look first (DASH / HLS manifests behind proxy links).
        if (StreamType.needsProbe(n.url, itemFor(n.url)?.fileName())) {
            val seq = ++probeSeq[0]
            now = n
            scope.launch {
                val mime = withContext(Dispatchers.IO) { StreamType.probe(n.url) }
                if (seq != probeSeq[0]) return@launch // another link was picked meanwhile
                if (mime.isNotEmpty()) ways = waysFor(n.url, engine, big = cachingFor(itemFor(n.url)) > 1000, manifest = true)
                startWay(n, startMs)
            }
        } else {
            probeSeq[0]++
            startWay(n, startMs)
        }
    }

    /** Subtitles of a new stream plus the ones already loaded (from the subtitle addons), without duplicates. */
    fun withSubs(st: StreamItem, old: List<Subtitle>): List<Subtitle> =
        st.stream.subtitles.orEmpty() + old.filter { s -> st.stream.subtitles.orEmpty().none { it.url == s.url } }

    // ───── Watch together: follow the room (play / pause / seek / video), share this player's state ─────
    DisposableEffect(Unit) {
        Together.provider = { roomState(isPlayingNow()) }
        Together.myName = vm.current.value?.name ?: Cloud.user.value?.email?.substringBefore('@') ?: ""
        onDispose { Together.provider = null; Together.playerGone() }
    }
    // The room ended (its creator left): this video closes too.
    LaunchedEffect(Unit) { Together.ended.collect { onBack() } }
    // A planned watch ("Watch now" or its reminder): once the picture is up, the room starts by itself and everyone in
    // the plan is told you're waiting.
    LaunchedEffect(firstFrame) {
        if (!firstFrame || BuildConfig.IS_TV || Social.hostPlan == null || Together.active()) return@LaunchedEffect
        delay(800)
        val err = Together.create(roomState(isPlayingNow()))
        if (err == null) Together.code.value?.let { Social.planRoomStarted(it); Snack.show("Room started · your friends are told you're waiting") }
        else { Social.hostPlan = null; Snack.show(err) }
    }
    var appliedRev by remember { mutableLongStateOf(Together.remote.value?.rev ?: 0L) }
    LaunchedEffect(Unit) {
        Together.remote.collect { st ->
            if (st == null || st.by == Together.me || st.rev <= appliedRev || !st.url.startsWith("http")) return@collect
            appliedRev = st.rev
            val expected = Together.expected(st)
            when {
                // The room moved to another title: open it here too.
                st.key.isNotBlank() && st.key != p.key -> onReplace(Screen.Player(st.title, st.url, emptyList(), key = st.key, image = st.image, resumeMs = expected, type = st.type))
                // Same title, another link (source / quality changed): switch to it at the same second.
                st.url != now?.url -> load(Now(st.url, now?.subs.orEmpty(), "Watch together", null), expected, "Watch together")
                else -> {
                    Together.applying = true
                    try {
                        // Pause: everyone on the same frame. Play: small gaps are closed quietly (below), big ones jump.
                        if (!st.playing) { if (kotlin.math.abs(curPos() - expected) > 150) seekAbs(expected); pauseNow() }
                        else { if (kotlin.math.abs(curPos() - expected) > 1500) seekAbs(expected); playNow() }
                    } finally { Together.applying = false }
                }
            }
        }
    }
    // Watch together, while playing: closer than 0.15 s to the room = fine; up to 1.5 s off = play 5 % faster / slower
    // for a moment (not noticeable); more = jump. Someone buffering: everyone waits, then starts together.
    val roomNow by Together.code.collectAsState()
    val waitingFor by Together.waitingFor.collectAsState()
    var waitEnded by remember { mutableLongStateOf(0L) }
    LaunchedEffect(roomNow, firstFrame) {
        if (roomNow == null || !firstFrame) return@LaunchedEffect
        var nudged = false
        try {
            while (isActive) {
                delay(1000)
                val st = Together.remote.value
                val skip = st == null || Together.isHost || !st.playing || st.url != now?.url || !isPlayingNow() || buffering ||
                    Together.waitingFor.value != null || SystemClock.elapsedRealtime() - waitEnded < 3000 || Together.buf.value.isNotEmpty()
                if (skip) { if (nudged) { setRate(speed); nudged = false }; continue }
                val exp = Together.expected(st!!)
                val gap = curPos() - exp
                when {
                    kotlin.math.abs(gap) > 1500 -> { Together.applying = true; try { seekAbs(exp) } finally { Together.applying = false }; if (nudged) { setRate(speed); nudged = false } }
                    kotlin.math.abs(gap) > 150 -> { setRate(speed * if (gap > 0) 0.95f else 1.05f); nudged = true }
                    nudged -> { setRate(speed); nudged = false }
                }
            }
        } finally { if (nudged) setRate(speed) }
    }
    // This phone buffering for a moment: the room is told (the others wait for it after 1.5 s).
    LaunchedEffect(buffering, firstFrame, roomNow) {
        if (roomNow == null) return@LaunchedEffect
        if (buffering && firstFrame) { delay(700); Together.setBuffering(true) } else Together.setBuffering(false)
    }
    var heldForWait by remember { mutableStateOf(false) }
    LaunchedEffect(waitingFor) {
        if (roomNow == null) return@LaunchedEffect
        if (waitingFor != null) {
            if (isPlayingNow() || player.playWhenReady) {
                heldForWait = true
                // The host pauses the room (everyone stops on its frame); the others only hold here.
                if (Together.isHost) pauseNow() else { Together.applying = true; try { pauseNow() } finally { Together.applying = false } }
            }
        } else if (heldForWait) {
            heldForWait = false; waitEnded = SystemClock.elapsedRealtime()
            if (Together.isHost) { playNow(); Together.local(roomState(true)) }
            else if (Together.remote.value?.playing != false) { Together.applying = true; try { playNow() } finally { Together.applying = false } }
        }
    }
    // A friend talks: the movie gets quieter for a moment (Watch together › Audio › Lower the movie…).
    val talkingNow by Call.talking.collectAsState()
    val duckOn by Call.duckOn.collectAsState()
    val hearOn by Call.hearOn.collectAsState()
    LaunchedEffect(talkingNow.isNotEmpty() && duckOn && hearOn && roomNow != null, usingVlc) {
        val target = duckTarget()
        if (!usingVlc) { val from = player.volume; repeat(6) { i -> player.volume = from + (target - from) * (i + 1) / 6f; delay(40) } }
        else vlc?.let { v -> runCatching { v.volumeScale(target) } }
    }

    // Booster (Settings › Playback › Parallel download): while a big file plays, the part 10–40 s ahead of what is
    // loaded is fetched in 3 pieces at once into the saved video, so the player finds it there (smoother 4K).
    LaunchedEffect(firstFrame, now?.url, usingVlc) {
        val u = now?.url ?: return@LaunchedEffect
        if (!firstFrame || usingVlc || BuildConfig.IS_TV || !StreamCache.parallel || !u.startsWith("http") || isPlaylistLink(itemFor(u), u)) return@LaunchedEffect
        val size = StreamCache.sizes[u] ?: withContext(Dispatchers.IO) { StreamCache.probe(u) }.size.takeIf { it > 0 } ?: return@LaunchedEffect
        if (size < 400L * 1024 * 1024) return@LaunchedEffect
        var boostedTo = 0L
        while (isActive) {
            delay(3_000)
            val d = curDur()
            if (d <= 0 || !isPlayingNow()) continue
            val perMs = size.toDouble() / d
            val from = ((curBuffered() + 10_000) * perMs).toLong().coerceAtLeast(boostedTo)
            if (from >= size) break
            // Only when the player is less than ~40 s from it (no point fetching far ahead).
            if (from - (curBuffered() * perMs).toLong() > (40_000 * perMs).toLong()) continue
            val len = minOf((30_000 * perMs).toLong(), 64L * 1024 * 1024, size - from)
            if (len < 1024 * 1024) continue
            StreamCache.saveParts(ctx, u, from, len, 3, null) { }
            boostedTo = from + len
        }
    }

    // A new link started here (you picked another source / quality): the room follows.
    var sharedUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(firstFrame, now?.url) {
        val u = now?.url ?: return@LaunchedEffect
        if (firstFrame && u != sharedUrl) { val first = sharedUrl == null; sharedUrl = u; if (!first) Together.local(roomState(isPlayingNow())) }
    }

    /** Other links still to try for this title? */
    fun hasMoreLinks(): Boolean = candidates.any { it.stream.url != null && it.stream.url.orEmpty() !in tried && it.stream.url != now?.url && fileId(it.stream.url!!) !in badFiles }
    fun okNext(it: StreamItem) = it.stream.url != null && it.stream.url.orEmpty() !in tried && it.stream.url != now?.url && fileId(it.stream.url!!) !in badFiles
    fun hostOk(it: StreamItem) = Ranker.hostKey(it.stream.url.orEmpty()) !in skipHosts
    /** Other links on servers that haven't refused / stayed silent in this play? */
    fun hasOtherServers(): Boolean = candidates.any { okNext(it) && hostOk(it) }
    // Late links: the link being opened has shown nothing for 2.5 s and a better-ranked link has arrived since: if that one
    // delivers real video at once, it takes over (once per play).
    var lateRaced by remember { mutableStateOf(false) }
    LaunchedEffect(candidates.size, firstFrame) {
        if (firstFrame || lateRaced || usingVlc) return@LaunchedEffect
        val cur = now ?: return@LaunchedEffect
        val since = SystemClock.elapsedRealtime() - loadStartedAt
        if (loadStartedAt == 0L) return@LaunchedEffect
        if (since < 2_500) delay(2_500 - since)
        if (firstFrame || now?.url != cur.url || player.bufferedPosition > 0) return@LaunchedEffect
        val curItem = itemFor(cur.url)
        val ranked = vm.rankedCandidates(candidates + listOfNotNull(curItem), quality, quickOnly = false)
        val best = ranked.firstOrNull { okNext(it) && hostOk(it) } ?: return@LaunchedEffect
        if (curItem != null && ranked.indexOf(curItem) in 0 until ranked.indexOf(best)) return@LaunchedEffect
        // Never trade a 4K / 1080p link that's still loading for a lower-quality one just because it answered faster.
        if (curItem != null && best.effectiveRes() < curItem.effectiveRes()) return@LaunchedEffect
        lateRaced = true
        val ok = withContext(Dispatchers.IO) { StreamCache.firstBytes(best.stream.url!!, 128 * 1024) } || isPlaylistLink(best, best.stream.url!!)
        if (ok && !firstFrame && now?.url == cur.url) {
            PlayTiming.mark("a better link arrived and sent video first: switching")
            candidates = candidates.filter { it !== best }
            load(Now(best.stream.url!!, withSubs(best, cur.subs), best.stream.name ?: best.addonName, best.addonName), resumeAt)
        }
    }
    // The next links' types (DASH / HLS / plain file) are looked up quietly ahead of time, so a switch never waits for it.
    LaunchedEffect(candidates) {
        delay(1_500)
        vm.rankedCandidates(candidates, quality, quickOnly = false).take(3).forEach { c ->
            val u = c.stream.url ?: return@forEach
            if (StreamType.needsProbe(u, c.fileName())) launch(Dispatchers.IO) { StreamType.probe(u) }
        }
    }

    /** No more ways for this link: the next source; in Quick Start, then every other addon's files; else the error. */
    fun tryNextSource(n: Now?, start: Long, code: String) {
        // This link failed every way: count it for its provider group (twice in a row = under observation).
        n?.let { nn -> val it0 = itemFor(nn.url); scope.launch { Ranker.recordFail(it0, code) } }
        // The best remaining link by the Source Test ranking (dead and keeps-failing groups last resort only):
        // links that already sent video in the race first, then servers that haven't failed in this play, then the rest.
        // …never the same file again through another link (e.g. a re-signed storage link) after it failed to open.
        val ranked = vm.rankedCandidates(candidates, quality, quickOnly = false)
        val next = ranked.firstOrNull { okNext(it) && hostOk(it) && it.stream.url in StreamCache.warm }
            ?: ranked.firstOrNull { okNext(it) && hostOk(it) }
            ?: ranked.firstOrNull { okNext(it) }
            ?: candidates.firstOrNull { okNext(it) }
        when {
            next != null -> {
                candidates = candidates.filter { it !== next && it.stream.url.orEmpty() !in tried }
                Snack.show("That source didn't play — trying the next one")
                load(Now(next.stream.url!!, withSubs(next, n?.subs.orEmpty()), next.stream.name ?: next.addonName, next.addonName), start)
            }
            // Quick Start ran out of files: carry on with every other addon's files before giving up.
            p.quick && !extendedAll -> {
                extendedAll = true
                recovering = true
                scope.launch {
                    val more = withTimeoutOrNull(20_000) { runCatching { vm.allStreams(type, p.key) }.getOrNull() }.orEmpty()
                        .filter { it.stream.url != null && it.stream.url.orEmpty() !in tried && it.stream.url != n?.url }
                    recovering = false
                    if (!isActive || now?.url != n?.url) return@launch // left the player, or something else was chosen meanwhile
                    if (more.isNotEmpty()) {
                        known = (known + more).distinctBy { it.stream.url }
                        candidates = (candidates + more).distinctBy { it.stream.url }
                    }
                    tryNextSource(n, start, code)
                }
            }
            else -> { forgetStreams(p.key); switching = false; frozen = null; failure = "This file couldn't be played, even after trying every way ($code). Try another source." }
        }
    }

    /** Same link another way (software decoding, header relay, other engine) — quietly; then the next source. */
    fun tryOtherWays(n: Now?, start: Long, code: String) {
        if (n != null && wayIdx + 1 < ways.size) {
            wayIdx++
            if (wayIdx == 1) Snack.show("Trying another way to play this file…")
            resumeAt = start
            startWay(n, start)
        } else tryNextSource(n, start, code)
    }

    /**
     * The server refused the link (e.g. HTTP 403 — provider links expire): look the title up again, without the cache,
     * and return the same file's fresh link (same addon and name; else same quality and language). Null if there is none.
     */
    suspend fun refreshedLink(n: Now, code: String): Now? {
        if (!code.contains("HTTP")) {
            // VLC doesn't say why it failed: ask the server.
            val probe = PlaybackLog.probe(n.url)
            if (!Regex("HTTP 4\\d\\d").containsMatchIn(probe)) return null
        }
        refreshedOnce = true
        val old = itemFor(n.url)
        val quickScope = p.quick && (old == null || vm.isQuick(old))
        forgetStreams(p.key); vm.forgetLink(p.key); NextUp.clear(p.key)
        val fresh = withTimeoutOrNull(15_000) {
            runCatching { if (quickScope) vm.quickStreams(type, p.key) else vm.allStreams(type, p.key) }.getOrNull()
        }.orEmpty().filter { it.stream.url != null && it.stream.url.orEmpty() !in tried }
        if (fresh.isEmpty()) return null
        fun txt(s: StreamItem) = listOfNotNull(s.stream.name, s.stream.title, s.stream.description, s.stream.behaviorHints?.filename).joinToString("\n")
        val match = old?.let { o -> fresh.firstOrNull { it.addonName == o.addonName && txt(it) == txt(o) } }
            ?: old?.let { o -> fresh.firstOrNull { it.addonName == o.addonName && it.resolution() == o.resolution() && vm.langRank(it) == vm.langRank(o) } }
            ?: old?.let { o -> fresh.firstOrNull { it.resolution() == o.resolution() && vm.langRank(it) == vm.langRank(o) } }
            ?: fresh.firstOrNull { it.addonName == n.addon && it.stream.name == n.source }
            ?: return null
        known = (fresh + known).distinctBy { it.stream.url }
        if (quickScope) quickList = fresh
        val rest = fresh.filter { it !== match }
        candidates = if (quickScope) rest.filter { vm.isQuick(it) }.sortedBy { vm.langRank(it) } else rest
        return Now(match.stream.url!!, withSubs(match, n.subs), match.stream.name ?: match.addonName, match.addonName)
    }

    /** A link failed: reload it after a blip, refresh a refused link once, try it another way, then the next source, else show the error. */
    /** Set below (needs switchTo): the upgraded HD file failed → back to MovieBox. */
    var backToMovieBox: (String) -> Boolean = { false }
    onEngineError = { code ->
        PlayTiming.mark("error: $code")
        if (!backToMovieBox(code)) {
        val n = now
        val start = keepPos()
        if (n != null) {
            tried += n.url
            val w = ways.getOrNull(wayIdx)
            val title = p.title
            scope.launch { PlaybackLog.add(ctx, "$title · ${n.addon ?: "?"} · ${w?.label() ?: "?"} · $code · ${PlaybackLog.probe(n.url)}") }
        }
        if (n != null && vm.lastLink(p.key)?.url == n.url) vm.forgetLink(p.key)
        if (n != null && NextUp.url == n.url) NextUp.clear()
        val refused = code.contains("HTTP") || (code == "VLC_ERROR" && wayIdx == 0)
        // Learn from it: a refusal, or a link that sent nothing at all, counts against its server; one refusal (or two
        // silent links) and the server's other links go last for the rest of this play.
        if (n != null && !firstFrame && n.url.startsWith("http")) {
            val silent = code == "TIMEOUT" && tracks.isEmpty && !usingVlc && player.bufferedPosition <= 0L
            if (code.contains("HTTP") || silent) HostStats.noteFail(n.url)
            val hk = Ranker.hostKey(n.url)
            if (code.contains("HTTP")) skipHosts += hk
            if (silent) { val c = (silentCount[hk] ?: 0) + 1; silentCount[hk] = c; if (c >= 2) skipHosts += hk }
        }
        when {
            // A fresh link is already being looked up.
            recovering -> Unit
            // Broke while playing (network blip): reload the same link at the same second, twice at most.
            firstFrame && n != null && midRetries < 2 -> {
                midRetries++
                resumeAt = start
                startWay(n, start)
            }
            // Refused by the server: get a fresh link for the same file once, and continue from the same second.
            // (Only when no other server is waiting: another server is faster than a fresh link from one that refused.)
            n != null && refused && !refreshedOnce && n.url.startsWith("http") && !hasOtherServers() -> {
                recovering = true
                scope.launch {
                    val fresh = refreshedLink(n, code)
                    recovering = false
                    if (!isActive || now?.url != n.url) return@launch
                    if (fresh != null) load(fresh, start) else if (code.contains("HTTP")) tryNextSource(n, start, code) else tryOtherWays(n, start, code)
                }
            }
            // The server refused (403 / 428 / 5xx…) or the link stalls while other links are waiting: VLC can't fix a
            // refused link and a stuck one wastes time — straight to the next source.
            n != null && !firstFrame && (code.contains("HTTP") || (code == "TIMEOUT" && hasMoreLinks())) -> {
                if (code.contains("400") || code.contains("404")) Ranker.markDeadUrl(n.url)
                tryNextSource(n, start, code)
            }
            // "Not a video file": if the server sent a web page, it's a dead link (no VLC either).
            n != null && !firstFrame && code.contains("PARSING_CONTAINER") -> {
                scope.launch {
                    val pr = StreamCache.probe(n.url)
                    if (!isActive || now?.url != n.url) return@launch
                    badFiles += fileId(n.url)
                    if (!pr.ok || pr.type?.startsWith("text/html", true) == true) {
                        if (Ranker.isDeadReason(pr.status, pr.type, n.url) || pr.type?.startsWith("text/html", true) == true) Ranker.markDeadUrl(n.url)
                        tryNextSource(n, start, code)
                    }
                    // A real file ExoPlayer can't read: other links first (VLC on it is slow); VLC only as the last resort.
                    else if (hasMoreLinks()) tryNextSource(n, start, code)
                    else tryOtherWays(n, start, code)
                }
            }
            else -> tryOtherWays(n, start, code)
        }
        }
        Unit
    }

    // A link that never starts (no error, no picture) counts as failed. With other links waiting: switched when nothing
    // has arrived after 6 s — but a link that is sending video (file opened / data loaded) gets up to 14 s. VLC: 8 s.
    // Nothing better to try: 30 s.
    LaunchedEffect(loadTick) {
        if (loadTick == 0) return@LaunchedEffect
        if (usingVlc) delay(8_000L)
        else if (hasMoreLinks()) {
            // How long this server may stay silent: 3.5 s for servers that keep failing, up to 10–12 s for good servers
            // that are slow to answer the first time (pixeldrain), 6 s otherwise (learned from real plays).
            val budget = HostStats.startBudgetMs(now?.url.orEmpty())
            delay(budget)
            var waited = budget
            while (waited < maxOf(14_000L, budget) && !firstFrame && (tracks.groups.isNotEmpty() || player.bufferedPosition > 0)) { delay(500); waited += 500 }
        } else delay(30_000L)
        if (!firstFrame && failure == null && now != null && !recovering) onEngineError("TIMEOUT")
    }
    // The paused player from the title page: if no picture 6 s after Play, it is treated like a stuck link.
    LaunchedEffect(Unit) {
        if (pre == null) return@LaunchedEffect
        delay(6_000L)
        if (!firstFrame && failure == null && !recovering && loadTick == 0) { PlayTiming.mark("paused player gave no picture in 6 s"); onEngineError("TIMEOUT") }
    }
    // Back on screen after a switch: say where we continued, with a quick way to start over.
    // Started: the provider group worked; instant play says how fast; phones save the other qualities' next minute.
    var instantShown by remember { mutableStateOf(false) }
    LaunchedEffect(firstFrame) {
        if (!firstFrame) return@LaunchedEffect
        Ranker.recordOk(now?.url?.let { itemFor(it) })
        now?.url?.takeIf { it.startsWith("http") && loadStartedAt > 0 }?.let { HostStats.noteOk(it, SystemClock.elapsedRealtime() - loadStartedAt) }
        PlayTiming.finish("✓ first picture" + (now?.url?.let { u -> itemFor(u)?.let { " · ${Ranker.labelOf(Ranker.groupKey(it))}" } } ?: ""))
        // A video of this title is playing: slow addons (CNCVerse) stop refreshing it.
        SlowAddons.played(p.key)
        if (p.instant && !instantShown) {
            instantShown = true
            val secs = (SystemClock.elapsedRealtime() - openedAt) / 1000f
            Snack.show("⚡ Started in ${"%.1f".format(secs)} s from saved video")
        }
    }
    LaunchedEffect(firstFrame, quickList.size) {
        if (!firstFrame || BuildConfig.IS_TV || quickList.isEmpty()) return@LaunchedEffect
        // Wait until the video playing has ~60 s loaded ahead (so the extra download never makes it stutter).
        var waited = 0
        while (waited < 180) {
            delay(2000); waited += 2
            if (!usingVlc && player.bufferedPosition - player.currentPosition >= 60_000) break
        }
        val u = now?.url ?: return@LaunchedEffect
        val d = curDur()
        if (d > 0) Prepare.saveOthers(vm, ctx, quickList, u, curPos(), d)
    }
    DisposableEffect(Unit) { onDispose { Prepare.stopOthers() } }
    LaunchedEffect(firstFrame) {
        if (firstFrame && switching) {
            switching = false
            frozen = null
            val at = resumeAt
            if (at > 5_000) Snack.show("${switchLabel?.let { "$it · " } ?: ""}Resumed at ${fmtTime(at)}", "Start from beginning", 5000) { seekAbs(0L) }
            else switchLabel?.let { Snack.show(it) }
            switchLabel = null
        }
    }

    // Start straight away when a link was given; the player is preparing while the loading screen shows.
    LaunchedEffect(Unit) {
        if (pre != null) {
            ways = waysFor(pre.url, "exo"); wayIdx = 0
            buffering = player.playbackState == Player.STATE_BUFFERING
            player.playWhenReady = true
        } else now?.let { load(it, p.resumeMs) }
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (usingVlc) return
                onEngineError(error.errorCodeName.removePrefix("ERROR_CODE_"))
            }
            override fun onTracksChanged(t: Tracks) {
                tracks = t
                // Learn the real quality of this link (for links that don't name it).
                t.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.let { g -> (0 until g.length).maxOfOrNull { g.getTrackFormat(it).height } }
                    ?.let { h -> now?.url?.let { u -> ResMemory.put(u, h) } }
                if (!t.isEmpty) PlayTiming.mark("tracks found: " + t.groups.filter { it.type == C.TRACK_TYPE_VIDEO || it.type == C.TRACK_TYPE_AUDIO }
                    .joinToString(", ") { g -> g.getTrackFormat(0).let { f -> (f.sampleMimeType ?: "?").substringAfter('/') + if (g.isTrackSupported(0)) "" else " (not supported)" } }, once = "tracks")
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                Speech.anchorHintMs = newPosition.positionMs
            }
            override fun onCues(cueGroup: CueGroup) { if (ext == null && !subPreview) cues = cueGroup.cues }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!usingVlc) playing = isPlaying }
            override fun onRenderedFirstFrame() { if (!usingVlc) firstFrame = true }
            override fun onPlaybackStateChanged(state: Int) {
                if (usingVlc) return
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) PlayTiming.mark("ready (enough loaded to start)", once = "ready")
                if (state == Player.STATE_READY && !linkSaved) {
                    linkSaved = true
                    now?.let { n -> if (n.url.startsWith("http")) vm.rememberLink(p.key, n.url, n.source, n.addon) }
                    // Audio-only or no video track: there is no "first frame", so don't wait for it.
                    if (player.videoSize.width == 0) scope.launch { delay(800); firstFrame = true }
                }
            }
        }
        player.addListener(l)
        // A player prepared on the title page already knows its tracks (no "tracks changed" will come): take them now.
        if (!player.currentTracks.isEmpty) tracks = player.currentTracks
        onDispose { player.removeListener(l) }
    }

    // Media session: earbuds / headset / Bluetooth remote play-pause buttons control the video (also with VLC playing).
    DisposableEffect(player) {
        val fp = object : androidx.media3.common.ForwardingPlayer(player) {
            override fun play() { if (usingVlc) vlc?.play() else super.play() }
            override fun pause() { if (usingVlc) vlc?.pause() else super.pause() }
            override fun getPlayWhenReady(): Boolean = if (usingVlc) vlc?.isPlaying == true else super.getPlayWhenReady()
            override fun getPlaybackState(): Int = if (usingVlc) Player.STATE_READY else super.getPlaybackState()
        }
        val session = runCatching { androidx.media3.session.MediaSession.Builder(ctx, fp).setId("streamx-${System.nanoTime()}").build() }.getOrNull()
        onDispose { runCatching { session?.release() } }
    }

    // "Finding stream source": Auto Stream Selection inside the player (and for the next episode).
    /**
     * Fast start (like Nuvio, but racing): the best three links of [list] (your quality, language, Source Test ranking)
     * are asked at once and the first server that answers with a video plays — no waiting for slow addons or slow
     * servers. False when none answered within 5 s.
     */
    suspend fun raceStart(list: List<StreamItem>, subsJob: kotlinx.coroutines.Deferred<List<Subtitle>>): Boolean {
        // TV: ranking a few hundred links took seconds on the stick's main thread — done in the background there.
        val triedNow = tried.toSet(); val badNow = badFiles.toSet()
        val rankIt: () -> List<StreamItem> = {
            vm.rankedCandidates(list, quality, quickOnly = false)
                .filter { it.stream.url != null && it.stream.url.orEmpty() !in triedNow && fileId(it.stream.url!!) !in badNow }
                .let { if (p.quick && quality == "auto" && !vm.pluginMode()) vm.movieBoxFirst(it) else it }
        }
        val ranked = if (BuildConfig.IS_TV) withContext(Dispatchers.Default) { rankIt() } else rankIt()
        if (ranked.isEmpty()) return false
        // Race the best three, but servers that keep failing only when nothing else is there.
        // Only links in a language as good as the best one race (a faster English file never beats the Hindi you chose).
        val bestLang = vm.langRank(ranked.first())
        val sameLang = ranked.filter { vm.langRank(it) <= bestLang }
        // One link per server: three links on one slow server (pixeldrain…) would all be slow together.
        val top0 = (sameLang.filter { HostStats.penalty(it.stream.url.orEmpty()) == 0 }.distinctBy { hostOf(it.stream.url) }.take(3)
            .ifEmpty { sameLang.distinctBy { hostOf(it.stream.url) }.take(3) })
        // A MovieBox streaming playlist (.mpd) starts like Netflix: it joins the race when it is in a language as good
        // as the best link's.
        val mbp = ranked.firstOrNull { it !in top0 && it.isMovieBox() && it.isPlaylist() && vm.langRank(it) <= vm.langRank(top0.first()) }
        val top = when {
            // Plugin mode: a Netflix / Prime Video / Hotstar / M Player link on top races only against its own kind
            // (a 4K file that answered later never beats it — the 4K comes through "Upgrade to 4K").
            vm.pluginMode() && ranked.first().isOtt() -> ranked.filter { it.isOtt() && vm.langRank(it) <= bestLang }.distinctBy { hostOf(it.stream.url) + it.addonName }.take(2)
            // Quick play with MovieBox's playlist on top: it alone (racing others only slows it down).
            p.quick && ranked.first().isMovieBox() && ranked.first().isPlaylist() -> listOf(ranked.first())
            mbp == null -> top0
            else -> top0.take(2) + mbp
        }
        PlayTiming.mark("racing ${top.size} link(s): " + top.joinToString(" / ") { Ranker.labelOf(Ranker.groupKey(it)).substringAfter(" · ") }, once = "race")
        val won = if (top.size == 1) null else StreamCache.race(top.mapNotNull { it.stream.url }, 8000) ?: return false
        val url = won?.first ?: top[0].stream.url!!
        val q = ranked.first { it.stream.url == url }
        // A big file: its index (end of the file) is fetched at the same time as the player opens it — it opens faster.
        won?.second?.takeIf { it.ok && it.size > 40L * 1024 * 1024 && !isPlaylistLink(q, url) }?.let { pr ->
            scope.launch(Dispatchers.IO) {
                val mp4 = url.contains(".mp4", true) || q.fileName()?.endsWith(".mp4", true) == true
                val t = if (mp4) minOf(24L * 1024 * 1024, pr.size / 60) else minOf(8L * 1024 * 1024, pr.size / 200)
                runCatching { StreamCache.saveParts(ctx, url, pr.size - t, t, 2, null) { } }
            }
        }
        known = (known + list).distinctBy { it.stream.url }
        candidates = ranked.filter { it !== q }
        // Backup: the next good link's first 2 MB are saved quietly, so a switch (if the winner stalls) starts at once.
        top.firstOrNull { it !== q && HostStats.penalty(it.stream.url.orEmpty()) == 0 }?.stream?.url?.let { b ->
            if (!isPlaylistLink(itemFor(b), b)) backupJob = scope.launch(Dispatchers.IO) { runCatching { StreamCache.saveParts(ctx, b, 0, 2L * 1024 * 1024, 1, null) { } } }
        }
        StreamCache.register(q)
        StreamHeaders.use(url)
        val extra = withTimeoutOrNull(600) { subsJob.await() }.orEmpty()
        load(Now(url, q.stream.subtitles.orEmpty() + extra, q.stream.name ?: q.addonName, q.addonName), resumeAt)
        return true
    }

    LaunchedEffect(findRequest) {
        if (findRequest == 0) return@LaunchedEffect
        val findT0 = SystemClock.elapsedRealtime()
        failure = null; firstFrame = false; status = "Finding stream source"
        PlayTiming.mark("finding stream source (no prepared link)", once = "find")
        answered = 0; bestRes = 0; bufProgress = 0f; sourceTotal = streamAddonCount()
        val subsJob = async { runCatching { vm.subtitles(type, p.key) }.getOrDefault(emptyList()) }
        val forced = findRequest > 1 // asked again after a failure: never reuse the old link
        // Next episode handoff: its link was found while the last episode was still playing (also in Quick Start).
        if (!forced && NextUp.fresh(p.key)) {
            val hUrl = NextUp.url!!
            val hSubs = NextUp.subs
            val hSource = NextUp.source
            val hAddon = NextUp.addon
            NextUp.clear(p.key)
            val extra = withTimeoutOrNull(1200) { subsJob.await() }.orEmpty().filter { s -> hSubs.none { it.url == s.url } }
            load(Now(hUrl, hSubs + extra, hSource, hAddon), resumeAt)
            // The other files (quality menu, Audio panel, fallbacks) — already cached by the look-up ahead of time.
            scope.launch {
                if (p.quick) {
                    val l = runCatching { vm.quickStreams(type, p.key) }.getOrDefault(emptyList())
                    if (l.isEmpty()) return@launch
                    quickList = l
                    known = (known + l).distinctBy { it.stream.url }
                    candidates = l.filter { it.stream.url != hUrl && vm.isQuick(it) }.sortedBy { vm.langRank(it) }
                    vm.qualityMap(l).firstOrNull { it.first.id != "auto" && it.second?.stream?.url == hUrl }?.let { quality = it.first.id }
                } else {
                    val l = runCatching { vm.allStreams(type, p.key) }.getOrDefault(emptyList())
                    if (l.isEmpty()) return@launch
                    known = l
                    candidates = l.filter { it.stream.url != hUrl }
                }
            }
            return@LaunchedEffect
        }
        // Links the title page already found: race them straight away (no second search).
        if (!forced && p.candidates.isNotEmpty()) {
            if (p.quick) quickList = p.candidates
            if (raceStart(p.candidates.filter { !it.slowMp4() }.ifEmpty { p.candidates }, subsJob)) return@LaunchedEffect
        }
        val last = if (!forced && !p.quick && isOn(opt("reuseLastLink"))) vm.lastLink(p.key) else null
        if (last != null) {
            load(Now(last.url, withTimeoutOrNull(1200) { subsJob.await() }.orEmpty(), last.source, last.addon), resumeAt)
            // Also look up other sources in the background, in case the saved link has expired.
            scope.launch { vm.streamsFlow(type, p.key).first { it.second }.let { (l, _) -> val fl = vm.filtered(l).list; known = fl; candidates = fl.filter { it.stream.url != last.url } } }
            return@LaunchedEffect
        }
        // Quick Start: fast addons, chosen quality and your language order.
        // The first file in your top language (e.g. Hindi) starts at once; the other addons keep loading
        // in the background and fill the Audio panel / quality menu as they arrive.
        if (p.quick) {
            var started = false
            suspend fun startWith(list: List<StreamItem>, q: StreamItem) {
                started = true
                candidates = list.filter { it !== q && vm.isQuick(it) }.sortedBy { vm.langRank(it) }
                val extra = withTimeoutOrNull(800) { subsJob.await() }.orEmpty()
                load(Now(q.stream.url!!, q.stream.subtitles.orEmpty() + extra, q.stream.name ?: q.addonName, q.addonName), resumeAt)
            }
            val cached = if (forced) null else cachedQuick(p.key)
            if (cached != null) {
                sourceTotal = streamAddonCount(vm.qsAddons()); noteAnswer(cached, true)
                quickList = cached; known = (known + cached).distinctBy { it.stream.url }
                val okCached = cached.filter { it.stream.url !in tried }
                val q = vm.pickQuick(okCached, quality) ?: vm.pickQuick(okCached, "auto")
                if (q != null) { startWith(cached, q); return@LaunchedEffect }
            } else {
                val topLang = vm.qsLangs().firstOrNull()
                var last: List<StreamItem> = emptyList()
                sourceTotal = streamAddonCount(vm.qsAddons())
                // 6-second rule: no streaming playlist (MPD / HLS) from quick play within 6 s → race the backup servers'
                // links (CNCVerse, HdHub…; one per server) instead of waiting 15–25 s. MovieBox still wins if its playlist
                // answers meanwhile. MovieBox MP4s (they start late) only when nothing else exists.
                // Instant plays never reach this: they start long before 6 s.
                var rescueRacing = false
                var quickDone = false
                var allDone = false
                var nothingFound = false
                // The other addons are asked right away in the background — their links are only used if quick play has no
                // playlist at 6 s. (Quick play's own addons aren't asked twice: shared requests.)
                val pool = kotlinx.coroutines.flow.MutableStateFlow((cachedAll(p.key).orEmpty() + SlowAddons.links.value[p.key].orEmpty()).distinctBy { it.stream.url })
                fun add(l: List<StreamItem>) { if (l.isNotEmpty()) pool.value = (pool.value + l).distinctBy { it.stream.url } }
                val feeds = listOf(
                    launch { SlowAddons.links.collect { m -> add(m[p.key].orEmpty()) } },
                    launch { runCatching { vm.streamsFlow(type, p.key).collect { (l, d) -> add(l); if (d) allDone = true } }; allDone = true },
                )
                val rescue = launch {
                    // TV: the backup links (CNCVerse / HdHub…) race after 1.5 s if they're there — waiting 6 s made a
                    // Fire TV start take 18 s even though 26 links had come back in under half a second.
                    if (BuildConfig.IS_TV) { delay(1_500); withTimeoutOrNull(4_500) { pool.first { vm.filtered(it).list.any { s -> !s.slowMp4() } } } } else delay(6_000)
                    if (started) return@launch
                    if (!vm.pluginMode()) PlayTiming.mark("no quick playlist in 6 s — racing backup servers (${vm.filtered(pool.value).list.size} known)", once = "rescue")
                    status = "Quick play is slow — trying backup servers"
                    add(known)
                    val raced = HashSet<String>()
                    var askedAgain = false
                    while (!started) {
                        val l = vm.filtered(pool.value).list.filter { it.stream.url !in tried && !it.slowMp4() }
                        val fresh = l.filter { it.stream.url !in raced }
                        if (fresh.isNotEmpty()) {
                            l.forEach { it.stream.url?.let { u -> raced += u } }
                            rescueRacing = true
                            val ok = runCatching { raceStart(l, subsJob) }.getOrDefault(false)
                            rescueRacing = false
                            if (ok) { started = true; break }
                        }
                        val el = SystemClock.elapsedRealtime() - findT0
                        // Nothing playing 10 s after Play: CNCVerse is asked again (every 2 s until it returns links).
                        if (!askedAgain && el >= 10_000 && !vm.pluginMode()) {
                            askedAgain = true
                            PlayTiming.mark("nothing playing 10 s — CNCVerse asked again", once = "cnc10")
                            SlowAddons.poll(vm, type, p.key)
                        }
                        // Only MovieBox MP4s exist: play them (they take longer to start).
                        val mp4 = (last + pool.value).distinctBy { it.stream.url }.filter { it.slowMp4() && it.stream.url !in tried && it.stream.url !in raced }
                        if (l.isEmpty() && mp4.isNotEmpty() && (quickDone || el > 12_000)) {
                            mp4.forEach { it.stream.url?.let { u -> raced += u } }
                            PlayTiming.mark("only MovieBox MP4 files — playing one (starts slower)", once = "mp4")
                            rescueRacing = true
                            val ok = runCatching { raceStart(mp4, subsJob) }.getOrDefault(false)
                            rescueRacing = false
                            if (ok) { started = true; break }
                        }
                        // Every server answered and nothing can play: say so instead of spinning.
                        if (quickDone && allDone && askedAgain && !SlowAddons.watching(p.key) && fresh.isEmpty() && el > 14_000) {
                            if (l.isEmpty() && mp4.isEmpty() && raced.isEmpty()) {
                                nothingFound = true
                                PlayTiming.mark("no server has this title", once = "none")
                                failure = "Not available right now — no server has this title yet. Try again in a while."
                            }
                            break
                        }
                        if (el > 60_000) break
                        val n = pool.value.size
                        withTimeoutOrNull(2_000) { pool.first { it.size != n } }
                    }
                }
                vm.streamsFlow(type, p.key, only = vm.qsAddons()).collect { (l, done) ->
                    val list = vm.filtered(l).list
                    if (!started) noteAnswer(list, done)
                    last = list
                    quickList = list
                    known = (known + list).distinctBy { it.stream.url }
                    if (started) {
                        candidates = (candidates + list.filter { it.stream.url != now?.url && vm.isQuick(it) }).distinctBy { it.stream.url }.sortedBy { vm.langRank(it) }
                    } else {
                        // Only streaming playlists start from quick play (Hindi → English → Original): they open in ~0.5 s.
                        val okList = list.filter { it.stream.url !in tried && it.isPlaylist() }
                        val q = vm.pickQuick(okList, quality) ?: vm.pickQuick(okList, "auto")
                        val instant = q != null && q.isPlaylist()
                        // A playlist arriving while the 6-second rule is racing other links: it takes over at once.
                        if (instant && rescueRacing) { rescue.cancel(); rescueRacing = false }
                        if (q != null && instant && !rescueRacing) {
                            if (raceStart(list.filter { it.isPlaylist() }, subsJob)) started = true
                        }
                    }
                }
                quickDone = true
                putQuick(p.key, last)
                // Quick play finished without a playlist: the 6-second rule takes it from here (backups, then MP4s).
                if (!started) rescue.join()
                rescue.cancel(); feeds.forEach { it.cancel() }
                if (started || nothingFound) return@LaunchedEffect
            }
        }
        val mode = opt("autoPlayMode").let { if (it == "manual") "first" else it }
        val patterns = regexList(opt("autoRegex"))
        val prefer = p.source
        var picked: StreamItem? = null
        var pool: List<StreamItem> = emptyList()
        var hiddenAll = false
        val cachedList = if (forced) null else cachedAll(p.key)
        val flow = if (cachedList != null) kotlinx.coroutines.flow.flowOf(cachedList to true) else vm.streamsFlow(type, p.key)
        answered = 0; sourceTotal = streamAddonCount()
        flow.first { (l, done) ->
            val f = vm.filtered(l)
            noteAnswer(f.list, done)
            // Never links the title page already found broken; the most reliable providers (Source Test) first.
            pool = f.list.filter { it.stream.url !in tried }.sortedByDescending { Ranker.score(it) }
            hiddenAll = f.forcedEmpty
            picked = when {
                pool.isEmpty() -> null
                prefer != null && !done -> pool.firstOrNull { it.stream.name == prefer } // same source as the last episode
                mode == "regex" && patterns.isNotEmpty() -> matchChain(pool, patterns.take(1))?.first ?: if (done) vm.autoPick(pool, "regex", opt("autoRegex")) else null
                else -> if (done || prefer == null) vm.autoPick(pool, "first", "", prefer) else null
            }
            picked != null || done
        }
        val s0 = picked ?: (if (pool.isNotEmpty()) vm.autoPick(pool, mode, opt("autoRegex"), prefer) else null)
        // Prefer your audio language (Hindi → Original…) among versions of the same file from the same addon.
        val s1 = s0?.let { a -> pool.filter { it.addonName == a.addonName && it.resolution() == a.resolution() }.minByOrNull { vm.langRank(it) }
            ?.takeIf { vm.langRank(it) < vm.langRank(a) } ?: a }
        // TV: above 1080p only when the same addon has no 1080p-or-lower copy in the same language.
        val s = if (BuildConfig.IS_TV && s1 != null && s1.resolution() > 1080)
            pool.filter { it.addonName == s1.addonName && it.resolution() in 1..1080 && vm.langRank(it) <= vm.langRank(s1) }.maxByOrNull { it.resolution() } ?: s1
        else s1
        if (s == null) {
            failure = if (hiddenAll) "No streams match your forced stream filters." else "No stream found for this title."
            return@LaunchedEffect
        }
        known = pool
        candidates = pool.filter { it !== s }
        val extra = withTimeoutOrNull(1500) { subsJob.await() }.orEmpty()
        load(Now(s.stream.url!!, s.stream.subtitles.orEmpty() + extra, s.stream.name ?: s.addonName, s.addonName), resumeAt)
    }
    LaunchedEffect(firstFrame, buffering) { if (!firstFrame && now != null && failure == null) status = "Buffering…" }
    // "Serving" progress on the loading screen: eases to 90% over ~4 s (faster when ExoPlayer reports a real buffer),
    // creeps on slowly after that and jumps to 100% with the first frame. It never goes backwards.
    LaunchedEffect(status, loadTick, firstFrame) {
        if (firstFrame) { bufProgress = 1f; return@LaunchedEffect }
        if (!status.startsWith("Buffering")) return@LaunchedEffect
        val t0 = SystemClock.uptimeMillis()
        while (true) {
            val t = (SystemClock.uptimeMillis() - t0) / 4000f
            val eased = if (t < 1f) { val u = 1f - t; 0.9f * (1f - u * u * u) } else 0.9f + 0.08f * (1f - kotlin.math.exp(-(t - 1f)))
            val real = if (!usingVlc) ((player.bufferedPosition - resumeAt).toFloat() / 2500f).coerceIn(0f, 0.95f) else 0f
            bufProgress = maxOf(bufProgress, eased, real)
            delay(50)
        }
    }
    // Starting brightness (Playback > Brightness): applied once, when the video is first on screen.
    LaunchedEffect(firstFrame) {
        if (!firstFrame || brightApplied) return@LaunchedEffect
        brightApplied = true
        // TVs have their own picture settings: never touch the brightness there.
        if (BuildConfig.IS_TV || !isOn(opt("startBright"))) return@LaunchedEffect
        val act = ctx as? Activity ?: return@LaunchedEffect
        val saved = if (opt("startBrightMode") == "last") vm.playerPref("bright").toFloatOrNull() else null
        val level = (saved ?: ((opt("startBrightLevel").toFloatOrNull() ?: 50f) / 100f)).coerceIn(0.1f, 1f)
        act.window.attributes = act.window.attributes.apply { screenBrightness = level }
    }
    // Starting volume (Playback > Volume): applied once and quietly (no system slider); the phone's volume comes back on leaving.
    LaunchedEffect(firstFrame) {
        if (!firstFrame || volApplied) return@LaunchedEffect
        volApplied = true
        // TVs (and their sound bars) keep their own volume.
        if (BuildConfig.IS_TV || !isOn(opt("startVol"))) return@LaunchedEffect
        // Only for earphones / headphones / Bluetooth speakers; the phone's own speaker keeps the phone's volume.
        if (!externalAudio(audio)) return@LaunchedEffect
        val saved = if (opt("startVolMode") == "last") vm.playerPref("vol").toFloatOrNull() else null
        val level = (saved ?: ((opt("startVolLevel").toFloatOrNull() ?: 50f) / 100f)).coerceIn(0.1f, 1f)
        runCatching {
            val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, kotlin.math.round(level * maxVol).toInt().coerceIn(0, maxVol), 0)
            volBefore = before
        }
    }
    // The Full HD / Mid HD / Low menu is always there when Quick Start is on — also when playing from
    // Continue Watching, the sources list or a saved link: its streams are looked up in the background.
    LaunchedEffect(p.key) {
        if (!vm.qsActive()) return@LaunchedEffect
        delay(1500)
        if (quickList.isNotEmpty()) return@LaunchedEffect
        val l = cachedQuick(p.key) ?: runCatching { vm.quickStreams(type, p.key) }.getOrDefault(emptyList())
        if (quickList.isEmpty() && l.isNotEmpty()) {
            quickList = l
            known = (known + l).distinctBy { it.stream.url }
            // Show the level that matches what's playing.
            now?.url?.let { u -> vm.qualityMap(l).firstOrNull { it.first.id != "auto" && it.second?.stream?.url == u }?.let { quality = it.first.id } }
        }
    }

    // Episodes in order, for "Next episode".
    val episodes = remember(meta) {
        meta?.videos.orEmpty().filter { (it.season ?: 0) > 0 && it.episode != null }.sortedWith(compareBy({ it.season }, { it.episode }))
    }
    val nextEp = remember(episodes, p.key) { episodes.indexOfFirst { it.id == p.key }.let { i -> if (i >= 0) episodes.getOrNull(i + 1) else null } }
    val nextEpState = rememberUpdatedState(nextEp)

    LaunchedEffect(p.key) {
        if (meta == null || meta?.videos == null && type == "series") {
            vm.fullMeta(Meta(id = p.key.substringBefore(":"), type = type))?.let { meta = it }
        }
    }
    // Content advisory once the video is on screen.
    LaunchedEffect(p.key, firstFrame) {
        if (firstFrame && advisory.isEmpty() && isOn(opt("showAdvisory")) && p.key.startsWith("tt")) {
            val g = vm.parentalGuide(p.key.substringBefore(":"))
            if (g.isNotEmpty()) { advisory = g; showAdvisory = true; delay(9_000); showAdvisory = false }
        }
    }
    LaunchedEffect(p.key) { if (isOn(opt("skipIntro"))) segs = vm.segments(p.key) }

    LaunchedEffect(player) {
        while (true) {
            pos = if (firstFrame) curPos() else resumeAt
            dur = curDur()
            buffered = curBuffered()
            delay(500)
        }
    }
    // Phones: keep a small copy of the picture every 10 s of playback, so seeking back always shows real frames.
    LaunchedEffect(thumbs, firstFrame) {
        val t = thumbs ?: return@LaunchedEffect
        if (BuildConfig.IS_TV || !firstFrame) return@LaunchedEffect
        while (true) {
            delay(2500)
            val at = curPos()
            val vv = videoView
            if (playing && at > 0 && vv != null && !t.has(at)) captureFrame(vv) { bmp -> if (bmp != null) t.put(at, bmp) }
        }
    }
    // Controls hide after 4 s — never while a finger is on the seek bar (scrubbing) or a remote seek is pending.
    // …and never while a finger is anywhere on the controls (choosing a button, sliding the button row): the 4 s start
    // again only after the finger lifts.
    LaunchedEffect(controls, poke, playing, panel, qualityMenu, pendingSeek, scrubbing, touchingControls, more) {
        if (controls && playing && panel == null && !more && !qualityMenu && pendingSeek == null && !scrubbing && !touchingControls) { delay(4000); controls = false; more = false }
    }
    LaunchedEffect(playing) { PipState.playing = playing }
    fun disableExoText() {
        if (!usingVlc) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }
    // MovieBox: the other languages you rank highest get their start saved while this one plays; opening the audio list
    // saves the part at the current time too (switching language then starts at once).
    fun langOthers(): List<StreamItem> {
        val cur = now?.url ?: return emptyList()
        if (itemFor(cur)?.isMovieBox() != true) return emptyList()
        return vm.languageVersions((known + quickList).distinctBy { it.stream.url }, cur).filter { it.preferred && !it.current }.map { it.stream }
    }
    LaunchedEffect(firstFrame, now?.url) {
        if (!firstFrame || BuildConfig.IS_TV) return@LaunchedEffect
        if (itemFor(now?.url.orEmpty())?.isMovieBox() == true) otherLangsOpen = true
        delay(4_000)
        LangAhead.start(ctx, langOthers())
    }
    LaunchedEffect(panel) {
        if ((panel == "avsubs" || panel == "audio") && firstFrame && !BuildConfig.IS_TV) LangAhead.around(ctx, langOthers(), curPos(), curDur())
    }
    DisposableEffect(Unit) { onDispose { LangAhead.stop() } }
    // ───── Quick start + upgrade (phones) ─────
    // MovieBox starts at once; meanwhile a hidden, silent second player opens the best other file for your quality
    // (same language), from where you are. When ~12 s of it are saved — and it is the same cut (lengths within 2.5 s) —
    // "Upgrade to 4K" / "Upgrade quality" appears; tapping it switches using what is already saved.
    var upgrade by remember { mutableStateOf<UpgradeOffer?>(null) }
    /** The upgrade pop-up was shown for this video (it shows once; the file then waits in Quality). */
    var offeredOnce by remember(p.key) { mutableStateOf(false) }
    var upLeft by remember { mutableIntStateOf(0) }
    val upTried = remember { mutableSetOf<String>() }
    // Remember which link each language played from (switching back to a language plays that same link).
    LaunchedEffect(firstFrame, now?.url) {
        if (!firstFrame) return@LaunchedEffect
        val u = now?.url ?: return@LaunchedEffect
        itemFor(u)?.languagesOrOriginal()?.takeIf { it.size == 1 }?.forEach { urlByLang[it] = u }
    }
    // Every stop to buffer after the first picture is written to Play timing: when, which file / server, how much was
    // ready ahead, and whether anything else was downloading.
    LaunchedEffect(buffering, firstFrame) {
        if (!buffering || !firstFrame || usingVlc) return@LaunchedEffect
        val t0 = SystemClock.elapsedRealtime()
        val at = fmtTime(curPos())
        val it0 = now?.url?.let { itemFor(it) }
        val what = it0?.let { "${VisualTags.quality(it)} · ${VisualTags.primary(it)} · ${hostOf(it.stream.url)}" } ?: hostOf(now?.url)
        val br = runCatching { player.videoFormat?.bitrate?.takeIf { it > 0 }?.let { "%.1f Mbps file".format(it / 1e6) } }.getOrNull()
        val bg = if (it0?.isMovieBox() == true) "background saving may run" else "background saving: paused"
        PlayTiming.late(listOfNotNull("buffering at $at", what, br, bg).joinToString(" · "))
        try { snapshotFlow { buffering }.first { !it } } finally {
            PlayTiming.late("playing again after ${"%.1f".format((SystemClock.elapsedRealtime() - t0) / 1000f)} s (${runCatching { player.totalBufferedDuration / 1000 }.getOrDefault(0)} s ahead)")
        }
    }
    val ffNow by rememberUpdatedState(firstFrame)
    DisposableEffect(player) {
        val l = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioInputFormatChanged(e: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, f: androidx.media3.common.Format, d: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                if (ffNow) PlayTiming.late("audio now: ${f.sampleMimeType?.substringAfter('/') ?: "?"} · ${f.channelCount} ch${if (f.bitrate > 0) " · ${f.bitrate / 1000} kbps" else ""}${f.language?.let { " · $it" } ?: ""}")
            }
            override fun onVideoInputFormatChanged(e: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, f: androidx.media3.common.Format, d: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                if (ffNow) PlayTiming.late("video now: ${f.width}×${f.height}${if (f.bitrate > 0) " · %.1f Mbps".format(f.bitrate / 1e6) else ""}")
            }
        }
        player.addAnalyticsListener(l)
        onDispose { runCatching { player.removeAnalyticsListener(l) } }
    }
    // The HD file that really played (first picture) is remembered for this title (2 days): next time it's made ready
    // on the title page.
    LaunchedEffect(firstFrame, now?.url) {
        if (!firstFrame || BuildConfig.IS_TV) return@LaunchedEffect
        val it0 = now?.url?.let { itemFor(it) } ?: return@LaunchedEffect
        if (!it0.isMovieBox() && !it0.isPlaylist() && it0.effectiveRes() >= 1080) HdMemo.put(ctx, p.key, it0)
    }
    // ── Quick play → HD: while MovieBox plays, 2 Full HD files and 1 4K file (best working servers, your language) are made
    //    ready in the background: the server is checked and only the file's start and its index (end of the file) are
    //    saved — then any of them opens in about a second from wherever you are. Links come from every addon; CNCVerse was
    //    already asked on the title page and is asked once more ~20 s after the start (its server has found them all by then).
    // Started once per video (a picture that drops and comes back must not start the search a second time).
    var everPictured by remember(p.key) { mutableStateOf(false) }
    LaunchedEffect(firstFrame) { if (firstFrame) everPictured = true }
    LaunchedEffect(everPictured, p.key) {
        if (!everPictured || BuildConfig.IS_TV || usingVlc || !isOn(opt("qsUpgrade")) || !vm.upgradeAllowed()) return@LaunchedEffect
        if (vm.pluginMode()) return@LaunchedEffect      // plugin mode: "Upgrade to 4K" below
        val nowUrl = now?.url ?: return@LaunchedEffect
        val cur = itemFor(nowUrl) ?: StreamItem(now?.addon ?: "", StreamInfo(url = nowUrl, name = now?.source))
        // Searched while what plays is a quick / lower-quality file (MovieBox, a playlist, or under Full HD).
        if (!cur.belowFullHd() || hdReady.isNotEmpty()) return@LaunchedEffect
        val pool = java.util.concurrent.ConcurrentHashMap<String, StreamItem>()
        fun add(l: List<StreamItem>) = l.forEach { s0 -> s0.stream.url?.let { u -> pool.putIfAbsent(u, s0) } }
        // From the first picture: CNCVerse every 2 s (only CNCVerse) until a Vegamovies file comes, at most 20 s. No
        // Vegamovies in 20 s (or none of its files works): HdHub. Elite on → only on Wi-Fi (checked above).
        val slowNames = vm.slowStreamAddons(type, p.key).map { it.label() }.toSet()
        add(SlowAddons.links.value[p.key].orEmpty())
        var hdHubOn = false
        var cncDone = false
        val needHd = kotlinx.coroutines.CompletableDeferred<Unit>()
        val smartCnc = AutoPlayRules.smart
        val cncMs = if (smartCnc) AutoPlayRules.current.value.giveUpSec * 1000L else 20_000L
        PlayTiming.late(if (smartCnc) "HD: CNCVerse on the smart schedule (up to ${cncMs / 1000} s) for a Vegamovies file" else "HD: asking CNCVerse every 2 s (up to 20 s) for a Vegamovies file")
        val gather = scope.launch {
            val got = askCncverse(vm, type, p.key, forMs = cncMs) { l -> add(vm.filtered(l).list); l.any { it.isVegamovies() } }
            add(vm.filtered(got).list)
            val vega = got.any { it.isVegamovies() }
            PlayTiming.late(if (vega) "HD: Vegamovies file found — CNCVerse no longer asked" else "HD: no Vegamovies in ${cncMs / 1000} s (${got.size} CNCVerse links) — HdHub next")
            cncDone = true
            if (vega) needHd.await()
            hdHubOn = true
            val hd = vm.addons.value.filter { it.label().contains("hdhub", true) || it.manifest.name.contains("hdhub", true) }.map { it.label() }.toSet()
            if (hd.isNotEmpty()) runCatching { vm.streamsFlow(type, p.key, only = hd).collect { (l, _) -> add(vm.filtered(l).list) } }
        }
        try {
            val curLang = vm.langRank(cur)
            val tried = HashSet<String>()
            fun eligible(tier: Int): List<StreamItem> = vm.rankedCandidates(
                pool.values.filter { !it.isMovieBox() && it.stream.url !in tried && it.stream.url?.startsWith("http") == true && Ranker.tier(it.effectiveRes()) == tier &&
                    (hdHubOn || it.addonName in slowNames) },
                if (tier >= 4) "4k" else "fhd", quickOnly = false,
            ).filter { !Ranker.isDead(it) && !Ranker.keepsFailing(it) && !it.unplayableHere() && !it.isHeavy() && HostStats.penalty(it.stream.url.orEmpty()) == 0 && !AutoPlayRules.blocked(it.stream.url) }
                // Only files you can jump inside: no streaming playlists from these servers (many can't seek) and no
                // groups the Source Test found unseekable.
                .filter { !it.isPlaylist() }
                .filter { vm.langRank(it) <= curLang || "multi" in it.languages() }
                // Vegamovies first, then your picture preference (SDR first by default).
                .sortedWith(compareBy<StreamItem>({ if (it.isVegamovies()) 0 else 1 }, { AutoPlayRules.hostRank(it.stream.url) }, { PicturePref.rank(vm, it) }))
            /** Server check + start and index saved. */
            suspend fun ready(c: StreamItem): Boolean {
                val url = c.stream.url!!
                tried += url
                val label = "${Prepare.qualityName(c.effectiveRes())} · ${Ranker.labelOf(Ranker.groupKey(c))}"
                StreamCache.register(c)
                val pr = StreamCache.probe(url)
                val playlist = isPlaylistLink(c, url)
                if (!pr.ok || playlist || !pr.seekable || pr.size <= 0) {
                    PlayTiming.late("HD: $label refused (${pr.error ?: "HTTP ${pr.status}"}${if (pr.ok && !pr.seekable) ", can't jump inside" else ""})")
                    if (Ranker.isDeadReason(pr.status, pr.type, url)) Ranker.markDeadUrl(url)
                    return false
                }
                if (!playlist) {
                    // A real jump test: a piece from the middle of the file must come back (some servers answer the start
                    // but not a jump — those can't seek).
                    val mid = (pr.size / 2) / (1024 * 1024) * (1024 * 1024)
                    val jumpOk = withTimeoutOrNull(12_000) { StreamCache.saveParts(ctx, url, mid, 256L * 1024, 1, null) { } } == null &&
                        StreamCache.savedBytes(ctx, url, mid, 256L * 1024) > 0
                    if (!jumpOk) { PlayTiming.late("HD: $label can't jump inside — next"); return false }
                    val ok = withTimeoutOrNull(45_000) {
                        kotlinx.coroutines.coroutineScope {
                            val head = async { StreamCache.saveParts(ctx, url, 0, minOf(3L * 1024 * 1024, pr.size), 1, null) { } }
                            val mp4 = url.contains(".mp4", true) || c.fileName()?.endsWith(".mp4", true) == true
                            val t = if (mp4) minOf(24L * 1024 * 1024, pr.size / 60) else minOf(8L * 1024 * 1024, pr.size / 200)
                            val tail = async { if (pr.size > 40L * 1024 * 1024) StreamCache.saveParts(ctx, url, pr.size - t, t, 2, null) { } else null }
                            head.await() == null && tail.await() == null
                        }
                    } == true
                    if (!ok) { PlayTiming.late("HD: $label didn't save in time"); return false }
                }
                PlayTiming.late("HD: $label ready (start + index saved)")
                return true
            }
            val t0 = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - t0 < 150_000) {
                // Saving in the background only while the quick playlist plays and nothing is buffering (the file you
                // watch gets the whole connection).
                if (now?.url?.let { itemFor(it) }?.belowFullHd() != true) break
                if (buffering) { delay(1_000); continue }
                val fhd = hdReady["fhd"].orEmpty(); val uhd = hdReady["4k"].orEmpty()
                if (fhd.size >= 2 && uhd.isNotEmpty()) break
                val next = (if (fhd.size < 2) eligible(3).firstOrNull()?.let { "fhd" to it } else null)
                    ?: (if (uhd.isEmpty()) eligible(4).firstOrNull()?.let { "4k" to it } else null)
                if (next == null) { if (cncDone && !needHd.isCompleted) needHd.complete(Unit); delay(1_500); continue }
                // Smart logic: the next three candidates get their one-byte check at the same time; the ones that refuse
                // are skipped at once, so the first working server is saved without waiting for each failure in turn.
                if (smartCnc) {
                    val group = eligible(if (next.first == "4k") 4 else 3).take(3)
                    if (group.size > 1) {
                        val bad = kotlinx.coroutines.coroutineScope {
                            group.map { c -> async { c to StreamCache.probe(c.stream.url!!) } }.map { it.await() }
                        }.filter { (c, pr) -> !pr.ok || (!isPlaylistLink(c, c.stream.url!!) && (!pr.seekable || pr.size <= 0)) }
                        bad.forEach { (c, pr) ->
                            val u = c.stream.url!!
                            tried += u
                            if (Ranker.isDeadReason(pr.status, pr.type, u)) Ranker.markDeadUrl(u)
                            PlayTiming.late("HD: ${Ranker.labelOf(Ranker.groupKey(c))} refused in the quick check (${pr.error ?: "HTTP ${pr.status}"})")
                        }
                        if (bad.any { it.first.stream.url == next.second.stream.url }) continue
                    }
                }
                if (ready(next.second)) hdReady = hdReady + (next.first to (hdReady[next.first].orEmpty() + next.second))
            }
            if (hdReady.isEmpty()) PlayTiming.late("HD: nothing ready (${pool.size} links seen)")
        } finally { gather.cancel() }
    }
    // ── Plugin mode: "Upgrade to 4K". The 4K links of the plugins picked in Quick Start › Upgrade to 4K are ranked with
    //    your filters (quality, audio, picture tags), the best is checked and its start + index saved (playlists need
    //    nothing saved), then the button pops up; one tap plays it from the same second.
    LaunchedEffect(everPictured, p.key) {
        if (!everPictured || BuildConfig.IS_TV || usingVlc || !vm.pluginMode()) return@LaunchedEffect
        val names = vm.qs4kNames().ifEmpty { return@LaunchedEffect }
        // The Netflix-type swap comes first (three downloads at once would slow all of them).
        delay(300); while (ottSwapping) delay(500)
        val cur0 = now?.url?.let { itemFor(it) }
        if (cur0 != null && cur0.effectiveRes() >= 2160) return@LaunchedEffect
        PlayTiming.late("4K: asking ${names.size} plugin(s) for a 4K link")
        val pool = java.util.concurrent.ConcurrentHashMap<String, StreamItem>()
        var done = false
        val gather = scope.launch {
            runCatching { vm.streamsFlow(type, p.key, only = names).collect { (l, d) -> l.forEach { s0 -> s0.stream.url?.let { u -> pool.putIfAbsent(u, s0) } }; done = d } }
            done = true
        }
        try {
            val tried = HashSet<String>()
            val t0 = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - t0 < 180_000 && hdReady["4k"].isNullOrEmpty()) {
                if (buffering) { delay(1_000); continue }
                val cands = vm.rankedCandidates(vm.filtered(pool.values.toList()).list.filter { it.effectiveRes() >= 2160 && it.stream.url !in tried }, "4k", quickOnly = false)
                    .filter { !Ranker.isDead(it) && !Ranker.keepsFailing(it) && !it.unplayableHere() }
                    .sortedWith(compareBy<StreamItem>({ vm.langRank(it) }, { PicturePref.rank(vm, it) }))
                val c = cands.firstOrNull()
                if (c == null) { if (done) break; delay(1_500); continue }
                val url = c.stream.url!!
                tried += url
                val label = "${Prepare.qualityName(c.effectiveRes())} · ${c.addonName.removePrefix("CloudStream · ")}"
                StreamCache.register(c)
                if (isPlaylistLink(c, url)) { hdReady = hdReady + ("4k" to listOf(c)); PlayTiming.late("4K: $label ready (playlist)"); break }
                val pr = StreamCache.probe(url)
                if (!pr.ok || !pr.seekable || pr.size <= 0) {
                    if (pr.ok && !pr.seekable) Ranker.markUnseekable(url)
                    PlayTiming.late("4K: $label refused (${pr.error ?: "HTTP ${pr.status}"}${if (pr.ok && !pr.seekable) ", can't jump inside" else ""})"); continue
                }
                // A real jump test: a piece from the middle must come back (servers that only send the start can't seek —
                // they'd show the first picture after every jump).
                val mid = (pr.size / 2) / (1024 * 1024) * (1024 * 1024)
                val jumpOk = withTimeoutOrNull(12_000) { StreamCache.saveParts(ctx, url, mid, 256L * 1024, 1, null) { } } == null &&
                    StreamCache.savedBytes(ctx, url, mid, 256L * 1024) > 0
                if (!jumpOk) { Ranker.markUnseekable(url); PlayTiming.late("4K: $label can't jump inside — next (kept as last backup)"); continue }
                val ok = withTimeoutOrNull(45_000) {
                    kotlinx.coroutines.coroutineScope {
                        val head = async { StreamCache.saveParts(ctx, url, 0, minOf(3L * 1024 * 1024, pr.size), 1, null) { } }
                        val mp4 = url.contains(".mp4", true) || c.fileName()?.endsWith(".mp4", true) == true
                        val tl = if (mp4) minOf(24L * 1024 * 1024, pr.size / 60) else minOf(8L * 1024 * 1024, pr.size / 200)
                        val tail = async { if (pr.size > 40L * 1024 * 1024) StreamCache.saveParts(ctx, url, pr.size - tl, tl, 2, null) { } else null }
                        head.await() == null && tail.await() == null
                    }
                } == true
                if (ok) { hdReady = hdReady + ("4k" to listOf(c)); PlayTiming.late("4K: $label ready (start + index saved)") }
                else PlayTiming.late("4K: $label didn't save in time")
            }
            if (hdReady["4k"].isNullOrEmpty()) PlayTiming.late("4K: nothing ready (${pool.size} plugin links seen)")
        } finally { gather.cancel() }
    }
    // ── TV: better quality, no background saving. Quick play starts; CNCVerse (asked on the title page) is asked again if
    //    it hasn't answered with a Vegamovies file yet. When one comes it goes first in 4K / Full HD / Mid HD and the green
    //    pill pops up (OK = play it from the same second). Nothing from Vegamovies in 90 s: the top HdHub file instead.
    LaunchedEffect(everPictured, p.key) {
        if (!BuildConfig.IS_TV || !everPictured || usingVlc) return@LaunchedEffect
        val t0 = SystemClock.elapsedRealtime()
        fun cur() = now?.url?.let { itemFor(it) }
        val startItem = cur()
        if (startItem == null || !(startItem.isPlaylist() || startItem.isMovieBox()) || startItem.isVegamovies()) return@LaunchedEffect
        if (SlowAddons.links.value[p.key].orEmpty().none { it.isVegamovies() }) launch {
            PlayTiming.late("TV: asking CNCVerse again for a Vegamovies file")
            val got = runCatching { askCncverse(vm, type, p.key, forMs = 60_000) { l -> l.any { it.isVegamovies() } } }.getOrDefault(emptyList())
            if (got.isNotEmpty()) known = (known + vm.filtered(got).list).distinctBy { it.stream.url }
        }
        var offered: String? = null
        while (SystemClock.elapsedRealtime() - t0 < 10 * 60_000L) {
            val c = cur()
            if (c == null || !(c.isPlaylist() || c.isMovieBox())) { tvUp = null; break }
            val pool = (known + quickList + SlowAddons.links.value[p.key].orEmpty()).distinctBy { it.stream.url }
            val g = withContext(Dispatchers.Default) { runCatching { vm.tvGroups(vm.filtered(pool).list) }.getOrDefault(emptyMap()) }
            // Fire TV sticks: Full HD first (4K only when no Full HD copy exists).
            // Only something at least as sharp as what plays, and Full HD or better (0.7 offered a 720p over a 1080p stream).
            val curRes = c.effectiveRes()
            val vega = (g["Full HD"].orEmpty() + g["4K"].orEmpty()).firstOrNull { it.isVegamovies() && it.effectiveRes() >= maxOf(curRes, 1080) }
            if (vega != null) {
                if (offered != vega.stream.url) {
                    offered = vega.stream.url
                    tvUp = vega; tvUpFallback = false; tvUpHidden = false; tvUpAt = SystemClock.elapsedRealtime()
                    PlayTiming.late("TV: Vegamovies ${VisualTags.quality(vega)} ready — upgrade offered")
                }
                break
            }
            if (offered == null && SystemClock.elapsedRealtime() - t0 > 90_000) {
                val hd = g["Full HD"].orEmpty().firstOrNull { it.addonName.contains("hdhub", true) && !it.isPlaylist() && it.effectiveRes() >= curRes }
                    ?: g["Full HD"].orEmpty().firstOrNull { !it.isMovieBox() && !it.isPlaylist() && it.effectiveRes() >= curRes }
                if (hd != null) {
                    offered = hd.stream.url
                    tvUp = hd; tvUpFallback = true; tvUpHidden = false; tvUpAt = SystemClock.elapsedRealtime()
                    PlayTiming.late("TV: no Vegamovies in 90 s — backup ${VisualTags.quality(hd)} offered")
                }
            }
            delay(3_000)
        }
    }
    // TV: no internet → say so at once (it used to spin through every server for 30 s), and start again when it's back.
    LaunchedEffect(Unit) {
        if (!BuildConfig.IS_TV) return@LaunchedEffect
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return@LaunchedEffect
        fun online(): Boolean = runCatching {
            val n = cm.activeNetwork ?: return@runCatching false
            cm.getNetworkCapabilities(n)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(true)
        val offlineMsg = "Your TV is offline. It will start again by itself when the internet is back."
        while (true) {
            delay(2_000)
            val on = online()
            if (!on && !firstFrame && failure == null) { PlayTiming.mark("TV offline — waiting for the internet", once = "offline"); failure = offlineMsg }
            else if (on && failure == offlineMsg) { failure = null; findRequest = if (findRequest < 1) 2 else findRequest + 1 }
        }
    }
    // TV: "Watch together" was chosen on the title page — open its panel once the picture is on.
    LaunchedEffect(firstFrame) {
        if (BuildConfig.IS_TV && firstFrame && TvFlags.openTogether) { TvFlags.openTogether = false; delay(600); panel = "together" }
    }
    // TV: the pill hides itself after 30 s (it comes back with the controls); the keys go back to the video when the
    // Quality bubble closes.
    LaunchedEffect(tvUp, tvUpAt) { if (tvUp != null) { delay(30_500); tvZone = if (tvZone == "upg") "bar" else tvZone } }
    LaunchedEffect(tvQ) { if (BuildConfig.IS_TV && !tvQ) { delay(40); runCatching { rootFocus.requestFocus() } } }
    // Sources by visual tag: the first file of the top three groups gets its start + index saved (opens in about a second
    // when picked). At most 5 files are saved ahead in all (with the HD ones); a file already saved isn't saved twice.
    LaunchedEffect(firstFrame, p.key) {
        if (!firstFrame || BuildConfig.IS_TV || usingVlc) return@LaunchedEffect
        delay(25_000)
        val all = withTimeoutOrNull(30_000) { runCatching { vm.allStreams(type, p.key) }.getOrNull() }.orEmpty()
        val list = vm.filtered((known + all + SlowAddons.links.value[p.key].orEmpty()).distinctBy { it.stream.url }
            .filter { !Ranker.isDead(it) && !Ranker.keepsFailing(it) && HostStats.penalty(it.stream.url.orEmpty()) == 0 && !it.unplayableHere() && !it.isMovieBox() }).list
            .sortedWith(compareByDescending<StreamItem> { Ranker.score(it) }.thenByDescending { it.effectiveRes() })
        if (!vm.upgradeAllowed()) return@LaunchedEffect
        for ((tagName, files) in tagGroups(list, PicturePref.order(vm)).take(3)) {
            // Only while the quick playlist plays and nothing is buffering.
            while (buffering) delay(1_000)
            if (now?.url?.let { itemFor(it) }?.isPlaylist() != true) break
            val c = files.first()
            val url = c.stream.url ?: continue
            val hd = hdReady.values.flatten().mapNotNull { it.stream.url }.toSet()
            if (url in hd || url in tagReady) continue
            if (hd.size + tagReady.size >= 5) break
            StreamCache.register(c)
            val pr = StreamCache.probe(url)
            val playlist = isPlaylistLink(c, url)
            if (!pr.ok || (!playlist && (!pr.seekable || pr.size <= 0))) { PlayTiming.late("Sources: $tagName first file refused (or can't jump inside)"); continue }
            val ok = playlist || withTimeoutOrNull(45_000) {
                kotlinx.coroutines.coroutineScope {
                    val head = async { StreamCache.saveParts(ctx, url, 0, minOf(3L * 1024 * 1024, pr.size), 1, null) { } }
                    val mp4 = url.contains(".mp4", true) || c.fileName()?.endsWith(".mp4", true) == true
                    val t = if (mp4) minOf(24L * 1024 * 1024, pr.size / 60) else minOf(8L * 1024 * 1024, pr.size / 200)
                    val tail = async { if (pr.size > 40L * 1024 * 1024) StreamCache.saveParts(ctx, url, pr.size - t, t, 2, null) { } else null }
                    head.await() == null && tail.await() == null
                }
            } == true
            if (ok) { tagReady = tagReady + url; PlayTiming.late("Sources: $tagName · ${VisualTags.quality(c)} ready") }
        }
    }
    // The offer: as soon as a Full HD (or, with 4K chosen, a 4K) file is ready — 30 s count down; after any jump, 5 s of
    // playing at the new place, then 30 s again. One tap switches to it (its start and index are already saved).
    // Keyed on the picture too: an HD file that got ready while the picture was briefly away still gets its offer.
    LaunchedEffect(hdReady.isNotEmpty(), now?.url, firstFrame) {
        upgrade = null
        if (hdReady.isEmpty() || !firstFrame) return@LaunchedEffect
        // Shown once per video: afterwards the ready file waits in Quality (a "4K READY" badge), so nothing is
        // downloaded again around every jump (it was re-offered — and re-saved — after each buffer or seek).
        if (offeredOnce) return@LaunchedEffect
        val cur = now?.url?.let { itemFor(it) } ?: StreamItem(now?.addon ?: "", StreamInfo(url = now?.url))
        // Offered whenever what plays is lower quality than the ready file (it used to need a MovieBox file playing,
        // so after other quick sources started first, the pill never came).
        val best = (hdReady["4k"].orEmpty() + hdReady["fhd"].orEmpty()).maxOfOrNull { it.effectiveRes() } ?: 0
        if (!cur.belowFullHd() && cur.effectiveRes() >= best) { PlayTiming.late("HD: offer skipped — already playing ${cur.effectiveRes()}p"); return@LaunchedEffect }
        (upCooldownUntil - SystemClock.elapsedRealtime()).takeIf { it > 0 }?.let { delay(it) }
        suspend fun playedInARow(ms: Long) {
            var acc = 0L; var last = curPos(); var lastT = SystemClock.elapsedRealtime()
            while (acc < ms) {
                delay(250)
                val p0 = curPos(); val t0 = SystemClock.elapsedRealtime(); val dt = t0 - lastT
                acc = if (isPlayingNow() && (p0 - last) in 0..(dt * 3 + 1_500)) acc + dt else 0L
                last = p0; lastT = t0
            }
        }
        var round = 0
        while (true) {
            val pick = (if (quality == "4k") hdReady["4k"]?.firstOrNull { it.stream.url !in upTried } else null)
                ?: hdReady["fhd"]?.firstOrNull { it.stream.url !in upTried } ?: hdReady["4k"]?.firstOrNull { it.stream.url !in upTried } ?: return@LaunchedEffect
            // After a jump: a few MB of the HD file saved at the new spot first, so the tap is instant there too.
            if (round++ > 0) pick.stream.url?.let { u -> saveAround(ctx, u, curPos(), dur) }
            upgrade = UpgradeOffer(pick, pick.effectiveRes() >= 2160)
            offeredOnce = true
            PlayTiming.late("HD: Upgrade quality shown (${Prepare.qualityName(pick.effectiveRes())}) — once; then it waits in Quality")
            var left = 30
            var jumped = false
            while (left > 0) {
                upLeft = left
                val before = curPos()
                delay(1_000)
                if (upgrade == null) return@LaunchedEffect
                val here = curPos()
                if (here < before - 1_500 || here > before + 4_000) { jumped = true; break }
                if (isPlayingNow()) left--
            }
            upgrade = null
            if (!jumped || round > 0) return@LaunchedEffect
            // Jumped while it was showing: one more short showing after 5 s of playing at the new place.
            playedInARow(5_000)
        }
    }
    // The upgraded HD file stalls (buffering 8 s while playing): back to MovieBox too.
    LaunchedEffect(buffering, now?.url, firstFrame) {
        if (!buffering || !firstFrame || upgradedUrl == null || now?.url != upgradedUrl) return@LaunchedEffect
        delay(8_000)
        // Right after a jump the 4K file needs time to load from the new point: give it a full minute.
        val sinceJump = SystemClock.uptimeMillis() - seekedAt
        if (sinceJump < 60_000) delay(60_000 - sinceJump)
        if (buffering && now?.url == upgradedUrl) backToMovieBox("stalled after waiting")
    }
    // Online subtitles drawn by the app, in step with the video (moved by "Adjust timing"); while Size is dragged, two
    // sample lines (one line, then two) show at the real size and place.
    LaunchedEffect(ext, subOffsetMs, subPreview) {
        if (subPreview) {
            var i = 0
            while (true) {
                cues = listOf(Cue.Builder().setText(if (i % 2 == 0) "Where did you put the keys?" else "I left them on the table,\nright next to your phone.").build())
                i++; delay(1400)
            }
        }
        val e = ext
        if (e == null) { cues = emptyList(); return@LaunchedEffect }
        while (true) {
            val t = curPos() - subOffsetMs
            cues = ExtSubs.at(e.cues, t).map { Cue.Builder().setText(it.text).build() }
            delay(80)
        }
    }
    var syncTick by remember { mutableIntStateOf(0) }
    /** Online subtitles downloaded for this video (up to five) — auto sync picks the one that fits the talking best. */
    var subCands by remember { mutableStateOf<List<ExtActive>>(emptyList()) }
    /** The shown online subtitle was picked by the app (so auto sync may swap it for a better-fitting one). */
    var autoPickedSub by remember { mutableStateOf(false) }
    /** What auto sync is doing (shown under Adjust timing). */
    var syncNote by remember { mutableStateOf("") }
    /** "Sync now": line the online subtitle up with the speech heard so far (the last 3 minutes). Quiet: the result
     *  shows under Adjust timing only, never as a popup. */
    fun syncNow() {
        val e = ext ?: return
        val t = curPos()
        val off = Speech.bestOffset(e.cues, t - 180_000, t, subOffsetMs)
        syncNote = when {
            off != null -> { subOffsetMs = off; "Synced " + (if (off > 0) "+" else "") + "%.1f s".format(off / 1000f) }
            Speech.lastWhy == "ok" -> "In step ✓"
            else -> "Needs a little more talking — try again in ~30 s"
        }
    }
    // Subtitle auto sync (Xstream): listen once at the start until there's enough talking, sync once, then only check
    // again every 30 minutes (or right away when Sync is tapped). Silent: no popups, just a note under Adjust timing.
    LaunchedEffect(ext?.sub?.url, syncTick) {
        val e0 = ext ?: run { syncNote = ""; return@LaunchedEffect }
        if (!isOn(opt("subAutoSync")) || usingVlc) { syncNote = ""; return@LaunchedEffect }
        // Another subtitle file: its timing starts from 0 again (the last file's shift doesn't carry over).
        if (syncTick == 0 && !manualSync) subOffsetMs = 0L
        var e = e0
        /** One check: the best-fitting subtitle, its best shift for the last 3 minutes heard, and how well it fits. */
        fun check(): Pair<Long, Float>? {
            if (manualSync || ext?.sub?.url != e.sub.url || !isPlayingNow()) return null
            val t = curPos()
            val scored = (if (autoPickedSub) subCands else listOf(e)).mapNotNull { c ->
                Speech.evaluate(c.cues, t - 180_000, t, if (c.sub.url == e.sub.url) subOffsetMs else 0L)?.let { c to it }
            }
            if (scored.isEmpty()) return null
            val (bestSub, fit) = scored.maxBy { it.second.second }
            if (bestSub.sub.url != e.sub.url && fit.second >= 0.7f) { e = bestSub; ext = bestSub }
            return fit
        }
        // Starts at 0 and keeps listening in the background the whole time (every 20 s). The timing only moves when it's
        // sure: the same shift found in several checks in a row (3 for small shifts, 4 for shifts over 8 s) and a good
        // fit — so a guess never pushes a subtitle that was already right to -30 s.
        syncNote = "Auto sync: listening · in step (0.0 s)"
        var cand: Long? = null; var agree = 0
        while (true) {
            delay(20_000L)
            if (manualSync || ext?.sub?.url != e.sub.url) return@LaunchedEffect
            val (off, fit) = check() ?: continue
            if (kotlin.math.abs(off - subOffsetMs) < 300) { cand = null; agree = 0
                syncNote = "Auto sync: listening · in step (" + (if (subOffsetMs > 0) "+" else "") + "%.1f s)".format(subOffsetMs / 1000f); continue }
            if (cand != null && kotlin.math.abs(off - cand!!) < 300) agree++ else { cand = off; agree = 1 }
            val need = if (kotlin.math.abs(off) > 8_000) 4 else 3
            if (agree >= need && fit >= 0.62f) {
                subOffsetMs = off; cand = null; agree = 0
                syncNote = "Auto sync: in step (" + (if (off > 0) "+" else "") + "%.1f s)".format(off / 1000f)
                ActLog.add("SUBSYNC", "moved to $off ms (fit ${"%.2f".format(fit)}, $need checks agreed)")
            } else syncNote = "Auto sync: listening…"
        }
    }
    // Follow the speaker (beta): while a line is on screen, a small copy of the picture is checked ~3 times a second.
    LaunchedEffect(isOn(opt("subFollow")), usingVlc) {
        if (!isOn(opt("subFollow")) || usingVlc || BuildConfig.IS_TV) { speakerAt = 0.5f; return@LaunchedEffect }
        SpeakerTracker.reset()
        var away = 0
        while (true) {
            delay(330)
            val v = videoView
            if (cues.isEmpty() || v == null || !isPlayingNow()) { if (cues.isEmpty() && ++away > 6) speakerAt = 0.5f; continue }
            away = 0
            val got = kotlinx.coroutines.CompletableDeferred<android.graphics.Bitmap?>()
            captureFrame(v) { b -> got.complete(b) }
            val bmp = withTimeoutOrNull(1_000) { got.await() } ?: continue
            val x = runCatching { withContext(Dispatchers.Default) { SpeakerTracker.speakerX(bmp) } }.getOrNull()
            speakerAt = x ?: 0.5f
        }
    }
    /** Subtitles chosen by hand during this play (then the automatic defaults leave them alone). */
    var subsByHand by remember { mutableStateOf(false) }
    // Netflix / Prime Video playlists name their audio tracks ("1. Czech, 2. German … 6. Hindi") without a language
    // code, so the player started on the first one (Czech). Once per video: the track whose name matches your language
    // order (or your remembered audio language) is picked.
    var audioAutoFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tracks, now?.url) {
        val u = now?.url ?: return@LaunchedEffect
        if (usingVlc || audioAutoFor == u) return@LaunchedEffect
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.size < 2) return@LaunchedEffect
        audioAutoFor = u
        val want = (listOfNotNull(vm.playerPref("audio").takeIf { it.isNotBlank() && it != "off" }?.let { runCatching { java.util.Locale(it).displayLanguage.lowercase() }.getOrNull() }) +
            vm.qsLangs().filter { it != "original" }).distinct()
        fun nameOf(f: androidx.media3.common.Format): String = ((f.label ?: "") + " " + (f.language?.takeIf { it != "und" }?.let { runCatching { java.util.Locale(it).displayLanguage }.getOrNull() } ?: "")).lowercase()
        val cur = groups.firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
        val curRank = cur?.let { f -> want.indexOfFirst { it in nameOf(f) } }?.takeIf { it >= 0 } ?: Int.MAX_VALUE
        var best: Pair<Tracks.Group, Int>? = null; var bestRank = curRank
        groups.forEach { g -> (0 until g.length).forEach { i ->
            val r = want.indexOfFirst { it in nameOf(g.getTrackFormat(i)) }
            if (r in 0 until bestRank && g.isTrackSupported(i)) { bestRank = r; best = g to i }
        } }
        val b = best ?: return@LaunchedEffect
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(b.first.mediaTrackGroup, b.second)).build()
        PlayTiming.late("audio: picked ${b.first.getTrackFormat(b.second).label ?: "?"} (your language)")
    }
    /** Is Hindi what you hear now? (the selected audio track, else the stream's language) */
    fun hindiAudioNow(): Boolean {
        val f = (if (tracks.groups.none { it.type == C.TRACK_TYPE_AUDIO }) player.currentTracks else tracks).groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }.firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
        val lang = f?.language?.takeIf { it.isNotBlank() && it != "und" }?.let { langName(it) }
            ?: now?.url?.let { itemFor(it) }?.languagesOrOriginal()?.firstOrNull()
        return lang == "hindi"
    }
    // Subtitles inside the video: picked automatically (your subtitle language first, else one with no language named,
    // else the first) — once per video, unless you turned subtitles off.
    var subAutoFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tracks, now?.url, firstFrame) {
        val url0 = now?.url ?: return@LaunchedEffect
        // Never touch the track choice before the picture shows: a change while it opens makes the player start over
        // (that was the 5–6 s "buffering" in 0.9.28).
        if (!firstFrame || usingVlc || subsByHand) return@LaunchedEffect
        // Hindi audio: subtitles off by default (pick one by hand if you want). Any other language: English on.
        val hindi = hindiAudioNow()
        val url = url0 + if (hindi) "#hi" else "#other"
        if (subAutoFor == url) return@LaunchedEffect
        if (hindi) {
            subAutoFor = url
            if (ext != null) ext = null
            val anyOn = tracks.groups.any { g -> g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.isTrackSelected(it) } }
            if (anyOn) disableExoText()
            return@LaunchedEffect
        }
        if (ext != null || vm.playerPref("subs") == "off") return@LaunchedEffect
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (groups.isEmpty()) return@LaunchedEffect
        subAutoFor = url
        if (groups.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }) return@LaunchedEffect
        val wanted = subLangCodes(opt("subLangs").ifBlank { "english" }).ifEmpty { listOf("en") }
        val all = groups.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
        fun lang(x: Pair<Tracks.Group, Int>) = x.first.getTrackFormat(x.second).language?.lowercase().orEmpty()
        fun forced(x: Pair<Tracks.Group, Int>) = (x.first.getTrackFormat(x.second).selectionFlags and C.SELECTION_FLAG_FORCED) != 0
        fun side(x: Pair<Tracks.Group, Int>) = x.first.getTrackFormat(x.second).isSidecar()
        val pick = all.filter { !forced(it) }.minWithOrNull(compareBy<Pair<Tracks.Group, Int>>({ x -> wanted.indexOfFirst { lang(x).startsWith(it) }.let { if (it < 0) 99 else it } }, { if (side(it)) 1 else 0 }))
            ?.takeIf { x -> wanted.any { lang(x).startsWith(it) } || lang(x).isEmpty() || lang(x) == "und" }
            ?: return@LaunchedEffect
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(pick.first.mediaTrackGroup, pick.second)).build()
    }
    // Subtitles sent with the link (addons, CloudStream / Nuvio plugins): the chosen one is drawn by the app instead of
    // the player, so Sync / auto sync can move it (the player can't shift its own subtitles).
    LaunchedEffect(tracks, firstFrame) {
        if (!firstFrame || usingVlc) return@LaunchedEffect
        val f = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            .firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } } ?: return@LaunchedEffect
        if (!f.isSidecar()) return@LaunchedEffect
        val h = f.id?.substringAfter("side:")?.toIntOrNull() ?: return@LaunchedEffect
        val sub = now?.subs?.firstOrNull { it.url.hashCode() == h } ?: return@LaunchedEffect
        if (ext?.sub?.url == sub.url) return@LaunchedEffect
        val cues = ExtSubs.load(sub.url) ?: return@LaunchedEffect      // can't read it: the player keeps showing it
        autoPickedSub = false; manualSync = false
        ext = ExtActive(sub, cues); disableExoText()
        ActLog.add("SUBSYNC", "link subtitle ${sub.lang} drawn by the app (syncable)")
    }
    // A video without subtitles inside: your language's online subtitles are downloaded (the first three) and the first
    // one is shown at once — unless you turned subtitles off (Settings › Playback › Online subtitles by default).
    LaunchedEffect(firstFrame) {
        if (!firstFrame || usingVlc || BuildConfig.IS_TV || ext != null) return@LaunchedEffect
        if (!isOn(opt("autoOnlineSubs")) || vm.playerPref("subs") == "off") return@LaunchedEffect
        delay(1500)
        if (hindiAudioNow() || subsByHand) return@LaunchedEffect
        val wanted = subLangCodes(opt("subLangs").ifBlank { "english" })
        // Subtitles in your language inside the video (or sent with the link) win: they are picked automatically.
        val textNow = (if (tracks.groups.none { it.type == C.TRACK_TYPE_TEXT }) player.currentTracks else tracks).groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (textNow.any { g -> (0 until g.length).any { i -> val l = g.getTrackFormat(i).language?.lowercase().orEmpty(); wanted.any { l.startsWith(it) } || ((l.isEmpty() || l == "und") && !g.getTrackFormat(i).isSidecar()) } }) return@LaunchedEffect
        // Five at once: the first that arrives is shown; all five are compared with the talking later (auto sync).
        val list = runCatching { vm.onlineSubtitles(type, p.key) }.getOrDefault(emptyList())
            .filter { s0 -> wanted.any { w -> s0.lang.lowercase().startsWith(w) } }.take(5)
        kotlinx.coroutines.coroutineScope {
            list.map { sub -> async { ExtSubs.load(sub.url)?.let { ExtActive(sub, it) } } }.forEach { d ->
                launch {
                    val x = d.await() ?: return@launch
                    subCands = subCands + x
                    if (ext == null) { ext = x; autoPickedSub = true; disableExoText() }
                }
            }
        }
    }
    var guide by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(p.key) { guide = ParentalGuide.get(p.key) }
    var openingShown by remember { mutableStateOf(false) }
    var showOpening by remember { mutableStateOf(false) }
    LaunchedEffect(firstFrame) {
        if (!firstFrame || openingShown) return@LaunchedEffect
        openingShown = true; delay(500); showOpening = true; delay(7_500); showOpening = false
    }
    LaunchedEffect(controls) { if (!controls) touchingControls = false }
    LaunchedEffect(inPip) { if (inPip) { controls = false; panel = null; showInfo = false } }
    // Sleep timer: the sound fades out over the last 30 s, then the video pauses.
    LaunchedEffect(sleepAt, sleepEnd) {
        val at = sleepAt
        if (at == null && !sleepEnd) return@LaunchedEffect
        while (true) {
            val left = if (sleepEnd) (curDur() - curPos()).coerceAtLeast(0L) else at!! - SystemClock.elapsedRealtime()
            if (left <= 30_000) {
                if (!usingVlc) player.volume = (left / 30_000f).coerceIn(0f, 1f)
                if (left <= 300) break
            }
            delay(if (left > 40_000) 5_000 else 250)
        }
        pauseNow()
        if (!usingVlc) player.volume = 1f
        sleepAt = null; sleepEnd = false
        Snack.show("Sleep timer: paused. Sleep well!")
    }
    // Paused: the info screen appears after 6 s without any touch (a touch on the controls, › included, starts the 6 s again).
    LaunchedEffect(playing, firstFrame, poke, panel, touchingControls, qualityMenu) {
        if (firstFrame && !playing && !buffering && isOn(opt("pauseInfo"))) {
            if (panel != null || touchingControls || qualityMenu) return@LaunchedEffect
            delay(6000); if (!isPlayingNow() && panel == null) { showInfo = true; controls = false }
        } else if (playing && !infoManual) showInfo = false
    }

    fun seekBy(ms: Long) { seekAbs((curPos() + ms).coerceIn(0L, maxOf(0L, curDur()))); poke++ }

    fun playEpisode(ep: Video) {
        val m = meta ?: return
        // Same saved place the title page would use (Continue Watching or the episode's own progress).
        val saved = (vm.progress.value.firstOrNull { it.key == ep.id } ?: vm.epProgress.value[ep.id])
            ?.takeIf { it.durationMs <= 0 || it.positionMs < it.durationMs * 0.95 }?.positionMs ?: 0L
        onReplace(
            Screen.Player(
                "${m.name}  S${ep.season}E${ep.episode}", "", emptyList(), key = ep.id,
                image = ep.thumbnail ?: m.background ?: m.poster, type = type, meta = m, resumeMs = saved,
                source = if (p.quick) null else now?.source, addon = now?.addon, find = true, quick = p.quick,
            )
        )
    }
    fun playNext() { nextEp?.let { playEpisode(it) } }
    /** Play another Quick Start file (quality or language change) from the same second. */
    fun switchTo(st: StreamItem, label: String? = null) {
        val keep = keepPos()
        val addonSubs = now?.subs.orEmpty().filter { sub -> st.stream.subtitles.orEmpty().none { it.url == sub.url } }
        load(Now(st.stream.url!!, st.stream.subtitles.orEmpty() + addonSubs, st.stream.name ?: st.addonName, st.addonName), keep, label)
    }
    /** The upgraded HD file failed: straight back to the MovieBox link at the same second (no hunting through other HD files). */
    backToMovieBox = fun(why: String): Boolean {
        val from = upgradedFrom ?: return false
        val n = now ?: return false
        if (n.url != upgradedUrl) return false
        PlayTiming.mark("upgrade: the HD file failed ($why) — back to MovieBox")
        tried += n.url
        upTried += n.url
        upgradedFrom = null; upgradedUrl = null
        upCooldownUntil = SystemClock.elapsedRealtime() + 60_000
        HdMemo.forget(ctx, p.key)
        Snack.show("The HD file stopped — back to the quick server")
        switchTo(from, "Back to the quick server")
        return true
    }
    // ───── Plugin mode: playing MovieBox because Netflix / Prime Video / Hotstar / MPlayer weren't ready yet ─────
    // Their links keep loading in the background; the best one (same or better language) opens in a second, silent
    // player a few seconds ahead, paused on that exact frame. When the video reaches that frame the second player starts,
    // fades in over 0.3 s (sound crossfades too) and simply becomes the player — no loading, no message. If the two
    // copies aren't the same length (another cut), there's no swap: "Upgrade quality" pops up instead.
    var ottSwapStarted by remember(p.key) { mutableStateOf(false) }
    suspend fun ottSwapTo(c: StreamItem): Boolean {
        val url = c.stream.url ?: return false
        val name = c.addonName.removePrefix("CloudStream · ")
        val hold = HeaderHold(StreamHeaders.forUrl(url))
        val sp = buildPlayer(ctx, vm, true, hold = hold)
        sp.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
        sp.volume = 0f
        var failed = false
        sp.addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failed = true } })
        val mime = StreamType.mimeFor(url, c.fileName())
        val subsNow = withSubs(c, now?.subs.orEmpty())
        val lead = 8_000L
        var target = curPos() + lead
        sp.setMediaItem(MediaItem.Builder().setUri(url).apply { if (mime != null) setMimeType(mime) }.setSubtitleConfigurations(subtitleConfigs(subsNow)).build(), target)
        sp.playWhenReady = false
        sp.prepare()
        shadow = sp; shadowAlpha = 0f
        fun drop() { if (shadow === sp) { shadow = null; shadowAlpha = 0f }; runCatching { sp.release() } }
        suspend fun ready(ms: Long): Boolean {
            val t0 = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - t0 < ms) { if (failed) return false; if (sp.playbackState == Player.STATE_READY) return true; delay(100) }
            return false
        }
        PlayTiming.late("swap: opening $name quietly at ${fmtTime(target)}")
        if (!ready(25_000)) { PlayTiming.late("swap: $name didn't open in time"); drop(); return false }
        val dMain = curDur(); val dSh = sp.duration
        if (dMain <= 0 || dSh <= 0 || kotlin.math.abs(dMain - dSh) > 1_500) {
            PlayTiming.late("swap: lengths differ (${fmtTime(dMain)} vs ${fmtTime(dSh)}) — Upgrade quality offered instead")
            drop()
            if (upgrade == null) { upgrade = UpgradeOffer(c, false); upLeft = 30 }
            return true
        }
        // Wait for the picture to reach the second player's frame; aim again if you jumped or paused for long.
        while (true) {
            if (failed) { drop(); return false }
            val n = now ?: run { drop(); return true }
            if (usingVlc || itemFor(n.url)?.isOtt() == true) { drop(); return true }
            val pos = curPos()
            if (pos > target - 40 && pos < target + 250 && isPlayingNow()) break
            if (pos >= target + 250 || pos < target - 90_000) {
                target = pos + lead
                sp.seekTo(target)
                if (!ready(15_000)) { drop(); return false }
                continue
            }
            delay(((target - 40 - pos) / 2).coerceIn(5, 100))
        }
        // Go: the second player starts and fades in, the sound crossfades, then it IS the player.
        sp.play()
        val old = player
        repeat(10) { i -> val f = (i + 1) / 10f; shadowAlpha = f; sp.volume = f; runCatching { old.volume = 1f - f }; delay(30) }
        PlayTiming.late("swap: now playing $name (frame-matched, ${kotlin.math.abs(curPos() - (sp.currentPosition))} ms apart)")
        hold.map = null
        StreamHeaders.active = StreamHeaders.forUrl(url)
        known = (known + c).distinctBy { it.stream.url }
        sp.setSeekParameters(androidx.media3.exoplayer.SeekParameters(300_000L, 300_000L))
        player = sp
        runCatching { old.release() }
        ways = waysFor(url, "exo", manifest = mime != null); wayIdx = 0
        now = Now(url, subsNow, c.stream.name ?: c.addonName, c.addonName)
        vm.rememberLink(p.key, url, now?.source, now?.addon)
        if (speed != 1f) sp.setPlaybackSpeed(speed)
        playing = sp.isPlaying; buffering = false
        return true
    }
    LaunchedEffect(firstFrame) {
        if (!firstFrame || BuildConfig.IS_TV || usingVlc || !vm.pluginMode() || ottSwapStarted) return@LaunchedEffect
        val cur = now?.url?.let { itemFor(it) } ?: return@LaunchedEffect
        if (cur.isOtt()) return@LaunchedEffect
        ottSwapStarted = true
        ottSwapping = true
        scope.launch {
            val pool = java.util.concurrent.ConcurrentHashMap<String, StreamItem>()
            var done = false
            val gather = scope.launch {
                runCatching { vm.streamsFlow(type, p.key).collect { (l, d) -> vm.filtered(l).list.filter { it.isOtt() }.forEach { s -> s.stream.url?.let { u -> pool.putIfAbsent(u, s) } }; done = d } }
                done = true
            }
            try {
                val t0 = SystemClock.elapsedRealtime()
                val triedOtt = HashSet<String>()
                while (SystemClock.elapsedRealtime() - t0 < 120_000) {
                    val playing = now?.url?.let { itemFor(it) } ?: cur
                    if (playing.isOtt() || usingVlc) break
                    val c = vm.rankedCandidates(pool.values.toList(), "auto", quickOnly = false)
                        .firstOrNull { it.stream.url !in triedOtt && vm.langRank(it) <= vm.langRank(playing) && !it.unplayableHere() }
                    if (c == null) { if (done) break; delay(800); continue }
                    triedOtt += c.stream.url!!
                    if (ottSwapTo(c)) break
                }
            } finally {
                gather.cancel()
                shadow?.takeIf { it !== player }?.let { runCatching { it.release() }; shadow = null; shadowAlpha = 0f }
                ottSwapping = false
            }
        }
    }
    /** An online subtitle: reload this link from the same second with it added first, and pick it. */
    fun addOnlineSub(sub: Subtitle) {
        val cur = now ?: return
        if (ext?.sub?.url?.let { sameSubUrl(it, sub.url) } == true) return   // already on: no reload, no flicker
        vm.setPlayerPref("subs", sub.lang)
        // Shown by the app itself: no reload, the video keeps playing.
        if (!usingVlc) {
            scope.launch {
                val c = ExtSubs.load(sub.url)
                if (c != null) { subsByHand = true; autoPickedSub = false; manualSync = false; ext = ExtActive(sub, c); disableExoText(); Snack.show("Subtitles: ${sub.lang.uppercase()}") }
                else Snack.show("That subtitle file couldn't be read — try another one")
            }
            return
        }
        panel = null
        if (!usingVlc) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(sub.lang).build()
        load(Now(cur.url, listOf(sub) + cur.subs.filter { it.url != sub.url }, cur.source, cur.addon), keepPos(), "Adding subtitles")
    }
    fun setQuality(id: String) {
        quality = id; qualityMenu = false; poke++
        vm.setPlayerPref("quality", id)
        val st = vm.pickQuick(quickList, id) ?: return
        if (st.stream.url != now?.url) switchTo(st, AllQualityLevels.firstOrNull { it.id == id }?.label)
    }
    fun setAspect(i: Int, label: String = AspectModes[i].first) {
        cinematic = false
        aspectIdx = i; resize = AspectModes[i].second; aspectLabel = label
        vlc?.setAspect(i)
        vm.setPlayerPref("aspect", i.toString())
    }

    // ───── Elite auto quality ─────
    // With "Auto" chosen and Elite on, the player steps between 4K / 1080p / 720p (TV: 1080p / 720p) at the same second:
    // down after two stalls (or one long one), up again after 90 s of smooth playback with a full buffer.
    var stalls by remember { mutableIntStateOf(0) }
    var stallAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(buffering, firstFrame) {
        if (firstFrame && buffering && !switching) {
            val t = SystemClock.uptimeMillis()
            stalls = if (t - stallAt < 90_000) stalls + 1 else 1
            stallAt = t
        }
    }
    LaunchedEffect(firstFrame, quality, quickList.size) {
        if (!firstFrame || quality != "auto" || quickList.isEmpty() || !isOn(opt("autoQuality")) || !vm.eliteOn()) return@LaunchedEffect
        stalls = 0
        var smoothSince = SystemClock.uptimeMillis()
        val ladder = if (BuildConfig.IS_TV) listOf(720, 1080) else listOf(720, 1080, 2160)
        fun streamAt(r: Int, cur: StreamItem): StreamItem? = quickList
            .filter { vm.isQuick(it) && it.resolution() == r && it.stream.url.let { u -> u != null && u !in tried } }
            .sortedWith(compareBy({ if (it.addonName == cur.addonName) 0 else 1 }, { vm.langRank(it) }))
            .firstOrNull()?.takeIf { vm.langRank(it) <= vm.langRank(cur) }
        while (isActive) {
            delay(4000)
            val url = now?.url ?: continue
            val cur = itemFor(url) ?: continue
            val res = cur.resolution()
            val t = SystemClock.uptimeMillis()
            if (buffering || stalls > 0) smoothSince = t
            val longStall = buffering && t - stallAt > 5000 && t - seekedAt > 60_000
            if ((stalls >= 2 && t - seekedAt > 60_000) || longStall) {
                val lower = ladder.filter { it < res }.sortedDescending().firstNotNullOfOrNull { r -> streamAt(r, cur) }
                if (lower != null) { stalls = 0; switchTo(lower, "Auto · ${lower.resolution()}p (slow connection)"); return@LaunchedEffect }
            } else if (!usingVlc && t - smoothSince > 90_000 && player.bufferedPosition - player.currentPosition > 45_000) {
                val higher = ladder.filter { it > res }.sorted().firstNotNullOfOrNull { r -> streamAt(r, cur) }
                if (higher != null) { switchTo(higher, "Auto · ${higher.resolution()}p"); return@LaunchedEffect }
                smoothSince = t // nothing better: check again later
            }
        }
    }

    // ───── Remote seeking: ◀ ▶ move a target (with a preview frame); OK / Play jumps there, or it jumps by itself 1.6 s later ─────
    fun commitSeek() {
        val t = pendingSeek ?: return
        pendingSeek = null
        seekAbs(t); pos = t; poke++
    }
    fun nudgeSeek(forward: Boolean, repeat: Int) {
        val d = curDur()
        if (d <= 0L) return
        // On the seek bar: 10 s a press (30 s while held down).
        val step = if (repeat >= 6) 30_000L else 10_000L
        val base = pendingSeek ?: curPos()
        pendingSeek = (base + if (forward) step else -step).coerceIn(0L, d)
        controls = true; poke++; seekKeyTick++
        thumbs?.warmUp()
    }
    /** Controls hidden: ◀ ▶ jump 30 s straight away, shown as a bubble that adds up while you keep pressing. */
    fun bubbleSeek(forward: Boolean) {
        // Phones: a plain 10 s jump with the small hint (the 30 s bubble is TV only).
        if (!BuildConfig.IS_TV) { seekBy(if (forward) 10_000 else -10_000); seekHint = if (forward) "+10s" else "-10s"; return }
        val d = curDur()
        if (d <= 0L) return
        seekAbs((curPos() + if (forward) 30_000L else -30_000L).coerceIn(0L, d))
        val b = bubble
        bubble = if (b != null && b.first == forward) forward to (b.second + 30) else forward to 30
        bubbleTick++
    }
    /** Hide the controls; play on if ▲ had paused the video. */
    fun hideControls() {
        controls = false; more = false
        if (pausedByUp) { pausedByUp = false; if (!isPlayingNow()) togglePlay() }
    }
    /** TV: Back (or ▲ in Watch Next / ▼ under the buttons) — everything away, the video plays on. */
    fun tvClearAll() {
        watchNext = false; panel = null; panelFromMore = false; tvPillMode = false; qualityMenu = false; tvQ = false; showEpisodes = false; showInfo = false; pendingSeek = null
        controls = false; more = false; tvZone = "bar"; pausedByUp = false
        val paused = if (usingVlc) vlc?.isPlaying != true else !player.playWhenReady
        if (paused && firstFrame) togglePlay()
    }
    fun handleBack() {
        if (BuildConfig.IS_TV && firstFrame && !locked && failure == null) {
            if (tvQ) { tvQ = false; controls = true; tvZone = "top"; return }
            if (tvPillMode) { tvPillMode = false; return }
            if (panel != null && panelFromMore) { panel = null; panelFromMore = false; controls = true; more = true; return }
            if (watchNext || panel != null || qualityMenu || showEpisodes || showInfo || controls || more || pendingSeek != null || !isPlayingNow() && !(if (usingVlc) vlc?.isPlaying == true else player.playWhenReady)) { tvClearAll(); return }
            onBack(); return
        }
        when {
            watchNext -> watchNext = false
            panel != null -> panel = null
            qualityMenu -> qualityMenu = false
            showEpisodes -> showEpisodes = false
            showInfo -> showInfo = false
            pendingSeek != null -> pendingSeek = null
            locked && firstFrame -> { controls = true; poke++ }
            // TV: the first Back hides the controls, the next one leaves.
            BuildConfig.IS_TV && controls && firstFrame -> { controls = false; more = false }
            else -> onBack()
        }
    }
    /** TV: the green "better quality is ready" pill is on screen (for 30 s, or while the controls show). */
    fun tvUpVisible(): Boolean = BuildConfig.IS_TV && tvUp != null && !tvUpHidden && SystemClock.elapsedRealtime() - tvUpAt < 30_000
    /** The pills on screen now, top to bottom. */
    fun tvPills(): List<String> = buildList { if (tvSkipOn) add("skip"); if (tvUpVisible()) add("upg"); if (tvNextOn) add("next") }
    fun tvTakeUpgrade() {
        val u = tvUp ?: return
        tvUp = null; tvZone = "bar"
        PlayTiming.late("TV: upgrade taken (${VisualTags.quality(u)}${if (tvUpFallback) ", backup" else ""})")
        switchTo(u, "${tvQualityOf(u)} · same moment")
    }
    /** TV bottom row: Watch Next · Next · Episodes (series) · More. */
    // Movies: only Watch Next and ❯ More (Next title lives inside More). Series: Watch Next · Next episode · Episodes · More.
    fun tvBotKeys(): List<String> = buildList { add("wn"); if (type == "series") add("next"); if (type == "series" && episodes.isNotEmpty()) add("eps"); add("more") }
    /** TV Next: the next episode, or (movies) the first title like this one. */
    fun tvNext() {
        if (type == "series") { if (nextEp != null) playNext() else Snack.show("This is the last episode for now"); return }
        scope.launch {
            val imdb = p.key.substringBefore(":")
            val r = runCatching { vm.tmdbInfo(imdb, type)?.let { vm.likedCandidates(imdb, it).firstOrNull() } }.getOrNull()
            val id = r?.let { runCatching { vm.tmdbToImdb(it) }.getOrNull() }
            val m = if (r != null && !id.isNullOrBlank()) Meta(id = id, type = if (r.kind == "tv") "series" else "movie", name = r.title, poster = r.poster)
                else vm.shelves.value.flatMap { it.items }.firstOrNull { it.id != imdb && it.type == "movie" }
            if (m == null) { Snack.show("Nothing to play next yet"); return@launch }
            Snack.show("Next: ${m.name}")
            tvPlayTitle?.invoke(m)
        }
    }
    /**
     * TV remote, as Rick designed it. Watching: ◀ ▶ = −30/+30 s (no controls), ▲ = timeline, ▼ = Watch Next, OK = pause.
     * Timeline: ◀ ▶ = 10 s (with a preview), ▲ = Quality / Audio & Subtitles, ▼ = Watch Next / ❯ More.
     * Under the buttons: ▼ = everything away. Null = not handled here (media keys, panels move their own focus).
     */
    fun tvKey(e: KeyEvent): Boolean? {
        if (!firstFrame || locked || failure != null) return null
        if (panel != null || qualityMenu || tvQ || showEpisodes || (more && controls)) return false
        val k = e.key
        if (watchNext) { if (k == Key.DirectionUp) { tvClearAll(); return true }; return false }
        val ok = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
        if (k != Key.DirectionLeft && k != Key.DirectionRight && k != Key.DirectionUp && k != Key.DirectionDown && !ok) return null
        if (showInfo) showInfo = false
        poke++
        // Skip Intro / Upgrade quality / Next episode: ▲ ▼ move between them, OK uses one.
        if (tvPillMode) {
            val pills = tvPills()
            if (pills.isEmpty()) { tvPillMode = false } else {
                tvPillIdx = tvPillIdx.coerceIn(0, pills.lastIndex)
                when {
                    k == Key.DirectionUp -> if (tvPillIdx > 0) tvPillIdx-- else { tvPillMode = false; controls = true; tvZone = "bar" }
                    k == Key.DirectionDown -> if (tvPillIdx < pills.lastIndex) tvPillIdx++ else tvPillMode = false
                    ok -> { swallowOkUp = true; tvPillMode = false; when (pills[tvPillIdx]) { "skip" -> tvSkipAction?.invoke(); "upg" -> tvTakeUpgrade(); "next" -> playNext() } }
                    else -> { tvPillMode = false; bubbleSeek(k == Key.DirectionRight) }
                }
                return true
            }
        }
        if (!controls) {
            when {
                k == Key.DirectionLeft || k == Key.DirectionRight -> bubbleSeek(k == Key.DirectionRight)
                k == Key.DirectionUp -> { val n = tvPills().size; if (n > 0) { tvPillMode = true; tvPillIdx = n - 1 } else { controls = true; tvZone = "bar" } }
                k == Key.DirectionDown -> watchNext = true
                ok -> { swallowOkUp = true; val was = isPlayingNow(); togglePlay(); if (was) { controls = true; tvZone = "bar" } }
            }
            return true
        }
        when (tvZone) {
            "bar" -> when {
                k == Key.DirectionLeft || k == Key.DirectionRight -> nudgeSeek(k == Key.DirectionRight, 0)
                k == Key.DirectionUp -> tvZone = "top"
                k == Key.DirectionDown -> { tvZone = "bot"; tvBot = 0 }
                ok -> { swallowOkUp = true; if (pendingSeek != null) commitSeek() else togglePlay() }
            }
            "top" -> when {
                k == Key.DirectionLeft -> tvTop = 0
                k == Key.DirectionRight -> tvTop = 1
                k == Key.DirectionDown -> tvZone = "bar"
                ok -> { swallowOkUp = true; panelFromMore = false; if (tvTop == 0) tvQ = true else panel = "avsubs" }
            }
            else -> when {
                k == Key.DirectionLeft -> tvBot = (tvBot - 1).coerceAtLeast(0)
                k == Key.DirectionRight -> tvBot = (tvBot + 1).coerceAtMost(tvBotKeys().lastIndex)
                k == Key.DirectionUp -> tvZone = "bar"
                k == Key.DirectionDown -> tvClearAll()
                ok -> { swallowOkUp = true; when (tvBotKeys().getOrNull(tvBot)) {
                    "wn" -> { watchNext = true; controls = false }
                    "next" -> tvNext()
                    "eps" -> { showEpisodes = true; controls = false }
                    else -> more = true
                } }
            }
        }
        return true
    }
    /** Remote / keyboard keys. Arrows and OK act on the video only while nothing else (a button, a panel) has focus. */
    fun onKey(e: KeyEvent): Boolean {
        val k = e.key
        if (k == Key.Back) {
            if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) handleBack()
            return true
        }
        if (BuildConfig.IS_TV && e.type == KeyEventType.KeyUp && swallowOkUp &&
            (k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter)) { swallowOkUp = false; return true }
        if (e.type != KeyEventType.KeyDown) return false
        if (BuildConfig.IS_TV) tvKey(e)?.let { return it }
        val repeat = e.nativeKeyEvent.repeatCount
        val free = rootFocused && panel == null && !showEpisodes && !qualityMenu && !watchNext
        if (free && showInfo) showInfo = false
        if (firstFrame) poke++
        return when (k) {
            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                if (!firstFrame) return false
                if (pendingSeek != null) commitSeek()
                else {
                    val playingNow = isPlayingNow()
                    if (k == Key.MediaPlayPause || (k == Key.MediaPlay && !playingNow) || (k == Key.MediaPause && playingNow)) togglePlay()
                }
                if (!locked && panel == null && !showEpisodes) controls = true
                true
            }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                // On the seek bar: OK jumps to the chosen point (or plays / pauses).
                if (seekFocused && controls && firstFrame) {
                    if (pendingSeek != null) commitSeek() else { togglePlay(); pausedByUp = false }
                    return true
                }
                if (!free || !firstFrame) return false
                if (locked) controls = true
                else if (pendingSeek != null) commitSeek()
                else { togglePlay(); controls = true }
                true
            }
            Key.DirectionLeft, Key.DirectionRight, Key.MediaRewind, Key.MediaFastForward -> {
                val media = k == Key.MediaRewind || k == Key.MediaFastForward
                val fwd = k == Key.DirectionRight || k == Key.MediaFastForward
                when {
                    // Panels (Audio & Subtitles columns, Sources tabs): the remote moves between them.
                    panel != null || watchNext -> false
                    !firstFrame -> false
                    media && (showEpisodes || qualityMenu) -> false
                    locked -> { controls = true; true }
                    // On the seek bar: move a target 10 s at a time, with a preview frame.
                    seekFocused && controls -> { nudgeSeek(fwd, repeat); true }
                    // Phones: only a real remote / keyboard / game pad may jump (a stray key from earbuds, an accessibility
                    // service or the system made the 30 s bubble pop up on its own every few seconds).
                    !BuildConfig.IS_TV && !realKeys(e) -> true
                    // Controls hidden: 30 s jumps with a bubble.
                    free && !controls -> { bubbleSeek(fwd); true }
                    media -> { if (controls) nudgeSeek(fwd, repeat) else bubbleSeek(fwd); true }
                    // Controls shown, focus on a button: move between buttons.
                    !free -> false
                    else -> { nudgeSeek(fwd, repeat); true }
                }
            }
            Key.DirectionUp -> {
                if (!firstFrame) return false
                when {
                    free -> {
                        // ▲ : controls on, video paused, focus on the buttons.
                        controls = true
                        if (!locked) {
                            if (isPlayingNow()) { pauseNow(); pausedByUp = true }
                            focusRowTick++
                        }
                        true
                    }
                    // ▲ from the top bar: hide everything.
                    topHasFocus && controls && panel == null && !qualityMenu -> { hideControls(); true }
                    else -> false // buttons → seek bar → top bar
                }
            }
            Key.DirectionDown -> {
                if (!firstFrame) return false
                when {
                    free -> { controls = true; if (!locked) focusRowTick++; true }
                    // ▼ from the buttons: hide and play on.
                    rowHasFocus && controls && panel == null -> { hideControls(); true }
                    else -> false
                }
            }
            Key.Menu -> {
                if (!firstFrame || locked) return false
                panel = "avsubs"
                true
            }
            else -> false
        }
    }
    LaunchedEffect(playing) { if (playing) pausedByUp = false }
    LaunchedEffect(bubbleTick) { if (bubbleTick > 0) { delay(1000); bubble = null } }
    LaunchedEffect(seekKeyTick) {
        if (pendingSeek == null) return@LaunchedEffect
        delay(1600)
        commitSeek()
    }
    LaunchedEffect(Unit) { delay(80); runCatching { rootFocus.requestFocus() } }
    // ▲ : into the control row (once it is on screen).
    LaunchedEffect(focusRowTick) {
        if (focusRowTick == 0) return@LaunchedEffect
        for (i in 0 until 12) { delay(50); runCatching { rowFocus.requestFocus() }; if (!rootFocused) break }
    }
    // Controls gone (or a pop-up closed): the keys go back to the video.
    LaunchedEffect(controls, panel, showEpisodes, qualityMenu, watchNext) {
        if (!controls && panel == null && !showEpisodes && !qualityMenu && !watchNext && !rootFocused) { delay(30); runCatching { rootFocus.requestFocus() } }
    }
    // A side panel opened: focus its first item (TV: the controls step aside).
    LaunchedEffect(panel) {
        if (panel == null) return@LaunchedEffect
        if (BuildConfig.IS_TV) { controls = false; more = false }
        panelHasFocus = false
        for (i in 0 until 40) { delay(100); if (panelHasFocus) break; runCatching { panelFocus.requestFocus() } }
    }
    LaunchedEffect(qualityMenu) {
        if (!qualityMenu) return@LaunchedEffect
        menuHasFocus = false
        for (i in 0 until 10) { delay(80); if (menuHasFocus) break; runCatching { qualityFocus.requestFocus() } }
    }
    LaunchedEffect(failure) {
        if (failure == null) return@LaunchedEffect
        for (i in 0 until 10) { delay(120); runCatching { failFocus.requestFocus() }; if (!rootFocused && rootHasFocus) break }
    }

    // 60% into an episode: find the next one's link now (picked exactly like its own search would),
    // hand it over to the next episode and warm up its server, so it starts at once.
    var nextPrefetched by remember { mutableStateOf(false) }
    LaunchedEffect(dur > 60_000 && pos >= dur * 0.6) {
        val ep = nextEp
        if (nextPrefetched || ep == null || dur <= 60_000 || pos < dur * 0.6) return@LaunchedEffect
        nextPrefetched = true
        val cur = now
        scope.launch {
            val picked: StreamItem? = if (p.quick) {
                val list = runCatching { vm.quickStreams(type, ep.id) }.getOrDefault(emptyList())
                vm.pickQuick(list, quality) ?: vm.pickQuick(list, "auto")
            } else {
                val pool = runCatching { vm.allStreams(type, ep.id) }.getOrDefault(emptyList())
                val mode = opt("autoPlayMode").let { if (it == "manual") "first" else it }
                val patterns = regexList(opt("autoRegex"))
                val prefer = cur?.source
                val s0 = when {
                    pool.isEmpty() -> null
                    else -> prefer?.let { pr -> pool.firstOrNull { it.stream.name == pr && it.addonName == (cur?.addon ?: it.addonName) } ?: pool.firstOrNull { it.stream.name == pr } }
                        ?: (if (mode == "regex" && patterns.isNotEmpty()) matchChain(pool, patterns.take(1))?.first ?: vm.autoPick(pool, "regex", opt("autoRegex")) else null)
                        ?: cur?.addon?.let { a -> pool.firstOrNull { it.addonName == a } }
                        ?: vm.autoPick(pool, "first", "", prefer)
                }
                // Prefer your audio language among versions of the same file from the same addon (as the search does).
                s0?.let { a -> pool.filter { it.addonName == a.addonName && it.resolution() == a.resolution() }.minByOrNull { vm.langRank(it) }
                    ?.takeIf { vm.langRank(it) < vm.langRank(a) } ?: a }
            }
            val pk = picked ?: return@launch
            val url = pk.stream.url ?: return@launch
            if (!isActive) return@launch
            val source = pk.stream.name ?: pk.addonName
            vm.rememberLink(ep.id, url, source, pk.addonName)
            NextUp.set(ep.id, url, source, pk.addonName, pk.stream.subtitles.orEmpty())
            launch(kotlinx.coroutines.Dispatchers.IO) { warmUpLink(url) }
        }
    }
    // Elite, 75% in: the next episode's first minute is loaded in the background, so it starts instantly.
    var nextPreloaded by remember { mutableStateOf(false) }
    LaunchedEffect(dur > 60_000 && pos >= dur * 0.75) {
        val ep = nextEp ?: return@LaunchedEffect
        if (BuildConfig.IS_TV) return@LaunchedEffect // too heavy for 1 GB TV sticks
        if (nextPreloaded || dur <= 60_000 || pos < dur * 0.75 || usingVlc || engine != "exo" || !vm.eliteOn()) return@LaunchedEffect
        // The link from the 60% look-up (wait for it a little if it's still coming).
        var tries = 0
        while (!NextUp.fresh(ep.id) && tries < 20) { delay(1500); tries++ }
        if (!NextUp.fresh(ep.id) || Preloader.has(ep.id)) return@LaunchedEffect
        val url = NextUp.url ?: return@LaunchedEffect
        nextPreloaded = true
        val subs = NextUp.subs
        val fname = itemFor(url)?.stream?.behaviorHints?.filename
        val mime = if (StreamType.needsProbe(url, fname)) withContext(Dispatchers.IO) { StreamType.probe(url) }.takeIf { it.isNotEmpty() } else StreamType.mimeFor(url, fname)
        val next = buildPlayer(ctx, vm, isOn(opt("fastStart")))
        next.setMediaItem(MediaItem.Builder().setUri(url).apply { if (mime != null) setMimeType(mime) }.setSubtitleConfigurations(subtitleConfigs(subs)).build(), 0L)
        next.playWhenReady = false
        next.prepare()
        Preloader.set(Preloaded(ep.id, url, subs, NextUp.source, NextUp.addon, emptyList(), next))
    }
    // Last 5 minutes of an episode (quick play): the next episode's HD file is found — CNCVerse (Vegamovies) first, then
    // HdHub — and its start + index saved, so the next episode opens straight in HD. Elite on → only on Wi-Fi.
    var nextHdDone by remember { mutableStateOf(false) }
    LaunchedEffect(dur > 10 * 60_000L && pos >= dur - 5 * 60_000L) {
        val ep = nextEp ?: return@LaunchedEffect
        if (nextHdDone || dur <= 10 * 60_000L || pos < dur - 5 * 60_000L || BuildConfig.IS_TV || usingVlc || !p.quick ||
            !isOn(opt("qsUpgrade")) || !vm.upgradeAllowed()) return@LaunchedEffect
        nextHdDone = true
        scope.launch {
            val lang = now?.url?.let { itemFor(it) }?.let { vm.langRank(it) } ?: 0
            fun usable(l: List<StreamItem>) = vm.rankedCandidates(vm.filtered(l).list, "fhd", quickOnly = false)
                .filter { !it.isMovieBox() && !it.isPlaylist() && !it.slowMp4() && Ranker.tier(it.effectiveRes()) >= 3 &&
                    (vm.langRank(it) <= lang || "multi" in it.languages()) && !Ranker.isDead(it) && !Ranker.keepsFailing(it) && !it.unplayableHere() && !AutoPlayRules.blocked(it.stream.url) }
                .sortedWith(compareBy<StreamItem>({ if (it.isVegamovies()) 0 else 1 }, { AutoPlayRules.hostRank(it.stream.url) }, { PicturePref.rank(vm, it) }))
            PlayTiming.late("Next episode: looking for its HD file")
            var list = usable(askCncverse(vm, type, ep.id) { l -> l.any { it.isVegamovies() } })
            if (list.none { it.isVegamovies() }) {
                val hd = vm.addons.value.filter { it.label().contains("hdhub", true) || it.manifest.name.contains("hdhub", true) }.map { it.label() }.toSet()
                if (hd.isNotEmpty()) list = list + usable(withTimeoutOrNull(20_000) { vm.streamsFlow(type, ep.id, only = hd).first { it.second } }?.first.orEmpty())
            }
            for (c in list.distinctBy { it.stream.url }.take(4)) {
                if (!isActive) return@launch
                if (!saveHdStart(ctx, c)) continue
                val u = c.stream.url ?: continue
                StreamCache.register(c); StreamHeaders.remember(c)
                if (Preloader.has(ep.id)) Preloader.release(ep.id)
                NextUp.set(ep.id, u, c.stream.name ?: c.addonName, c.addonName, c.stream.subtitles.orEmpty())
                PlayTiming.late("Next episode: HD file ready (${Prepare.qualityName(c.effectiveRes())} · ${Ranker.labelOf(Ranker.groupKey(c))})")
                return@launch
            }
            PlayTiming.late("Next episode: no HD file — it starts from the quick server")
        }
    }
    val threshold = (opt("nextThreshold").toIntOrNull() ?: 95).coerceIn(50, 100) / 100f
    LaunchedEffect(pos, dur) {
        if (!autoNextDone && isOn(opt("autoNext")) && nextEp != null && dur > 60_000 && pos >= dur * threshold) {
            autoNextDone = true
            playNext()
        }
    }

    DisposableEffect(Unit) {
        val activity = ctx as Activity
        val ctrl = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            val d = curDur()
            now?.let { n -> if (firstFrame) onProgress(curPos(), if (d > 0) d else 0L, n.url, n.subs) }
            if (PlayTiming.active()) PlayTiming.finish("left the player before the first picture")
            player.release()
            vlc?.release()
            // Next episode prepared but not played (left the player): free it. (Taken already when it plays next.)
            nextEpState.value?.let { Preloader.release(it.id) }
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = -1f }
            // Give the phone its own volume back (only when we set a starting volume).
            if (volBefore >= 0) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, volBefore, 0) }
            ctrl.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BackHandler { handleBack() }

    val subSize = opt("subSize").toFloatOrNull() ?: 20f
    // Netflix style (default): clean medium-weight white text, a soft shadow, no box — for every subtitle, inside the
    // video or online.
    val netflixSubs = isOn(opt("subNetflix"))
    val subStyle = if (netflixSubs) CaptionStyleCompat(
        Color.White.toArgb(), Color.Transparent.toArgb(), Color.Transparent.toArgb(),
        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color(0xE6000000).toArgb(),
        Typeface.create("sans-serif-medium", if (isOn(opt("subBold"))) Typeface.BOLD else Typeface.NORMAL),
    ) else CaptionStyleCompat(
        (parseColor(opt("subColor")) ?: Color.White).toArgb(),
        (parseColor(opt("subBg")) ?: Color.Transparent).toArgb(),
        Color.Transparent.toArgb(),
        if (isOn(opt("subOutline"))) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
        (parseColor(opt("subOutlineColor")) ?: Color.Black).toArgb(),
        if (isOn(opt("subBold"))) Typeface.DEFAULT_BOLD else Typeface.DEFAULT,
    )

    // Loading screen fades out, then the video fades in from black.
    val loadingAlpha by animateFloatAsState(if ((firstFrame || switching) && failure == null) 0f else 1f, tween(420), label = "loading")
    val videoAlpha by animateFloatAsState(if (firstFrame) 1f else 0f, tween(650, delayMillis = 180), label = "video")

    ProvideTextStyle(TextStyle(fontFamily = InterFont, color = Color.White)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(Color.Black)
                .onPreviewKeyEvent { onKey(it) }
                .focusRequester(rootFocus)
                .onFocusChanged {
                    rootFocused = it.isFocused
                    val lost = rootHasFocus && !it.hasFocus
                    rootHasFocus = it.hasFocus
                    // The focused button went away (controls hid, a list changed): take the keys back.
                    if (lost) scope.launch { delay(60); if (!rootHasFocus) runCatching { rootFocus.requestFocus() } }
                }
                .focusable(),
        ) {
            // Measured from the reference player: wide side margins, scaled to this screen.
            val side = (maxWidth * 0.11f).coerceIn(28.dp, 110.dp)
            // The screen's size, for pop-ups placed inside inner layouts (where the outer scope isn't reachable).
            val screenWPx = constraints.maxWidth; val screenHPx = constraints.maxHeight
            val screenW = maxWidth; val screenH = maxHeight
            val topEnd = (maxWidth * 0.12f).coerceIn(28.dp, 120.dp)
            val topY = (maxHeight * 0.12f).coerceIn(16.dp, 64.dp)
            val bottomY = (maxHeight * 0.06f).coerceIn(12.dp, 36.dp)

            Box(Modifier.fillMaxSize()) {
                if (usingVlc) {
                    val v = vlc
                    if (v != null) AndroidView(
                        factory = { c -> org.videolan.libvlc.util.VLCVideoLayout(c).apply { keepScreenOn = true; videoView = this } },
                        update = { layout -> v.attach(layout); v.setAspect(aspectIdx) },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else AndroidView(
                    factory = { c -> PlayerView(c).apply { useController = false; this.player = if (shadow === player) null else player; keepScreenOn = true; if (shadow == null) videoView = this } },
                    update = { v -> v.resizeMode = if (BuildConfig.IS_TV) AspectRatioFrameLayout.RESIZE_MODE_FIT else resize; v.subtitleView?.visibility = View.GONE },
                    // TV: most videos are 16:9 like the TV, so Crop / Stretch changed nothing. On TV they zoom past the
                    // black bars instead: Crop = 1.33× (fills a wide film), Stretch = taller only.
                    modifier = Modifier.fillMaxSize().then(if (BuildConfig.IS_TV) Modifier.graphicsLayer {
                        when (aspectIdx) { 1 -> { scaleX = 1.33f; scaleY = 1.33f }; 2 -> { scaleX = 1f; scaleY = 1.33f }; else -> { scaleX = 1f; scaleY = 1f } }
                    } else Modifier.graphicsLayer { scaleX = cineScale; scaleY = cineScale }),
                )
                // Plugin mode's quiet swap: the second player's picture, above the main one (fades in, then stays).
                val sh = shadow
                if (sh != null && !usingVlc) key(sh) {
                    AndroidView(
                        factory = { c ->
                            val outer = android.widget.FrameLayout(c)
                            val arf = AspectRatioFrameLayout(c)
                            val tv = android.view.TextureView(c)
                            arf.addView(tv, android.widget.FrameLayout.LayoutParams(-1, -1))
                            outer.addView(arf, android.widget.FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER))
                            sh.setVideoTextureView(tv)
                            fun aspect(vs: androidx.media3.common.VideoSize) { if (vs.width > 0 && vs.height > 0) arf.setAspectRatio(vs.width * vs.pixelWidthHeightRatio / vs.height) }
                            aspect(sh.videoSize)
                            sh.addListener(object : Player.Listener { override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { aspect(videoSize) } })
                            outer.tag = arf
                            outer
                        },
                        update = { v -> (v.tag as? AspectRatioFrameLayout)?.resizeMode = resize; if (sh === player) videoView = v },
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = shadowAlpha; scaleX = cineScale; scaleY = cineScale },
                    )
                }
                // Subtitles live outside the video frame, so zoom / crop never moves or enlarges them.
                // Like Netflix: while the controls are on screen the subtitles glide up above the timeline and buttons,
                // and back down when they hide.
                val subLiftDp by animateFloatAsState(
                    if (controls && !locked && firstFrame && !inPip) (if (BuildConfig.IS_TV) 210f else 130f) else 0f,
                    tween(if (controls) 220 else 300), label = "subLift",
                )
                val screenHdp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.coerceAtLeast(200)
                // Follow the speaker (beta): the line slides under the person talking (left / right), else stays centred.
                val spkX by animateFloatAsState(speakerAt, tween(380), label = "speaker")
                if (!usingVlc) AndroidView(
                    factory = { c -> SubtitleView(c).apply { setBottomPaddingFraction(0.07f) } },
                    update = { sv ->
                        sv.setBottomPaddingFraction((0.07f + subLiftDp / screenHdp).coerceAtMost(0.6f))
                        sv.setApplyEmbeddedStyles(false); sv.setApplyEmbeddedFontSizes(false)
                        sv.setStyle(subStyle); sv.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subSize); sv.setCues(liftCues(cues, subLiftDp / screenHdp))
                    },
                    modifier = Modifier.fillMaxSize().graphicsLayer { translationX = (spkX - 0.5f) * size.width * 0.55f },
                )
                // The video surface can't fade itself, so a black layer on top fades away instead.
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - videoAlpha }.background(Color.Black))
                // Switching link mid-playback: the last frame stays, softly blurred, until the new one is on screen.
                androidx.compose.animation.AnimatedVisibility(switching && failure == null, Modifier.fillMaxSize(), enter = fadeIn(tween(120)), exit = fadeOut(tween(450))) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        frozen?.let { bmp ->
                            androidx.compose.foundation.Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize().blur(18.dp), contentScale = if (aspectIdx == 1) ContentScale.Crop else ContentScale.Fit)
                        }
                        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x33000000), Color(0x99000000)))))
                        SwitchCapsule(bufProgress, resumeAt, Modifier.align(Alignment.Center))
                    }
                }
            }

            // Gestures:
            //  tap = controls · double-tap left/right = ∓10 s · double-tap middle = play/pause
            //  swipe up/down left = brightness, right = volume · pinch = zoom to fill / original
            //  press & hold = 2x, keep holding and slide right/left = faster/slower (0.5x – 4x)
            Box(
                Modifier.fillMaxSize().pointerInput(locked, firstFrame) {
                    if (!firstFrame) return@pointerInput
                    if (locked) { detectTapGestures(onTap = { controls = !controls; poke++ }); return@pointerInput }
                    val slop = viewConfiguration.touchSlop
                    val longMs = viewConfiguration.longPressTimeoutMillis
                    val stepPx = 36.dp.toPx()
                    var lastTapAt = 0L
                    var lastTapX = 0f
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        val downAt = SystemClock.uptimeMillis()
                        var mode = 0 // 0 = undecided, 1 = brightness/volume, 2 = hold speed, 3 = pinch, 4 = ignore
                        val left = start.x < size.width / 2f
                        var level = 0f
                        var pinchStart = 0f
                        var pinchRatio = 1f
                        var baseSpeed = 1f
                        var lastStep = 0
                        var wnLastY = 0f; var wnLastT = 0L; var wnVel = 0f
                        while (true) {
                            val wait = if (mode == 0) (longMs - (SystemClock.uptimeMillis() - downAt)).coerceAtLeast(1L) else Long.MAX_VALUE
                            val ev = this.withTimeoutOrNull(wait) { awaitPointerEvent() }
                            if (ev == null) {
                                // Held still long enough: 2x while holding.
                                if (mode == 0) {
                                    mode = 2
                                    baseSpeed = rateNow()
                                    setRate(2f); holdSpeed = 2f
                                    controls = false
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                continue
                            }
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2 && (mode == 0 || mode == 3)) {
                                val d = (pressed[0].position - pressed[1].position).getDistance()
                                if (mode == 0) { mode = 3; pinchStart = d.coerceAtLeast(1f) } else pinchRatio = d / pinchStart
                                ev.changes.forEach { it.consume() }
                                continue
                            }
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: pressed.first()
                            val delta = c.position - start
                            when (mode) {
                                0 -> if (!BuildConfig.IS_TV && abs(delta.y) > slop && abs(delta.y) > abs(delta.x) && start.x in (size.width / 3f)..(size.width * 2f / 3f)) {
                                    // Xstream: the middle third — swiping up there pulls Watch Next up (like YouTube).
                                    mode = 5
                                    wnDrag = 0f; wnLastY = c.position.y; wnLastT = SystemClock.uptimeMillis(); wnVel = 0f
                                } else if (abs(delta.y) > slop && abs(delta.y) > abs(delta.x)) {
                                    mode = 1
                                    val act = ctx as Activity
                                    level = if (left) act.window.attributes.screenBrightness.let { if (it < 0f) 0.5f else it }
                                    else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
                                } else if (abs(delta.x) > slop * 2) mode = 4
                                1 -> {
                                    val dy = c.position.y - c.previousPosition.y
                                    level = (level - dy / (size.height * 0.8f)).coerceIn(0f, 1f)
                                    if (left) {
                                        val act = ctx as Activity
                                        act.window.attributes = act.window.attributes.apply { screenBrightness = level.coerceAtLeast(0.01f) }
                                        hud = "brightness" to level
                                    } else {
                                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * maxVol).toInt(), 0)
                                        hud = "volume" to level
                                    }
                                    c.consume()
                                }
                                5 -> {
                                    // The sheet tracks the finger: a full pull is just over half the screen.
                                    wnDrag = ((start.y - c.position.y) / (size.height * 0.55f)).coerceIn(0f, 1f)
                                    val tNow = SystemClock.uptimeMillis()
                                    if (tNow > wnLastT) { wnVel = (wnLastY - c.position.y) / (tNow - wnLastT); wnLastY = c.position.y; wnLastT = tNow }
                                    c.consume()
                                }
                                2 -> {
                                    val step = (delta.x / stepPx).toInt()
                                    if (step != lastStep) {
                                        lastStep = step
                                        val sp = (2f + 0.25f * step).coerceIn(0.5f, 4f)
                                        if (sp != holdSpeed) {
                                            setRate(sp); holdSpeed = sp
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                    c.consume()
                                }
                            }
                        }
                        if (mode == 5) {
                            // Let go past a third of the way (or with a quick flick up) and it opens; otherwise it drops back.
                            val pNow = wnDrag.coerceAtLeast(0f)
                            val open = pNow > 0.3f || (wnVel > 0.6f && pNow > 0.05f)
                            scope.launch {
                                wnAnim.snapTo(pNow); wnDrag = -1f
                                if (open) { if (!watchNext) { watchNext = true; controls = false } else wnAnim.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 420f)) }
                                else wnAnim.animateTo(0f, tween(200))
                            }
                        }
                        when (mode) {
                            0 -> {
                                // A tap. Two quick taps = double-tap.
                                val t = SystemClock.uptimeMillis()
                                if (t - lastTapAt < 300 && abs(start.x - lastTapX) < 120.dp.toPx()) {
                                    tapJob?.cancel(); lastTapAt = 0L
                                    val third = size.width / 3f
                                    when {
                                        // Like the TV: controls hidden = 30 s jumps with the bubble; controls shown = 10 s.
                                        // Phones: 10 s back / forward, controls shown or hidden (no 30 s bubble).
                                        start.x < third -> { seekBy(-10_000); seekHint = "-10s" }
                                        start.x > third * 2 -> { seekBy(10_000); seekHint = "+10s" }
                                        else -> { val was = isPlayingNow(); togglePlay(); seekHint = if (was) "❚❚" else "▶" }
                                    }
                                } else {
                                    lastTapAt = t; lastTapX = start.x
                                    tapJob?.cancel()
                                    tapJob = scope.launch {
                                        delay(260)
                                        if (showInfo) showInfo = false else { controls = !controls; poke++ }
                                    }
                                }
                            }
                            1 -> {
                                // Remember the brightness you swiped to (used by "Remember my last").
                                if (left) {
                                    val b = (ctx as Activity).window.attributes.screenBrightness
                                    if (b > 0f) vm.setPlayerPref("bright", b.toString())
                                } else {
                                    // And the volume (0..1), for the volume's "Remember my last".
                                    runCatching { vm.setPlayerPref("vol", (audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol).toString()) }
                                }
                                hud = null
                            }
                            2 -> { setRate(baseSpeed); holdSpeed = null }
                            3 -> when {
                                pinchRatio > 1.12f && aspectIdx != 1 -> setAspect(1, "Zoomed to fill")
                                pinchRatio > 1.12f && aspectIdx == 1 && !cinematic -> { cinematic = true; aspectLabel = "Cinematic zoom" }
                                pinchRatio < 0.9f && cinematic -> { cinematic = false; aspectLabel = "Zoomed to fill" }
                                pinchRatio < 0.9f && aspectIdx != 0 -> setAspect(0, "Original")
                            }
                        }
                    }
                },
            )

            // Start of the video: age guide (IMDb parents guide) and the real picture size, top left, for a few seconds.
            if (showOpening && !inPip && !BuildConfig.IS_TV) OpeningOverlay(guide, realVideoLine(tracks),
                Modifier.align(Alignment.TopStart).padding(start = side, top = topY + 58.dp))

            // Watch together: reactions from everyone float up the right side.
            val roomCode by Together.code.collectAsState()
            if (roomCode != null) ReactionsOverlay(Modifier.fillMaxSize())
            if (roomCode != null && !inPip) ChatToasts(Modifier.align(Alignment.CenterEnd).padding(end = side, top = 40.dp))
            // Friends' camera / voice bubbles (tap one = Watch together), and "Waiting for …" while someone buffers.
            if (roomCode != null && !inPip) CallBubbles(Modifier.fillMaxSize()) { panel = "together"; controls = true; poke++ }
            if (roomCode != null && waitingFor != null) Text("Waiting for $waitingFor…", Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
                .clip(RoundedCornerShape(22.dp)).background(Color(0xB3000000)).padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

            // Hold-for-speed pill.
            AnimatedVisibility(holdSpeed != null, Modifier.align(Alignment.TopCenter).padding(top = 28.dp), enter = fadeIn(tween(150)), exit = fadeOut(tween(200))) {
                Row(
                    Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xB3000000)).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${(holdSpeed ?: 2f).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }}x", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.FastForward, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Slide ◀ ▶ to change", fontSize = 11.sp, color = Color(0xFFB6BDCC))
                }
            }

            LaunchedEffect(seekHint) { if (seekHint != null) { delay(700); seekHint = null } }
            seekHint?.let {
                Text(it, Modifier.align(when { it.startsWith("-") -> Alignment.CenterStart; it.startsWith("+") -> Alignment.CenterEnd; else -> Alignment.Center }).padding(horizontal = side)
                    .clip(RoundedCornerShape(30.dp)).background(Color(0x88000000)).padding(horizontal = 18.dp, vertical = 10.dp),
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            // Mid-playback buffering: a thin spinner only.
            if (firstFrame && buffering && failure == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp), color = Color.White, strokeWidth = 3.dp)
            hud?.let { (kind, lvl) ->
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 32.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xB3000000))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (kind == "brightness") Icons.Default.LightMode else if (lvl == 0f) Icons.Default.VolumeOff else Icons.Default.VolumeUp, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.width(140.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(lvl).background(Color.White))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("${(lvl * 100).toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            LaunchedEffect(aspectLabel) { if (aspectLabel != null) { delay(1500); aspectLabel = null } }
            aspectLabel?.let {
                Text(it, Modifier.align(Alignment.Center).padding(top = 110.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xB3000000))
                    .padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }

            // Content advisory, top-left: white bar + "Category · Level" lines, 12sp.
            AnimatedVisibility(showAdvisory && advisory.isNotEmpty() && !controls, Modifier.align(Alignment.TopStart).padding(start = side, top = 20.dp),
                enter = fadeIn(tween(500)), exit = fadeOut(tween(500))) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(Color.White))
                    Column(Modifier.padding(start = 12.dp)) {
                        advisory.forEach { (cat, level) ->
                            Row(Modifier.padding(vertical = 4.dp)) {
                                Text(cat, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("  ·  $level", fontSize = 12.sp, color = Color(0xFFB6BDCC))
                            }
                        }
                    }
                }
            }

            // Skip intro / recap, and Next episode near the end.
            val s = segs
            val inIntro = s?.intro?.let { pos >= it.start_ms && pos < it.end_ms - 1500 } == true
            val inRecap = s?.recap?.let { pos >= it.start_ms && pos < it.end_ms - 1500 } == true
            val nearEnd = nextEp != null && dur > 0 && (s?.outro?.let { pos >= it.start_ms } ?: (pos >= dur - 120_000))
            // Auto-skip (Settings › Skip Intro): jumps over an intro / recap by itself, once each, with a short note.
            val autoSkip = isOn(opt("autoSkipIntro"))
            LaunchedEffect(inIntro, inRecap, autoSkip) {
                if (!autoSkip || !(inIntro || inRecap) || s == null) return@LaunchedEffect
                val seg = if (inIntro) s.intro else s.recap
                val tag = (if (inIntro) "i" else "r") + (seg?.start_ms ?: 0)
                if (seg == null || tag in autoSkipped) return@LaunchedEffect
                autoSkipped += tag
                delay(600)
                seekAbs(seg.end_ms)
                Snack.show(if (inIntro) "Intro skipped" else "Recap skipped")
            }
            // TV: the same pills, drawn for the remote (▲ reaches them; the focused one grows and gets the white edge).
            if (BuildConfig.IS_TV) {
                val skipOn = firstFrame && !inPip && (inIntro || inRecap)
                val nextOn = firstFrame && !inPip && nearEnd && !locked
                SideEffect {
                    tvSkipOn = skipOn; tvNextOn = nextOn
                    tvSkipAction = { s?.let { seg -> (if (inIntro) seg.intro else seg.recap)?.let { seekAbs(it.end_ms); poke++ } } }
                }
                var upTick by remember { mutableIntStateOf(0) }
                LaunchedEffect(tvUp, tvUpAt) { while (tvUp != null) { delay(1000); upTick++ } }
                val upLeftTv = if (tvUp == null) 0 else (30 - ((SystemClock.elapsedRealtime() - tvUpAt) / 1000).toInt()).coerceIn(0, 30) + upTick * 0
                val pills = buildList { if (skipOn) add("skip"); if (tvUp != null && tvUpVisible() && panel == null && !tvQ) add("upg"); if (nextOn) add("next") }
                val focusedPill = if (tvPillMode) pills.getOrNull(tvPillIdx) else null
                if (pills.isNotEmpty()) Column(Modifier.align(Alignment.BottomEnd).padding(end = side, bottom = if (controls) 160.dp else 32.dp),
                    horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    pills.forEach { k ->
                        TvPillFrame(focusedPill == k, top = if (k == "upg") 0.dp else 10.dp, bottom = if (k == "upg") 10.dp else 0.dp) {
                            when (k) {
                                "skip" -> PillButton(if (inIntro) "Skip Intro" else "Skip Recap", Icons.Default.FastForward) {}
                                "upg" -> { val u = tvUp; if (u != null && upLeftTv > 0) UpgradePill(tvQualityOf(u) == "4K", upLeftTv) {} }
                                else -> { val windowStart = s?.outro?.start_ms ?: (dur - 120_000)
                                    NextEpisodePill(left = (dur - pos).coerceAtLeast(0L), window = (dur - windowStart).coerceAtLeast(1L)) {} }
                            }
                        }
                    }
                }
            }
            if (!BuildConfig.IS_TV && firstFrame && !inPip) Column(Modifier.align(Alignment.BottomEnd).padding(end = side, bottom = if (controls) 160.dp else 32.dp), horizontalAlignment = Alignment.End) {
                AnimatedVisibility(inIntro || inRecap,
                    enter = fadeIn(tween(220)) + slideInHorizontally(tween(320)) { it / 3 },
                    exit = fadeOut(tween(180))) {
                    PillButton(if (inIntro) "Skip Intro" else "Skip Recap", Icons.Default.FastForward) {
                        seekAbs(if (inIntro) s!!.intro!!.end_ms else s!!.recap!!.end_ms); poke++
                    }
                }
                // Quick start + upgrade: the better file is saved ahead — the ring counts down the seconds still saved.
                AnimatedVisibility(upgrade != null && upLeft > 0 && !locked && !switching,
                    enter = fadeIn(tween(260)) + slideInHorizontally(tween(360)) { it / 3 },
                    exit = fadeOut(tween(200))) {
                    val u = upgrade
                    // Slide it right or up (anywhere) to send it away.
                    var dx by remember { mutableFloatStateOf(0f) }
                    var dy by remember { mutableFloatStateOf(0f) }
                    if (u != null) Box(Modifier
                        .offset { androidx.compose.ui.unit.IntOffset(dx.toInt(), dy.toInt()) }
                        .graphicsLayer { alpha = (1f - (dx - dy) / 500f).coerceIn(0f, 1f) }
                        .pointerInput(u) {
                            detectDragGestures(
                                onDragEnd = {
                                    if (dx > 110f || dy < -110f) { upgrade = null; PlayTiming.late("Upgrade offer swiped away") }
                                    dx = 0f; dy = 0f
                                },
                                onDragCancel = { dx = 0f; dy = 0f },
                            ) { ch, d -> ch.consume(); dx = (dx + d.x).coerceAtLeast(0f); dy = (dy + d.y).coerceAtMost(0f) }
                        }) {
                        UpgradePill(u.is4k, upLeft) {
                            upgradedFrom = now?.url?.let { itemFor(it) }; upgradedUrl = u.item.stream.url
                            upgrade = null; poke++; switchTo(u.item, if (u.is4k) "4K" else "Better quality")
                        }
                    }
                }
                AnimatedVisibility(nearEnd && !locked,
                    enter = fadeIn(tween(260)) + slideInHorizontally(tween(360)) { it / 3 },
                    exit = fadeOut(tween(200))) {
                    val windowStart = s?.outro?.start_ms ?: (dur - 120_000)
                    NextEpisodePill(left = (dur - pos).coerceAtLeast(0L), window = (dur - windowStart).coerceAtLeast(1L)) { playNext() }
                }
            }

            // ───── Controls (fade in / out), Hotstar layout ─────
            //  top-left: title · top-right: lock, quality, back (+ clock)
            //  bottom: seek bar, then Next Episode · Audio & Subtitles · Episodes · Watch Next · More ▸
            AnimatedVisibility(!BuildConfig.IS_TV && controls && !locked && !showInfo && firstFrame && !inPip, Modifier.fillMaxSize(), enter = fadeIn(tween(220)), exit = fadeOut(tween(260))) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xB3000000), 0.25f to Color(0x26000000), 0.6f to Color(0x33000000), 1f to Color(0xE6000000)))
) {
                    // Only the buttons watch for a finger (the empty video area keeps its tap-to-hide and double-taps).
                    val touch: (Boolean) -> Unit = { d -> touchingControls = d; if (!d) poke++ }
                    // Phones: one play / pause button in the middle.
                    // Xstream: play / pause lives at the bottom right, next to ›. The top-right buttons' width keeps the title clear of them.
                    var topRightW by remember { mutableIntStateOf(0) }
                    val topRightDp = with(LocalDensity.current) { topRightW.toDp() }
                    // Title, top-left.
                    val showName = meta?.name?.takeIf { it.isNotBlank() }
                    val epNow = episodes.firstOrNull { it.id == p.key }
                    Column(Modifier.align(Alignment.TopStart).padding(start = side, top = topY - 4.dp, end = side + topRightDp + 14.dp).fillMaxWidth()) {
                        Text(if (type == "series") showName ?: p.title.substringBefore("  S") else showName ?: p.title,
                            fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 26.sp)
                        if (type == "series" && epNow != null) Text(
                            "S${epNow.season} E${epNow.episode}" + (epNow.title ?: epNow.name)?.let { " · $it" }.orEmpty(),
                            Modifier.padding(top = 2.dp), fontSize = 14.sp, color = Color(0xFFD0D4DD), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Top-right: clock, lock (phone), quality, back.
                    Row(
                        Modifier.align(Alignment.TopEnd).padding(top = topY - 8.dp, end = side - 8.dp).onSizeChanged { topRightW = it.width }.onFocusChanged { topHasFocus = it.hasFocus }.watchTouch(touch),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(clockNow(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE6E8EE))
                        if (!BuildConfig.IS_TV) Box {
                            GlassBtn(Icons.Default.Groups, null, "Watch together", at("together")) { panel = "together" }
                            // In a room: a small green dot.
                            val unread by Together.unread.collectAsState()
                            if (roomNow != null && unread > 0) Text(if (unread > 9) "9+" else "$unread", Modifier.align(Alignment.TopEnd).clip(CircleShape).background(Color(0xFFE50914))
                                .border(1.5.dp, Color.Black, CircleShape).padding(horizontal = 5.dp, vertical = 1.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            else if (roomNow != null) Box(Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 5.dp).size(9.dp).clip(CircleShape).background(Color(0xFF3CCF7A)).border(2.dp, Color.Black, CircleShape))
                        }
                        if (!BuildConfig.IS_TV) GlassBtn(Icons.Default.LockOpen, null, "Lock") { locked = true; controls = true; poke++ }
                        val plugQ = !BuildConfig.IS_TV && vm.pluginMode()
                        if (quickList.isNotEmpty() || plugQ) GlassBtn(if (qualityMenu) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            run {
                                // Show what is really playing (a 1080p file is never labelled 4K).
                                // The real picture height of the playing file when known (wide films: by width), else its name.
                                val vf = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
                                val res = vf?.takeIf { it.width > 0 }?.let { f -> when { f.width >= 3200 -> 2160; f.width >= 1800 -> 1080; f.width >= 1200 -> 720; else -> f.height } }
                                    ?: now?.url?.let { itemFor(it) }?.effectiveRes() ?: 0
                                val real = when { res >= 1440 -> "4K"; res >= 1080 -> "Full HD"; res >= 720 -> "HD 720p"; res > 0 -> "Low"; else -> null }
                                val quickNow = now?.url?.let { itemFor(it) }?.let { it.isMovieBox() || vm.isQuick(it) } == true
                                // Plugin mode: what plays, plus "4K ready" once a 4K file waits in the menu (the pop-up shows once only).
                                val ready4k = hdReady["4k"].orEmpty().isNotEmpty() && res < 1440
                                if (plugQ) (real ?: "Auto") + (if (ready4k) " · 4K READY" else "")
                                else if (quality == "auto" || quickNow) "Quick" + (real?.let { " · $it" } ?: "") else real ?: (AllQualityLevels.firstOrNull { it.id == quality }?.label ?: "Quick")
                            }, "Quality", iconFirst = false) { qualityMenu = !qualityMenu; poke++ }
                        // Xstream: one stream that carries several qualities (an HLS playlist, like CloudStream's Netflix
                        // server): pick one of them here, or Auto.
                        if (!BuildConfig.IS_TV && quickList.isEmpty() && !vm.pluginMode()) {
                            val vg = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 1 }
                            val heights = vg?.let { g -> (0 until g.length).map { g.getTrackFormat(it).height }.filter { it > 0 }.distinct().sortedDescending() }.orEmpty()
                            if (vg != null && heights.size > 1) Box {
                                var open by remember { mutableStateOf(false) }
                                val curH = (0 until vg.length).firstOrNull { vg.isTrackSelected(it) }?.let { vg.getTrackFormat(it).height }
                                val pinned = player.trackSelectionParameters.overrides.keys.any { it == vg.mediaTrackGroup }
                                GlassBtn(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    (if (pinned) "" else "Auto · ") + (curH?.let { "${it}p" } ?: "Quality"), "Quality", iconFirst = false) { open = !open; poke++ }
                                androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = Color(0xF21A1A20)) {
                                    androidx.compose.material3.DropdownMenuItem({ Text("Auto" + if (!pinned) "  ✓" else "") }, {
                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build(); open = false
                                    })
                                    heights.forEach { h ->
                                        androidx.compose.material3.DropdownMenuItem({ Text("${h}p" + if (pinned && curH == h) "  ✓" else "") }, {
                                            val idx = (0 until vg.length).filter { vg.getTrackFormat(it).height == h }
                                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(vg.mediaTrackGroup, idx)).build()
                                            open = false
                                        })
                                    }
                                }
                            }
                        }
                        GlassBtn(Icons.Default.Close, null, "Back") { onBack() }
                    }
                    // Phones: play / pause is the big centre button. TV: it stays the first button of the bottom row (remote focus).
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = side, end = side, bottom = bottomY).watchTouch(touch)) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${fmtTime(pendingSeek ?: pos)} / ${fmtTime(dur)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            if (dur > 0) Text("Ends at ${endsAt(dur - pos, rateNow())}", fontSize = 13.sp, color = Color(0xFFB6BDCC))
                        }
                        Box(
                            Modifier.fillMaxWidth()
                                .then(if (BuildConfig.IS_TV) Modifier.focusRequester(seekFocus).onFocusChanged { seekFocused = it.isFocused }.focusable() else Modifier),
                        ) {
                            SeekBar(pos, buffered, dur, thumbs, pendingSeek, focused = seekFocused,
                                onScrub = { scrubbing = it; if (it) { controls = true; thumbs?.warmUp() }; poke++ }) { pendingSeek = null; seekAbs(it); poke++ }
                        }
                        Spacer(Modifier.height(10.dp))
                        val railScroll = androidx.compose.foundation.rememberScrollState()
                        // Phones: › sits on its own just under the right end of the progress bar (› closed, ⌃ open).
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.then(if (BuildConfig.IS_TV) Modifier else Modifier.weight(1f)).onFocusChanged { rowHasFocus = it.hasFocus }
                                // Buttons slide away under a rounded, invisible edge (same shape as the buttons) and fade out
                                // there, instead of being cut by a straight line.
                                .clip(RoundedCornerShape(percent = 50))
                                .railFade(railScroll)
                                .horizontalScroll(railScroll)
                                .padding(horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            var firstSet = false
                            fun firstMod(): Modifier = if (!firstSet) { firstSet = true; Modifier.focusRequester(rowFocus) } else Modifier
                            if (BuildConfig.IS_TV) GlassBtn(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, if (playing) "Pause" else "Play", firstMod()) {
                                togglePlay(); pausedByUp = false; poke++
                            }
                            if (BuildConfig.IS_TV) {
                            // Order: Watch Next · Audio & Subtitles · Episodes · Next Episode · ›
                            GlassBtn(Icons.Default.Theaters, "Watch Next", "Watch next", firstMod().then(at("watchnext"))) { watchNext = true; controls = false }
                            GlassBtn(Icons.Default.Subtitles, "Audio & Subtitles", "Audio and subtitles",
                                firstMod().then(Modifier.onGloballyPositioned { c -> avBtnCenter = c.boundsInRoot().center; btnCenters["avsubs"] = c.boundsInRoot().center })) { panel = "avsubs" }
                            if (type == "series" && episodes.isNotEmpty()) GlassBtn(Icons.Default.VideoLibrary, "Episodes", "Episodes", firstMod().then(at("episodes"))) { showEpisodes = true; controls = false }
                            if (nextEp != null) GlassBtn(Icons.Default.SkipNext, "Next Episode", "Next episode", firstMod()) { playNext() }
                            } else {
                                // Xstream phone: Audio & Subtitles, then Watch Next (movies) or Episodes (series) in the same spot.
                                GlassBtn(Icons.Default.Subtitles, "Audio & Subtitles", "Audio and subtitles",
                                    Modifier.onGloballyPositioned { c -> avBtnCenter = c.boundsInRoot().center; btnCenters["avsubs"] = c.boundsInRoot().center }) { panel = "avsubs" }
                                if (type == "series" && episodes.isNotEmpty()) GlassBtn(Icons.Default.VideoLibrary, "Episodes", "Episodes", at("episodes")) { showEpisodes = true; controls = false }
                                else GlassBtn(Icons.Default.Theaters, "Watch Next", "Watch next", at("watchnext")) { watchNext = true; controls = false }
                            }
                            // TV: the extra buttons in the row; phones: › opens them as a small bubble above the button.
                            if (more && BuildConfig.IS_TV) {
                                GlassBtn(Icons.Default.CloudQueue, "Sources", "Sources") { panel = "sources" }
                                GlassBtn(Icons.Default.AspectRatio, AspectModes[aspectIdx].first, "Aspect ratio") { setAspect((aspectIdx + 1) % AspectModes.size); poke++ }
                                GlassBtn(Icons.Default.Speed, "${speed.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }}x", "Speed") { panel = "speed" }
                                GlassBtn(Icons.Default.Bedtime, if (sleepAt != null || sleepEnd) "Sleep on" else "Sleep", "Sleep timer") { panel = "sleep" }
                                GlassBtn(Icons.Outlined.Info, null, "Info") { infoManual = true; showInfo = true; controls = false }
                            }
                            if (BuildConfig.IS_TV) GlassBtn(if (more) Icons.Default.ChevronLeft else Icons.Default.ChevronRight, null, if (more) "Less" else "More", at("more")) { more = !more; poke++ }
                        }
                        if (!BuildConfig.IS_TV) {
                            // Series: next episode (icon only) before play / pause.
                            if (type == "series" && nextEp != null) { Spacer(Modifier.width(10.dp)); GlassBtn(Icons.Default.SkipNext, null, "Next episode") { playNext() } }
                            Spacer(Modifier.width(10.dp))
                            GlassBtn(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, if (playing) "Pause" else "Play") { togglePlay(); pausedByUp = false; poke++ }
                            Spacer(Modifier.width(10.dp))
                            GlassBtn(if (more) Icons.Default.KeyboardArrowUp else Icons.Default.ChevronRight, null, if (more) "Less" else "More", at("more")) { more = !more; poke++ }
                        }
                        }
                    }
                    // › More (phones): a small glass bubble that grows out of the › button with the extra options.
                    if (!BuildConfig.IS_TV) {
                        if (more) Box(Modifier.fillMaxSize().clickable(interactionSource = noRipple, indication = null) { more = false })
                        val mc = btnCenters["more"]
                        val wPx0 = screenWPx.toFloat().coerceAtLeast(1f); val hPx0 = screenHPx.toFloat().coerceAtLeast(1f)
                        val mo = androidx.compose.ui.graphics.TransformOrigin(((mc?.x ?: wPx0 * 0.8f) / wPx0).coerceIn(0f, 1f), ((mc?.y ?: hPx0 * 0.9f) / hPx0).coerceIn(0f, 1f))
                        AnimatedVisibility(more, Modifier.fillMaxSize(),
                            enter = scaleIn(spring(dampingRatio = 0.78f, stiffness = 420f), initialScale = 0.08f, transformOrigin = mo) + fadeIn(tween(140)),
                            exit = scaleOut(tween(200), targetScale = 0.08f, transformOrigin = mo) + fadeOut(tween(180))) {
                            val shape = RoundedCornerShape(22.dp)
                            fun pick(block: () -> Unit) { more = false; poke++; block() }
                            Box(Modifier.fillMaxSize().layout { m, c ->
                                val pl = m.measure(c.copy(minWidth = 0, minHeight = 0))
                                layout(c.maxWidth, c.maxHeight) {
                                    val cx = mc?.x ?: (c.maxWidth * 0.8f); val cy = mc?.y ?: (c.maxHeight * 0.9f)
                                    val x = (cx - pl.width + 60f).toInt().coerceIn(16, (c.maxWidth - pl.width - 16).coerceAtLeast(16))
                                    val y = (cy - pl.height - 40f).toInt().coerceAtLeast(16)
                                    pl.place(x, y)
                                }
                            }.widthIn(max = 340.dp).shadow(26.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                                .clip(shape).background(Color(0xF2111216)).border(1.dp, Color(0x59E50914), shape)
                                .clickable(interactionSource = noRipple, indication = null) {}.padding(14.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("More", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0x9E14141A)).border(1.dp, Color(0x8CE50914), CircleShape)
                                            .clickable { more = false }, Alignment.Center) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", Modifier.size(17.dp)) }
                                    }
                                    val items = buildList<Triple<ImageVector, String, () -> Unit>> {
                                        add(Triple(Icons.Default.CloudQueue, "Sources") { pick { panel = "sources" } })
                                        add(Triple(Icons.Default.Speed, "Speed · ${speed.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }}x") { pick { panel = "speed" } })
                                        add(Triple(Icons.Default.Bedtime, if (sleepAt != null || sleepEnd) "Sleep on" else "Sleep timer") { pick { panel = "sleep" } })
                                        add(Triple(Icons.Default.AspectRatio, AspectModes[aspectIdx].first) { poke++; setAspect((aspectIdx + 1) % AspectModes.size) })
                                        if (PipState.supported(ctx)) add(Triple(Icons.Default.PictureInPictureAlt, "Picture in picture") { pick { PipState.enter(ctx as Activity, player.videoSize) } })
                                        add(Triple(Icons.Default.OpenInNew, "Other player") { pick { pauseNow(); now?.let { openExternal(ctx, it.url, p.title) } } })
                                        add(Triple(Icons.Outlined.Info, "Info") { pick { infoManual = true; showInfo = true; controls = false } })
                                    }
                                    items.chunked(2).forEach { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            row.forEach { (ic, label, act) ->
                                                Row(Modifier.width(150.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x12FFFFFF)).clickable { act() }
                                                    .padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(ic, null, Modifier.size(19.dp), tint = Color(0xFFE6E8EE))
                                                    Spacer(Modifier.width(9.dp))
                                                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                    }
                                    // Dev sources: a small icon at the end of the bubble, developer accounts only.
                                    if (Public.dev) Row(Modifier.width(308.dp), horizontalArrangement = Arrangement.End) {
                                        Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0x2EE50914)).border(1.dp, Color(0x8CE50914), CircleShape)
                                            .clickable { pick { panel = "devsources" } }, Alignment.Center) { Icon(Icons.Default.Code, "Dev sources", Modifier.size(16.dp), tint = Color(0xFFFF9AA1)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ───── TV: remote-first controls (nothing here takes focus; the remote drives tvZone) ─────
            //  top-left title · top-right Quality / Audio & Subtitles · thin timeline · Watch Next  ❯ More
            if (BuildConfig.IS_TV) AnimatedVisibility(controls && !locked && !showInfo && firstFrame && !inPip, Modifier.fillMaxSize(), enter = fadeIn(tween(200)), exit = fadeOut(tween(220))) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xA6000000), 0.2f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color(0xD9000000)))) {
                    val showName = meta?.name?.takeIf { it.isNotBlank() }
                    val epNow = episodes.firstOrNull { it.id == p.key }
                    Column(Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 28.dp).fillMaxWidth(0.55f)) {
                        Text(if (type == "series") showName ?: p.title.substringBefore("  S") else showName ?: p.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (type == "series" && epNow != null) Text("S${epNow.season} E${epNow.episode}" + (epNow.title ?: epNow.name)?.let { " · $it" }.orEmpty(),
                            Modifier.padding(top = 2.dp), fontSize = 13.sp, color = Color(0xFFD0D4DD), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val qLabel = run {
                        val vf = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
                        val res = vf?.takeIf { it.width > 0 }?.let { f -> when { f.width >= 3200 -> 2160; f.width >= 1800 -> 1080; f.width >= 1200 -> 720; else -> f.height } }
                            ?: now?.url?.let { itemFor(it) }?.effectiveRes() ?: 0
                        when { res >= 1440 -> "4K"; res >= 1080 -> "Full HD"; res >= 720 -> "HD"; res > 0 -> "SD"; else -> "Auto" }
                    }
                    Row(Modifier.align(Alignment.TopEnd).padding(end = 40.dp, top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box {
                            TvPill(tvZone == "top" && tvTop == 0, Icons.Outlined.Slideshow, "Quality", now?.url?.let { itemFor(it) }?.let { tvQualityOf(it) } ?: qLabel, at("quality"))
                            if (tvUp != null) Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).size(8.dp).clip(CircleShape).background(Color(0xFF3CCF7A)))
                        }
                        TvPill(tvZone == "top" && tvTop == 1, Icons.Outlined.Subtitles, "Audio & Subtitles", null,
                            Modifier.onGloballyPositioned { c -> avBtnCenter = c.boundsInRoot().center; btnCenters["avsubs"] = c.boundsInRoot().center })
                    }
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 30.dp)) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(fmtTime(pendingSeek ?: pos), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE6E8EE))
                            Spacer(Modifier.weight(1f))
                            if (dur > 0) Text(fmtTime((dur - (pendingSeek ?: pos)).coerceAtLeast(0)), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE6E8EE))
                        }
                        SeekBar(pos, buffered, dur, thumbs, pendingSeek, focused = tvZone == "bar") { pendingSeek = null; seekAbs(it); poke++ }
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Watch Next · Next · Episodes (series) · ❯ More — icons only after Watch Next, same glass on focus.
                            val keys = tvBotKeys()
                            fun on(k: String) = tvZone == "bot" && keys.getOrNull(tvBot) == k
                            TvPill(on("wn"), Icons.Outlined.Explore, "Watch Next", null, at("watchnext"))
                            if ("next" in keys) TvIconPill(on("next"), Icons.Default.SkipNext, "Next episode")
                            if ("eps" in keys) TvIconPill(on("eps"), Icons.Default.VideoLibrary, "Episodes")
                            val rot by animateFloatAsState(if (more) -90f else 0f, spring(dampingRatio = 0.6f, stiffness = 400f), label = "moreRot")
                            TvPill(on("more"), Icons.Default.ChevronRight, null, null, at("more"), iconRotation = rot)
                        }
                    }
                }
            }
            // TV Quality: Instant · 4K · Full HD · Mid HD (the tags and every link are in Sources).
            if (BuildConfig.IS_TV) {
                val slowTv by SlowAddons.links.collectAsStateWithLifecycle()
                // Worked out ahead of time, so the bubble opens with its four qualities ready.
                val tvGroupsNow by produceState(emptyMap<String, List<StreamItem>>(), quickList, known, slowTv[p.key]) {
                    value = withContext(Dispatchers.Default) {
                        runCatching { vm.tvGroups(vm.filtered((quickList + known + slowTv[p.key].orEmpty()).distinctBy { it.stream.url }).list) }.getOrDefault(emptyMap())
                    }
                }
                AnimatedVisibility(tvQ, Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = 40.dp),
                    enter = fadeIn(tween(150)) + scaleIn(spring(dampingRatio = 0.75f, stiffness = 480f), initialScale = 0.3f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.8f, 0f)),
                    exit = fadeOut(tween(140)) + scaleOut(tween(160), targetScale = 0.3f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.8f, 0f))) {
                    TvQualityBubble(tvGroupsNow, now?.url, upgradeReady = tvUp != null, onClose = { tvQ = false; controls = true; tvZone = "top" }, onPick = { q, st ->
                        tvQ = false; controls = false
                        if (st.stream.url == now?.url) return@TvQualityBubble
                        if (st.stream.url == tvUp?.stream?.url) tvUp = null
                        switchTo(st, "$q · same moment")
                    })
                }
            }
            // TV ❯ More: a compact Xstream bubble with the phone player's other buttons (the remote moves inside it).
            if (BuildConfig.IS_TV) {
                val mc = btnCenters["more"]
                val bw = constraints.maxWidth.toFloat().coerceAtLeast(1f); val bh = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                val mo = androidx.compose.ui.graphics.TransformOrigin(((mc?.x ?: bw * 0.12f) / bw).coerceIn(0f, 1f), ((mc?.y ?: bh * 0.9f) / bh).coerceIn(0f, 1f))
                AnimatedVisibility(more && controls && firstFrame && !inPip, Modifier.fillMaxSize(),
                    enter = scaleIn(spring(dampingRatio = 0.8f, stiffness = 420f), initialScale = 0.1f, transformOrigin = mo) + fadeIn(tween(140)),
                    exit = scaleOut(tween(180), targetScale = 0.1f, transformOrigin = mo) + fadeOut(tween(160))) {
                    val shape = RoundedCornerShape(24.dp)
                    val first = remember { FocusRequester() }
                    LaunchedEffect(Unit) { repeat(8) { delay(60); runCatching { first.requestFocus() } } }
                    fun pick(block: () -> Unit) { more = false; block(); if (panel != null) panelFromMore = true }
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 92.dp).widthIn(max = 420.dp)
                            .shadow(26.dp, shape, ambientColor = Color.Black, spotColor = Color.Black).clip(shape).background(Color(0xF5111216)).border(1.dp, Color(0x52E50914), shape).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("More", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0x9E14141A)).border(1.dp, Color(0x8CE50914), CircleShape), Alignment.Center) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", Modifier.size(17.dp)) }
                            }
                            val items = buildList<Triple<ImageVector, String, () -> Unit>> {
                                add(Triple(Icons.Default.CloudQueue, "Sources") { pick { panel = "sources" } })
                                add(Triple(Icons.Default.Speed, "Speed · ${speed.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }}x") { pick { panel = "speed" } })
                                add(Triple(Icons.Default.Bedtime, if (sleepAt != null || sleepEnd) "Sleep on" else "Sleep timer") { pick { panel = "sleep" } })
                                add(Triple(Icons.Default.AspectRatio, AspectModes[aspectIdx].first) { setAspect((aspectIdx + 1) % AspectModes.size); poke++ })
                                add(Triple(Icons.Default.GraphicEq, "Sound") { pick { panel = "sound" } })
                                add(Triple(Icons.Default.Groups, "Watch together") { pick { panel = "together" } })
                                if (type == "series" && episodes.isNotEmpty()) add(Triple(Icons.Default.VideoLibrary, "Episodes") { pick { showEpisodes = true; controls = false } })
                                if (nextEp != null) add(Triple(Icons.Default.SkipNext, "Next episode") { pick { playNext() } })
                                if (type != "series") add(Triple(Icons.Default.SkipNext, "Next title") { pick { tvNext() } })
                                add(Triple(Icons.Default.OpenInNew, "Other player") { pick { pauseNow(); now?.let { openExternal(ctx, it.url, p.title) } } })
                                add(Triple(Icons.Outlined.Info, "Info") { pick { infoManual = true; showInfo = true; controls = false } })
                            }
                            items.chunked(2).forEachIndexed { r, row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEachIndexed { c, (ic, label, act) ->
                                        TvTile(ic, label, if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier, act)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Seek bubble (controls hidden): « 30s / 30s » that adds up while you keep pressing.
            // The side and text stay those of the last skip while it fades (it used to flip to the other side for a moment).
            var lastBubble by remember { mutableStateOf(true to 30) }
            bubble?.let { if (it != lastBubble) lastBubble = it }
            AnimatedVisibility(bubble != null, Modifier.align(if ((bubble ?: lastBubble).first) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = side),
                enter = fadeIn(tween(120)) + androidx.compose.animation.scaleIn(tween(160), initialScale = 0.85f), exit = fadeOut(tween(260))) {
                val b = bubble ?: lastBubble
                Column(
                    Modifier.size(132.dp).clip(CircleShape).background(Color(0x66000000)).border(1.dp, Color(0x33FFFFFF), CircleShape),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(if (b.first) Icons.Default.FastForward else Icons.Default.FastRewind, null, Modifier.size(34.dp))
                    Text("${b.second}s", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(fmtTime(pos), fontSize = 12.sp, color = Color(0xFFC9CED9))
                }
            }

            if (locked && controls) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = topY, end = topEnd).size(44.dp).clip(CircleShape).background(Color(0x99000000))
                    .focusRing(CircleShape).clickable { locked = false; poke++ }, Alignment.Center) { Icon(Icons.Default.Lock, "Unlock", Modifier.size(22.dp)) }
                LaunchedEffect(poke) { delay(2500); if (locked) controls = false }
            }

            // Info screen while paused.
            AnimatedVisibility(
                showInfo, Modifier.fillMaxSize(),
                enter = fadeIn(tween(450)) + androidx.compose.animation.scaleIn(tween(450), initialScale = 1.03f),
                exit = fadeOut(tween(380)) + androidx.compose.animation.scaleOut(tween(380), targetScale = 1.06f),
            ) {
                val m = meta
                // Tap anywhere: the info softly blurs, grows and fades away (no click ripple).
                val infoBlur by transition.animateDp(transitionSpec = { tween(380) }, label = "infoBlur") {
                    if (it == androidx.compose.animation.EnterExitState.Visible) 0.dp else 14.dp
                }
                Box(Modifier.fillMaxSize().blur(infoBlur).background(Brush.horizontalGradient(0f to Color(0xF2000000), 0.6f to Color(0xAA000000), 1f to Color(0x22000000)))
                    .pointerInput(Unit) { detectTapGestures { showInfo = false } }) {
                    Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.55f).padding(start = side)) {
                        Text("You're watching", fontSize = 15.sp, color = Color(0xFFB6BDCC))
                        if (m != null && logoOf(m) != null) AsyncImage(logoOf(m), m.name, Modifier.padding(vertical = 10.dp).height(70.dp), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
                        else Text(m?.name ?: p.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                        if (type == "series") Text(p.title.removePrefix(m?.name ?: "").trim(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        m?.let { mm ->
                            Text(listOfNotNull(mm.releaseInfo, mm.runtime, mm.imdbRating?.let { "IMDb $it" }).joinToString("  •  "), fontSize = 14.sp, color = Color(0xFFC9CEDB), modifier = Modifier.padding(top = 6.dp))
                            mm.genres?.takeIf { it.isNotEmpty() }?.let { Text(it.take(3).joinToString(" · "), fontSize = 14.sp, color = Accent, modifier = Modifier.padding(top = 4.dp)) }
                            val epDesc = mm.videos?.firstOrNull { it.id == p.key }?.let { it.title ?: it.name }
                            Text(mm.description ?: epDesc ?: "", fontSize = 15.sp, lineHeight = 22.sp, color = Color(0xFFE2E5EC), maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
                        }
                        realVideoLine(tracks)?.let { Text("Video · $it", fontSize = 13.sp, color = Color(0xFFC9CEDB), fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 12.dp)) }
                        Text("Tap anywhere to continue", fontSize = 13.sp, color = Color(0xFF8A94A8), modifier = Modifier.padding(top = 16.dp))
                    }
                }
            }

            // ───── Loading screen: dimmed artwork, "Finding stream source" → "Buffering…" ─────
            if (loadingAlpha > 0.01f) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = loadingAlpha }.noFocus().clickable(interactionSource = noRipple, indication = null) {}) {
                    AsyncImage(p.image ?: meta?.background ?: meta?.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Color(0xA6000000)))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x66000000), 0.5f to Color.Transparent, 1f to Color(0x99000000))))
                    val f = failure
                    if (f == null) {
                        // Finding a source → the cat inspects the poster; buffering → the movie is served under a cloche.
                        val scenePoster = meta?.poster ?: p.image
                        val sceneQuality = when {
                            bestRes >= 2160 -> "4K · SHARP"
                            bestRes >= 1440 -> "1440p · SHARP"
                            bestRes >= 1080 -> "1080p · SHARP"
                            bestRes >= 720 -> "720p · CLEAR"
                            else -> null
                        }
                        // Instant play: no loading scenes — just the artwork for the moment the saved video takes to open
                        // (a small ring only if it takes longer than 1.5 s).
                        // TV: the heartbeat monitor with a real percentage (finding = up to 40 %, loading the video = the rest).
                        if (BuildConfig.IS_TV && !(p.instant && !status.startsWith("Finding"))) {
                            val finding = status.startsWith("Finding") || status.startsWith("Quick")
                            val pct = if (finding) (if (sourceTotal > 0) answered * 38 / sourceTotal.coerceAtLeast(1) else 5).coerceIn(3, 39)
                                else (40 + bufProgress * 59).toInt().coerceIn(40, 99)
                            TvMomentLoader(vm, p.key.substringBefore(":"), type, meta?.name ?: p.title.substringBefore("  S"),
                                poster = meta?.poster, fallbackArt = meta?.background ?: p.image, story = meta?.description,
                                stage = when { status.startsWith("Quick") -> "Trying the fastest servers"; finding -> "Finding the best source"; pct < 70 -> "Connecting"; else -> "Loading the first minutes" },
                                percent = pct, modifier = Modifier.fillMaxSize())
                        } else if (p.instant && !status.startsWith("Finding")) {
                            var slow by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) { delay(1500); slow = true }
                            if (slow) CircularProgressIndicator(Modifier.align(Alignment.Center).size(34.dp), color = Color.White, strokeWidth = 2.5.dp)
                        // Quick play starts in a moment: only the "Serving it fresh" scene (no finding scene first).
                        // Playing from all addons: finding → serving, as before.
                        } else {
                            // Xstream phone: the StreamX TV style loader, with the trailer playing inside the frame.
                            val finding = status.startsWith("Finding") || status.startsWith("Quick")
                            val pct = if (finding) (if (sourceTotal > 0) answered * 38 / sourceTotal.coerceAtLeast(1) else 5).coerceIn(3, 39)
                                else (40 + bufProgress * 59).toInt().coerceIn(40, 99)
                            XMomentLoader(vm, meta, p.key.substringBefore(":"), type, meta?.name ?: p.title.substringBefore("  S"), meta?.background ?: p.image,
                                stage = when { status.startsWith("Quick") -> "Trying the fastest servers"; finding -> "Finding the best source"; pct < 70 -> "Connecting"; else -> "Loading the first minutes" },
                                percent = pct, modifier = Modifier.fillMaxSize())
                        }
                    } else {
                        Column(Modifier.align(Alignment.Center).padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(Public.say(f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                LoadBtn("Find another source", true, Modifier.focusRequester(failFocus)) { findRequest = if (findRequest < 1) 2 else findRequest + 1 }
                                LoadBtn("Choose source", false) { failure = null; panel = "sources"; status = "Buffering…" }
                            }
                        }
                    }
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = topEnd - 14.dp).size(50.dp).clip(CircleShape).background(Color(0x80000000)).focusRing(CircleShape).clickable { onBack() },
                        Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(22.dp)) }
                    // Sources: top-left, a little down from the edge so the thumb reaches it.
                    Box(
                        Modifier.align(Alignment.TopStart).padding(start = 30.dp, top = 74.dp).size(44.dp)
                            .shadow(10.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                            .background(Color(0x8C101014), CircleShape).border(1.dp, Color(0x24FFFFFF), CircleShape)
                            .focusRing(CircleShape)
                            .clickable(interactionSource = noRipple, indication = null) { panel = "sources" },
                        Alignment.Center,
                    ) { Icon(Icons.Default.KeyboardArrowDown, "Sources", Modifier.size(20.dp), tint = Color.White) }
                }
            }

            // ───── Quality menu (Auto / Full HD / Mid HD / Low) ─────
            LaunchedEffect(controls) { if (!controls) qualityMenu = false }
            // The menu's rows are worked out in the background as soon as the links are known (sorting every link by
            // language and quality took seconds on the main thread, so the menu appeared late after a tap).
            val picOrder = remember(vm.texts.collectAsStateWithLifecycle().value["picPref"], vm.texts.value["picMatch"]) { PicturePref.order(vm) }
            val slowNow by SlowAddons.links.collectAsStateWithLifecycle()
            val qCols by produceState<List<Pair<String, List<Pair<String, List<StreamItem>>>>>>(emptyList(), quickList, known, hdReady, slowNow[p.key], picOrder, qualityMenu) {
                value = withContext(Dispatchers.Default) { runCatching {
                    val all = vm.filtered((quickList + known + hdReady.values.flatten() + slowNow[p.key].orEmpty()).distinctBy { it.stream.url }).list
                        .filter { !Ranker.isDead(it) && !Ranker.keepsFailing(it) && !it.unplayableHere() && !it.slowMp4() && HostStats.penalty(it.stream.url.orEmpty()) == 0 }
                    fun col(lo: Int, hi: Int) = all.filter { it.effectiveRes() in lo until hi }
                        .groupBy { if (it.isPlaylist()) "Instant" else VisualTags.primary(it) }.toList()
                        .sortedBy { (g, _) -> if (g == "Instant") -1 else picOrder.indexOf(g).let { i -> if (i < 0) 99 else i } }
                        .map { (g, l) -> g to l.sortedWith(compareBy<StreamItem>({ vm.langRank(it) }, { -Ranker.score(it) })).take(if (vm.pluginMode()) 60 else 4) }
                    (if (vm.pluginMode()) listOf("Auto" to col(0, 1)) else emptyList()) +
                        listOf("4K" to col(1440, 100_000), "1080p" to col(1080, 1440), "720p" to col(700, 1080))
                }.getOrDefault(emptyList()) }
            }
            // The first file of each quality gets its start + index saved (opens in about a second from the menu). On mobile
            // data only the quality playing and the next one up; on Wi-Fi all three. Elite on → only on Wi-Fi.
            LaunchedEffect(qCols.any { it.second.isNotEmpty() }, firstFrame, p.key) {
                if (!firstFrame || BuildConfig.IS_TV || usingVlc || !vm.upgradeAllowed() || qCols.all { it.second.isEmpty() }) return@LaunchedEffect
                delay(20_000)
                val wifi = isOnWifi(ctx)
                val curRes = now?.url?.let { itemFor(it) }?.effectiveRes() ?: 0
                val curCol = when { curRes >= 1440 -> 0; curRes >= 1080 -> 1; else -> 2 }
                qCols.forEachIndexed { i, (title, groups) ->
                    while (buffering) delay(1_000)
                    if (now?.url?.let { itemFor(it) }?.isPlaylist() != true) return@LaunchedEffect
                    if (!wifi && i != curCol && i != curCol - 1) return@forEachIndexed
                    val c = groups.flatMap { it.second }.firstOrNull { !it.isPlaylist() && it.stream.url != now?.url } ?: return@forEachIndexed
                    val u = c.stream.url ?: return@forEachIndexed
                    if (u in tagReady || hdReady.values.flatten().any { it.stream.url == u }) return@forEachIndexed
                    if (saveHdStart(ctx, c)) { tagReady = tagReady + u; PlayTiming.late("Quality menu: $title first file ready") }
                }
            }
            // Instant (already playing) folds away 2 s after the menu opens; tap its header to open it again.
            var instantOpen by remember { mutableStateOf(true) }
            LaunchedEffect(qualityMenu) { if (qualityMenu) { instantOpen = true; delay(2_000); instantOpen = false } }
            AnimatedVisibility(
                qualityMenu && controls, Modifier.align(Alignment.TopEnd).padding(top = topY + 40.dp, end = topEnd + 40.dp),
                enter = fadeIn(tween(160)) + androidx.compose.animation.scaleIn(spring(dampingRatio = 0.75f, stiffness = 500f), initialScale = 0.9f,
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)),
                exit = fadeOut(tween(140)) + androidx.compose.animation.scaleOut(tween(140), targetScale = 0.95f),
            ) {
                // Three columns — 4K | 1080p | 720p — each grouped by picture tag in your picture-preference order
                // (instant streaming playlists first). A red tick = start and index saved (opens in about a second).
                val shape = RoundedCornerShape(20.dp)
                val playing = now?.url
                val curItem = playing?.let { itemFor(it) }
                val onQuick = curItem?.isMovieBox() == true || curItem?.let { vm.isQuick(it) } == true
                val readySet = tagReady + hdReady.values.flatten().mapNotNull { it.stream.url }
                val focusUrl = qCols.flatMap { c -> c.second.flatMap { it.second } }.let { all -> all.firstOrNull { it.stream.url == playing } ?: all.firstOrNull() }?.stream?.url
                val vgNow = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 1 }
                val ladder = vgNow?.let { g -> (0 until g.length).map { g.getTrackFormat(it).height }.filter { it > 0 }.distinct().sortedDescending() }.orEmpty()
                val pinnedH = vgNow?.takeIf { g -> player.trackSelectionParameters.overrides.keys.any { it == g.mediaTrackGroup } }
                    ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).height } }
                if (vm.pluginMode()) PluginQualityBar(qCols, playing, readySet, ladder, pinnedH, onVariant = { h ->
                    val g = vgNow ?: return@PluginQualityBar
                    player.trackSelectionParameters = if (h == null) player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
                        else player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, (0 until g.length).filter { g.getTrackFormat(it).height == h })).build()
                    qualityMenu = false; poke++
                }, modifier = Modifier.onFocusChanged {
                    val had = menuHasFocus
                    menuHasFocus = it.hasFocus
                    if (had && !it.hasFocus) qualityMenu = false
                }, onPoke = { poke++ }) { st ->
                    qualityMenu = false; poke++
                    upgrade = null
                    switchTo(st, VisualTags.quality(st))
                } else Row(
                    Modifier.widthIn(max = 660.dp).heightIn(max = 330.dp).clip(shape).background(Color(0xF0141418)).border(1.dp, Color(0x1FFFFFFF), shape)
                        .onFocusChanged {
                            val had = menuHasFocus
                            menuHasFocus = it.hasFocus
                            if (had && !it.hasFocus) qualityMenu = false
                        }
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (qCols.all { it.second.isEmpty() }) Text("Finding qualities…", Modifier.padding(16.dp), fontSize = 13.sp, color = Color(0xFF9AA3B5))
                    else qCols.forEach { (title, groups) ->
                        Column(Modifier.width(200.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x08FFFFFF)).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(8.dp)) {
                            Row(Modifier.padding(start = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(title, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                Text("${groups.sumOf { it.second.size }}", fontSize = 11.sp, color = Color(0xFF8A94A8))
                            }
                            if (groups.isEmpty()) Text("Not available", Modifier.padding(4.dp), fontSize = 12.sp, color = Color(0xFF6E7687))
                            groups.forEach { (tag, files) ->
                                val inst = tag == "Instant"
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).clip(RoundedCornerShape(6.dp))
                                    .then(if (inst) Modifier.clickable { instantOpen = !instantOpen; poke++ } else Modifier).padding(start = 4.dp, end = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(tag.uppercase(), Modifier.weight(1f), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                        color = if (inst) Color(0xFFFFB3B8) else Color(0xFF8A94A8), letterSpacing = 0.8.sp)
                                    if (inst) {
                                        val rot by androidx.compose.animation.core.animateFloatAsState(if (instantOpen) 0f else -90f, tween(300), label = "instChev")
                                        Text("${files.size}", fontSize = 10.sp, color = Color(0xFF8A94A8))
                                        Icon(Icons.Default.KeyboardArrowDown, if (instantOpen) "Fold" else "Open", Modifier.size(14.dp).graphicsLayer { rotationZ = rot }, tint = Color(0xFFFFB3B8))
                                    }
                                }
                                AnimatedVisibility(!inst || instantOpen,
                                    enter = androidx.compose.animation.expandVertically(tween(380)) + fadeIn(tween(300)),
                                    exit = androidx.compose.animation.shrinkVertically(tween(420)) + fadeOut(tween(260))) {
                                Column {
                                files.forEach { st ->
                                    val on = st.stream.url == playing
                                    val ready = st.stream.url in readySet || st.isPlaylist()
                                    Column(
                                        Modifier.padding(bottom = 5.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                            .background(if (on) Color(0x26E50914) else Color(0x0DFFFFFF))
                                            .border(1.dp, if (on) Color(0xCCE50914) else Color(0x12FFFFFF), RoundedCornerShape(10.dp))
                                            .then(if (st.stream.url == focusUrl) Modifier.focusRequester(qualityFocus) else Modifier)
                                            .focusFill().clickable(enabled = !on) {
                                                qualityMenu = false; poke++
                                                if (onQuick && !st.isMovieBox()) { upgradedFrom = curItem; upgradedUrl = st.stream.url }
                                                upgrade = null
                                                switchTo(st, VisualTags.quality(st))
                                            }
                                            .padding(horizontal = 9.dp, vertical = 7.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text((if (st.isPlaylist()) listOf("Instant") else VisualTags.video(st).ifEmpty { listOf(VisualTags.primary(st)) }).joinToString(" · "),
                                                Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (on) Icon(Icons.Default.Check, null, Modifier.size(15.dp), tint = Color(0xFFFF3B47))
                                            else if (ready) Icon(Icons.Default.Check, "Ready", Modifier.size(14.dp), tint = Color(0x99FF3B47))
                                        }
                                        Text(listOfNotNull(VisualTags.audio(st), VisualTags.size(st), st.languagesOrOriginal().joinToString(" + ") { langLabel(it) }).joinToString(" · "),
                                            fontSize = 10.5.sp, color = Color(0xFF9AA3B5), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                }
                                }
                            }
                        }
                    }
                }
            }

            // Episodes and Watch Next grow out of their buttons too (a bubble), then settle.
            val bw = constraints.maxWidth.toFloat().coerceAtLeast(1f); val bh = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            fun originOf(k: String) = btnCenters[k]?.let { androidx.compose.ui.graphics.TransformOrigin((it.x / bw).coerceIn(0f, 1f), (it.y / bh).coerceIn(0f, 1f)) }
                ?: androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.9f)
            val epScale by animateFloatAsState(if (showEpisodes) 1f else 0.08f, if (showEpisodes) spring(dampingRatio = 0.8f, stiffness = 380f) else tween(220), label = "epB")
            // ───── Episodes (series): premium pop-up grouped by season ─────
            Box(Modifier.fillMaxSize().graphicsLayer { if (!BuildConfig.IS_TV) { scaleX = epScale; scaleY = epScale; transformOrigin = originOf("episodes") } }) {
            EpisodesPopup(
                visible = showEpisodes, episodes = episodes, currentId = p.key, showName = meta?.name ?: "",
                maxW = screenW, maxH = screenH, onClose = { showEpisodes = false },
                watched = { id -> (vm.progress.value.firstOrNull { it.key == id } ?: vm.epProgress.value[id])?.let { s -> if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else null } },
            ) { ep -> showEpisodes = false; if (ep.id != p.key) playEpisode(ep) }
            }

            // ───── Watch Next: the next episodes and titles like this one ─────
            if (BuildConfig.IS_TV) SideEffect {
                tvPlayTitle = { m ->
                    scope.launch {
                        if (m.type == "series") {
                            val full = vm.fullMeta(m) ?: m
                            val first = full.videos.orEmpty().filter { (it.season ?: 0) > 0 && it.episode != null }
                                .sortedWith(compareBy({ it.season }, { it.episode })).firstOrNull()
                            if (first == null) { Snack.show("No episodes found for ${m.name}"); return@launch }
                            onReplace(Screen.Player("${full.name}  S${first.season}E${first.episode}", "", emptyList(), key = first.id,
                                image = first.thumbnail ?: full.background ?: full.poster, type = "series", meta = full, find = true, quick = vm.qsActive()))
                        } else onReplace(Screen.Player(m.name, "", emptyList(), key = m.id, image = m.background ?: m.poster, type = "movie", meta = m,
                            find = true, quick = vm.qsActive()))
                    }
                }
            }
            val wnP = if (wnDrag >= 0f) wnDrag else wnAnim.value
            Box(Modifier.fillMaxSize().graphicsLayer {
                // Phones: the sheet slides up from the bottom, following the finger while it is dragged.
                if (!BuildConfig.IS_TV) { translationY = (1f - wnP) * bh; alpha = (wnP * 1.6f).coerceAtMost(1f) }
            }) {
            WatchNextOverlay(
                vm, watchNext || wnP > 0.001f, meta, type, p.key, episodes, onClose = { watchNext = false },
                onEpisode = { ep -> watchNext = false; playEpisode(ep) },
                onDrag = { dy, h ->
                    val cur = if (wnDrag >= 0f) wnDrag else wnAnim.value
                    wnDrag = (cur - dy / (h * 0.55f)).coerceIn(0f, 1f)
                },
                onDragEnd = { vel ->
                    // Let go below 70 % (or flick down) and it closes; otherwise it springs back open.
                    val pNow = wnDrag.coerceAtLeast(0f)
                    val close = pNow < 0.7f || vel > 1200f
                    scope.launch {
                        wnAnim.snapTo(pNow); wnDrag = -1f
                        if (close) { watchNext = false; wnAnim.animateTo(0f, tween(220)) }
                        else wnAnim.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 420f))
                    }
                },
                dragging = { wnDrag >= 0f },
            ) { m ->
                watchNext = false
                scope.launch {
                    if (m.type == "series") {
                        val full = vm.fullMeta(m) ?: m
                        val first = full.videos.orEmpty().filter { (it.season ?: 0) > 0 && it.episode != null }
                            .sortedWith(compareBy({ it.season }, { it.episode })).firstOrNull()
                        if (first == null) { Snack.show("No episodes found for ${m.name}"); return@launch }
                        onReplace(Screen.Player("${full.name}  S${first.season}E${first.episode}", "", emptyList(), key = first.id,
                            image = first.thumbnail ?: full.background ?: full.poster, type = "series", meta = full, find = true, quick = vm.qsActive()))
                    } else onReplace(Screen.Player(m.name, "", emptyList(), key = m.id, image = m.background ?: m.poster, type = "movie", meta = m,
                        find = true, quick = vm.qsActive()))
                }
            }
            }

            // ───── Side panels slide in from the right ─────
            LaunchedEffect(panel) { panel?.let { lastPanel = it } }
            // Audio & Subtitles: Bubble (grows out of its button, default), Today's layout or Compact (Settings › Player).
            val avLayout = opt("avLayout").ifBlank { "bubble" }
            val whichNow = panel ?: lastPanel
            // Bubble style (default): every panel grows out of the button that opened it; Audio & Subtitles is the big one.
            val bubbleAll = avLayout == "bubble" || BuildConfig.IS_TV
            val bubble = bubbleAll
            val compact = whichNow == "avsubs" && avLayout == "compact" && !BuildConfig.IS_TV
            val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f); val hPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val from = btnCenters[whichNow.orEmpty()] ?: btnCenters["more"] ?: avBtnCenter
            val origin = androidx.compose.ui.graphics.TransformOrigin(
                ((from?.x ?: wPx * 0.3f) / wPx).coerceIn(0f, 1f), ((from?.y ?: hPx * 0.9f) / hPx).coerceIn(0f, 1f))
            val panelAlpha by animateFloatAsState(if (subPreview) 0f else 1f, tween(220), label = "panelAlpha")
            AnimatedVisibility(panel != null, Modifier.fillMaxSize().graphicsLayer { alpha = panelAlpha }, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
                // Audio & Subtitles closes only with its back button; the other panels also with a tap outside.
                Box(Modifier.fillMaxSize().background(Color(0x66000000)).noFocus().clickable(interactionSource = noRipple, indication = null) { if (whichNow != "avsubs" || BuildConfig.IS_TV) panel = null })
            }
            AnimatedVisibility(
                panel != null,
                (when {
                    // TV: compact bubbles, only as big as their options need (the screen is big already).
                    BuildConfig.IS_TV && whichNow == "avsubs" -> Modifier.align(Alignment.Center).width(720.dp).fillMaxHeight(0.78f)
                    BuildConfig.IS_TV && whichNow == "sources" -> Modifier.align(Alignment.Center).width(820.dp).fillMaxHeight(0.82f)
                    BuildConfig.IS_TV -> Modifier.align(Alignment.Center).width(420.dp).fillMaxHeight(0.66f)
                    bubble && (whichNow == "avsubs" || whichNow == "devsources") -> Modifier.align(Alignment.Center).fillMaxWidth(0.94f).fillMaxHeight(0.9f)
                    bubble -> Modifier.align(Alignment.Center).width(440.dp).fillMaxHeight(0.88f)
                    else -> Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                })
                    .graphicsLayer { alpha = panelAlpha },
                enter = if (bubble) scaleIn(spring(dampingRatio = 0.8f, stiffness = 360f), initialScale = 0.06f, transformOrigin = origin) + fadeIn(tween(140))
                    else slideInHorizontally(spring(dampingRatio = 0.86f, stiffness = 420f)) { it } + fadeIn(tween(160)),
                exit = if (bubble) scaleOut(tween(240), targetScale = 0.06f, transformOrigin = origin) + fadeOut(tween(220))
                    else slideOutHorizontally(tween(220)) { it } + fadeOut(tween(200)),
            ) {
                val which = panel ?: lastPanel
                val pShape = if (bubble) RoundedCornerShape(26.dp) else RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
                Column(
                    Modifier.fillMaxHeight().then(if (bubble) Modifier.fillMaxWidth() else Modifier.width(when {
                        which != "avsubs" && which != "devsources" -> 400.dp
                        compact -> (maxWidth * 0.66f).coerceAtMost(600.dp)
                        else -> (maxWidth * 0.94f).coerceAtMost(1060.dp)
                    }))
                        .then(if (bubble) Modifier.shadow(30.dp, pShape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
                        .clip(pShape)
                        .background(Color(0xF5111216)).border(1.dp, if (bubble) Color(0x47E50914) else Color(0x14FFFFFF), pShape)
                        .onFocusChanged {
                            val had = panelHasFocus
                            panelHasFocus = it.hasFocus
                            // Remote focus slipped out of the open panel (or its list changed): back to its first item.
                            if (had && !it.hasFocus) scope.launch { delay(60); if (panel != null && !panelHasFocus) runCatching { panelFocus.requestFocus() } }
                        }
                        .noFocus()
                        .clickable(interactionSource = noRipple, indication = null) {}
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (which) { "subs" -> "Subtitles"; "audio" -> "Audio"; "avsubs" -> "Audio & Subtitles"; "speed" -> "Playback speed"; "sleep" -> "Sleep timer"; "sound" -> "Sound"; "together" -> "Watch together"; "devsources" -> "Dev sources"; else -> "Sources" },
                            Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        )
                        // Back: a glass circle with the Xstream red edge.
                        if (!BuildConfig.IS_TV) Box(
                            Modifier.size(38.dp).shadow(12.dp, CircleShape, ambientColor = Color(0x99E50914), spotColor = Color(0x99E50914))
                                .clip(CircleShape).background(Color(0x9E14141A)).border(1.dp, Color(0x8CE50914), CircleShape)
                                .clickable { panel = null },
                            Alignment.Center,
                        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", Modifier.size(19.dp)) }
                    }
                    val audioContent: @Composable ColumnScope.() -> Unit = {
                        if (usingVlc) vlc?.let { v -> VlcTrackList(v, audio = true, tick = vlcTick) }
                        else {
                            // Every version of this title by language (same provider as what plays; MovieBox: every dub).
                            val cur = now?.url?.let { itemFor(it) }
                            val groups = remember(known, quickList, now) {
                                val pool = (known + quickList).distinctBy { it.stream.url }
                                    .filter { cur == null || it.addonName == cur.addonName || (cur.isMovieBox() && it.isMovieBox()) }
                                val curRes = cur?.effectiveRes() ?: 0
                                // Per language: the link it last played from, then streaming playlists (MovieBox .mpd — start at
                                // once), working servers, the quality closest to now. (Hindi → Original → Hindi = the same Hindi.)
                                pool.flatMap { s -> s.languagesOrOriginal().map { it to s } }.groupBy({ it.first }, { it.second })
                                    .mapValues { (lang, l) -> l.sortedWith(compareBy<StreamItem>(
                                        { if (it.stream.url == urlByLang[lang]) 0 else 1 }, { if (Ranker.isDead(it) || Ranker.keepsFailing(it)) 1 else 0 },
                                        { if (it.isPlaylist()) 0 else 1 }, { if (it.languagesOrOriginal().size == 1) 0 else 1 },
                                        { kotlin.math.abs(it.effectiveRes() - curRes) })).take(3) }
                            }
                            val order = (listOfNotNull(LangPick.current) + vm.qsLangs()).distinct()
                            AudioByLanguage(
                                tracks, player, order, groups, now?.url, cur?.languagesOrOriginal().orEmpty(), LangAhead.saved, panelFocus,
                                onTrack = { code -> vm.setPlayerPref("audio", code); langName(code)?.let { vm.setTitleLang(p.key, it) } },
                            ) { lang, v -> panel = null; vm.setTitleLang(p.key, lang); switchTo(v, langLabel(lang)) }
                        }
                    }
                    CompositionLocalProvider(LocalFirstFocus provides panelFocus) {
                    when (which) {
                        "subs" -> SubsPanel(
                            vm, type, p.key, loaded = now?.subs.orEmpty(),
                            tracks = {
                                if (usingVlc) vlc?.let { v -> VlcTrackList(v, audio = false, tick = vlcTick) }
                                else TrackList(tracks, C.TRACK_TYPE_TEXT, player, allowOff = true, offOverride = ext != null) { lang -> vm.setPlayerPref("subs", lang); ext = null }
                            },
                        ) { sub -> addOnlineSub(sub) }
                        "audio" -> audioContent()
                        "avsubs" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { Row(Modifier.fillMaxHeight().widthIn(max = 1040.dp).fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                            // Audio | Subtitles | Style — three equal columns with the same gap, centred; the remote moves
                            // between them with ◀ ▶.
                            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                                SubSection("Audio")
                                audioContent()
                                // Sound: Audio boost and Night mode (remembered).
                                if (!BuildConfig.IS_TV) {
                                    Text("Sound", Modifier.padding(top = 14.dp, bottom = 4.dp), fontSize = 13.sp, color = Color(0xFF8A94A8))
                                    SoundToggle("Audio boost", "Up to ~2× louder on phone speakers", boost) { boost = it; vm.setPlayerPref("boost", if (it) "yes" else "") }
                                    SoundToggle("Night mode", "Softer loud scenes, clearer dialogue", night) { night = it; vm.setPlayerPref("night", if (it) "yes" else "") }
                                }
                            }
                            val subsCol: @Composable () -> Unit = {
                                CompositionLocalProvider(LocalFirstFocus provides null) {
                                    SubtitlesColumn(
                                        vm, type, p.key, loaded = now?.subs.orEmpty(), activeUrl = ext?.sub?.url,
                                        hasInside = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT },
                                        tracks = {
                                            if (usingVlc) vlc?.let { v -> VlcTrackList(v, audio = false, tick = vlcTick) }
                                            else TrackList(tracks, C.TRACK_TYPE_TEXT, player, allowOff = true, offOverride = ext != null) { lang -> vm.setPlayerPref("subs", lang); ext = null }
                                        },
                                        scroll = !compact,
                                        exo = if (usingVlc) null else tracks to player,
                                        onInside = { lang -> subsByHand = true; if (lang == "off") vm.setPlayerPref("subs", "") ; ext = null }, syncNote = syncNote,
                                    ) { sub -> addOnlineSub(sub) }
                                }
                            }
                            val styleCol: @Composable () -> Unit = {
                                CompositionLocalProvider(LocalFirstFocus provides null) {
                                    SubStyleColumn(vm, subOffsetMs, ext != null, { subOffsetMs = it; manualSync = true }, { subPreview = it }, scroll = !compact,
                                        onSyncNow = { manualSync = false; syncNote = "Auto sync: checking…"; syncNow(); syncTick++ }, onAuto = { on -> if (on) { manualSync = false; syncTick++ } }, syncNote = syncNote)
                                }
                            }
                            if (compact) Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState())) { subsCol(); styleCol() }
                            else {
                                Column(Modifier.weight(1f).fillMaxHeight()) { subsCol() }
                                Column(Modifier.weight(1f).fillMaxHeight()) { styleCol() }
                            }
                        } }
                        "speed" -> OptionList(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { "${it}x" to (speed == it) }) { i ->
                            speed = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)[i]; setRate(speed); panel = null
                        }
                        "sleep" -> {
                            val mins = listOf(0, 15, 30, 45, 60)
                            val cur = sleepAt?.let { ((it - SystemClock.elapsedRealtime()) / 60_000L).toInt() }
                            OptionList(mins.map { m -> (if (m == 0) "Off" else "$m minutes") to (if (m == 0) sleepAt == null && !sleepEnd else false) } +
                                listOf("End of this video" to sleepEnd)) { i ->
                                if (i < mins.size) {
                                    sleepEnd = false
                                    sleepAt = if (mins[i] == 0) null else SystemClock.elapsedRealtime() + mins[i] * 60_000L
                                    Snack.show(if (mins[i] == 0) "Sleep timer off" else "Pausing in ${mins[i]} minutes (the sound fades out first)")
                                } else { sleepAt = null; sleepEnd = true; Snack.show("Pausing at the end of this video") }
                                panel = null
                            }
                            if (cur != null) Text("About $cur min left", Modifier.padding(top = 10.dp), fontSize = 13.sp, color = Color(0xFF9AA1AE))
                        }
                        "together" -> TogetherPanel(current = { roomState(isPlayingNow()) }) { st ->
                            panel = null
                            appliedRev = st.rev
                            val expected = Together.expected(st)
                            when {
                                st.key.isNotBlank() && st.key != p.key -> onReplace(Screen.Player(st.title, st.url, emptyList(), key = st.key, image = st.image, resumeMs = expected, type = st.type))
                                st.url != now?.url -> load(Now(st.url, now?.subs.orEmpty(), "Watch together", null), expected, "Watch together")
                                else -> { Together.applying = true; try { seekAbs(expected); if (st.playing) playNow() else pauseNow() } finally { Together.applying = false } }
                            }
                        }
                        "sound" -> OptionList(listOf(
                            "Audio boost${if (boost) "  ✓" else ""} — up to 2× louder on phone speakers" to boost,
                            "Night mode${if (night) "  ✓" else ""} — softer loud scenes, clearer quiet dialogue" to night,
                        )) { i ->
                            if (i == 0) { boost = !boost; vm.setPlayerPref("boost", if (boost) "yes" else "") }
                            else { night = !night; vm.setPlayerPref("night", if (night) "yes" else "") }
                        }
                        else -> {
                          val pickSource: (StreamItem, List<StreamItem>) -> Unit = { st, all ->
                            panel = null
                            known = all
                            candidates = all.filter { it !== st }
                            val keep = keepPos()
                            scope.launch {
                                val extra = withTimeoutOrNull(1500) { runCatching { vm.subtitles(type, p.key) }.getOrDefault(emptyList()) }.orEmpty()
                                load(Now(st.stream.url!!, st.stream.subtitles.orEmpty() + extra, st.stream.name ?: st.addonName, st.addonName), keep, "Source changed")
                            }
                          }
                          // Sources: by visual tag, no server / provider names. Dev sources (chosen accounts): everything, as before.
                          if (which == "devsources") DevSourcesPanel(vm, type, p.key, now?.url, quickList, meta, onSwitch = pickSource)
                          // TV: by quality (Instant · 4K · Full HD · Mid HD) with every link's picture / sound tags.
                          else if (BuildConfig.IS_TV) TvSourcesPanel(vm, type, p.key, now?.url, (known + quickList).distinctBy { it.stream.url }, panelFocus) { st, all ->
                              if (st.stream.url == tvUp?.stream?.url) tvUp = null
                              pickSource(st, all)
                          }
                          else TagSourcesList(vm, type, p.key, now?.url, (known + quickList).distinctBy { it.stream.url },
                              tagReady + hdReady.values.flatten().mapNotNull { it.stream.url }, pickSource)
                        }
                    }
                    }
                }
            }
        }
    }
}

/** The first item of an open side panel gets the remote's focus through this. */
internal val LocalFirstFocus = staticCompositionLocalOf<FocusRequester?> { null }

/** Never takes the remote's focus (tap-through blockers and scrims). */
private fun Modifier.noFocus(): Modifier = this.focusProperties { canFocus = false }

/** White ring while a control has the remote's focus. Put it before the clickable. */
@Composable
private fun Modifier.focusRing(shape: Shape, width: Dp = 2.dp, color: Color = Color.White): Modifier {
    var f by remember { mutableStateOf(false) }
    return this.onFocusChanged { f = it.isFocused }.border(width, if (f) color else Color.Transparent, shape)
}

/** Soft highlight behind a list row while it has the remote's focus. Put it before the clickable. */
@Composable
private fun Modifier.focusFill(color: Color = Color(0x2EFFFFFF)): Modifier {
    var f by remember { mutableStateOf(false) }
    return this.onFocusChanged { f = it.isFocused }.background(if (f) color else Color.Transparent)
}

@Composable
private fun CtlIcon(icon: ImageVector, desc: String, size: Int, modifier: Modifier = Modifier, pad: Int = 8, onClick: () -> Unit) {
    // Remote focus: a soft white circle behind the icon and a slightly bigger icon.
    var focused by remember { mutableStateOf(false) }
    Icon(
        icon, desc,
        modifier
            .graphicsLayer { val sc = if (focused) 1.1f else 1f; scaleX = sc; scaleY = sc }
            .drawBehind { if (focused) drawCircle(Color(0x40FFFFFF), radius = this.size.minDimension / 2f + 6.dp.toPx()) }
            .clip(CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick).padding(pad.dp).size(size.dp),
        tint = Color.White,
    )
}

@Composable
private fun LoadBtn(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Text(text, modifier.clip(RoundedCornerShape(22.dp)).background(if (primary) Color.White else Color(0x33FFFFFF))
        .focusRing(RoundedCornerShape(22.dp), if (primary) 3.dp else 2.dp, if (primary) Accent else Color.White).clickable(onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 10.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (primary) Color.Black else Color.White)

@OptIn(UnstableApi::class)
private val AspectModes = listOf(
    "Fit" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
    "Crop" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    "Stretch" to AspectRatioFrameLayout.RESIZE_MODE_FILL,
)

/** TV: wraps a pill so it can't take focus itself (the remote's ▲ selects it) and shows the white edge when selected. */
@Composable
private fun TvPillFrame(selected: Boolean, top: Dp = 10.dp, bottom: Dp = 0.dp, content: @Composable () -> Unit) {
    val sc by animateFloatAsState(if (selected) 1.06f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "pillSel")
    Box(
        Modifier.graphicsLayer { scaleX = sc; scaleY = sc }
            .drawWithContent {
                drawContent()
                if (selected) {
                    // The ring wraps the pill itself: [top] / [bottom] are the gaps each pill keeps outside its shape.
                    val g = 4.dp.toPx(); val t = top.toPx()
                    val h = size.height - t - bottom.toPx()
                    drawRoundRect(TvFocusEdge, topLeft = Offset(-g, t - g), size = Size(size.width + 2 * g, h + 2 * g),
                        cornerRadius = CornerRadius(h / 2f + g), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                }
            }
            .focusProperties { canFocus = false },
    ) { content() }
}

@Composable
private fun PillButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    // TV: the pill itself never takes the remote's focus (the player's ▲ selects it).
    val tap = !BuildConfig.IS_TV
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier.padding(top = 10.dp).clip(shape).background(Color(0xE6FFFFFF)).border(1.dp, Color(0x33000000), shape)
            .focusRing(shape, 3.dp, Accent).clickable(enabled = tap, onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = Color.Black)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    }
}

/**
 * "Next Episode" at the end credits: a white pill whose ring fills up as the credits run out (the next episode starts by
 * itself at the end, as before). Tap to start it now.
 */
@Composable
private fun NextEpisodePill(left: Long, window: Long, onClick: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    val frac = (1f - left.toFloat() / window).coerceIn(0f, 1f)
    val ring by androidx.compose.animation.core.animateFloatAsState(frac, tween(900), label = "nextRing")
    Row(
        Modifier.padding(top = 10.dp).softHalo(26.dp, spread = 8.dp, strength = 0.35f).clip(shape).background(Color(0xF2FFFFFF))
            .focusRing(shape, 3.dp, Accent).clickable(enabled = !BuildConfig.IS_TV, onClick = onClick).padding(start = 8.dp, end = 18.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp), Alignment.Center) {
            CircularProgressIndicator(progress = { ring }, modifier = Modifier.fillMaxSize(), color = Color.Black, strokeWidth = 2.5.dp, trackColor = Color(0x22000000))
            Icon(Icons.Default.SkipNext, null, Modifier.size(16.dp), tint = Color.Black)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Next Episode", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            if (left in 1L..60_000L) Text("in ${(left + 999) / 1000} s", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF4A4F5C))
        }
    }
}

/** "9:41 PM" (or 21:41, as the device shows time). */
private fun clockNow(): String =
    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())

/** When the video ends at this speed. */
private fun endsAt(leftMs: Long, rate: Float): String {
    val r = if (rate > 0f) rate else 1f
    return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(System.currentTimeMillis() + (leftMs.coerceAtLeast(0L) / r).toLong()))
}

/**
 * Player button: icon (+ optional label). Remote focus = a frosted glass circle / pill around it, a little bigger
 * (no square highlight). [big] = the phone's centre play / pause.
 */
@Composable
private fun GlassBtn(
    icon: ImageVector, label: String?, desc: String, modifier: Modifier = Modifier, big: Boolean = false, iconFirst: Boolean = true, onClick: () -> Unit,
) {
    // Xstream: text-only player buttons — no fill, no border, no glass. A soft shadow keeps them readable on bright
    // scenes; touching one makes it pure white with a short glow and a little bigger.
    var focused by remember { mutableStateOf(false) }
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val hot = pressed || focused
    val sc by animateFloatAsState(if (hot) 1.1f else 1f, spring(dampingRatio = 0.55f, stiffness = 600f), label = "txtBtn")
    val iconSize = if (big) 44.dp else 22.dp
    val glow = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(if (hot) Color(0xCCFFFFFF) else Color(0xCC000000), blurRadius = if (hot) 18f else 8f))
    val tint = if (hot) Color.White else Color(0xFFEDEFF4)
    Row(
        modifier
            .graphicsLayer { scaleX = sc; scaleY = sc }
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .semantics { contentDescription = desc }
            .padding(horizontal = if (label == null) (if (big) 18.dp else 9.dp) else 10.dp, vertical = if (big) 18.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconFirst) Icon(icon, null, Modifier.size(iconSize).softGlow(hot), tint = tint)
        if (label != null) Text(label, Modifier.padding(start = if (iconFirst) 8.dp else 0.dp, end = if (iconFirst) 2.dp else 4.dp),
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = tint, style = glow)
        if (!iconFirst) Icon(icon, null, Modifier.size(iconSize).softGlow(hot), tint = tint)
    }
}

/** A soft round glow behind an icon while it's touched (and a faint shadow otherwise, for bright scenes). */
private fun Modifier.softGlow(on: Boolean): Modifier = drawBehind {
    val r = size.minDimension * 0.9f
    drawCircle(Brush.radialGradient(listOf(if (on) Color(0x55FFFFFF) else Color(0x33000000), Color.Transparent), center = center, radius = r), r)
}

/** The TV focus box (from Rick's photo): dark glass, a cool off-white edge, soft round corners. */
val TvFocusFill = Color(0x9E0E121C)
val TvFocusEdge = Color(0xEBE2E9F5)
val TvFocusShape = RoundedCornerShape(14.dp)

/** A TV control (Quality, Audio & Subtitles, Watch Next, ❯): text with the focus box when the remote is on it. */
@Composable
private fun TvPill(focused: Boolean, icon: ImageVector, label: String?, value: String?, modifier: Modifier = Modifier, iconRotation: Float = 0f) {
    Row(
        modifier.clip(TvFocusShape).background(if (focused) TvFocusFill else Color.Transparent)
            .border(2.dp, if (focused) TvFocusEdge else Color.Transparent, TvFocusShape)
            .padding(horizontal = if (label == null) 9.dp else 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp).graphicsLayer { rotationZ = iconRotation }, tint = Color.White)
        if (label != null) Text(label, Modifier.padding(start = 7.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        if (value != null) Text(value, Modifier.padding(start = 6.dp), fontSize = 14.sp, color = Color(0xFFD5D9E2))
    }
}

/** A tile in the TV ❯ More bubble (focusable: the remote moves between them). */
@Composable
private fun TvTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Row(
        modifier.width(186.dp).onFocusChanged { f = it.isFocused }.clip(TvFocusShape)
            .background(if (f) TvFocusFill else Color(0x12FFFFFF)).border(2.dp, if (f) TvFocusEdge else Color.Transparent, TvFocusShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(19.dp), tint = Color(0xFFE6E8EE))
        Text(label, Modifier.padding(start = 9.dp), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 6dp rounded seek bar: white = watched, grey = buffered. Tap or drag to seek.
 * While dragging (phone) or moving a remote seek target ([pending], TV) a preview frame shows above it.
 */
@Composable
private fun SeekBar(pos: Long, buffered: Long, dur: Long, thumbs: SeekThumbs?, pending: Long?, focused: Boolean = false, onScrub: (Boolean) -> Unit = {}, onSeek: (Long) -> Unit) {
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    // Read through State so a changing duration never restarts (= cancels) a drag in progress.
    val durNow = rememberUpdatedState(dur)
    val scrub = rememberUpdatedState(onScrub)
    val seek = rememberUpdatedState(onSeek)
    val frac = dragFrac ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val bFrac = if (dur > 0) (buffered.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val pendFrac = if (pending != null && dur > 0) (pending.toFloat() / dur).coerceIn(0f, 1f) else null
    val previewFrac = dragFrac ?: pendFrac
    val previewMs = if (previewFrac != null && dur > 0) (dragFrac?.let { (it * dur).toLong() } ?: pending ?: 0L) else null
    Box(Modifier.fillMaxWidth().height(22.dp)) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { o -> val d = durNow.value; if (d > 0) seek.value((o.x / size.width * d).toLong()) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFrac = (o.x / size.width).coerceIn(0f, 1f); scrub.value(true) },
                        onDragEnd = {
                            val d = durNow.value
                            dragFrac?.let { if (d > 0) seek.value((it * d).toLong()) }
                            dragFrac = null; scrub.value(false)
                        },
                        onDragCancel = { dragFrac = null; scrub.value(false) },
                    ) { change, _ -> change.consume(); dragFrac = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
        ) {
            val h = (if (focused) 7 else 5).dp.toPx(); val y = (size.height - h) / 2f; val r = CornerRadius(h / 2f)
            drawRoundRect(Color(0x4DFFFFFF), Offset(0f, y), Size(size.width, h), r)
            drawRoundRect(Color(0x80FFFFFF), Offset(0f, y), Size(size.width * bFrac, h), r)
            drawRoundRect(Color.White, Offset(0f, y), Size(size.width * frac, h), r)
            if (dragFrac != null) drawCircle(Color.White, 8.dp.toPx(), Offset(size.width * frac, size.height / 2f))
            else if (pendFrac != null) {
                // Remote seek target: the jump shown as a light span, with a white marker where it lands.
                val a = minOf(frac, pendFrac); val b = maxOf(frac, pendFrac)
                drawRoundRect(Color(0x99FFFFFF), Offset(size.width * a, y), Size(size.width * (b - a), h), r)
                drawRoundRect(Color.White, Offset(size.width * pendFrac - 1.5.dp.toPx(), size.height / 2f - 8.dp.toPx()), Size(3.dp.toPx(), 16.dp.toPx()), CornerRadius(1.5.dp.toPx()))
                drawCircle(if (focused) BrandRed else Color.White, (if (focused) 9 else 6).dp.toPx(), Offset(size.width * pendFrac, size.height / 2f))
            } else if (focused) {
                // Seek bar has the remote's focus: a red dot where you are (◀ ▶ = 10 s).
                drawCircle(Color(0x55E50914), 15.dp.toPx(), Offset(size.width * frac, size.height / 2f))
                drawCircle(BrandRed, 9.dp.toPx(), Offset(size.width * frac, size.height / 2f))
            }
        }
        if (previewFrac != null && previewMs != null) SeekPreview(thumbs, previewMs, previewFrac, Modifier.align(Alignment.TopStart))
    }
}

/** The preview frame above the seek bar (160×90 on phones, 240×135 on TV), with the time under it. */
@Composable
private fun SeekPreview(thumbs: SeekThumbs?, ms: Long, frac: Float, modifier: Modifier = Modifier) {
    val tv = BuildConfig.IS_TV
    val bucket = ms / 10_000L
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    var imgBucket by remember { mutableLongStateOf(-1L) }
    // Asked for after the finger / remote rests for ~120 ms on a 10-second step; a newer step cancels the older one.
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(thumbs, bucket) {
        val t = thumbs ?: return@LaunchedEffect
        t.cached(ms)?.let { img = it; imgBucket = bucket; loading = false; return@LaunchedEffect }
        loading = true
        delay(90)
        val b = t.frameAt(ms)
        if (b != null) { img = b; imgBucket = bucket }
        loading = false
    }
    // The exact frame, else the closest one already made (so the box is rarely empty while you drag).
    val shown = if (imgBucket == bucket) img else (thumbs?.nearest(ms) ?: img)
    // No empty box: the frame shows when there is one (or while one is on its way); otherwise just the time.
    val withFrame = shown != null || (loading && thumbs?.available == true)
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.layout { m, c ->
            val pl = m.measure(Constraints())
            val full = c.maxWidth
            layout(0, 0) {
                val x = (frac * full - pl.width / 2f).roundToInt().coerceIn(0, (full - pl.width).coerceAtLeast(0))
                pl.place(x, -pl.height - 8.dp.roundToPx())
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (withFrame) Box(
            Modifier.size(if (tv) 240.dp else 160.dp, if (tv) 135.dp else 90.dp).clip(shape).background(Color(0xFF15161B)).border(2.dp, Color.White, shape),
        ) {
            shown?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (loading && imgBucket != bucket && thumbs?.available == true) CircularProgressIndicator(
                Modifier.align(Alignment.Center).size(if (tv) 26.dp else 20.dp), color = Color.White, strokeWidth = 2.dp,
            )
        }
        Text(
            fmtTime(ms),
            Modifier.padding(top = if (withFrame) 6.dp else 0.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xB3000000)).padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = if (tv) 15.sp else 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
        )
    }
}

@Composable
private fun OptionList(options: List<Pair<String, Boolean>>, enabled: List<Boolean> = emptyList(), onPick: (Int) -> Unit) {
    val first = LocalFirstFocus.current
    // Remote: focus starts on the chosen option (else the first one that can be picked).
    val focusAt = options.indices.firstOrNull { options[it].second && enabled.getOrElse(it) { true } }
        ?: options.indices.firstOrNull { enabled.getOrElse(it) { true } } ?: -1
    LazyColumn {
        items(options.size) { i ->
            val (label, on) = options[i]
            val en = enabled.getOrElse(i) { true }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(if (i == focusAt && first != null) Modifier.focusRequester(first) else Modifier)
                .focusFill().clickable(enabled = en) { onPick(i) }.graphicsLayer { alpha = if (en) 1f else 0.4f }.padding(vertical = 13.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), fontSize = 16.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                if (on) Icon(Icons.Default.Check, null, tint = Accent)
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TrackList(tracks: Tracks, trackType: Int, player: ExoPlayer, allowOff: Boolean, offOverride: Boolean = false, onRemember: (String) -> Unit) {
    // Every track is listed by its language only (details behind ⓘ); ones this phone can't decode are greyed out.
    data class Opt(val name: String, val details: List<String>, val group: Tracks.Group, val index: Int, val selected: Boolean, val supported: Boolean, val lang: String?)
    // The live tracks of the player when the remembered list is empty (e.g. a player prepared on the title page).
    val src = if (tracks.groups.none { it.type == trackType } && !player.currentTracks.isEmpty) player.currentTracks else tracks
    val raw = src.groups.filter { it.type == trackType }.flatMap { g ->
        (0 until g.length).map { i ->
            val f = g.getTrackFormat(i)
            val langName = f.language?.let { java.util.Locale(it).displayLanguage.takeIf { d -> d.isNotBlank() } ?: it }
            val name = (langName ?: "Track ${i + 1}")
            val details = buildList {
                // No release-group / site names from the track label — only its kind.
                f.label.orEmpty().lowercase().let { l -> when {
                    "commentary" in l -> "Commentary"; "sdh" in l -> "SDH"; "forced" in l -> "Forced"; "descri" in l -> "Audio description"; else -> null
                } }?.let { add(it) }
                if (trackType == C.TRACK_TYPE_AUDIO) {
                    listOfNotNull(audioCodecName(f.sampleMimeType) ?: f.sampleMimeType?.substringAfter('/')?.uppercase(), f.channelCount.takeIf { it > 0 }?.let { channelsLabel(it) })
                        .joinToString(" · ").takeIf { it.isNotBlank() }?.let { add(it) }
                    listOfNotNull(f.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" }, f.sampleRate.takeIf { it > 0 }?.let { "${"%.1f".format(it / 1000f)} kHz" })
                        .joinToString(" · ").takeIf { it.isNotBlank() }?.let { add(it) }
                } else {
                    f.sampleMimeType?.substringAfter('/')?.uppercase()?.let { add(it) }
                    if ((f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) add("Forced")
                }
                if (!g.isTrackSupported(i)) add("Not supported on this device")
            }
            Opt(name, details, g, i, g.isTrackSelected(i), g.isTrackSupported(i), f.language)
        }
    }
    // Same language twice: numbered ("English", "English 2").
    val seen = HashMap<String, Int>()
    val opts = raw.map { o -> val n = (seen[o.name] ?: 0) + 1; seen[o.name] = n; if (n > 1) o.copy(name = "${o.name} $n") else o }
    val off = player.trackSelectionParameters.disabledTrackTypes.contains(trackType) && !offOverride
    if (opts.isEmpty()) {
        Text(if (trackType == C.TRACK_TYPE_TEXT) "None inside this video." else "Only one audio track.", color = Color(0xFF9AA3B5), fontSize = 13.sp)
        return
    }
    val first = LocalFirstFocus.current
    Column {
        if (allowOff) TrackRow("Off", off, null, true, first) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(trackType, true).build()
            onRemember("off")
        }
        opts.forEachIndexed { i, o ->
            TrackRow(o.name, o.selected && !off, o.details, o.supported, if (!allowOff && i == 0) first else null) {
                val params = player.trackSelectionParameters.buildUpon()
                params.setTrackTypeDisabled(trackType, false).setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
                val lang = o.lang.orEmpty()
                if (trackType == C.TRACK_TYPE_AUDIO) params.setPreferredAudioLanguage(lang.ifEmpty { null }) else params.setPreferredTextLanguage(lang.ifEmpty { null })
                player.trackSelectionParameters = params.build()
                onRemember(lang)
            }
        }
    }
}

/**
 * Audio, all at once and grouped by language: each language is one line with its choices as chips — the tracks inside
 * the playing file (✓ = playing) and the other versions of this title in that language (by quality). No inner scrolling.
 */
/**
 * Audio, everything at once: one line per language — just the name, a small ⓘ with the details (codec, channels,
 * quality, where it comes from) and a red tick on the one playing. Tracks inside the playing file switch at once; other
 * languages play another stream of the title from the same second. Your languages first, then the rest.
 */
@OptIn(UnstableApi::class)
@Composable
private fun AudioByLanguage(
    tracks: Tracks, player: ExoPlayer, order: List<String>, versions: Map<String, List<StreamItem>>, currentUrl: String?,
    currentLangs: List<String>, saved: Set<String>, focus: FocusRequester?,
    onTrack: (String) -> Unit, onVersion: (String, StreamItem) -> Unit,
) {
    class FT(val lang: String, val details: List<String>, val g: Tracks.Group, val i: Int, val sel: Boolean, val ok: Boolean, val code: String?)
    val src = if (tracks.groups.none { it.type == C.TRACK_TYPE_AUDIO } && !player.currentTracks.isEmpty) player.currentTracks else tracks
    val inFile = src.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { g ->
        (0 until g.length).map { i ->
            val f = g.getTrackFormat(i)
            val code = f.language?.takeIf { it.isNotBlank() && it != "und" }
            val lang = code?.let { langName(it) ?: it.lowercase() } ?: currentLangs.firstOrNull() ?: "original"
            val details = buildList {
                add("In this video")
                // Never the track's own label (often the release group / site, e.g. "Vegamovies.To") — only what kind of
                // track it is, when it says so.
                f.label.orEmpty().lowercase().let { l -> when {
                    "commentary" in l -> "Commentary"; "descri" in l || Regex("(^|[^a-z])ad([^a-z]|$)").containsMatchIn(l) -> "Audio description"; else -> null
                } }?.let { add(it) }
                listOfNotNull(audioCodecName(f.sampleMimeType) ?: f.sampleMimeType?.substringAfter('/')?.uppercase(), f.channelCount.takeIf { it > 0 }?.let { channelsLabel(it) })
                    .joinToString(" · ").takeIf { it.isNotBlank() }?.let { add(it) }
                listOfNotNull(f.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" }, f.sampleRate.takeIf { it > 0 }?.let { "${"%.1f".format(it / 1000f)} kHz" })
                    .joinToString(" · ").takeIf { it.isNotBlank() }?.let { add(it) }
                if (!g.isTrackSupported(i)) add("Not supported on this device")
            }
            FT(lang, details, g, i, g.isTrackSelected(i), g.isTrackSupported(i), code)
        }
    }
    val langs = (inFile.map { it.lang } + versions.keys).distinct()
    if (langs.isEmpty()) { Text("Only one audio track.", color = Color(0xFF9AA3B5), fontSize = 13.sp); return }
    val mine = langs.filter { it in order || inFile.any { t -> t.lang == it } }.sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }
    val rest = (langs - mine.toSet()).sorted()
    var focusUsed = false
    fun nextFocus(): FocusRequester? = if (!focusUsed && focus != null) { focusUsed = true; focus } else null
    @Composable
    fun langRows(lang: String) {
        val here = inFile.filter { it.lang == lang }
        if (here.isNotEmpty()) {
            here.forEachIndexed { n, t ->
                TrackRow(langLabel(lang) + if (here.size > 1) " ${n + 1}" else "", t.sel, t.details, t.ok, if (!t.sel) nextFocus() else null) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).setOverrideForType(TrackSelectionOverride(t.g.mediaTrackGroup, t.i))
                        .setPreferredAudioLanguage(t.code).build()
                    onTrack(t.code.orEmpty())
                }
            }
            return
        }
        val playing = inFile.isEmpty() && lang in currentLangs
        val v = versions[lang].orEmpty().firstOrNull { it.stream.url != currentUrl }
        if (playing || v == null) { TrackRow(langLabel(lang), playing, listOf("In this video"), true, null) {}; return }
        val res = v.effectiveRes().takeIf { it > 0 }?.let { if (it >= 2160) "4K" else "${it}p" }
        TrackRow(langLabel(lang), false, listOfNotNull(
            "Another stream of this title", res ?: "Other file",
            if (v.stream.url in saved) "Its start is saved: switches in about a second" else if (v.isPlaylist()) "Starts at once (streaming playlist)" else null,
        ), true, nextFocus()) { onVersion(lang, v) }
    }
    Column {
        mine.forEach { langRows(it) }
        if (rest.isNotEmpty()) {
            Text("OTHER LANGUAGES", Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp), fontSize = 11.sp, letterSpacing = 0.8.sp, color = Color(0xFF6F7890))
            rest.forEach { langRows(it) }
        }
    }
}

@Composable
private fun AudioChip(title: String, sub: String?, selected: Boolean, enabled: Boolean, focus: FocusRequester?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.then(if (focus != null) Modifier.focusRequester(focus) else Modifier).clip(shape)
            .background(if (selected) Color(0x2E5FE0A8) else Color(0x14FFFFFF))
            .border(1.dp, if (selected) Color(0x995FE0A8) else Color(0x1FFFFFFF), shape)
            .focusFill().clickable(enabled = enabled && !selected) { onClick() }.padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) { Icon(Icons.Default.Check, null, Modifier.size(15.dp), tint = Color(0xFF5FE0A8)); Spacer(Modifier.width(6.dp)) }
        Column {
            Text(title, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, color = if (enabled) Color.White else Color(0xFF6B7280), maxLines = 1)
            if (!sub.isNullOrBlank()) Text(sub, fontSize = 11.sp, color = Color(0xFF9AA3B5), maxLines = 1)
        }
    }
}

/** One choice in Audio & Subtitles: the name, a small ⓘ with its details, and a tick when it is the one playing. */
@Composable
private fun TrackRow(name: String, selected: Boolean, details: List<String>?, enabled: Boolean, focus: FocusRequester? = null, ready: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .focusFill().clickable(enabled = enabled && !selected) { onClick() }.padding(vertical = 11.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, Modifier.weight(1f, fill = false), fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (enabled) Color.White else Color(0xFF6B7280), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (ready) Box(Modifier.padding(start = 8.dp).size(6.dp).clip(CircleShape).background(Color(0xFF5FE0A8)))
        if (!details.isNullOrEmpty()) InfoDot(name, details)
        Spacer(Modifier.weight(1f))
        // The one playing: a red tick on the right.
        if (selected) Icon(Icons.Default.Check, null, Modifier.padding(start = 8.dp).size(20.dp), tint = Color(0xFFFF3B47))
    }
}

/** A small ⓘ: tap it and a card with the details springs open beside it (tap anywhere to close). */
@Composable
private fun InfoDot(title: String, lines: List<String>) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.padding(start = 8.dp).size(20.dp).clip(CircleShape).border(1.dp, Color(0x40FFFFFF), CircleShape).clickable { open = !open },
            Alignment.Center,
        ) { Text("i", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB6BDCC)) }
        if (open) androidx.compose.ui.window.Popup(
            alignment = Alignment.TopStart, offset = androidx.compose.ui.unit.IntOffset(0, 70),
            onDismissRequest = { open = false }, properties = androidx.compose.ui.window.PopupProperties(focusable = true),
        ) {
            val a = remember { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(Unit) { a.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 520f)) }
            Column(
                Modifier.graphicsLayer {
                    val k = 0.85f + 0.15f * a.value; scaleX = k; scaleY = k; alpha = a.value.coerceIn(0f, 1f)
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                }.widthIn(min = 190.dp, max = 300.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xF71C1C24))
                    .border(1.dp, Color(0x8CE50914), RoundedCornerShape(14.dp)).clickable { open = false }.padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
                lines.forEach { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFE8E8EE)) }
            }
        }
    }
}

private fun channelsLabel(n: Int) = when (n) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${n}ch" }

@OptIn(UnstableApi::class)
private fun audioCodecName(mime: String?): String? = when (mime) {
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_AC3 -> "Dolby Digital"
    MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+"
    MimeTypes.AUDIO_AC4 -> "AC-4"
    MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
    MimeTypes.AUDIO_TRUEHD -> "TrueHD"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.AUDIO_VORBIS -> "Vorbis"
    else -> null
}

/**
 * Sources inside the player. With Quick Start on there are two tabs: "Quick Start" (your Quick Start addons' links,
 * grouped by addon, the playing one marked) and "All sources" (every other addon, same filters and badges as the
 * sources page).
 */
private val Flags = mapOf("hindi" to "🇮🇳", "english" to "🇺🇸", "tamil" to "🇮🇳", "telugu" to "🇮🇳", "malayalam" to "🇮🇳", "kannada" to "🇮🇳",
    "bengali" to "🇮🇳", "marathi" to "🇮🇳", "punjabi" to "🇮🇳", "korean" to "🇰🇷", "japanese" to "🇯🇵", "spanish" to "🇪🇸", "french" to "🇫🇷",
    "german" to "🇩🇪", "italian" to "🇮🇹", "russian" to "🇷🇺", "portuguese" to "🇵🇹", "arabic" to "🇸🇦", "chinese" to "🇨🇳", "turkish" to "🇹🇷")

/**
 * Dev sources (developer accounts): two sections side by side — Quick play (left) and All addons (right, addon by
 * addon) — every file with its Fusion badges, the full file name, languages with flags, and size / server.
 * ↻ asks every addon again. Tap a file to switch to it.
 */
@kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DevSourcesPanel(vm: AppViewModel, type: String, key: String, playingUrl: String?, quick: List<StreamItem>, meta: Meta?,
                            onSwitch: (StreamItem, List<StreamItem>) -> Unit) {
    val pack by vm.badgePack.collectAsStateWithLifecycle()
    val pctx = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    var qs by remember { mutableStateOf(quick) }
    var all by remember { mutableStateOf<List<StreamItem>>(emptyList()) }
    var csItems by remember { mutableStateOf<List<StreamItem>>(emptyList()) }
    var nuItems by remember { mutableStateOf<List<StreamItem>>(emptyList()) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(key, reload) {
        if (reload > 0) { forgetStreams(key); qs = emptyList() }
        all = emptyList(); done = false
        launch { if (qs.isEmpty()) qs = runCatching { vm.quickStreams(type, key) }.getOrDefault(emptyList()) }
        if (reload > 0) SlowAddons.poll(vm, type, key, forMs = 6_000) else SlowAddons.watch(vm, type, key)
        vm.streamsFlow(type, key, except = if (vm.qsActive()) vm.qsAddons() else null).collect { (l, d) -> all = l; done = d }
    }
    // Plugin mode: the plugins' links come through streamsFlow like an addon's (grouped by provider below).
    val slow by SlowAddons.links.collectAsStateWithLifecycle()
    val others = remember(all, slow[key], qs, csItems, nuItems) {
        val qUrls = qs.mapNotNull { it.stream.url }.toSet()
        (all + slow[key].orEmpty() + csItems + nuItems).distinctBy { it.stream.url }.filter { it.stream.url !in qUrls }.groupBy { it.addonName }.toList()
    }
    // Groups fold: the one holding what's playing starts open, the rest closed (tap a header to open it).
    var openGroups by remember(key) { mutableStateOf<Set<String>?>(null) }
    val playingGroup = remember(others, qs, playingUrl) {
        (qs.firstOrNull { it.stream.url == playingUrl } ?: others.flatMap { it.second }.firstOrNull { it.stream.url == playingUrl })?.addonName
    }
    val opened = openGroups ?: setOfNotNull(playingGroup)
    fun toggle(g: String) { openGroups = if (g in opened) opened - g else opened + g }
    val spin by animateFloatAsState(reload * 360f, tween(700), label = "devspin")
    val allFiles = remember(others) { others.flatMap { it.second } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${qs.size + others.sumOf { it.second.size }} files${if (!done) " · still asking…" else ""}", Modifier.weight(1f), fontSize = 12.sp, color = Color(0xFF8A94A8))
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0x14FFFFFF)).border(1.dp, Color(0x1FFFFFFF), CircleShape).clickable { reload++ }, Alignment.Center) {
                Icon(Icons.Default.Refresh, "Refresh", Modifier.size(18.dp).graphicsLayer { rotationZ = spin })
            }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DevColumn("Quick play", qs.size, Modifier.weight(1f)) {
                if (qs.isEmpty()) item { Text(if (done) "Nothing from quick play" else "Asking…", Modifier.padding(8.dp), fontSize = 12.sp, color = Color(0xFF8A94A8)) }
                qs.groupBy { it.addonName }.forEach { (addon, files) ->
                    item(key = "qh$addon") { DevGroupHeader(addon, files.size, addon in opened, files.any { it.stream.url == playingUrl }) { toggle(addon) } }
                    if (addon in opened) items(files, key = { "q" + it.stream.url }) { s -> DevFileCard(s, s.stream.url == playingUrl, pack) { onSwitch(s, qs) } }
                }
            }
            DevColumn("All addons", others.sumOf { it.second.size }, Modifier.weight(1.35f)) {
                others.forEach { (addon, files) ->
                    item(key = "h$addon") { DevGroupHeader(addon, files.size, addon in opened, files.any { it.stream.url == playingUrl }) { toggle(addon) } }
                    if (addon in opened) items(files, key = { "a" + it.stream.url }) { s -> DevFileCard(s, s.stream.url == playingUrl, pack) { onSwitch(s, allFiles) } }
                }
                if (!done) item { Box(Modifier.fillMaxWidth().padding(10.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) } }
            }
        }
    }
}

/** A foldable addon group in the dev sources: name, file count, ▾ / ▸. */
@Composable
private fun DevGroupHeader(addon: String, n: Int, open: Boolean, playingHere: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (playingHere) Color(0x14E50914) else Color(0x0AFFFFFF))
        .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp), tint = Color(0xFFC9CED9))
        Text(addonLabel(addon), Modifier.weight(1f).padding(start = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC9CED9),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (playingHere) Text("▶ ", fontSize = 11.sp, color = Color(0xFFFF8D94))
        Text("$n", fontSize = 11.sp, color = Color(0xFF8A94A8))
    }
}

@Composable
private fun DevColumn(title: String, n: Int, modifier: Modifier, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Color(0x08FFFFFF)).border(1.dp, Color(0x0FFFFFFF), RoundedCornerShape(16.dp))) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text("  $n files", fontSize = 11.sp, color = Color(0xFF8A94A8))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DevFileCard(s: StreamItem, playing: Boolean, pack: List<PackBadge>, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val badges = remember(s, pack) { s.badges(pack) }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(if (playing) Color(0x1FE50914) else Color(0xFF15161B))
            .border(1.dp, if (playing) Color(0xD9E50914) else Color(0x0DFFFFFF), shape).clickable(enabled = !playing, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 8.dp),
    ) {
        if (badges.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 5.dp)) {
            badges.forEach { BadgeChip(it) }
        }
        val fname = s.fileName() ?: s.stream.title?.lineSequence()?.firstOrNull() ?: s.stream.name ?: ""
        Text(fname, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFE2E5EC))
        val langs = s.languagesOrOriginal().joinToString("  ") { l -> listOfNotNull(Flags[l], langLabel(l)).joinToString(" ") }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(langs, Modifier.weight(1f), fontSize = 11.5.sp, color = Color(0xFFC9CED9), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (playing) Text("▶ Playing", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF8D94))
        }
        Text(listOfNotNull(addonLabel(s.addonName), VisualTags.size(s), hostOf(s.stream.url).takeIf { it.isNotBlank() }).joinToString(" · "),
            fontSize = 10.sp, color = Color(0xFF8A94A8), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SourcesPanel(
    vm: AppViewModel, type: String, key: String, playingUrl: String?, except: Set<String>? = null,
    quick: List<StreamItem> = emptyList(), onSwitch: (StreamItem, List<StreamItem>) -> Unit,
) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    val pack by vm.badgePack.collectAsStateWithLifecycle()
    val hasQuick = except != null
    var qs by remember { mutableStateOf(quick) }
    LaunchedEffect(key, hasQuick) {
        if (hasQuick && qs.isEmpty()) qs = runCatching { vm.quickStreams(type, key) }.getOrDefault(emptyList())
    }
    var tab by remember { mutableStateOf(if (hasQuick && (playingUrl == null || quick.isEmpty() || quick.any { it.stream.url == playingUrl })) 0 else 1) }
    val first = LocalFirstFocus.current
    Column {
        if (hasQuick) {
            Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Quick Start", "All sources").forEachIndexed { i, label ->
                    val sel = tab == i
                    Text(
                        label,
                        Modifier.clip(RoundedCornerShape(18.dp))
                            .then(if (sel && first != null) Modifier.focusRequester(first) else Modifier)
                            .focusRing(RoundedCornerShape(18.dp))
                            .background(if (sel) Color.White else Color(0x1FFFFFFF))
                            .clickable { tab = i }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (sel) Color.Black else Color.White,
                    )
                }
            }
        }
        if (hasQuick && tab == 0) QuickSourcesList(vm, qs, playingUrl, pack, texts) { st -> onSwitch(st, qs) }
        else CompositionLocalProvider(LocalFirstFocus provides (if (hasQuick) null else first)) {
            AllSourcesList(vm, type, key, playingUrl, except, texts, pack, onSwitch)
        }
    }
}

/** Quick Start links grouped by addon: which one is playing, and what else each addon has. */
@Composable
private fun QuickSourcesList(vm: AppViewModel, list: List<StreamItem>, playingUrl: String?, pack: List<PackBadge>, texts: Map<String, String>, onPick: (StreamItem) -> Unit) {
    val fusion = isOn(texts["fusionBadges"] ?: Defaults["fusionBadges"] ?: "yes")
    if (list.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(30.dp), Alignment.Center) { CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp) }
        return
    }
    val groups = remember(list) { list.groupBy { it.addonName } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        groups.forEach { (addon, links) ->
            item(key = "h:$addon") {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(addon, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB6BDCC), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val playingHere = links.any { it.stream.url == playingUrl }
                    Text(if (playingHere) "Playing · ${links.size} links" else "${links.size} links", fontSize = 12.sp,
                        color = if (playingHere) Accent else Color(0xFF8A94A8))
                }
            }
            itemsIndexed(links, key = { i, it -> "q:$addon:$i:${it.stream.url}" }) { _, s ->
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(16.dp)
                Box(Modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }.border(2.dp, if (focused) Color.White else Color.Transparent, shape)) {
                    StreamCard(s, fusion, true, pack, playing = s.stream.url == playingUrl, compact = true) { if (s.stream.url != playingUrl) onPick(s) }
                }
            }
        }
    }
}

@Composable
private fun AllSourcesList(
    vm: AppViewModel, type: String, key: String, playingUrl: String?, except: Set<String>?, texts: Map<String, String>,
    pack: List<PackBadge>, onSwitch: (StreamItem, List<StreamItem>) -> Unit,
) {
    var all by remember { mutableStateOf<List<StreamItem>>(emptyList()) }
    var done by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    LaunchedEffect(key) { vm.streamsFlow(type, key, except = except).collect { (l, d) -> all = l; done = d } }
    // Links a slow-server addon (CNCVerse) finds later appear here by themselves.
    LaunchedEffect(key) { SlowAddons.watch(vm, type, key) }
    val slow by SlowAddons.links.collectAsStateWithLifecycle()
    val merged = remember(all, slow[key]) { (all + slow[key].orEmpty().filter { except == null || it.addonName !in except }).distinctBy { it.stream.url } }
    // While a quick play is on: only servers that play (Source Test ranking order) — dead, keeps-failing and web-page
    // servers (hubcloud & co.) are left out.
    val playable = remember(merged, except) {
        if (except == null) merged
        else merged.filter { !Ranker.isDead(it) && !Ranker.keepsFailing(it) && HostStats.penalty(it.stream.url.orEmpty()) == 0 }
            .sortedByDescending { Ranker.score(it) }
    }
    val f = remember(playable, texts["tagRules"], showAll) { if (showAll) Filtered(playable, 0, false, emptyList()) else vm.filtered(playable) }
    val fusion = isOn(texts["fusionBadges"] ?: Defaults["fusionBadges"] ?: "yes")
    val showAddon = isOn(texts["showAddonName"] ?: "yes")
    val first = LocalFirstFocus.current
    when {
        f.forcedEmpty -> Column {
            Text("No ${f.forcedLabels.joinToString(" / ")} streams for this title.", color = Color(0xFF9AA3B5))
            LoadBtn("Show all streams", true, first?.let { Modifier.focusRequester(it) } ?: Modifier) { showAll = true }
        }
        f.list.isEmpty() && done -> Text("No sources found.", color = Color(0xFF9AA3B5))
        f.list.isEmpty() -> Box(Modifier.fillMaxWidth().padding(30.dp), Alignment.Center) { CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp) }
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Remote: focus starts on the first card that isn't the one playing.
            val focusUrl = f.list.firstOrNull { it.stream.url != playingUrl }?.stream?.url
            items(f.list) { s ->
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(16.dp)
                Box(
                    Modifier.fillMaxWidth().then(if (first != null && s.stream.url == focusUrl) Modifier.focusRequester(first) else Modifier)
                        .onFocusChanged { focused = it.hasFocus }
                        .border(2.dp, if (focused) Color.White else Color.Transparent, shape),
                ) {
                    StreamCard(s, fusion, showAddon, pack, playing = s.stream.url == playingUrl, compact = true) { onSwitch(s, f.list) }
                }
            }
            if (!done) item { Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp) } }
        }
    }
}

/**
 * Audio & Subtitles › Subtitles: two stacks — "In this video" (open) and "Online · <your languages>": only the
 * languages chosen in Settings › Playback are fetched, the first three are downloaded ahead (green dot) and switch
 * without any loading.
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SubtitlesColumn(
    vm: AppViewModel, type: String, key: String, loaded: List<Subtitle>, activeUrl: String?, hasInside: Boolean,
    tracks: @Composable () -> Unit, scroll: Boolean = true,
    /** ExoPlayer only (null with VLC: the old track list is shown instead). */
    exo: Pair<Tracks, ExoPlayer>? = null, onInside: (String) -> Unit = {}, syncNote: String = "",
    onPick: (Subtitle) -> Unit,
) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    val langSetting = texts["subLangs"] ?: Defaults["subLangs"] ?: "english"
    val wanted = remember(langSetting) { subLangCodes(langSetting) }
    var online by remember { mutableStateOf<List<Subtitle>?>(null) }
    var ready by remember { mutableStateOf(setOf<String>()) }
    var insideOpen by remember { mutableStateOf(true) }
    var onlineOpen by remember { mutableStateOf(!hasInside || activeUrl != null) }
    LaunchedEffect(key, wanted) {
        val l = runCatching { vm.onlineSubtitles(type, key) }.getOrDefault(emptyList())
            .filter { o -> loaded.none { it.url == o.url } && wanted.any { w -> o.lang.lowercase().startsWith(w) } }
            .sortedBy { o -> wanted.indexOfFirst { w -> o.lang.lowercase().startsWith(w) } }
        online = l
        ready = l.filter { ExtSubs.has(it.url) }.map { it.url }.toSet()
        // The first three are downloaded ahead: picking one then shows it at once.
        for (sub in l.take(3)) if (ExtSubs.load(sub.url) != null) ready = ready + sub.url
    }
    val langTitle = langSetting.split(",").map { it.trim().replaceFirstChar { c -> c.uppercase() } }.filter { it.isNotEmpty() }.joinToString(" · ")
    Column(Modifier.fillMaxWidth().then(if (scroll) Modifier.fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState()) else Modifier)) {
        // The Subtitles column in focus: a red-edged header card saying what shows now and what auto sync is doing.
        run {
            val insideOn = exo?.let { (tr, pl) ->
                val src = if (tr.groups.none { it.type == C.TRACK_TYPE_TEXT } && !pl.currentTracks.isEmpty) pl.currentTracks else tr
                src.groups.filter { it.type == C.TRACK_TYPE_TEXT }.firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
            }
            val showing = when {
                activeUrl != null -> (online?.firstOrNull { it.url == activeUrl }?.lang?.let { subLangName(it) } ?: "Online") + " · online"
                insideOn != null -> (insideOn.language?.let { subLangName(it) } ?: insideOn.label ?: "On") + " · in the video"
                else -> "Off"
            }
            val shape = RoundedCornerShape(16.dp)
            Column(Modifier.padding(top = 10.dp, bottom = 4.dp).fillMaxWidth().clip(shape)
                .background(Brush.horizontalGradient(listOf(Color(0x33E50914), Color(0x0DFFFFFF)))).border(1.dp, Color(0x66E50914), shape)
                .padding(horizontal = 14.dp, vertical = 11.dp)) {
                Text("Subtitles", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Showing: $showing", Modifier.padding(top = 2.dp), fontSize = 13.sp, color = Color(0xFFD5D8DF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (activeUrl != null && syncNote.isNotBlank()) Text(syncNote, fontSize = 12.sp,
                    color = if (syncNote.contains("✓")) Color(0xFF5FE0A8) else Color(0xFFFFB4B9))
            }
        }
        if (exo == null) {
            // VLC: its own list of the tracks inside, then the online ones below.
            SubStack("In this video", null, true, {}) { tracks() }
        }
        // Two stacks as before: "In this video" (Off + the ones inside, picked automatically) and "Online · <languages>"
        // (used only when the video has none). Each row: the language, a small ⓘ, a red tick on the one showing.
        val list = online
        var sidecars: List<Pair<Tracks.Group, Int>> = emptyList()
        if (exo != null) {
            val (tr, player) = exo
            val src = if (tr.groups.none { it.type == C.TRACK_TYPE_TEXT } && !player.currentTracks.isEmpty) player.currentTracks else tr
            val off = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) && activeUrl == null
            val allText = src.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g -> (0 until g.length).map { g to it } }
            val inside = allText.filter { (g, i) -> !g.getTrackFormat(i).isSidecar() }
            sidecars = allText.filter { (g, i) -> g.getTrackFormat(i).isSidecar() && wanted.any { w -> g.getTrackFormat(i).language.orEmpty().lowercase().startsWith(w) } }
            val anyInsideOn = allText.any { (g, i) -> g.isTrackSelected(i) } && activeUrl == null && !off
            SubStack("In this video", inside.size.takeIf { it > 0 }?.toString(), insideOpen, { insideOpen = !insideOpen }) {
                TrackRow("Off", off || (!anyInsideOn && activeUrl == null), null, true) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    onInside("off")
                }
                if (inside.isEmpty()) Text("None inside this video.", Modifier.padding(horizontal = 8.dp, vertical = 6.dp), fontSize = 13.sp, color = Color(0xFF9AA3B5))
                val seen = HashMap<String, Int>()
                inside.forEach { (g, i) ->
                    val f = g.getTrackFormat(i)
                    // Just the language and its kind (SDH / Forced / CC) — never the provider's name from the label.
                    val lbl = f.label.orEmpty().lowercase()
                    val forced = (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 || "forced" in lbl
                    val kind = when { forced -> "Forced"; "sdh" in lbl -> "SDH"; Regex("(^|[^a-z])cc([^a-z]|$)").containsMatchIn(lbl) -> "CC"; else -> null }
                    val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { subLangName(it) }
                        ?: QsLanguages.entries.firstOrNull { (_, ws) -> ws.any { w -> w.length > 2 && w in lbl } }?.key?.let { langLabel(it) } ?: "Subtitles"
                    val base = lang + (kind?.let { " – $it" } ?: "")
                    val n = (seen[base] ?: 0) + 1; seen[base] = n
                    TrackRow(if (n > 1) "$base $n" else base, g.isTrackSelected(i) && activeUrl == null && !off,
                        listOfNotNull("Inside the video", kind, f.sampleMimeType?.substringAfter('/')?.uppercase()),
                        g.isTrackSupported(i)) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).setPreferredTextLanguage(f.language).build()
                        onInside(f.language.orEmpty())
                    }
                }
            }
        }
        SubStack("Online · $langTitle", ((list?.size ?: 0) + sidecars.size).takeIf { list != null || sidecars.isNotEmpty() }?.toString(), onlineOpen, { onlineOpen = !onlineOpen }) {
            // Subtitles that came with the link / from your subtitle addons (already loaded: switch at once).
            if (exo != null) {
                val pl = exo.second
                val seenS = HashMap<String, Int>()
                sidecars.forEach { (g, i) ->
                    val f = g.getTrackFormat(i)
                    val lang = f.language?.let { subLangName(it) } ?: "Subtitles"
                    val n = (seenS[lang] ?: 0) + 1; seenS[lang] = n
                    // Shown by the app now (sidecars become app-drawn subtitles): its tick follows that, and tapping it
                    // again does nothing (it used to switch to the player's own copy too — two subtitles flickering).
                    val src = loaded.firstOrNull { s -> f.id?.contains("side:" + s.url.hashCode()) == true }
                    val onApp = src != null && activeUrl != null && sameSubUrl(src.url, activeUrl)
                    TrackRow(if (n > 1) "$lang $n" else lang, onApp || (g.isTrackSelected(i) && activeUrl == null), listOf("Online · loaded with the video", "Switches at once"), true, ready = true) {
                        if (onApp) return@TrackRow
                        if (src != null && activeUrl != null) { onPick(src); return@TrackRow }
                        pl.trackSelectionParameters = pl.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).setPreferredTextLanguage(f.language).build()
                        onInside(f.language.orEmpty())
                    }
                }
            }
            when {
                list == null && sidecars.isEmpty() -> Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Text("Looking for $langTitle subtitles…", Modifier.padding(start = 10.dp), fontSize = 13.sp, color = Color(0xFF9AA3B5))
                }
                list == null -> Unit
                list.isEmpty() && sidecars.isEmpty() -> Text("None found in $langTitle (Settings › Playback › Subtitle languages).", Modifier.padding(8.dp), fontSize = 13.sp, color = Color(0xFF9AA3B5))
                list.isEmpty() -> Unit
                else -> {
                    val count = HashMap<String, Int>()
                    // The subtitle playing came from another list (auto pick) or a link written differently: it's still
                    // shown first with its tick, so it's clear which one is on.
                    val shown = list.take(30)
                    if (activeUrl != null && shown.none { sameSubUrl(it.url, activeUrl) })
                        TrackRow((online?.firstOrNull { sameSubUrl(it.url, activeUrl) }?.lang?.let { subLangName(it) } ?: "Online") + " · in use", true, listOf("Auto sync on"), true, ready = true) { }
                    shown.forEach { sub ->
                        val src = when {
                            sub.url.contains("opensubtitles", true) || sub.url.contains("strem.io", true) -> "OpenSubtitles"
                            sub.id?.isNotBlank() == true -> sub.id.substringBefore(':').substringBefore('_').take(14).ifBlank { "Online" }
                            else -> "Online"
                        }
                        val lang = subLangName(sub.lang)
                        val n = (count[lang] ?: 0) + 1; count[lang] = n
                        val on = activeUrl != null && sameSubUrl(sub.url, activeUrl)
                        TrackRow(if (n > 1) "$lang $n" else lang, on,
                            listOfNotNull(src, if (sub.url in ready) "Downloaded · switches at once" else "Downloads when you pick it", if (on) "Auto sync on" else null),
                            true, ready = sub.url in ready || on) { if (!on) onPick(sub) }
                    }
                }
            }
        }
        Text("Online languages: $langTitle — change in Settings › Playback › Subtitle languages.", Modifier.padding(top = 12.dp), fontSize = 11.sp, color = Color(0xFF8A94A8))
        Spacer(Modifier.height(16.dp))
    }
}

/** Two links to the same subtitle file (one with %-escapes or extra parameters) count as one. */
fun sameSubUrl(a: String, b: String): Boolean {
    if (a == b) return true
    fun n(u: String) = runCatching { java.net.URLDecoder.decode(u, "UTF-8") }.getOrDefault(u).substringBefore('#').trimEnd('/')
    return n(a) == n(b)
}

/** A stack in the Subtitles column: a header you tap to open or close, its rows inside. */
@Composable
private fun SubStack(title: String, count: String?, open: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape).border(1.dp, Color(0x1AFFFFFF), shape)) {
        Row(
            Modifier.fillMaxWidth().background(Color(0x0DFFFFFF)).focusFill().clickable { onToggle() }.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            count?.let { Text(it, Modifier.padding(end = 6.dp), fontSize = 12.sp, color = Color(0xFF8A94A8)) }
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = Color(0xFFB6BDCC))
        }
        AnimatedVisibility(open) { Column(Modifier.padding(6.dp)) { content() } }
    }
}

/**
 * Audio & Subtitles › Style & timing: Adjust timing (online subtitles, by 0.1 s), Size as a slider (while you drag it
 * the panel fades away and sample lines show on the video at the real size), then colour, background and text.
 */
@Composable
private fun SubStyleColumn(
    vm: AppViewModel, offsetMs: Long, extActive: Boolean, onOffset: (Long) -> Unit, onPreview: (Boolean) -> Unit, scroll: Boolean = true,
    onSyncNow: () -> Unit = {}, onAuto: (Boolean) -> Unit = {}, syncNote: String = "",
) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    fun v(k: String) = texts[k] ?: Defaults[k] ?: ""
    Column(Modifier.fillMaxWidth().then(if (scroll) Modifier.fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState()) else Modifier)) {
        SubSection("Style & timing")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Adjust timing", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text((if (offsetMs > 0) "+" else "") + "%.1f s".format(offsetMs / 1000f), fontSize = 13.sp, color = Color(0xFFB6BDCC))
        }
        // − slider + : the buttons move it 0.1 s per tap (hold to keep moving).
        Row(verticalAlignment = Alignment.CenterVertically) {
            @Composable
            fun step(label: String, d: Long) {
                val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val held by src.collectIsPressedAsState()
                val cur by rememberUpdatedState(offsetMs)
                LaunchedEffect(held) { if (held) { delay(450); while (true) { onOffset((cur + d).coerceIn(-10_000L, 10_000L)); delay(90) } } }
                Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0x9E14141A)).border(1.dp, Color(0x8CE50914), CircleShape)
                    .clickable(enabled = extActive, interactionSource = src, indication = null) { onOffset((offsetMs + d).coerceIn(-10_000L, 10_000L)) }
                    .graphicsLayer { alpha = if (extActive) 1f else 0.4f },
                    Alignment.Center) { Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }
            step("−", -100L)
            if (BuildConfig.IS_TV) TvSlider(offsetMs / 1000f, { onOffset((it * 10).roundToInt() * 100L) }, -10f..10f, 0.1f, Modifier.weight(1f).padding(horizontal = 6.dp), enabled = extActive)
            else Slider(
                offsetMs / 1000f, { onOffset((it * 10).roundToInt() * 100L) }, Modifier.weight(1f).padding(horizontal = 6.dp), valueRange = -10f..10f, enabled = extActive,
                colors = SliderDefaults.colors(thumbColor = Color(0xFFE50914), activeTrackColor = Color(0xFFE50914), inactiveTrackColor = Color(0x33FFFFFF)),
            )
            step("+", 100L)
        }
        // Auto sync (listens to the speech and slides the subtitles to it) + a button to line them up right now.
        val auto = isOn(v("subAutoSync"))
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto sync", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(if (!auto) "Off" else syncNote.ifBlank { "Lines them up with the speech every 30 s" }, fontSize = 11.sp,
                    color = if (syncNote.contains("✓")) Color(0xFF5FE0A8) else Color(0xFF8A94A8))
            }
            androidx.compose.material3.Switch(auto, { vm.setText("subAutoSync", if (it) "yes" else "no"); onAuto(it) },
                colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Color(0xFFE50914), checkedThumbColor = Color.White))
        }
        Text("Sync now", Modifier.padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x1FE50914)).border(1.dp, Color(0x59E50914), RoundedCornerShape(14.dp))
            .clickable(enabled = extActive) { onSyncNow() }.padding(horizontal = 14.dp, vertical = 8.dp).graphicsLayer { alpha = if (extActive) 1f else 0.4f },
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(if (extActive) "Subtitles early? Slide right. Late? Slide left." else "For online subtitles (pick one in the Online stack).",
            fontSize = 11.sp, color = Color(0xFF8A94A8))
        Spacer(Modifier.height(10.dp))
        val size = v("subSize").toFloatOrNull() ?: 20f
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Size", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("${(size / 20f * 100).roundToInt()}%", fontSize = 13.sp, color = Color(0xFFB6BDCC))
        }
        val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val dragged by src.collectIsDraggedAsState()
        val pressed by src.collectIsPressedAsState()
        LaunchedEffect(dragged || pressed) { if (dragged || pressed) onPreview(true) else { delay(700); onPreview(false) } }
        if (BuildConfig.IS_TV) TvSlider(size, { vm.setText("subSize", it.roundToInt().toString()) }, 12f..40f, 1f)
        else Slider(
            size, { vm.setText("subSize", it.roundToInt().toString()) }, valueRange = 12f..40f, interactionSource = src,
            colors = SliderDefaults.colors(thumbColor = Color(0xFFE50914), activeTrackColor = Color(0xFFE50914), inactiveTrackColor = Color(0x33FFFFFF)),
        )
        SubSection("Look")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubChip("Netflix style", isOn(v("subNetflix"))) { vm.setText("subNetflix", if (isOn(v("subNetflix"))) "no" else "yes") }
            if (!BuildConfig.IS_TV) SubChip("Follow the speaker · beta", isOn(v("subFollow"))) { vm.setText("subFollow", if (isOn(v("subFollow"))) "no" else "yes") }
        }
        if (isOn(v("subNetflix"))) Text("Netflix style sets the font, colour and shadow; turn it off to choose your own below.", fontSize = 11.5.sp, color = Color(0xFF8A94A8))
        SubSection("Text colour")
        SubChips(listOf("White" to "#FFFFFF", "Yellow" to "#FFE14D", "Cyan" to "#6FE3FF", "Green" to "#7CFF8A", "Pink" to "#FF8FB1"), v("subColor").uppercase()) { vm.setText("subColor", it) }
        SubSection("Background")
        SubChips(listOf("None" to "#00000000", "Shadow" to "#80000000", "Black" to "#FF000000", "Grey" to "#B3333333"), v("subBg").uppercase()) { vm.setText("subBg", it) }
        SubSection("Text")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubChip("Bold", isOn(v("subBold"))) { vm.setText("subBold", if (isOn(v("subBold"))) "no" else "yes") }
            SubChip("Outline", isOn(v("subOutline"))) { vm.setText("subOutline", if (isOn(v("subOutline"))) "no" else "yes") }
        }
        if (isOn(v("subOutline"))) {
            SubSection("Outline colour")
            SubChips(listOf("Black" to "#000000", "Dark grey" to "#333333", "Red" to "#B00020", "Blue" to "#0A3D91"), v("subOutlineColor").uppercase()) { vm.setText("subOutlineColor", it) }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** "english, hindi" → language-code prefixes to match subtitle languages ("en", "eng", "hi", "hin"…). */
fun subLangCodes(setting: String): List<String> = setting.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.flatMap { name ->
    val code = java.util.Locale.getAvailableLocales().firstOrNull { it.getDisplayLanguage(java.util.Locale.ENGLISH).equals(name, true) }?.language
    listOfNotNull(code, runCatching { code?.let { java.util.Locale(it).isO3Language } }.getOrNull(), name.take(3))
}.distinct()

/**
 * Subtitles panel: the tracks in this video, "Find subtitles online" (your subtitle addons + OpenSubtitles, preferred
 * languages first) and the look of the subtitles (size, colour, background, bold, outline) — changes show at once.
 */
@Composable
private fun SubsPanel(
    vm: AppViewModel, type: String, key: String, loaded: List<Subtitle>,
    tracks: @Composable () -> Unit, onPick: (Subtitle) -> Unit,
) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    fun v(k: String) = texts[k] ?: Defaults[k] ?: ""
    var online by remember { mutableStateOf<List<Subtitle>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var openLang by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val prefs = remember(texts["qsLangs"]) {
        (listOf("en", "eng", "english") + v("qsLangs").split(",").map { it.trim().lowercase() }).distinct()
    }

    Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
        SubSection("In this video")
        Column(Modifier.heightIn(max = 230.dp)) { tracks() }

        SubSection("Find subtitles online")
        when {
            online == null -> SubChip(if (searching) "Searching…" else "Search OpenSubtitles & your addons", selected = false) {
                if (!searching) {
                    searching = true
                    scope.launch {
                        online = runCatching { vm.onlineSubtitles(type, key) }.getOrDefault(emptyList())
                            .filter { o -> loaded.none { it.url == o.url } }
                        searching = false
                    }
                }
            }
            online!!.isEmpty() -> Text("No more subtitles found for this title.", fontSize = 13.sp, color = Color(0xFF9AA3B5))
            else -> {
                val byLang = remember(online) {
                    val rank = { code: String -> val l = code.lowercase(); prefs.indexOfFirst { p -> l.startsWith(p.take(2)) }.let { if (it < 0) 99 else it } }
                    online!!.groupBy { subLangName(it.lang) }.entries.toList()
                        .sortedWith(compareBy<Map.Entry<String, List<Subtitle>>> { rank(it.value.first().lang) }.thenBy { it.key })
                }
                Text("${online!!.size} found · tap one to add it", fontSize = 12.sp, color = Color(0xFF8A94A8), modifier = Modifier.padding(bottom = 6.dp))
                byLang.forEach { (lang, list) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).focusFill().clickable { openLang = if (openLang == lang) null else lang }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(lang, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("${list.size}", fontSize = 12.sp, color = Color(0xFF8A94A8))
                        Icon(if (openLang == lang) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = Color(0xFFB6BDCC))
                    }
                    if (openLang == lang) list.take(25).forEachIndexed { i, sub ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).focusFill().clickable { onPick(sub) }
                                .padding(vertical = 9.dp, horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.ClosedCaption, null, Modifier.size(16.dp), tint = Color(0xFFB6BDCC))
                            Spacer(Modifier.width(10.dp))
                            Text("$lang ${i + 1}", Modifier.weight(1f), fontSize = 14.sp)
                            Text(if (sub.url.contains("opensubtitles", true)) "OpenSubtitles" else "Addon", fontSize = 11.sp, color = Color(0xFF8A94A8))
                        }
                    }
                }
            }
        }

        SubSection("Size")
        SubChips(listOf("Small" to "16", "Medium" to "20", "Large" to "26", "Extra large" to "32"), v("subSize")) { vm.setText("subSize", it) }
        SubSection("Look")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubChip("Netflix style", isOn(v("subNetflix"))) { vm.setText("subNetflix", if (isOn(v("subNetflix"))) "no" else "yes") }
            if (!BuildConfig.IS_TV) SubChip("Follow the speaker · beta", isOn(v("subFollow"))) { vm.setText("subFollow", if (isOn(v("subFollow"))) "no" else "yes") }
        }
        if (isOn(v("subNetflix"))) Text("Netflix style sets the font, colour and shadow; turn it off to choose your own below.", fontSize = 11.5.sp, color = Color(0xFF8A94A8))
        SubSection("Text colour")
        SubChips(listOf("White" to "#FFFFFF", "Yellow" to "#FFE14D", "Cyan" to "#6FE3FF", "Green" to "#7CFF8A", "Pink" to "#FF8FB1"), v("subColor").uppercase()) { vm.setText("subColor", it) }
        SubSection("Background")
        SubChips(listOf("None" to "#00000000", "Shadow" to "#80000000", "Black" to "#FF000000", "Grey" to "#B3333333"), v("subBg").uppercase()) { vm.setText("subBg", it) }
        SubSection("Style")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubChip("Bold", isOn(v("subBold"))) { vm.setText("subBold", if (isOn(v("subBold"))) "no" else "yes") }
            SubChip("Outline", isOn(v("subOutline"))) { vm.setText("subOutline", if (isOn(v("subOutline"))) "no" else "yes") }
        }
        if (isOn(v("subOutline"))) {
            SubSection("Outline colour")
            SubChips(listOf("Black" to "#000000", "Dark grey" to "#333333", "Red" to "#B00020", "Blue" to "#0A3D91"), v("subOutlineColor").uppercase()) { vm.setText("subOutlineColor", it) }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** "eng" / "en" / "hin" → English / Hindi (falls back to the code). */
private fun subLangName(code: String): String =
    runCatching { java.util.Locale(code.trim()).displayLanguage }.getOrNull()?.takeIf { it.isNotBlank() && !it.equals(code.trim(), true) }
        ?: code.replaceFirstChar { it.uppercase() }

@Composable
private fun SubSection(text: String) =
    Text(text, Modifier.padding(top = 16.dp, bottom = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF8A94A8))

@Composable
private fun SubChips(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
    // Two rows at most, wrapping by hand (no FlowRow needed).
    options.chunked(3).forEach { row ->
        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (label, value) -> SubChip(label, value.equals(current, ignoreCase = true)) { onPick(value) } }
        }
    }
}

@Composable
private fun SubChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Text(
        label,
        modifier.clip(shape).focusRing(shape).background(if (selected) Color.White else Color(0x1FFFFFFF))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color.Black else Color.White, maxLines = 1,
    )
}

/** Episodes pop-up: scales in with a spring, season chips on top, "Now playing" on the current episode. */
@Composable
private fun EpisodesPopup(
    visible: Boolean, episodes: List<Video>, currentId: String, showName: String,
    maxW: androidx.compose.ui.unit.Dp, maxH: androidx.compose.ui.unit.Dp,
    onClose: () -> Unit, watched: (String) -> Float? = { null }, onPick: (Video) -> Unit,
) {
    val prog = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) prog.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 380f)) else prog.animateTo(0f, tween(200))
    }
    if (!visible && prog.value <= 0.01f) return
    val seasons = remember(episodes) { episodes.mapNotNull { it.season }.distinct().sorted() }
    val currentSeason = episodes.firstOrNull { it.id == currentId }?.season ?: seasons.firstOrNull() ?: 1
    var season by remember(currentSeason) { mutableIntStateOf(currentSeason) }
    val list = remember(episodes, season) { episodes.filter { it.season == season } }
    val listState = rememberLazyListState()
    LaunchedEffect(season, visible) {
        val i = list.indexOfFirst { it.id == currentId }
        if (visible && i > 0) listState.scrollToItem((i - 1).coerceAtLeast(0))
    }
    // Remote: focus starts on the episode playing (else the season's first one) and stays inside the pop-up.
    val epFocus = remember { FocusRequester() }
    val popScope = rememberCoroutineScope()
    var popHasFocus by remember { mutableStateOf(false) }
    val focusId = list.firstOrNull { it.id == currentId }?.id ?: list.firstOrNull()?.id
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        popHasFocus = false
        for (i in 0 until 30) { delay(80); if (popHasFocus) break; runCatching { epFocus.requestFocus() } }
    }
    val shape = RoundedCornerShape(28.dp)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = prog.value.coerceIn(0f, 1f) }.background(Color(0xA6000000))
            .noFocus().clickable(interactionSource = noRipple, indication = null) { onClose() })
        Column(
            Modifier.align(Alignment.Center)
                .width((maxW * 0.86f).coerceAtMost(820.dp)).height((maxH * 0.84f))
                .graphicsLayer {
                    val v = prog.value
                    alpha = v.coerceIn(0f, 1f); scaleX = 0.9f + 0.1f * v; scaleY = scaleX
                }
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xF21C1C22), Color(0xF5121216))))
                .border(1.dp, Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x0AFFFFFF))), shape)
                .onFocusChanged {
                    val had = popHasFocus
                    popHasFocus = it.hasFocus
                    if (had && !it.hasFocus) popScope.launch { delay(60); if (visible && !popHasFocus) runCatching { epFocus.requestFocus() } }
                }
                .noFocus()
                .clickable(interactionSource = noRipple, indication = null) {}
                .padding(top = 18.dp, bottom = 10.dp),
        ) {
            Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Episodes", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (showName.isNotBlank()) Text(showName, fontSize = 13.sp, color = Color(0xFF9AA3B5), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0x1FFFFFFF)).focusRing(CircleShape).clickable { onClose() }, Alignment.Center) {
                    Icon(Icons.Default.Close, "Close", Modifier.size(20.dp))
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons) { sn ->
                    val on = sn == season
                    Text(
                        "Season $sn" + if (sn == currentSeason) " •" else "",
                        Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) Color.White else Color(0x1AFFFFFF))
                            .focusRing(RoundedCornerShape(20.dp), 2.dp, if (on) Accent else Color.White).clickable { season = sn }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.Black else Color.White,
                    )
                }
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(list, key = { _, e -> e.id }) { _, ep ->
                    val now = ep.id == currentId
                    val rs = RoundedCornerShape(18.dp)
                    var focused by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().clip(rs).background(if (focused) Color(0x26FFFFFF) else if (now) Accent.copy(alpha = 0.14f) else Color(0x0DFFFFFF))
                            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else if (now) Accent.copy(alpha = 0.7f) else Color.Transparent, rs)
                            .then(if (ep.id == focusId) Modifier.focusRequester(epFocus) else Modifier)
                            .onFocusChanged { focused = it.isFocused }
                            .clickable { onPick(ep) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF22222A))) {
                            AsyncImage(ep.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (now) Box(Modifier.fillMaxSize().background(Color(0x66000000)), Alignment.Center) {
                                Icon(Icons.Default.GraphicEq, null, Modifier.size(28.dp), tint = Color.White)
                            }
                            // How much of this episode you've watched.
                            watched(ep.id)?.takeIf { it > 0.01f }?.let { f ->
                                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
                                    Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(BrandRed))
                                }
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 14.dp, end = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("E${ep.episode}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9AA3B5))
                                if (!now) watched(ep.id)?.takeIf { it > 0.01f }?.let { f ->
                                    Text(if (f >= 0.95f) "Watched" else "${(f * 100).toInt()}% watched", Modifier.padding(start = 8.dp), fontSize = 11.sp, color = Color(0xFFB6BDCC))
                                }
                                if (now) Text("NOW PLAYING", Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp)).background(Accent)
                                    .padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = OnAccent, letterSpacing = 0.5.sp)
                            }
                            Text(ep.title ?: ep.name ?: "Episode ${ep.episode}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp))
                            (ep.overview ?: ep.description)?.takeIf { it.isNotBlank() }?.let {
                                Text(it, fontSize = 12.sp, color = Color(0xFFB6BDCC), maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One card in Watch Next. */
private data class WnItem(val key: String, val title: String, val sub: String?, val image: String?, val ep: Video? = null, val rec: TmRec? = null, val meta: Meta? = null)

/**
 * Watch Next (player): "Up next" episodes of this show, then "More like this" (TMDB recommendations, else your Home rows).
 * Slides up from the bottom; the remote stays inside; Back closes it.
 */
@Composable
private fun WatchNextOverlay(
    vm: AppViewModel, visible: Boolean, meta: Meta?, type: String, key: String, episodes: List<Video>,
    onClose: () -> Unit, onEpisode: (Video) -> Unit,
    // Phones: dragging the sheet (dy px, sheet height px) and letting go (vertical speed, px/s, down = +).
    onDrag: (Float, Float) -> Unit = { _, _ -> }, onDragEnd: (Float) -> Unit = {}, dragging: () -> Boolean = { false },
    onTitle: (Meta) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var similar by remember(key) { mutableStateOf<List<WnItem>?>(null) }
    LaunchedEffect(visible, key) {
        if (!visible || similar != null) return@LaunchedEffect
        val imdb = key.substringBefore(":")
        val fromTmdb = runCatching {
            val info = vm.tmdbInfo(imdb, type)
            if (info == null) emptyList() else vm.likedCandidates(imdb, info).map { r ->
                WnItem("t:${r.kind}${r.tmdbId}", r.title, listOfNotNull(r.year, if (r.kind == "tv") "Series" else "Movie").joinToString(" · "), r.poster, rec = r)
            }
        }.getOrDefault(emptyList())
        similar = fromTmdb.ifEmpty {
            vm.shelves.value.flatMap { it.items }.filter { it.id != imdb && (it.type == "movie" || it.type == "series") }.distinctBy { it.id }.take(20)
                .map { m -> WnItem("m:${m.id}", m.name, listOfNotNull(m.releaseInfo, if (m.type == "series") "Series" else "Movie").joinToString(" · "), m.poster, meta = m) }
        }
    }
    val upNext = remember(episodes, key) {
        val i = episodes.indexOfFirst { it.id == key }
        if (i < 0) emptyList() else episodes.drop(i + 1).take(8).map { e ->
            WnItem("e:${e.id}", "S${e.season} E${e.episode}" + (e.title ?: e.name)?.let { " · $it" }.orEmpty(), null, e.thumbnail ?: meta?.background, ep = e)
        }
    }
    val firstFr = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(visible, similar, upNext) {
        if (!visible) return@LaunchedEffect
        for (i in 0 until 25) { delay(80); if (hasFocus) break; runCatching { firstFr.requestFocus() } }
    }
    fun pick(it: WnItem) {
        val ep = it.ep
        val mt = it.meta
        when {
            ep != null -> onEpisode(ep)
            mt != null -> onTitle(mt)
            it.rec != null -> scope.launch {
                val r = it.rec ?: return@launch
                val imdb = vm.tmdbToImdb(r)
                if (imdb == null) Snack.show("Couldn't open ${r.title}")
                else onTitle(Meta(id = imdb, type = if (r.kind == "tv") "series" else "movie", name = r.title, poster = r.poster))
            }
        }
    }
    val epProg by vm.epProgress.collectAsState()
    val prog by vm.progress.collectAsState()
    fun watched(id: String): Float? = (prog.firstOrNull { it.key == id } ?: epProg[id])?.let { s -> if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else null }
    AnimatedVisibility(visible, Modifier.fillMaxSize(), enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x33000000), 0.45f to Color(0xB3000000), 1f to Color(0xF2000000)))
            .noFocus()) {
            val sheetH = constraints.maxHeight.toFloat()
            val sheetMinH = maxHeight
            val scroll = androidx.compose.foundation.rememberScrollState()
            // Phones: when the list can't scroll any further, the drag moves the whole sheet (it follows the finger both ways).
            val sheetScroll = remember(sheetH) {
                object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                        if (BuildConfig.IS_TV || !dragging() || available.y >= 0f) return Offset.Zero
                        onDrag(available.y, sheetH); return Offset(0f, available.y)   // pulling back up first re-opens the sheet
                    }
                    override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                        if (BuildConfig.IS_TV || available.y <= 0f || source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) return Offset.Zero
                        onDrag(available.y, sheetH); return Offset(0f, available.y)
                    }
                    override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                        if (BuildConfig.IS_TV || !dragging()) return androidx.compose.ui.unit.Velocity.Zero
                        onDragEnd(available.y); return available
                    }
                }
            }
            Column(Modifier.fillMaxSize().nestedScroll(sheetScroll).verticalScroll(scroll)) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = sheetMinH)
                    .clickable(interactionSource = noRipple, indication = null) { onClose() }
                    .padding(top = 24.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
            Column(
                Modifier.fillMaxWidth()
                    .onFocusChanged {
                        val had = hasFocus
                        hasFocus = it.hasFocus
                        if (had && !it.hasFocus) scope.launch { delay(60); if (visible && !hasFocus) runCatching { firstFr.requestFocus() } }
                    },
            ) {
                Text("Watch Next", Modifier.padding(start = 40.dp, bottom = 6.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                var first = true
                if (upNext.isNotEmpty()) {
                    Text("Up next", Modifier.padding(start = 40.dp, top = 6.dp, bottom = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFB6BDCC))
                    val isFirst = first; first = false
                    WnRow(upNext, wide = true, firstFr = if (isFirst) firstFr else null, progressOf = { it.ep?.id?.let(::watched) }) { pick(it) }
                }
                val sim = similar
                Text("More like this", Modifier.padding(start = 40.dp, top = 12.dp, bottom = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFB6BDCC))
                when {
                    sim == null -> CircularProgressIndicator(Modifier.padding(start = 40.dp).size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    sim.isEmpty() -> Text("Nothing to suggest yet.", Modifier.padding(start = 40.dp), fontSize = 13.sp, color = Color(0xFF9AA3B5))
                    else -> WnRow(sim, wide = false, firstFr = if (first) firstFr else null) { pick(it) }
                }
            }
            }
            }
        }
    }
}

@Composable
private fun WnRow(items: List<WnItem>, wide: Boolean, firstFr: FocusRequester?, progressOf: (WnItem) -> Float? = { null }, onPick: (WnItem) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 40.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(items, key = { _, it -> it.key }) { i, it ->
            var focused by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(12.dp)
            // TV: a poster opens up when focused — the poster plus its title, year and rating beside it.
            if (BuildConfig.IS_TV && !wide) { TvWnPoster(it, i == 0 && firstFr != null, firstFr) { onPick(it) }; return@itemsIndexed }
            Column(Modifier.width(if (wide) 200.dp else 100.dp)) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f)
                        .graphicsLayer { val sc = if (focused) 1.06f else 1f; scaleX = sc; scaleY = sc }
                        .then(if (i == 0 && firstFr != null) Modifier.focusRequester(firstFr) else Modifier)
                        .onFocusChanged { f -> focused = f.isFocused }
                        .clip(shape).background(Color(0xFF1E1F26))
                        .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick(it) },
                ) {
                    AsyncImage(it.image, it.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    if (wide) Box(Modifier.align(Alignment.Center).size(38.dp).clip(CircleShape).background(Color(0x80000000)), Alignment.Center) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(22.dp), tint = Color.White)
                    }
                    // How much of this episode you've watched.
                    progressOf(it)?.takeIf { f -> f > 0.01f }?.let { f ->
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(BrandRed))
                        }
                    }
                }
                Text(it.title, Modifier.padding(top = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                it.sub?.let { sub -> Text(sub, fontSize = 11.sp, color = Color(0xFF9AA3B5), maxLines = 1) }
            }
        }
    }
}

/** TV Watch Next poster: 116 wide; focused it opens to 330 with the title, year / kind and TMDB score beside the poster. */
@Composable
private fun TvWnPoster(it: WnItem, first: Boolean, firstFr: FocusRequester?, onPick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val w by androidx.compose.animation.core.animateDpAsState(if (focused) 330.dp else 116.dp, tween(200), label = "wnW")
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.width(w)) {
        Row(
            Modifier.fillMaxWidth().height(174.dp)
                .then(if (first && firstFr != null) Modifier.focusRequester(firstFr) else Modifier)
                .onFocusChanged { f -> focused = f.isFocused }
                .clip(shape).background(if (focused) Color(0xF2141519) else Color(0xFF1E1F26))
                .border(2.dp, if (focused) TvFocusEdge else Color.Transparent, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick() },
        ) {
            AsyncImage(it.image, it.title, Modifier.width(116.dp).fillMaxHeight().clip(shape), contentScale = ContentScale.Crop)
            if (focused) Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.Bottom) {
                Text(it.title, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                it.sub?.let { sub -> Text(sub, Modifier.padding(top = 4.dp), fontSize = 11.sp, color = Color(0xFFC9CED8), maxLines = 1) }
                it.rec?.score?.takeIf { s -> s > 0 }?.let { sc -> Text("★ %.1f".format(sc), Modifier.padding(top = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF5C518)) }
                it.meta?.imdbRating?.takeIf { r -> r.isNotBlank() }?.let { r -> Text("IMDb $r", Modifier.padding(top = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF5C518)) }
                Text("▶  OK to play", Modifier.padding(top = 8.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE6E8EE))
            }
        }
        if (!focused) Text(it.title, Modifier.padding(top = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Audio / subtitle lists when the VLC engine is playing. */
@Composable
private fun VlcTrackList(v: VlcEngine, audio: Boolean, tick: Int) {
    var refresh by remember { mutableIntStateOf(0) }
    val list = remember(tick, refresh) { if (audio) v.audioTracks() else v.subTracks() }
    val cur = remember(tick, refresh) { if (audio) v.audioTrack else v.subTrack }
    if (list.isEmpty()) {
        Text(if (audio) "Only one audio track." else "No subtitles in this stream.", color = Color(0xFF9AA3B5))
        return
    }
    val rows = (if (!audio) listOf("Off" to (cur < 0)) else emptyList()) + list.map { it.second to (it.first == cur) }
    OptionList(rows) { i ->
        if (!audio && i == 0) v.selectSub(-1)
        else { val id = list[if (audio) i else i - 1].first; if (audio) v.selectAudio(id) else v.selectSub(id) }
        refresh++
    }
}


/** The play / pause in the middle of the phone player: a clean, flat, see-through circle with a bold icon and a soft press. */
@Composable
private fun CenterPlayPause(playing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.9f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 600f), label = "ppScale")
    Box(
        // Xstream: no circle behind it — just the icon (brighter with a glow while pressed).
        modifier.size(76.dp)
            .graphicsLayer { val k = if (pressed) 1.12f else scale; scaleX = k; scaleY = k }
            .softGlow(pressed)
            .clickable(interactionSource = press, indication = null, onClickLabel = if (playing) "Pause" else "Play", onClick = onClick),
        Alignment.Center,
    ) {
        androidx.compose.animation.Crossfade(playing, label = "pp") { pl ->
            Icon(if (pl) Icons.Rounded.RoundedPause else Icons.Rounded.RoundedPlay, if (pl) "Pause" else "Play", Modifier.size(44.dp), tint = Color.White)
        }
    }
}

/**
 * Changing source / language / quality mid-video: a glass capsule with a red ring that fills with the real loading
 * progress (percentage inside) and where the video continues; a soft light sweeps across while it loads.
 */
@Composable
private fun SwitchCapsule(progress: Float, at: Long, modifier: Modifier = Modifier) {
    val p by androidx.compose.animation.core.animateFloatAsState(progress.coerceIn(0f, 1f), tween(300), label = "swp")
    val sheen = rememberInfiniteTransition(label = "sheen")
    val x by sheen.animateFloat(-1f, 2f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing)), label = "sx")
    val shape = RoundedCornerShape(50)
    Row(
        modifier.clip(shape).background(Color(0xA614141A)).border(1.dp, Color(0x8CE50914), shape)
            .drawWithContent {
                drawContent()
                drawRect(Brush.linearGradient(listOf(Color.Transparent, Color(0x17FFFFFF), Color.Transparent),
                    start = Offset(size.width * x - size.width * 0.3f, 0f), end = Offset(size.width * x + size.width * 0.3f, size.height)))
            }
            .padding(start = 10.dp, end = 22.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { p }, modifier = Modifier.fillMaxSize(), color = Color(0xFFE50914), strokeWidth = 3.dp,
                trackColor = Color(0x24FFFFFF), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
            Text("${(p * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text("Continuing from ${fmtTime(at)}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFF2F2F4))
    }
}

private data class UpgradeOffer(val item: StreamItem, val is4k: Boolean)

/**
 * "Upgrade to 4K" (4K bold in Xstream red) / "Upgrade quality": the size of Skip intro, glass with a red edge; the ring
 * counts down the seconds of the better file still saved ahead (38 → 1).
 */
@Composable
private fun UpgradePill(is4k: Boolean, left: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val frac by animateFloatAsState(left / 30f, tween(450), label = "upLeft")
    Row(
        Modifier.padding(bottom = 10.dp).height(40.dp).shadow(16.dp, shape, ambientColor = Color(0x99E50914), spotColor = Color(0x99E50914))
            .clip(shape).background(Color(0x9E14141A)).border(1.dp, Color(0x8CE50914), shape).clickable(enabled = !BuildConfig.IS_TV) { onClick() }
            .padding(start = 6.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxSize(), color = Color(0xFFE50914), strokeWidth = 2.5.dp,
                trackColor = Color(0x29FFFFFF), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
            Text("$left", fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(9.dp))
        if (is4k) {
            Text("Upgrade to ", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("4K", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFFF4B55))
        } else Text("Upgrade quality", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Notes when a finger is on these controls (they then stay up), without taking the touch from them. */
private fun Modifier.watchTouch(onChange: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        var down = false
        while (true) {
            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            val now = e.changes.any { it.pressed }
            if (now != down) { down = now; onChange(now) }
        }
    }
}

/** Fades the button row out at an edge only where more buttons are hidden behind it. */
private fun Modifier.railFade(scroll: androidx.compose.foundation.ScrollState): Modifier =
    graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val f = 28.dp.toPx()
            if (scroll.value > 0) drawRect(
                Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = f),
                size = Size(f, size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
            if (scroll.value < scroll.maxValue) drawRect(
                Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width - f, endX = size.width),
                topLeft = Offset(size.width - f, 0f), size = Size(f, size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }


/** Audio boost (LoudnessEnhancer) and Night mode (a soft compressor that tames loud scenes and lifts dialogue). */
object SoundFx {
    private var boostFx: android.media.audiofx.LoudnessEnhancer? = null
    private var nightFx: android.media.audiofx.DynamicsProcessing? = null
    private var session = 0

    fun apply(sessionId: Int, boost: Boolean, night: Boolean) {
        if (sessionId <= 0) return
        if (sessionId != session) { release(); session = sessionId }
        runCatching {
            if (boost) {
                val fx = boostFx ?: android.media.audiofx.LoudnessEnhancer(sessionId).also { boostFx = it }
                fx.setTargetGain(700) // +7 dB
                fx.enabled = true
            } else boostFx?.enabled = false
        }
        runCatching {
            if (night && android.os.Build.VERSION.SDK_INT >= 28) {
                val fx = nightFx ?: run {
                    val cfg = android.media.audiofx.DynamicsProcessing.Config.Builder(
                        android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        false, 0, false, 0, false, 0, true,
                    ).build()
                    android.media.audiofx.DynamicsProcessing(0, sessionId, cfg).also { nightFx = it }
                }
                // Loud parts are held back above -26 dB, then everything is lifted: explosions softer, voices louder.
                fx.setLimiterAllChannelsTo(android.media.audiofx.DynamicsProcessing.Limiter(true, true, 0, 2f, 120f, 6f, -26f, 12f))
                fx.enabled = true
            } else if (android.os.Build.VERSION.SDK_INT >= 28) nightFx?.enabled = false
        }
    }

    fun release() {
        runCatching { boostFx?.release() }; boostFx = null
        runCatching { if (android.os.Build.VERSION.SDK_INT >= 28) nightFx?.release() }; nightFx = null
        session = 0
    }
}

/** Picture-in-picture: the player keeps playing in a small window (button in the player, or when you go Home while playing). */
object PipState {
    @Volatile var playerOpen = false
    @Volatile var playing = false

    fun supported(ctx: android.content.Context): Boolean =
        android.os.Build.VERSION.SDK_INT >= 26 && ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun enter(act: Activity, size: androidx.media3.common.VideoSize? = null) {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        runCatching {
            val w = size?.width?.takeIf { it > 0 } ?: 16
            val h = size?.height?.takeIf { it > 0 } ?: 9
            // Android allows aspect ratios between 1:2.39 and 2.39:1.
            val ratio = (w.toFloat() / h).coerceIn(0.42f, 2.39f)
            val r = android.util.Rational((ratio * 1000).toInt(), 1000)
            act.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(r).build())
        }
    }
}


/** A compact switch row in the player's Audio column. */
@Composable
private fun SoundToggle(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!on) }.padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 11.sp, color = Color(0xFF9AA1AE))
        }
        androidx.compose.material3.Switch(on, onChange)
    }
}


/** A quick / lower-quality file worth upgrading from: MovieBox, a streaming playlist, or a file under Full HD. */
private fun StreamItem.belowFullHd(): Boolean = isMovieBox() || isPlaylist() || effectiveRes() in 1..1079 || effectiveRes() <= 0


/**
 * Subtitles inside the video often carry their own place on screen, so the bottom padding alone never moved them.
 * Text lines placed near the bottom go back to the normal (padded) place; picture subtitles (PGS / VobSub) are moved up
 * by the same amount the controls need. Lines placed at the top (signs, songs) stay where they are.
 */
internal fun liftCues(cues: List<Cue>, lift: Float): List<Cue> = cues.map { c ->
    when {
        c.bitmap != null -> if (lift > 0f && c.lineType == Cue.LINE_TYPE_FRACTION && c.line != Cue.DIMEN_UNSET)
            c.buildUpon().setLine((c.line - lift).coerceAtLeast(0f), Cue.LINE_TYPE_FRACTION).build() else c
        c.line == Cue.DIMEN_UNSET -> c
        (c.lineType == Cue.LINE_TYPE_FRACTION && c.line >= 0.6f) || (c.lineType == Cue.LINE_TYPE_NUMBER && c.line < 0f) ->
            c.buildUpon().setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).setLineAnchor(Cue.TYPE_UNSET).build()
        else -> c
    }
}


/**
 * Plugin mode's quality picker, inside the bubble: the playing stream's own qualities (Auto / 1080p / 720p, for Netflix,
 * Prime Video, Hotstar, MPlayer playlists) first, then Auto · 4K · Full HD · Mid HD downwards. Tap = play the best of that
 * quality; long-press = every stream of it, ranked 1, 2, 3… (servers the Source Test hasn't checked yet say Unidentified).
 */
@kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PluginQualityBar(
    cols: List<Pair<String, List<Pair<String, List<StreamItem>>>>>, playing: String?, ready: Set<String>,
    ladder: List<Int>, pinnedH: Int?, onVariant: (Int?) -> Unit, modifier: Modifier,
    onPoke: () -> Unit, onPick: (StreamItem) -> Unit,
) {
    var open by remember { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(20.dp)
    val red = Color(0xFFFF4D57)
    Column(modifier.width(300.dp).heightIn(max = 340.dp).clip(shape).background(Color(0xF0141418)).border(1.dp, Color(0x1FFFFFFF), shape)
        .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (ladder.size > 1) {
            Text("THIS STREAM", Modifier.padding(start = 6.dp, top = 2.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8A94A8), letterSpacing = 0.8.sp)
            (listOf<Int?>(null) + ladder).forEach { h ->
                val sel = h == pinnedH
                val rs = RoundedCornerShape(12.dp)
                Row(Modifier.fillMaxWidth().clip(rs).background(if (sel) Color(0x26E50914) else Color(0x0DFFFFFF))
                    .border(1.dp, if (sel) Color(0xCCE50914) else Color(0x12FFFFFF), rs).focusFill().clickable { onVariant(h) }
                    .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(h?.let { if (it >= 1440) "4K" else "${it}p" } ?: "Auto", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (sel) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = red)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("OTHER STREAMS", Modifier.padding(start = 6.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8A94A8), letterSpacing = 0.8.sp)
        }
        if (cols.all { it.second.isEmpty() }) Text("Finding qualities…", Modifier.padding(12.dp), fontSize = 13.sp, color = Color(0xFF9AA3B5))
        cols.forEach { (title, groups) ->
            val files = groups.flatMap { it.second }
            if (files.isEmpty() && title == "Auto") return@forEach
            val label = when (title) { "4K" -> "4K"; "1080p" -> "Full HD"; "720p" -> "Mid HD"; else -> "Auto" }
            val here = files.any { it.stream.url == playing }
            // A file of this quality is ready (start saved / a playlist): tap plays it from where you are.
            val readyOne = files.firstOrNull { it.stream.url in ready }
            val ps = RoundedCornerShape(14.dp)
            Row(
                Modifier.fillMaxWidth().clip(ps).background(if (here) Color(0x33E50914) else if (open == title) Color(0x26FFFFFF) else Color(0x0FFFFFFF))
                    .border(1.dp, if (here) Color(0xCCE50914) else Color(0x14FFFFFF), ps).focusFill()
                    .combinedClickable(enabled = files.isNotEmpty(),
                        onClick = { onPoke(); if (!here) (readyOne ?: files.firstOrNull())?.let(onPick) else open = if (open == title) null else title },
                        onLongClick = { onPoke(); open = if (open == title) null else title })
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (files.isEmpty()) Color(0xFF6E7687) else Color.White)
                    Text(if (files.isEmpty()) "Not available" else if (here) "Playing · ${files.size} streams · hold for all"
                        else if (readyOne != null && title == "4K") "Ready — tap to play from here" else "${files.size} streams · hold for all",
                        fontSize = 11.sp, color = if (here || (readyOne != null && title == "4K")) Color(0xFFFF8D94) else Color(0xFF9AA3B5))
                }
                if (here) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = red)
                else if (readyOne != null && title == "4K") Text("READY", Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFE50914)).padding(horizontal = 8.dp, vertical = 3.dp),
                    fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.White)
            }
            AnimatedVisibility(open == title && files.isNotEmpty(), enter = androidx.compose.animation.expandVertically(tween(260)) + fadeIn(tween(200)),
                exit = androidx.compose.animation.shrinkVertically(tween(220)) + fadeOut(tween(160))) {
                Column(Modifier.padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    files.forEachIndexed { i, st ->
                        val on = st.stream.url == playing
                        val rs = RoundedCornerShape(10.dp)
                        Row(
                            Modifier.fillMaxWidth().clip(rs).background(if (on) Color(0x26E50914) else Color(0x0DFFFFFF))
                                .border(1.dp, if (on) Color(0xCCE50914) else Color(0x12FFFFFF), rs).focusFill()
                                .clickable(enabled = !on) { onPick(st) }.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${i + 1}", Modifier.width(22.dp), fontSize = 15.sp, fontWeight = FontWeight.Black, color = red)
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text((VisualTags.video(st).ifEmpty { listOf(VisualTags.primary(st)) } + Public.codeName(st.addonName)).joinToString(" · "),
                                        Modifier.weight(1f, fill = false), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (Ranker.untested(st)) Text("Unidentified", Modifier.padding(start = 6.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x1FFFFFFF))
                                        .padding(horizontal = 5.dp, vertical = 1.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB6BDCC))
                                }
                                Text(listOfNotNull(VisualTags.audio(st), VisualTags.size(st), st.languagesOrOriginal().joinToString(" + ") { langLabel(it) },
                                    if (Ranker.knownUnseekable(st) || st.isEmbedBackup()) "backup" else null).joinToString(" · "),
                                    fontSize = 10.5.sp, color = Color(0xFF9AA3B5), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (on) Icon(Icons.Default.Check, null, Modifier.size(15.dp), tint = red)
                            else if (st.stream.url in ready || st.isPlaylist()) Icon(Icons.Default.Check, "Ready", Modifier.size(14.dp), tint = Color(0x99FF3B47))
                        }
                    }
                }
            }
        }
    }
}
