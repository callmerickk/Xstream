package com.example.streamnest

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/*
 * Full-screen YouTube trailer player in its own plain Activity.
 *
 * Why an Activity: a WebView playing video inside Compose (AndroidView) under clips / graphicsLayer transforms /
 * alpha (or inside a pager) often draws a black picture while the sound plays. Here the WebView is a plain child of
 * a FrameLayout (match_parent, default layer type, no clip, no alpha); our Xstream controls are a separate
 * ComposeView stacked on top of it.
 */

/**
 * Plays YouTube trailers full screen in a plain Activity (fixes black video).
 * Calls [onPlayMovie] when the user picks "Play the movie".
 */
@Composable
fun rememberTrailerLauncher(onPlayMovie: () -> Unit): (keys: List<String>, titles: List<String>, start: Int, title: String) -> Unit {
    val ctx = LocalContext.current
    val play by rememberUpdatedState(onPlayMovie)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data?.getBooleanExtra(TrailerActivity.RESULT_PLAY, false) == true) play()
    }
    return remember(launcher, ctx) {
        { keys: List<String>, titles: List<String>, start: Int, title: String ->
            if (keys.isEmpty()) Snack.show("No trailer found for this title")
            else runCatching { launcher.launch(TrailerActivity.intent(ctx, keys, titles, start, title)) }
                .onFailure { Snack.show("Couldn't open the trailer player") }
            Unit
        }
    }
}

/** One audio choice: a soundtrack [url] of this video, or another trailer ([index] ≥ 0). */
internal data class TrailerAudio(val name: String, val url: String?, val index: Int)

/** Observable player state shared by the WebView callbacks and the Compose controls. */
internal class TrailerUi(start: Int) {
    var index by mutableIntStateOf(start)
    var ready by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var muted by mutableStateOf(false)
    var pos by mutableDoubleStateOf(0.0)
    var dur by mutableDoubleStateOf(0.0)
    var controls by mutableStateOf(true)
    var finished by mutableStateOf(false)
    var failed by mutableStateOf(false)
    /** Bumps on every interaction (restarts the auto-hide timer). */
    var touch by mutableIntStateOf(0)
    /** Bumps when the controls should grab remote focus again. */
    var refocus by mutableIntStateOf(0)
    /** Audio languages to switch between (dubbed tracks and other-language trailers) and the one playing. */
    var audios by mutableStateOf<List<TrailerAudio>>(emptyList())
    var audioNow by mutableStateOf<String?>(null)
    /** Caption languages the video has (code to name); the chosen one (null = off). */
    var captions by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var cc by mutableStateOf<String?>(null)
    /** Picture: fill the screen (crop black bars) and a light zoom. */
    var fill by mutableStateOf(false)
    var zoom by mutableFloatStateOf(1f)
}

private fun trailerClock(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

class TrailerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_KEYS = "streamx.trailer.keys"
        const val EXTRA_TITLES = "streamx.trailer.titles"
        const val EXTRA_START = "streamx.trailer.start"
        const val EXTRA_TITLE = "streamx.trailer.title"
        /** Result extra: true = the user picked "Play the movie". */
        const val RESULT_PLAY = "playMovie"
        /** Language code of each trailer in the next launch (set by the title page just before it opens this). */
        @Volatile var nextLangs: List<String> = emptyList()

        fun intent(ctx: Context, keys: List<String>, titles: List<String>, start: Int, title: String): Intent =
            Intent(ctx, TrailerActivity::class.java)
                .putStringArrayListExtra(EXTRA_KEYS, ArrayList(keys))
                .putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles))
                .putExtra(EXTRA_START, start)
                .putExtra(EXTRA_TITLE, title)
    }

    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var keys: List<String> = emptyList()
    private var titles: List<String> = emptyList()
    private var langs: List<String> = emptyList()
    private lateinit var ui: TrailerUi
    /** The trailer playing now as direct video, and where to jump once the next one is ready (language switch). */
    private var curSrc: YtDirect.Src? = null
    private var seekOnReady: Long = -1

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keys = intent.getStringArrayListExtra(EXTRA_KEYS).orEmpty().filter { it.isNotBlank() }
        titles = intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        langs = nextLangs.takeIf { it.size == keys.size }.orEmpty(); nextLangs = emptyList()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val start = (savedInstanceState?.getInt(EXTRA_START) ?: intent.getIntExtra(EXTRA_START, 0)).coerceIn(0, (keys.size - 1).coerceAtLeast(0))
        ui = TrailerUi(start)

        // Full screen, immersive, screen kept on.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val root = FrameLayout(this).apply { setBackgroundColor(AColor.BLACK) }
        rootView = root
        // Xstream: the trailer plays as real video in our own player when YouTube gives its direct address; the
        // embedded YouTube page is only the fallback.
        if (keys.isNotEmpty()) lifecycleScope.launch {
            val src = YtDirect.resolve(keys[ui.index])
            if (src != null && !isFinishing) startDirect(src) else if (!isFinishing) startWeb()
        }
        val controls = ComposeView(this).apply {
            setContent {
                StreamNestTheme {
                    TrailerControls(
                        ui, keys, titles, title,
                        onBack = { finish() },
                        onPlayMovie = { playMovie() },
                        js = { js(it) },
                        onNext = { next() },
                        onOpenYouTube = { k -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$k"))) } },
                        onAudio = { nextAudio() },
                    )
                }
            }
        }
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        // Remote keys go to the Compose controls (the WebView never takes focus).
        controls.isFocusable = true
        controls.isFocusableInTouchMode = true
        controls.post { controls.requestFocus() }
    }

    private var rootView: FrameLayout? = null
    private var exo: androidx.media3.exoplayer.ExoPlayer? = null
    private var pv: androidx.media3.ui.PlayerView? = null

    @SuppressLint("SetJavaScriptEnabled")
    private fun startWeb() {
        val root = rootView ?: return
        if (web != null) return
            val w = WebView(this).apply {
            // Default layer type, no clip, no alpha: the video surface draws normally.
            setBackgroundColor(AColor.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false
            webChromeClient = object : WebChromeClient() {
                // No grey "play" poster before the first frame.
                override fun getDefaultVideoPoster(): Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    main.postDelayed({ runCatching { view?.evaluateJavascript("tryPlay()", null) } }, 1200)
                    main.postDelayed({ runCatching { view?.evaluateJavascript("tryPlay()", null) } }, 3000)
                }
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun ready() { main.post { ui.ready = true } }
                @JavascriptInterface fun playing() { main.post { ui.playing = true; ui.failed = false } }
                @JavascriptInterface fun paused() { main.post { ui.playing = false } }
                @JavascriptInterface fun ended() { main.post { ui.playing = false; next() } }
                @JavascriptInterface fun error(code: Int) { main.post { ui.playing = false; ui.failed = true; ui.controls = true; ui.refocus++ } }
                @JavascriptInterface fun time(cur: Double, total: Double) { main.post { ui.pos = cur; if (total > 0) ui.dur = total } }
                @JavascriptInterface fun captions(list: String) {
                    val parsed = runCatching {
                        Api.json.parseToJsonElement(list).jsonArray.mapNotNull { e ->
                            val t = e.jsonPrimitive.content
                            t.substringBefore("|").takeIf { it.isNotBlank() }?.let { it to t.substringAfter("|") }
                        }
                    }.getOrDefault(emptyList())
                    main.post { ui.captions = parsed.distinctBy { it.first } }
                }
            }, "Android")
            val origin = "https://$packageName"
            loadDataWithBaseURL("$origin/", trailerPlayerHtml(keys[ui.index], origin, ui.muted), "text/html", "utf-8", null)
        }
        web = w
        root.addView(w, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun startDirect(src: YtDirect.Src) {
        val root = rootView ?: return
        val p = exo ?: androidx.media3.exoplayer.ExoPlayer.Builder(this).build().also { pl ->
            exo = pl
            pl.addListener(object : androidx.media3.common.Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { ui.playing = isPlaying; if (isPlaying) ui.failed = false }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == androidx.media3.common.Player.STATE_READY) {
                        ui.ready = true
                        if (seekOnReady > 0) { exo?.seekTo(seekOnReady); seekOnReady = -1 }
                    }
                    if (state == androidx.media3.common.Player.STATE_ENDED) { ui.playing = false; next() }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    ActLog.add("TRAILER", "full-screen direct failed ${error.errorCodeName}: embedded player instead")
                    // Direct address refused: fall back to the embedded player for this trailer.
                    stopDirect(); startWeb()
                }
            })
            val v = androidx.media3.ui.PlayerView(this).apply {
                useController = false; player = pl; setShutterBackgroundColor(AColor.BLACK); setKeepContentOnPlayerReset(true)
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                isFocusable = false
            }
            pv = v
            root.addView(v, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Time for the controls.
            val tick = object : Runnable {
                override fun run() {
                    exo?.let { e -> ui.pos = e.currentPosition / 1000.0; if (e.duration > 0) ui.dur = e.duration / 1000.0 }
                    main.postDelayed(this, 400)
                }
            }
            main.post(tick)
        }
        p.volume = if (ui.muted) 0f else 1f
        curSrc = src
        p.setMediaSource(YtDirect.source(src)); p.prepare(); p.playWhenReady = true
        buildAudios(src)
    }

    /**
     * Audio choices: the dubbed soundtracks inside this video, plus the same trailer in other languages (other videos
     * on TMDB). Your language was picked first already; the chip cycles through the rest.
     */
    private fun buildAudios(src: YtDirect.Src?) {
        val list = ArrayList<TrailerAudio>()
        src?.tracks?.forEach { (name, url) -> list += TrailerAudio(name, url, -1) }
        val myLang = langs.getOrNull(ui.index).orEmpty()
        val myType = titles.getOrNull(ui.index).orEmpty().lowercase().let { if ("teaser" in it) "teaser" else "trailer" }
        langs.forEachIndexed { i, l ->
            if (i != ui.index && l.isNotBlank() && l != myLang && list.none { it.index >= 0 && langs[it.index] == l } &&
                titles.getOrNull(i).orEmpty().lowercase().contains(myType))
                list += TrailerAudio(YtDirect.langName(l) + " trailer", null, i)
        }
        val nowName = src?.audioName ?: myLang.takeIf { it.isNotBlank() }?.let { YtDirect.langName(it) }
        if (list.none { it.index < 0 } && nowName != null) list.add(0, TrailerAudio(nowName, src?.audio, -1))
        ui.audios = list
        ui.audioNow = nowName ?: list.firstOrNull()?.name
    }

    private fun nextAudio() {
        val l = ui.audios; if (l.size < 2) return
        val i = l.indexOfFirst { it.name == ui.audioNow }
        val o = l[(i + 1) % l.size]
        val pos = exo?.currentPosition ?: (ui.pos * 1000).toLong()
        ui.audioNow = o.name
        if (o.index >= 0) {
            // The same trailer in that language: another video, opened at the same moment.
            ui.index = o.index; ui.finished = false; ui.failed = false
            seekOnReady = pos; loadIndex()
        } else {
            val src = curSrc ?: return
            val e = exo ?: return
            e.setMediaSource(YtDirect.source(src, o.url), pos); e.prepare(); e.playWhenReady = true
        }
    }

    private fun stopDirect() {
        exo?.release(); exo = null
        pv?.let { (it.parent as? ViewGroup)?.removeView(it) }; pv = null
    }

    /** The control commands (written for the embedded page) acted out on our own player. */
    private fun direct(code: String) {
        val e = exo ?: return
        code.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { c ->
            val arg = c.substringAfter("(", "").substringBeforeLast(")")
            when (c.substringBefore("(")) {
                "playV" -> e.play()
                "pauseV" -> e.pause()
                "seekBy" -> arg.toDoubleOrNull()?.let { e.seekTo((e.currentPosition + it * 1000).toLong().coerceAtLeast(0)) }
                "seekTo" -> arg.toDoubleOrNull()?.let { e.seekTo((it * 1000).toLong()) }
                "setMuted" -> e.volume = if (arg == "true") 0f else 1f
                "setFill" -> pv?.resizeMode = if (arg == "true") androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                "setZoom" -> arg.toFloatOrNull()?.let { z -> pv?.scaleX = z; pv?.scaleY = z }
            }
        }
    }

    private fun loadIndex() {
        val k = keys[ui.index]
        if (web != null) { js("loadVid('$k')"); return }
        lifecycleScope.launch {
            val src = YtDirect.resolve(k)
            if (src != null) startDirect(src) else { stopDirect(); startWeb() }
        }
    }

    private fun js(code: String) { if (exo != null) { direct(code); return }; runCatching { web?.evaluateJavascript(code, null) } }

    private fun next() {
        if (ui.index < keys.lastIndex) {
            ui.index++
            ui.finished = false; ui.failed = false; ui.pos = 0.0; ui.dur = 0.0
            loadIndex()
        } else {
            ui.finished = true; ui.controls = true; ui.playing = false; ui.refocus++
        }
    }

    private fun playMovie() {
        setResult(RESULT_OK, Intent().putExtra(RESULT_PLAY, true))
        finish()
    }

    private fun togglePlay() { js(if (ui.playing) "pauseV()" else "playV()") }

    /** Keys whose key-down we handled here: their key-up must not reach Compose (it would "click" the focused button). */
    private val swallowed = HashSet<Int>()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) {
            if (swallowed.remove(code)) return true
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        ui.touch++
        if (handleKey(code, event.repeatCount)) { swallowed.add(code); return true }
        return super.dispatchKeyEvent(event)
    }

    private fun handleKey(code: Int, repeat: Int): Boolean {
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); ui.controls = true; ui.refocus++; return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { js("playV()"); ui.controls = true; ui.refocus++; return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { js("pauseV()"); ui.controls = true; ui.refocus++; return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { js("seekBy(10)"); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { js("seekBy(-10)"); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (ui.index < keys.lastIndex) next(); return true }
        }
        // Controls hidden: the remote acts on the video directly (and brings the controls back).
        if (ui.controls) return false
        return when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (repeat == 0) togglePlay(); ui.controls = true; ui.refocus++; true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { js("seekBy(-10)"); ui.controls = true; ui.refocus++; true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { js("seekBy(10)"); ui.controls = true; ui.refocus++; true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> { ui.controls = true; ui.refocus++; true }
            else -> false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_START, ui.index)
    }

    override fun onPause() {
        super.onPause()
        exo?.pause()
        runCatching { web?.onPause() }
    }

    override fun onResume() {
        super.onResume()
        runCatching { web?.onResume() }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopDirect()
        web?.let { w ->
            runCatching {
                w.stopLoading(); w.loadUrl("about:blank"); w.removeJavascriptInterface("Android")
                (w.parent as? ViewGroup)?.removeView(w)
                w.destroy()
            }
        }
        web = null
        super.onDestroy()
    }
}

/** Remote focus look for the trailer controls: white ring, no ripple. */
private fun Modifier.trailerFocus(shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
        .onFocusChanged { focused = it.isFocused }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

@Composable
private fun TrailerControls(
    ui: TrailerUi, keys: List<String>, titles: List<String>, title: String,
    onBack: () -> Unit, onPlayMovie: () -> Unit, js: (String) -> Unit, onNext: () -> Unit, onOpenYouTube: (String) -> Unit,
    onAudio: () -> Unit = {},
) {
    val key = keys.getOrNull(ui.index)
    val name = titles.getOrNull(ui.index)?.takeIf { it.isNotBlank() } ?: "Trailer"
    val hasNext = ui.index < keys.lastIndex
    val playReq = remember { FocusRequester() }
    val endReq = remember { FocusRequester() }

    LaunchedEffect(ui.muted, ui.ready) { if (ui.ready) js("setMuted(${if (ui.muted) "true" else "false"})") }
    // Auto-hide the controls while playing.
    LaunchedEffect(ui.controls, ui.playing, ui.touch, ui.finished, ui.failed) {
        if (ui.controls && ui.playing && !ui.finished && !ui.failed) { delay(3500); ui.controls = false }
    }
    // Remote: put focus on the main button whenever the controls come back.
    LaunchedEffect(ui.refocus, ui.controls, ui.finished, ui.failed) {
        if (!ui.controls && !ui.finished && !ui.failed) return@LaunchedEffect
        delay(60)
        runCatching { if (ui.finished || ui.failed) endReq.requestFocus() else playReq.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        if (keys.isEmpty()) {
            Text("No trailer to play.", Modifier.align(Alignment.Center), fontSize = 16.sp, color = Color(0xFF9AA3B5))
        }
        // Tap anywhere: show / hide the controls; double-tap a side: ±10 s.
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(onTap = { ui.controls = !ui.controls; ui.touch++ }, onDoubleTap = { o ->
                if (o.x < size.width / 2f) js("seekBy(-10)") else js("seekBy(10)"); ui.touch++
            })
        })

        AnimatedVisibility(ui.controls || ui.finished || ui.failed, Modifier.fillMaxSize(), enter = fadeIn(tween(180)), exit = fadeOut(tween(220))) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x8C000000), 0.3f to Color(0x1A000000), 0.6f to Color(0x26000000), 1f to Color(0xD9000000)))) {
                // ── Top: back, title, mute ──
                Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF15161C)).trailerFocus(CircleShape, onBack), Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", Modifier.size(26.dp), tint = Color.White)
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (ui.dur > 0) "$name · ${trailerClock(ui.dur)}" else name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFC9CED9),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // Subtitles (the video's caption languages, OK cycles), picture fit / fill, light zoom.
                    val chip = RoundedCornerShape(18.dp)
                    val ccName = ui.cc?.let { c -> ui.captions.firstOrNull { it.first == c }?.second ?: c.uppercase() } ?: "Off"
                    Text("CC · $ccName", Modifier.padding(end = 10.dp).clip(chip).background(Color(0xFF15161C)).trailerFocus(chip) {
                        val codes = listOf<String?>(null) + ui.captions.map { it.first }.ifEmpty { listOf("en") }
                        val i = codes.indexOf(ui.cc)
                        ui.cc = codes[(i + 1) % codes.size]
                        js("setCaptions(${ui.cc?.let { "'$it'" } ?: "null"})"); ui.touch++
                    }.padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    // Audio: your language first; tap for the next one (dubbed track or the trailer in that language).
                    if (ui.audios.size > 1) Text("Audio · ${ui.audioNow ?: "Original"}", Modifier.padding(end = 10.dp).clip(chip).background(Color(0xFF15161C)).trailerFocus(chip) {
                        onAudio(); ui.touch++
                    }.padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(if (ui.fill) "Fill" else "Fit", Modifier.padding(end = 10.dp).clip(chip).background(Color(0xFF15161C)).trailerFocus(chip) {
                        ui.fill = !ui.fill; js("setFill(${ui.fill})"); ui.touch++
                    }.padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Zoom ${"%.2f".format(ui.zoom).trimEnd('0').trimEnd('.')}×", Modifier.padding(end = 10.dp).clip(chip).background(Color(0xFF15161C)).trailerFocus(chip) {
                        ui.zoom = when { ui.zoom < 1.1f -> 1.15f; ui.zoom < 1.25f -> 1.3f; else -> 1f }; js("setZoom(${ui.zoom})"); ui.touch++
                    }.padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF15161C)).trailerFocus(CircleShape) { ui.muted = !ui.muted; ui.touch++ }, Alignment.Center) {
                        Icon(if (ui.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (ui.muted) "Unmute" else "Mute",
                            Modifier.size(22.dp), tint = Color.White)
                    }
                }

                val pill = RoundedCornerShape(18.dp)
                when {
                    ui.finished -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (keys.size > 1) "That's all the trailers" else "Trailer finished", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFC9CED9))
                        Box(Modifier.padding(top = 16.dp).focusRequester(endReq).trailerFocus(RoundedCornerShape(30.dp), onPlayMovie)) {
                            RedPill("Play the movie", Modifier.widthIn(min = 240.dp), onClick = onPlayMovie)
                        }
                        Text("Watch again", Modifier.padding(top = 14.dp).clip(pill).background(Color(0x2EFFFFFF))
                            .trailerFocus(pill) { ui.finished = false; js("seekTo(0);playV()") }.padding(horizontal = 16.dp, vertical = 8.dp),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    ui.failed -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("This video can't be played inside the app.", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (hasNext) Text("Next video", Modifier.focusRequester(endReq).clip(pill).background(Color(0x2EFFFFFF)).trailerFocus(pill) { onNext() }
                                .padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Open in YouTube", Modifier.then(if (hasNext) Modifier else Modifier.focusRequester(endReq)).clip(pill).background(Color(0x2EFFFFFF))
                                .trailerFocus(pill) { key?.let(onOpenYouTube) }.padding(horizontal = 16.dp, vertical = 8.dp),
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Box(Modifier.trailerFocus(RoundedCornerShape(20.dp), onPlayMovie)) {
                                RedPill("Play the movie", Modifier.widthIn(min = 150.dp), height = 40, fontSize = 14, onClick = onPlayMovie)
                            }
                        }
                    }
                    else -> Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(70.dp), verticalAlignment = Alignment.CenterVertically) {
                        // ±10 s icons: touch only (on the remote, LEFT / RIGHT on the play button seek).
                        Icon(Icons.Default.Replay10, "Back 10 seconds", Modifier.size(52.dp).clip(CircleShape).focusProperties { canFocus = false }
                            .quietClick { js("seekBy(-10)"); ui.touch++ }.padding(8.dp), tint = Color.White)
                        Box(
                            Modifier.size(74.dp).clip(CircleShape).background(Color(0x59000000)).border(1.5.dp, Color(0x59FFFFFF), CircleShape)
                                .focusRequester(playReq)
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (e.key) {
                                        Key.DirectionLeft -> { js("seekBy(-10)"); true }
                                        Key.DirectionRight -> { js("seekBy(10)"); true }
                                        else -> false
                                    }
                                }
                                .trailerFocus(CircleShape) { js(if (ui.playing) "pauseV()" else "playV()"); ui.touch++ },
                            Alignment.Center,
                        ) { Icon(if (ui.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (ui.playing) "Pause" else "Play", Modifier.size(38.dp), tint = Color.White) }
                        Icon(Icons.Default.Forward10, "Forward 10 seconds", Modifier.size(52.dp).clip(CircleShape).focusProperties { canFocus = false }
                            .quietClick { js("seekBy(10)"); ui.touch++ }.padding(8.dp), tint = Color.White)
                    }
                }

                // ── Bottom: seek bar, time, next, play the movie ──
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 40.dp, vertical = 20.dp)) {
                    val frac = if (ui.dur > 0) (ui.pos / ui.dur).toFloat().coerceIn(0f, 1f) else 0f
                    Box(
                        Modifier.fillMaxWidth().height(22.dp).pointerInput(Unit) {
                            detectTapGestures { o -> if (ui.dur > 0) { js("seekTo(${(o.x / size.width).coerceIn(0f, 1f) * ui.dur})"); ui.touch++ } }
                        },
                        Alignment.CenterStart,
                    ) {
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x4DFFFFFF))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).clip(RoundedCornerShape(3.dp)).background(Color(0xFFE50914)))
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            Box(Modifier.offset(x = maxWidth * frac - 8.dp).size(16.dp).clip(CircleShape).background(Color.White))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${trailerClock(ui.pos)} / ${trailerClock(ui.dur)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        if (hasNext) Text("Next: ${titles.getOrNull(ui.index + 1)?.takeIf { it.isNotBlank() } ?: "Video"} ▸",
                            Modifier.padding(end = 16.dp).widthIn(max = 260.dp).clip(pill).trailerFocus(pill) { onNext() }.padding(horizontal = 10.dp, vertical = 6.dp),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFC9CED9), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!ui.finished) Box(Modifier.trailerFocus(RoundedCornerShape(20.dp), onPlayMovie)) {
                            RedPill("Play the movie", Modifier.widthIn(min = 150.dp), height = 40, fontSize = 14, onClick = onPlayMovie)
                        }
                    }
                }
            }
        }
    }
}

/** YouTube IFrame player page: no YouTube controls, autoplays muted, then unmutes once it really plays. */
internal fun trailerPlayerHtml(id: String, origin: String, muted: Boolean) = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}#p{position:absolute;top:0;left:0;width:100%;height:100%;pointer-events:none}</style>
</head><body><div id="p"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var player=null,ready=false,tries=0,timer=null,poll=null,wantMuted=${if (muted) "true" else "false"},started=false;
function tryPlay(){try{if(player&&ready&&!started){player.mute();if(player.getPlayerState()!=1)player.playVideo();}}catch(e){}}
function report(){try{if(player&&ready)Android.time(player.getCurrentTime()||0,player.getDuration()||0);}catch(e){}}
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{videoId:'$id',
    playerVars:{autoplay:1,mute:1,controls:0,rel:0,playsinline:1,modestbranding:1,iv_load_policy:3,fs:0,disablekb:1,cc_load_policy:0,origin:'$origin'},
    events:{
      onReady:function(e){ready=true;Android.ready();e.target.mute();e.target.playVideo();
        poll=setInterval(report,500);
        timer=setInterval(function(){tries++;tryPlay();if(tries>24||started){clearInterval(timer);}},400);},
      onStateChange:function(e){
        if(e.data==1){var first=!started;started=true;if(!wantMuted)player.unMute();Android.playing();
          if(first){try{player.loadModule('captions');}catch(x){} setTimeout(captionList,1500); if(!ccOn)setTimeout(function(){setCaptions(null)},1600);}}
        if(e.data==2)Android.paused();
        if(e.data==0){report();Android.ended();}
      },
      onError:function(e){Android.error(e.data);}
    }});
}
var zoom=1,fill=false,ccOn=false;
function applyView(){var p=document.getElementById('p');if(p){p.style.transformOrigin='50% 50%';p.style.transform='scale('+(zoom*(fill?1.33:1))+')';}}
function setZoom(z){zoom=z;applyView();}
function setFill(f){fill=f;applyView();}
function setCaptions(code){try{if(!code){ccOn=false;player.unloadModule('captions');}else{ccOn=true;player.loadModule('captions');player.setOption('captions','track',{languageCode:code});}}catch(e){}}
function captionList(){try{var l=player.getOption('captions','tracklist')||[];Android.captions(JSON.stringify(l.map(function(t){return t.languageCode+'|'+(t.displayName||t.languageName||t.languageCode);})));}catch(e){}}
function setMuted(m){wantMuted=m;if(player&&player.mute){if(m)player.mute();else player.unMute();}}
function playV(){try{player.playVideo();}catch(e){}}
function pauseV(){try{player.pauseVideo();}catch(e){}}
function seekTo(s){try{player.seekTo(Math.max(0,s),true);report();}catch(e){}}
function seekBy(d){try{seekTo((player.getCurrentTime()||0)+d);}catch(e){}}
function loadVid(v){try{started=false;player.loadVideoById(v);if(!wantMuted)player.unMute();applyView();}catch(e){}}
</script></body></html>
""".trimIndent()
