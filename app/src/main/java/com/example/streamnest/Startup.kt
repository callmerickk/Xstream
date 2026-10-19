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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
        leave.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
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

    // Xstream "Box office": every profile is a cinema ticket. Tap yours: the stub tears off along the perforation and
    // drops away, "Admit one" lights up and you're in. The last profile keeps the auto-open ring on its stub.
    val cream = Color(0xFFF4E6C8); val ink = Color(0xFF1A1208); val gold = Color(0xFFF2B544)
    Box(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF2A1D10), Color(0xFF0B0806)), center = Offset(500f, 0f), radius = 1600f))
            .graphicsLayer { alpha = 1f - (leave.value - 0.7f).coerceAtLeast(0f) * 2f }
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); if (countdown.value > 0f) cancelled = true } },
    ) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 18.dp, end = 10.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(AppLook.logoRes(AppLook.logo.value)), null, Modifier.size(30.dp))
            Spacer(Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable { editMode = !editMode; cancelled = true }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (editMode) Icons.Default.Check else Icons.Default.Edit, null, Modifier.size(18.dp), tint = Color(0xFFD9C39A))
                Spacer(Modifier.width(6.dp))
                Text(if (editMode) "Done" else "Edit", color = Color(0xFFD9C39A), fontSize = 16.sp)
            }
        }
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 60.dp).graphicsLayer { alpha = enter.value }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("BOX OFFICE", fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = 4.sp, color = gold)
            Text(if (editMode) "TAP A TICKET TO EDIT" else "PICK YOUR TICKET", Modifier.padding(top = 2.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.5.sp, color = Color(0x99D9C39A))
        }
        val cells: List<Profile?> = profiles + (if (profiles.size < 5) listOf(null) else emptyList())
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(top = 140.dp, bottom = 24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            cells.forEachIndexed { i, p ->
                val pop = remember { Animatable(0f) }
                LaunchedEffect(active) { if (active) { delay(250L + i * 110L); pop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 260f)) } }
                val focused = p != null && p.id == focusId && !editMode && leaving == null
                val isLeaving = p != null && p.id == leaving
                val tilt = listOf(-2f, 1.5f, -1f, 2f, -1.5f, 1f)[i % 6]
                Row(
                    Modifier.width(290.dp).height(96.dp).graphicsLayer {
                        val v = pop.value
                        alpha = v.coerceIn(0f, 1f) * (if (leaving != null && !isLeaving) 1f - leave.value else 1f)
                        translationY = (1f - v) * 30.dp.toPx() + (if (leaving != null && !isLeaving) leave.value * 60.dp.toPx() else 0f)
                        rotationZ = if (isLeaving) tilt - 3f * leave.value else tilt
                        val k = if (isLeaving) 1f + 0.12f * leave.value else if (focused) 1.04f else 1f
                        scaleX = k; scaleY = k
                    }.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                        when { p == null -> { cancelled = true; adding = true }; editMode -> editing = p; else -> pick(p) }
                    },
                ) {
                    // Main part of the ticket.
                    Row(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                            .background(if (p == null) Color(0x14F4E6C8) else cream)
                            .then(if (p == null) Modifier.border(1.5.dp, Color(0x66F4E6C8), RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)) else Modifier)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (p == null) {
                            Icon(Icons.Default.Add, "Add profile", Modifier.size(30.dp), tint = cream)
                            Text("ADD A PROFILE", Modifier.padding(start = 12.dp), fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 1.sp, color = cream)
                        } else {
                            Box(contentAlignment = Alignment.Center) {
                                ProfileAvatar(vm, p, 54.dp, Modifier.border(2.dp, ink, CircleShape))
                                if (editMode) Box(Modifier.size(54.dp).clip(CircleShape).background(Color(0x88000000)), Alignment.Center) { Icon(Icons.Default.Edit, "Edit ${p.name}", Modifier.size(22.dp)) }
                            }
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(p.name.uppercase(), fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 1.sp, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (p.kids) "KIDS · FAMILY SHOWS" else "ADMIT ONE · ALL SHOWS", fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Color(0xAA1A1208))
                            }
                        }
                    }
                    // Perforation, then the stub (it tears off and drops when you pick this ticket).
                    Canvas(Modifier.width(2.dp).fillMaxHeight().background(if (p == null) Color.Transparent else cream)) {
                        var y = 4.dp.toPx()
                        while (y < size.height) { drawLine(ink.copy(alpha = 0.5f), Offset(1f, y), Offset(1f, y + 4.dp.toPx()), 2f); y += 8.dp.toPx() }
                    }
                    Column(
                        Modifier.width(64.dp).fillMaxHeight().graphicsLayer {
                            if (isLeaving) { val v = leave.value; translationX = v * 30.dp.toPx(); translationY = v * 140.dp.toPx(); rotationZ = v * 28f; alpha = 1f - v }
                        }.clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)).background(if (p == null) Color(0x14F4E6C8) else cream),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        if (p != null) {
                            Text("SEAT", fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Color(0xAA1A1208))
                            Box(contentAlignment = Alignment.Center) {
                                Text("${"AKGDMR"[i % 6]}${i + 1}", fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = ink)
                                if (focused && countdown.value > 0f && !cancelled) Canvas(Modifier.size(42.dp)) {
                                    val sw = 3.dp.toPx()
                                    drawArc(Color(0x22000000), 0f, 360f, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw))
                                    drawArc(BrandRed, -90f, 360f * countdown.value, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
                                }
                            }
                        }
                    }
                }
            }
        }
        // "Admit one" as the ticket is torn; "Opening Rick in 5" while the auto-open ring runs.
        val focusName = profiles.firstOrNull { it.id == focusId }?.name ?: ""
        val showCount = countdown.value > 0f && !cancelled && !editMode && leaving == null
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (leaving != null) Text("ADMIT ONE", Modifier.graphicsLayer { alpha = (leave.value * 2f).coerceAtMost(1f) }, fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = 4.sp, color = cream)
            else if (showCount) Text(buildAnnotatedString {
                append("Opening "); withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append(focusName) }
                append(" in "); withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append("$remaining") }
            }, fontSize = 14.sp, color = Color(0xFFD9C39A))
        }
    }
    editing?.let { p -> ProfileEditor(vm, p) { editing = null } }
    if (adding) ProfileEditor(vm, null) { adding = false }
}
