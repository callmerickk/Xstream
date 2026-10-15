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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
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
 * Dark background, the logo pops in, "Xstream" slides in beside it, a short hold, then everything fades. Only layer
 * alpha / scale / translation animate.
 */
@Composable
fun TvStartupSplash(onDone: () -> Unit) {
    val mark = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val out = remember { Animatable(1f) }
    val done by rememberUpdatedState(onDone)

    LaunchedEffect(Unit) {
        withFrameNanos { }
        launch { mark.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 320f)) }
        delay(260)
        word.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        delay(420)
        out.animateTo(0f, tween(320))
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
fun TvProfileGate(vm: AppViewModel, active: Boolean, onPicked: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val texts by vm.texts.collectAsStateWithLifecycle()
    val posters by vm.wallPosters.collectAsStateWithLifecycle()
    LaunchedEffect(active) { if (active) vm.loadWallPosters() }
    val lastId = texts["lastProfile"] ?: ""
    val startIdx = profiles.indexOfFirst { it.id == lastId }.coerceAtLeast(0)
    val frs = remember(profiles.size) { List(profiles.size + 1) { FocusRequester() } }
    var focusedIdx by remember { mutableIntStateOf(-1) }
    var adding by remember { mutableStateOf(false) }
    var cancelled by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf<String?>(null) }
    val allowAuto = remember { !StartupState.autoPickDone }
    val secs = (texts["autoProfileSecs"] ?: Defaults["autoProfileSecs"] ?: "5").toIntOrNull() ?: 5
    val countdown = remember { Animatable(0f) }
    val enter = remember { Animatable(0f) }
    val leave = remember { Animatable(0f) }

    fun pick(p: Profile) {
        if (leaving != null) return
        cancelled = true
        leaving = p.id
    }
    LaunchedEffect(Unit) { StartupState.autoPickDone = true }
    LaunchedEffect(leaving) {
        val id = leaving ?: return@LaunchedEffect
        // Open at once: a short pulse, then the profile (its data is read off the main thread first).
        leave.animateTo(1f, tween(180))
        profiles.firstOrNull { it.id == id }?.let { vm.prewarmProfile(it.id); vm.selectProfile(it) }
        onPicked()
    }
    LaunchedEffect(active) { if (active) enter.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
    LaunchedEffect(active, profiles.size) {
        if (!active || profiles.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        repeat(4) { if (frs.getOrNull(startIdx)?.tryFocus() == true) return@LaunchedEffect; delay(60) }
    }
    LaunchedEffect(active, cancelled, adding, profiles.size) {
        if (!allowAuto || !active || cancelled || adding || secs <= 0 || profiles.isEmpty() || leaving != null) return@LaunchedEffect
        delay(1000)
        countdown.snapTo(0f)
        countdown.animateTo(1f, tween(secs * 1000, easing = LinearEasing))
        profiles.getOrNull(startIdx)?.let { pick(it) }
    }
    // Recomposes once a second (not every frame) for the "in N" text.
    val remaining by remember { derivedStateOf { (secs - (countdown.value * secs).toInt()).coerceAtLeast(1) } }
    val counting = !cancelled && allowAuto && secs > 0 && leaving == null

    val drift = rememberInfiniteTransition(label = "wall")
    val wallY by drift.animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Reverse), label = "wallY")
    val arcY = 330.dp

    Box(
        Modifier.fillMaxSize().background(Color(0xFF0B0B10))
            .graphicsLayer { alpha = 1f - leave.value * 0.9f }
            .onPreviewKeyEvent { cancelled = true; false },
    ) {
        // Poster wall drifting slowly (layer translation only).
        if (posters.isNotEmpty() && active) {
            Column(
                Modifier.fillMaxWidth().requiredWidth(1000.dp).padding(top = 10.dp)
                    .graphicsLayer { alpha = 0.75f * enter.value; translationY = -wallY * 60.dp.toPx() },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                posters.chunked(8).take(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { url ->
                            val req = tvImage(url, 118.dp, 177.dp)
                            AsyncImage(req, null, Modifier.weight(1f).height(177.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1A1A22)),
                                contentScale = ContentScale.Crop)
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(GateFadeV))
        Box(Modifier.fillMaxSize().background(GateFadeH))

        // Blue → pink glow and the curved line.
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = enter.value }) {
            val y = arcY.toPx()
            drawCircle(Brush.radialGradient(listOf(Color(0x8C2896FF), Color.Transparent), Offset(size.width * 0.3f, y + 60.dp.toPx()), size.width * 0.32f),
                size.width * 0.32f, Offset(size.width * 0.3f, y + 60.dp.toPx()))
            drawCircle(Brush.radialGradient(listOf(Color(0x80FF2882), Color.Transparent), Offset(size.width * 0.72f, y + 70.dp.toPx()), size.width * 0.32f),
                size.width * 0.32f, Offset(size.width * 0.72f, y + 70.dp.toPx()))
            val path = Path().apply { moveTo(-20f, y + 40.dp.toPx()); quadraticBezierTo(size.width / 2f, y, size.width + 20f, y + 40.dp.toPx()) }
            val line = Brush.horizontalGradient(listOf(Color(0x332AA0FF), Color(0xFF3FB4FF), Color(0xFFFF3B8D), Color(0x33FF2A6A)))
            val reveal = enter.value
            clipRect(left = size.width / 2f * (1f - reveal), right = size.width / 2f * (1f + reveal)) {
                drawPath(path, line, alpha = 0.25f, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
                drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }

        Row(Modifier.padding(start = 28.dp, top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(AppLook.logoRes(AppLook.logo.value)), null, Modifier.size(30.dp))
            Spacer(Modifier.width(10.dp))
            Wordmark(t("appName"), 20.sp)
        }
        Text(
            t("whoWatching"),
            Modifier.fillMaxWidth().padding(top = 78.dp).graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 10.dp.toPx() },
            fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        )

        // Profiles sitting on the line.
        Row(
            Modifier.fillMaxWidth().padding(top = arcY - 62.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally),
        ) {
            val count = profiles.size + if (profiles.size < 5) 1 else 0
            for (i in 0 until count) {
                val p = profiles.getOrNull(i)
                val pop = remember { Animatable(0f) }
                LaunchedEffect(active) { if (active) { delay(300L + i * 100L); pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f)) } }
                val focused = focusedIdx == i
                val isLeaving = p != null && p.id == leaving
                val sc by animateFloatAsState(if (focused || isLeaving) 1.2f else 1f, spring(dampingRatio = 0.6f, stiffness = 350f), label = "sc")
                Column(
                    Modifier.width(110.dp).graphicsLayer {
                        alpha = pop.value.coerceIn(0f, 1f) * (if (leaving != null && !isLeaving) 1f - leave.value else 1f)
                        translationY = (1f - pop.value) * 16.dp.toPx()
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(104.dp)
                            .focusRequester(frs[i])
                            .onFocusChanged { if (it.isFocused) focusedIdx = i else if (focusedIdx == i) focusedIdx = -1 }
                            .tvCard(52.dp, scale = 1f, ring = false) {
                                if (p == null) { cancelled = true; adding = true } else pick(p)
                            }
                            .graphicsLayer {
                                val l = if (isLeaving) 1f + leave.value * 0.6f else 1f
                                scaleX = sc * l; scaleY = sc * l
                            },
                        Alignment.Center,
                    ) {
                        if (p == null) {
                            Box(
                                Modifier.size(80.dp).clip(CircleShape).background(Color(0x2EFFFFFF))
                                    .then(if (focused) Modifier.border(3.dp, Color.White, CircleShape) else Modifier),
                                Alignment.Center,
                            ) { Icon(Icons.Default.Add, "Add profile", Modifier.size(32.dp)) }
                        } else {
                            ProfileAvatar(vm, p, 80.dp, if (focused || isLeaving) Modifier.border(3.dp, Color.White, CircleShape) else Modifier, animate = false)
                            if (i == startIdx && counting) Canvas(Modifier.size(98.dp)) {
                                val v = countdown.value
                                if (v <= 0f) return@Canvas
                                val sw = 3.5.dp.toPx()
                                drawArc(Color(0x2EFFFFFF), 0f, 360f, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw))
                                drawArc(BrandRed, -90f, 360f * v, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
                            }
                        }
                    }
                    Text(
                        p?.name ?: "Add", Modifier.padding(top = if (focused) 12.dp else 6.dp), fontSize = 14.sp,
                        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (focused) Color.White else Color(0xFFC9CDD6), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // "Opening Rick in 5"
        val name = profiles.getOrNull(startIdx)?.name ?: ""
        Column(
            Modifier.fillMaxWidth().padding(top = arcY + 120.dp).graphicsLayer { alpha = if ((counting && countdown.value > 0f) || leaving != null) 1f else 0f },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                buildAnnotatedString {
                    append("Opening ")
                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) {
                        append(profiles.firstOrNull { it.id == leaving }?.name ?: name)
                    }
                    if (leaving == null) { append(" in "); withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append("$remaining") } }
                    else append("…")
                },
                fontSize = 14.sp, color = Color(0xFFC9CDD6),
            )
            if (leaving == null) Text("Press any button to choose", Modifier.padding(top = 4.dp), fontSize = 12.sp, color = Color(0xFF7C8394))
        }
    }
    if (adding) ProfileEditor(vm, null) { adding = false }
}

private val GateFadeV = Brush.verticalGradient(
    0f to Color(0xB30B0B10), 0.3f to Color(0x4D0B0B10), 0.55f to Color(0xCC0B0B10), 0.7f to Color(0xFF0B0B10), 1f to Color(0xFF0B0B10),
)
private val GateFadeH = Brush.horizontalGradient(0f to Color(0x990B0B10), 0.25f to Color.Transparent, 0.75f to Color.Transparent, 1f to Color(0x990B0B10))
