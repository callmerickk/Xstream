package com.example.streamnest

import kotlinx.coroutines.flow.first
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
        /** picked by hand (Play manually): never swapped for another stream */
        val manual: Boolean = false,
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
    // Xstream: the dock also hides after 4 s without a touch; any touch brings it back.
    // (A plain timestamp, not state: touches never recompose anything; only the hide / show flip is observed.)
    var idleHidden by remember { mutableStateOf(false) }
    val lastTouch = remember { longArrayOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(250)
            val idle = System.currentTimeMillis() - lastTouch[0] > 4_000
            if (idle != idleHidden) idleHidden = idle
        }
    }
    val navStyle = texts["navStyle"] ?: "adaptive"
    val capsuleAnimatable = remember { Animatable(0f) }
    val barAnimatable = remember { Animatable(0f) }
    LaunchedEffect(navStyle) {
        launch { capsuleAnimatable.follow { if (navStyle != "expanded" && scrolledDown) 1f else 0f } }
        launch {
            barAnimatable.follow {
                val compact = when (navStyle) { "expanded" -> false; "compact" -> true; else -> scrolledDown } || idleHidden
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
    // Phones: Back on Home asks "Exit Xstream?" — Yes clears the temporary video data (saved starts, first frames,
    // old logs and downloads) so the phone stays light; posters, settings and sign-ins stay.
    var askExit by remember { mutableStateOf(false) }
    val exitCtx = androidx.compose.ui.platform.LocalContext.current
    BackHandler(!BuildConfig.IS_TV && stack.isEmpty() && profile != null && tab == 0) { askExit = true }
    if (askExit) {
        val used = remember { StreamCache.usedBytes(exitCtx) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askExit = false },
            containerColor = Color(0xFF1A1720),
            title = { Text("Exit Xstream?", fontWeight = FontWeight.Bold) },
            text = { Text("Leaving also clears temporary video data" + (if (used > 1_000_000) " (${used / 1_000_000} MB)" else "") +
                " so your phone stays light. Posters, your settings and sign-ins are kept.", color = Color(0xFFBDB7C3)) },
            confirmButton = { androidx.compose.material3.TextButton({
                askExit = false
                val act = exitCtx as? android.app.Activity
                Preloader.release()
                Thread {
                    runCatching { StreamCache.clearAll(exitCtx) }
                    listOf("logs", "avatar_dl", "updates", "xs-hls-thumbs").forEach { d -> runCatching { java.io.File(exitCtx.cacheDir, d).deleteRecursively() } }
                    exitCtx.cacheDir.listFiles()?.filter { it.isFile && (it.name.endsWith(".ts") || it.name.endsWith(".tmp") || it.name.startsWith("seg")) }?.forEach { runCatching { it.delete() } }
                }.start()
                act?.finishAndRemoveTask()
            }) { Text("Yes, exit", color = Color(0xFFFF4D57), fontWeight = FontWeight.Bold) } },
            dismissButton = { androidx.compose.material3.TextButton({ askExit = false }) { Text("Stay", color = Color.White) } },
        )
    }

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
            null -> Box(Modifier.fillMaxSize().background(Bg).nestedScroll(scrollWatcher).pointerInput(Unit) {
                // Watches every touch (never consumes it) to wake the dock.
                awaitPointerEventScope { while (true) { awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial); lastTouch[0] = System.currentTimeMillis(); if (idleHidden) idleHidden = false } }
            }) {
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
                        onProfile = { stack.add(Screen.Settings("profileEdit")) },
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
                if (voiceOpen) VoiceAssistant(vm, onClose = { voiceOpen = false }, onOpen = { voiceOpen = false; open(it) })
                val bellTogether by BellNav.together.collectAsState()
                LaunchedEffect(bellTogether) { if (bellTogether) { BellNav.together.value = false; stack.add(Screen.Together()) } }
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
        // First start on a profile: Turbo or Classic (each profile chooses once; Settings › Sources & Extensions later).
        if (!BuildConfig.IS_TV && profile != null && texts["srcMode"].isNullOrEmpty()) FirstWatchChooser(vm)
        // Built-in servers (CNCVerse / HdHub links published from Developer's Corner) and the Connect PenguPlay card.
        // The look everyone shares (app logo, AI logo, X's voice) first — it's quick, and it used to wait behind the rest.
        LaunchedEffect(profile?.id) { if (profile != null) { Public.vm = vm; runCatching { AppLook.fetch(vm.appContext) }; runCatching { BuiltIns.sync(vm) }; runCatching { DevDefaults.sync(vm) }; runCatching { Official.apply(vm, vm.appContext) } } }
        // CloudStream plugins "kept warm" (Source Test › CloudStream): loaded and woken in the background.
        // Plugin mode: every switched-on plugin is loaded and woken at start, so a title page gets its links at once.
        LaunchedEffect(profile?.id) {
            if (profile == null || !(vm.store("csWarm") == "yes" || vm.pluginMode())) return@LaunchedEffect
            runCatching { CsPlugins.init(vm.appContext); if (CsPlugins.catalog.value.isEmpty()) CsPlugins.refresh(); CsPlugins.warmAll() }
            // Plugin mode: the first two Continue Watching titles get their Quick Play links fetched now (kept 10 minutes),
            // so tapping them starts at once.
            suspend fun cwAhead() {
                if (!vm.pluginMode() || vm.qsAddons().isEmpty()) return
                vm.progress.value.filter { !(it.durationMs > 0 && it.positionMs >= it.durationMs * 0.95) }.take(2).forEach { pr ->
                    val type = if (pr.key.contains(":")) "series" else "movie"
                    runCatching { kotlinx.coroutines.withTimeoutOrNull(40_000) { vm.streamsFlow(type, pr.key, only = vm.qsAddons()).first { it.second } } }
                }
            }
            cwAhead()
            // Kept warm: woken again every 9 minutes while the app is open (sites close idle connections; links expire).
            // Quick Play's plugins stay warm the whole time the app is open: a tiny search every 3 minutes keeps their
            // sites' connections and cookies alive (that's why CloudStream with Netflix open starts everything at once).
            var tick = 0
            while (true) {
                kotlinx.coroutines.delay(3 * 60_000L); tick++
                if (!vm.pluginMode()) continue
                runCatching { CsPlugins.warmSome(vm.qsAddons() + vm.qs4kNames()) }
                if (tick % 3 == 0) cwAhead()
            }
        }
        // Xstream: no "Connect PenguPlay" prompt — servers come from Developer's Corner.
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
        // Holding any dock button: every icon wobbles as if under water.
        var water by remember { mutableStateOf(false) }
        Row(
            Modifier.width(312.dp).height(68.dp).shadow(22.dp, shape)
                .drawBehind {
                    drawOval(Brush.radialGradient(listOf(red.copy(alpha = 0.45f), Color.Transparent), center = Offset(size.width * 0.3f, 0f), radius = size.width * 0.45f),
                        topLeft = Offset(-size.width * 0.1f, -size.height * 0.55f), size = androidx.compose.ui.geometry.Size(size.width * 0.85f, size.height * 1.1f))
                }
                .clip(shape).background(Brush.verticalGradient(listOf(Color(0xF71A1A22), Color(0xFA0D0E13))))
                .border(1.5.dp, Brush.horizontalGradient(listOf(Color(0xFFFF6A3D), red, red.copy(alpha = 0.35f), Color(0x26FFFFFF))), shape)
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            DockItem(0, water, tab == 0, t("navHome"), { onTab(0) }, { water = it }) { c -> Icon(Icons.Default.Home, t("navHome"), Modifier.size(23.dp), tint = c) }
            DockItem(1, water, tab == 1, t("navSearch"), { onTab(1) }, { water = it }) { c -> Icon(Icons.Default.Search, t("navSearch"), Modifier.size(23.dp), tint = c) }
            DockItem(2, water, tab == 3, "Library", { onTab(3) }, { water = it }) { c -> Icon(Icons.Default.VideoLibrary, "Library", Modifier.size(23.dp), tint = c) }
            val meLabel = t("navMySpace")
            val bob = rememberFloat(3, water)
            Box(
                Modifier.size(50.dp).graphicsLayer { translationY = bob.value.first * 3.dp.toPx(); rotationZ = bob.value.second }
                    .onGloballyPositioned { meOrigin[0] = it.boundsInRoot().topLeft }
                    .pointerInput(profiles) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val long = awaitLongPressOrCancellation(down.id)
                            if (long == null) { onTab(2); return@awaitEachGesture }
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            bubble = true; hover = -1
                            var moved = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                val at = meOrigin[0] + ch.position
                                if ((ch.position - down.position).getDistance() > 24f) moved = true
                                val h = slots.entries.firstOrNull { (_, r) -> r.inflate(18f).contains(at) }?.key ?: -1
                                if (h != hover) { hover = h; if (h >= 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                ch.consume()
                                if (!ch.pressed) break
                            }
                            val pick = profiles.getOrNull(hover)
                            hover = -1
                            // Released on a profile: switch. Released without sliding: the bubble stays open to tap.
                            if (pick != null) { bubble = false; if (pick.id != current?.id) ProfileLock.ask(pick) { vm.selectProfile(pick); onTab(2) } }
                            else if (moved) bubble = false
                        }
                    }
                    .semantics { contentDescription = meLabel },
                Alignment.Center,
            ) {
                // Your picture with a red ring (white inside it while My Space is open).
                Box(Modifier.size(40.dp).border(2.dp, if (tab == 2) Color.White else red, CircleShape).padding(3.dp).clip(CircleShape), Alignment.Center) { Avatar(vm, 34.dp) }
                Box(Modifier.size(46.dp).drawBehind { drawCircle(red.copy(alpha = 0.28f), radius = size.minDimension / 2f, style = androidx.compose.ui.graphics.drawscope.Stroke(5.dp.toPx())) })
            }
        }
        if (bubble) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { bubble = false } })
        if (bubble) Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp).offset(y = (-84).dp)
                .shadow(18.dp, RoundedCornerShape(40.dp)).clip(RoundedCornerShape(40.dp)).background(Color(0xF216181F))
                .border(1.dp, Brush.verticalGradient(listOf(red.copy(alpha = 0.7f), red.copy(alpha = 0.2f))), RoundedCornerShape(40.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End,
        ) {
            profiles.forEachIndexed { i, pr ->
                val on = hover == i
                val grow by animateFloatAsState(if (on) 1.18f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 500f), label = "grow")
                Row(Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { bubble = false; if (pr.id != current?.id) ProfileLock.ask(pr) { vm.selectProfile(pr); onTab(2) } },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(pr.name + if (pr.id == current?.id) " · you" else "", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (on) Color.White else Color(0xFFB6BDCC))
                    Box(Modifier.size(52.dp).onGloballyPositioned { slots[i] = it.boundsInRoot() }, Alignment.Center) {
                        if (on) WaterRipple(Modifier.matchParentSize())
                        // Hovered: a round ring that hugs the avatar (not a square highlight).
                        ProfileAvatar(vm, pr, 46.dp, Modifier.graphicsLayer { scaleX = grow; scaleY = grow }
                            .then(if (on) Modifier.border(2.dp, Color.White, CircleShape) else Modifier), animate = false)
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

/** One floating dock icon: it bobs gently all the time (as if on water) and wears a red glowing ring. */
@Composable
private fun DockItem(i: Int, water: Boolean, selected: Boolean, label: String, onClick: () -> Unit, onHold: (Boolean) -> Unit, icon: @Composable (Color) -> Unit) {
    val click by rememberUpdatedState(onClick)
    val hold by rememberUpdatedState(onHold)
    val bob = rememberFloat(i, water)
    val fill by androidx.compose.animation.animateColorAsState(if (selected) Color(0x24FFFFFF) else Color.Transparent, label = "dock")
    val red = Accent
    Box(
        Modifier.size(50.dp).graphicsLayer {
            val (y, r) = bob.value
            translationY = y * (if (water) 5.dp else 3.dp).toPx(); rotationZ = r
            val sc = if (water) 1f + 0.04f * y else 1f; scaleX = sc; scaleY = sc
        }
            .drawBehind {
                // Glow, then the ring.
                drawCircle(Brush.radialGradient(listOf(red.copy(alpha = if (selected) 0.32f else 0.22f), Color.Transparent), radius = size.minDimension * 0.62f), radius = size.minDimension * 0.62f)
                drawCircle(red.copy(alpha = if (selected) 0.95f else 0.6f), radius = size.minDimension / 2f - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            }
            .clip(CircleShape).background(fill)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { click() },
                    onPress = { tryAwaitRelease(); hold(false) },
                    onLongPress = { hold(true) },
                )
            }
            .semantics { contentDescription = label },
        Alignment.Center,
    ) { icon(if (selected) Color.White else Color(0xFF9AA0B0)) }
}

/** A slow float for dock icon [i] (each a little out of step): (up/down -1..1, tilt in degrees). Faster and wilder in [water]. */
@Composable
private fun rememberFloat(i: Int, water: Boolean): State<Pair<Float, Float>> {
    val inf = rememberInfiniteTransition(label = "float$i")
    val t by inf.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "t$i")
    return remember(water) {
        derivedStateOf {
            val ph = t * (if (water) 2.6f else 1f) + i * 1.6f
            val y = kotlin.math.sin(ph)
            val r = (if (water) 4f else 1.6f) * kotlin.math.sin(ph * 0.7f + 0.8f)
            y to r
        }
    }
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
