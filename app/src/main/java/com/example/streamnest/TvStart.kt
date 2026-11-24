package com.example.streamnest

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.runtime.key
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ───────────── Start animation (the phone's, tuned for Fire TV) ─────────────

private val SplashBase = Brush.linearGradient(listOf(Color(0xFF14060C), Color(0xFF1A0718), Color(0xFF07040A)))
private val SplashRed = Brush.radialGradient(listOf(Color(0x8CE50914), Color.Transparent))
private val SplashPurple = Brush.radialGradient(listOf(Color(0x737828BE), Color.Transparent))

/**
 * TV 0.7 start: light on purpose (the blurred red / purple blobs and the 14× zooming X made Fire TV sticks stutter).
 * Dark background, the logo pops in, "StreamX" slides in beside it, a short hold, then everything fades. Only layer
 * alpha / scale / translation animate.
 */
@Composable
fun TvStartupSplash(onFading: () -> Unit = {}, onDone: () -> Unit) {
    // TV 0.9.14: the logo is already on screen from the app's window (no black wait), so it starts visible.
    val mark = remember { Animatable(1f) }
    val word = remember { Animatable(0f) }
    val out = remember { Animatable(1f) }
    val done by rememberUpdatedState(onDone)

    LaunchedEffect(Unit) {
        withFrameNanos { }
        delay(120)
        word.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        delay(380)
        // Who's watching starts drawing underneath, then the splash fades over it (a cross-fade, never an empty screen).
        onFading()
        delay(120)
        out.animateTo(0f, tween(520, easing = FastOutSlowInEasing))
        StartupState.splashShown = true
        done()
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF07080C)).graphicsLayer { alpha = out.value }) {
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            val markMod = Modifier.graphicsLayer {
                val v = mark.value
                alpha = v.coerceIn(0f, 1f)
                scaleX = 0.7f + 0.3f * v; scaleY = scaleX
            }
            if (AppLook.logo.value == "signal") Box(markMod) { SignalMark(78.dp, "broadcast", standalone = true) }
            else Image(painterResource(AppLook.logoRes(AppLook.logo.value)), null, Modifier.size(78.dp).then(markMod))
            Spacer(Modifier.width(16.dp))
            Box(Modifier.graphicsLayer { alpha = word.value; translationX = (1f - word.value) * -22.dp.toPx() }) {
                Wordmark(t("appName"), 46.sp)
            }
        }
    }
}

// ───────────── "Who's watching?" for the remote (the phone's look, landscape) ─────────────

/**
 * The phone's "Who's watching?": a slowly drifting poster wall, the blue → pink glow with the curved line the profiles
 * sit on, and a red ring that counts down around the last profile and opens it by itself (first start only).
 * Remote: ◀ ▶ moves between profiles, OK opens, any key stops the countdown.
 */
@Composable
fun TvProfileGate(vm: AppViewModel, active: Boolean, onResume: ((Progress) -> Unit)? = null, onPicked: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val texts by vm.texts.collectAsStateWithLifecycle()
    // TV 0.9.0: Add first, then the other profiles, the main profile always last on the right.
    val slots: List<Profile?> = remember(profiles) {
        val main = profiles.firstOrNull { it.id == "p1" }
        (if (profiles.size < 5) listOf<Profile?>(null) else emptyList()) + profiles.filter { it.id != "p1" } + listOfNotNull(main)
    }
    val lastId = texts["lastProfile"] ?: ""
    val startIdx = slots.indexOfFirst { it != null && it.id == lastId }.takeIf { it >= 0 } ?: slots.lastIndex
    val frs = remember(slots.size) { List(slots.size) { FocusRequester() } }
    val resumeFr = remember { FocusRequester() }
    var focusedIdx by remember { mutableIntStateOf(-1) }
    var adding by remember { mutableStateOf(false) }
    var cancelled by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf<String?>(null) }
    var resumeItem by remember { mutableStateOf<Progress?>(null) }
    val allowAuto = remember { !StartupState.autoPickDone }
    val secs = (texts["autoProfileSecs"] ?: Defaults["autoProfileSecs"] ?: "5").toIntOrNull() ?: 5
    val countdown = remember { Animatable(0f) }
    val enter = remember { Animatable(0f) }
    val leave = remember { Animatable(0f) }

    // The profile whose Continue watching is shown: the one with focus, else the last one that had it (TV 0.9.4: it
    // went back to the main profile as soon as focus moved up to the cards), else the one that opens by itself.
    var lastIdx by remember(slots.size) { mutableIntStateOf(startIdx) }
    LaunchedEffect(focusedIdx) { if (focusedIdx >= 0) lastIdx = focusedIdx }
    val shownIdx = if (focusedIdx >= 0) focusedIdx else lastIdx
    val shown = slots.getOrNull(shownIdx)
    var stack by remember { mutableStateOf<List<Progress>>(emptyList()) }
    var stackIdx by remember { mutableIntStateOf(0) }
    val stackFr = remember { FocusRequester() }
    val stripFr = remember { FocusRequester() }
    var stackFocused by remember { mutableStateOf(false) }
    var stripFocused by remember { mutableStateOf(false) }
    LaunchedEffect(shown?.id) {
        val p = shown
        if (p == null || ProfileLock.hasPin(p.id)) { stack = emptyList(); return@LaunchedEffect }
        delay(120)
        stack = vm.progressOf(p.id).filter { !it.finished() }.sortedByDescending { it.updated }.take(5)
        stackIdx = 0
    }

    fun pick(p: Profile, resume: Progress? = null) {
        if (leaving != null) return
        cancelled = true
        resumeItem = resume
        leaving = p.id
    }
    LaunchedEffect(Unit) { StartupState.autoPickDone = true }
    LaunchedEffect(leaving) {
        val id = leaving ?: return@LaunchedEffect
        leave.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        profiles.firstOrNull { it.id == id }?.let { vm.prewarmProfile(it.id); vm.selectProfile(it) }
        val r = resumeItem
        if (r != null && onResume != null) onResume(r) else onPicked()
    }
    LaunchedEffect(focusedIdx) { delay(200); slots.getOrNull(focusedIdx)?.let { runCatching { vm.prewarmProfile(it.id) } } }
    LaunchedEffect(active) { if (active) enter.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    LaunchedEffect(active, slots.size) {
        if (!active || slots.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        repeat(4) { if (frs.getOrNull(startIdx)?.tryFocus() == true) return@LaunchedEffect; delay(60) }
    }
    LaunchedEffect(active, cancelled, adding, slots.size) {
        val target = slots.getOrNull(startIdx)
        if (!allowAuto || !active || cancelled || adding || secs <= 0 || target == null || leaving != null) return@LaunchedEffect
        delay(1000)
        countdown.snapTo(0f)
        countdown.animateTo(1f, tween(secs * 1000, easing = LinearEasing))
        pick(target)
    }
    val remaining by remember { derivedStateOf { (secs - (countdown.value * secs).toInt()).coerceAtLeast(1) } }
    val counting = !cancelled && allowAuto && secs > 0 && leaving == null && slots.getOrNull(startIdx) != null
    val accent = Accent

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0A0C17), Color(0xFF0F1014))))
            // TV 0.9.14: picking a profile no longer dims everything to grey: a short zoom, then Home fades in.
            .graphicsLayer { alpha = 1f - leave.value * 0.3f; val sc = 1f + leave.value * 0.04f; scaleX = sc; scaleY = sc }
            .onPreviewKeyEvent { cancelled = true; false },
    ) {
        // Stars, the red glow and the glowing curved ground the profiles stand on (a few shapes: cheap on any box).
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = enter.value }) {
            val w = size.width; val h = size.height
            GateStars.forEach { (x, y, r) -> drawCircle(Color.White, r * density, Offset(w * x, h * y), alpha = 0.55f) }
            val y = h * 0.73f
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.26f), Color.Transparent), Offset(w / 2f, y), w * 0.42f), w * 0.42f, Offset(w / 2f, y))
            val ground = Path().apply { moveTo(0f, y + h * 0.05f); quadraticTo(w / 2f, y - h * 0.05f, w, y + h * 0.05f); lineTo(w, h); lineTo(0f, h); close() }
            drawPath(ground, Brush.verticalGradient(listOf(Color(0xFF140E1A), Color(0xFF06070B)), y - h * 0.05f, h))
            val edge = Path().apply { moveTo(0f, y + h * 0.05f); quadraticTo(w / 2f, y - h * 0.05f, w, y + h * 0.05f) }
            val line = Brush.horizontalGradient(listOf(accent.copy(alpha = 0.1f), accent, accent.copy(alpha = 0.1f)))
            drawPath(edge, line, alpha = 0.3f, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
            drawPath(edge, line, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        }

        Row(Modifier.padding(start = 42.dp, top = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(AppLook.logoRes(AppLook.logo.value)), null, Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Wordmark(t("appName"), 19.sp)
        }

        // "Opening Rick in 5 · ◀ ▶ change": a small pill top-right (the profiles never move).
        if (counting || leaving != null) {
            val name = profiles.firstOrNull { it.id == leaving }?.name ?: slots.getOrNull(startIdx)?.name ?: ""
            Row(
                Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 40.dp)
                    .graphicsLayer { alpha = if (countdown.value > 0f || leaving != null) 1f else 0f }
                    .clip(CircleShape).background(Color(0x12FFFFFF)).border(1.dp, Color(0x24FFFFFF), CircleShape)
                    .padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(15.dp)) {
                    val sw = 1.8.dp.toPx()
                    drawArc(Color(0x33FFFFFF), 0f, 360f, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw))
                    drawArc(accent, -90f, 360f * (1f - countdown.value), false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
                }
                Spacer(Modifier.width(8.dp))
                Text(buildAnnotatedString {
                    append("Opening ")
                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append(name) }
                    if (leaving == null) append(" in $remaining s") else append("…")
                }, fontSize = 11.sp, color = Color(0xFFD7DAE2))
                if (leaving == null) {
                    Box(Modifier.padding(horizontal = 8.dp).size(1.dp, 12.dp).background(Color(0x2EFFFFFF)))
                    Text("◀ ▶ change", fontSize = 10.sp, color = Color(0xFF8A90A0))
                }
            }
        }

        val front = stack.getOrNull(stackIdx.coerceIn(0, (stack.size - 1).coerceAtLeast(0)))
        // TV 0.9.4: the front card's rating, genres and story (Cinemeta, kept in memory — the player then has it too).
        val frontMeta by produceState<Meta?>(null, front?.key) {
            val k = front?.key ?: run { value = null; return@produceState }
            delay(900)   // TV 0.9.6: after the posters (they came late while this loaded)
            value = runCatching { PluginSources.metaFor(if (Regex(":\\d+:\\d+$").containsMatchIn(k)) "series" else "movie", k) }.getOrNull()
        }
        if (front != null && shown != null) {
            Text("CONTINUE WATCHING · ${shown.name.uppercase()}", Modifier.padding(start = 42.dp, top = 70.dp), fontSize = 10.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = Color(0xFFFF6A5C))
            // TV 0.9.4: a bigger stack in the middle between the label and the ground. Remote: ▲ from the profiles = the
            // strip of small cards (◀ ▶ change the big card), ▲ again = the big card, ▶ = Resume, ▼ = back down.
            val n = stack.size
            Box(Modifier.padding(start = 96.dp, top = 96.dp).size(480.dp, 246.dp).graphicsLayer { alpha = enter.value }) {
                stack.getOrNull((stackIdx + 1) % n)?.takeIf { n > 1 }?.let { GateCard(it, Modifier.offset(x = 0.dp, y = 16.dp).size(360.dp, 203.dp).graphicsLayer { rotationZ = -6f; alpha = 0.5f }, small = true) }
                stack.getOrNull((stackIdx + 2) % n)?.takeIf { n > 2 }?.let { GateCard(it, Modifier.offset(x = 120.dp, y = 16.dp).size(360.dp, 203.dp).graphicsLayer { rotationZ = 6f; alpha = 0.5f }, small = true) }
                key(front.key) {
                    GateCard(front, Modifier.offset(x = 40.dp, y = 0.dp).size(400.dp, 225.dp)
                        .focusRequester(stackFr)
                        .onFocusChanged { stackFocused = it.isFocused }
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionLeft -> { if (n > 1) stackIdx = (stackIdx - 1 + n) % n; true }
                                Key.DirectionRight -> { resumeFr.tryFocus(); true }
                                Key.DirectionDown -> { if (n > 1) stripFr.tryFocus() else frs.getOrNull(shownIdx)?.tryFocus(); true }
                                Key.DirectionUp -> true
                                else -> false
                            }
                        }
                        .tvCard(12.dp, scale = 1.03f, ring = false) { pick(shown, front) }
                        .border(if (stackFocused) 3.dp else 2.dp, if (stackFocused) Color.White else Color(0x99FFFFFF), RoundedCornerShape(12.dp)), small = false)
                }
            }
            // The small cards: one strip the remote selects as a whole; ◀ ▶ change the big card.
            if (n > 1) Row(Modifier.padding(start = 136.dp, top = 352.dp).width(400.dp).graphicsLayer { alpha = enter.value }
                .focusRequester(stripFr)
                .onFocusChanged { stripFocused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { stackIdx = (stackIdx - 1 + n) % n; true }
                        Key.DirectionRight -> { stackIdx = (stackIdx + 1) % n; true }
                        Key.DirectionUp -> { stackFr.tryFocus(); true }
                        Key.DirectionDown -> { frs.getOrNull(shownIdx)?.tryFocus(); true }
                        else -> false
                    }
                }
                .tvCard(8.dp, scale = 1f, ring = false) { pick(shown, front) }
                .clip(RoundedCornerShape(8.dp)).background(if (stripFocused) Color(0x1FFFFFFF) else Color.Transparent).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", Modifier.padding(end = 2.dp), fontSize = 16.sp, color = if (stripFocused) Color.White else Color(0x80FFFFFF))
                stack.forEachIndexed { i, it -> GateThumb(it, i == stackIdx) }
                Text("›", Modifier.padding(start = 2.dp), fontSize = 16.sp, color = if (stripFocused) Color.White else Color(0x80FFFFFF))
                Text("${stackIdx + 1} / $n", Modifier.padding(start = 6.dp), fontSize = 10.sp, color = if (stripFocused) Color.White else Color(0xFF8A90A0))
            }
            Column(Modifier.padding(start = 608.dp, top = 112.dp).width(320.dp)) {
                Text(front.title.substringBefore("  S").trim(), fontSize = 28.sp, lineHeight = 31.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val fm = frontMeta
                val facts = listOfNotNull(
                    fm?.imdbRating?.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" },
                    fm?.releaseInfo?.takeIf { it.isNotBlank() },
                ) + fm?.genres.orEmpty().take(3)
                if (facts.isNotEmpty()) Text(facts.joinToString("  •  "), Modifier.padding(top = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE6E8EE), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val epM = Regex(":(\\d+):(\\d+)$").find(front.key)
                val epName = epM?.let { m -> fm?.videos?.firstOrNull { it.id == front.key }?.let { it.title ?: it.name } }
                val ep = epM?.let { "S${it.groupValues[1]} E${it.groupValues[2]}" + (epName?.let { n -> " · $n" } ?: "") + " · " } ?: ""
                Text(ep + cwTimeLeft(front), Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Color(0xFFC3C7D6), maxLines = 1, overflow = TextOverflow.Ellipsis)
                fm?.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, Modifier.padding(top = 8.dp), fontSize = 12.sp, lineHeight = 17.sp, color = Color(0xFFB9BDC9), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(
                    Modifier.padding(top = 16.dp)
                        .focusRequester(resumeFr)
                        .focusProperties { down = frs.getOrNull(shownIdx) ?: FocusRequester.Default; left = stackFr; up = stackFr }
                        .tvCard(19.dp, scale = 1.05f) { pick(shown, front) }
                        .clip(CircleShape).background(Color.White).padding(horizontal = 20.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp), tint = Color.Black)
                    Spacer(Modifier.width(4.dp))
                    Text("Resume", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        } else {
            Text(t("whoWatching"), Modifier.fillMaxWidth().padding(top = 150.dp).graphicsLayer { alpha = enter.value },
                fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }

        // Profiles in a line on the ground — the one with focus gets a glowing red ring (it never lifts up).
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(34.dp),
            verticalAlignment = Alignment.Top,
        ) {
            slots.forEachIndexed { i, p ->
                val focused = focusedIdx == i
                val isLeaving = p != null && p.id == leaving
                val sel = focused || isLeaving
                Column(
                    Modifier.width(72.dp).graphicsLayer { alpha = (if (sel) 1f else 0.62f) * enter.value * (if (leaving != null && !isLeaving) 1f - leave.value else 1f) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(64.dp)
                            .focusRequester(frs[i])
                            .then(if (front != null) Modifier.focusProperties { up = if (stack.size > 1) stripFr else stackFr } else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedIdx = i else if (focusedIdx == i) focusedIdx = -1 }
                            .tvCard(32.dp, scale = 1f, ring = false) { if (p == null) { cancelled = true; adding = true } else pick(p) }
                            .drawBehind {
                                if (!sel) return@drawBehind
                                val c = Offset(size.width / 2f, size.height / 2f)
                                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.45f), Color.Transparent), c, size.width * 0.85f), size.width * 0.85f, c)
                                drawCircle(accent, size.width / 2f + 2.dp.toPx(), c, style = Stroke(2.5.dp.toPx()))
                            },
                        Alignment.Center,
                    ) {
                        if (p == null) Box(
                            Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF1C1E26)).border(1.dp, Color(0x59FFFFFF), CircleShape),
                            Alignment.Center,
                        ) { Icon(Icons.Default.Add, "Add profile", Modifier.size(26.dp), tint = Color(0xFFCFD2DC)) }
                        else {
                            ProfileAvatar(vm, p, 56.dp, animate = false)
                            if (p.id == "p1") Box(
                                Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(accent).border(1.5.dp, Color(0xFF0F1014), CircleShape),
                                Alignment.Center,
                            ) { Icon(Icons.Default.Star, null, Modifier.size(10.dp), tint = Color.White) }
                        }
                    }
                    Text(p?.name ?: "Add", Modifier.padding(top = 6.dp), fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                        color = if (sel) Color.White else Color(0xFFC9CDD6), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Box(Modifier.padding(top = 4.dp).size(17.dp, 2.5.dp).clip(CircleShape).background(if (sel) accent else Color.Transparent))
                }
            }
        }
    }
    if (adding) ProfileEditor(vm, null) { adding = false }
}

/** Fixed star positions (fractions of the screen, radius in dp). */
private val GateStars = listOf(
    Triple(0.08f, 0.12f, 0.9f), Triple(0.22f, 0.30f, 0.6f), Triple(0.40f, 0.08f, 0.9f), Triple(0.63f, 0.22f, 0.6f),
    Triple(0.78f, 0.10f, 0.9f), Triple(0.92f, 0.28f, 0.6f), Triple(0.52f, 0.40f, 0.5f), Triple(0.14f, 0.46f, 0.5f),
    Triple(0.86f, 0.44f, 0.5f), Triple(0.33f, 0.52f, 0.4f),
)

/** A small card of the strip under the stack (no title; the one in front has a white frame). */
@Composable
private fun GateThumb(p: Progress, on: Boolean) {
    val req = tvImage(p.image, 56.dp, 32.dp)
    Box(Modifier.size(if (on) 56.dp else 48.dp, if (on) 32.dp else 27.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF15161C))
        .then(if (on) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(5.dp)) else Modifier).graphicsLayer { alpha = if (on) 1f else 0.55f }) {
        if (req != null) AsyncImage(req, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(p.fraction().coerceIn(0.03f, 1f)).height(2.dp).background(BrandRed))
    }
}

/** One landscape card of the profile page stack: picture, a soft shade, the title and the red progress line. */
@Composable
private fun GateCard(p: Progress, modifier: Modifier, small: Boolean) {
    val req = tvImage(p.image, if (small) 240.dp else 390.dp, if (small) 135.dp else 219.dp)   // TV 0.9.6: the dim back cards smaller
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.clip(shape).background(Color(0xFF15161C))) {
        if (req != null) AsyncImage(req, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xBF000000))))
        val ep = Regex(":(\\d+):(\\d+)$").find(p.key)?.let { "S${it.groupValues[1]} · E${it.groupValues[2]}" }
        if (!small && ep != null) Text(ep, Modifier.padding(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000))
            .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(p.title.substringBefore("  S").trim(), Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp, end = 12.dp),
            fontSize = if (small) 11.sp else 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(p.fraction().coerceIn(0.03f, 1f)).height(3.5.dp).background(BrandRed))
    }
}

private val GateFadeV = Brush.verticalGradient(
    0f to Color(0xB30B0B10), 0.3f to Color(0x4D0B0B10), 0.55f to Color(0xCC0B0B10), 0.7f to Color(0xFF0B0B10), 1f to Color(0xFF0B0B10),
)
private val GateFadeH = Brush.horizontalGradient(0f to Color(0x990B0B10), 0.25f to Color.Transparent, 0.75f to Color.Transparent, 1f to Color(0x990B0B10))
