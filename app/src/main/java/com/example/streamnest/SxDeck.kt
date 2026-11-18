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

/** Home style "StreamX": StreamX's own top cards (copied from StreamX's Home), chosen in Settings › Layout › Home style. */

@Composable
internal fun SxDeck(
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
    val cardW = minOf(cfg.screenWidthDp * 0.83f, cfg.screenHeightDp * 0.68f * 0.76f).coerceAtLeast(240f).dp
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
    // Where the settled front card is, inside the deck box: the trailer WebView is laid over exactly that spot.
    var front by remember { mutableStateOf<Rect?>(null) }
    val deckBox = remember { SxDeckCoords() }
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

    val corner = Palette.corner
    val tint = Palette.tint
    val bg = Bg
    Box(Modifier.fillMaxWidth().onPlaced { deckBox.coords = it }) {
        modeBanner?.let { (on, at) ->
            key(at) { TrailerModeBanner(on, Modifier.align(Alignment.TopCenter).padding(top = 12.dp).zIndex(10f)) { if (modeBanner?.second == at) modeBanner = null } }
        }
        // ── Card trailer, UNDER the pager ──
        // The WebView must not sit inside a pager page (graphicsLayer scale, rounded clip, alpha): that draws the video
        // black while the sound plays. So it lives here, in the plain deck box, exactly where the front card is, and the
        // front card turns see-through (poster fades out) on top of it. Corners: masks in the page background colour.
        val tid = trailerId
        // 0.9.63: the trailer plays INSIDE the front card (exactly like Xstream), so this separate layer is off.
        val r: Rect? = null
        if (tid != null && r != null) {
            val rootY = remember { mutableFloatStateOf(0f) }
            Box(
                Modifier
                    .layout { measurable, _ ->
                        val w = r.width.roundToInt().coerceAtLeast(1)
                        val h = r.height.roundToInt().coerceAtLeast(1)
                        val pl = measurable.measure(Constraints.fixed(w, h))
                        layout(w, h) { pl.place(r.left.roundToInt(), r.top.roundToInt()) }
                    }
                    .onGloballyPositioned { rootY.floatValue = it.positionInRoot().y },
            ) {
                val frontMeta = items.getOrNull(pager.settledPage % n)?.meta
                // Fills the card edge to edge (zoomed in like Xstream), instead of a small 16:9 picture inside the tall card.
                TrailerVideo(tid, muted, Modifier.fillMaxSize(), fitWhole = false, zoom = 1f,
                    backdrop = frontMeta?.let { backdropOf(it) ?: it.poster }, onPlaying = { videoFor = tid },
                    onEnded = { nextTrailer() }, onError = { nextTrailer() })
                // Keeps touches away from the video (the pager above gets them anyway).
                Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
                // Rounded corners without clipping the WebView: paint the corners in the page's background gradient
                // (HomeScreen's 600dp tint → bg gradient, which is fixed to the top of the screen).
                Box(Modifier.fillMaxSize().drawBehind {
                    val cr = corner.dp.toPx()
                    val y0 = -rootY.floatValue
                    val brush = Brush.verticalGradient(listOf(tint, bg), startY = y0, endY = y0 + 600.dp.toPx())
                    val mask = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(Offset.Zero, size))
                        addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(cr, cr)))
                    }
                    drawPath(mask, brush)
                })
            }
        }

        HorizontalPager(
            state = pager,
            pageSize = PageSize.Fixed(cardW),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp),
            // 2 is enough for the stacked look (cards 3+ behind are fully transparent) and composes fewer heavy cards.
            beyondViewportPageCount = 2,
            // Smooth like Hotstar: one card per swipe, a soft glide that settles without a bounce.
            flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
                state = pager,
                pagerSnapDistance = androidx.compose.foundation.pager.PagerSnapDistance.atMost(1),
                snapAnimationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.92f, stiffness = 170f),
                snapPositionalThreshold = 0.3f,
            ),
            key = { "${at(it).meta.id}#${it / n}" },
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val d = at(page)
            val isFront = page == pager.settledPage
            SxDeckCard(
                d.meta, rank = page % n + 1, catalog = d.catalog, saved = myList.any { it.id == d.meta.id },
                trailerOn = isFront && tid != null, videoOn = isFront && videoOn, muted = muted,
                onMute = { muted = !muted; HeroSound.muted = muted },
                trailerId = if (isFront) tid else null, onTrailerPlaying = { videoFor = tid }, onTrailerEnded = { nextTrailer() },
                onOpen = { onOpen(d.meta) }, onToggle = { onToggle(d.meta) },
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
                modifier = Modifier
                    .zIndex(-abs(page - pager.currentPage).toFloat())
                    .graphicsLayer {
                        val r = page - pager.currentPage - pager.currentPageOffsetFraction
                        val s = 1f - 0.06f * minOf(abs(r), 2.5f)
                        scaleX = s; scaleY = s
                        if (r > 0f) translationX = -minOf(r, 3f) * size.width * 0.88f
                        // Only 0 or 1: a partial alpha on these big, busy cards made the GPU render each stacked card
                        // into an offscreen buffer on every frame (also while the page itself scrolls).
                        alpha = if (r > 2.6f) 0f else 1f
                    }
                    // The cards behind are dimmed with a cheap translucent overlay instead (same look on the dark page).
                    .drawWithContent {
                        val r = page - pager.currentPage - pager.currentPageOffsetFraction
                        val dim = 0.18f * minOf(abs(r), 2f)
                        val dimCr = Palette.corner.dp.toPx()
                        if (videoOn && abs(r) > 0.01f) {
                            // A trailer plays under the (see-through) front card: the cards stacked behind it must not
                            // cover it. Cut the front card's rounded rectangle out of this card, in this card's own
                            // (scaled / shifted) coordinates.
                            val w = size.width; val h = size.height
                            val s = 1f - 0.06f * minOf(abs(r), 2.5f)
                            val tx = if (r > 0f) -minOf(r, 3f) * w * 0.88f else 0f
                            val cx = w / 2f; val cy = h / 2f
                            val x0 = cx + (-r * w - cx - tx) / s
                            val x1 = cx + (w - r * w - cx - tx) / s
                            val y0 = cy - cy / s
                            val y1 = cy + (h - cy) / s
                            val cr = Palette.corner.dp.toPx() / s
                            val hole = Path().apply { addRoundRect(RoundRect(x0, y0, x1, y1, CornerRadius(cr, cr))) }
                            clipPath(hole, ClipOp.Difference) {
                                this@drawWithContent.drawContent()
                                if (dim > 0.005f) drawRoundRect(Color.Black, alpha = dim, cornerRadius = CornerRadius(dimCr, dimCr))
                            }
                        } else {
                            drawContent()
                            if (dim > 0.005f) drawRoundRect(Color.Black, alpha = dim, cornerRadius = CornerRadius(dimCr, dimCr))
                        }
                    }
                    ,
                // Where the front CARD is (relative to the deck box) while a trailer is up. Measured on the card itself:
                // the pager page around it is taller, and the trailer used to fill that taller box (over-zoomed, too high,
                // frames showing below and beside the card).
                onPlaced = if (isFront && tid != null) { c ->
                    val box = deckBox.coords
                    if (box != null && box.isAttached && c.isAttached) front = box.localBoundingBoxOf(c, false)
                } else null,
            )
        }
    }
}

/**
 * "Trailer mode on / off": a glass capsule that springs in at the top of the cards with a red glow breathing behind
 * it and a light sweep, then floats away after ~3 s.
 */

private class SxDeckCoords { var coords: LayoutCoordinates? = null }

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun SxDeckCard(
    m: Meta, rank: Int, catalog: String, saved: Boolean,
    /** a trailer is up for this card (drawn by Deck under it) / its video is really playing */
    trailerOn: Boolean, videoOn: Boolean, muted: Boolean, onMute: () -> Unit,
    onOpen: () -> Unit, onToggle: () -> Unit, modifier: Modifier, onHold: () -> Unit = {}, onTrailer: () -> Unit = {},
    onPlaced: ((androidx.compose.ui.layout.LayoutCoordinates) -> Unit)? = null,
    /** The trailer to play inside this card (front card only), like Xstream: cropped to fill, centred, clipped to the card. */
    trailerId: String? = null, onTrailerPlaying: () -> Unit = {}, onTrailerEnded: () -> Unit = {},
) {
    val shape = RoundedCornerShape(Palette.corner.dp)
    val rating = m.imdbRating?.toDoubleOrNull()
    val rankText = when {
        catalog == "Picked for you" || catalog == AIDEN_PICK -> AIDEN_PICK
        listOf("new", "latest", "fresh", "release", "arriv").any { catalog.contains(it, true) } -> "New arrival · $catalog"
        else -> t("badge").replace("{rank}", rank.toString()).replace("{catalog}", catalog)
    }
    var logoFailed by remember(m.id) { mutableStateOf(false) }
    val posterAlpha by animateFloatAsState(if (videoOn) 0f else 1f, tween(600), label = "poster")
    // While the trailer plays (under this card) the card is see-through: no dark fill, the poster fades away.
    Box(modifier.fillMaxWidth().aspectRatio(0.76f).then(if (onPlaced != null) Modifier.onGloballyPositioned(onPlaced) else Modifier)
        .clip(shape).drawBehind { if (!videoOn) drawRect(Color(0xFF05070C)) }
        .combinedClickable(onClick = onOpen, onLongClick = onHold)) {
        // The trailer, inside the card and under the poster (the poster fades away once the video really plays).
        if (trailerId != null) TrailerVideo(trailerId, muted, Modifier.fillMaxSize(), fitWhole = false, zoom = 1f,
            backdrop = backdropOf(m) ?: m.poster, onPlaying = onTrailerPlaying, onEnded = onTrailerEnded, onError = onTrailerEnded)
        // Always a sharp, tall poster (HD copy by IMDb id when there is one), anchored at the top so faces and titles
        // stay in view. A wide picture is shown whole at the top over a blurred copy of itself, never cut in the middle.
        var hdFailed by remember(m.id) { mutableStateOf(false) }
        val own = m.poster?.takeIf { !m.posterShape.equals("landscape", true) && !m.posterShape.equals("square", true) }
        val tall = (if (!hdFailed) hdPoster(m) else null) ?: own
        if (tall != null) {
            AsyncImage(hiResArt(tall), m.name, Modifier.fillMaxSize().graphicsLayer { alpha = posterAlpha }, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                onError = { if (tall != own) hdFailed = true })
        } else {
            val wide = hiResArt(backdropOf(m) ?: m.poster)
            AsyncImage(wide, null, Modifier.fillMaxSize().graphicsLayer { alpha = posterAlpha * 0.9f }.blur(28.dp), contentScale = ContentScale.Crop)
            AsyncImage(wide, m.name, Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(top = 0.dp).graphicsLayer { alpha = posterAlpha }, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        }
        // While a trailer plays the shade is lighter (only enough for the text at the bottom): a brighter picture.
        Box(Modifier.fillMaxSize().background(if (videoOn) Brush.verticalGradient(0.62f to Color.Transparent, 0.86f to Color(0x99000000), 1f to Color(0xD9000000))
            else Brush.verticalGradient(0.42f to Color.Transparent, 0.82f to Color(0xEE000000), 1f to Color.Black)))

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
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
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
                SxGlassButton(Modifier.weight(1f).height(52.dp), onOpen) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(28.dp)); Spacer(Modifier.width(6.dp))
                    Text(t("watchNow"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                SxGlassButton(Modifier.size(52.dp), onToggle) {
                    Icon(if (saved) Icons.Default.Check else Icons.Default.Add, if (saved) "In Watchlist" else "Add to Watchlist")
                }
            }
        }
    }
}


@Composable
private fun SxGlassButton(modifier: Modifier, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(modifier.clip(shape).background(Color(0x3DFFFFFF)).border(1.dp, Color(0x26FFFFFF), shape).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, content = content)
}

/** Continue Watching card. style: "card" (TV-style), "wide" (info on the artwork) or "poster" (tall). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
