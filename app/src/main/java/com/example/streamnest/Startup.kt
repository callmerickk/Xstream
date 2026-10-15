package com.example.streamnest

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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

/** The splash plays once per app start (not when coming back from the background). */
object StartupState { var splashShown = false; var autoPickDone = false }

// ───────────── Startup animation ─────────────

/**
 * The red X of the start animation, drawn ONCE at 6× size (with its glow) into a picture on the graphics chip, then only
 * that picture is scaled and faded. Before, the text and its blurred shadow were redrawn bigger every frame (up to 26×),
 * which stuttered and sometimes never finished smoothly.
 */
@Composable
fun ZoomX(fontSp: Float, appear: () -> Float, zoom: () -> Float, alpha: () -> Float) {
    val k = 6f
    Box(Modifier.size((fontSp * 0.62f).dp, (fontSp * 1.2f).dp), Alignment.Center) {
        Box(
            Modifier.wrapContentSize(unbounded = true).graphicsLayer {
                val v = appear()
                this.alpha = v.coerceIn(0f, 1f) * alpha()
                val s = (0.4f + 0.6f * v) * zoom() / k
                scaleX = s; scaleY = s
                compositingStrategy = CompositingStrategy.Offscreen
            },
            Alignment.Center,
        ) {
            Box(Modifier.size((fontSp * k * 1.5f).dp).background(Brush.radialGradient(listOf(Color(0x99E50914), Color(0x33E50914), Color.Transparent))))
            Text("X", fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = (fontSp * k).sp, color = BrandRed)
        }
    }
}

/**
 * Black → red/purple glow, the logo pops, the red X pops, "stream" slides out from behind it.
 * Then the logo and "stream" fade, the red X zooms towards you while fading, and the glow fades out.
 */
@Composable
fun StartupSplash(onDone: () -> Unit) {
    val bg = remember { Animatable(0f) }
    val mark = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val x = remember { Animatable(0f) }
    val zoom = remember { Animatable(1f) }
    val out = remember { Animatable(1f) }      // logo + "stream" fade
    val xAlpha = remember { Animatable(1f) }
    val done by rememberUpdatedState(onDone)

    LaunchedEffect(Unit) {
        launch { bg.animateTo(1f, tween(600)) }
        launch { delay(260); mark.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f)) }
        delay(720)
        launch { x.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 300f)) }
        delay(240)
        word.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
        delay(520)
        launch { out.animateTo(0f, tween(320)) }
        launch { delay(250); bg.animateTo(0f, tween(800)) }
        launch { delay(260); xAlpha.animateTo(0f, tween(700)) }
        zoom.animateTo(26f, tween(950, easing = androidx.compose.animation.core.CubicBezierEasing(0.6f, 0f, 0.8f, 0.4f)))
        StartupState.splashShown = true
        done()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = bg.value }) {
            drawRect(Brush.linearGradient(listOf(Color(0xFF14060C), Color(0xFF1A0718), Color(0xFF07040A)), Offset.Zero, Offset(size.width, size.height)))
            drawRect(Brush.radialGradient(listOf(Color(0x8CE50914), Color.Transparent), Offset(size.width * 0.2f, size.height * 0.85f), size.maxDimension * 0.75f))
            drawRect(Brush.radialGradient(listOf(Color(0x737828BE), Color.Transparent), Offset(size.width * 0.85f, size.height * 0.15f), size.maxDimension * 0.55f))
        }
        Row(Modifier.align(Alignment.Center).offset(y = (-20).dp), verticalAlignment = Alignment.CenterVertically) {
            val lookLogo = AppLook.logo.value
            val markMod = Modifier.graphicsLayer {
                val v = mark.value
                alpha = (v.coerceIn(0f, 1f)) * out.value
                scaleX = 0.5f + 0.5f * v; scaleY = scaleX
            }
            // Signal X draws itself and its waves ripple out; any other logo pops in as before.
            if (lookLogo == "signal") Box(markMod) { SignalMark(76.dp, "broadcast", standalone = true) }
            else Image(painterResource(AppLook.logoRes(lookLogo)), null, Modifier.size(76.dp).then(markMod))
            Spacer(Modifier.width(14.dp))
            ZoomX(44f, { x.value }, { zoom.value }, { xAlpha.value })
            Text(
                "stream",
                Modifier.graphicsLayer { alpha = word.value * out.value; translationX = (1f - word.value) * -18.dp.toPx() },
                fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = 44.sp, letterSpacing = (-1).sp, color = Color.White,
            )
        }
    }
}

// ───────────── "Who's watching?" (Hotstar TV style) ─────────────

@Composable
fun WhoIsWatchingScreen(vm: AppViewModel, active: Boolean = true, onPicked: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val texts by vm.texts.collectAsStateWithLifecycle()
    val posters by vm.wallPosters.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadWallPosters() }
    var editMode by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var adding by remember { mutableStateOf(false) }

    val lastId = texts["lastProfile"] ?: ""
    val focusId = remember(profiles, lastId) { (profiles.firstOrNull { it.id == lastId } ?: profiles.firstOrNull())?.id }
    val secs = (texts["autoProfileSecs"] ?: Defaults["autoProfileSecs"] ?: "5").toIntOrNull() ?: 5
    var cancelled by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf<String?>(null) }
    val countdown = remember { Animatable(0f) }
    // The countdown only runs on the first "Who's watching?" after the app opens, not after "Switch profile".
    val allowAuto = remember { !StartupState.autoPickDone && JoinIntent.code.value == null }
    LaunchedEffect(Unit) { StartupState.autoPickDone = true }
    val leave = remember { Animatable(0f) }
    val enter = remember { Animatable(0f) }

    fun pick(p: Profile) {
        if (leaving != null) return
        cancelled = true
        // A locked profile asks for its PIN (or fingerprint) first.
        ProfileLock.ask(p) { leaving = p.id }
    }
    LaunchedEffect(leaving) {
        val id = leaving ?: return@LaunchedEffect
        leave.animateTo(1f, tween(520))
        profiles.firstOrNull { it.id == id }?.let { vm.selectProfile(it) }
        onPicked()
    }
    LaunchedEffect(active) { if (active) enter.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    // Countdown ring: if nobody picks another profile, the last one opens by itself.
    LaunchedEffect(active, focusId, cancelled, editMode, secs) {
        if (!allowAuto || !active || cancelled || editMode || secs <= 0 || focusId == null || leaving != null) return@LaunchedEffect
        delay(1100)
        countdown.snapTo(0f)
        countdown.animateTo(1f, tween(secs * 1000, easing = LinearEasing))
        profiles.firstOrNull { it.id == focusId }?.let { pick(it) }
    }
    val remaining = (secs - (countdown.value * secs).toInt()).coerceAtLeast(1)

    val drift = rememberInfiniteTransition(label = "wall")
    val wallY by drift.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "wallY")
    val h = LocalConfiguration.current.screenHeightDp.dp
    val arcY = h * 0.62f

    Box(
        Modifier.fillMaxSize().background(Color(0xFF0B0B10))
            .graphicsLayer { alpha = (1f - leave.value * 0.9f) }
            // Touching the screen anywhere stops the auto-open timer.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); if (countdown.value > 0f) cancelled = true } },
    ) {
        // Poster wall drifting slowly behind everything.
        if (posters.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().requiredWidth(LocalConfiguration.current.screenWidthDp.dp + 40.dp).padding(top = 24.dp)
                    .graphicsLayer { alpha = 0.8f * enter.value; translationY = -wallY * 70.dp.toPx() },
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                posters.chunked(5).take(6).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        row.forEach { url ->
                            AsyncImage(url, null, Modifier.weight(1f).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1A1A22)), contentScale = ContentScale.Crop)
                        }
                    }
                }
            }
        }
        // Dim + fade the wall into the background (darker at the top and towards the profiles).
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color(0xB30B0B10), 0.3f to Color(0x330B0B10), 0.55f to Color(0xBF0B0B10), 0.7f to Color(0xFF0B0B10), 1f to Color(0xFF0B0B10))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0x990B0B10), 0.25f to Color.Transparent, 0.75f to Color.Transparent, 1f to Color(0x990B0B10))))

        // Blue → pink glow and the curved line the profiles sit on.
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = enter.value }) {
            val y = arcY.toPx()
            drawCircle(Brush.radialGradient(listOf(Color(0x8C2896FF), Color.Transparent), Offset(size.width * 0.28f, y + 50.dp.toPx()), size.width * 0.5f),
                size.width * 0.5f, Offset(size.width * 0.28f, y + 50.dp.toPx()))
            drawCircle(Brush.radialGradient(listOf(Color(0x80FF2882), Color.Transparent), Offset(size.width * 0.74f, y + 60.dp.toPx()), size.width * 0.5f),
                size.width * 0.5f, Offset(size.width * 0.74f, y + 60.dp.toPx()))
            val path = Path().apply {
                moveTo(-20f, y + 32.dp.toPx()); quadraticBezierTo(size.width / 2f, y, size.width + 20f, y + 32.dp.toPx())
            }
            val line = Brush.horizontalGradient(listOf(Color(0x332AA0FF), Color(0xFF3FB4FF), Color(0xFFFF3B8D), Color(0x33FF2A6A)))
            val reveal = enter.value
            clipRect(left = size.width / 2f * (1f - reveal), right = size.width / 2f * (1f + reveal)) {
                drawPath(path, line, alpha = 0.25f, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
                drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }

        // Top bar: small logo, Edit.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 18.dp, end = 10.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(AppLook.logoRes(AppLook.logo.value)), null, Modifier.size(30.dp))
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable { editMode = !editMode; cancelled = true }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (editMode) Icons.Default.Check else Icons.Default.Edit, null, Modifier.size(18.dp), tint = Color(0xFFB6BDCC))
                Spacer(Modifier.width(6.dp))
                Text(if (editMode) "Done" else "Edit", color = Color(0xFFB6BDCC), fontSize = 16.sp)
            }
        }
        Text(
            t("whoWatching"),
            Modifier.fillMaxWidth().statusBarsPadding().padding(top = 64.dp).graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 10.dp.toPx() },
            fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        )

        // Profiles on the line.
        val cells: List<Profile?> = profiles + (if (profiles.size < 5) listOf(null) else emptyList())
        LazyRow(
            Modifier.fillMaxWidth().padding(top = arcY - 4.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        ) {
            items(cells.size) { i ->
                val p = cells[i]
                val pop = remember { Animatable(0f) }
                LaunchedEffect(active) { if (active) { delay(350L + i * 110L); pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f)) } }
                val focused = p != null && p.id == focusId && !editMode && leaving == null
                val isLeaving = p != null && p.id == leaving
                val sc by androidx.compose.animation.core.animateFloatAsState(if (focused || isLeaving) 1.22f else 1f, spring(dampingRatio = 0.6f, stiffness = 350f), label = "sc")
                Column(
                    Modifier.width(92.dp).graphicsLayer {
                        alpha = pop.value.coerceIn(0f, 1f) * (if (leaving != null && !isLeaving) 1f - leave.value else 1f)
                        translationY = (1f - pop.value) * 16.dp.toPx()
                    }.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                        when {
                            p == null -> { cancelled = true; adding = true }
                            editMode -> editing = p
                            else -> pick(p)
                        }
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(96.dp).graphicsLayer {
                            val l = if (isLeaving) 1f + leave.value * 0.8f else 1f
                            scaleX = sc * l; scaleY = sc * l
                            alpha = if (isLeaving) 1f - leave.value * 0.7f else 1f
                        },
                        Alignment.Center,
                    ) {
                        if (p == null) {
                            Box(Modifier.size(74.dp).clip(CircleShape).background(Color(0x2EFFFFFF)), Alignment.Center) {
                                Icon(Icons.Default.Add, "Add profile", Modifier.size(32.dp))
                            }
                        } else {
                            ProfileAvatar(vm, p, 74.dp, if (focused || isLeaving) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                            if (editMode) Box(Modifier.size(74.dp).clip(CircleShape).background(Color(0x88000000)), Alignment.Center) {
                                Icon(Icons.Default.Edit, "Edit ${p.name}", Modifier.size(26.dp))
                            }
                            if (focused && countdown.value > 0f && !cancelled) Canvas(Modifier.size(90.dp)) {
                                val sw = 3.5.dp.toPx()
                                drawArc(Color(0x2EFFFFFF), 0f, 360f, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw))
                                drawArc(BrandRed, -90f, 360f * countdown.value, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
                            }
                        }
                    }
                    Text(
                        p?.name ?: "Add", Modifier.padding(top = if (focused) 10.dp else 4.dp), fontSize = 13.sp,
                        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal, color = if (focused) Color.White else Color(0xFFC9CDD6),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // "Opening Rick in 5"
        val focusName = profiles.firstOrNull { it.id == focusId }?.name ?: ""
        val showCount = countdown.value > 0f && !cancelled && !editMode
        Column(
            Modifier.fillMaxWidth().padding(top = arcY + 150.dp).graphicsLayer { alpha = if (showCount || leaving != null) 1f else 0f },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                buildAnnotatedString {
                    append("Opening ")
                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append(focusName) }
                    if (leaving == null) { append(" in "); withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append("$remaining") } } else append("…")
                },
                fontSize = 14.sp, color = Color(0xFFC9CDD6),
            )
            if (leaving == null) Text("Tap another profile to switch", Modifier.padding(top = 4.dp), fontSize = 12.sp, color = Color(0xFF7C8394))
        }
    }
    editing?.let { p -> ProfileEditor(vm, p) { editing = null } }
    if (adding) ProfileEditor(vm, null) { adding = false }
}
