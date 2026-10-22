package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Everything TV Home must remember while a title page / player is open on top of it (Home itself is not composed
 * then): scroll positions, the hero card and the item that had focus. Plain fields: writing them recomposes nothing.
 */
class TvHomeState {
    val list = LazyListState()
    private val rows = HashMap<String, LazyListState>()
    fun row(key: String): LazyListState = rows.getOrPut(key) { LazyListState() }
    val deckPage: MutableIntState = mutableIntStateOf(0)
    /** Key of the row that had focus ("deck" = the hero card), and the item index in it. */
    var focusRow: String? = null
    var focusIndex: Int = 0
    /** Bumped by TvApp when the sidebar closes without a known item to return to: Home puts focus back itself. */
    val refocus: MutableIntState = mutableIntStateOf(0)
}

private data class TvDeckEntry(val meta: Meta, val catalog: String)

private sealed class TvHomeSec(val key: String) {
    class Cw(val items: List<Progress>) : TvHomeSec("cw")
    class Col(val c: NCollection) : TvHomeSec("col:" + c.id) { val folders = c.folders.distinctBy { it.id } }
    class Metas(key: String, val title: String, val items: List<Meta>) : TvHomeSec(key)
}

private const val DeckRow = "deck"

@Composable
fun TvHomeScreen(
    vm: AppViewModel,
    onOpen: (Meta) -> Unit,
    onPlay: (Screen.Player) -> Unit,
    onPlayMeta: (Meta) -> Unit,
    onOpenCollection: (NCollection, NFolder) -> Unit,
    onLeftEdge: (FocusRequester) -> Unit,
    state: TvHomeState = remember { TvHomeState() },
) {
    val allShelves by vm.shelves.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val profile by vm.current.collectAsStateWithLifecycle()
    val recs by vm.recommended.collectAsStateWithLifecycle()
    val colFlow = remember(vm) { vm.collections() }
    val collections by colFlow.collectAsStateWithLifecycle()

    val rowsOrder = t("rowsOrder")
    val rowsHidden = t("rowsHidden")
    val shelves = remember(allShelves, rowsOrder, rowsHidden) {
        val hidden = rowsHidden.split("|").toSet()
        arrangeShelves(allShelves, rowsOrder).filter { it.title !in hidden && it.items.isNotEmpty() }
    }
    val showHero = isOn(t("showHero"))
    // Hero cards: the first two rows mixed (e.g. popular movies + popular series), like the phone app.
    val deck = remember(shelves, showHero) {
        if (!showHero) emptyList() else {
            val a = shelves.getOrNull(0)?.let { s -> s.items.map { TvDeckEntry(it, s.title) } }.orEmpty()
            val b = shelves.getOrNull(1)?.let { s -> s.items.map { TvDeckEntry(it, s.title) } }.orEmpty()
            (0 until maxOf(a.size, b.size)).flatMap { listOfNotNull(a.getOrNull(it), b.getOrNull(it)) }
                .filter { it.meta.poster != null || it.meta.background != null }.distinctBy { it.meta.id }.take(10)
        }
    }
    val cwShow = isOn(t("cwShow"))
    val showRecs = isOn(t("showRecs"))
    val recsFirst = isOn(t("recsAfterCw"))
    val continueTitle = t("continueWatching").replace("{name}", profile?.name ?: "").trim()
    val recTitle = t("recommendedFor").replace("{name}", profile?.name ?: "you")
    val newOn = t("newOn").replace("{app}", t("appName"))
    val sections = remember(progress, collections, shelves, recs, cwShow, showRecs, recsFirst, recTitle, newOn) {
        buildList<TvHomeSec> {
            if (cwShow && progress.isNotEmpty()) add(TvHomeSec.Cw(progress))
            // "Recommended for you" sits right after Continue Watching (Settings › Layout › Home can move it to the end).
            if (showRecs && recsFirst && recs.isNotEmpty()) add(TvHomeSec.Metas("recs", recTitle, recs))
            collections.filter { it.folders.isNotEmpty() }.distinctBy { it.id }.forEach { add(TvHomeSec.Col(it)) }
            shelves.forEachIndexed { i, s ->
                val title = rowName(s.title) ?: if (s.title.startsWith("New")) "$newOn · ${s.title.removePrefix("New ").trim()}" else s.title
                add(TvHomeSec.Metas("shelf:$i:${s.title}", title, s.items))
            }
            if (showRecs && !recsFirst && recs.isNotEmpty()) add(TvHomeSec.Metas("recs", recTitle, recs))
        }
    }

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val scrollJob = remember { arrayOfNulls<Job>(1) }
    val bgImage = remember { mutableStateOf<String?>(null) }
    val deckFr = remember { FocusRequester() }
    val restoreFr = remember { FocusRequester() }
    val homeFocused = remember { booleanArrayOf(false) }
    val ls = state.list
    // Trailers that play by themselves (hero card, backdrop) are OFF on TV: they made Fire TV sticks slow and crash.
    // (The Trailer button on title pages still plays them full screen.)
    val elite = false
    val trailerFor = remember { mutableStateOf<Meta?>(null) }
    var trailerId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(trailerFor.value, elite) {
        trailerId = null
        val m = trailerFor.value ?: return@LaunchedEffect
        if (!elite) return@LaunchedEffect
        delay(5000)
        trailerId = runCatching { vm.trailerId(m) }.getOrNull()
    }

    // Row [ord] got focus. The first row (Continue Watching) keeps the lower half of the hero card in view above
    // it; later rows go to the top. ord = -1: the hero card itself (list back at the very top).
    fun scrollToSection(ord: Int) {
        scrollJob[0]?.cancel()
        scrollJob[0] = scope.launch {
            if (ord < 0) {
                if (ls.firstVisibleItemIndex != 0 || ls.firstVisibleItemScrollOffset != 0) ls.animateScrollToItem(0)
            } else if (ord == 0 && deck.isNotEmpty()) {
                ls.animateScrollToItem(0, with(density) { 250.dp.roundToPx() })
            } else {
                val target = ord + 1
                if (ls.firstVisibleItemIndex != target || ls.firstVisibleItemScrollOffset != 0) ls.animateScrollToItem(target)
            }
        }
    }

    // Focus on start: the hero card (waits for it while the rows load), else back where you were (after a title
    // page), else the first row.
    val restoreRow = state.focusRow
    val restoreIdx = state.focusIndex
    val hasContent = deck.isNotEmpty() || sections.isNotEmpty()
    LaunchedEffect(deck.isNotEmpty(), sections.isNotEmpty(), loading) {
        if (!hasContent) return@LaunchedEffect
        withFrameNanos { }
        delay(30)
        if (homeFocused[0]) return@LaunchedEffect
        when {
            restoreRow != null && restoreRow != DeckRow && restoreFr.tryFocus() -> Unit
            deck.isNotEmpty() && deckFr.tryFocus() -> Unit
            deck.isEmpty() && (loading && showHero) -> Unit // the cards are on their way: don't jump to a row first
            else -> restoreFr.tryFocus()
        }
    }
    val refocusTick = state.refocus.intValue
    LaunchedEffect(refocusTick) {
        if (refocusTick == 0) return@LaunchedEffect
        withFrameNanos { }
        val ok = if (restoreRow != null && restoreRow != DeckRow) restoreFr.tryFocus() else false
        if (!ok && !deckFr.tryFocus()) restoreFr.tryFocus()
    }

    Box(Modifier.fillMaxSize().onFocusChanged { homeFocused[0] = it.hasFocus; if (!it.hasFocus) trailerFor.value = null }.focusGroup()) {
        val tid = trailerId
        if (tid != null) TvBackdropTrailer(tid) { trailerId = null }
        else TvHomeBackdrop(bgImage)
        if (!hasContent) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                if (loading) CircularProgressIndicator(color = Accent)
                else Text(t("emptyMsg"), Modifier.padding(32.dp), fontSize = 13.sp, color = TvSubText)
            }
        } else TvManualScroll {
            LazyColumn(Modifier.fillMaxSize(), state = ls, contentPadding = PaddingValues(bottom = 90.dp)) {
                item(key = DeckRow, contentType = DeckRow) {
                    if (deck.isNotEmpty()) TvHeroDeck(
                        vm, deck, state.deckPage, deckFr, ls, elite,
                        onLeftEdge = onLeftEdge,
                        onFocused = { state.focusRow = DeckRow; state.focusIndex = 0; bgImage.value = null; trailerFor.value = null; scrollToSection(-1) },
                        onPlay = onPlayMeta,
                        onOpen = onOpen,
                    ) else if (loading && showHero) TvDeckPlaceholder() else Spacer(Modifier.height(54.dp))
                }
                itemsIndexed(sections, key = { _, s -> s.key }, contentType = { _, s ->
                    when (s) { is TvHomeSec.Cw -> "cw"; is TvHomeSec.Col -> "col"; is TvHomeSec.Metas -> "row" }
                }) { ord, s ->
                    val rIdx = if (s.key == restoreRow) restoreIdx else if (restoreRow == null && ord == 0 && deck.isEmpty()) 0 else -1
                    val itemFocus: (Int) -> Unit = { i -> state.focusRow = s.key; state.focusIndex = i }
                    when (s) {
                        is TvHomeSec.Cw -> TvRow(
                            s.items, key = { it.key }, title = continueTitle, state = state.row(s.key), spacing = 16.dp,
                            restoreIndex = rIdx, restoreRequester = restoreFr, onLeftEdge = onLeftEdge,
                            onRowFocus = { bgImage.value = null; trailerFor.value = null; scrollToSection(ord) }, onItemFocus = itemFocus,
                        ) { _, p, mod ->
                            TvContinueCard(vm, p, mod) {
                                if (p.finished()) onOpen(p.asMeta())
                                else onPlay(Screen.Player(p.title, p.url, p.subs, p.key, p.image, p.positionMs))
                            }
                        }
                        is TvHomeSec.Col -> TvRow(
                            s.folders, key = { it.id }, title = s.c.title, state = state.row(s.key),
                            restoreIndex = rIdx, restoreRequester = restoreFr, onLeftEdge = onLeftEdge,
                            onRowFocus = { trailerFor.value = null; scrollToSection(ord) }, onItemFocus = itemFocus,
                        ) { _, f, mod ->
                            TvFolderTile(f, mod, onFocus = { if (it) bgImage.value = f.hero ?: f.cover ?: s.c.backdrop }) { onOpenCollection(s.c, f) }
                        }
                        is TvHomeSec.Metas -> {
                            val wide = tvLandscapeRow(s.items)
                            TvRow(
                                s.items, key = { it.type + it.id }, title = s.title, state = state.row(s.key),
                                restoreIndex = rIdx, restoreRequester = restoreFr, onLeftEdge = onLeftEdge,
                                onRowFocus = { bgImage.value = null; scrollToSection(ord) }, onItemFocus = itemFocus,
                            ) { _, m, mod ->
                                if (wide) TvPosterCard(m, mod, 200.dp, 112.dp) { onOpen(m) }
                                else TvExpandCard(m, mod, onFocus = { f -> if (f) { trailerFor.value = m; bgImage.value = null } }) { onOpen(m) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Grey card where the hero deck will be while the first rows load (so Home never starts on Continue Watching). */
@Composable
private fun TvDeckPlaceholder() {
    Box(Modifier.padding(start = TvEdge, top = 54.dp, end = 168.dp).fillMaxWidth().height(DeckH).clip(DeckShape).background(Color(0xFF14151B)))
}

/** Full-screen artwork behind the rows while a collection folder has focus (dimmed, faded into the background). */
@Composable
private fun TvHomeBackdrop(image: State<String?>) {
    val url = image.value ?: return
    val req = tvImage(url, 480.dp, 270.dp)
    val bg = Bg
    val side = remember(bg) { Brush.horizontalGradient(0.25f to bg, 0.6f to bg.copy(alpha = 0.6f), 1f to bg.copy(alpha = 0.1f)) }
    val bottom = remember(bg) { Brush.verticalGradient(0.35f to Color.Transparent, 0.85f to bg) }
    Box(Modifier.fillMaxSize()) {
        if (req != null) AsyncImage(req, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.55f)
        Box(Modifier.fillMaxSize().background(side))
        Box(Modifier.fillMaxSize().background(bottom))
    }
}

/** Elite: the focused title's trailer, full screen behind the rows, faded into the page on the left and bottom. */
@Composable
private fun TvBackdropTrailer(id: String, onGone: () -> Unit) {
    val bg = Bg
    val side = remember(bg) { Brush.horizontalGradient(0f to bg.copy(alpha = 0.9f), 0.35f to bg.copy(alpha = 0.45f), 0.7f to Color.Transparent) }
    val bottom = remember(bg) { Brush.verticalGradient(0.45f to Color.Transparent, 0.8f to bg.copy(alpha = 0.85f), 1f to bg) }
    Box(Modifier.fillMaxSize()) {
        TrailerVideo(id, muted = false, modifier = Modifier.fillMaxSize(), onPlaying = {}, onEnded = onGone, onError = onGone)
        Box(Modifier.fillMaxSize().background(side))
        Box(Modifier.fillMaxSize().background(bottom))
    }
}

// ───────────── Hero cards (Hotstar style) ─────────────

private val DeckShape = RoundedCornerShape(18.dp)
private val DeckShade = Brush.horizontalGradient(0f to Color(0xE6000000), 0.34f to Color(0x99000000), 0.62f to Color.Transparent)
private val DeckShadeBottom = Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xB3000000))
private val DeckRim = Color(0x40FFFFFF)

/** Hero card size; the next two cards are stacked under it (a card deck), like Hotstar TV. */
private val DeckH = 392.dp
private val DeckPeek = 56.dp

/**
 * The big hero card. Focus rests on the CARD: ◀ ▶ = previous / next card (◀ on the first one opens the menu),
 * OK = title page, ▼ = Play. On Play: ▶ = My List, ▲ = card. On My List: ▶ = next card (focus back on the card).
 * Elite: rest on a card for 5 s and its trailer plays inside it. Auto-advances every 8 s while untouched.
 */
@Composable
private fun TvHeroDeck(
    vm: AppViewModel,
    items: List<TvDeckEntry>,
    pageState: MutableIntState,
    requester: FocusRequester,
    listState: LazyListState,
    elite: Boolean,
    onLeftEdge: (FocusRequester) -> Unit,
    onFocused: () -> Unit,
    onPlay: (Meta) -> Unit,
    onOpen: (Meta) -> Unit,
) {
    var page by pageState
    val p = page.coerceIn(0, items.lastIndex)
    var cardFocused by remember { mutableStateOf(false) }
    var btnFocused by remember { mutableStateOf(false) }
    val lastKey = remember { longArrayOf(0L) }
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val myList by vm.myList.collectAsStateWithLifecycle()
    val playFr = remember { FocusRequester() }
    val addFr = remember { FocusRequester() }
    val bg = Bg

    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = TvEdge, top = 54.dp, end = 18.dp)) {
        val deckW = maxWidth - DeckPeek
        val wPx = with(density) { deckW.roundToPx() }
        val hPx = with(density) { DeckH.roundToPx() }
        val anyFocus = cardFocused || btnFocused

        // Elite trailer inside the card after 5 s on it (stops when the card changes or focus leaves the deck).
        var trailerId by remember { mutableStateOf<String?>(null) }
        var videoOn by remember { mutableStateOf(false) }
        LaunchedEffect(p, anyFocus, elite) {
            trailerId = null; videoOn = false
            if (!elite || !anyFocus) return@LaunchedEffect
            delay(5000)
            trailerId = runCatching { vm.trailerId(items[p].meta) }.getOrNull()
        }
        // Auto-advance while the cards are on screen, nobody pressed a key for 8 s and no trailer is playing.
        LaunchedEffect(items.size) {
            if (items.size < 2) return@LaunchedEffect
            while (true) {
                delay(8000)
                val idle = System.currentTimeMillis() - lastKey[0] > 7500
                if (idle && trailerId == null && listState.firstVisibleItemIndex == 0) page = (page.coerceIn(0, items.lastIndex) + 1) % items.size
            }
        }
        // Pre-load the neighbours at exactly the card's size, so changing cards is instant (memory-cache hit).
        LaunchedEffect(p, items, wPx) {
            listOf(items.getOrNull((p + 1) % items.size), items.getOrNull((p - 1 + items.size) % items.size)).forEach { e ->
                val m = e?.meta ?: return@forEach
                val url = backdropOf(m) ?: m.poster ?: return@forEach
                ctx.imageLoader.enqueue(tvRequest(ctx, url, wPx, hPx))
            }
        }

        val cur = items[p]
        val m = cur.meta
        fun next() { if (items.size > 1) page = (p + 1) % items.size }
        Box(Modifier.fillMaxWidth().height(DeckH)) {
            // The deck: the next two cards sit behind the front one and peek out on its RIGHT, a little smaller.
            if (items.size > 2) TvDeckPeek(items[(p + 2) % items.size].meta, deckW + DeckPeek - deckW * 0.82f, deckW * 0.82f, DeckH * 0.82f, 0.7f, DeckH * 0.09f)
            if (items.size > 1) TvDeckPeek(items[(p + 1) % items.size].meta, deckW + DeckPeek / 2 - deckW * 0.91f, deckW * 0.91f, DeckH * 0.91f, 0.5f, DeckH * 0.045f)

            // The trailer lives UNDER the card (a WebView inside a clipped / layered card would draw black).
            val tid = trailerId
            if (tid != null) Box(Modifier.size(deckW, DeckH)) {
                TrailerVideo(tid, muted = false, modifier = Modifier.fillMaxSize(), onPlaying = { videoOn = true },
                    onEnded = { trailerId = null; videoOn = false }, onError = { trailerId = null; videoOn = false })
                // Rounded corners: paint the corners in the page colour.
                Box(Modifier.fillMaxSize().drawWithContent {
                    val cr = 18.dp.toPx()
                    val path = androidx.compose.ui.graphics.Path().apply {
                        fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                        addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                        addRoundRect(androidx.compose.ui.geometry.RoundRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size), CornerRadius(cr, cr)))
                    }
                    drawPath(path, bg)
                })
            }

            val artAlpha by animateFloatAsState(if (videoOn) 0f else 1f, tween(600), label = "deckArt")
            Box(
                Modifier.size(deckW, DeckH)
                    .focusRequester(requester)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown || !cardFocused) return@onPreviewKeyEvent false
                        lastKey[0] = System.currentTimeMillis()
                        when (e.key) {
                            Key.DirectionLeft -> { if (p > 0) page = p - 1 else onLeftEdge(requester); true }
                            Key.DirectionRight -> { next(); true }
                            Key.DirectionDown -> { playFr.tryFocus(); true }
                            else -> false
                        }
                    }
                    .onFocusChanged { if (it.isFocused != cardFocused) { cardFocused = it.isFocused; if (it.isFocused) onFocused() } }
                    .onKeyEvent { e ->
                        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                        if (ok && e.type == KeyEventType.KeyUp) onOpen(m)
                        ok
                    }
                    .focusable()
                    .clip(DeckShape)
                    .background(if (videoOn) Color.Transparent else Color(0xFF05070C)),
            ) {
                // Changing cards: the front card slides away to the left and the next rises from the deck
                // (only position / scale / alpha animate, on the graphics chip; the buttons below never move).
                androidx.compose.animation.AnimatedContent(
                    targetState = p,
                    transitionSpec = {
                        val fwd = targetState == (initialState + 1) % items.size.coerceAtLeast(1)
                        ((androidx.compose.animation.slideInHorizontally(tween(420, easing = FastOutSlowInEasing)) { if (fwd) it / 12 else -it / 12 } +
                            androidx.compose.animation.scaleIn(tween(420, easing = FastOutSlowInEasing), initialScale = 0.95f) + androidx.compose.animation.fadeIn(tween(260)))
                            togetherWith (androidx.compose.animation.slideOutHorizontally(tween(380, easing = FastOutSlowInEasing)) { if (fwd) -it else it } +
                            androidx.compose.animation.fadeOut(tween(300))))
                    },
                    label = "deck",
                ) { pp ->
                    val mm = items[pp.coerceIn(0, items.lastIndex)].meta
                    Box(Modifier.fillMaxSize().clip(DeckShape)) {
                        val url = backdropOf(mm) ?: mm.poster
                        val art = remember(url, wPx) { url?.let { tvRequest(ctx, it, wPx, hPx) } }
                        if (art != null) AsyncImage(art, mm.name, Modifier.fillMaxSize().graphicsLayer { alpha = artAlpha }, contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(DeckShade))
                        Box(Modifier.fillMaxSize().background(DeckShadeBottom))
                    }
                }

                val rating = m.imdbRating?.toDoubleOrNull()
                val badge = if (rating != null && rating >= 7.5) "IMDb ${m.imdbRating}" else "#${p + 1} in ${cur.catalog}"
                Row(
                    Modifier.padding(start = 20.dp, top = 18.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x8C000000))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (rating != null && rating >= 7.5) Icons.Default.Star else Icons.Default.Whatshot, null,
                        Modifier.size(12.dp), tint = if (rating != null && rating >= 7.5) Color(0xFFF5C518) else Color(0xFFFF4D3D))
                    Spacer(Modifier.width(5.dp))
                    Text(badge, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Column(Modifier.align(Alignment.BottomStart).padding(start = 30.dp, bottom = 26.dp).width(440.dp)) {
                    var logoFailed by remember(m.id) { mutableStateOf(false) }
                    val logoReq = tvImage(logoOf(m), 320.dp, 96.dp)
                    if (logoReq != null && !logoFailed) {
                        AsyncImage(
                            logoReq, m.name, Modifier.widthIn(max = 320.dp).height(88.dp).fillMaxWidth(),
                            contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, onError = { logoFailed = true },
                        )
                    } else {
                        Text(m.name, fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    val line = (listOfNotNull(
                        m.releaseInfo?.take(4)?.takeIf { it.isNotBlank() },
                        m.runtime?.takeIf { it.isNotBlank() },
                        if (m.type == "series") "Series" else null,
                    ) + m.genres.orEmpty().take(2)).joinToString("  •  ")
                    if (line.isNotBlank()) Text(line, Modifier.padding(top = 10.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE6E8EE), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!videoOn) m.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, Modifier.padding(top = 6.dp), fontSize = 11.5.sp, lineHeight = 16.sp, color = Color(0xFFD0D3DB),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        TvPillFocus(
                            Modifier.focusRequester(playFr).onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                lastKey[0] = System.currentTimeMillis()
                                when (e.key) {
                                    Key.DirectionLeft, Key.DirectionUp -> { requester.tryFocus(); true }
                                    Key.DirectionRight -> { addFr.tryFocus(); true }
                                    else -> false
                                }
                            },
                            onFocus = { f -> btnFocused = f; if (f) onFocused() },
                            onClick = { onPlay(m) },
                            ring = false,
                        ) {
                            RedPill("Play", Modifier.width(184.dp), height = 52, fontSize = 21, onClick = { onPlay(m) })
                        }
                        Spacer(Modifier.width(14.dp))
                        val saved = myList.any { it.id == m.id }
                        TvPillFocus(
                            Modifier.focusRequester(addFr).onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                lastKey[0] = System.currentTimeMillis()
                                when (e.key) {
                                    Key.DirectionRight -> { next(); requester.tryFocus(); true }
                                    Key.DirectionLeft -> { playFr.tryFocus(); true }
                                    Key.DirectionUp -> { requester.tryFocus(); true }
                                    else -> false
                                }
                            },
                            onFocus = { f -> btnFocused = f; if (f) onFocused() },
                            onClick = { vm.toggleList(m) },
                        ) {
                            Box(
                                Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x33FFFFFF))
                                    .border(1.dp, Color(0x4DFFFFFF), RoundedCornerShape(16.dp)),
                                Alignment.Center,
                            ) {
                                Icon(if (saved) Icons.Default.Check else Icons.Default.Add, if (saved) "In My List" else "Add to My List",
                                    Modifier.size(24.dp), tint = Color.White)
                            }
                        }
                    }
                }
            }
            // Rim: white when the card itself has focus, softer while Play / My List have it.
            Box(Modifier.size(deckW, DeckH).border(
                if (cardFocused) 3.dp else if (btnFocused) 1.5.dp else 1.dp,
                if (cardFocused) TvFocusEdge else if (btnFocused) Color(0x99FFFFFF) else DeckRim, DeckShape,
            ))
        }
        // Page dots, centred under the card.
        Row(
            Modifier.padding(top = DeckH + 12.dp).width(deckW),
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        ) {
            items.indices.forEach { i ->
                Box(Modifier.size(width = if (i == p) 16.dp else 6.dp, height = 6.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (i == p) Color.White else Color(0x59FFFFFF)))
            }
        }
    }
}

/** A card under the hero card (the deck), dimmed. [x] is its left edge, [down] how far it shows below. */
@Composable
private fun TvDeckPeek(m: Meta, x: Dp, w: Dp, h: Dp, dim: Float, down: Dp) {
    val req = tvImage(backdropOf(m) ?: m.poster, w, h)
    Box(Modifier.offset(x, down).size(w, h).clip(DeckShape).background(Surface1)) {
        if (req != null) AsyncImage(req, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
    }
}

/**
 * Focus look for the hero buttons: grows a little; the red Play pill glows red behind (keeps its exact look), the
 * other button gets a thin white ring. OK on the remote runs [onClick].
 */
@Composable
private fun TvPillFocus(modifier: Modifier, onFocus: (Boolean) -> Unit, onClick: () -> Unit, ring: Boolean = true, content: @Composable () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val a by animateFloatAsState(if (f) 1f else 0f, tween(140), label = "pill")
    val focusCb = rememberUpdatedState(onFocus)
    val clickCb = rememberUpdatedState(onClick)
    Box(
        modifier
            .graphicsLayer { val sc = 1f + 0.08f * a; scaleX = sc; scaleY = sc }
            .drawWithContent {
                val pill = size.width > size.height * 1.5f
                if (!ring && a > 0.01f) {
                    for (i in 1..5) {
                        val g = 4.dp.toPx() * i
                        drawRoundRect(Color(0xFFE50914), topLeft = Offset(-g, -g + 2.dp.toPx()), size = Size(size.width + 2 * g, size.height + 2 * g),
                            cornerRadius = CornerRadius(size.height / 2f + g), alpha = 0.10f * a)
                    }
                }
                drawContent()
                if (ring && a > 0.01f) {
                    val o = 3.dp.toPx()
                    val r = size.height / 2f + o
                    drawRoundRect(Color.White, topLeft = Offset(-o, -o), size = Size(size.width + 2 * o, size.height + 2 * o),
                        cornerRadius = CornerRadius(if (pill) r else 12.dp.toPx() + o), style = Stroke(2.dp.toPx()), alpha = a)
                }
            }
            .onFocusChanged { if (it.isFocused != f) { f = it.isFocused; focusCb.value(it.isFocused) } }
            .onKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (ok && e.type == KeyEventType.KeyUp) clickCb.value()
                ok
            }
            .focusable(),
    ) { content() }
}

// ───────────── Cards ─────────────

private val CwShade = Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD9000000))
private val NextBadge = Brush.verticalGradient(listOf(Color(0xFFF0242E), Color(0xFFB5070F)))

/**
 * Continue Watching card, big like Hotstar (420×236): artwork, "S2 E2 · EPISODE" + title, red progress line, and a
 * badge top-right — time left / "Next · S5 E12" (red) / "New episode in 4 days" (gold).
 */
@Composable
fun TvContinueCard(vm: AppViewModel, p: Progress, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = remember { RoundedCornerShape(14.dp) }
    var focused by remember { mutableStateOf(false) }
    // Focused: the card opens wider with the title's details (ratings, story, time left, the next episode).
    val w by androidx.compose.animation.core.animateDpAsState(if (focused) 600.dp else 420.dp, tween(220), label = "cwW")
    val req = tvImage(p.image, 600.dp, 236.dp)
    var badge by remember(p.key, p.positionMs) { mutableStateOf(if (p.finished()) null else CwBadge(CwBadge.LEFT, cwTimeLeft(p))) }
    LaunchedEffect(p.key, p.positionMs) { if (p.finished()) badge = runCatching { vm.cwBadge(p) }.getOrNull() }
    var info by remember(p.key) { mutableStateOf<Meta?>(null) }
    LaunchedEffect(focused, p.key) { if (focused && info == null) { delay(250); info = runCatching { vm.cwMeta(p) }.getOrNull() } }
    val ep = Regex(":(\\d+):(\\d+)$").find(p.key)?.let { "S${it.groupValues[1]} E${it.groupValues[2]}" }
    Box(modifier.size(w, 236.dp).tvCard(14.dp, scale = 1f, onFocus = { focused = it }, onClick = onClick).clip(shape).background(Surface1)) {
        if (req != null) AsyncImage(req, p.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(if (focused) CwInfoShade else CwShade))
        if (!focused) {
            badge?.let { b -> CwBadgeView(b, Modifier.align(Alignment.TopEnd).padding(12.dp)) }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                if (ep != null) Text(ep, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = Color(0xFFD8DBE3))
                Text(p.title.substringBefore("  S").trim(), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            val m = info
            val epVid = m?.videos?.firstOrNull { it.id == p.key }
            val nextVid = badge?.nextId?.let { id -> m?.videos?.firstOrNull { it.id == id } }
                ?: m?.videos?.let { vs -> val l = vs.filter { (it.season ?: 0) > 0 }.sortedWith(compareBy({ it.season }, { it.episode })); l.getOrNull(l.indexOfFirst { it.id == p.key } + 1) }
            if (p.isSeries() && nextVid != null) Column(
                Modifier.align(Alignment.TopEnd).padding(12.dp).width(150.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x99000000))
                    .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(10.dp)),
            ) {
                AsyncImage(tvImage(nextVid.thumbnail ?: m?.background, 150.dp, 70.dp), null, Modifier.fillMaxWidth().height(70.dp), contentScale = ContentScale.Crop)
                Text("Next: S${nextVid.season} E${nextVid.episode}" + (nextVid.title ?: nextVid.name)?.let { " · $it" }.orEmpty(),
                    Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else badge?.takeIf { it.kind != CwBadge.LEFT }?.let { b -> CwBadgeView(b, Modifier.align(Alignment.TopEnd).padding(12.dp)) }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 18.dp, bottom = 16.dp).width(380.dp)) {
                Text(m?.name ?: p.title.substringBefore("  S").trim(), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(ep?.let { e -> e + ((epVid?.title ?: epVid?.name)?.let { " · $it" } ?: "") } ?: "Movie",
                    m?.releaseInfo?.take(4), m?.runtime).joinToString("  ·  "),
                    Modifier.padding(top = 2.dp), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE6E8EE), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    m?.imdbRating?.takeIf { it.isNotBlank() }?.let {
                        Text("IMDb", Modifier.clip(RoundedCornerShape(3.dp)).background(Color(0xFFF5C518)).padding(horizontal = 4.dp), fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color.Black)
                        Text(" $it", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF5C518))
                        Spacer(Modifier.width(10.dp))
                    }
                    m?.genres?.take(2)?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), fontSize = 11.sp, color = Color(0xFFC9CED8), maxLines = 1) }
                }
                ((epVid?.overview ?: epVid?.description)?.takeIf { it.isNotBlank() } ?: m?.description)?.let {
                    Text(it, Modifier.padding(top = 5.dp), fontSize = 11.sp, lineHeight = 15.sp, color = Color(0xFFD0D3DB), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(120.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF))) {
                        Box(Modifier.fillMaxWidth(p.fraction()).fillMaxHeight().background(BrandRed))
                    }
                    Text("  " + (if (p.finished()) (badge?.text ?: "Watched") else cwTimeLeft(p)), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE6E8EE))
                }
            }
        }
        if (!focused) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x40FFFFFF))) {
            Box(Modifier.fillMaxWidth(p.fraction()).fillMaxHeight().background(BrandRed))
        }
    }
}

private val CwInfoShade = Brush.horizontalGradient(0f to Color(0xF20A0B10), 0.5f to Color(0xB30A0B10), 1f to Color(0x330A0B10))

/** The Continue Watching corner badge (shared look for TV; the phone draws its own size). */
@Composable
fun CwBadgeView(b: CwBadge, modifier: Modifier = Modifier, small: Boolean = false) {
    val fs = if (small) 10.sp else 11.sp
    when (b.kind) {
        CwBadge.NEXT -> Row(
            modifier.clip(RoundedCornerShape(9.dp)).background(NextBadge).border(1.5.dp, Color.Black, RoundedCornerShape(9.dp))
                .padding(start = 5.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(8.dp)).background(Color.White), Alignment.Center) {
                Text("▶", fontSize = 7.sp, color = Color(0xFFC2070F))
            }
            Spacer(Modifier.width(6.dp))
            Text(b.text, fontSize = fs, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
        CwBadge.AIRS -> Row(
            modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xC714141A)).border(1.dp, Color(0x99F5C518), RoundedCornerShape(9.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFFFD84D)))
            Spacer(Modifier.width(6.dp))
            Text(b.text, fontSize = fs, fontWeight = FontWeight.Bold, color = Color(0xFFFFD84D))
        }
        else -> Text(
            b.text, modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xC714141A)).padding(horizontal = 9.dp, vertical = 5.dp),
            fontSize = fs, fontWeight = FontWeight.Bold, color = Color.White,
        )
    }
}

/**
 * A poster in a Home row that opens up when focused: 120×180 poster → 360×180 landscape with the backdrop, logo /
 * title, info line and two lines of story (the addon's own poster art is shown untouched while not focused).
 */
@Composable
fun TvExpandCard(m: Meta, modifier: Modifier = Modifier, onFocus: (Boolean) -> Unit = {}, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val w by androidx.compose.animation.core.animateDpAsState(if (focused) 360.dp else 120.dp, tween(200), label = "expand")
    val shape = remember { RoundedCornerShape(10.dp) }
    val posterReq = tvImage(m.poster ?: backdropOf(m), 120.dp, 180.dp)
    val wideReq = tvImage(backdropOf(m) ?: m.poster, 360.dp, 180.dp)
    Box(
        modifier.size(w, 180.dp)
            .tvCard(10.dp, scale = 1f, onFocus = { f -> focused = f; onFocus(f) }, onClick = onClick)
            .clip(shape).background(Surface1),
    ) {
        if (!focused) {
            if (posterReq != null) AsyncImage(posterReq, m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(m.name, Modifier.align(Alignment.Center).padding(8.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 3)
        } else {
            if (wideReq != null) AsyncImage(wideReq, m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xE6000000), 0.6f to Color(0x66000000), 1f to Color.Transparent)))
            Column(Modifier.align(Alignment.BottomStart).padding(14.dp).width(250.dp)) {
                val rating = m.imdbRating?.toDoubleOrNull()
                if (rating != null) Text("IMDb ${m.imdbRating}", Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFF5C518))
                    .padding(horizontal = 5.dp, vertical = 1.dp), fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color.Black)
                var logoFailed by remember(m.id) { mutableStateOf(false) }
                val logoReq = tvImage(logoOf(m), 200.dp, 50.dp)
                if (logoReq != null && !logoFailed) AsyncImage(logoReq, m.name, Modifier.padding(top = 6.dp).widthIn(max = 200.dp).height(42.dp),
                    contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, onError = { logoFailed = true })
                else Text(m.name, Modifier.padding(top = 6.dp), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = (listOfNotNull(m.releaseInfo?.take(4), m.runtime) + m.genres.orEmpty().take(2)).filter { it.isNotBlank() }.joinToString(" • ")
                if (line.isNotBlank()) Text(line, Modifier.padding(top = 4.dp), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE6E8EE), maxLines = 1)
                m.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, Modifier.padding(top = 3.dp), fontSize = 9.5.sp, lineHeight = 13.sp, color = Color(0xFFCFD3DC), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private val FolderShade = Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xB3000000))

/** A collection folder tile: cover art, optional logo, title (unless hidden). */
@Composable
fun TvFolderTile(f: NFolder, modifier: Modifier = Modifier, onFocus: ((Boolean) -> Unit)? = null, onClick: () -> Unit) {
    val (w, h) = when (f.tileShape.uppercase()) {
        "POSTER", "PORTRAIT" -> 90.dp to 135.dp
        "SQUARE" -> 100.dp to 100.dp
        else -> 150.dp to 85.dp
    }
    val shape = remember { RoundedCornerShape(12.dp) }
    val cover = f.cover ?: f.hero
    val req = tvImage(cover, w, h)
    val logoReq = tvImage(f.logo, w * 0.62f, h * 0.5f)
    var logoFailed by remember(f.id) { mutableStateOf(false) }
    Box(modifier.size(w, h).tvCard(12.dp, onFocus = onFocus, onClick = onClick).clip(shape).background(Color(0xFF15161C))) {
        if (req != null) AsyncImage(req, f.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (logoReq != null && !logoFailed) {
            AsyncImage(logoReq, f.title, Modifier.align(Alignment.Center).fillMaxWidth(0.62f).fillMaxHeight(0.5f),
                contentScale = ContentScale.Fit, onError = { logoFailed = true })
        } else if (!f.hideTitle || cover == null) {
            if (cover != null) Box(Modifier.fillMaxSize().background(FolderShade))
            Text(
                f.title,
                if (cover != null) Modifier.align(Alignment.BottomStart).padding(start = 9.dp, end = 9.dp, bottom = 7.dp)
                else Modifier.align(Alignment.Center).padding(8.dp),
                fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ───────────── Library (beta: Continue Watching + My List) ─────────────

@Composable
fun TvLibraryScreen(vm: AppViewModel, onOpen: (Meta) -> Unit, onPlay: (Screen.Player) -> Unit, onLeftEdge: (FocusRequester) -> Unit) {
    val progress by vm.progress.collectAsStateWithLifecycle()
    val myList by vm.myList.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    val cwState = rememberLazyListState()
    val listState = rememberLazyListState()
    val empty = progress.isEmpty() && myList.isEmpty()
    LaunchedEffect(empty) {
        if (empty) return@LaunchedEffect
        withFrameNanos { }
        first.tryFocus()
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 30.dp, bottom = 60.dp)) {
            item(key = "title") {
                Text("Library", Modifier.padding(start = TvEdge, bottom = 4.dp), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (progress.isNotEmpty()) item(key = "cw") {
                TvRow(progress, key = { it.key }, title = t("continueWatching").replace("{name}", "").trim(), state = cwState,
                    restoreIndex = 0, restoreRequester = first, onLeftEdge = onLeftEdge) { _, p, mod ->
                    TvContinueCard(vm, p, mod) { if (p.finished()) onOpen(p.asMeta()) else onPlay(Screen.Player(p.title, p.url, p.subs, p.key, p.image, p.positionMs)) }
                }
            }
            if (myList.isNotEmpty()) item(key = "list") {
                TvMetaRow("My List", myList, onOpen, restoreIndex = if (progress.isEmpty()) 0 else -1, restoreRequester = first, onLeftEdge = onLeftEdge)
            }
            if (empty) item(key = "empty") {
                Text("Titles you add to My List and shows you're watching appear here.", Modifier.padding(start = TvEdge, top = 24.dp),
                    fontSize = 12.sp, color = TvSubText)
            }
        }
    }
}
