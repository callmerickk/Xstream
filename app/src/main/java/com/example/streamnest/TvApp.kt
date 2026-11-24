package com.example.streamnest

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Screens pushed on top of the TV sections. */
sealed interface TvRoute {
    /** [autoPlay]: Play was pressed on Home's hero card — start playing as soon as the page is ready (once). */
    data class Detail(val meta: Meta, val autoPlay: Boolean = false, val manual: Boolean = false, val ep: String? = null) : TvRoute { var played = false }
    data class Person(val p: Screen.Person) : TvRoute
    data class Player(val p: Screen.Player) : TvRoute
    data class Collection(val c: NCollection, val f: NFolder) : TvRoute
    data class Settings(val start: String) : TvRoute
}

internal tailrec fun Context.tvActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.tvActivity()
    else -> null
}

/**
 * StreamX TV shell: splash, "Who's watching?", then the sections (Home / Search / Library / My Space / Settings) with
 * a floating sidebar that is completely hidden until LEFT is pressed on the left-most item (or BACK on Home).
 * BACK: closes pushed screens → goes back to Home → opens the sidebar → (sidebar open on Home) exits the app.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvApp(vm: AppViewModel = viewModel()) {
    val stack = remember { mutableStateListOf<TvRoute>() }
    var tab by rememberSaveable { mutableStateOf(TvTab.Home) }
    var sidebar by remember { mutableStateOf(false) }
    val returnFocus = remember { arrayOfNulls<FocusRequester>(1) }
    val contentFr = remember { FocusRequester() }
    val contentFocused = remember { booleanArrayOf(false) }
    val railTick = remember { mutableIntStateOf(0) }
    val profile by vm.current.collectAsStateWithLifecycle()
    LaunchedEffect(stack.lastOrNull(), tab, profile?.id) {
        val top = stack.lastOrNull()
        // 0.9.5: the phone app in big-screen mode logs "Big …" and keeps the phone's memory / frame lines.
        val pre = if (BuildConfig.IS_TV) "TV " else "Big "
        ActLog.screen = if (profile == null) pre + "Who's watching" else pre + (top?.let { it::class.simpleName } ?: tab.name)
        ActLog.add("SCREEN", ActLog.screen + (top?.let { " · " + it.toString().take(80) } ?: ""))
        if (BuildConfig.IS_TV) TvSmall.lastScreen = ActLog.screen
        else {
            MemWatch.currentId = (top as? TvRoute.Detail)?.meta?.id ?: (top as? TvRoute.Player)?.p?.key
            MemWatch.onScreen(vm.appContext, ActLog.screen); FrameStats.onScreen(ActLog.screen)
        }
    }
    val texts by vm.texts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(profile?.id) {
        if (profile == null) return@LaunchedEffect
        Public.vm = vm
        runCatching { AppLook.fetch(vm.appContext) }; runCatching { BuiltIns.sync(vm) }; runCatching { DevDefaults.sync(vm) }
        // Turbo on the TV too: the developer's official plugins arrive, and Quick Play's plugins are kept warm.
        runCatching { Official.apply(vm, vm.appContext) }
        if (vm.pluginMode()) runCatching { CsPlugins.init(vm.appContext); if (CsPlugins.catalog.value.isEmpty()) CsPlugins.refresh() }
        while (true) {
            if (vm.pluginMode()) runCatching { CsPlugins.warmSome(vm.qsAddons() + vm.qs4kNames()) }
            kotlinx.coroutines.delay(3 * 60_000L)
        }
    }
    val ctx = LocalContext.current
    // TV 0.8.3: reads this box once (memory, decoders, the TV's screen modes, soundbar formats) and sets defaults for it.
    LaunchedEffect(Unit) {
        if (!BuildConfig.IS_TV) return@LaunchedEffect // 0.9.5: TV box setup and the TV memory lines are for the TV app only
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { TvBox.info(ctx) } }
        runCatching { TvBox.setupOnce(ctx, vm) }
        // TV 0.9.11: memory right after the start too (the log showed 227 of 256 MB 5 s in, on Who's watching).
        var memAt = 0L
        for (at in listOf(3_000L, 6_000L, 12_000L, 25_000L)) {
            kotlinx.coroutines.delay(at - memAt); memAt = at
            ActLog.add("APP", "memory +${at / 1000} s · ${TvSmall.mem(ctx)}")
        }
        // TV 0.8.4: a memory line every minute (and the mark that tells, next start, if Android closed the app on screen).
        while (true) {
            kotlinx.coroutines.delay(60_000)
            TvSmall.beat(ctx)
            ActLog.add("APP", "memory · ${TvSmall.mem(ctx)}")
        }
    }
    // TV 0.8.7: 4K HDHUB starts in the background 15 s after a profile opens (it often needs 12+ s to load).
    LaunchedEffect(profile?.id) {
        if (profile == null || !BuildConfig.IS_TV) return@LaunchedEffect // 0.9.5: TV only
        kotlinx.coroutines.delay(15_000)
        TvIdle.waitQuiet() // TV 0.9.13: not while you're moving around Home
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { CsPlugins.tvPrewarm(ctx) } }
    }
    val focusManager = LocalFocusManager.current
    val homeState = remember(profile?.id) { TvHomeState() }
    val searchQuery = remember(profile?.id) { mutableStateOf("") }
    val opening = remember { arrayOfNulls<Job>(1) }
    var voteFor by remember { mutableStateOf<Meta?>(null) }
    var showSplash by remember { mutableStateOf(!StartupState.splashShown && isOn(texts["splash"] ?: "yes")) }
    /** TV 0.9.14: the splash cross-fades into Who's watching (it used to fade to an empty dark screen first). */
    var splashFading by remember { mutableStateOf(false) }
    var gateOpen by rememberSaveable { mutableStateOf(!Cloud.decided.value) }

    val push: (TvRoute) -> Unit = { stack.add(it) }
    val pop: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex); Unit }
    // Collection items can have TMDB ids: they are turned into IMDb ids first (normal addon titles return at once).
    fun openTitle(m: Meta, autoPlay: Boolean, manual: Boolean = false) {
        if (opening[0]?.isActive != true) opening[0] = scope.launch {
            val r = runCatching { vm.resolveForOpen(m) }.getOrNull()
            if (r != null) stack.add(TvRoute.Detail(r, autoPlay || manual, manual)) else Snack.show("Couldn't open ${m.name}")
        }
    }
    val openMeta: (Meta) -> Unit = { m -> openTitle(m, false) }
    val playMeta: (Meta) -> Unit = { m -> openTitle(m, true) }
    val manualMeta: (Meta) -> Unit = { m -> openTitle(m, false, manual = true) }
    val settingsIdx = remember { mutableIntStateOf(0) }
    // TV 0.8.4: a saved link to the app's own relay (127.0.0.1) is dead after an update or restart — the player finds a
    // fresh one at once (the source it last played comes first), instead of trying the dead one and showing an error.
    val play: (Screen.Player) -> Unit = { s0 ->
        val local = s0.url.startsWith("http://127.0.0.1") || s0.url.startsWith("http://localhost")
        stack.add(TvRoute.Player(if (local && s0.key.isNotEmpty()) s0.copy(url = "", find = true, instant = false) else s0))
    }

    fun openSidebar(from: FocusRequester?) {
        returnFocus[0] = from
        sidebar = true
    }
    fun closeSidebar() {
        sidebar = false
        val fr = returnFocus[0]
        returnFocus[0] = null
        if (fr != null && fr.tryFocus()) return
        if (tab == TvTab.Home) homeState.refocus.intValue++ else contentFr.tryFocus()
    }

    val tvAskExit = remember { mutableStateOf(false) }
    val tvEmailGate = remember { mutableStateOf(false) }
    BackHandler(enabled = profile != null && !showSplash && !gateOpen) {
        when {
            stack.isNotEmpty() -> pop()
            sidebar -> if (tab == TvTab.Home) tvAskExit.value = true else closeSidebar()
            tab != TvTab.Home -> tab = TvTab.Home
            else -> openSidebar(null)
        }
    }

    CompositionLocalProvider(LocalTexts provides texts) {
        // 0.9.5: the phone app in big-screen mode asks for the account terms as the phone layout does.
        if (!BuildConfig.IS_TV) LegalNoticeGate(vm)
        if (tvAskExit.value) TvExitDialog(onStay = { tvAskExit.value = false })
        LaunchedEffect(profile == null) { if (profile == null) { stack.clear(); sidebar = false } }
        // After switching sections, make sure something on the new screen has focus.
        LaunchedEffect(tab, stack.isEmpty(), profile?.id) {
            if (profile == null || stack.isNotEmpty()) return@LaunchedEffect
            delay(160)
            if (!sidebar && !contentFocused[0]) contentFr.tryFocus()
        }
        // TV 0.9.14: a profile was just picked → Home fades and settles in (it used to sit behind a grey dim for seconds).
        val prevPid = remember { mutableStateOf(profile?.id) }
        val homeIn = remember(profile?.id) { androidx.compose.animation.core.Animatable(if (prevPid.value == null && profile != null) 0f else 1f) }
        SideEffect { prevPid.value = profile?.id }
        LaunchedEffect(homeIn) { homeIn.animateTo(1f, androidx.compose.animation.core.tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = homeIn.value; val sc = 0.98f + 0.02f * homeIn.value; scaleX = sc; scaleY = sc }
            .background(if (Palette.glass) Color(0xFF05060A) else Bg)) {
            // TV 0.9.3 Glass theme: a soft blur of the poster you are on behind every page (a tiny picture stretched —
            // blurry by itself, almost free on a stick), the see-through page colour on top.
            if (Palette.glass) TvGlassBackdrop()
            if (Palette.glass) Box(Modifier.fillMaxSize().background(Bg))
            // 0.9.5: big-screen mode on the phone app signs in like the phone (no "link with your phone" code page).
            if (!BuildConfig.IS_TV && profile == null && gateOpen) CloudGate(onDone = { gateOpen = false })
            else if (!BuildConfig.IS_TV && profile == null && texts["streamMode"] == null) ModeChooser(vm) {}
            else if (profile == null && gateOpen && !tvEmailGate.value) TvLinkGate(onDone = { gateOpen = false }, onEmail = { tvEmailGate.value = true })
            else if (profile == null && gateOpen) CloudGate(onDone = { gateOpen = false; tvEmailGate.value = false }, onPhone = { tvEmailGate.value = false })
            // TV 0.8.5: Turbo + Elite from the start (no question); Settings can still change both.
            else if (profile == null && texts["streamMode"] == null) LaunchedEffect(Unit) { vm.setText("streamMode", "elite"); ActLog.add("APP", "TV: Turbo + Elite on by default") }
            else if (profile == null) TvProfileGate(vm, active = !showSplash || splashFading, onResume = { r -> tab = TvTab.Home; play(Screen.Player(r.title, r.url, r.subs, r.key, r.image, r.positionMs)) }) { tab = TvTab.Home }
            else when (val top = stack.lastOrNull()) {
                is TvRoute.Player -> key(top) {
                    val p = top.p
                    // TV 0.9.10: Play manually from the player's episode menu: the title page's sources list for that episode.
                    TvEpNav.manual = { m, epId -> if (stack.isNotEmpty()) stack[stack.lastIndex] = TvRoute.Detail(m, autoPlay = true, manual = true, ep = epId) }
                    PlayerScreen(vm, p, onBack = pop, onReplace = { next -> if (stack.isNotEmpty()) stack[stack.lastIndex] = TvRoute.Player(next) }, onProgress = { pos, dur, url, subs ->
                        if (p.key.isNotEmpty() && url.isNotEmpty()) vm.saveProgress(Progress(p.key, p.title, p.image, url, subs, pos, dur, System.currentTimeMillis()))
                        // "How was it?" once you've really watched something (10 min or 30%).
                        val base = p.key.substringBefore(":")
                        val prev = vm.votes.value[base]
                        val watchedEnough = pos >= 10 * 60_000L || (dur > 0 && pos >= dur * 0.3)
                        val askAgain = prev == null || (prev.v == 0 && System.currentTimeMillis() - prev.at > 3L * 24 * 3600_000)
                        if (base.isNotEmpty() && watchedEnough && askAgain && isOn(texts["askVote"] ?: "yes")) {
                            voteFor = p.meta?.copy(id = base) ?: Meta(id = base, type = p.type.ifBlank { if (p.key.contains(":")) "series" else "movie" },
                                name = p.title.substringBefore("  S"), poster = p.image)
                        }
                    })
                }
                is TvRoute.Detail -> key(top) {
                    TvDetailScreen(vm, top.meta, onBack = pop, onOpen = openMeta, onPlay = play,
                        onPerson = { c -> push(TvRoute.Person(Screen.Person(c.id, c.name, c.photo))) },
                        autoPlay = top.autoPlay && !top.played, onAutoPlayed = { top.played = true }, autoManual = top.manual, autoEp = top.ep)
                }
                is TvRoute.Person -> key(top) { Box(Modifier.fillMaxSize().background(Bg)) { PersonScreen(vm, top.p, onBack = pop, onOpen = openMeta) } }
                is TvRoute.Collection -> key(top) { TvCollectionScreen(vm, top.c, top.f, onBack = pop, onOpen = openMeta) }
                is TvRoute.Settings -> key(top) { TvSettingsPage(vm, top.start, onClose = pop) }
                null -> {
                    val edge: (FocusRequester) -> Unit = { fr -> openSidebar(fr) }
                    // TV 0.9.0: side bar style (Settings › Look). Floating / Hidden give the page the whole screen.
                    val barStyle = TvLook.bar(texts)
                    val fullPage = TvLook.fullScreen(barStyle)
                    Box(
                        Modifier.fillMaxSize()
                            .padding(start = if (fullPage) 0.dp else TvRailW)
                            .onFocusChanged { contentFocused[0] = it.hasFocus }
                            // 0.9.5 (big screen): a click / tap on the page closes the open side bar (keys keep working as on TV).
                            .then(if (BuildConfig.IS_TV) Modifier else Modifier.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                        if (sidebar && ev.type == androidx.compose.ui.input.pointer.PointerEventType.Press) sidebar = false
                                    }
                                }
                            })
                            // While the menu is open, keys never go to the page (the search keypad used to keep them):
                            // focus is sent back to the menu.
                            .onPreviewKeyEvent { e ->
                                val dpad = e.key == Key.DirectionUp || e.key == Key.DirectionDown || e.key == Key.DirectionLeft ||
                                    e.key == Key.DirectionRight || e.key == Key.DirectionCenter || e.key == Key.Enter
                                if (sidebar && dpad && e.type == KeyEventType.KeyDown) { railTick.intValue++; true } else false
                            }
                            .focusRequester(contentFr),
                    ) {
                      CompositionLocalProvider(LocalTvEdge provides when (barStyle) { "floating" -> 74.dp; "orbit" -> 82.dp; "hidden" -> 36.dp; else -> TvRailEdge }) {
                        when (tab) {
                            TvTab.Home -> TvHomeScreen(vm, onOpen = openMeta, onPlay = play, onPlayMeta = playMeta, onManualMeta = manualMeta,
                                onOpenCollection = { c, f -> push(TvRoute.Collection(c, f)) }, onLeftEdge = edge, state = homeState)
                            TvTab.Search -> TvSearchScreen(vm, searchQuery, onOpen = openMeta, onLeftEdge = edge)
                            TvTab.Library -> TvLibraryHub(vm, onOpen = openMeta, onPlay = play, onLeftEdge = edge)
                            TvTab.MySpace -> TvMySpaceScreen(vm, onSwitchProfile = { vm.leaveProfile() }, onPlay = play, onLeftEdge = edge)
                            TvTab.Settings -> TvSettingsScreen(vm, settingsIdx, onOpen = { k -> push(TvRoute.Settings(k)) }, onLeftEdge = edge)
                        }
                      }
                    }
                    TvRail(
                        open = sidebar, vm = vm, current = tab, refocus = railTick.intValue, style = barStyle, openStyle = TvLook.openStyle(texts),
                        onSelect = { t ->
                            if (t == tab) closeSidebar()
                            else { returnFocus[0] = null; sidebar = false; tab = t }
                        },
                        onProfile = { sidebar = false; vm.leaveProfile() },
                        onClose = { closeSidebar() },
                    )
                }
            }
        }
        if (profile != null && stack.lastOrNull() !is TvRoute.Player) voteFor?.let { m ->
            key(m.id) { TvVoteCapsule(m, onVote = { v -> vm.vote(m, v) }, onDismiss = { voteFor = null }) }
        }
        if (profile != null && stack.isEmpty() && texts["guideDone"].isNullOrEmpty()) QuickGuide { vm.setText("guideDone", "1") }
        // TV 0.9.0: new profiles (nothing watched or saved yet) choose their look once, after the quick guide.
        if (profile != null && stack.isEmpty() && !texts["guideDone"].isNullOrEmpty() && texts[TvLook.ASKED].isNullOrEmpty()) {
            val prog by vm.progress.collectAsStateWithLifecycle()
            val saved by vm.myList.collectAsStateWithLifecycle()
            if (prog.isEmpty() && saved.isEmpty()) TvLookFirstRun(vm) { vm.setText(TvLook.ASKED, "1") }
            else LaunchedEffect(Unit) { vm.setText(TvLook.ASKED, "1") }
        }
        if (showSplash) TvStartupSplash(onFading = { splashFading = true }) { showSplash = false }
        SnackHost()
    }
}

/**
 * A phone settings page on the TV: centred at a readable width over the dark background, and focus goes to its
 * first control straight away so the remote works at once.
 */
@Composable
private fun TvSettingsPage(vm: AppViewModel, start: String, onClose: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        repeat(5) { if (fr.tryFocus()) return@LaunchedEffect; delay(60) }
    }
    Box(Modifier.fillMaxSize().background(Bg)) {
        Box(Modifier.align(Alignment.TopCenter).widthIn(max = 700.dp).fillMaxSize().focusRequester(fr)) {
            SettingsHost(vm, start, onClose = onClose)
        }
    }
}
