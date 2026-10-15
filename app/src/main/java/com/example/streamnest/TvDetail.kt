package com.example.streamnest

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Xstream TV title page (mockups s5 "title page" and s6 "episodes"; 960×540dp canvas).
 *
 * Two states sharing one tab row:
 *  - hero: full backdrop, title info and the actions row (Play · Trailer · Library · More), tabs at the bottom;
 *  - content: the tab row moves to the top and the chosen tab fills the page (Episodes / More Like This / Cast /
 *    Trailers & More). The tab row is the same composable in both states, so remote focus stays on it.
 * Focus DOWN from the actions lands on the tabs (→ content); UP from the tabs or BACK returns to the hero (focus Play).
 */

private data class TdPlayTarget(val target: SourceTarget, val label: String, val resumeMs: Long, val frac: Float)

private enum class TdTab(val label: String) { Episodes("Episodes"), More("More Like This"), Cast("Cast"), Videos("Trailers & More") }

private val TdGlow = Color(0xFFE50914)
private val TdMuted = Color(0xFF9AA3B5)
private val TdSoft = Color(0xFFC9CED9)
private val TdRoundBg = Color(0x1FFFFFFF)
private val TdRoundRim = Color(0x33FFFFFF)

/**
 * Remote focus look: scale (draw phase only), 2dp white ring and a soft red glow (static translucent rings — no
 * animated shadows), no ripple. Put it BEFORE the element's own clip/background.
 */
private fun Modifier.tdFocus(
    corner: Dp,
    scale: Float = 1.05f,
    requester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    onKey: ((Key) -> Boolean)? = null,
    ring: Boolean = true,
    glow: Boolean = true,
    onLong: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val longCb = rememberUpdatedState(onLong)
    val longFired = remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val anim = animateFloatAsState(if (focused) 1f else 0f, tween(140), label = "tdFocus")
    val click = rememberUpdatedState(onClick)
    val focusCb = rememberUpdatedState(onFocused)
    val keyCb = rememberUpdatedState(onKey)
    val interaction = remember { MutableInteractionSource() }
    this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            val s = 1f + (scale - 1f) * anim.value
            scaleX = s; scaleY = s
        }
        .drawWithCache {
            val r = corner.toPx()
            val stroke = 2.dp.toPx()
            val o = 2.dp.toPx() + stroke / 2f
            val spread = 8.dp.toPx()
            val ringStroke = Stroke(stroke)
            onDrawWithContent {
                val v = anim.value
                if (glow && v > 0.01f) for (i in 1..3) {
                    val g = spread * i / 3f
                    drawRoundRect(TdGlow, topLeft = Offset(-g, -g), size = Size(size.width + 2 * g, size.height + 2 * g),
                        cornerRadius = CornerRadius(r + g, r + g), alpha = 0.12f * v)
                }
                drawContent()
                if (ring && v > 0.01f) drawRoundRect(Color.White, topLeft = Offset(-o, -o), size = Size(size.width + 2 * o, size.height + 2 * o),
                    cornerRadius = CornerRadius(r + o, r + o), style = ringStroke, alpha = v)
            }
        }
        .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
        .onFocusChanged { st ->
            if (st.isFocused != focused) {
                focused = st.isFocused
                if (st.isFocused) focusCb.value?.invoke()
            }
        }
        .onPreviewKeyEvent { e ->
            val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
            val long = longCb.value
            when {
                // Hold OK: the long-press action (the release after it doesn't also click).
                ok && long != null && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 -> {
                    if (!longFired.value) { longFired.value = true; long() }
                    true
                }
                ok && e.type == KeyEventType.KeyUp && longFired.value -> { longFired.value = false; true }
                else -> e.type == KeyEventType.KeyDown && keyCb.value?.invoke(e.key) == true
            }
        }
        .clickable(interactionSource = interaction, indication = null) { click.value() }
}

private fun FocusRequester.tdTry(): Boolean = runCatching { requestFocus() }.isSuccess

/** "6 Oct 2026" from "2026-10-06…". */
private fun tdDate(iso: String?): String? {
    val d = iso?.take(10)?.takeIf { it.length == 10 } ?: return null
    return runCatching {
        val at = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(d) ?: return null
        java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US).format(at)
    }.getOrNull()
}

/** Released within the last 90 days. */
private fun tdIsNew(iso: String?): Boolean {
    val d = iso?.take(10)?.takeIf { it.length == 10 } ?: return false
    val at = runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(d)?.time }.getOrNull() ?: return false
    val days = (System.currentTimeMillis() - at) / 86_400_000L
    return days in 0..90
}

private fun tdLanguage(code: String?): String? {
    val c = code?.trim()?.lowercase()?.takeIf { it.length in 2..3 } ?: return null
    return runCatching { java.util.Locale(c).getDisplayLanguage(java.util.Locale.ENGLISH) }.getOrNull()?.takeIf { it.isNotBlank() && !it.equals(c, true) }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvDetailScreen(
    vm: AppViewModel, meta: Meta, onBack: () -> Unit, onOpen: (Meta) -> Unit,
    onPlay: (Screen.Player) -> Unit, onPerson: (TmCast) -> Unit,
    autoPlay: Boolean = false, onAutoPlayed: () -> Unit = {},
) {
    var cur by remember(meta.id) { mutableStateOf(meta) }
    var metaLoaded by remember(meta.id) { mutableStateOf(false) }
    LaunchedEffect(meta.id) { runCatching { vm.fullMeta(meta) }.getOrNull()?.let { cur = it }; metaLoaded = true }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val progress by vm.progress.collectAsStateWithLifecycle()
    val done by vm.doneKeys.collectAsStateWithLifecycle()
    val myList by vm.myList.collectAsStateWithLifecycle()
    val texts by vm.texts.collectAsStateWithLifecycle()
    val imdb = cur.id.substringBefore(":")

    // ── Data (same sources as the phone title page) ──
    var tm by remember(imdb) { mutableStateOf<TmdbInfo?>(null) }
    var art by remember(imdb) { mutableStateOf<Art?>(null) }
    var ratings by remember(imdb) { mutableStateOf<List<Rating>>(emptyList()) }
    var trailer by remember(imdb) { mutableStateOf<String?>(null) }
    var more by remember(imdb) { mutableStateOf<List<Meta>>(emptyList()) }
    var picks by remember(imdb) { mutableStateOf<List<LikedPick>>(emptyList()) }
    LaunchedEffect(imdb) { tm = runCatching { vm.tmdbInfo(imdb, cur.type) }.getOrNull() }
    LaunchedEffect(imdb) { art = runCatching { vm.tmdbArt(imdb, cur.type) }.getOrNull() }
    LaunchedEffect(imdb) { ratings = runCatching { vm.mdbRatings(imdb) }.getOrDefault(emptyList()) }
    LaunchedEffect(imdb) { trailer = runCatching { vm.trailerId(cur) }.getOrNull() }
    LaunchedEffect(imdb, cur.genres) {
        val g = cur.genres.orEmpty().take(2)
        if (g.isNotEmpty()) more = runCatching { vm.genreTitles(g, listOf(cur.type)) }.getOrDefault(emptyList()).filter { it.id != cur.id }.take(20)
    }
    LaunchedEffect(imdb, tm) {
        val info = tm ?: return@LaunchedEffect
        val cands = runCatching { vm.likedCandidates(imdb, info) }.getOrDefault(emptyList())
        picks = tmdbPicks(info, cands)
    }

    // ── Episodes & the Play button (phone logic) ──
    val episodes = remember(cur) { cur.videos.orEmpty().filter { it.season != null && it.episode != null }.sortedWith(compareBy({ it.season }, { it.episode })) }
    val seasons = remember(episodes) { episodes.mapNotNull { it.season }.distinct().sorted().let { s -> s.filter { it > 0 } + s.filter { it == 0 } } }
    val autoMode = t("autoPlayMode")
    val external = t("playerChoice") == "external"
    val quick = vm.qsActive()
    fun resumeFor(vid: String): Long = progress.firstOrNull { it.key == vid }
        ?.takeIf { it.durationMs <= 0 || it.positionMs < it.durationMs * 0.95 }?.positionMs ?: 0L
    fun target(ep: Video) = SourceTarget(ep.id, "${cur.name}  S${ep.season}E${ep.episode}", "S${ep.season} E${ep.episode}", ep.title ?: ep.name, ep.thumbnail)

    val playTarget = remember(cur, progress, done, episodes) {
        if (episodes.isEmpty()) {
            val p = progress.firstOrNull { it.key == cur.id }
            val r = resumeFor(cur.id)
            TdPlayTarget(SourceTarget(cur.id, cur.name, null, null, null), if (r > 0) "Resume ${fmtTime(r)}" else "Play", r,
                if (p != null && p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f)
        } else {
            val mine = progress.filter { it.key.startsWith(cur.id + ":") }.maxByOrNull { it.updated }
            val ep = mine?.let { m -> episodes.firstOrNull { it.id == m.key } }
                ?: episodes.lastOrNull { it.id in done }?.let { last -> episodes.getOrNull(episodes.indexOf(last) + 1) }
                ?: episodes.firstOrNull { (it.season ?: 0) > 0 } ?: episodes.first()
            val r = resumeFor(ep.id)
            val p = progress.firstOrNull { it.key == ep.id }
            TdPlayTarget(target(ep), if (r > 0) "Resume S${ep.season} E${ep.episode}" else "Play", r,
                if (p != null && p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f)
        }
    }
    var season by remember(seasons, playTarget.target.videoId) {
        mutableIntStateOf(episodes.firstOrNull { it.id == playTarget.target.videoId }?.season ?: seasons.firstOrNull() ?: 1)
    }
    val shown = remember(episodes, season) { episodes.filter { it.season == season }.distinctBy { it.id } }

    // Look the streams up as soon as the page opens, so Play starts quickly.
    // Elite (Quick Start + ExoPlayer): the chosen stream is also prepared — its first seconds loaded — while you read the page.
    // CNCVerse (the HD server) needs time to find its files: asked as soon as the page opens, so they're there by the
    // time the video plays. The trailer player is warmed up too (the first one opened slowly on Fire TV).
    LaunchedEffect(playTarget.target.videoId) {
        runCatching { SlowAddons.watch(vm, cur.type, playTarget.target.videoId) }
        delay(2500)
        TvWarm.webView(ctx)
    }
    LaunchedEffect(playTarget.target.videoId, quick) {
        val id = playTarget.target.videoId
        val list = runCatching { if (quick) vm.quickStreams(cur.type, id) else vm.allStreams(cur.type, id) }.getOrDefault(emptyList())
        // Off on TV for now: a second video player in the background is too heavy for 1 GB Fire TV sticks (crashes / stutter).
        if (true) return@LaunchedEffect
        if (!quick || external || list.isEmpty() || Preloader.has(id) || (texts["playerEngine"] ?: "exo") != "exo" || !vm.eliteOn()) return@LaunchedEffect
        // Fire TV: never 4K in the background.
        val level = vm.playerPref("quality").ifEmpty { texts["qsQuality"] ?: "auto" }.let { if (it == "4k") "fhd" else it }
        val s = vm.pickQuick(list, level) ?: vm.pickQuick(list, "auto") ?: return@LaunchedEffect
        val url = s.stream.url ?: return@LaunchedEffect
        delay(1200) // let the page settle first (smooth scrolling on 1 GB sticks)
        val extra = withTimeoutOrNull(1500) { runCatching { vm.subtitles(cur.type, id) }.getOrDefault(emptyList()) }.orEmpty()
        val subs = s.stream.subtitles.orEmpty() + extra
        val fname = s.stream.behaviorHints?.filename
        val mime = if (StreamType.needsProbe(url, fname)) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { StreamType.probe(url) }.takeIf { it.isNotEmpty() }
            else StreamType.mimeFor(url, fname)
        val player = buildPlayer(ctx, vm, isOn(texts["fastStart"] ?: "yes"))
        player.setMediaItem(androidx.media3.common.MediaItem.Builder().setUri(url).apply { if (mime != null) setMimeType(mime) }
            .setSubtitleConfigurations(subtitleConfigs(subs)).build(), playTarget.resumeMs)
        player.playWhenReady = false
        player.prepare()
        Preloader.set(Preloaded(id, url, subs, s.stream.name ?: s.addonName, s.addonName, list, player))
    }
    val preKey by rememberUpdatedState(playTarget.target.videoId)
    DisposableEffect(Unit) { onDispose { Preloader.release(preKey) } }

    var pick by remember { mutableStateOf<SourceTarget?>(null) }
    val subsJob = remember(pick?.videoId) { pick?.let { pk -> scope.async { runCatching { vm.subtitles(cur.type, pk.videoId) }.getOrDefault(emptyList()) } } }

    fun start(t: SourceTarget, forceSources: Boolean = false, fromStart: Boolean = false) {
        val image = t.image ?: cur.background ?: cur.poster
        if (fromStart) Preloader.release(t.videoId) // prepared at the resume point: drop it
        if (fromStart && !forceSources) {
            onPlay(Screen.Player(t.title, "", emptyList(), key = t.videoId, image = image, resumeMs = 0L, type = cur.type, meta = cur, find = true, quick = quick))
            return
        }
        when {
            forceSources -> pick = t
            quick && !external -> onPlay(Screen.Player(t.title, "", emptyList(), key = t.videoId, image = image,
                resumeMs = resumeFor(t.videoId), type = cur.type, meta = cur, find = true, quick = true))
            quick && external -> scope.launch {
                val list = runCatching { vm.quickStreams(cur.type, t.videoId) }.getOrDefault(emptyList())
                val s = vm.pickQuick(list, texts["qsQuality"] ?: "auto")
                if (s != null) openExternal(ctx, s.stream.url!!, t.title) else pick = t
            }
            !external && autoMode != "manual" -> onPlay(Screen.Player(t.title, "", emptyList(), key = t.videoId, image = image,
                resumeMs = resumeFor(t.videoId), type = cur.type, meta = cur, find = true))
            else -> pick = t
        }
    }

    // Play pressed on Home's hero card: start as soon as the full title (episodes) is known — only once.
    LaunchedEffect(metaLoaded) {
        if (autoPlay && metaLoaded) { onAutoPlayed(); start(playTarget.target) }
    }

    fun play(s: StreamItem, all: List<StreamItem>, resume: Long) {
        val tg = pick ?: return
        scope.launch {
            val extra = withTimeoutOrNull(1500) { subsJob?.await() }.orEmpty()
            val subs = s.stream.subtitles.orEmpty() + extra
            pick = null
            if (external) openExternal(ctx, s.stream.url!!, tg.title)
            else onPlay(Screen.Player(tg.title, s.stream.url!!, subs, key = tg.videoId, image = tg.image ?: cur.background ?: cur.poster,
                resumeMs = resume, type = cur.type, meta = cur, source = s.stream.name ?: s.addonName, addon = s.addonName,
                candidates = all.filter { it !== s }))
        }
    }

    // "Play the movie" from the trailer player: press Play once the page (and, for series, the episode list) is ready.
    LaunchedEffect(pendingDetailPlay.value, episodes, metaLoaded) {
        val want = pendingDetailPlay.value ?: return@LaunchedEffect
        if (want.substringBefore(":") != imdb) return@LaunchedEffect
        if (cur.type == "series" && episodes.isEmpty() && !metaLoaded) return@LaunchedEffect
        pendingDetailPlay.value = null
        start(playTarget.target)
    }

    // ── Trailers (official uploads only when there are any, newest first) ──
    val allVideos = tm?.videos.orEmpty()
    val videos = remember(allVideos) {
        (if (allVideos.any { it.official }) allVideos.filter { it.official } else allVideos).sortedByDescending { it.publishedAt ?: it.year ?: "" }
    }
    val launchTrailer = rememberTrailerLauncher { pendingDetailPlay.value = cur.id }
    fun openVideos(list: List<TmVideo>, i: Int) = launchTrailer(list.map { it.key }, list.map { it.name }, i, cur.name)
    fun playTrailer() {
        val chain = videos.filter { it.type == "Trailer" } + videos.filter { it.type == "Teaser" }
        val list = chain.ifEmpty { videos }
        val fallback = trailer
        when {
            list.isNotEmpty() -> openVideos(list, 0)
            fallback != null -> launchTrailer(listOf(fallback), listOf("Trailer"), 0, cur.name)
            else -> Snack.show("No trailer found for this title")
        }
    }

    // ── Page state ──
    val tabs = remember(seasons) { if (seasons.isNotEmpty()) TdTab.entries.toList() else TdTab.entries.filter { it != TdTab.Episodes } }
    var tab by remember(tabs) { mutableStateOf(tabs.first()) }
    var content by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf(false) }
    val playReq = remember { FocusRequester() }
    val tabReqs = remember { TdTab.entries.associateWith { FocusRequester() } }
    var heroFocusTick by remember { mutableIntStateOf(0) }
    var tabFocusTick by remember { mutableIntStateOf(0) }

    // Initial focus on Play, and back on Play whenever the hero comes back.
    LaunchedEffect(content, heroFocusTick) {
        if (content) return@LaunchedEffect
        repeat(4) { if (playReq.tdTry()) return@LaunchedEffect; delay(60) }
    }
    LaunchedEffect(tabFocusTick) {
        if (tabFocusTick == 0) return@LaunchedEffect
        repeat(4) { if (tabReqs[tab]?.tdTry() == true) return@LaunchedEffect; delay(60) }
    }
    fun toHero() { content = false; heroFocusTick++ }
    fun showTab(tb: TdTab) { tab = tb; content = true; tabFocusTick++ }

    BackHandler(enabled = pick == null && !actions) { if (content) toHero() else onBack() }

    val saved = myList.any { it.id == cur.id }
    val backdrop = tm?.backdrop ?: art?.backdrop ?: backdropOf(cur)
    val bg = Bg

    // Sources page ("Play manually"): shown alone, so the remote can't wander onto the page behind it.
    pick?.let { tg ->
        BackHandler { pick = null; if (content) tabFocusTick++ else heroFocusTick++ }
        Box(Modifier.fillMaxSize().background(bg)) {
            SourcesScreen(vm, cur, tg, resumeFor(tg.videoId), onBack = { pick = null; if (content) tabFocusTick++ else heroFocusTick++ }) { s, all, resume -> play(s, all, resume) }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(bg)) {
        // ── Backdrop with left / bottom gradients (in the tab view: dimmed with a flat overlay, no layer alpha) ──
        AsyncImage(backdrop ?: tm?.poster ?: cur.poster, null, Modifier.fillMaxSize(), alignment = BiasAlignment(0f, -0.5f), contentScale = ContentScale.Crop)
        val shadeH = remember(bg) { Brush.horizontalGradient(0f to bg, 0.18f to bg, 0.42f to bg.copy(alpha = 0.75f), 0.7f to Color.Transparent) }
        val shadeV = remember(bg) { Brush.verticalGradient(0.55f to Color.Transparent, 0.92f to bg, 1f to bg) }
        Box(Modifier.fillMaxSize().background(shadeH))
        Box(Modifier.fillMaxSize().background(shadeV))
        if (content) Box(Modifier.fillMaxSize().background(bg.copy(alpha = 0.8f)))

        if (!content) {
            TdHero(
                cur, tm, art, ratings, playTarget, saved, hasTrailer = videos.isNotEmpty() || trailer != null, playReq = playReq,
                onPlay = { start(playTarget.target) },
                onTrailer = { playTrailer() },
                onAllTrailers = { if (videos.isNotEmpty()) showTab(TdTab.Videos) else playTrailer() },
                onLibrary = { vm.toggleList(cur) },
                onMore = { actions = true },
            )
        } else {
            Box(Modifier.fillMaxSize().padding(top = 92.dp)) {
                when (tab) {
                    TdTab.Episodes -> TdEpisodes(seasons, season, { season = it }, shown, progress, done, tm?.runtime, playTarget.target.videoId,
                        onPlay = { ep -> start(target(ep)) }, onSources = { ep -> start(target(ep), forceSources = true) })
                    TdTab.More -> TdMoreLikeThis(cur, picks, more,
                        onPick = { r ->
                            scope.launch {
                                val id = runCatching { vm.tmdbToImdb(r) }.getOrNull()
                                if (id.isNullOrBlank()) Snack.show("This title isn't available to open yet")
                                else onOpen(Meta(id = id, type = if (r.kind == "tv") "series" else "movie", name = r.title, poster = r.poster))
                            }
                        },
                        onMeta = onOpen)
                    TdTab.Cast -> TdCast(tm?.cast?.takeIf { it.isNotEmpty() } ?: cur.cast.orEmpty().take(20).map { TmCast(it, "", null) }) { c ->
                        if (c.id.isNotEmpty()) onPerson(c) else Snack.show("Add a TMDB key in Integrations to see actor pages")
                    }
                    TdTab.Videos -> TdVideos(videos, trailer) { list, i ->
                        if (list.isEmpty()) playTrailer() else openVideos(list, i)
                    }
                }
            }
        }

        // ── Tabs: bottom of the hero, top of the tab view (same composable → focus survives the switch) ──
        Row(
            Modifier.align(if (content) Alignment.TopStart else Alignment.BottomStart)
                .padding(start = 70.dp, top = 52.dp, bottom = 34.dp)
                // Entering the tab row (DOWN from the actions, UP from the content) always lands on the current tab.
                .focusProperties { enter = { tabReqs[tab] ?: FocusRequester.Default } }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tb ->
                val on = tb == tab
                Column(
                    Modifier.tdFocus(10.dp, scale = 1.04f, requester = tabReqs[tb],
                        onFocused = { if (tab != tb || !content) { tab = tb; content = true } },
                        onKey = { k -> if (k == Key.DirectionUp) { toHero(); true } else false },
                        onClick = { tab = tb; content = true })
                        .clip(RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(tb.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else TdMuted, maxLines = 1)
                    Box(Modifier.padding(top = 4.dp).width(if (on) 26.dp else 0.dp).height(1.5.dp).background(if (on) BrandRed else Color.Transparent))
                }
            }
        }

        if (actions) TdMoreSheet(
            playTarget, saved, hasVideos = videos.isNotEmpty() || trailer != null,
            onDismiss = { actions = false },
            onManual = { start(playTarget.target, forceSources = true) },
            onFromStart = { start(playTarget.target, fromStart = true) },
            onLibrary = { vm.toggleList(cur) },
            onTrailer = { playTrailer() },
            onAllTrailers = { if (videos.isNotEmpty()) showTab(TdTab.Videos) else playTrailer() },
            onTogether = { TvFlags.openTogether = true; start(playTarget.target) },
        )
    }
}

// ───────────── Hero (s5) ─────────────

@Composable
private fun TdHero(
    m: Meta, tm: TmdbInfo?, art: Art?, ratings: List<Rating>, play: TdPlayTarget, saved: Boolean, hasTrailer: Boolean, playReq: FocusRequester,
    onPlay: () -> Unit, onTrailer: () -> Unit, onAllTrailers: () -> Unit, onLibrary: () -> Unit, onMore: () -> Unit,
) {
    Column(Modifier.padding(start = 70.dp, top = 64.dp).width(400.dp)) {
        // Title logo (or a big title)
        val logo = tm?.logo ?: art?.logo ?: logoOf(m)
        var logoFailed by remember(logo) { mutableStateOf(false) }
        if (logo != null && !logoFailed) {
            AsyncImage(logo, m.name, Modifier.widthIn(max = 300.dp).height(78.dp), alignment = Alignment.BottomStart, contentScale = ContentScale.Fit,
                onError = { logoFailed = true })
        } else {
            Text(m.name, fontSize = 40.sp, lineHeight = 42.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val release = tm?.release
        if (tdIsNew(release)) Text("New Release", Modifier.padding(top = 7.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF5A5F))

        // year · runtime · certification · languages
        val year = release?.take(4) ?: m.releaseInfo?.take(4)
        val runtime = tm?.runtime ?: m.runtime
        val cert = tm?.certification
        val lang = tdLanguage(tm?.language)
        val bits = listOfNotNull(year, runtime)
        if (bits.isNotEmpty() || cert != null || lang != null) {
            // (text, isCertificationChip)
            val parts = bits.map { it to false } + listOfNotNull(cert?.let { it to true }, lang?.let { it to false })
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                parts.forEachIndexed { i, (txt, chip) ->
                    if (i > 0) Box(Modifier.size(2.5.dp).clip(CircleShape).background(TdMuted))
                    if (chip) Text(txt, Modifier.tdChip(), fontSize = 7.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    else Text(txt, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE6E8EE), maxLines = 1)
                }
            }
        }

        // Ratings: IMDb · TMDB · Trakt
        fun v(src: String) = ratings.firstOrNull { it.source == src }?.value?.trim()?.takeIf { it.isNotEmpty() }
        val imdbV = v("IMDb") ?: m.imdbRating?.trim()?.takeIf { it.isNotEmpty() && it != "0" && it != "0.0" }
        val tmdbV = v("TMDB")
        val traktV = v("Trakt")
        if (imdbV != null || tmdbV != null || traktV != null) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
                imdbV?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("IMDb", Modifier.clip(RoundedCornerShape(2.5.dp)).background(Color(0xFFF5C518)).padding(horizontal = 3.5.dp, vertical = 1.dp),
                            fontSize = 8.sp, lineHeight = 9.sp, fontWeight = FontWeight.Black, color = Color.Black)
                        Text(it, Modifier.padding(start = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFF5C518))
                    }
                }
                tmdbV?.let { Text("TMDB $it", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF01B4E4)) }
                traktV?.let { Text("Trakt $it", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFFF3B45)) }
            }
        }

        (tm?.overview ?: m.description)?.takeIf { it.isNotBlank() }?.let { d ->
            Text(d, Modifier.padding(top = 8.dp), fontSize = 11.sp, lineHeight = 16.sp, color = Color(0xFFD6D9E1), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        val genres = tm?.genres?.takeIf { it.isNotEmpty() } ?: m.genres.orEmpty()
        if (genres.isNotEmpty()) Text(genres.take(4).joinToString(" · "), Modifier.padding(top = 7.dp), fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold,
            color = TdMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)

        // ── Actions: Play · Trailer · Library · More ──
        Row(Modifier.padding(top = 17.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // The phone's red Play pill, same proportions; focus = it grows and glows red (no white ring).
            // Exact phone red when focused (no glow, which washed it out on Fire TV); dimmed a little when not focused.
            var playFocused by remember { mutableStateOf(false) }
            Box(Modifier.onFocusChanged { playFocused = it.hasFocus }.tdFocus(26.dp, scale = 1.06f, requester = playReq, ring = false, glow = false, onClick = onPlay)) {
                RedPill(play.label, Modifier.width(if (play.label.length > 10) 236.dp else 184.dp).graphicsLayer { alpha = if (playFocused) 1f else 0.78f }, height = 52, fontSize = 21, onClick = onPlay) {
                    if (play.frac > 0f) Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 18.dp, vertical = 4.dp).fillMaxWidth().height(2.dp)
                        .clip(RoundedCornerShape(1.dp)).background(Color(0x4DFFFFFF))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(play.frac).background(Color.White))
                    }
                }
            }
            // Trailer: OK = newest official trailer; hold OK (or MENU) = every trailer & video, newest first.
            TdRound("Trailer · hold for all", alpha = if (hasTrailer) 1f else 0.45f, onKey = { k -> if (k == Key.Menu) { onAllTrailers(); true } else false },
                onLong = onAllTrailers, onClick = onTrailer) {
                TdClapper(22.dp)
            }
            TdRound(if (saved) "In Library" else "Library", onClick = onLibrary) {
                Icon(if (saved) Icons.Default.Check else Icons.Default.Add, if (saved) "Remove from Library" else "Add to Library", Modifier.size(23.dp), tint = Color.White)
            }
            TdRound("More", onClick = onMore) {
                Icon(Icons.Default.MoreHoriz, "More", Modifier.size(23.dp), tint = Color.White)
            }
        }
    }
}

/** Outlined certification chip ("U/A 13+"). */
private fun Modifier.tdChip(): Modifier = this
    .drawWithCache {
        val r = 3.dp.toPx()
        val st = Stroke(0.75.dp.toPx())
        onDrawWithContent {
            drawContent()
            drawRoundRect(Color(0x66FFFFFF), cornerRadius = CornerRadius(r, r), style = st)
        }
    }
    .padding(horizontal = 4.dp, vertical = 1.dp)

/** 50dp round action button (label shown in the focus hint under it). */
@Composable
private fun TdRound(label: String, alpha: Float = 1f, onKey: ((Key) -> Boolean)? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.size(50.dp), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier.onFocusChanged { focused = it.hasFocus }
                .tdFocus(25.dp, onKey = onKey, onLong = onLong, onClick = onClick)
                .size(50.dp).clip(CircleShape).background(TdRoundBg)
                .drawWithCache {
                    val st = Stroke(1.dp.toPx())
                    onDrawWithContent { drawContent(); drawCircle(TdRoundRim, radius = size.minDimension / 2f - st.width / 2f, style = st) }
                }
                .graphicsLayer { this.alpha = alpha },
            Alignment.Center,
            content = content,
        )
        // Small label under the focused button (keeps the row as clean as the mockup).
        if (focused) Text(label, Modifier.offset(y = 58.dp).wrapContentWidth(unbounded = true), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = TdSoft,
            maxLines = 1, softWrap = false)
    }
}

/** Clapperboard with a red play triangle (Trailer button). */
@Composable
private fun TdClapper(dp: Dp) = Canvas(Modifier.size(dp)) {
    val u = size.width / 24f
    val sw = 1.9f * u
    drawRoundRect(Color.White, topLeft = Offset(3 * u, 6 * u), size = Size(18 * u, 13 * u), cornerRadius = CornerRadius(2.5f * u),
        style = Stroke(width = sw, join = StrokeJoin.Round))
    drawLine(Color.White, Offset(3 * u, 10 * u), Offset(21 * u, 10 * u), strokeWidth = sw)
    listOf(7f, 12f, 17f).forEach { x -> drawLine(Color.White, Offset(x * u, 6 * u), Offset((x + 2) * u, 10 * u), strokeWidth = sw, cap = StrokeCap.Round) }
    val tri = Path().apply { moveTo(10.5f * u, 12.6f * u); lineTo(10.5f * u, 16.8f * u); lineTo(14.1f * u, 14.7f * u); close() }
    drawPath(tri, Color(0xFFE50914))
}

// ───────────── Episodes (s6) ─────────────

@Composable
private fun TdEpisodes(
    seasons: List<Int>, season: Int, onSeason: (Int) -> Unit, shown: List<Video>, progress: List<Progress>, done: Set<String>,
    runtime: String?, nextId: String, onPlay: (Video) -> Unit, onSources: (Video) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        // Seasons (focus = select)
        LazyColumn(Modifier.padding(start = 70.dp).width(124.dp).fillMaxHeight(), contentPadding = PaddingValues(top = 22.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(seasons, key = { it }) { sn ->
                val on = sn == season
                Box(
                    Modifier.fillMaxWidth().tdFocus(8.dp, scale = 1.03f, onFocused = { onSeason(sn) }, onClick = { onSeason(sn) })
                        .clip(RoundedCornerShape(8.dp)).background(if (on) Color(0x1AFFFFFF) else Color.Transparent),
                ) {
                    if (on) Box(Modifier.align(Alignment.CenterStart).width(2.dp).height(22.dp).background(BrandRed))
                    Text(if (sn == 0) "Specials" else "Season $sn", Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else TdMuted, maxLines = 1)
                }
            }
        }
        // Episodes
        LazyColumn(Modifier.padding(start = 26.dp).weight(1f).fillMaxHeight(), contentPadding = PaddingValues(top = 18.dp, bottom = 40.dp, end = 40.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.id }) { ep ->
                val p = progress.firstOrNull { it.key == ep.id }
                val watched = ep.id in done
                val frac = if (p != null && p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f
                val resuming = frac > 0f && !watched && (p?.durationMs ?: 0L) > 0L && frac < 0.95f
                Row(
                    Modifier.fillMaxWidth()
                        .tdFocus(10.dp, scale = 1.0f, onKey = { k -> if (k == Key.Menu) { onSources(ep); true } else false }, onClick = { onPlay(ep) })
                        .clip(RoundedCornerShape(10.dp)).padding(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(170.dp, 96.dp).clip(RoundedCornerShape(7.dp)).background(Surface1)) {
                        AsyncImage(ep.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.align(Alignment.BottomStart).padding(7.dp).size(23.dp).clip(CircleShape).background(Color(0x8C000000)), Alignment.Center) {
                            Icon(if (watched) Icons.Default.Check else Icons.Default.PlayArrow, null, Modifier.size(14.dp), tint = Color.White)
                        }
                        if (frac > 0f && !watched) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.5.dp).background(Color(0x55FFFFFF))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(BrandRed))
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text("E${ep.episode} · ${ep.title ?: ep.name ?: "Episode ${ep.episode}"}", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            val info = listOfNotNull(tdDate(ep.released), runtime).joinToString(" · ")
                            if (info.isNotEmpty()) Text(info, fontSize = 9.sp, fontWeight = FontWeight.Medium, color = TdMuted, maxLines = 1)
                            val badge = when {
                                resuming -> "Resume${p?.let { timeLeft(it) }?.let { " $it" } ?: ""}"
                                ep.id == nextId && !watched -> "Up next"
                                watched -> "Watched"
                                else -> null
                            }
                            if (badge != null) Text((if (info.isNotEmpty()) " · " else "") + badge, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                                color = if (watched && !resuming) TdMuted else BrandRed, maxLines = 1)
                        }
                        (ep.overview ?: ep.description)?.takeIf { it.isNotBlank() }?.let {
                            Text(it, Modifier.padding(top = 5.dp), fontSize = 9.5.sp, lineHeight = 13.5.sp, color = TdSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ───────────── More Like This ─────────────

@Composable
private fun TdMoreLikeThis(m: Meta, picks: List<LikedPick>, more: List<Meta>, onPick: (TmRec) -> Unit, onMeta: (Meta) -> Unit) {
    if (picks.isEmpty() && more.isEmpty()) { TdEmpty("Nothing similar found yet."); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (picks.isNotEmpty()) item(key = "picks") {
            Column {
                TdRowTitle("Because you're looking at ${m.name}")
                LazyRow(contentPadding = PaddingValues(horizontal = 70.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(picks, key = { it.rec.kind + it.rec.tmdbId }) { pk -> TdPoster(pk.rec.poster, pk.rec.title, "${pk.match}% match") { onPick(pk.rec) } }
                }
            }
        }
        if (more.isNotEmpty()) item(key = "more") {
            Column {
                val g = m.genres.orEmpty().firstOrNull()
                TdRowTitle(if (g != null) "More $g" else "More like this")
                LazyRow(contentPadding = PaddingValues(horizontal = 70.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(more, key = { i, x -> "$i:${x.id}" }) { _, x -> TdPoster(x.poster, x.name, x.releaseInfo?.take(4)) { onMeta(x) } }
                }
            }
        }
    }
}

@Composable
private fun TdRowTitle(text: String) =
    Text(text, Modifier.padding(start = 70.dp, end = 70.dp, bottom = 10.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun TdPoster(poster: String?, title: String, sub: String?, onClick: () -> Unit) =
    Column(Modifier.width(100.dp)) {
        Box(Modifier.size(100.dp, 150.dp).tdFocus(8.dp, onClick = onClick).clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1F2433), Color(0xFF0B0D14))))) {
            Text(title, Modifier.align(Alignment.BottomStart).padding(8.dp), fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 4)
            if (poster != null) AsyncImage(poster, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (!sub.isNullOrBlank()) Text(sub, Modifier.padding(top = 6.dp), fontSize = 9.sp, fontWeight = FontWeight.Medium, color = TdMuted, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }

// ───────────── Cast ─────────────

@Composable
private fun TdCast(cast: List<TmCast>, onPerson: (TmCast) -> Unit) {
    if (cast.isEmpty()) { TdEmpty("No cast information."); return }
    LazyRow(Modifier.padding(top = 30.dp), contentPadding = PaddingValues(horizontal = 70.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        itemsIndexed(cast, key = { i, c -> "$i:${c.id}:${c.name}" }) { _, c ->
            Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(80.dp).tdFocus(40.dp, onClick = { onPerson(c) }).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFF2A2F3D), Color(0xFF1B1E28)))),
                    Alignment.Center,
                ) {
                    Text(c.name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TdMuted)
                    if (c.photo != null) AsyncImage(c.photo, c.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Text(c.name, Modifier.padding(top = 9.dp), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center)
                if (c.role.isNotBlank()) Text(c.role, Modifier.padding(top = 2.dp), fontSize = 9.sp, color = TdMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

// ───────────── Trailers & More ─────────────

@Composable
private fun TdVideos(videos: List<TmVideo>, fallback: String?, onPlay: (List<TmVideo>, Int) -> Unit) {
    val list = remember(videos, fallback) {
        videos.ifEmpty { fallback?.let { listOf(TmVideo(it, "Trailer", "Trailer", null)) }.orEmpty() }
    }
    if (list.isEmpty()) { TdEmpty("No trailers found for this title."); return }
    val newest = list.firstOrNull()
    LazyRow(Modifier.padding(top = 30.dp), contentPadding = PaddingValues(horizontal = 70.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(list, key = { _, v -> v.key }) { i, v ->
            Column(Modifier.width(200.dp)) {
                Box(Modifier.size(200.dp, 112.dp).tdFocus(9.dp, onClick = { onPlay(list, i) }).clip(RoundedCornerShape(9.dp)).background(Surface1)) {
                    AsyncImage("https://i.ytimg.com/vi/${v.key}/mqdefault.jpg", v.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    if (v == newest && list.size > 1) Text("NEW", Modifier.padding(6.dp).clip(RoundedCornerShape(4.dp)).background(BrandRed)
                        .padding(horizontal = 5.dp, vertical = 1.dp), fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
                    Box(Modifier.align(Alignment.Center).size(30.dp).clip(CircleShape).background(Color(0x8C000000)), Alignment.Center) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp), tint = Color.White)
                    }
                }
                Text(v.name, Modifier.padding(top = 7.dp), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(v.type, tdDate(v.publishedAt) ?: v.year).joinToString(" · "), Modifier.padding(top = 2.dp), fontSize = 9.sp,
                    color = TdMuted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TdEmpty(text: String) =
    Text(text, Modifier.padding(start = 70.dp, top = 40.dp), fontSize = 12.sp, color = TdMuted)

// ───────────── More (⋯) ─────────────

@Composable
private fun TdMoreSheet(
    play: TdPlayTarget, saved: Boolean, hasVideos: Boolean, onDismiss: () -> Unit,
    onManual: () -> Unit, onFromStart: () -> Unit, onLibrary: () -> Unit, onTrailer: () -> Unit, onAllTrailers: () -> Unit,
    onTogether: () -> Unit = {},
) = PremiumSheet(onDismiss) { close ->
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(5) { if (first.tdTry()) return@LaunchedEffect; delay(60) } }
    Text("More", fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    TdSheetItem("Play manually (choose source)", Icons.Default.List, first, close, onManual)
    TdSheetItem("Watch together (start a room)", Icons.Default.Groups, null, close, onTogether)
    if (play.resumeMs > 0) TdSheetItem("Start from beginning", Icons.Default.Replay, null, close, onFromStart)
    TdSheetItem(if (saved) "Remove from Library" else "Add to Library", if (saved) Icons.Default.Check else Icons.Default.Add, null, close, onLibrary)
    if (hasVideos) {
        TdSheetItem("Watch trailer", Icons.Default.Movie, null, close, onTrailer)
        TdSheetItem("All trailers & videos", Icons.Default.VideoLibrary, null, close, onAllTrailers)
    }
}

@Composable
private fun TdSheetItem(label: String, icon: ImageVector, req: FocusRequester?, close: () -> Unit, action: () -> Unit) =
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).tdFocus(10.dp, scale = 1.02f, requester = req, onClick = { close(); action() })
            .clip(RoundedCornerShape(10.dp)).background(Color(0x0FFFFFFF)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = Color.White)
        Text(label, Modifier.padding(start = 12.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
