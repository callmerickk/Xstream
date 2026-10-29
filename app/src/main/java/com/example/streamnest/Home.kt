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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
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

@Composable
private fun SectionTitle(text: String) =
    Text(text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE6E9F2),
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 12.dp))

/** The app's logo: the uploaded one from Developer's Corner, or the built-in red mark. */
@Composable
fun AppLogo(vm: AppViewModel, size: Dp, modifier: Modifier = Modifier) {
    val ver by vm.imgVersion.collectAsStateWithLifecycle()
    val custom = remember(ver) { vm.logoFile().takeIf { it.exists() } }
    if (custom != null) AsyncImage(custom, null, modifier.size(size).clip(RoundedCornerShape(size * 0.22f)), contentScale = ContentScale.Crop)
    else {
        val look by AppLook.logo.collectAsStateWithLifecycle()
        Image(painterResource(AppLook.logoRes(look)), null, modifier.size(size))
    }
}

private data class DeckItem(val meta: Meta, val catalog: String)
/** The top cards AIDEN picked for this profile. */
private const val AIDEN_PICK = "\u0001aiden"

/** LazyColumn key of the top cards (also used to find them in the list's layout info). */
private const val DeckKey = "deck"
/** The endless deck repeats its cards this many times (and starts in the middle). */
private const val DeckLoops = 2000

/**
 * A soft drop shadow drawn as a few stacked translucent rounded rects behind the content.
 * Much cheaper than Modifier.shadow on weak GPUs (Fire TV): no elevation/RenderNode shadow work, it is recorded once
 * per size, and it costs nothing extra while the element moves or fades. [corner] is clamped to a pill at most.
 */
internal fun Modifier.softHalo(corner: Dp, spread: Dp = 10.dp, strength: Float = 0.45f, dy: Dp = 3.dp): Modifier = drawWithCache {
    val steps = 4
    val s = spread.toPx()
    val off = dy.toPx()
    val r = minOf(corner.toPx(), size.minDimension / 2f)
    val c = Color.Black.copy(alpha = strength / steps)
    onDrawBehind {
        for (i in 1..steps) {
            val g = s * i / steps
            drawRoundRect(
                color = c,
                topLeft = Offset(-g, -g + off),
                size = Size(size.width + 2 * g, size.height + 2 * g),
                cornerRadius = CornerRadius(r + g),
            )
        }
    }
}

private val CapsuleBrush = Brush.horizontalGradient(listOf(Color(0xF2263070), Color(0xF24A2468)))

@Composable
fun HomeScreen(
    vm: AppViewModel,
    listState: LazyListState,
    capsuleHide: State<Float>,
    onOpen: (Meta) -> Unit,
    onPlay: (Screen.Player) -> Unit,
    onLive: () -> Unit,
    onAi: () -> Unit,
    onManual: (Meta, String) -> Unit = { _, _ -> },
    onOpenCollection: (NCollection, NFolder) -> Unit = { _, _ -> },
) {
    val allShelves by vm.shelves.collectAsStateWithLifecycle()
    // At app open, in the background: Discover's titles and the Hindi titles for both Trending rows, so neither changes
    // in front of you.
    LaunchedEffect(allShelves.isNotEmpty()) {
        if (allShelves.isEmpty()) return@LaunchedEffect
        launch { runCatching { HindiTrending.list("movie") } }
        launch { runCatching { HindiTrending.list("series") } }
    }
    LaunchedEffect(allShelves) { DiscoverPool.ensure(vm, allShelves) }
    val loading by vm.loading.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    // Continue Watching: the first title's links are looked up ahead (home source ready → Resume starts at once).
    val cwKey = progress.firstOrNull()?.key
    LaunchedEffect(cwKey) {
        val k = cwKey ?: return@LaunchedEffect
        delay(3_000)
        if (vm.qsActive()) runCatching { vm.quickStreams(if (k.contains(":")) "series" else "movie", k) }
    }
    val myList by vm.myList.collectAsStateWithLifecycle()
    val profile by vm.current.collectAsStateWithLifecycle()
    val recs by vm.recommended.collectAsStateWithLifecycle()
    var actionsFor by remember { mutableStateOf<Progress?>(null) }
    // Xstream: long-press on any poster lifts it out with its trailer.
    var holdFor by remember { mutableStateOf<Pair<Meta, androidx.compose.ui.geometry.Rect>?>(null) }
    var addFor by remember { mutableStateOf<Meta?>(null) }
    var cwAll by remember { mutableStateOf(false) }
    // Where each Continue Watching card is (the long-press menu lifts it from there).
    val cwRects = remember { mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    val lifted = actionsFor?.key ?: holdFor?.first?.id
    val homeBlur by androidx.compose.animation.core.animateDpAsState(if (lifted != null && !BuildConfig.IS_TV) 14.dp else 0.dp,
        androidx.compose.animation.core.tween(320), label = "cwBlur")
    val showRecs = isOn(t("showRecs"))
    val recsFirst = isOn(t("recsAfterCw"))
    // Old default names (kept by phones that saved them) give way to the new ones.
    val recTitle = t("recommendedFor").let { if (it == "Recommended for {name}") "{ai} picked for {name}" else it }
        .replace("{ai}", aiName().lowercase().replaceFirstChar { it.uppercase() }).replace("{name}", profile?.name?.takeIf { it.isNotBlank() && it != "Me" } ?: "you")
    var filter by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    val hasSports = remember(allShelves) { allShelves.any { it.title.contains("sport", ignoreCase = true) } }
    val rowsOrder = t("rowsOrder")
    val rowsHidden = t("rowsHidden")
    val shuffleOn = isOn(t("shuffleRows"))
    // Films only in cinemas so far (no web / Blu-ray release) stay off Home; search still finds them.
    val cinemaHidden by CinemaOnly.hidden.collectAsState()
    LaunchedEffect(Unit) { if (!BuildConfig.IS_TV) CinemaOnly.load(vm.appContext) }
    LaunchedEffect(allShelves.size) { if (!BuildConfig.IS_TV && allShelves.isNotEmpty()) { delay(6_000); runCatching { CinemaOnly.check(vm, allShelves.flatMap { it.items }) } } }
    val arranged = remember(allShelves, rowsOrder, rowsHidden, shuffleOn, cinemaHidden) {
        val hidden = rowsHidden.split("|").toSet()
        // Fresh every time the app starts (same order while it stays open): each row is shuffled once per launch.
        // A catalogue can list the same title twice; a row with two equal cards crashed Home (0.9.55), so repeats go first.
        arrangeShelves(allShelves, rowsOrder).filter { it.pinned || it.title !in hidden }
            .map { sh -> sh.copy(items = sh.items.distinctBy { it.type + it.id }.filter { it.id.substringBefore(":") !in cinemaHidden }) }.filter { it.items.isNotEmpty() }
            .map { sh -> if (shuffleOn) sh.copy(items = sh.items.shuffled(java.util.Random(HomeShuffle.seed xor sh.title.hashCode().toLong()))) else sh }
    }
    val shelves = remember(arranged, filter) {
        arranged.filter { s ->
            when (filter) {
                null -> true
                "sports" -> s.title.contains("sport", ignoreCase = true)
                else -> s.items.firstOrNull()?.type == filter
            }
        }
    }
    // Top cards: mix the first two rows (e.g. popular movies + popular series).
    // Top cards: popular (first two rows), picked for you (from your votes and history) and new arrivals, mixed.
    val deck = remember(shelves, recs, filter) {
        val a = shelves.getOrNull(0)?.let { s -> s.items.map { DeckItem(it, s.title) } }.orEmpty()
        val b = shelves.getOrNull(1)?.let { s -> s.items.map { DeckItem(it, s.title) } }.orEmpty()
        val r = recs.filter { filter == null || filter == "sports" || it.type == filter }.map { DeckItem(it, AIDEN_PICK) }
        val n = shelves.drop(2).firstOrNull { sh -> listOf("new", "latest", "fresh", "release", "arriv").any { sh.title.contains(it, true) } }
            ?.let { sh -> sh.items.map { DeckItem(it, sh.title) } }.orEmpty()
        // Most of the top cards are AIDEN's picks for you; a few popular / new titles keep it fresh.
        val rest = (0 until maxOf(a.size, b.size, n.size)).flatMap { i -> listOfNotNull(a.getOrNull(i), b.getOrNull(i), n.getOrNull(i)) }
        // One card per title: the same film can come from two rows with different ids (an addon id and an IMDb id), so
        // titles are matched by IMDb id and by name. A title that is trending stays a trending card: AIDEN picks something else.
        fun nameKey(m: Meta) = m.name.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        fun keys(m: Meta) = listOf("id:" + m.id.substringBefore(":"), "n:" + nameKey(m))
        val restOk = rest.filter { it.meta.poster != null }
        val trendingKeys = restOk.take(12).flatMap { keys(it.meta) }.toSet()
        val ri = r.filter { it.meta.poster != null && keys(it.meta).none { k -> k in trendingKeys } }.iterator()
        val oi = restOk.iterator()
        val seen = HashSet<String>()
        val mixed = ArrayList<DeckItem>()
        fun put(d: DeckItem): Boolean { val k = keys(d.meta); if (k.any { it in seen }) return false; seen += k; mixed += d; return true }
        while (mixed.size < 12 && (ri.hasNext() || oi.hasNext())) {
            var got = 0
            while (got < 3 && ri.hasNext()) if (put(ri.next())) got++
            while (oi.hasNext()) if (put(oi.next())) break
        }
        mixed.take(12)
    }

    val tasteKey = t("tasteLangs") + "|" + t("tasteTypes")
    var newForYou by remember { mutableStateOf<List<Meta>>(emptyList()) }
    var tasteExtras by remember { mutableStateOf<List<Shelf>>(emptyList()) }
    LaunchedEffect(tasteKey, profile?.id) {
        if (BuildConfig.IS_TV) return@LaunchedEffect
        newForYou = runCatching { TasteRows.newForYou(vm) }.getOrDefault(emptyList())
        tasteExtras = runCatching { TasteRows.extras(vm) }.getOrDefault(emptyList())
    }
    val autoSlide = t("autoSlide").trim().lowercase() != "no"
    val slideSecs = t("slideSeconds").trim().toIntOrNull()?.coerceIn(2, 20) ?: 5
    val showBrand = t("showBrand").trim().lowercase() == "yes"
    val appName = t("appName")
    val cwStyle = t("cwStyle")
    val showHero = isOn(t("showHero"))
    val cardTrailers = isOn(t("cardTrailers"))
    val cwShow = isOn(t("cwShow"))
    val continueTitle = t("continueWatching").let { if (it == "Continue Watching for {name}") "Pick up where you left off" else it }.replace("{name}", profile?.name ?: "")
    val newOn = t("newOn").replace("{app}", appName)
    val pillLeft = t("pillLeft"); val pillRight = t("pillRight"); val pillSports = t("pillSports"); val pillLive = t("pillLive")
    // Remembered so FloatingFilters gets the same list instance and can skip recomposition.
    val pillLabels = remember(pillLeft, pillRight, pillSports, pillLive, hasSports) {
        buildList {
            add(pillLeft to "series")
            add(pillRight to "movie")
            if (hasSports) add(pillSports to "sports")
            add(pillLive to "live")
        }
    }
    // The top cards keep their position when you scroll away and come back (state lives outside the lazy item).
    // Endless: a very long strip of pages that repeats the cards (card = page mod count), started in the middle, so
    // swiping either way never reaches an end — after the last card comes the first, before the first the last.
    val deckPager = rememberPagerState { if (deck.size > 1) deck.size * DeckLoops else deck.size }
    // Live server: the first three movie cards' streams are asked for quietly once Home is up (the server keeps them
    // 5 minutes and looks up the best two files), so tapping one of them starts like a title you already opened.
    val deckIds = remember(deck) { deck.take(3).map { it.meta }.filter { it.type == "movie" && it.id.startsWith("tt") }.map { it.id } }
    LaunchedEffect(deckIds) {
        if (deckIds.isEmpty() || !LiveServer.enabled(vm.appContext)) return@LaunchedEffect
        delay(6_000)
        deckIds.forEach { id -> runCatching { LiveServer.streams(vm.appContext, "movie", id) } }
    }
    // True while at least half of the top cards are on screen. Recomputed on scroll, but only notifies readers
    // when the answer flips, so it costs nothing per frame. Trailers / auto-slide stop when the deck scrolls away.
    val deckOnScreen = remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val d = info.visibleItemsInfo.firstOrNull { it.key == DeckKey }
            d != null && d.offset + d.size / 2 > info.viewportStartOffset
        }
    }
    val tint = Palette.tint
    val bg = Bg
    val topBrush = remember(tint, bg) { Brush.verticalGradient(listOf(tint, bg)) }
    val accent = Accent
    val brandRim = remember(accent) { Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.25f))) }
    val onFilter: (String) -> Unit = remember(onLive) {
        { type: String -> if (type == "live") onLive() else { filter = if (filter == type) null else type }; Unit }
    }
    val onTop: () -> Unit = remember(listState, scope) { { scope.launch { listState.animateScrollToItem(0) }; Unit } }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(600.dp).background(topBrush))

        CompositionLocalProvider(LocalTitleHold provides { m, r -> holdFor = m to r }) {
        LazyColumn(Modifier.fillMaxSize().then(if (homeBlur > 0.dp) Modifier.blur(homeBlur) else Modifier), state = listState) {
            item(key = "header", contentType = "header") {
                Row(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val pill = RoundedCornerShape(50)
                    Row(
                        Modifier.clip(pill).background(Color(0x99101420))
                            .border(1.dp, brandRim, pill)
                            .padding(start = 6.dp, end = if (showBrand) 16.dp else 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Signal X logo with its animation (Developer's Corner › Logo & animation); the hanging X stays an option.
                        val lookLogo by AppLook.logo.collectAsStateWithLifecycle()
                        val lookAnim by AppLook.anim.collectAsStateWithLifecycle()
                        val imgVer by vm.imgVersion.collectAsStateWithLifecycle()
                        val customLogo = remember(imgVer) { vm.logoFile().exists() }
                        val signalAnim = !customLogo && lookLogo == "signal" && lookAnim != "hanging" && lookAnim != "none"
                        var wordIn by remember { mutableStateOf(!signalAnim || !(lookAnim == "broadcast" || lookAnim == "broadcast_living") ) }
                        val wordA by androidx.compose.animation.core.animateFloatAsState(if (wordIn) 1f else 0f, androidx.compose.animation.core.tween(500), label = "wordIn")
                        if (signalAnim) SignalMark(28.dp, lookAnim, paused = listState.isScrollInProgress) { wordIn = true }
                        else AppLogo(vm, 28.dp)
                        if (showBrand) {
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.graphicsLayer { alpha = wordA; translationX = (1f - wordA) * -24f }) {
                                Wordmark(appName, animated = lookAnim == "hanging")
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (!BuildConfig.IS_TV) { DownloadsButton(onPlay); Spacer(Modifier.width(10.dp)) }
                    NotificationBell(onTogether = { BellNav.together.value = true }, onOpen = onOpen)
                }
            }
            item(key = "forYou", contentType = "forYou") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("forYou"), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFEDEFF7),
                        modifier = Modifier.weight(1f).padding(start = 16.dp, top = 18.dp, bottom = 16.dp))
                    // Live server music (64-bit app).
                    if (!BuildConfig.IS_TV) MusicChip(Modifier.padding(end = 16.dp, top = 4.dp))
                }
            }
            if (!BuildConfig.IS_TV) item(key = "langPills", contentType = "langPills") { LanguagePills(vm, onOpen) }
            if (shelves.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    Box(Modifier.fillMaxWidth().height(420.dp), Alignment.Center) {
                        if (loading) CircularProgressIndicator(color = Accent) else Text(t("emptyMsg"), Modifier.padding(32.dp))
                    }
                }
            } else {
                if (showHero) item(key = DeckKey, contentType = DeckKey) {
                    Box(Modifier.tourTarget("deck")) { Deck(vm, deck, deckPager, deckOnScreen, myList, autoSlide, slideSecs, cardTrailers, onOpen) { WatchlistAsk.toggle(vm, it) } }
                }
            }
            if (progress.isNotEmpty() && filter == null && cwShow) {
                item(key = "cwTitle", contentType = "title") {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Box(Modifier.weight(1f)) { SectionTitle(continueTitle) }
                        Text("See all", Modifier.padding(end = 16.dp, bottom = 14.dp).clip(RoundedCornerShape(50)).clickable { cwAll = true }
                            .padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFB6BDCC))
                    }
                }
                item(key = "cwRow", contentType = "cwRow") {
                    LazyRow(Modifier.tourTarget("cw"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(progress.take(30), key = { it.key }, contentType = { "cw" }) { p ->
                            Box(Modifier.animateItem().onGloballyPositioned { cwRects[p.key] = it.boundsInRoot() }
                                .graphicsLayer { alpha = if (lifted == p.key && !BuildConfig.IS_TV) 0f else 1f }) {
                                ContinueCard(p, cwStyle, vm = vm, onClick = { if (p.finished()) onOpen(p.toMeta()) else onPlay(Screen.Player(p.title, p.url, p.subs, p.key, p.image, p.positionMs)) }, onRemove = { vm.removeProgress(p.key) },
                                    onLongClick = { actionsFor = p })
                            }
                        }
                    }
                }
            }
            // "Recommended for you" right after Continue Watching (Settings › Layout › Home can move it after the Trending rows).
            if (recs.isNotEmpty() && filter == null && showRecs && recsFirst) {
                item(key = "recTitle", contentType = "title") { SectionTitle(recTitle) }
                item(key = "recRow", contentType = "row") { AidenRow(recs, onOpen) }
            }
            // Order: Pick up where you left off → the two Trending rows (numbers) → Aiden's picks → your languages → collections → the rest.
            @Composable fun shelfItem(i: Int, shelf: Shelf) {
                SectionTitle(rowName(shelf.title) ?: if (shelf.title.startsWith("New")) "$newOn · ${shelf.title.removePrefix("New ").trim()}" else shelf.title)
                // The first two rows: tall rounded posters with big 1, 2, 3 numbers (Home Layout can change any row's style).
                when (RowStyles.of(shelf.title, i)) {
                    // Trending rows come in a new order every time the app opens.
                    "numbered" -> NumberedRow(remember(shelf.items) { shelf.items.shuffled(java.util.Random(HomeShuffle.seed xor shelf.title.hashCode().toLong())) }, onOpen)
                    "portrait" -> PortraitRow(shelf.items, onOpen)
                    "landscape" -> LandscapeRow(shelf.items, onOpen)
                    else -> ShelfRow(shelf.items, onOpen)
                }
            }
            // Keys include the index so two catalogs with the same name can never collide (duplicate keys crash).
            itemsIndexed(shelves.take(2), key = { i, s -> "shelf:$i:${s.title}" }, contentType = { _, _ -> "shelf" }) { i, shelf -> shelfItem(i, shelf) }
            if (recs.isNotEmpty() && filter == null && showRecs && !recsFirst) {
                item(key = "recTitle", contentType = "title") { SectionTitle(recTitle) }
                item(key = "recRow", contentType = "row") { AidenRow(recs, onOpen) }
            }
            // Personal: the newest titles in all your languages, mixed; Anime / K-Drama when you picked them.
            if (filter == null && newForYou.isNotEmpty()) {
                item(key = "newForYouTitle", contentType = "title") { SectionTitle("New for you · " + vm.tasteLangs().take(3).joinToString(" · ") { c -> TasteLangs.firstOrNull { it.code == c }?.name ?: c }) }
                item(key = "newForYouRow", contentType = "row") { PortraitRow(newForYou, onOpen) }
            }
            if (filter == null) itemsIndexed(tasteExtras, key = { i, s -> "taste:$i:${s.title}" }, contentType = { _, _ -> "shelf" }) { _, sh ->
                SectionTitle(sh.title); PortraitRow(sh.items, onOpen)
            }
            if (filter == null) item(key = "collections", contentType = "collections") { CollectionsRows(vm) { c, f -> onOpenCollection(c, f) } }
            itemsIndexed(shelves.drop(2), key = { i, s -> "shelf:${i + 2}:${s.title}" }, contentType = { _, _ -> "shelf" }) { i, shelf -> shelfItem(i + 2, shelf) }
            item(key = "bottomSpace", contentType = "spacer") { Spacer(Modifier.navigationBarsPadding().height(180.dp)) }
        }
        }

        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 86.dp)) {
            FloatingFilters(
                vm = vm, hide = capsuleHide,
                filter = filter,
                labels = pillLabels,
                onFilter = onFilter,
                onTop = onTop,
                onAi = onAi,
            )
        }
    }
    holdFor?.let { (m, r) ->
        androidx.compose.ui.window.Dialog({ holdFor = null }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
                SideEffect { w.setDimAmount(0f); w.setWindowAnimations(0) }
            }
            TitleLiftMenu(vm, m, r,
                onDetails = { onOpen(m) },
                onManual = { if (m.type == "series") onOpen(m) else onManual(m, m.id) },
                onWatchlist = { addFor = m },
                onClose = { holdFor = null })
        }
    }
    addFor?.let { m -> AddToWatchlistSheet(vm, m) { addFor = null } }
    if (cwAll) ContinueAllPage(vm, progress, onClose = { cwAll = false },
        onPlay = { p -> cwAll = false; if (p.finished()) onOpen(p.toMeta()) else onPlay(Screen.Player(p.title, p.url, p.subs, p.key, p.image, p.positionMs)) })
    val liftFrom = actionsFor?.let { cwRects[it.key] }
    if (!BuildConfig.IS_TV && actionsFor != null && liftFrom != null) {
        val p = actionsFor!!
        androidx.compose.ui.window.Dialog({ actionsFor = null }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            // A full-screen window above Home (the nav bar and filters too); the card's place is in screen coordinates.
            (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
                SideEffect { w.setDimAmount(0f); w.setWindowAnimations(0) }
            }
            CwLiftMenu(
                p, liftFrom,
                listOf(
                    TitleAction("Go to details", Icons.Default.Info) { onOpen(p.toMeta()) },
                    TitleAction("Play manually", Icons.Default.PlayArrow) { onManual(p.toMeta(), p.key) },
                    TitleAction("Start from beginning", Icons.Default.Replay) { onPlay(Screen.Player(p.title, p.url, p.subs, p.key, p.image, 0L)) },
                    TitleAction("Remove", Icons.Default.DeleteOutline, danger = true) { },
                ),
                onRemove = { vm.removeProgress(p.key) },
                onClose = { actionsFor = null },
            )
        }
    } else actionsFor?.let { p ->
        ContinueActions(
            p,
            onDetails = { onOpen(p.toMeta()) },
            onManual = { onManual(p.toMeta(), p.key) },
            onStartOver = { onPlay(Screen.Player(p.title, p.url, p.subs, p.key, p.image, 0L)) },
            onRemove = { vm.removeProgress(p.key) },
            onDismiss = { actionsFor = null },
        )
    }
}

// Poster Card Style (Help & Settings > Layout > Poster Card Style)
val PosterWidths = linkedMapOf("Compact" to 100, "Dense" to 112, "Standard" to 120, "Balanced" to 126, "Comfort" to 140, "Large" to 156)
val PosterCorners = linkedMapOf("Sharp" to 0, "Subtle" to 4, "Classic" to 8, "Rounded" to 12, "Pill" to 22)

@Composable fun posterWidth(): Dp = (PosterWidths[t("posterWidth")] ?: 126).dp
@Composable fun posterCorner(): Dp = (PosterCorners[t("posterCorner")] ?: 12).dp

/** Rows are shuffled once per app start. */
object HomeShuffle { val seed: Long = System.nanoTime() }

/** How each Home row looks: "numbered" (top 10 style), "portrait", "landscape" or "auto"; set in Home Layout by the developer. */
object RowStyles {
    @Volatile private var raw: String? = null
    @Volatile private var parsed: Map<String, String> = emptyMap()
    fun map(): Map<String, String> {
        val r = Public.vm?.texts?.value?.get("rowStyles")
        if (r != raw) {
            raw = r
            parsed = (r ?: "").lines().mapNotNull { l -> val i = l.indexOf('='); if (i <= 0) null else l.substring(0, i) to l.substring(i + 1).trim() }.toMap()
        }
        return parsed
    }
    fun of(title: String, index: Int): String = map()[title] ?: if (index < 2) "numbered" else "auto"
    fun set(vm: AppViewModel, title: String, style: String) {
        val m = map().toMutableMap(); m[title] = style
        vm.setText("rowStyles", m.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
}

/** Top 10 style: tall rounded posters (real portrait art from TMDB), a big solid number beside each one (1, 2, 3 …). */
@Composable
fun NumberedRow(items: List<Meta>, onOpen: (Meta) -> Unit) {
    val corner = Palette.corner.dp.coerceAtMost(18.dp)
    val shape = remember(corner) { RoundedCornerShape(corner) }
    // Bollywood mixed in: the newest popular Hindi titles of the same kind take every third place.
    val kind = remember(items) { if (items.count { it.type == "series" } > items.size / 2) "series" else "movie" }
    // The row appears once its Hindi titles are in (fetched at app open; at most 3 s), so it never reshuffles while you
    // look at it — that reshuffle is what left it scrolled to 7 or 8.
    val hindi by produceState(HindiTrending.cachedOrNull(kind), kind) {
        if (value == null) value = kotlinx.coroutines.withTimeoutOrNull(3_000) { runCatching { HindiTrending.list(kind) }.getOrNull() } ?: emptyList()
    }
    val h = hindi ?: run { Spacer(Modifier.fillMaxWidth().height(186.dp)); return }
    val top = remember(items, h) { HindiTrending.mix(items, h).take(10) }
    // All ten posters are asked for at once (one quick TMDB call each, remembered), so the row fills in together.
    LaunchedEffect(top) { top.forEach { m -> launch { runCatching { TmdbPosters.portrait(m) } } } }
    val fill = remember { Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFD5D9E2), Color(0xFF9AA1B0))) }
    // Like Netflix: each number has its own space to the left of its poster; the poster only just touches its edge.
    // A fresh scroll position for every list: it always opens at 1.
    val rowState = remember(top) { androidx.compose.foundation.lazy.LazyListState() }
    LazyRow(state = rowState, contentPadding = PaddingValues(start = 6.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(top, key = { _, m -> "n" + m.type + m.id }, contentType = { _, _ -> "num" }) { i, m ->
            val num = "${i + 1}"
            val numW = if (num.length > 1) 138.dp else 78.dp
            Box(Modifier.width(numW + 116.dp).height(186.dp)) {
                val style = androidx.compose.ui.text.TextStyle(fontSize = 136.sp, fontWeight = FontWeight.Black, letterSpacing = (-6).sp, lineHeight = 136.sp)
                Box(Modifier.align(Alignment.BottomStart).width(numW + 8.dp).offset(y = 20.dp), contentAlignment = Alignment.BottomEnd) {
                    Text(num, style = style.merge(androidx.compose.ui.text.TextStyle(color = Color(0xFF05060A),
                        drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 12f, join = androidx.compose.ui.graphics.StrokeJoin.Round))), softWrap = false)
                    Text(num, style = style.merge(androidx.compose.ui.text.TextStyle(brush = fill,
                        shadow = androidx.compose.ui.graphics.Shadow(Color(0xCC000000), androidx.compose.ui.geometry.Offset(0f, 6f), 18f))), softWrap = false)
                }
                TallPoster(m, Modifier.align(Alignment.BottomEnd).width(120.dp).aspectRatio(2f / 3f)
                    .softHalo(corner, spread = 8.dp, strength = 0.5f, dy = 4.dp).clip(shape).titleTap(m) { onOpen(m) })
            }
        }
    }
}

/**
 * A real tall poster: TMDB's full-size portrait art (w780), else Cinemeta's large poster, else the title's own tall one.
 * Only when a title has nothing tall at all is its wide picture shown whole at the top over a blurred copy — never cut.
 */
@Composable
fun TallPoster(m: Meta, modifier: Modifier) {
    var tries by remember(m.id) { mutableIntStateOf(0) }
    val choices by produceState<List<String>?>(TmdbPosters.cached(m)?.let { listOfNotNull(it, TmdbPosters.fallback(m)) }, m.id) {
        if (value == null) {
            val t = kotlinx.coroutines.withTimeoutOrNull(3500) { TmdbPosters.portrait(m) }
            value = listOfNotNull(t, TmdbPosters.fallback(m)).distinct()
        }
    }
    Box(modifier.background(Surface1)) {
        val list = choices ?: return@Box
        val url = list.getOrNull(tries)
        if (url != null) {
            AsyncImage(url, m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                onError = { tries++ })
        } else {
            val wide = hiResArt(backdropOf(m) ?: m.poster)
            AsyncImage(wide, null, Modifier.fillMaxSize().blur(24.dp), contentScale = ContentScale.Crop)
            AsyncImage(wide, m.name, Modifier.fillMaxWidth().aspectRatio(16f / 9f).align(Alignment.TopCenter), contentScale = ContentScale.Fit)
            Text(m.name, Modifier.align(Alignment.BottomCenter).padding(8.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** Aiden's picks: tall posters, each with the colour AI face in the corner. */
@Composable
fun AidenRow(items: List<Meta>, onOpen: (Meta) -> Unit) {
    val corner = Palette.corner.dp.coerceAtMost(18.dp)
    val shape = remember(corner) { RoundedCornerShape(corner) }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(items.distinctBy { it.type + it.id }, key = { i, m -> "a$i" + m.type + m.id }) { _, m ->
            Box(Modifier.width(posterWidth()).aspectRatio(2f / 3f).clip(shape).background(Surface1).titleTap(m) { onOpen(m) }) {
                AsyncImage(hiResArt(TmdbPosters.fallback(m) ?: m.poster), m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(30.dp).clip(CircleShape).background(Color(0xB3000000))
                    .border(1.dp, Color(0x66FF5A64), CircleShape), Alignment.Center) {
                    AiFace(Modifier.size(22.dp))
                }
            }
        }
    }
}

/** Tall rounded posters (same corners as the top cards). */
@Composable
fun PortraitRow(items: List<Meta>, onOpen: (Meta) -> Unit) {
    val corner = Palette.corner.dp.coerceAtMost(18.dp)
    val shape = remember(corner) { RoundedCornerShape(corner) }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { "p" + it.type + it.id }) { m ->
            AsyncImage(hiResArt(m.poster ?: hdPoster(m)), m.name, Modifier.width(posterWidth()).aspectRatio(2f / 3f).clip(shape).background(Surface1).titleTap(m) { onOpen(m) },
                contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        }
    }
}

/** Applies the row order chosen in Home Layout; rows not in the list keep their original order at the end. */
/** A Home row's name as the developer renamed it for everyone (Home Layout › rename), or null = its own name. */
fun rowName(original: String): String? = RowNames.map()[original]

object RowNames {
    @Volatile private var raw: String? = null
    @Volatile private var parsed: Map<String, String> = emptyMap()
    /** texts["rowNames"]: one "original=new name" per line (sent with the developer's defaults). */
    fun map(): Map<String, String> {
        val r = Public.vm?.texts?.value?.get("rowNames")
        if (r != raw) {
            raw = r
            parsed = (r ?: "").lines().mapNotNull { l -> val i = l.indexOf('='); if (i <= 0) null else l.substring(0, i) to l.substring(i + 1).trim() }
                .filter { it.second.isNotBlank() }.toMap()
        }
        return parsed
    }
    fun set(vm: AppViewModel, original: String, name: String) {
        val m = map().toMutableMap()
        if (name.isBlank() || name == original) m.remove(original) else m[original] = name.trim()
        vm.setText("rowNames", m.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
}

fun arrangeShelves(all: List<Shelf>, order: String): List<Shelf> {
    val wanted = order.split("|").filter { it.isNotBlank() }
    // The developer's published rows stay on top, in the developer's order; your own rows follow in yours.
    val (pinned, own) = all.partition { it.pinned }
    return pinned + own.withIndex().sortedBy { (i, s) -> wanted.indexOf(s.title).let { if (it < 0) 10_000 + i else it } }.map { it.value }
}

/**
 * A home row. When the catalog addon designs its own posters (wide "Top 10" artwork with rank, rating and provider,
 * square art…), they are shown whole, in their own shape, exactly as the addon made them. Otherwise: wide landscape
 * cards (Hotstar style) or tall posters, depending on settings.
 */
@Composable
fun ShelfRow(items: List<Meta>, onOpen: (Meta) -> Unit) {
    val landscape = isOn(t("landscapePosters"))
    val addonDesign = isOn(t("addonPosters"))
    val w = posterWidth()
    // Shape the addon declares (posterShape), or the real shape of its first poster once it has loaded.
    val declared = items.firstNotNullOfOrNull { it.posterShape?.lowercase()?.takeIf { s -> s == "landscape" || s == "square" } }
    var detected by remember(items.firstOrNull()?.id) { mutableStateOf<Float?>(null) }
    val ratio: Float? = if (!addonDesign) null else when (declared) {
        "landscape" -> detected?.takeIf { it > 1.2f } ?: (16f / 9f)
        "square" -> 1f
        // Tall posters the addon makes itself (configured rating / provider posters): shown as they are, not swapped
        // for a wide backdrop card.
        else -> detected?.takeIf { it > 1.2f || it in 0.9f..1.1f } ?: (2f / 3f).takeIf { items.take(4).any { m -> isDesignedPoster(m.poster) } }
    }
    val corner = posterCorner()
    val posterShape = remember(corner) { RoundedCornerShape(corner) }
    // Coil sizes each request from the (fixed) layout constraints, so posters are decoded at card size, not full size.
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(if (ratio != null) 14.dp else 10.dp)) {
        itemsIndexed(items, key = { _, m -> m.type + m.id }, contentType = { _, _ -> "card" }) { i, m ->
            val onRatio: (Float) -> Unit = { r -> if (i == 0 && detected == null) detected = r }
            when {
                ratio != null -> AddonPosterCard(m, ratio, onRatio) { onOpen(m) }
                landscape -> LandscapeCard(m, w * 1.87f) { onOpen(m) }
                else -> AsyncImage(
                    hiResArt(m.poster), m.name,
                    Modifier.width(w).aspectRatio(2f / 3f).clip(posterShape).background(Surface1).titleTap(m) { onOpen(m) },
                    contentScale = ContentScale.Crop,
                    onSuccess = { st -> val sz = st.painter.intrinsicSize; if (addonDesign && sz.width > 0f && sz.height > 0f) onRatio(sz.width / sz.height) },
                )
            }
        }
    }
}

/** The addon's own poster, shown whole (no cropping, nothing drawn on top), with the title and year under it. */
@Composable
fun AddonPosterCard(m: Meta, ratio: Float, onRatio: (Float) -> Unit = {}, onClick: () -> Unit) {
    val width = when { ratio > 1.2f -> 262.dp; ratio >= 0.9f -> 170.dp; else -> posterWidth() }
    val corner = posterCorner().coerceAtLeast(12.dp)
    val shape = remember(corner) { RoundedCornerShape(corner) }
    Column(Modifier.width(width).titleTap(m, onClick)) {
        // softHalo instead of Modifier.shadow: elevation shadows on every card are re-rendered while rows scroll.
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).softHalo(corner, spread = 8.dp, strength = 0.55f, dy = 4.dp).clip(shape).background(Surface1)) {
            AsyncImage(
                m.poster ?: backdropOf(m), m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                onSuccess = { st -> val sz = st.painter.intrinsicSize; if (sz.width > 0f && sz.height > 0f) onRatio(sz.width / sz.height) },
            )
            Box(Modifier.fillMaxSize().border(1.dp, Brush.verticalGradient(listOf(Color(0x1FFFFFFF), Color(0x05FFFFFF))), shape))
        }
        Text(m.name, Modifier.padding(top = 10.dp, start = 2.dp), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFFEDEFF5),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        m.releaseInfo?.takeIf { it.isNotBlank() }?.let { Text(it.take(4), Modifier.padding(start = 2.dp, top = 2.dp), fontSize = 13.sp, color = Color(0xFF9AA3B5)) }
    }
}

@Composable
fun LandscapeRow(items: List<Meta>, onOpen: (Meta) -> Unit) =
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.type + it.id }) { m -> LandscapeCard(m) { onOpen(m) } }
    }

/** Wide 16:9 card: backdrop artwork with the title logo on top (Hotstar style). */
@Composable
fun LandscapeCard(m: Meta, width: Dp = 236.dp, onClick: () -> Unit) {
    var logoFailed by remember(m.id) { mutableStateOf(false) }
    val hideLabels = isOn(t("hideLabels"))
    Box(Modifier.width(width).aspectRatio(16f / 9f).clip(RoundedCornerShape(posterCorner())).background(Surface1).titleTap(m, onClick)) {
        AsyncImage(hiResArt(backdropOf(m)), m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xCC000000), 0.65f to Color.Transparent)))
        val logo = logoOf(m)
        if (!hideLabels) Box(Modifier.align(Alignment.BottomStart).padding(10.dp).fillMaxWidth(0.55f)) {
            if (logo != null && !logoFailed) {
                AsyncImage(logo, m.name, Modifier.fillMaxWidth().height(42.dp), contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart, onError = { logoFailed = true })
            } else {
                Text(m.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Deck(
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
    val deckBox = remember { DeckCoords() }
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
        val r = front
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
                TrailerVideo(tid, muted, Modifier.fillMaxSize(), fitWhole = true, zoom = 1f,
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
            // Xstream: the card sits in the centre (same size as before); only a thin slice of the cards beside it shows.
            contentPadding = PaddingValues(horizontal = ((cfg.screenWidthDp.dp - cardW) / 2).coerceAtLeast(12.dp)),
            pageSpacing = 8.dp,
            beyondViewportPageCount = 1,
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
            DeckCard(
                d.meta, rank = page % n + 1, catalog = d.catalog, saved = myList.any { it.id == d.meta.id },
                trailerOn = isFront && tid != null, videoOn = isFront && videoOn, muted = muted,
                onMute = { muted = !muted; HeroSound.muted = muted },
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
                        // The side cards shrink a little and darken, so the centre card is clearly the one in focus.
                        val r = abs(page - pager.currentPage - pager.currentPageOffsetFraction).coerceAtMost(1f)
                        val sc = 1f - 0.08f * r
                        scaleX = sc; scaleY = sc
                    }
                    .drawWithContent {
                        drawContent()
                        val r = abs(page - pager.currentPage - pager.currentPageOffsetFraction).coerceAtMost(1f)
                        val dimCr = Palette.corner.dp.toPx()
                        if (r > 0.01f) drawRoundRect(Color.Black, alpha = 0.55f * r, cornerRadius = CornerRadius(dimCr, dimCr))
                    }
                    // Remember where the front card is (relative to the deck box) while a trailer is up.
                    .then(
                        if (isFront && tid != null) Modifier.onGloballyPositioned { c ->
                            val box = deckBox.coords
                            if (box != null && box.isAttached && c.isAttached) front = box.localBoundingBoxOf(c, false)
                        } else Modifier
                    ),
            )
        }
    }
}

/**
 * "Trailer mode on / off": a glass capsule that springs in at the top of the cards with a red glow breathing behind
 * it and a light sweep, then floats away after ~3 s.
 */
@Composable
private fun TrailerModeBanner(on: Boolean, modifier: Modifier = Modifier, onDone: () -> Unit) {
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

/** Hero card trailer sound and on / off, kept while the app is open (across cards and screens). */
object HeroSound { var muted = false; var trailers = true }

/** Layout coordinates of the deck box (not state: only read from layout callbacks). */
private class DeckCoords { var coords: LayoutCoordinates? = null }

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun DeckCard(
    m: Meta, rank: Int, catalog: String, saved: Boolean,
    /** a trailer is up for this card (drawn by Deck under it) / its video is really playing */
    trailerOn: Boolean, videoOn: Boolean, muted: Boolean, onMute: () -> Unit,
    onOpen: () -> Unit, onToggle: () -> Unit, modifier: Modifier, onHold: () -> Unit = {}, onTrailer: () -> Unit = {},
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
    Box(modifier.fillMaxWidth().aspectRatio(0.76f).clip(shape).drawBehind { if (!videoOn) drawRect(Color(0xFF05070C)) }
        .combinedClickable(onClick = onOpen, onLongClick = onHold)) {
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
                GlassButton(Modifier.weight(1f).height(52.dp), onOpen) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(28.dp)); Spacer(Modifier.width(6.dp))
                    Text(t("watchNow"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                GlassButton(Modifier.size(52.dp), onToggle) {
                    Icon(if (saved) Icons.Default.Check else Icons.Default.Add, if (saved) "In Watchlist" else "Add to Watchlist")
                }
            }
        }
    }
}

@Composable
private fun GlassButton(modifier: Modifier, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(modifier.clip(shape).background(Color(0x3DFFFFFF)).border(1.dp, Color(0x26FFFFFF), shape).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, content = content)
}

/** Continue Watching card. style: "card" (TV-style), "wide" (info on the artwork) or "poster" (tall). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ContinueCard(p: Progress, style: String = "card", vm: AppViewModel? = null, onClick: () -> Unit, onRemove: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val long = onLongClick ?: onRemove
    // Corner badge: time left / "Next · S5 E12" / "New episode in 4 days".
    var badge by remember(p.key, p.positionMs) { mutableStateOf(if (p.finished()) null else CwBadge(CwBadge.LEFT, cwTimeLeft(p))) }
    LaunchedEffect(p.key, p.positionMs) { if (vm != null && p.finished()) badge = runCatching { vm.cwBadge(p) }.getOrNull() }
    val badgeCorner: @Composable BoxScope.() -> Unit = { badge?.takeIf { it.kind != CwBadge.NEXT || it.nextId == null }?.let { CwBadgeView(it, Modifier.align(Alignment.TopEnd).padding(8.dp), small = true) } }
    // An episode watched to the end shows the NEXT one: its picture and name, an "UP NEXT" tag, and a tap plays it.
    val upNext = badge?.kind == CwBadge.NEXT && badge?.nextId != null
    val img = if (upNext) badge?.nextImage ?: p.image else p.image
    val ttl = if (upNext) p.title.substringBefore("  S").trim() + " · " + (badge?.nextTitle ?: "") else p.title
    val go: () -> Unit = { if (upNext) pendingDetailPlay.value = badge?.nextId; onClick() }
    val tap = Modifier.combinedClickable(onClick = go, onLongClick = long)
    val frac = if (upNext) 0f else if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f
    val upTag: @Composable BoxScope.() -> Unit = {
        if (upNext) Text("UP NEXT", Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFE50914))
            .padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = Color.White)
    }
    val leftMin = if (p.durationMs > 0) ((p.durationMs - p.positionMs) / 60000).toInt() else 0
    val left = badge?.text ?: if (p.durationMs <= 0) "Resume" else if (leftMin >= 60) "${leftMin / 60}h ${leftMin % 60}m left" else "${maxOf(leftMin, 1)}m left"
    var menu by remember { mutableStateOf(false) }
    val corner = posterCorner()
    val more: @Composable () -> Unit = {
        Box {
            Icon(Icons.Default.MoreVert, "More", Modifier.clip(CircleShape).clickable { if (onLongClick != null) onLongClick() else menu = true }.padding(2.dp).size(22.dp), tint = Color(0xFFB6BDCC))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Remove from Continue Watching") }, onClick = { menu = false; onRemove() })
            }
        }
    }
    val bar: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0x33FFFFFF))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Accent))
        }
    }
    when (style) {
        "wide" -> Box(Modifier.width(300.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(corner)).background(Surface1).then(tap)) {
            AsyncImage(img, ttl, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xE6000000))))
            badgeCorner(); upTag()
            Row(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayCircle, null, Modifier.size(34.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(ttl, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(left, fontSize = 12.sp, color = Color(0xFFC9CEDB))
                }
                more()
            }
            Box(Modifier.align(Alignment.BottomCenter)) { bar() }
        }
        "poster" -> Column(Modifier.width(posterWidth())) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(corner)).background(Surface1).then(tap)) {
                AsyncImage(img, ttl, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Icon(Icons.Default.PlayCircle, null, Modifier.align(Alignment.Center).size(40.dp))
                badgeCorner(); upTag()
                Box(Modifier.align(Alignment.BottomCenter)) { bar() }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(left, Modifier.weight(1f), fontSize = 12.sp, color = Color(0xFF8A94A8), maxLines = 1)
                more()
            }
        }
        else -> Column(Modifier.width(236.dp)) {
            Column(Modifier.clip(RoundedCornerShape(corner)).then(tap)) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Surface1)) {
                    AsyncImage(img, ttl, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0x99000000))))
                    Icon(Icons.Default.PlayArrow, null, Modifier.align(Alignment.BottomStart).padding(8.dp).size(30.dp))
                    badgeCorner(); upTag()
                }
                bar()
            }
            Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(ttl, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(left, fontSize = 13.sp, color = Color(0xFF8A94A8))
                }
                more()
            }
        }
    }
}

/** Hotstar-style capsule (centred) with the AI voice button on the right. */
@Composable
private fun FloatingFilters(
    vm: AppViewModel,
    hide: State<Float>,
    filter: String?,
    labels: List<Pair<String, String>>,
    onFilter: (String) -> Unit,
    onTop: () -> Unit,
    onAi: () -> Unit,
) {
    val pill = RoundedCornerShape(50)
    val maxCapsule = (LocalConfiguration.current.screenWidthDp - 2 * (12 + 52 + 8)).dp
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        // Xstream: no TV / Movies / Live capsule — only the AI voice button.
        Box(Modifier.align(Alignment.CenterEnd).tourTarget("ai")) { AiOrbButton(vm, 52.dp, hide, onAi) }
        // AIDEN wakes up when the app opens: slides out of the button, says hello, and goes back to sleep.
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 56.dp).offset(y = (-58).dp)) { AidenWake(vm, onAi) }
    }
}


// ───────────── "Top 10" ranked rows ─────────────

/** Rows named like "Top 10…", "Top X…", "Trending Top…" get big rank numbers (Netflix / Nuvio style). */
fun isRankedRow(title: String) = Regex("\\btop\\b|\\btrending\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)

private val CountryFlags = linkedMapOf(
    "india" to "🇮🇳", "usa" to "🇺🇸", "united states" to "🇺🇸", "us" to "🇺🇸", "uk" to "🇬🇧", "united kingdom" to "🇬🇧", "korea" to "🇰🇷",
    "japan" to "🇯🇵", "spain" to "🇪🇸", "france" to "🇫🇷", "germany" to "🇩🇪", "canada" to "🇨🇦", "australia" to "🇦🇺", "brazil" to "🇧🇷", "mexico" to "🇲🇽",
)
private val ServiceTags = linkedMapOf(
    "netflix" to ("NETFLIX" to Color(0xFFE50914)), "prime" to ("PRIME" to Color(0xFF00A8E1)), "amazon" to ("PRIME" to Color(0xFF00A8E1)),
    "disney" to ("DISNEY+" to Color(0xFF3A8DFF)), "hotstar" to ("HOTSTAR" to Color(0xFF3A8DFF)), "hbo" to ("HBO" to Color(0xFF9B5CFF)),
    "apple" to ("APPLE TV+" to Color(0xFFDDDDDD)), "zee5" to ("ZEE5" to Color(0xFF8E44FF)), "sony" to ("SONYLIV" to Color(0xFFF5A20A)),
    "jio" to ("JIO" to Color(0xFFE5197A)), "hulu" to ("HULU" to Color(0xFF1CE783)), "paramount" to ("PARAMOUNT+" to Color(0xFF0064FF)),
)

@Composable
fun RankedRow(title: String, items: List<Meta>, onOpen: (Meta) -> Unit) {
    val lower = title.lowercase()
    fun has(word: String) = Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(lower)
    val service = ServiceTags.entries.firstOrNull { lower.contains(it.key) }?.value
    val flag = if (service == null) CountryFlags.entries.firstOrNull { has(it.key) }?.value else null
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(items, key = { _, m -> m.type + m.id }) { i, m -> RankedCard(i + 1, m, flag, service) { onOpen(m) } }
    }
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
private fun RankedCard(rank: Int, m: Meta, flag: String?, service: Pair<String, Color>?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(posterCorner().coerceAtLeast(14.dp))
    Column(Modifier.width(262.dp).clip(RoundedCornerShape(16.dp)).titleTap(m, onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(Surface1)) {
            AsyncImage(backdropOf(m), m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // Dark fade on the left so the number and info stay readable.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF0000000), 0.32f to Color(0x99000000), 0.55f to Color.Transparent)))
            if (rank <= 10) Column(Modifier.align(Alignment.CenterStart).padding(start = 10.dp).width(78.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Same numbers as Trending: white → grey fill, black outline, soft shadow.
                Box {
                    val st = androidx.compose.ui.text.TextStyle(fontSize = if (rank >= 10) 68.sp else 92.sp, fontWeight = FontWeight.Black, lineHeight = 92.sp, letterSpacing = (-4).sp)
                    Text("$rank", style = st.merge(androidx.compose.ui.text.TextStyle(color = Color(0xFF05060A),
                        drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 9f, join = androidx.compose.ui.graphics.StrokeJoin.Round))), softWrap = false)
                    Text("$rank", style = st.merge(androidx.compose.ui.text.TextStyle(
                        brush = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFD5D9E2), Color(0xFF9AA1B0))),
                        shadow = androidx.compose.ui.graphics.Shadow(Color(0xCC000000), androidx.compose.ui.geometry.Offset(0f, 5f), 14f))), softWrap = false)
                }
                m.genres?.firstOrNull()?.let {
                    Text(it.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = Color(0xFFDDE1EA), maxLines = 1)
                }
                m.imdbRating?.takeIf { it.isNotBlank() }?.let {
                    Text("★ $it", Modifier.padding(top = 2.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                when {
                    service != null -> Text(service.first, Modifier.padding(top = 4.dp), fontSize = 9.sp, fontWeight = FontWeight.Black, color = service.second, letterSpacing = 0.6.sp, maxLines = 1)
                    flag != null -> Text(flag, Modifier.padding(top = 2.dp), fontSize = 16.sp)
                }
            }
        }
        Text(m.name, Modifier.padding(top = 10.dp, start = 2.dp), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        m.releaseInfo?.takeIf { it.isNotBlank() }?.let { Text(it.take(4), Modifier.padding(start = 2.dp, top = 2.dp), fontSize = 13.sp, color = Color(0xFF9AA3B5)) }
    }
}


/** Copies a small piece of the screen and says whether it is (almost) black. */
private suspend fun cardLooksBlack(window: android.view.Window, rect: android.graphics.Rect): Boolean =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        if (android.os.Build.VERSION.SDK_INT < 26) { cont.resumeWith(Result.success(false)); return@suspendCancellableCoroutine }
        val bmp = android.graphics.Bitmap.createBitmap(24, 16, android.graphics.Bitmap.Config.ARGB_8888)
        runCatching {
            android.view.PixelCopy.request(window, rect, bmp, { res ->
                var max = 0
                if (res == android.view.PixelCopy.SUCCESS) for (x in 0 until bmp.width) for (y in 0 until bmp.height) {
                    val c = bmp.getPixel(x, y)
                    val l = maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
                    if (l > max) max = l
                }
                if (cont.isActive) cont.resumeWith(Result.success(res == android.view.PixelCopy.SUCCESS && max < 22))
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }.onFailure { if (cont.isActive) cont.resumeWith(Result.success(false)) }
    }


/** Big cards (Picked for you): the sharp version of a poster — Cinemeta "large", TMDB w780 — instead of the small one. */
/** A sharp tall poster for an IMDb title (the same art Cinemeta uses, large size). */
fun hdPoster(m: Meta): String? = m.id.takeIf { it.startsWith("tt") }?.substringBefore(":")?.let { "https://images.metahub.space/poster/large/$it/img" }

fun hiResArt(url: String?): String? = url
    ?.replace(Regex("/poster/(small|medium)/"), "/poster/large/")
    ?.replace(Regex("/background/(small|medium)/"), "/background/large/")
    ?.replace(Regex("/t/p/w(92|154|185|300|342|500)/"), "/t/p/w780/")
    // IMDb / Amazon images come resized to ~300 px: ask for a 1000 px copy.
    ?.replace(Regex("""\._V1_[^/]*?\.(jpg|jpeg|png)$"""), "._V1_QL90_UX1000_.$1")
    ?.replace("assets.fanart.tv/preview/", "assets.fanart.tv/fanart/")


/** Xstream: Continue Watching opened up: every title you started, and a button to clear the row. */
@Composable
private fun ContinueAllPage(vm: AppViewModel, items: List<Progress>, onClose: () -> Unit, onPlay: (Progress) -> Unit) {
    var ask by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.IconButton(onClose) { Icon(Icons.Default.ArrowBack, "Back") }
                Text("Continue Watching", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (items.isNotEmpty()) androidx.compose.material3.IconButton({ ask = true }) { Icon(Icons.Default.DeleteSweep, "Clear Continue Watching", tint = Color(0xFFFF8A80)) }
            }
            if (items.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Nothing here yet", color = Color(0xFF9AA3B5)) }
            else androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                androidx.compose.foundation.lazy.grid.GridCells.Fixed(2), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(items.size, key = { items[it].key }) { i ->
                    val p = items[i]
                    ContinueCard(p, "wide", vm = vm, onClick = { onPlay(p) }, onRemove = { vm.removeProgress(p.key) })
                }
            }
        }
        if (ask) PremiumAlert(
            onDismissRequest = { ask = false },
            title = { Text("Clear Continue Watching?") },
            text = { Text("All ${items.size} titles leave the row on your phones and TV. What you watched still counts in My Space.") },
            confirmButton = { androidx.compose.material3.TextButton({ ask = false; vm.clearContinue(); onClose() }) { Text("Clear", color = Color(0xFFFF8A80)) } },
            dismissButton = { androidx.compose.material3.TextButton({ ask = false }) { Text("Cancel") } },
        )
    }
}


/** A poster drawn by the addon (rating / rank posters from a configured addon), not a plain artwork file. */
fun isDesignedPoster(url: String?): Boolean {
    val u = url?.lowercase() ?: return false
    if (!u.startsWith("http")) return false
    val plain = listOf("image.tmdb.org", "images.metahub.space", "live.metahub.space", "m.media-amazon.com", "artworks.thetvdb.com", "assets.fanart.tv", "img.youtube.com")
    return plain.none { it in u }
}
