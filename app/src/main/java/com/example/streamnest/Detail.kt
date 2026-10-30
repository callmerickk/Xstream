package com.example.streamnest

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the big Play button will do. */
private data class PlayTarget(val target: SourceTarget, val label: String, val resumeMs: Long, val frac: Float)

/**
 * Set to a title id (e.g. by the trailer player's "Play the movie") to make that title page press Play
 * as soon as it is shown again.
 */
val pendingDetailPlay = mutableStateOf<String?>(null)

private val TitleCardBg = Color(0xFF0E0F14)
private val CardRim = Color(0xFF1D1E25)
private val RoundBg = Color(0xFF15161C)
private val TitleMuted = Color(0xFF8A94A8)
private val LinkRed = Color(0xFFFF3B45)

/** Tap and long-press without a ripple. */
private fun Modifier.tapHold(onTap: () -> Unit, onHold: () -> Unit): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val haptics = LocalHapticFeedback.current
    pointerInput(Unit) {
        detectTapGestures(onTap = { tap() }, onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); hold() })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    vm: AppViewModel, meta0: Meta, onBack: () -> Unit, onOpen: (Meta) -> Unit = {}, sourcesFor: String? = null,
    onPlay: (Screen.Player) -> Unit,
    /** Tap on a cast member (opens the actor page). */
    onPerson: (TmCast) -> Unit = {},
    /** Unused now (trailers open TrailerActivity directly); kept so existing callers compile. */
    @Suppress("UNUSED_PARAMETER") onTrailer: (Screen.Trailer) -> Unit = {},
) {
    var meta by remember { mutableStateOf(meta0) }
    // The audio language you last chose for this title goes first when its links are ranked here too.
    remember(meta0.id) { LangPick.current = vm.titleLang(meta0.id); 0 }
    var metaLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(meta0.id) { vm.fullMeta(meta0)?.let { meta = it }; metaLoaded = true }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val progress0 by vm.progress.collectAsStateWithLifecycle()
    val epProg by vm.epProgress.collectAsStateWithLifecycle()
    // Xstream: Continue Watching's entries plus every episode's own saved place.
    val progress = remember(progress0, epProg) { progress0 + epProg.values.filter { e -> progress0.none { it.key == e.key } } }
    val done by vm.doneKeys.collectAsStateWithLifecycle()
    val myList by vm.myList.collectAsStateWithLifecycle()
    val texts by vm.texts.collectAsStateWithLifecycle()
    val imdb = meta.id.substringBefore(":")

    var art by remember(imdb) { mutableStateOf<Art?>(null) }
    var tm by remember(imdb) { mutableStateOf<TmdbInfo?>(null) }
    LaunchedEffect(imdb) { tm = vm.tmdbInfo(imdb, meta.type) }
    var ratings by remember(imdb) { mutableStateOf<List<Rating>>(emptyList()) }
    var more by remember(imdb) { mutableStateOf<List<Meta>>(emptyList()) }
    var trailer by remember(imdb) { mutableStateOf<String?>(null) }
    // No extra artwork is downloaded: the page shows the poster Home already showed (it's in the image cache), so the
    // connection is free for readying the stream.
    LaunchedEffect(imdb) { ratings = vm.mdbRatings(imdb) }
    LaunchedEffect(imdb) { trailer = runCatching { vm.trailerId(meta) }.getOrNull() }
    LaunchedEffect(imdb, meta.genres) {
        val g = meta.genres.orEmpty().take(2)
        if (g.isNotEmpty() && isOn(texts["detailMore"] ?: "yes")) more = runCatching { vm.genreTitles(g, listOf(meta.type)) }.getOrDefault(emptyList()).filter { it.id != meta.id }.take(20)
    }
    // "If you liked": TMDB recommendations first, then (when an AI key is set) the AI's ranking.
    var picks by remember(imdb) { mutableStateOf<List<LikedPick>>(emptyList()) }
    var aiPicked by remember(imdb) { mutableStateOf(false) }
    LaunchedEffect(imdb, tm) {
        val info = tm ?: return@LaunchedEffect
        if (!isOn(texts["detailMore"] ?: "yes")) return@LaunchedEffect
        val cands = runCatching { vm.likedCandidates(imdb, info) }.getOrDefault(emptyList())
        picks = tmdbPicks(info, cands)
        val ai = runCatching { vm.aiLikedPicks(imdb, meta.name, (info.release ?: meta.releaseInfo)?.take(4), meta.type, cands) }.getOrNull()
        if (!ai.isNullOrEmpty()) { picks = ai; aiPicked = true }
    }

    var pick by remember { mutableStateOf<SourceTarget?>(null) }
    var actions by remember { mutableStateOf(false) }
    var showVideos by remember { mutableStateOf(false) }
    var shareFriends by remember { mutableStateOf(false) }
    var planWatch by remember { mutableStateOf(false) }
    var openedSources by remember { mutableStateOf(false) }
    val subsJob = remember(pick?.videoId) { pick?.let { pk -> scope.async { runCatching { vm.subtitles(meta.type, pk.videoId) }.getOrDefault(emptyList()) } } }

    val episodes = remember(meta) { meta.videos.orEmpty().filter { it.season != null && it.episode != null }.sortedWith(compareBy({ it.season }, { it.episode })) }
    val seasons = remember(episodes) { episodes.mapNotNull { it.season }.distinct().sorted().let { s -> s.filter { it > 0 } + s.filter { it == 0 } } }

    val autoMode = t("autoPlayMode")
    val external = t("playerChoice") == "external"
    val quick = vm.qsActive()
    fun resumeFor(vid: String): Long = progress.firstOrNull { it.key == vid }
        ?.takeIf { it.durationMs <= 0 || it.positionMs < it.durationMs * 0.95 }?.positionMs ?: 0L
    fun target(ep: Video) = SourceTarget(ep.id, "${meta.name}  S${ep.season}E${ep.episode}", "S${ep.season} E${ep.episode}", ep.title ?: ep.name, ep.thumbnail)

    // The big button: resume where you left off, or the next episode / start.
    val playRect = remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val playTarget = remember(meta, progress, done, episodes) {
        if (episodes.isEmpty()) {
            val p = progress.firstOrNull { it.key == meta.id }
            val r = resumeFor(meta.id)
            PlayTarget(SourceTarget(meta.id, meta.name, null, null, null), if (r > 0) "Resume ${fmtTime(r)}" else "Play", r,
                if (p != null && p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f)
        } else {
            val mine = progress.filter { it.key.startsWith(meta.id + ":") }.maxByOrNull { it.updated }
            val ep = mine?.let { m -> episodes.firstOrNull { it.id == m.key } }
                ?: episodes.lastOrNull { it.id in done }?.let { last -> episodes.getOrNull(episodes.indexOf(last) + 1) }
                ?: episodes.firstOrNull { (it.season ?: 0) > 0 } ?: episodes.first()
            val r = resumeFor(ep.id)
            val p = progress.firstOrNull { it.key == ep.id }
            PlayTarget(target(ep), (if (r > 0) "Resume" else "Play") + " S${ep.season} E${ep.episode}", r,
                if (p != null && p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f)
        }
    }
    var season by remember(seasons, playTarget.target.videoId) {
        mutableIntStateOf(episodes.firstOrNull { it.id == playTarget.target.videoId }?.season ?: seasons.firstOrNull() ?: 1)
    }
    val shown = remember(episodes, season) { episodes.filter { it.season == season }.distinctBy { it.id } }

    // Instant play: as soon as the page opens, find the streams and save the start of the best link to storage
    // (Prepare.kt). The line under the Play button says what happened.
    val level = vm.playerPref("quality").ifEmpty { texts["qsQuality"] ?: "auto" }
    val instantOn = !external && (quick || autoMode != "manual")
    LaunchedEffect(playTarget.target.videoId, quick, level) {
        // CNCVerse (Vegamovies…) is asked from the moment the page opens, alongside MovieBox: its server needs time to
        // collect the links, so they are there for the HD upgrade once the video plays.
        SlowAddons.watch(vm, meta.type, playTarget.target.videoId)
        if (!instantOn) { runCatching { if (quick) vm.quickStreams(meta.type, playTarget.target.videoId) else vm.allStreams(meta.type, playTarget.target.videoId) }; return@LaunchedEffect }
        Prepare.start(vm, ctx, meta.type, playTarget.target.videoId, playTarget.resumeMs, runtimeMs(meta.runtime), quick, level, meta)
    }
    // Plugin mode: the Upgrade to 4K plugins are asked as soon as the page opens (their answers are kept), so the player
    // can ready the 4K file the moment the first picture of Quick Play is on.
    LaunchedEffect(playTarget.target.videoId) {
        if (!vm.pluginMode()) return@LaunchedEffect
        PluginSources.noteMeta(meta)
        val names = vm.qs4kNames()
        if (names.isNotEmpty()) runCatching { vm.streamsFlow(meta.type, playTarget.target.videoId, only = names).collect { } }
    }
    val prep by Prepare.state.collectAsStateWithLifecycle()
    // Play pressed while it is still saving: stay here; start as soon as enough is saved (or fall back).
    var waitingFor by remember { mutableStateOf<SourceTarget?>(null) }
    var waitingResume by remember { mutableLongStateOf(0L) }
    var showPrepInfo by remember { mutableStateOf(false) }
    // Play pressed while Quick Play's CloudStream plugins are still getting their best link ready (≤ 20 s from the page
    // opening): wait for them, then play whatever is ready (CloudStream, else MovieBox).
    var csWait by remember { mutableStateOf<SourceTarget?>(null) }
    // A ready, paused player made for this page: freed when you leave without playing (Play takes it over first).
    val prepKey by rememberUpdatedState(playTarget.target.videoId)
    DisposableEffect(Unit) { onDispose { Preloader.release(prepKey) } }

    /** Play button / episode tap: Quick Start or auto modes go straight to the player, manual opens the sources page. */
    fun start(t: SourceTarget, forceSources: Boolean = false, fromStart: Boolean = false) {
        // Not out yet: say when it airs instead of searching every server for nothing.
        episodes.firstOrNull { it.id == t.videoId }?.let { ep -> airsIn(ep.released)?.let { Snack.show("${t.epLabel ?: "This episode"} · $it"); return } }
        // Downloaded: play the file on the phone (no internet needed).
        if (!forceSources) Downloads.readyFile(ctx, t.videoId)?.let { f ->
            onPlay(Screen.Player(t.title, android.net.Uri.fromFile(f).toString(), emptyList(), key = t.videoId, image = t.image ?: meta.background ?: meta.poster,
                resumeMs = if (fromStart) 0L else resumeFor(t.videoId), type = meta.type, meta = meta, source = "Downloaded", addon = "Downloads"))
            return
        }
        if (fromStart) {
            // Start from the beginning: drop any video prepared at the resume point and play from 0.
            Preloader.release(t.videoId)
            if (!forceSources) {
                onPlay(Screen.Player(t.title, "", emptyList(), key = t.videoId, image = t.image ?: meta.background ?: meta.poster,
                    resumeMs = 0L, type = meta.type, meta = meta, find = true, quick = quick))
                return
            }
        }
        when {
            forceSources -> pick = t
            instantOn && !fromStart && Prepare.csPendingFor(t.videoId) -> csWait = t
            instantOn && !fromStart -> {
                // Instant: the prepared link plays straight from storage; if it isn't ready yet, wait here.
                val st = Prepare.state.value
                PlayTiming.begin(t.title, "Play pressed · light: " + when {
                    st == null || st.key != t.videoId -> "not prepared yet"
                    st.ready -> "green"; st.failed -> "amber/red"; else -> "grey (still preparing)"
                } + (st?.takeIf { it.key == t.videoId }?.status?.let { " · $it" } ?: ""))
                // No waiting here any more: the player opens at once.
                // A Turbo link prepared long ago has probably expired (plugin links last a few minutes): looked up again.
                val stale = st != null && st.key == t.videoId && vm.pluginMode() && System.currentTimeMillis() - st.at > 8 * 60_000L
                if (stale) { PlayTiming.mark("prepared link is ${(System.currentTimeMillis() - st!!.at) / 60_000} min old — looking it up again"); Prepare.cancel(); Preloader.release(t.videoId); forgetStreams(t.videoId) }
                val mine = st?.takeIf { it.key == t.videoId && !stale }
                val item = mine?.item
                val url = item?.stream?.url
                val img = t.image ?: meta.background ?: meta.poster
                if (mine != null && item != null && url != null) {
                    // A checked link (paused player waiting, saved, or still saving): play it now — the rest streams.
                    PlayTiming.mark("opening the player · ${Ranker.labelOf(Ranker.groupKey(item))} · ${Prepare.qualityName(item.resolution())}" +
                        (if (Preloader.has(t.videoId)) " · ready paused player waiting" else " · no paused player") +
                        " · saved before Play: ${"%.1f".format(mine.saved / 1_048_576f)} MB")
                    Prepare.stopSaving()
                    onPlay(Screen.Player(t.title, url, mine.subs, key = t.videoId, image = img, resumeMs = resumeFor(t.videoId), type = meta.type, meta = meta,
                        source = item.stream.name ?: item.addonName, addon = item.addonName,
                        candidates = mine.list.filter { it.stream.url != url && (!quick || vm.isQuick(it)) }, quick = quick, instant = true))
                } else {
                    // Nothing checked yet: the player takes the links found so far and races the best ones itself.
                    PlayTiming.mark("player opens now — it races the best links itself" + (mine?.list?.size?.takeIf { it > 0 }?.let { " ($it links already found)" } ?: ""))
                    Prepare.cancel()
                    onPlay(Screen.Player(t.title, "", mine?.subs.orEmpty(), key = t.videoId, image = img, resumeMs = resumeFor(t.videoId), type = meta.type, meta = meta,
                        find = true, quick = quick, candidates = mine?.list.orEmpty()))
                }
            }
            quick && !external -> onPlay(Screen.Player(t.title, "", emptyList(), key = t.videoId, image = t.image ?: meta.background ?: meta.poster,
                resumeMs = resumeFor(t.videoId), type = meta.type, meta = meta, find = true, quick = true))
            quick && external -> scope.launch {
                val list = runCatching { vm.quickStreams(meta.type, t.videoId) }.getOrDefault(emptyList())
                val s = vm.pickQuick(list, texts["qsQuality"] ?: "auto")
                if (s != null) openExternal(ctx, s.stream.url!!, t.title) else pick = t
            }
            !external && autoMode != "manual" -> onPlay(Screen.Player(t.title, "", emptyList(), key = t.videoId, image = t.image ?: meta.background ?: meta.poster,
                resumeMs = resumeFor(t.videoId), type = meta.type, meta = meta, find = true))
            else -> pick = t
        }
    }

    LaunchedEffect(csWait) {
        val t = csWait ?: return@LaunchedEffect
        PlayTiming.mark("Play pressed — waiting for CloudStream (at most 20 s from the page opening)")
        withTimeoutOrNull(21_000) { Prepare.csPending.first { it != t.videoId } }
        csWait = null
        start(t)
    }
    LaunchedEffect(waitingFor) {
        val t = waitingFor ?: return@LaunchedEffect
        val t0 = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val st = Prepare.state.value
            val waited = android.os.SystemClock.elapsedRealtime() - t0
            val item = st?.item
            val url = item?.stream?.url
            when {
                // Ready (enough saved), or still saving after 8 s but the link is known: play it (the rest streams).
                st != null && st.key == t.videoId && item != null && url != null && (st.ready || waited > 8_000) -> {
                    waitingFor = null
                    PlayTiming.mark("opening the player · ${Ranker.labelOf(Ranker.groupKey(item))} · ${Prepare.qualityName(item.resolution())}" +
                        (if (Preloader.has(t.videoId)) " · ready paused player waiting" else " · no paused player") +
                        (if (st.ready) "" else " · not fully saved yet (waited ${waited / 1000} s)") +
                        " · saved before Play: ${"%.1f".format(st.saved / 1_048_576f)} MB")
                    Prepare.stopSaving()
                    onPlay(Screen.Player(t.title, url, st.subs, key = t.videoId, image = t.image ?: meta.background ?: meta.poster,
                        resumeMs = waitingResume, type = meta.type, meta = meta, source = item.stream.name ?: item.addonName, addon = item.addonName,
                        candidates = st.list.filter { it.stream.url != url && (!quick || vm.isQuick(it)) }, quick = quick, instant = true))
                    return@LaunchedEffect
                }
                // Nothing could be prepared (or it takes too long): the normal search inside the player.
                // Nothing to play after 8 s (addons slow / every link refused): the player takes over at once.
                st == null || st.key != t.videoId || st.failed || (waited > 8_000 && item == null) || waited > 25_000 -> {
                    waitingFor = null
                    Prepare.cancel()
                    PlayTiming.mark("nothing prepared → the player searches by itself" + (st?.status?.let { " (was: $it)" } ?: ""))
                    onPlay(Screen.Player(t.title, "", emptyList(), key = t.videoId, image = t.image ?: meta.background ?: meta.poster,
                        resumeMs = waitingResume, type = meta.type, meta = meta, find = true, quick = quick))
                    return@LaunchedEffect
                }
            }
            delay(150)
        }
    }

    fun play(s: StreamItem, all: List<StreamItem>, resume: Long) {
        val tg = pick ?: return
        scope.launch {
            val extra = withTimeoutOrNull(1500) { subsJob?.await() }.orEmpty()
            val subs = s.stream.subtitles.orEmpty() + extra
            pick = null
            if (external) openExternal(ctx, s.stream.url!!, tg.title)
            else onPlay(Screen.Player(tg.title, s.stream.url!!, subs, key = tg.videoId, image = tg.image ?: meta.background ?: meta.poster,
                resumeMs = resume, type = meta.type, meta = meta, source = s.stream.name ?: s.addonName, addon = s.addonName,
                // If this link fails, the player tries the same source's other links first.
                candidates = all.filter { it !== s }.sortedBy { if (it.addonName == s.addonName) 0 else 1 }, manual = true))
        }
    }

    // "Play manually" from Continue Watching: open the sources page for that video.
    LaunchedEffect(sourcesFor, episodes) {
        val id = sourcesFor ?: return@LaunchedEffect
        if (openedSources) return@LaunchedEffect
        if (id.contains(":") && episodes.isEmpty()) return@LaunchedEffect // wait for the episode list
        openedSources = true
        pick = episodes.firstOrNull { it.id == id }?.let { target(it) } ?: SourceTarget(id, meta.name, null, null, null)
    }

    // "Play the movie" from the trailer player: press Play once the page (and, for series, the episode list) is ready.
    LaunchedEffect(pendingDetailPlay.value, episodes, metaLoaded) {
        val want = pendingDetailPlay.value ?: return@LaunchedEffect
        if (want.substringBefore(":") != imdb) return@LaunchedEffect
        if (meta.type == "series" && episodes.isEmpty() && !metaLoaded) return@LaunchedEffect
        pendingDetailPlay.value = null
        // A voice request for one episode ("season 1 episode 4"): that episode; otherwise Continue / the first.
        val ep = if (want.count { it == ':' } >= 2) episodes.firstOrNull { it.id == want } else null
        start(ep?.let { target(it) } ?: playTarget.target)
    }

    // ── Trailers & videos (TMDB): official uploads only when there are any, newest first ──
    val allVideos = tm?.videos.orEmpty()
    val anyOfficial = allVideos.any { it.official }
    val videos = remember(allVideos) {
        (if (anyOfficial) allVideos.filter { it.official } else allVideos).sortedByDescending { it.publishedAt ?: it.year ?: "" }
    }
    val hasTrailer = videos.isNotEmpty() || trailer != null
    // Trailers play in TrailerActivity (a plain Activity: the WebView video is never black there).
    // "Play the movie" there = press Play here (same path as the trailer route used: pendingDetailPlay).
    val launchTrailer = rememberTrailerLauncher { pendingDetailPlay.value = meta.id }
    // Trailers in your language first (Hindi before English…), then the original; a dubbed track inside a YouTube
    // video counts too (YtDirect picks it).
    val tLangs = remember { vm.qsLangs().mapNotNull { YtDirect.langCode(it) } }
    LaunchedEffect(tLangs) { YtDirect.prefLangs = tLangs }
    fun langOrder(v: TmVideo) = v.lang?.let { tLangs.indexOf(it) }?.takeIf { it >= 0 } ?: tLangs.size
    fun openTrailers(list: List<TmVideo>, start: Int) {
        showVideos = false
        TrailerActivity.nextLangs = list.map { it.lang.orEmpty() }
        launchTrailer(list.map { it.key }, list.map { it.name }, start, meta.name)
    }
    /** Trailer button tap: the newest official trailer in your language (then the other trailers and teasers). */
    fun playTrailer() {
        val chain = videos.filter { it.type == "Trailer" }.sortedBy { langOrder(it) } + videos.filter { it.type == "Teaser" }.sortedBy { langOrder(it) }
        val list = chain.ifEmpty { videos }
        val fallback = trailer
        when {
            list.isNotEmpty() -> openTrailers(list, 0)
            fallback != null -> launchTrailer(listOf(fallback), listOf("Trailer"), 0, meta.name)
            else -> Snack.show("No trailer found for this title")
        }
    }
    /** Trailer button long-press: every trailer & video, or a short note when there's nothing to list. */
    fun holdTrailer() {
        if (videos.isNotEmpty()) showVideos = true
        else if (trailer != null) Snack.show("Only one trailer for this title. Tap to play it.")
        else Snack.show("No trailers found for this title")
    }
    fun share() {
        runCatching {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "${meta.name}${if (imdb.startsWith("tt")) " https://www.imdb.com/title/$imdb/" else ""}"), "Share"))
        }
    }

    val saved = myList.any { it.id == meta.id }
    var expanded by remember { mutableStateOf(false) }
    var castOpen by remember { mutableStateOf(false) }
    val backdrop = art?.backdrop ?: meta.poster ?: backdropOf(meta)
    val artwork = meta.poster ?: backdrop

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            // ── Top bar (stays put while the page scrolls) ──
            Box(Modifier.fillMaxWidth().background(Bg).statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp)) {
                TopRound(Modifier.align(Alignment.CenterStart), onBack) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", Modifier.size(26.dp), tint = Color.White)
                }
                Box(Modifier.align(Alignment.Center)) { Wordmark(t("appName"), 22.sp) }
                Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TopRound(Modifier, { WatchlistAsk.toggle(vm, meta) }) {
                        Icon(if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (saved) "Remove from Watchlist" else "Add to Watchlist",
                            Modifier.size(20.dp), tint = if (saved) BrandRed else Color.White)
                    }
                    TopRound(Modifier, { share() }) { Icon(Icons.Default.Share, "Share", Modifier.size(19.dp), tint = Color.White) }
                }
            }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
                // ── Poster card: artwork, title, description, year · runtime · rating, scores ──
                item {
                    val shape = RoundedCornerShape(28.dp)
                    // Trailer mode: the trailer (your language first) starts in the picture after a moment; once it plays
                    // the picture grows and the title, text and ratings slide down, so the whole frame is the trailer.
                    val heroId = remember(videos, trailer) { videos.filter { it.type == "Trailer" }.sortedBy { langOrder(it) }.firstOrNull()?.key ?: trailer }
                    var tArm by remember(meta.id) { mutableStateOf(false) }
                    var tPlay by remember(meta.id) { mutableStateOf(false) }
                    var tStop by remember(meta.id) { mutableStateOf(false) }
                    var tMuted by remember { mutableStateOf(HeroSound.muted) }
                    LaunchedEffect(heroId) { tArm = false; if (heroId != null) { delay(1_800); tArm = true } }
                    val grow by animateFloatAsState(if (tPlay && !tStop) 1f else 0f, tween(650, easing = FastOutSlowInEasing), label = "trailerMode")
                    val extra = 170f * grow
                    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(TitleCardBg).border(1.dp, CardRim, shape)) {
                        AsyncImage(artwork, meta.name, Modifier.fillMaxWidth().height((300f + extra).dp).background(Surface1).graphicsLayer { alpha = 1f - grow },
                            alignment = BiasAlignment(0f, -0.4f), contentScale = ContentScale.Crop)
                        if (heroId != null && tArm && !tStop && isOn(texts["cardTrailers"] ?: "yes")) {
                            TrailerVideo(heroId, tMuted, Modifier.fillMaxWidth().height((300f + extra).dp), fitWhole = false, backdrop = backdrop,
                                onPlaying = { tPlay = true }, onEnded = { tStop = true; tPlay = false }, onError = { tStop = true; tPlay = false })
                        }
                        Box(Modifier.padding(top = (150f + extra).dp).fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color.Transparent, TitleCardBg))))
                        if (tPlay && !tStop) Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0x88000000)).clickable { tMuted = !tMuted; HeroSound.muted = tMuted }, Alignment.Center) {
                                Icon(if (tMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (tMuted) "Unmute" else "Mute", Modifier.size(20.dp), tint = Color.White)
                            }
                            Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0x88000000)).clickable { tStop = true; tPlay = false }, Alignment.Center) {
                                Icon(Icons.Default.Close, "Stop trailer", Modifier.size(20.dp), tint = Color.White)
                            }
                        }
                        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = (248f + extra * 1.2f).dp, bottom = 20.dp)) {
                            Text(meta.name, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                            (tm?.overview ?: meta.description)?.takeIf { it.isNotBlank() }?.let { d ->
                                Text(d, Modifier.padding(top = 8.dp).quietClick { expanded = !expanded }, fontSize = 15.sp, lineHeight = 22.sp,
                                    color = Color(0xFFD7DAE2), maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                            }
                            val year = tm?.release?.take(4) ?: meta.releaseInfo
                            val runtime = tm?.runtime ?: meta.runtime
                            val cert = tm?.certification
                            if (year != null || runtime != null || cert != null) {
                                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    year?.let { Text(it, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
                                    runtime?.let { Text(it, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
                                    cert?.let {
                                        Text(it, Modifier.border(1.5.dp, Color(0xFF4A4D57), RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
                                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFAAB0BD))
                                    }
                                }
                            }
                            if (isOn(texts["detailRatings"] ?: "yes")) RatingsRow(ratings, meta.imdbRating)
                        }
                    }
                }
                // ── Play + Trailer / Library / More ──
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        val waitingHere = waitingFor != null
                        val pillLabel = if (waitingHere) "Preparing… ${((prep?.progress ?: 0f) * 100).toInt()}%" else playTarget.label
                        Box(Modifier.weight(1f).tourTarget("play").onGloballyPositioned { playRect.value = it.boundsInRoot() }) {
                            RedPill(pillLabel, Modifier.fillMaxWidth(), fontSize = if (pillLabel.length > 9) 19 else 22,
                                onClick = { if (!waitingHere) start(playTarget.target) }, onLongClick = { actions = true }) {
                                if (playTarget.frac > 0f) Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 28.dp, vertical = 7.dp).fillMaxWidth().height(3.dp)
                                    .clip(RoundedCornerShape(2.dp)).background(Color(0x4DFFFFFF))) {
                                    Box(Modifier.fillMaxHeight().fillMaxWidth(playTarget.frac).background(Color.White))
                                }
                            }
                            // Status light on the Play button's corner (long-press it for the details).
                            val st = prep
                            if (instantOn && st != null && st.key == playTarget.target.videoId)
                                PrepDot(st, Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = (-10).dp).tourTarget("light")) { showPrepInfo = !showPrepInfo }
                        }
                        // Play · Watchlist · Download · Trailers (long-press Play: play manually, share, friends & plans)
                        LabeledRound("Watchlist", Modifier.quietClick { WatchlistAsk.toggle(vm, meta) }, rim = if (saved) Color(0x8CE50914) else Color(0xFF23252D)) {
                            Icon(if (saved) Icons.Default.Check else Icons.Default.Add, if (saved) "In Watchlist" else "Add to Watchlist", Modifier.size(24.dp), tint = Color.White)
                        }
                        if (!BuildConfig.IS_TV) {
                            val dls by Downloads.list.collectAsStateWithLifecycle()
                            val dl = dls.lastOrNull { it.key == playTarget.target.videoId }
                            var dlPct by remember(dl?.dmId) { mutableIntStateOf(-1) }
                            LaunchedEffect(dl?.dmId) {
                                while (dl != null) {
                                    val st = Downloads.status(ctx, dl)
                                    dlPct = if (st.done) 100 else if (st.total > 0) (st.bytes * 100 / st.total).toInt() else 0
                                    if (st.done || st.failed) break
                                    delay(1500)
                                }
                            }
                            val dlDone = dlPct >= 100
                            LabeledRound(if (dl == null) "Download" else if (dlDone) "Saved" else "${dlPct.coerceAtLeast(0)}%", Modifier.quietClick {
                                if (dl == null) {
                                    val tg = playTarget.target
                                    Snack.show("Finding the best file to download…")
                                    scope.launch { Snack.show(Downloads.start(vm, ctx, meta, tg), millis = 4500) }
                                } else if (dlDone) Snack.show("Downloaded: plays without internet. Remove it in Downloads.")
                                else Snack.show("Downloading… ${dlPct.coerceAtLeast(0)}%")
                            }, rim = if (dl != null) Color(0x8CE50914) else Color(0xFF23252D)) {
                                Icon(if (dlDone) Icons.Default.DownloadDone else Icons.Default.Download, "Download", Modifier.size(24.dp), tint = Color.White)
                            }
                        }
                        LabeledRound("Trailers", Modifier.tourTarget("trailer").alpha(if (hasTrailer) 1f else 0.45f).tapHold(onTap = { if (videos.size > 1) showVideos = true else playTrailer() }, onHold = { holdTrailer() }), rim = Color(0x8CE50914)) { ClapperGlyph(22.dp) }
                    }
                }
                // The status light's details (long-press the light), for screenshots.
                item {
                    val st = prep
                    LaunchedEffect(showPrepInfo) { if (showPrepInfo) { delay(8000); showPrepInfo = false } }
                    if (showPrepInfo && st != null && st.key == playTarget.target.videoId) Text(
                        Public.say(st.status), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E2027))
                            .border(1.dp, Color(0xFF3A3D48), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                        fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFFE6E8EE),
                    )
                }
                // ── Cast (actors only) ──
                if (isOn(texts["detailCast"] ?: "yes")) {
                    val cast = tm?.cast?.takeIf { it.isNotEmpty() } ?: meta.cast.orEmpty().take(20).map { TmCast(it, "", null) }
                    if (cast.isNotEmpty()) {
                        val collapsed = 10
                        item {
                            SectionHeader("Cast", if (cast.size > collapsed) (if (castOpen) "Show Less" else "Show More") else null) { castOpen = !castOpen }
                        }
                        val tapCast: (TmCast) -> Unit = { c ->
                            if (c.id.isNotEmpty()) onPerson(c) else Snack.show("Add a TMDB key in Integrations to see actor pages")
                        }
                        if (!castOpen) item {
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(cast.take(collapsed)) { c -> CastItem(c) { tapCast(c) } }
                            }
                        } else item {
                            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                val cols = ((maxWidth + 16.dp) / (84.dp + 16.dp)).toInt().coerceAtLeast(3)
                                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                    cast.chunked(cols).forEach { row ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            row.forEach { c -> CastItem(c) { tapCast(c) } }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // ── Episodes (series) ──
                if (seasons.isNotEmpty()) {
                    item { SectionHeader("Episodes") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(seasons) { sn ->
                                val on = sn == season
                                Text(if (sn == 0) "Specials" else "Season $sn",
                                    Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) Color.White else RoundBg).quietClick { season = sn }
                                        .padding(horizontal = 16.dp, vertical = 9.dp),
                                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.Black else Color(0xFFC9CED9))
                            }
                        }
                    }
                    items(shown, key = { it.id }) { ep ->
                        val p = progress.firstOrNull { it.key == ep.id }
                        val watched = ep.id in done
                        val frac = if (p != null && p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f
                        EpisodeRow(ep, watched, frac, isNext = ep.id == playTarget.target.videoId,
                            onClick = { start(target(ep)) }, onLongClick = { start(target(ep), forceSources = true) })
                    }
                }
                // ── If you liked … ──
                if (isOn(texts["detailMore"] ?: "yes") && (picks.isNotEmpty() || more.isNotEmpty())) {
                    item {
                        Column {
                            Text("If you liked ${meta.name}", Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = when {
                                picks.isNotEmpty() && aiPicked -> "Picked for you by AI from what you're watching"
                                picks.isNotEmpty() -> "Similar titles from TMDB"
                                else -> "More in the same genres"
                            }
                            Text(sub,
                                Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp), fontSize = 13.sp, color = TitleMuted)
                        }
                    }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (picks.isNotEmpty()) items(picks, key = { it.rec.kind + it.rec.tmdbId }) { pk ->
                                val r = pk.rec
                                LikedCard(r.poster, r.title, pk.match, pk.why) {
                                    scope.launch {
                                        val id = vm.tmdbToImdb(r)
                                        if (id.isNullOrBlank()) Snack.show("This title isn't available to open yet")
                                        else onOpen(Meta(id = id, type = if (r.kind == "tv") "series" else "movie", name = r.title, poster = r.poster))
                                    }
                                }
                            } else items(more, key = { it.id }) { m ->
                                val g = m.genres.orEmpty().firstOrNull { it in meta.genres.orEmpty() }
                                LikedCard(m.poster, m.name, null, g?.let { "Same genre: $it" } ?: (m.releaseInfo?.take(4) ?: "")) { onOpen(m) }
                            }
                        }
                    }
                }
                if (tm != null) item {
                    Text("Powered by TMDB", Modifier.fillMaxWidth().padding(16.dp), fontSize = 13.sp, color = TitleMuted, textAlign = TextAlign.End)
                }
            }
        }

        if (actions) {
          val actionList = if (!BuildConfig.IS_TV) buildList {
                // Phone: only what isn't already beside the Play button.
                add(TitleAction("Play manually", Icons.Default.PlayArrow) { start(playTarget.target, forceSources = true) })
                add(TitleAction("Share", Icons.Default.Share) { share() })
                add(TitleAction("Share with friends & plan a watch", Icons.Default.Groups) { shareFriends = true })
            } else buildList {
                add(TitleAction("Play manually", Icons.Default.PlayArrow) { start(playTarget.target, forceSources = true) })
                if (playTarget.resumeMs > 0) add(TitleAction("Start from beginning", Icons.Default.Replay) { start(playTarget.target, fromStart = true) })
                add(TitleAction(if (saved) "Remove from Watchlist" else "Add to Watchlist", if (saved) Icons.Default.Check else Icons.Default.Add) { WatchlistAsk.toggle(vm, meta) })
                if (hasTrailer) add(TitleAction("Watch trailer", Icons.Default.Movie) { playTrailer() })
                if (videos.size > 1) add(TitleAction("All trailers & videos", Icons.Default.VideoLibrary) { showVideos = true })
                if (!BuildConfig.IS_TV) {
                    val dl = Downloads.list.value.lastOrNull { it.key == playTarget.target.videoId }
                    if (dl == null) add(TitleAction("Download" + (playTarget.target.epLabel?.let { " $it" } ?: ""), Icons.Default.Download) {
                        val tg = playTarget.target
                        Snack.show("Finding the best file to download…")
                        scope.launch { Snack.show(Downloads.start(vm, ctx, meta, tg), millis = 4500) }
                    }) else add(TitleAction("Remove download", Icons.Default.Delete) { Downloads.remove(ctx, dl); Snack.show("Download removed") })
                }
                add(TitleAction("Share", Icons.Default.Share) { share() })
                if (!BuildConfig.IS_TV) {
                    add(TitleAction("Share with friends", Icons.Default.Groups) { shareFriends = true })
                    add(TitleAction("Plan a watch", Icons.Default.Event) { planWatch = true })
                }
            }
          // Xstream phone: the Play button lifts out like a poster — trailer playing in the card, ratings, then every action.
          if (!BuildConfig.IS_TV) androidx.compose.ui.window.Dialog({ actions = false }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
              TitleLiftMenu(vm, meta, playRect.value, onDetails = {}, onManual = {}, onWatchlist = {}, onClose = { actions = false },
                  actions = actionList, subtitle = playTarget.target.epLabel)
          } else TitleActionsSheet(
            meta.name, playTarget.target.epLabel ?: if (meta.type == "series") "Series" else "Movie",
            playTarget.target.image ?: backdrop, playTarget.frac, progress.firstOrNull { it.key == playTarget.target.videoId }?.let { timeLeft(it) },
            actionList,
            onDismiss = { actions = false },
          )
        }
        if (shareFriends) ShareWithFriends(meta, Social.friends.collectAsState().value) { shareFriends = false }
        if (planWatch) PlanFlow(meta, null, Social.friends.collectAsState().value, null, onDone = { planWatch = false })

        if (showVideos) VideosSheet(meta.name, videos, anyOfficial, onDismiss = { showVideos = false }) { list, i -> openTrailers(list, i) }

        // Full-screen sources page slides up over the details.
        val tgt = pick
        AnimatedVisibility(
            tgt != null, Modifier.fillMaxSize(),
            enter = slideInVertically(spring(dampingRatio = 0.9f, stiffness = 380f)) { it / 3 } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(220)) { it / 3 } + fadeOut(tween(180)),
        ) {
            var last by remember { mutableStateOf(tgt) }
            if (tgt != null) last = tgt
            last?.let { tg ->
                BackHandler { pick = null }
                SourcesScreen(vm, meta, tg, resumeFor(tg.videoId), onBack = { pick = null }) { s, all, resume -> play(s, all, resume) }
            }
        }
    }
}

// ───────────── Pieces ─────────────

@Composable
private fun TopRound(modifier: Modifier, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) =
    Box(modifier.size(42.dp).clip(CircleShape).background(RoundBg).quietClick(onClick), Alignment.Center, content = content)

/** 52dp round button with a small label under it. [modifier] carries the click handling. */
@Composable
private fun LabeledRound(label: String, modifier: Modifier, rim: Color = Color(0xFF23252D), content: @Composable BoxScope.() -> Unit) =
    Column(Modifier.padding(top = 4.dp).width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier.size(52.dp).clip(CircleShape).background(RoundBg).border(1.dp, rim, CircleShape), Alignment.Center, content = content)
        Text(label, Modifier.padding(top = 6.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF9AA3B5), maxLines = 1)
    }

@Composable
private fun SectionHeader(text: String, action: String? = null, onAction: () -> Unit = {}) =
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null) Text(action, Modifier.quietClick(onAction), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LinkRed)
    }

/** Clapperboard with a red play triangle (the Trailer button). */
@Composable
private fun ClapperGlyph(dp: Dp) = Canvas(Modifier.size(dp)) {
    val u = size.width / 24f
    val sw = 1.9f * u
    drawRoundRect(Color.White, topLeft = Offset(3 * u, 6 * u), size = Size(18 * u, 13 * u), cornerRadius = CornerRadius(2.5f * u),
        style = Stroke(width = sw, join = StrokeJoin.Round))
    drawLine(Color.White, Offset(3 * u, 10 * u), Offset(21 * u, 10 * u), strokeWidth = sw)
    listOf(7f, 12f, 17f).forEach { x -> drawLine(Color.White, Offset(x * u, 6 * u), Offset((x + 2) * u, 10 * u), strokeWidth = sw, cap = StrokeCap.Round) }
    val tri = Path().apply { moveTo(10.5f * u, 12.6f * u); lineTo(10.5f * u, 16.8f * u); lineTo(14.1f * u, 14.7f * u); close() }
    drawPath(tri, Color(0xFFE50914))
}

@Composable
private fun CastItem(c: TmCast, onClick: () -> Unit) =
    Column(Modifier.width(84.dp).quietClick(onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF2A2F3D), Color(0xFF1B1E28)))).border(2.dp, Color(0xFF1F2027), CircleShape),
            Alignment.Center,
        ) {
            Text(c.name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TitleMuted)
            if (c.photo != null) AsyncImage(c.photo, c.name, Modifier.fillMaxSize().padding(2.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        }
        Text(c.name, Modifier.padding(top = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (c.role.isNotBlank()) Text(c.role, Modifier.padding(top = 2.dp), fontSize = 11.5.sp, color = TitleMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }

@Composable
private fun LikedCard(poster: String?, title: String, match: Int?, why: String, onClick: () -> Unit) =
    Column(Modifier.width(118.dp).quietClick(onClick)) {
        Box(Modifier.fillMaxWidth().height(176.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF1F2433), Color(0xFF0B0D14))))) {
            Text(title, Modifier.align(Alignment.BottomStart).padding(10.dp), fontSize = 15.sp, lineHeight = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 4)
            if (poster != null) AsyncImage(poster, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (match != null) Text("$match% match",
                Modifier.padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(10.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        if (why.isNotBlank()) Text(why, Modifier.padding(top = 7.dp), fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = Color(0xFF9AA3B5), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

// ───────────── Ratings row ─────────────

@Composable
private fun RatingsRow(ratings: List<Rating>, imdbRating: String?) {
    fun v(src: String) = ratings.firstOrNull { it.source == src }?.value?.trim()?.takeIf { it.isNotEmpty() }
    val items = listOfNotNull(
        (v("IMDb") ?: imdbRating?.trim()?.takeIf { it.isNotEmpty() && it != "0" && it != "0.0" })?.let { "IMDb" to it },
        v("TMDB")?.let { "TMDB" to it },
        v("Trakt")?.let { "Trakt" to it },
        v("Letterboxd")?.let { "Letterboxd" to it },
        v("Metacritic")?.let { "Metacritic" to it },
    )
    if (items.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = if (items.size >= 4) Arrangement.SpaceBetween else Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (src, value) ->
            val color = when (src) {
                "IMDb" -> Color(0xFFF5C518); "TMDB" -> Color(0xFF01B4E4); "Trakt" -> LinkRed
                "Letterboxd" -> Color(0xFF2BD67B); else -> Color(0xFFFFC145)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RatingBadge(src)
                Text(value, Modifier.padding(start = 6.dp), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RatingBadge(src: String) {
    when (src) {
        "IMDb" -> Text("IMDb", Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFF5C518)).padding(horizontal = 5.dp, vertical = 2.dp),
            fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.Black, color = Color.Black, letterSpacing = (-0.3).sp)
        "TMDB" -> Column(Modifier.width(28.dp)) {
            val c = Color(0xFF90CEA1)
            Text("THE", fontSize = 7.5.sp, lineHeight = 7.5.sp, fontWeight = FontWeight.Black, color = c)
            Text("MOVIE", fontSize = 7.5.sp, lineHeight = 7.5.sp, fontWeight = FontWeight.Black, color = c, maxLines = 1)
            Box(Modifier.padding(vertical = 1.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(Brush.horizontalGradient(listOf(c, Color(0xFF01B4E4)))))
            Text("DB", fontSize = 7.5.sp, lineHeight = 7.5.sp, fontWeight = FontWeight.Black, color = c)
        }
        "Trakt" -> Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0xFFED1C24)), Alignment.Center) {
            Canvas(Modifier.size(13.dp)) {
                val u = size.width / 24f
                val sw = 3f * u
                drawLine(Color.White, Offset(7 * u, 7 * u), Offset(17 * u, 17 * u), strokeWidth = sw)
                drawLine(Color.White, Offset(17 * u, 7 * u), Offset(11 * u, 13 * u), strokeWidth = sw)
            }
        }
        "Letterboxd" -> Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White), Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                listOf(Color(0xFFFF8000), Color(0xFF00E054), Color(0xFF40BCF4)).forEach { Box(Modifier.size(5.dp).clip(CircleShape).background(it)) }
            }
        }
        else -> Box(Modifier.size(22.dp).clip(CircleShape).background(Color.Black).border(2.dp, Color(0xFFFFC145), CircleShape), Alignment.Center) {
            Text("m", fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
    }
}

// ───────────── Trailers & videos sheet ─────────────

/** "3 days ago" for an ISO date ("2024-05-01T16:00:05.000Z"). */
private fun relativeDate(iso: String?): String? {
    val d = iso?.take(10)?.takeIf { it.length == 10 } ?: return null
    val at = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(d)?.time
    }.getOrNull() ?: return null
    val days = ((System.currentTimeMillis() - at) / 86_400_000L).coerceAtLeast(0L)
    fun ago(n: Long, unit: String) = if (n == 1L) "1 $unit ago" else "$n ${unit}s ago"
    return when {
        days == 0L -> "Today"
        days == 1L -> "Yesterday"
        days < 7 -> ago(days, "day")
        days < 30 -> ago(days / 7, "week")
        days < 365 -> ago(days / 30, "month")
        else -> ago(days / 365, "year")
    }
}

@Composable
private fun VideosSheet(title: String, videos: List<TmVideo>, official: Boolean, onDismiss: () -> Unit, onPlay: (List<TmVideo>, Int) -> Unit) =
    PremiumSheet(onDismiss) { _ ->
        var filter by remember { mutableStateOf("All") }
        val groups = listOf("All" to null, "Trailers" to "Trailer", "Teasers" to "Teaser", "Clips" to "Clip")
        val shown = remember(filter, videos) {
            val ty = groups.firstOrNull { it.first == filter }?.second
            if (ty == null) videos else videos.filter { it.type == ty }
        }
        val newest = videos.firstOrNull()
        Text("Trailers & videos", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text("$title · ${if (official) "official channels only · " else ""}newest first", Modifier.padding(top = 2.dp),
            fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = TitleMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LazyRow(Modifier.padding(top = 14.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups.filter { (_, ty) -> ty == null || videos.any { it.type == ty } }) { (label, _) ->
                val on = filter == label
                Text(if (label == "All") "All ${videos.size}" else label,
                    Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) Color.White else Color(0xFF1A1B22)).quietClick { filter = label }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.Black else Color(0xFFC9CED9))
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            itemsIndexed(shown, key = { _, v -> v.key }) { i, v ->
                Column {
                    Row(Modifier.fillMaxWidth().quietClick { onPlay(shown, i) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(150.dp, 84.dp).clip(RoundedCornerShape(12.dp)).background(Surface1)) {
                            AsyncImage("https://i.ytimg.com/vi/${v.key}/hqdefault.jpg", v.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (v == newest) Text("NEW", Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(BrandRed).padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                            Box(Modifier.align(Alignment.Center).size(32.dp).clip(CircleShape).background(Color(0x8C000000)).border(1.dp, Color(0x4DFFFFFF), CircleShape), Alignment.Center) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp), tint = Color.White)
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(v.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (v.official) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Official channel", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Color(0xFFC9CED9))
                                Box(Modifier.padding(start = 4.dp).size(14.dp).clip(CircleShape).background(Color(0xFF9AA3B5)), Alignment.Center) {
                                    Icon(Icons.Default.Check, "Verified", Modifier.size(10.dp), tint = Color.Black)
                                }
                            }
                            Text(listOfNotNull(v.type, relativeDate(v.publishedAt) ?: v.year).joinToString(" · "), Modifier.padding(top = 3.dp),
                                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TitleMuted, maxLines = 1)
                        }
                    }
                    HorizontalDivider(color = Color(0xFF17181E))
                }
            }
        }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(ep: Video, watched: Boolean, frac: Float, isNext: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val alpha by animateFloatAsState(if (watched) 0.6f else 1f, label = "w")
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 8.dp)
            .graphicsLayer { this.alpha = alpha },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Surface1)) {
            AsyncImage(ep.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (watched) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(Color(0xCC000000)), Alignment.Center) {
                Icon(Icons.Default.Check, "Watched", Modifier.size(14.dp))
            } else Icon(Icons.Default.PlayCircle, null, Modifier.align(Alignment.Center).size(30.dp), tint = Color(0xD9FFFFFF))
            if (frac > 0f && !watched) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Accent))
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("E${ep.episode}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9AA3B5))
                if (isNext) Text(if (frac > 0f) "CONTINUE" else "UP NEXT", Modifier.padding(start = 8.dp).clip(RoundedCornerShape(5.dp)).background(Accent.copy(alpha = 0.2f))
                    .padding(horizontal = 5.dp, vertical = 1.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Accent, letterSpacing = 0.5.sp)
            }
            Text(ep.title ?: ep.name ?: "Episode ${ep.episode}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            (ep.overview ?: ep.description)?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 12.sp, color = Color(0xFF9AA3B5), maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
            }
            airsIn(ep.released)?.let { Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Accent) }
        }
    }
}

/**
 * Status light on the Play button: grey blinking = finding / saving · green = saved, plays instantly ·
 * amber = couldn't pre-save (plays the normal way) · red = no stream at all. Long-press = details.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PrepDot(st: Prepared, modifier: Modifier, onLong: () -> Unit) {
    val color = when {
        // Green = a paused player has the video open (instant). Saved but not opened yet: still the grey blink.
        st.ready && (st.playerReady || BuildConfig.IS_TV) -> Color(0xFF3DDC6B)
        st.failed && st.list.isEmpty() -> Color(0xFFFF3B3B)
        st.failed -> Color(0xFFFFB020)
        else -> Color(0xFFB9BEC9)
    }
    val busy = !(st.ready && (st.playerReady || BuildConfig.IS_TV)) && !st.failed
    val blink = androidx.compose.animation.core.rememberInfiniteTransition(label = "prepDot")
    val a by blink.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(600), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "blink",
    )
    // 28 dp touch area, 11 dp light.
    Box(modifier.size(28.dp).combinedClickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
        onClick = {}, onLongClick = onLong), Alignment.Center) {
        Box(Modifier.size(11.dp).graphicsLayer { alpha = if (busy) a else 1f }.clip(CircleShape).background(Color(0x8C000000)).padding(1.5.dp).clip(CircleShape).background(color))
    }
}

/**
 * "Airs in 3 days" / "Airs in 5 h" / "Airs in 40 min" for an episode that isn't out yet (null once it is out).
 * The release time is used when the catalogue gives one, else the start of that day.
 */
fun airsIn(released: String?): String? {
    val r = released?.trim()?.takeIf { it.length >= 10 } ?: return null
    val at = (if (r.length >= 19) runCatching { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(r.take(19))?.time }.getOrNull() else null)
        ?: runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(r.take(10))?.time }.getOrNull()
        ?: return null
    val left = at - System.currentTimeMillis()
    if (left <= 0) return null
    val mins = left / 60_000
    return when {
        mins < 60 -> "Airs in ${mins.coerceAtLeast(1)} min"
        mins < 24 * 60 -> "Airs in ${mins / 60} h"
        else -> { val d = ((mins + 24 * 60 - 1) / (24 * 60)); if (d == 1L) "Airs tomorrow" else "Airs in $d days" }
    }
}
