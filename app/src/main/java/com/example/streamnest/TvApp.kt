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
    data class Detail(val meta: Meta, val autoPlay: Boolean = false) : TvRoute { var played = false }
    data class Person(val p: Screen.Person) : TvRoute
    data class Player(val p: Screen.Player) : TvRoute
    data class Collection(val c: NCollection, val f: NFolder) : TvRoute
    data class Settings(val start: String) : TvRoute
}

private tailrec fun Context.tvActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.tvActivity()
    else -> null
}

/**
 * Xstream TV shell: splash, "Who's watching?", then the sections (Home / Search / Library / My Space / Settings) with
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
        ActLog.screen = if (profile == null) "TV Who's watching" else "TV " + (top?.let { it::class.simpleName } ?: tab.name)
        ActLog.add("SCREEN", ActLog.screen + (top?.let { " · " + it.toString().take(80) } ?: ""))
    }
    val texts by vm.texts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(profile?.id) { if (profile != null) { runCatching { BuiltIns.sync(vm) }; runCatching { AppLook.fetch(vm.appContext) } } }
    val ctx = LocalContext.current
    val focusManager = LocalFocusManager.current
    val homeState = remember(profile?.id) { TvHomeState() }
    val searchQuery = remember(profile?.id) { mutableStateOf("") }
    val opening = remember { arrayOfNulls<Job>(1) }
    var voteFor by remember { mutableStateOf<Meta?>(null) }
    var showSplash by remember { mutableStateOf(!StartupState.splashShown && isOn(texts["splash"] ?: "yes")) }
    var gateOpen by rememberSaveable { mutableStateOf(!Cloud.decided.value) }

    val push: (TvRoute) -> Unit = { stack.add(it) }
    val pop: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex); Unit }
    // Collection items can have TMDB ids: they are turned into IMDb ids first (normal addon titles return at once).
    fun openTitle(m: Meta, autoPlay: Boolean) {
        if (opening[0]?.isActive != true) opening[0] = scope.launch {
            val r = runCatching { vm.resolveForOpen(m) }.getOrNull()
            if (r != null) stack.add(TvRoute.Detail(r, autoPlay)) else Snack.show("Couldn't open ${m.name}")
        }
    }
    val openMeta: (Meta) -> Unit = { m -> openTitle(m, false) }
    val playMeta: (Meta) -> Unit = { m -> openTitle(m, true) }
    val settingsIdx = remember { mutableIntStateOf(0) }
    val play: (Screen.Player) -> Unit = { stack.add(TvRoute.Player(it)) }

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

    BackHandler(enabled = profile != null && !showSplash && !gateOpen) {
        when {
            stack.isNotEmpty() -> pop()
            sidebar -> if (tab == TvTab.Home) ctx.tvActivity()?.finish() else closeSidebar()
            tab != TvTab.Home -> tab = TvTab.Home
            else -> openSidebar(null)
        }
    }

    CompositionLocalProvider(LocalTexts provides texts) {
        LaunchedEffect(profile == null) { if (profile == null) { stack.clear(); sidebar = false } }
        // After switching sections, make sure something on the new screen has focus.
        LaunchedEffect(tab, stack.isEmpty(), profile?.id) {
            if (profile == null || stack.isNotEmpty()) return@LaunchedEffect
            delay(160)
            if (!sidebar && !contentFocused[0]) contentFr.tryFocus()
        }
        Box(Modifier.fillMaxSize().background(Bg)) {
            if (profile == null && gateOpen) CloudGate(onDone = { gateOpen = false })
            else if (profile == null && texts["streamMode"] == null) ModeChooser(vm) {}
            else if (profile == null) TvProfileGate(vm, active = !showSplash) { tab = TvTab.Home }
            else when (val top = stack.lastOrNull()) {
                is TvRoute.Player -> key(top) {
                    val p = top.p
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
                        autoPlay = top.autoPlay && !top.played, onAutoPlayed = { top.played = true })
                }
                is TvRoute.Person -> key(top) { Box(Modifier.fillMaxSize().background(Bg)) { PersonScreen(vm, top.p, onBack = pop, onOpen = openMeta) } }
                is TvRoute.Collection -> key(top) { TvCollectionScreen(vm, top.c, top.f, onBack = pop, onOpen = openMeta) }
                is TvRoute.Settings -> key(top) { TvSettingsPage(vm, top.start, onClose = pop) }
                null -> {
                    val edge: (FocusRequester) -> Unit = { fr -> openSidebar(fr) }
                    Box(
                        Modifier.fillMaxSize()
                            .padding(start = TvRailW)
                            .onFocusChanged { contentFocused[0] = it.hasFocus }
                            // While the menu is open, keys never go to the page (the search keypad used to keep them):
                            // focus is sent back to the menu.
                            .onPreviewKeyEvent { e ->
                                val dpad = e.key == Key.DirectionUp || e.key == Key.DirectionDown || e.key == Key.DirectionLeft ||
                                    e.key == Key.DirectionRight || e.key == Key.DirectionCenter || e.key == Key.Enter
                                if (sidebar && dpad && e.type == KeyEventType.KeyDown) { railTick.intValue++; true } else false
                            }
                            .focusRequester(contentFr),
                    ) {
                      CompositionLocalProvider(LocalTvEdge provides TvRailEdge) {
                        when (tab) {
                            TvTab.Home -> TvHomeScreen(vm, onOpen = openMeta, onPlay = play, onPlayMeta = playMeta,
                                onOpenCollection = { c, f -> push(TvRoute.Collection(c, f)) }, onLeftEdge = edge, state = homeState)
                            TvTab.Search -> TvSearchScreen(vm, searchQuery, onOpen = openMeta, onLeftEdge = edge)
                            TvTab.Library -> TvLibraryHub(vm, onOpen = openMeta, onPlay = play, onLeftEdge = edge)
                            TvTab.MySpace -> TvMySpaceScreen(vm, onSwitchProfile = { vm.leaveProfile() }, onPlay = play, onLeftEdge = edge)
                            TvTab.Settings -> TvSettingsScreen(vm, settingsIdx, onOpen = { k -> push(TvRoute.Settings(k)) }, onLeftEdge = edge)
                        }
                      }
                    }
                    TvRail(
                        open = sidebar, vm = vm, current = tab, refocus = railTick.intValue,
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
        if (showSplash) TvStartupSplash { showSplash = false }
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
