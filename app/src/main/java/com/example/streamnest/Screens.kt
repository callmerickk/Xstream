package com.example.streamnest

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.CompositingStrategy
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

sealed interface Screen {
    /** [sourcesFor] = open the sources page for this video id straight away ("Play manually"). */
    data class Detail(val meta: Meta, val sourcesFor: String? = null) : Screen
    data class Player(
        val title: String, val url: String, val subs: List<Subtitle>, val key: String = "", val image: String? = null, val resumeMs: Long = 0L,
        val type: String = "", val meta: Meta? = null, val source: String? = null,
        /** addon that gave this link */
        val addon: String? = null,
        /** other streams to fall back to if this one fails before it starts */
        val candidates: List<StreamItem> = emptyList(),
        /** true = no link yet; the player finds one itself ("Finding stream source") using Auto Stream Selection */
        val find: Boolean = false,
        /** find from the Quick Start addons (quality selector + language versions) */
        val quick: Boolean = false,
        /** the title page already saved the start of [url]: play straight from storage, no loading screen */
        val instant: Boolean = false,
    ) : Screen
    data class Settings(val start: String = "root") : Screen
    data object Live : Screen
    /** Actor page (TMDB person id). */
    data class Person(val personId: String, val name: String, val photo: String?) : Screen
    /** Full-screen trailer player: YouTube [videos] keys with their [titles], starting at [start]. */
    data class Trailer(val videos: List<String>, val titles: List<String>, val start: Int, val title: String, val meta: Meta?) : Screen
    /** A Nuvio-style collection, opened on one of its folders. */
    data class Collection(val collectionId: String, val folderId: String) : Screen
    /** Watch together page (optionally straight into a chat or a shared title). */
    data class Together(val chat: String? = null, val item: String? = null) : Screen
    data object Library : Screen
    data class Watchlist(val calendar: Boolean = false) : Screen
    data object Notes : Screen
}

@Composable
fun App(vm: AppViewModel = viewModel()) {
    val stack = remember { mutableStateListOf<Screen>() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var voiceOpen by rememberSaveable { mutableStateOf(false) }
    val pop = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex); Unit }
    val profile by vm.current.collectAsStateWithLifecycle()
    val texts by vm.texts.collectAsStateWithLifecycle()
    // Activity log: which screen is open.
    LaunchedEffect(stack.lastOrNull(), tab, profile?.id) {
        val top = stack.lastOrNull()
        ActLog.screen = if (profile == null) "Who's watching" else top?.let { it::class.simpleName } ?: listOf("Home", "Search", "My Space", "Profile", "Live").getOrElse(tab) { "Tab $tab" }
        ActLog.add("SCREEN", ActLog.screen + (top?.let { " · " + it.toString().take(80) } ?: ""))
    }

    // Hotstar-style: scrolling down hides the filter pill and shrinks the bar, scrolling up restores both.
    // The state only flips after a clear scroll in one direction, and the animation runs in the draw phase
    // (graphicsLayer), so scrolling stays smooth.
    // [scrolledDown] is NEVER read during composition: it is only observed by the two coroutines below, which
    // drive Animatables. So flipping it (each time the scroll direction changes) recomposes nothing — before, it
    // recomposed all of App() (and re-ran animateFloatAsState) right in the middle of a scroll.
    var scrolledDown by remember { mutableStateOf(false) }
    val navStyle = texts["navStyle"] ?: "adaptive"
    val capsuleAnimatable = remember { Animatable(0f) }
    val barAnimatable = remember { Animatable(0f) }
    LaunchedEffect(navStyle) {
        launch { capsuleAnimatable.follow { if (navStyle != "expanded" && scrolledDown) 1f else 0f } }
        launch {
            barAnimatable.follow {
                val compact = when (navStyle) { "expanded" -> false; "compact" -> true; else -> scrolledDown }
                if (compact) 1f else 0f
            }
        }
    }
    val capsuleAnim: State<Float> = remember(capsuleAnimatable) { capsuleAnimatable.asState() }
    val barAnim: State<Float> = remember(barAnimatable) { barAnimatable.asState() }
    val scrollWatcher = remember {
        object : NestedScrollConnection {
            var acc = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                acc = if ((acc < 0f && dy > 0f) || (acc > 0f && dy < 0f)) dy else acc + dy
                if (acc < -60f && !scrolledDown) { scrolledDown = true; acc = 0f }
                else if (acc > 40f && scrolledDown) { scrolledDown = false; acc = 0f }
                return Offset.Zero
            }
        }
    }
    val homeList = rememberLazyListState()
    val scope = rememberCoroutineScope()

    BackHandler(stack.isNotEmpty() || (profile != null && tab != 0)) { if (stack.isNotEmpty()) pop() else tab = 0 }

    // Startup animation: once per app start (Help & Settings > Advanced > Startup animation).
    var voteFor by remember { mutableStateOf<Meta?>(null) }
    var showSplash by remember { mutableStateOf(!StartupState.splashShown && isOn(texts["splash"] ?: "yes")) }
    // First start: "Sign in to Xstream" (or Skip) before "Who's watching?".
    var gateOpen by rememberSaveable { mutableStateOf(!Cloud.decided.value) }

    // Xstream: right after signing in, wait for the account (profiles, pictures, addons) before "Who's watching?".
    val restoringCloud by Cloud.restoring.collectAsState()
    val restoredN by Cloud.restoredProfiles.collectAsState()
    var emptyAccount by remember { mutableStateOf(false) }
    var sawRestore by remember { mutableStateOf(false) }
    LaunchedEffect(restoringCloud, restoredN) {
        if (restoringCloud) sawRestore = true
        else if (sawRestore) { sawRestore = false; emptyAccount = restoredN == 0 }
    }
    CompositionLocalProvider(LocalTexts provides texts) {
        LaunchedEffect(profile == null) { if (profile == null) stack.clear() }
        val open: (Meta) -> Unit = { stack.add(Screen.Detail(it)) }
        if (profile == null && gateOpen) CloudGate(onDone = { gateOpen = false })
        else if (profile == null && (restoringCloud || emptyAccount)) RestoreScreen(restoringCloud, onDifferent = { Cloud.signOut(); Cloud.restoring.value = false; emptyAccount = false; gateOpen = true }) { Cloud.restoring.value = false; emptyAccount = false }
        else if (profile == null && texts["streamMode"] == null) ModeChooser(vm) {}
        else if (profile == null) WhoIsWatchingScreen(vm, active = !showSplash) { tab = 0; scrolledDown = false }
        else when (val top = stack.lastOrNull()) {
            is Screen.Player -> key(top) {
                PlayerScreen(vm, top, onBack = pop, onReplace = { next -> if (stack.isNotEmpty()) stack[stack.lastIndex] = next }, onProgress = { pos, dur, url, subs ->
                    if (top.key.isNotEmpty() && url.isNotEmpty()) vm.saveProgress(Progress(top.key, top.title, top.image, url, subs, pos, dur, System.currentTimeMillis()))
                    // "How was it?" once you've really watched something (10 min or 30%).
                    val base = top.key.substringBefore(":")
                    val prev = vm.votes.value[base]
                    val watchedEnough = pos >= 10 * 60_000L || (dur > 0 && pos >= dur * 0.3)
                    val askAgain = prev == null || (prev.v == 0 && System.currentTimeMillis() - prev.at > 3L * 24 * 3600_000)
                    if (base.isNotEmpty() && watchedEnough && askAgain && isOn(texts["askVote"] ?: "yes")) {
                        voteFor = top.meta?.copy(id = base) ?: Meta(id = base, type = top.type.ifBlank { if (top.key.contains(":")) "series" else "movie" },
                            name = top.title.substringBefore("  S"), poster = top.image)
                    }
                })
            }
            is Screen.Detail -> key(top) {
                DetailScreen(vm, top.meta, onBack = pop, onOpen = open, sourcesFor = top.sourcesFor, onPlay = { stack.add(it) },
                    onPerson = { c -> stack.add(Screen.Person(c.id, c.name, c.photo)) },
                    onTrailer = { stack.add(it) })
            }
            is Screen.Person -> key(top) { PersonScreen(vm, top, onBack = pop, onOpen = open) }
            is Screen.Trailer -> key(top) {
                // "Play the movie": back to the title page, which then presses Play by itself.
                TrailerScreen(top, onBack = pop, onPlayMovie = { pendingDetailPlay.value = top.meta?.id; pop() })
            }
            is Screen.Settings -> key(top) { Box(Modifier.fillMaxSize().background(Bg)) { SettingsHost(vm, top.start, onClose = pop) } }
            Screen.Live -> Box(Modifier.fillMaxSize().background(Bg)) { LiveTvScreen(vm, onBack = pop) { stack.add(it) } }
            is Screen.Collection -> key(top) {
                Box(Modifier.fillMaxSize().background(Bg)) { CollectionScreen(vm, top.collectionId, top.folderId, onBack = pop, onOpen = open) }
            }
            is Screen.Together -> key(top) {
                WatchTogetherPage(vm, onBack = pop, onOpen = open, startChat = top.chat, startItem = top.item, onPlayRoom = { st ->
                    val expected = st.pos + if (st.playing) (System.currentTimeMillis() - st.receivedAt + 300) else 0L
                    stack.add(Screen.Player(st.title, st.url, emptyList(), key = st.key, image = st.image, resumeMs = expected, type = st.type))
                })
            }
            Screen.Library -> LibraryPage(vm, onBack = pop, onOpen = open, onPlay = { stack.add(it) })
            is Screen.Watchlist -> key(top) { WatchlistPage(vm, onBack = pop, onOpen = open, startCalendar = top.calendar) }
            Screen.Notes -> NotificationsPage(onBack = pop, onTogether = { stack.add(Screen.Together()) })
            null -> Box(Modifier.fillMaxSize().background(Bg).nestedScroll(scrollWatcher)) {
                when (tab) {
                    0 -> HomeScreen(
                        vm, homeList, capsuleAnim, open, { stack.add(it) },
                        onLive = { stack.add(Screen.Live) },
                        onAi = { voiceOpen = true },
                        onManual = { m, k -> stack.add(Screen.Detail(m, k)) },
                        onOpenCollection = { c, f -> stack.add(Screen.Collection(c.id, f.id)) },
                    )
                    1 -> SearchScreen(vm, "", open)
                    3 -> LibraryPage(vm, onBack = null, onOpen = open, onPlay = { stack.add(it) })
                    else -> MySpaceScreen(
                        vm,
                        onSettings = { stack.add(Screen.Settings()) },
                        onProfile = { stack.add(Screen.Settings("profile")) },
                        onSwitchProfile = { vm.leaveProfile() },
                        onOpen = open,
                        onPlay = { stack.add(it) },
                        onManual = { m, k -> stack.add(Screen.Detail(m, k)) },
                        onLibrary = { stack.add(Screen.Library) },
                        onTogether = { stack.add(Screen.Together()) },
                        onWatchlist = { stack.add(Screen.Watchlist()) },
                        onNotifications = { stack.add(Screen.Notes) },
                    )
                }
                BottomNav(
                    tab, vm, compact = barAnim,
                    onTab = {
                        scrolledDown = false
                        if (it == 0 && tab == 0) { scope.launch { homeList.animateScrollToItem(0) }; vm.refresh() } else tab = it
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).tourTarget("nav"),
                )
                if (voiceOpen) VoiceAssistant(vm, onClose = { voiceOpen = false }) { voiceOpen = false; open(it) }
                // Voice search handed a request to the AI.
                val voiceAsk by VoiceAskBus.pending.collectAsState()
                voiceAsk?.let { said -> key(said) { VoiceAssistant(vm, onClose = { VoiceAskBus.pending.value = null }, onOpen = { VoiceAskBus.pending.value = null; open(it) }, firstAsk = said) } }
            }
        }
        if (profile != null && stack.lastOrNull() !is Screen.Player && stack.lastOrNull() !is Screen.Trailer) voteFor?.let { m ->
            key(m.id) { VoteCapsule(m, onVote = { v -> vm.vote(m, v) }, onDismiss = { voteFor = null }) }
        }
        // Watch together invite (🔔 notification or a link): "Who's watching?" first, then join the room and open its video.
        val joinAsk by JoinIntent.code.collectAsState()
        LaunchedEffect(joinAsk, profile) {
            val c = joinAsk ?: return@LaunchedEffect
            if (!JoinIntent.asked) {
                JoinIntent.asked = true
                StartupState.autoPickDone = true
                if (profile != null) { vm.leaveProfile(); return@LaunchedEffect }
            }
            if (profile == null) return@LaunchedEffect
            // Joined in its own job: clearing the code restarts this effect, which used to cancel the join halfway.
            JoinIntent.code.value = null
            scope.launch {
                Snack.show("Joining the watch party…")
                // Right after a cold start the account can still be loading: wait for it briefly.
                var waited = 0
                while (Cloud.user.value == null && waited < 10_000) { delay(250); waited += 250 }
                var res: Pair<RoomState?, String?> = null to null
                for (attempt in 0 until 3) {
                    res = Together.join(c)
                    if (res.first != null || res.second?.startsWith("No room") == true) break
                    delay(1200)
                }
                val st = res.first
                ActLog.add("TOGETHER", if (st != null) "invite: joined $c" else "invite: $c failed (${res.second})")
                if (st == null) { Snack.show(res.second ?: "Couldn't join the room"); return@launch }
                val expected = st.pos + if (st.playing) (System.currentTimeMillis() - st.receivedAt + 300) else 0L
                stack.removeAll { it is Screen.Player }
                stack.add(Screen.Player(st.title, st.url, emptyList(), key = st.key, image = st.image, resumeMs = expected, type = st.type))
            }
        }
        if (profile == null && joinAsk != null) InviteBanner()
        // Watch together: your name card and lists, and what a notification asked to open (a chat, a plan).
        LaunchedEffect(profile?.id) {
            if (profile == null || BuildConfig.IS_TV) return@LaunchedEffect
            Together.myName = profile?.name ?: ""
            Social.publishCard(); runCatching { Social.refresh() }
        }
        val socialChat by SocialIntent.chat.collectAsState()
        val socialPlan by SocialIntent.plan.collectAsState()
        val socialOpen by SocialIntent.open.collectAsState()
        LaunchedEffect(socialChat, socialPlan, socialOpen, profile?.id) {
            if (profile == null || BuildConfig.IS_TV || (!socialOpen && socialChat == null && socialPlan == null)) return@LaunchedEffect
            val c = socialChat; val pl = socialPlan
            SocialIntent.chat.value = null; SocialIntent.plan.value = null; SocialIntent.open.value = false
            // A plan reminder without a room yet: you start it (opens the title and plays; the room starts by itself).
            if (pl != null) {
                val it0 = Social.item(pl.first)
                val p = it0?.plans?.firstOrNull { it.id == pl.second }
                if (it0 != null && p != null && p.room == null && p.at - System.currentTimeMillis() < 3 * 3600_000L) {
                    Social.watchNow(it0, p); pendingDetailPlay.value = it0.meta.id; stack.add(Screen.Detail(it0.meta)); return@LaunchedEffect
                }
            }
            stack.add(Screen.Together(chat = c, item = pl?.first))
        }
        // Home screen widget: open the title and press Play (it resumes where you stopped).
        val widgetAsk = WidgetOpen.pending.value
        LaunchedEffect(widgetAsk, profile) {
            val (k, title) = widgetAsk ?: return@LaunchedEffect
            if (profile == null) return@LaunchedEffect
            WidgetOpen.pending.value = null
            val base = k.substringBefore(":")
            val series = k.contains(":")
            stack.clear()
            stack.add(Screen.Detail(Meta(id = base, type = if (series) "series" else "movie", name = title.substringBefore("  S"))))
            pendingDetailPlay.value = base
        }
        // First-time visual tour, one screen at a time (after the short start guide).
        if (profile != null && !texts["guideDone"].isNullOrEmpty() && !showSplash && !BuildConfig.IS_TV) {
            val topScreen = stack.lastOrNull()
            when {
                topScreen == null && tab == 0 -> TourOverlay("home", HomeTour)
                topScreen == null && tab == 2 -> TourOverlay("myspace", MySpaceTour)
                topScreen is Screen.Detail -> TourOverlay("detail", DetailTour)
                topScreen is Screen.Player -> TourOverlay("player", PlayerTour)
            }
        }
        if (profile != null && texts["guideDone"].isNullOrEmpty()) QuickGuide { vm.setText("guideDone", "1") }
        // Built-in servers (CNCVerse / HdHub links published from Developer's Corner) and the Connect PenguPlay card.
        LaunchedEffect(profile?.id) { if (profile != null) { runCatching { BuiltIns.sync(vm) }; runCatching { AppLook.fetch(vm.appContext) } } }
        if (profile != null && stack.isEmpty() && !showSplash && !texts["guideDone"].isNullOrEmpty()) PenguConnectPrompt(vm)
        val askWl by WatchlistAsk.pending.collectAsState()
        askWl?.let { m -> AddToWatchlistSheet(vm, m) { WatchlistAsk.pending.value = null } }
        if (showSplash) StartupSplash { showSplash = false }
        SnackHost()
    }
}

/**
 * Animates towards [target] whenever it changes. [target] is read inside snapshotFlow (not in composition), so the
 * state it reads can flip as often as it likes without recomposing anything. The first value is applied instantly.
 */
private suspend fun Animatable<Float, AnimationVector1D>.follow(target: () -> Float) {
    var first = true
    snapshotFlow(target).distinctUntilChanged().collectLatest { v ->
        // Xstream: hiding shrinks away quickly; coming back pops in with a little bounce, like a bubble.
        if (first) { first = false; snapTo(v) }
        else if (v < value) animateTo(v, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 360f))
        else animateTo(v, tween(240, easing = androidx.compose.animation.core.FastOutLinearInEasing))
    }
}

/**
 * Xstream dock: a floating capsule with a red horizon glow along its top edge. Scrolling down the page it shrinks away
 * like a bubble; scrolling back up it pops in again. Long-press your picture and, without lifting, slide onto another
 * profile (it ripples like water); lift to switch.
 */
@Composable
fun BottomNav(tab: Int, vm: AppViewModel, compact: State<Float>, onTab: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    var bubble by remember { mutableStateOf(false) }
    var hover by remember { mutableIntStateOf(-1) }
    val slots = remember { mutableStateMapOf<Int, androidx.compose.ui.geometry.Rect>() }
    val meOrigin = remember { arrayOf(Offset.Zero) }
    val red = Accent
    Box(
        modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 14.dp).graphicsLayer {
            val v = compact.value
            translationY = v * 96.dp.toPx(); val sc = 1f - 0.6f * v.coerceIn(0f, 1f); scaleX = sc; scaleY = sc
            alpha = (1f - v * 1.3f).coerceIn(0f, 1f)
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
        },
        Alignment.Center,
    ) {
        val shape = RoundedCornerShape(50)
        Row(
            Modifier.width(300.dp).height(64.dp).shadow(22.dp, shape)
                .drawBehind {
                    // The glow above the edge (strongest on the left, like sunrise on StreamX's bar).
                    drawOval(Brush.radialGradient(listOf(red.copy(alpha = 0.45f), Color.Transparent), center = Offset(size.width * 0.3f, 0f), radius = size.width * 0.45f),
                        topLeft = Offset(-size.width * 0.1f, -size.height * 0.55f), size = androidx.compose.ui.geometry.Size(size.width * 0.85f, size.height * 1.1f))
                }
                .clip(shape).background(Brush.verticalGradient(listOf(Color(0xF71A1A22), Color(0xFA0D0E13))))
                .border(1.5.dp, Brush.horizontalGradient(listOf(Color(0xFFFF6A3D), red, red.copy(alpha = 0.35f), Color(0x26FFFFFF))), shape)
                .padding(horizontal = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            DockItem(tab == 0, t("navHome"), { onTab(0) }) { c -> Icon(Icons.Default.Home, t("navHome"), Modifier.size(24.dp), tint = c) }
            DockItem(tab == 1, t("navSearch"), { onTab(1) }) { c -> Icon(Icons.Default.Search, t("navSearch"), Modifier.size(24.dp), tint = c) }
            DockItem(tab == 3, "Library", { onTab(3) }) { c -> Icon(Icons.Default.VideoLibrary, "Library", Modifier.size(24.dp), tint = c) }
            val fill by androidx.compose.animation.animateColorAsState(if (tab == 2) Color(0xFFF2F4F8) else Color.Transparent, label = "me")
            val meLabel = t("navMySpace")
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(fill)
                    .onGloballyPositioned { meOrigin[0] = it.boundsInRoot().topLeft }
                    .pointerInput(profiles) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val long = awaitLongPressOrCancellation(down.id)
                            if (long == null) { onTab(2); return@awaitEachGesture }
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            bubble = true; hover = -1
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                val at = meOrigin[0] + ch.position
                                val h = slots.entries.firstOrNull { (_, r) -> r.inflate(18f).contains(at) }?.key ?: -1
                                if (h != hover) { hover = h; if (h >= 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                ch.consume()
                                if (!ch.pressed) break
                            }
                            val pick = profiles.getOrNull(hover)
                            bubble = false; hover = -1
                            if (pick != null && pick.id != current?.id) ProfileLock.ask(pick) { vm.selectProfile(pick); onTab(2) }
                        }
                    }
                    .semantics { contentDescription = meLabel },
                Alignment.Center,
            ) { Avatar(vm, 30.dp) }
        }
        if (bubble) Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp).offset(y = (-80).dp)
                .shadow(18.dp, RoundedCornerShape(40.dp)).clip(RoundedCornerShape(40.dp)).background(Color(0xF216181F))
                .border(1.dp, Color(0x262C3240), RoundedCornerShape(40.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End,
        ) {
            profiles.forEachIndexed { i, pr ->
                val on = hover == i
                val grow by animateFloatAsState(if (on) 1.18f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 500f), label = "grow")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(pr.name + if (pr.id == current?.id) " · you" else "", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (on) Color.White else Color(0xFFB6BDCC))
                    Box(Modifier.size(52.dp).onGloballyPositioned { slots[i] = it.boundsInRoot() }, Alignment.Center) {
                        if (on) WaterRipple(Modifier.matchParentSize())
                        ProfileAvatar(vm, pr, 46.dp, Modifier.graphicsLayer { scaleX = grow; scaleY = grow }, animate = false)
                    }
                }
            }
        }
    }
}

/** Three soft rings spreading out like water, under a hovered profile. */
@Composable
private fun WaterRipple(modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "water")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "w")
    Spacer(modifier.drawBehind {
        for (k in 0..2) {
            val p = (t + k / 3f) % 1f
            drawCircle(Color(0xFF9FD8FF), radius = size.minDimension / 2f * (0.8f + 0.7f * p), alpha = 0.7f * (1f - p), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    })
}

@Composable
private fun DockItem(selected: Boolean, label: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, icon: @Composable (Color) -> Unit) {
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val hasLong = onLongClick != null
    val fill by androidx.compose.animation.animateColorAsState(if (selected) Color(0xFFF2F4F8) else Color.Transparent, label = "dock")
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(fill)
            .pointerInput(hasLong) { detectTapGestures(onTap = { click() }, onLongPress = if (hasLong) { _: Offset -> longClick?.invoke() } else null) }
            .semantics { contentDescription = label },
        Alignment.Center,
    ) { icon(if (selected) Color(0xFF0E1014) else Color(0xFF8A90A0)) }
}

/** Instagram-style quick profile switcher: a small capsule of avatars above the My Space button. */
@Composable
private fun ProfileSwitcher(vm: AppViewModel, onDismiss: () -> Unit, onPicked: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val above = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = (anchorBounds.right - popupContentSize.width).coerceIn(16, maxOf(16, windowSize.width - popupContentSize.width - 16))
                val y = (anchorBounds.top - popupContentSize.height - 24).coerceAtLeast(0)
                return IntOffset(x, y)
            }
        }
    }
    Popup(popupPositionProvider = above, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val shape = RoundedCornerShape(40.dp)
        val pop = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(Unit) { pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 420f)) }
        Row(
            Modifier.graphicsLayer {
                val v = pop.value
                alpha = v.coerceIn(0f, 1f); scaleX = 0.88f + 0.12f * v; scaleY = scaleX
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.9f, 1f)
            }.shadow(18.dp, shape).clip(shape).background(Color(0xF21A1820)).border(1.dp, Color(0x26FFFFFF), shape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            profiles.forEach { pr ->
                val on = pr.id == current?.id
                Column(
                    Modifier.width(62.dp).clip(RoundedCornerShape(16.dp)).clickable { if (!on) ProfileLock.ask(pr) { vm.selectProfile(pr); onPicked() } else onPicked() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ProfileAvatar(vm, pr, 48.dp, if (on) Modifier.border(2.dp, Accent, CircleShape) else Modifier, animate = false)
                    Text(pr.name, Modifier.padding(top = 4.dp), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) Color.White else Color(0xFFB6BDCC))
                }
            }
        }
    }
}

@Composable
fun Poster(m: Meta, modifier: Modifier = Modifier, onClick: (Meta) -> Unit) {
    AsyncImage(
        model = m.poster, contentDescription = m.name, contentScale = ContentScale.Crop,
        modifier = modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(posterCorner())).background(Surface1).clickable { onClick(m) },
    )
}

// ───────────── Search ─────────────
@Composable
fun SearchScreen(vm: AppViewModel, initial: String, onOpen: (Meta) -> Unit) {
    PremiumSearch(vm, initial, onOpen)
}


/** Xstream: "Bringing everything over from your account" after signing in, and a clear note if the account is empty. */
@Composable
private fun RestoreScreen(busy: Boolean, onDifferent: () -> Unit, onContinue: () -> Unit) {
    val status by Cloud.status.collectAsState()
    val user by Cloud.user.collectAsState()
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(busy) { slow = false; if (busy) { kotlinx.coroutines.delay(20_000); slow = true } }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B10)).statusBarsPadding().navigationBarsPadding().padding(28.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(AppLook.logoRes(AppLook.logo.value)), null, Modifier.size(40.dp))
                Spacer(Modifier.width(10.dp)); Wordmark("Xstream", 26.sp)
            }
            if (busy) {
                Text("Bringing your profiles, pictures and settings from your account", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                androidx.compose.material3.CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                Text(status.ifBlank { "Connecting…" }, fontSize = 13.sp, color = Color(0xFF9AA3B5))
                if (slow) {
                    Text("This is taking longer than usual. Signed in as ${user?.email.orEmpty()}.", fontSize = 13.sp, color = Color(0xFFFFC27A),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    androidx.compose.material3.TextButton(onContinue) { Text("Continue without waiting") }
                    androidx.compose.material3.TextButton(onDifferent) { Text("Use a different account") }
                }
            } else {
                Text("This account has no profiles saved yet", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("You're signed in as ${user?.email.orEmpty()}. If StreamX uses a different email, sign in with that one to bring your profiles over.",
                    fontSize = 13.sp, color = Color(0xFF9AA3B5), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                androidx.compose.material3.TextButton(onDifferent) { Text("Use a different account") }
                androidx.compose.material3.TextButton(onContinue) { Text("Continue with this account") }
            }
        }
    }
}
