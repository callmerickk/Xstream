package com.example.streamnest

import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/** 0.9.75 (from Xstream): Home style "Classic" — Xstream's original deck (Settings › Layout › Home style). */
@Composable
internal fun ClassicDeck(
    vm: AppViewModel, items: List<DeckItem>, pager: PagerState, onScreen: State<Boolean>, myList: List<Meta>,
    auto: Boolean, secs: Int, trailers: Boolean,
    onOpen: (Meta) -> Unit, onToggle: (Meta) -> Unit,
) {
    if (items.isEmpty()) return
    val n = items.size
    fun at(page: Int) = items[((page % n) + n) % n]
    // Start (and restart when the cards change) in the middle of the long strip, on the first card.
    LaunchedEffect(n) { if (n > 1 && (pager.currentPage < n * 2 || pager.currentPage > n * (DeckLoops - 2))) pager.scrollToPage(n * (DeckLoops / 2)) }
    // Phones: 83 % of the width. Tablets / landscape: never taller than ~68 % of the screen (cards keep their shape).
    val cfg = LocalConfiguration.current
    // StreamX's card (same size and shape), centred, with the deck stacked behind it on both sides.
    val cardW = minOf(cfg.screenWidthDp * 0.83f, cfg.screenHeightDp * 0.68f * 0.76f).coerceAtLeast(240f).dp
    val cardH = cardW / 0.76f
    // Only flips when the deck crosses half-visible, so this read recomposes Deck rarely (never per scroll frame).
    val visible = onScreen.value
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    // Once you swipe the cards yourself, auto-slide stops and the card you land on plays its trailer (muted).
    var userTook by remember { mutableStateOf(false) }
    var trailerId by remember { mutableStateOf<String?>(null) }
    // Unmuted once = unmuted for the next cards too (until you mute again), also after leaving Home.
    var muted by remember { mutableStateOf(HeroSound.muted) }
    // Hold a card: trailers on / off for the cards (remembered while the app is open).
    var trailerMode by remember { mutableStateOf(HeroSound.trailers) }
    // Which trailer has really started playing (its first frame is on screen): the card's poster then fades out.
    var videoFor by remember { mutableStateOf<String?>(null) }
    val videoOn = trailerId != null && videoFor == trailerId
    val scope = rememberCoroutineScope()
    // "▶ Trailer" on a card: full-screen trailer (TrailerActivity). "Play the movie" opens the title and presses Play.
    var trailerFor by remember { mutableStateOf<Meta?>(null) }
    val launchTrailer = rememberTrailerLauncher {
        trailerFor?.let { m -> pendingDetailPlay.value = m.id; onOpen(m) }
    }
    LaunchedEffect(dragged) {
        if (dragged) { userTook = true; trailerId = null; videoFor = null }
        // Like Hotstar: after you swipe, the cards start gliding on their own again once you leave them alone for 8 s
        // (not while card trailers are on — those wait for you).
        else if (userTook && !trailerMode) { delay(8_000); if (!trailerMode) userTook = false }
    }
    var holdTick by remember { mutableIntStateOf(0) }
    /** Trailer mode just turned on (true) / off (false): the banner and the glow around the front card. */
    var modeBanner by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    val deckScope = rememberCoroutineScope()
    // Trailer mode plays one round: each card's trailer, then the next card, to the last one — then it turns itself off.
    fun nextTrailer() {
        trailerId = null; videoFor = null
        if (!trailerMode) { userTook = false; return }
        val next = pager.settledPage + 1
        if (pager.settledPage % n + 1 < n) deckScope.launch {
            pager.animateScrollToPage(next, animationSpec = tween(900, easing = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f)))
        } else {
            trailerMode = false; HeroSound.trailers = false; userTook = false
            modeBanner = false to System.currentTimeMillis()
        }
    }
    // The screen stays on during the round (afterwards your normal screen timeout applies again).
    val deckView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(trailerMode) { deckView.keepScreenOn = trailerMode; onDispose { deckView.keepScreenOn = false } }
    LaunchedEffect(pager.settledPage, userTook, trailers, trailerMode, holdTick, visible) {
        trailerId = null
        videoFor = null
        // No trailer (WebView) while the cards are mostly scrolled out of view.
        if (!trailers || !trailerMode || !userTook || !visible) return@LaunchedEffect
        // Starts right after the cards stop sliding (or straight away when you hold a card).
        delay(if (holdTick > 0) 150 else 300)
        trailerId = items.getOrNull(pager.settledPage % n)?.let { vm.trailerId(it.meta) }
    }

    // Home source kept ready (Quick Start addons, e.g. MovieBox through PenguPlay): the card that stays in front for
    // 2 s has its links looked up quietly (remembered 20 minutes), so Play starts at once.
    LaunchedEffect(pager.settledPage, items.size) {
        delay(2_000)
        val m = items.getOrNull(pager.settledPage % n)?.meta ?: return@LaunchedEffect
        if (m.type == "movie" && vm.qsActive()) runCatching { vm.quickStreams("movie", m.id) }
    }
    // Holding a card starts looking up its streams too, so Play is ready sooner.
    LaunchedEffect(holdTick) {
        if (holdTick == 0) return@LaunchedEffect
        val m = items.getOrNull(pager.settledPage % n)?.meta ?: return@LaunchedEffect
        if (m.type == "movie") runCatching { if (vm.qsActive()) vm.quickStreams("movie", m.id) else vm.allStreams("movie", m.id) }
    }

    // Auto-slide every few seconds until you take over.
    LaunchedEffect(auto, secs, dragged, userTook, items.size, visible) {
        // Auto-slide pauses while the cards are scrolled away (no pager animation competing with list scrolling).
        if (!auto || dragged || userTook || !visible || items.size < 2) return@LaunchedEffect
        while (true) {
            delay(secs * 1000L)
            pager.animateScrollToPage(pager.currentPage + 1,
                animationSpec = tween(900, easing = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f)))
        }
    }

    LaunchedEffect(Unit) { YtDirect.prefLangs = vm.qsLangs().mapNotNull { YtDirect.langCode(it) } }
    val tid = trailerId
    val glowColor = Accent
    // Settings › Layout › Card border (off by default): white edge + soft glow on the front card.
    val texts by vm.texts.collectAsStateWithLifecycle()
    val border = texts["deckBorder"] == "on"
    Box(Modifier.fillMaxWidth()) {
        modeBanner?.let { (on, at) ->
            key(at) { ClassicTrailerBanner(on, Modifier.align(Alignment.TopCenter).padding(top = 12.dp).zIndex(10f)) { if (modeBanner?.second == at) modeBanner = null } }
        }
        // ── The stack (pictures only) ──
        // Drawn here in our own order (farthest first, front card last) so the cards behind never cover the front one;
        // each card's place, size and shade follow the pager's position every frame (draw phase only).
        val cur = pager.currentPage
        val order = remember(cur, n) { (cur - 2..cur + 2).sortedByDescending { abs(it - cur) } }
        Box(Modifier.fillMaxWidth().height(cardH + 16.dp), Alignment.Center) {
            for (page in order) key(page) {
                val d = at(page)
                val isFront = page == pager.settledPage && page == cur
                fun off() = page - pager.currentPage - pager.currentPageOffsetFraction
                ClassicDeckArt(
                    d.meta, trailerId = if (isFront) tid else null, muted = muted, videoOn = isFront && videoOn,
                    onPlaying = { videoFor = tid }, onEnded = { nextTrailer() },
                    modifier = Modifier.width(cardW).height(cardH)
                        .graphicsLayer {
                            val o = off(); val a = abs(o); val w = size.width
                            // Pulled in behind the front card: the first card behind peeks ~3.5 % of a card past
                            // its edge, the second ~6.5 %; both smaller.
                            // The two cards behind fill the space to the screen edge (no empty strip left or right):
                            // the second one runs just past the edge.
                            val target = when {
                                a <= 1f -> 0.11f * a
                                a <= 2f -> 0.11f + 0.09f * (a - 1f)
                                else -> 0.20f + 0.03f * (a - 2f)
                            } * w
                            translationX = sign(o) * target
                            val sc = when {
                                a <= 1f -> 1f - 0.08f * a
                                a <= 2f -> 0.92f - 0.08f * (a - 1f)
                                else -> 0.84f
                            }
                            scaleX = sc; scaleY = sc
                            alpha = if (a <= 2f) 1f else (1f - (a - 2f) * 2f).coerceIn(0f, 1f)
                        }
                        .drawBehind {
                            // Front card: a thin white edge and a soft accent glow, so it never blends into the cards
                            // behind it or the posters below.
                            val a = abs(off()).coerceAtMost(1f)
                            val cr = Palette.corner.dp.toPx()
                            val k = 1f - a
                            if (border && k > 0.01f) for (i in 1..3) {
                                val g = (i * 3).dp.toPx()
                                drawRoundRect(glowColor.copy(alpha = 0.10f * k / i), topLeft = Offset(-g, -g), size = Size(size.width + 2 * g, size.height + 2 * g),
                                    cornerRadius = CornerRadius(cr + g, cr + g))
                            }
                        }
                        .drawWithContent {
                            drawContent()
                            val a = abs(off()).coerceAtMost(2f)
                            val cr = Palette.corner.dp.toPx()
                            if (a > 0.01f) drawRoundRect(Color.Black, alpha = (0.4f * a).coerceAtMost(0.62f), cornerRadius = CornerRadius(cr, cr))
                            if (border) {
                                val edge = (0.85f - 0.62f * a.coerceAtMost(1f))
                                drawRoundRect(Color.White.copy(alpha = edge), cornerRadius = CornerRadius(cr, cr), style = Stroke(width = 1.5.dp.toPx()))
                            }
                        },
                )
            }
        }

        // ── The front card's words and buttons, on the pager (it also takes the swipes) ──
        HorizontalPager(
            state = pager,
            pageSize = PageSize.Fixed(cardW),
            contentPadding = PaddingValues(horizontal = ((cfg.screenWidthDp.dp - cardW) / 2).coerceAtLeast(8.dp)),
            pageSpacing = 8.dp,
            beyondViewportPageCount = 1,
            // Smooth: one card per swipe, a soft glide that settles without a bounce.
            flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
                state = pager,
                pagerSnapDistance = androidx.compose.foundation.pager.PagerSnapDistance.atMost(1),
                snapAnimationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 170f),
                snapPositionalThreshold = 0.25f,
            ),
            key = { "${at(it).meta.id}#${it / n}" },
            modifier = Modifier.fillMaxWidth().height(cardH + 16.dp),
        ) { page ->
            val d = at(page)
            val isFront = page == pager.settledPage
            ClassicDeckCard(
                d.meta, rank = page % n + 1, catalog = d.catalog, saved = myList.any { it.id == d.meta.id },
                trailerOn = isFront && tid != null, videoOn = isFront && videoOn, muted = muted,
                settled = isFront && !pager.isScrollInProgress,
                onMute = { muted = !muted; HeroSound.muted = muted },
                // A card behind the front one: a tap brings it forward.
                onOpen = { if (page == pager.currentPage) onOpen(d.meta) else scope.launch { pager.animateScrollToPage(page) } },
                onToggle = { onToggle(d.meta) },
                onHold = {
                    if (trailers) {
                        if (trailerMode && tid != null) {
                            // Trailer playing: hold again = trailers off.
                            trailerMode = false; HeroSound.trailers = false; trailerId = null; videoFor = null
                            modeBanner = false to System.currentTimeMillis()
                        } else {
                            trailerMode = true; HeroSound.trailers = true; userTook = true; holdTick++
                            modeBanner = true to System.currentTimeMillis()
                        }
                    }
                },
                modifier = Modifier.padding(vertical = 8.dp).graphicsLayer {
                    // The words fade while the cards move and come back on the card that settles in front.
                    val a = abs(page - pager.currentPage - pager.currentPageOffsetFraction)
                    alpha = (1f - 2.4f * a).coerceIn(0f, 1f)
                },
            )
        }
    }
}

/** A deck card's picture (poster, or its trailer once it plays inside the card). The stack draws these. */
@Composable
private fun ClassicDeckArt(
    m: Meta, trailerId: String?, muted: Boolean, videoOn: Boolean, onPlaying: () -> Unit, onEnded: () -> Unit, modifier: Modifier,
) {
    val shape = RoundedCornerShape(Palette.corner.dp)
    val posterAlpha by animateFloatAsState(if (videoOn) 0f else 1f, tween(420), label = "poster")
    Box(modifier.clip(shape).background(Color(0xFF05070C))) {
        // The trailer plays INSIDE the card (our own video surface, cropped to fill it edge to edge, rounded corners).
        if (trailerId != null) TrailerVideo(trailerId, muted, Modifier.fillMaxSize(), fitWhole = false, zoom = 1f,
            backdrop = backdropOf(m) ?: m.poster, onPlaying = onPlaying, onEnded = onEnded, onError = onEnded)
        var hdFailed by remember(m.id) { mutableStateOf(false) }
        val own = m.poster?.takeIf { !m.posterShape.equals("landscape", true) && !m.posterShape.equals("square", true) }
        val tall = (if (!hdFailed) hdPoster(m) else null) ?: own
        if (tall != null) {
            AsyncImage(hiResArt(tall), m.name, Modifier.fillMaxSize().graphicsLayer { alpha = posterAlpha }, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                onError = { if (tall != own) hdFailed = true })
        } else {
            val wide = hiResArt(backdropOf(m) ?: m.poster)
            AsyncImage(wide, null, Modifier.fillMaxSize().graphicsLayer { alpha = posterAlpha * 0.9f }.blur(28.dp), contentScale = ContentScale.Crop)
            AsyncImage(wide, m.name, Modifier.fillMaxWidth().aspectRatio(16f / 9f).graphicsLayer { alpha = posterAlpha }, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        }
        // While a trailer plays the shade is lighter (only enough for the text at the bottom): a brighter picture.
        Box(Modifier.fillMaxSize().background(if (videoOn) Brush.verticalGradient(0.62f to Color.Transparent, 0.86f to Color(0x99000000), 1f to Color(0xD9000000))
            else Brush.verticalGradient(0.42f to Color.Transparent, 0.82f to Color(0xEE000000), 1f to Color.Black)))
    }
}

/**
 * "Trailer mode on / off": a glass capsule that springs in at the top of the cards with a red glow breathing behind
 * it and a light sweep, then floats away after ~3 s.
 */
@Composable
private fun ClassicTrailerBanner(on: Boolean, modifier: Modifier = Modifier, onDone: () -> Unit) {
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    val inf = rememberInfiniteTransition(label = "tmb")
    val glow by inf.animateFloat(0.35f, 0.9f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow")
    val sweep by inf.animateFloat(-0.6f, 1.6f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sweep")
    LaunchedEffect(Unit) {
        appear.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = 380f))
        delay(2_800)
        appear.animateTo(0f, tween(320))
        onDone()
    }
    val shape = RoundedCornerShape(50)
    val red = Color(0xFFE50914)
    Row(
        modifier
            .graphicsLayer { val k = 0.75f + 0.25f * appear.value; scaleX = k; scaleY = k; alpha = appear.value.coerceIn(0f, 1f); translationY = (1f - appear.value) * -24f }
            .drawBehind {
                drawRoundRect(red.copy(alpha = 0.35f * glow), topLeft = Offset(-8f, -8f), size = Size(size.width + 16f, size.height + 16f),
                    cornerRadius = CornerRadius(size.height, size.height))
            }
            .clip(shape).background(Color(0xE614141A)).border(1.dp, red.copy(alpha = 0.7f), shape)
            .drawWithContent {
                drawContent()
                drawRect(Brush.linearGradient(listOf(Color.Transparent, Color(0x22FFFFFF), Color.Transparent),
                    start = Offset(size.width * sweep - 80f, 0f), end = Offset(size.width * sweep + 80f, size.height)))
            }
            .padding(start = 10.dp, end = 18.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(red.copy(alpha = if (on) 0.9f else 0.35f)), Alignment.Center) {
            Icon(if (on) Icons.Default.Movie else Icons.Default.PauseCircle, null, Modifier.size(18.dp), tint = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(if (on) "Trailer mode on" else "Trailer mode off", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(if (on) "Plays every card's trailer · hold a card to stop" else "Hold a card to turn it on", fontSize = 12.sp, color = Color(0xFFC9CEDB))
        }
    }
}

/** Layout coordinates of the deck box (not state: only read from layout callbacks). */

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ClassicDeckCard(
    m: Meta, rank: Int, catalog: String, saved: Boolean,
    /** a trailer is up for this card (drawn by Deck under it) / its video is really playing */
    trailerOn: Boolean, videoOn: Boolean, muted: Boolean, onMute: () -> Unit,
    onOpen: () -> Unit, onToggle: () -> Unit, modifier: Modifier, onHold: () -> Unit = {}, onTrailer: () -> Unit = {},
    /** the card has settled in front: its title and buttons rise in (hidden at once while the cards move) */
    settled: Boolean = true,
) {
    val shape = RoundedCornerShape(Palette.corner.dp)
    // Premium touch: the poster moves alone; ~180 ms after it settles, the title, Watch now and + rise in with a soft spring.
    var show by remember { mutableStateOf(settled) }
    LaunchedEffect(settled) { if (settled) { delay(180); show = true } else show = false }
    val rise by animateFloatAsState(if (show) 1f else 0f,
        if (show) androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 260f) else tween(90), label = "deckRise")
    val rating = m.imdbRating?.toDoubleOrNull()
    val rankText = when {
        catalog == "Picked for you" || catalog == AIDEN_PICK -> AIDEN_PICK
        listOf("new", "latest", "fresh", "release", "arriv").any { catalog.contains(it, true) } -> "New arrival · $catalog"
        else -> t("badge").replace("{rank}", rank.toString()).replace("{catalog}", catalog)
    }
    var logoFailed by remember(m.id) { mutableStateOf(false) }
    // Words and buttons only: the picture (poster or trailer) is drawn by the stack under the pager ([DeckArt]).
    Box(modifier.fillMaxWidth().aspectRatio(0.76f).clip(shape).combinedClickable(onClick = onOpen, onLongClick = onHold)) {
        // Badge: IMDb score for highly rated titles, otherwise the rank in its row.
        val badgeShape = RoundedCornerShape(14.dp)
        if (catalog == AIDEN_PICK) {
            Row(Modifier.padding(16.dp).clip(badgeShape).background(Color(0xB3140A0E)).border(1.dp, Color(0x99FF5A64), badgeShape)
                .padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                // The AI face (Integrations › AI face) beside "picked for you".
                AiFace(Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("${aiName().lowercase().replaceFirstChar { it.uppercase() }} picked for you", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        } else if (rating != null && rating >= 7.5) {
            Row(Modifier.padding(16.dp).clip(badgeShape).background(Color(0x99000000)).border(1.dp, Color(0x33FFFFFF), badgeShape)
                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFF5C518)).padding(horizontal = 5.dp, vertical = 2.dp)) {
                    Icon(Icons.Default.Star, null, Modifier.size(14.dp), tint = Color.Black)
                }
                Spacer(Modifier.width(8.dp))
                Text("IMDb ${m.imdbRating}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        } else if (rankText.isNotBlank()) {
            Row(Modifier.padding(16.dp).clip(badgeShape).background(Color(0x99000000)).border(1.dp, Color(0x33FFFFFF), badgeShape)
                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WorkspacePremium, null, Modifier.size(20.dp), tint = Color(0xFFFFB13B))
                Spacer(Modifier.width(8.dp))
                Text(rankText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (trailerOn && videoOn) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(14.dp).size(40.dp).clip(CircleShape).background(Color(0x88000000))
                    .border(1.dp, Color(0x33FFFFFF), CircleShape).clickable(onClick = onMute),
                Alignment.Center,
            ) { Icon(if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, if (muted) "Unmute" else "Mute", Modifier.size(20.dp)) }
        }
        // While the trailer plays the title shrinks to the bottom-left corner (animated), out of the picture's way.
        val shrink by animateFloatAsState(if (videoOn) 1f else 0f, tween(650), label = "titleShrink")
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp).graphicsLayer {
            alpha = rise.coerceIn(0f, 1f); translationY = (1f - rise) * 28.dp.toPx(); val sc = 0.96f + 0.04f * rise; scaleX = sc; scaleY = sc
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
        }) {
            val logo = logoOf(m)
            if (logo != null && !logoFailed) {
                AsyncImage(logo, m.name, Modifier.fillMaxWidth(0.85f - 0.35f * shrink).height((84f - 38f * shrink).dp), contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart, onError = { logoFailed = true })
            } else {
                Text(m.name, fontSize = (32f - 12f * shrink).sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height((10f - 4f * shrink).dp))
            Text(
                (listOfNotNull(m.releaseInfo?.take(4)) + m.genres.orEmpty().take(2)).joinToString("  •  "),
                Modifier.graphicsLayer { alpha = 1f - 0.4f * shrink },
                fontSize = (15f - 2f * shrink).sp, color = Color(0xFFC9CEDB), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ClassicGlassButton(Modifier.weight(1f).height(52.dp), onOpen) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(28.dp)); Spacer(Modifier.width(6.dp))
                    Text(t("watchNow"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                ClassicGlassButton(Modifier.size(52.dp), onToggle) {
                    Icon(if (saved) Icons.Default.Check else Icons.Default.Add, if (saved) "In Watchlist" else "Add to Watchlist")
                }
            }
        }
    }
}

@Composable
private fun ClassicGlassButton(modifier: Modifier, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(modifier.clip(shape).background(Color(0x3DFFFFFF)).border(1.dp, Color(0x26FFFFFF), shape).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, content = content)
}

