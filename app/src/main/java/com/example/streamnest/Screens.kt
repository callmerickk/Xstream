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
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.blur
import androidx.lifecycle.repeatOnLifecycle
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
    // Kept in the ViewModel: if a phone rebuilds the screen (vivo turning to landscape for the player), the open
    // screen — the player — stays instead of falling back to the page underneath.
    val stack = vm.navStack
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 0.9.74: not saved across a screen rebuild (it re-opened the AI by itself after rotation / theme change / app restore).
    var voiceOpen by remember { mutableStateOf(false) }
    val pop = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex); Unit }
    val profile by vm.current.collectAsStateWithLifecycle()
    val texts by vm.texts.collectAsStateWithLifecycle()
    // Music (Live server): its pages open from the Home chip, the mini bar and the notification.
    // Account-level terms must be accepted before the rest of the app can be used.
    LegalNoticeGate(vm)
    // 0.9.75: everyone starts on "All Live" (Live Home + Orbit bar + Midnight) once per profile; changeable in Settings › Layout.
    LaunchedEffect(profile?.id) {
        if (BuildConfig.IS_TV || profile == null || vm.texts.value["allLive0975"] == "1") return@LaunchedEffect
        vm.setText("homeStyle", "live"); vm.setText("navBar", "orbit")
        Themes.firstOrNull { it.name == "Midnight" }?.let { applyThemeOpt(vm, it) }
        vm.setText("allLive0975", "1")
        ActLog.add("APP", "look: All Live set (once)")
    }
    // Activity log: which screen is open.
    LaunchedEffect(stack.lastOrNull(), tab, profile?.id) {
        val top = stack.lastOrNull()
        ActLog.screen = if (profile == null) "Who's watching" else top?.let { it::class.simpleName } ?: listOf("Home", "Search", "My Space", "Profile", "Live").getOrElse(tab) { "Tab $tab" }
        ActLog.add("SCREEN", ActLog.screen + (top?.let { " · " + it.toString().take(80) } ?: ""))
        // 0.9.77: memory lines for a minute after each screen change (and relief above 75 %).
        MemWatch.currentId = (top as? Screen.Detail)?.meta?.id ?: (top as? Screen.Player)?.key
        MemWatch.onScreen(vm.appContext, ActLog.screen)
        FrameStats.onScreen(ActLog.screen)
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
    // Phones: Back on Home asks "Exit StreamX?" — Yes clears the temporary video data (saved starts, first frames,
    // old logs and downloads) so the phone stays light; posters, settings and sign-ins stay.
    var askExit by remember { mutableStateOf(false) }
    val exitCtx = androidx.compose.ui.platform.LocalContext.current
    BackHandler(!BuildConfig.IS_TV && stack.isEmpty() && profile != null && tab == 0) { askExit = true }
    if (askExit) {
        val used = remember { StreamCache.usedBytes(exitCtx) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askExit = false },
            containerColor = Color(0xFF1A1720),
            title = { Text("Exit ${Brand.NAME}?", fontWeight = FontWeight.Bold) },
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
    // First start: "Sign in to StreamX" (or Skip) before "Who's watching?".
    var gateOpen by rememberSaveable { mutableStateOf(!Cloud.decided.value) }
    // Right after signing in, wait for the account (profiles, pictures, addons) before "Who's watching?".
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
        // Titles from collections / search groups carry TMDB ids ("tmdb:movie:299536"): they are turned into the IMDb id
        // first, so the title page loads (they opened with the TMDB id and every source answered "not found").
        // 0.9.73 (from Xstream): VR › Browse picked a title: leave this player, open its page, which starts it (again in VR).
        val vrPick by VrState.openTitle.collectAsState()
        LaunchedEffect(vrPick) {
            val m = vrPick ?: return@LaunchedEffect
            VrState.openTitle.value = null
            while (stack.lastOrNull() is Screen.Player) stack.removeAt(stack.lastIndex)
            pendingDetailPlay.value = m.id
            stack.add(Screen.Detail(m))
        }
        val open: (Meta) -> Unit = { m ->
            if (!m.id.startsWith("tmdb:")) stack.add(Screen.Detail(m))
            else scope.launch {
                val r = runCatching { vm.resolveForOpen(m) }.getOrNull()
                if (r != null) stack.add(Screen.Detail(r))
                else Snack.show(if (vm.tmdbKey().isEmpty()) "Add a TMDB key in Integrations to open this title" else "Couldn't open this title")
            }
        }
        // "Hey Aiden": the offline wake word opens the AI card (anywhere in the app, also during a movie).
        val buddyCtx = androidx.compose.ui.platform.LocalContext.current
        val callMic by Call.micOn.collectAsState()
        val life = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        var appResumed by remember { mutableStateOf(true) }
        DisposableEffect(life) {
            val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) appResumed = true
                else if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) appResumed = false
            }
            life.addObserver(o); onDispose { life.removeObserver(o); WakeWord.stop() }
        }
        val onPlayer = stack.lastOrNull() is Screen.Player
        val aiBusy = voiceOpen || VoiceAskBus.pending.collectAsState().value != null
        LaunchedEffect(onPlayer) { WakeWord.strict = onPlayer }
        LaunchedEffect(aiBusy) { if (!aiBusy) WakeWord.quietUntil = System.currentTimeMillis() + 3000 }
        // 0.8: AIDEN only listens in the video app — not while Xstream Music is open or a song is playing (songs said
        // its name), and not while voice search has the microphone.
        val musicLib by MusicPlayer.openLibrary.collectAsState()
        val musicNow by MusicPlayer.openNow.collectAsState()
        val songOn = MusicPlayer.now.collectAsState().value.playing
        val micHeld by MicHold.n.collectAsState()
        LaunchedEffect(profile?.id, aiBusy, callMic, appResumed, texts["wakeOn"], musicLib, musicNow, songOn, micHeld, onPlayer) {
            WakeWord.onName = { if (!voiceOpen) { AiPlayerBus.duck(); voiceOpen = true } }
            // Not during a video either: the listening microphone showed Android's green privacy dot over the film.
            // "Hey Aiden" is switched off for now (0.9.65): it didn't hear the name reliably and woke when not called.
            val on = WakeWordOff.not() && !BuildConfig.IS_TV && profile != null && !onPlayer && !aiBusy && !callMic && appResumed && !musicLib && !musicNow && !songOn && micHeld == 0 && isOn(texts["wakeOn"] ?: "yes")
            if (on) { delay(700); WakeWord.start(buddyCtx) } else WakeWord.stop()
        }
        if (profile == null && gateOpen) CloudGate(onDone = { gateOpen = false })
        else if (profile == null && (restoringCloud || emptyAccount)) RestoreScreen(restoringCloud, onDifferent = { Cloud.signOut(); Cloud.restoring.value = false; emptyAccount = false; gateOpen = true }) { Cloud.restoring.value = false; emptyAccount = false }
        else if (profile == null && texts["streamMode"] == null) ModeChooser(vm) {}
        // 0.9.74: phones get the Horizon profile page (from Xstream, with "no lift"); TV keeps its own.
        else if (profile == null && !BuildConfig.IS_TV) HorizonWhoIsWatching(vm, !showSplash, onPicked = { tab = 0; scrolledDown = false },
            onPlay = { stack.add(it) }, onOpen = open)
        else if (profile == null) WhoIsWatchingScreen(vm, active = !showSplash) { tab = 0; scrolledDown = false }
        else when (val top = stack.lastOrNull()) {
            is Screen.Player -> key(top) {
                // A saved link to the app's own relay (127.0.0.1:port) is dead after a restart (its port changes): the
                // title is looked up fresh instead (Continue Watching used to fail first, then recover after 15–20 s).
                val local = top.url.startsWith("http://127.0.0.1") || top.url.startsWith("http://localhost")
                val start = if (local && top.key.isNotEmpty()) top.copy(url = "", find = true, instant = false) else top
                PlayerScreen(vm, start, onBack = pop, onReplace = { next -> if (stack.isNotEmpty()) stack[stack.lastIndex] = next }, onProgress = { pos, dur, url, subs ->
                    if (top.key.isNotEmpty() && url.isNotEmpty()) vm.saveProgress(Progress(top.key, top.title, top.image, url, subs, pos, dur, System.currentTimeMillis(),
                        source = PlayingSrc.source.takeIf { PlayingSrc.url == url }, addon = PlayingSrc.addon.takeIf { PlayingSrc.url == url }))
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
                PosterZoomIn {
                    DetailScreen(vm, top.meta, onBack = pop, onOpen = open, sourcesFor = top.sourcesFor, onPlay = { stack.add(it) },
                        onPerson = { c -> stack.add(Screen.Person(c.id, c.name, c.photo)) },
                        onTrailer = { stack.add(it) })
                }
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
            Screen.Notes -> NotificationsPage(onBack = pop, onTogether = { stack.add(Screen.Together()) }, onOpen = open)
            null -> Box(Modifier.fillMaxSize().background(Bg).nestedScroll(scrollWatcher)) {
                Box(Modifier.fillMaxSize().then(if (voiceOpen) Modifier.blur(10.dp) else Modifier)) {
                when (tab) {
                    0 -> HomeScreen(
                        vm, homeList, capsuleAnim, open, { stack.add(it) },
                        onLive = { stack.add(Screen.Live) },
                        onAi = { voiceOpen = true },
                        onManual = { m, k -> stack.add(Screen.Detail(m, k)) },
                        onOpenCollection = { c, f -> stack.add(Screen.Collection(c.id, f.id)) },
                        onNotes = { stack.add(Screen.Notes) },
                        onTogether = { stack.add(Screen.Together()) },
                    )
                    1 -> SearchScreen(vm, "", open)
                    2 -> LibraryPage(vm, onBack = { tab = 0 }, onOpen = open, onPlay = { stack.add(it) })
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
                }
                val navHidden by NavHide.on.collectAsState()
                val onNavTab: (Int) -> Unit = {
                    scrolledDown = false
                    if (it == 0 && tab == 0) { scope.launch { homeList.animateScrollToItem(0) }; vm.refresh() } else tab = it
                }
                // 0.9.75: Settings › Layout › Bottom bar — Orbit (Live), Horizon (StreamX) or Capsule.
                if (!(navHidden && tab == 2)) when (barKindOf(texts)) {
                    "orbit" -> PhoneOrbitBar(tab, vm, compact = capsuleAnim, onTab = onNavTab, modifier = Modifier.align(Alignment.BottomCenter).tourTarget("nav"))
                    "capsule" -> PhoneCapsuleBar(tab, vm, compact = barAnim, onTab = onNavTab, modifier = Modifier.align(Alignment.BottomCenter).tourTarget("nav"))
                    else -> BottomNav(tab, vm, compact = barAnim, onTab = onNavTab, modifier = Modifier.align(Alignment.BottomCenter).tourTarget("nav"))
                }
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
        // The AI card: over any screen (the wake word can call it during a movie). Playing a title leaves the player.
        if (profile != null && voiceOpen) VoiceAssistant(vm, onClose = { voiceOpen = false; AiPlayerBus.unduck() }, onOpen = { m ->
            voiceOpen = false
            if (pendingDetailPlay.value != null) while (stack.lastOrNull() is Screen.Player) stack.removeAt(stack.lastIndex) else AiPlayerBus.unduck()
            open(m)
        })
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
                topScreen == null && tab == 3 -> TourOverlay("myspace", MySpaceTour)
                topScreen is Screen.Detail -> TourOverlay("detail", DetailTour)
                topScreen is Screen.Player -> TourOverlay("player", PlayerTour)
            }
        }
        if (profile != null && texts["guideDone"].isNullOrEmpty()) QuickGuide { vm.setText("guideDone", "1") }
        // First start on a profile: Turbo or Classic (each profile chooses once; Settings › Sources & Extensions later).
        if (!BuildConfig.IS_TV && profile != null && texts["srcMode"].isNullOrEmpty()) FirstWatchChooser(vm)
        // 0.9.76: "Choose your look" once per profile (new and existing): Home style + theme (Glass first, NEW). Skip = All Live.
        else if (profile != null && !texts["guideDone"].isNullOrEmpty() && lookDue(texts, profile) && !showSplash && stack.lastOrNull() !is Screen.Player)
            androidx.compose.ui.window.Dialog({}, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
                FirstLookScreen(vm) {}
            }
        // Built-in servers (CNCVerse / HdHub links published from Developer's Corner) and the Connect PenguPlay card.
        // The look everyone shares (app logo, AI logo, X's voice) first — it's quick, and it used to wait behind the rest.
        LaunchedEffect(profile?.id) { if (profile != null) { Public.vm = vm; runCatching { AppLook.fetch(vm.appContext) }; runCatching { BuiltIns.sync(vm) }; runCatching { DevDefaults.sync(vm) }; runCatching { Official.apply(vm, vm.appContext) } } }
        // Back in front: the shared look (AI logo) and the developer's defaults (row names…) again, so changes show without a restart.
        val lo = androidx.lifecycle.compose.LocalLifecycleOwner.current
        LaunchedEffect(lo, profile?.id) {
            if (profile == null) return@LaunchedEffect
            lo.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                runCatching { AppLook.refresh(vm.appContext) }
                runCatching { DevDefaults.sync(vm) }
            }
        }
        // CloudStream plugins "kept warm" (Source Test › CloudStream): loaded and woken in the background.
        // Plugin mode: every switched-on plugin is loaded and woken at start, so a title page gets its links at once.
        LaunchedEffect(profile?.id) {
            if (profile == null || BuildConfig.IS_TV) return@LaunchedEffect
            // Warm-up starts a few seconds after Home is up (the app opens smoothly first). A title played before then
            // starts its own plugins at once, so playing is never slower. Plugins are checked (repaired, updated) and
            // woken on every start whenever any are switched on.
            delay(4_000)
            // 0.9.79: quiet start — not while you are scrolling (at most 15 s more).
            if (!BuildConfig.IS_TV) TvIdle.waitQuiet(1_500, 15_000)
            runCatching { CsPlugins.init(vm.appContext); CsPlugins.upkeep(); if (CsPlugins.enabled.value.isNotEmpty()) CsPlugins.warmAll() }
            if (!(vm.store("csWarm") == "yes" || vm.pluginMode())) return@LaunchedEffect
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
        // StreamX: no "Connect PenguPlay" prompt — servers come from Developer's Corner.
        val askWl by WatchlistAsk.pending.collectAsState()
        askWl?.let { m -> AddToWatchlistSheet(vm, m) { WatchlistAsk.pending.value = null } }
        val rmWl by WatchlistAsk.removing.collectAsState()
        rmWl?.let { (m, r) -> RemoveFriendPickSheet(vm, m, r) { WatchlistAsk.removing.value = null } }
        if (profile != null && stack.isEmpty() && !showSplash && !texts["guideDone"].isNullOrEmpty()) PenguConnectPrompt(vm)
        if (showSplash) StartupSplash { showSplash = false }
        if (!BuildConfig.IS_TV) profile?.let { p -> MusicHost(vm, p.id, p.name) }
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
        if (first) { first = false; snapTo(v) } else animateTo(v, tween(260))
    }
}

/** Hotstar-style bottom bar: 3 items, dark base with a glowing curved top edge. Shrinks while scrolling down. */
@Composable
fun BottomNav(tab: Int, vm: AppViewModel, compact: State<Float>, onTab: (Int) -> Unit, modifier: Modifier = Modifier) {
    var switcher by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    // The compact animation is a pure layer translation (draw phase): the bar's contents are not re-laid-out,
    // recomposed or redrawn while it moves.
    Box(modifier.fillMaxWidth().graphicsLayer { translationY = compact.value * 16.dp.toPx() }) {
        Spacer(Modifier.matchParentSize().drawWithCache {
            // Paths, brushes and strokes are built once per size / theme colour, not on every draw.
            // A planet-like horizon: rises from the left, peaks a little right of centre, falls gently on the right.
            val glow = Accent
            val glow2 = Palette.accent2
            val yl = 26.dp.toPx(); val yr = 18.dp.toPx(); val peak = (-6).dp.toPx()
            val surface = Path().apply {
                moveTo(0f, yl); quadraticBezierTo(size.width * 0.5f, peak, size.width, yr)
                lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            val base = Brush.verticalGradient(listOf(Color(0xFA120E18), Color(0xFF07080C)))
            val edge = Path().apply { moveTo(0f, yl); quadraticBezierTo(size.width * 0.5f, peak, size.width, yr) }
            // Light is strongest on the left and fades towards the right, like sunrise on a horizon.
            val light = Brush.horizontalGradient(listOf(glow2, glow, glow.copy(alpha = 0.35f), glow.copy(alpha = 0.08f)))
            val wide = Stroke(width = 14.dp.toPx())
            val mid = Stroke(width = 7.dp.toPx())
            val thin = Stroke(width = 2.2.dp.toPx())
            onDrawBehind {
                drawPath(surface, base)
                drawPath(edge, light, style = wide, alpha = 0.10f)
                drawPath(edge, light, style = mid, alpha = 0.22f)
                drawPath(edge, light, style = thin)
            }
        })
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 14.dp).height(66.dp),
            horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(tab == 0, t("navHome"), compact, { onTab(0) }) { c -> Icon(Icons.Default.Home, t("navHome"), Modifier.size(26.dp), tint = c) }
            NavItem(tab == 1, t("navSearch"), compact, { onTab(1) }) { c -> Icon(Icons.Default.Search, t("navSearch"), Modifier.size(26.dp), tint = c) }
            NavItem(tab == 2, "Library", compact, { onTab(2) }) { c -> Icon(Icons.Default.VideoLibrary, "Library", Modifier.size(26.dp), tint = c) }
            Box {
                val profiles by vm.profiles.collectAsStateWithLifecycle()
                var hover by remember { mutableIntStateOf(-1) }
                var adding by remember { mutableStateOf(false) }
                val density = LocalDensity.current
                val itemPx = with(density) { 72.dp.toPx() }
                val gapPx = with(density) { 34.dp.toPx() }
                val sel = tab == 3
                val accent = BtnColor
                val glow = remember(accent) { Brush.radialGradient(listOf(accent.copy(alpha = 0.42f), accent.copy(alpha = 0.10f), Color.Transparent)) }
                Box(
                    Modifier.width(96.dp).fillMaxHeight().clip(RoundedCornerShape(22.dp))
                        .pointerInput(profiles.size) {
                            // Tap = Profile tab. Hold = the profiles capsule opens above; slide up and let go on a profile to switch.
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    while (true) { val e = awaitPointerEvent(); if (e.changes.all { !it.pressed }) return@withTimeoutOrNull false }
                                    @Suppress("UNREACHABLE_CODE") false
                                } == null
                                if (!held) { onTab(3); return@awaitEachGesture }
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress); switcher = true; hover = -1
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.firstOrNull() ?: break
                                    if (!c.pressed) break
                                    c.consume()
                                    // Slots from the top: "+ Add profile", then the profiles; the finger is measured from the bar.
                                    val up = -(c.position.y) - gapPx
                                    val total = profiles.size + 1
                                    val fromBottom = if (up < 0f) -1 else (up / itemPx).toInt()
                                    val idx = if (fromBottom in 0 until total) { val top = total - 1 - fromBottom; if (top == 0) profiles.size else top - 1 } else -1
                                    if (idx != hover) { hover = idx; if (idx >= 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                }
                                val wantsAdd = hover == profiles.size
                                val pick = profiles.getOrNull(hover)
                                hover = -1
                                if (wantsAdd) { switcher = false; adding = true }
                                if (pick != null) {
                                    switcher = false
                                    if (pick.id == vm.current.value?.id) onTab(3) else ProfileLock.ask(pick) { vm.selectProfile(pick); onTab(3) }
                                }
                            }
                        }
                        .then(if (sel) Modifier.background(glow) else Modifier),
                    Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(vm, 30.dp, if (sel) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                        if (sel) Text("Profile", Modifier.graphicsLayer { alpha = 1f - compact.value; compositingStrategy = CompositingStrategy.ModulateAlpha },
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                    }
                }
                if (switcher) ProfileSwitcher(vm, hover, onDismiss = { switcher = false }, onPicked = { switcher = false; onTab(3) }, onAdd = { switcher = false; adding = true })
                if (adding) ProfileEditor(vm, null) { adding = false }
            }
        }
    }
}

@Composable
private fun NavItem(
    selected: Boolean, label: String, compact: State<Float>, onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null, icon: @Composable (Color) -> Unit,
) {
    val c = if (selected) Color.White else Color(0xFF8A94A8)
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val hasLong = onLongClick != null
    val accent = BtnColor
    val selectedGlow = remember(accent) { Brush.radialGradient(listOf(accent.copy(alpha = 0.42f), accent.copy(alpha = 0.10f), Color.Transparent)) }
    Box(
        Modifier.width(96.dp).fillMaxHeight().clip(RoundedCornerShape(22.dp))
            .pointerInput(hasLong) {
                detectTapGestures(onTap = { click() }, onLongPress = if (hasLong) { _: Offset -> longClick?.invoke() } else null)
            }
            .then(if (selected) Modifier.background(selectedGlow) else Modifier),
        Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon(c)
            // Fade in the draw phase; ModulateAlpha avoids an offscreen buffer for the label on every frame.
            if (selected) Text(label, Modifier.graphicsLayer {
                alpha = 1f - compact.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c, maxLines = 1)
        }
    }
}

/** Quick profile switcher: a vertical capsule of avatars above the Profile button (slide a finger to pick, or tap). */
@Composable
private fun ProfileSwitcher(vm: AppViewModel, hover: Int, onDismiss: () -> Unit, onPicked: () -> Unit, onAdd: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val above = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2).coerceIn(16, maxOf(16, windowSize.width - popupContentSize.width - 16))
                val y = (anchorBounds.top - popupContentSize.height - 8).coerceAtLeast(0)
                return IntOffset(x, y)
            }
        }
    }
    Popup(popupPositionProvider = above, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val shape = RoundedCornerShape(40.dp)
        val pop = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(Unit) { pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 420f)) }
        Column(
            Modifier.graphicsLayer {
                val v = pop.value
                alpha = v.coerceIn(0f, 1f); scaleX = 0.88f + 0.12f * v; scaleY = scaleX
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            }.shadow(18.dp, shape).clip(shape).background(Color(0xF21A1820)).border(1.dp, Color(0x26FFFFFF), shape)
                .padding(horizontal = 8.dp, vertical = 13.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // "+ Add profile" at the top of the capsule.
            run {
                val hot = hover == profiles.size
                val grow by androidx.compose.animation.core.animateFloatAsState(if (hot) 1.18f else 1f, label = "hovAdd")
                Column(
                    Modifier.width(66.dp).height(72.dp).graphicsLayer { scaleX = grow; scaleY = grow }
                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onAdd),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(52.dp), Alignment.Center) {
                        if (hot) WaterRipple(Modifier.matchParentSize())
                        Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0x22FFFFFF)).border(2.dp, if (hot) Color.White else Color(0x44FFFFFF), CircleShape), Alignment.Center) {
                            Icon(Icons.Default.Add, "Add profile", Modifier.size(24.dp), tint = Color.White)
                        }
                    }
                    Text("Add", Modifier.padding(top = 2.dp), fontSize = 11.sp, color = Color(0xFFB6BDCC), maxLines = 1)
                }
            }
            profiles.forEachIndexed { i, pr ->
                val on = pr.id == current?.id
                val hot = i == hover
                val grow by androidx.compose.animation.core.animateFloatAsState(if (hot) 1.18f else 1f, label = "hov")
                Column(
                    Modifier.width(66.dp).height(72.dp).graphicsLayer { scaleX = grow; scaleY = grow }
                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { if (!on) ProfileLock.ask(pr) { vm.selectProfile(pr); onPicked() } else onPicked() },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    // A round ring that hugs the avatar (red = this profile, white = under your finger), with water rings spreading.
                    Box(Modifier.size(52.dp), Alignment.Center) {
                        if (hot) WaterRipple(Modifier.matchParentSize())
                        ProfileAvatar(vm, pr, 44.dp, if (hot) Modifier.border(2.dp, Color.White, CircleShape) else if (on) Modifier.border(2.dp, Accent, CircleShape) else Modifier, animate = false)
                    }
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

/** StreamX: "Bringing everything over from your account" after signing in, and a clear note if the account is empty. */
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
                Spacer(Modifier.width(10.dp)); Wordmark("${Brand.NAME}", 26.sp)
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
                Text("You're signed in as ${user?.email.orEmpty()}. If ${Brand.NAME} uses a different email, sign in with that one to bring your profiles over.",
                    fontSize = 13.sp, color = Color(0xFF9AA3B5), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                androidx.compose.material3.TextButton(onDifferent) { Text("Use a different account") }
                androidx.compose.material3.TextButton(onContinue) { Text("Continue with this account") }
            }
        }
    }
}


/** Soft rings spreading out like water under the profile your finger is on. */
@Composable
private fun WaterRipple(modifier: Modifier) {
    val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "water")
    val t by inf.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.LinearEasing)), label = "w")
    Spacer(modifier.drawBehind {
        for (k in 0..2) {
            val p = (t + k / 3f) % 1f
            drawCircle(Color(0xFF9FD8FF), radius = size.minDimension / 2f * (0.8f + 0.7f * p), alpha = 0.7f * (1f - p), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    })
}

/** "Hey Aiden" hands-free wake word: off in this version (the setting is hidden). */
const val WakeWordOff = true
