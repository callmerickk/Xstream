package com.example.streamnest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

class MusicActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MusicPlayer.init(applicationContext)
        enableEdgeToEdge()
        Coil.setImageLoader(ImageLoader.Builder(this).components { add(HdPoster) }.crossfade(120).build())
        val skipIntro = intent.getBooleanExtra("skipIntro", false)
        val requestedProfile = intent.getStringExtra("profileId")
        setContent {
            StreamNestTheme {
                val vm: AppViewModel = viewModel()
                // Kept across turning the phone (the activity is no longer rebuilt, but saved state is the safe side).
                var openingDone by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(skipIntro) }
                var picked by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(skipIntro && !requestedProfile.isNullOrBlank()) }
                val profile by vm.current.collectAsState()
                val scope = rememberCoroutineScope()

                LaunchedEffect(requestedProfile, skipIntro) {
                    if (skipIntro && !requestedProfile.isNullOrBlank()) {
                        vm.profiles.value.firstOrNull { it.id == requestedProfile }?.let {
                            vm.selectProfile(it)
                            picked = true
                            openingDone = true
                        }
                    }
                    // Warm the catalog behind the profile picker so Home opens without a loading flash.
                    runCatching { YtMusic.home() }
                }

                when {
                    !openingDone -> MusicOpening { openingDone = true }
                    !picked && !skipIntro -> MusicProfilePicker(vm) {
                        picked = true
                        profile?.id?.let { id -> scope.launch { MusicLibraryStore.open(this@MusicActivity, id) } }
                    }
                    else -> {
                        val current = profile
                        MusicApp(
                            profileId = current?.id ?: requestedProfile ?: "p1",
                            profileName = current?.name ?: "Music",
                            profileVm = vm,
                            onClose = { finish() },
                        )
                    }
                }
            }
        }
    }
}

private val XRed = Color(0xFFE50914)

/** The Xstream mark exactly as the app has it: red X and its three signal arcs. */
private fun DrawScope.xstreamMark(xAlpha: Float = 1f, arcsAlpha: Float = 1f, glow: Float = 0f) {
    val u = size.minDimension / 100f
    val ox = (size.width - 100f * u) / 2f; val oy = (size.height - 100f * u) / 2f
    fun o(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)
    val cap = StrokeCap.Round
    if (glow > 0f) drawCircle(Brush.radialGradient(listOf(XRed.copy(alpha = 0.4f * glow), Color.Transparent), o(31f, 48f), 36f * u), 36f * u, o(31f, 48f))
    drawLine(XRed.copy(alpha = xAlpha), o(18f, 32f), o(44f, 64f), 10f * u, cap)
    drawLine(XRed.copy(alpha = xAlpha), o(18f, 64f), o(44f, 32f), 10f * u, cap)
    listOf(20f to 1f, 29f to 0.7f, 38f to 0.4f).forEach { (r, a) ->
        drawArc(Color(0xFFF4F4F6).copy(alpha = a * arcsAlpha), -40f, 80f, false, o(39f - r, 50f - r), Size(2 * r * u, 2 * r * u), style = Stroke(6f * u, cap = cap))
    }
}

/** One music note (filled head, stem and flag) at [c], [h] tall. */
private fun DrawScope.note(c: Offset, h: Float, color: Color) {
    val head = h * 0.34f
    drawOval(color, Offset(c.x - head * 0.62f, c.y - head * 0.42f), Size(head * 1.24f, head * 0.84f))
    val sx = c.x + head * 0.5f
    drawLine(color, Offset(sx, c.y), Offset(sx, c.y - h), h * 0.09f, StrokeCap.Round)
    val path = androidx.compose.ui.graphics.Path().apply { moveTo(sx, c.y - h); quadraticTo(sx + h * 0.45f, c.y - h * 0.85f, sx + h * 0.42f, c.y - h * 0.5f) }
    drawPath(path, color, style = Stroke(h * 0.09f, cap = StrokeCap.Round))
}

/** Xstream Music's mark: the Xstream logo as it is, with one music note under it (used in Music's header). */
@Composable
fun XMusicMark(size: Dp, withNote: Boolean = false, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        xstreamMark()
        if (withNote) note(Offset(this.size.width * 0.47f, this.size.height * 0.95f), this.size.height * 0.2f, Color.White)
    }
}

/**
 * Xstream Music opening: the Xstream logo appears with a soft glow, "Xstream" slides out from behind it, "Music" settles
 * under "eam" in a serif italic, then the logo's red X zooms toward you into the profile picker.
 */
@Composable
private fun MusicOpening(onDone: () -> Unit) {
    val mark = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val music = remember { Animatable(0f) }
    val zoom = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { mark.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 300f)) }
        delay(380); word.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        music.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 320f))
        delay(420)
        zoom.animateTo(1f, tween(560, easing = FastOutLinearInEasing))
        onDone()
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF050506)), Alignment.Center) {
        Column(
            Modifier.graphicsLayer {
                // The X zooms toward you (around the X, left of the lockup); everything else fades out.
                val z = zoom.value
                scaleX = 1f + 16f * z * z; scaleY = scaleX
                transformOrigin = TransformOrigin(0.085f, 0.36f)
                alpha = 1f - (z - 0.65f).coerceAtLeast(0f) * 2.8f
            },
            horizontalAlignment = Alignment.End,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(72.dp).graphicsLayer { val k = 0.5f + 0.5f * mark.value; scaleX = k; scaleY = k; alpha = mark.value.coerceIn(0f, 1f) }) {
                    // While zooming, only the X stays (the arcs fade) so the red X fills the screen.
                    xstreamMark(arcsAlpha = 1f - zoom.value * 1.6f, glow = mark.value)
                }
                // "Xstream" slides out from behind the logo (X red, "stream" white).
                Box(Modifier.clipToBounds()) {
                    Row(Modifier.graphicsLayer { translationX = -(1f - word.value) * size.width; alpha = word.value * (1f - zoom.value * 2f).coerceIn(0f, 1f) }) {
                        Text("X", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, color = XRed, letterSpacing = (-1).sp)
                        Text("stream", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = (-1).sp)
                    }
                }
            }
            // "Music" under "eam": serif italic, red to blush.
            Text(
                "Music",
                Modifier.padding(end = 2.dp).offset(y = (-6).dp).graphicsLayer {
                    val s = 0.6f + 0.4f * music.value; scaleX = s; scaleY = s; alpha = music.value.coerceIn(0f, 1f) * (1f - zoom.value * 2f).coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
                style = TextStyle(
                    brush = Brush.horizontalGradient(listOf(Color(0xFFFF4A3D), Color(0xFFFFD1C4))),
                    fontSize = 38.sp, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Normal, letterSpacing = 1.sp,
                ),
            )
        }
    }
}

/**
 * Xstream Music's "Who's listening?": Hotstar's dark background (#0F1014) with red accents. The profiles slide
 * sideways: the last one you used sits in the middle, Add profile on its left, the other profiles on its right. Tap the
 * middle one to start (it opens by itself after 5 s).
 */
@Composable
private fun MusicProfilePicker(vm: AppViewModel, onPicked: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val texts by vm.texts.collectAsStateWithLifecycle()
    val last = texts["lastProfile"]
    // Add · last used · the others.
    val order: List<Profile?> = remember(profiles, last) {
        val l = profiles.firstOrNull { it.id == last } ?: profiles.firstOrNull()
        listOf<Profile?>(null) + listOfNotNull(l) + profiles.filter { it.id != l?.id }
    }
    val start = if (order.size > 1) 1 else 0
    val pager = rememberPagerState(initialPage = start) { order.size }
    var adding by remember { mutableStateOf(false) }
    val pscope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    val leave = remember { Animatable(0f) }
    var touched by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(5) }
    LaunchedEffect(pager.isScrollInProgress) { if (pager.isScrollInProgress) touched = true }
    fun pick(p: Profile) { if (leaving) return; touched = true; ProfileLock.ask(p) { vm.selectProfile(p); leaving = true } }
    LaunchedEffect(leaving) { if (leaving) { leave.animateTo(1f, tween(650, easing = FastOutSlowInEasing)); onPicked() } }
    LaunchedEffect(touched, order.size) {
        if (touched || order.size < 2) return@LaunchedEffect
        while (count > 0) { delay(1000); if (touched) return@LaunchedEffect; count-- }
        (order.getOrNull(pager.currentPage) ?: order.getOrNull(1))?.let { pick(it) }
    }
    val w = LocalConfiguration.current.screenWidthDp.dp
    val item = 132.dp
    Box(
        Modifier.fillMaxSize().background(Color(0xFF0F1014))
            .graphicsLayer { alpha = 1f - leave.value; val s = 1f + 0.12f * leave.value; scaleX = s; scaleY = s },
    ) {
        // A faint navy light from the top and a red line low on the screen (the accent).
        Box(Modifier.fillMaxWidth().height(360.dp).background(Brush.verticalGradient(listOf(Color(0x33223A6E), Color.Transparent))))
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                XMusicMark(30.dp)
                Text("X", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = XRed)
                Text("stream", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text(" Music", fontSize = 18.sp, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, color = Color(0xFFFF8A80))
            }
            Text("Who's listening?", Modifier.padding(top = 26.dp), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text("Swipe to choose · tap to start", Modifier.padding(top = 6.dp), fontSize = 13.sp, color = Color(0xFF8E97AD))
        }
        HorizontalPager(
            pager,
            Modifier.align(Alignment.Center).fillMaxWidth().height(260.dp),
            pageSize = PageSize.Fixed(item),
            contentPadding = PaddingValues(horizontal = (w - item) / 2),
            pageSpacing = 14.dp,
        ) { i ->
            val p = order[i]
            val off = (i - pager.currentPage - pager.currentPageOffsetFraction).absoluteValue.coerceAtMost(1.6f)
            val center = off < 0.5f
            Column(
                Modifier.fillMaxSize().graphicsLayer {
                    val s = 1f - 0.28f * off; scaleX = s; scaleY = s; alpha = 1f - 0.45f * off
                    translationY = off * 26.dp.toPx()
                }.clickable(remember { MutableInteractionSource() }, null) {
                    touched = true
                    if (!center) pscope.launch { pager.animateScrollToPage(i) }
                    else if (p == null) adding = true else pick(p)
                },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(112.dp), Alignment.Center) {
                    if (center) Box(Modifier.size(112.dp).clip(CircleShape).border(3.dp, Color.White, CircleShape))
                    if (p == null) Box(Modifier.size(100.dp).clip(CircleShape).background(Color(0x14FFFFFF)).border(1.5.dp, Color(0x33FFFFFF), CircleShape), Alignment.Center) {
                        Icon(Icons.Default.Add, "Add profile", Modifier.size(40.dp), tint = Color.White)
                    } else ProfileAvatar(vm, p, 100.dp, Modifier.then(if (center) Modifier.border(0.dp, Color.Transparent, CircleShape) else Modifier))
                    // Red glow ring on the one in the middle.
                    if (center) Canvas(Modifier.size(128.dp)) {
                        drawCircle(Brush.radialGradient(listOf(Color.Transparent, XRed.copy(alpha = 0.35f), Color.Transparent), radius = size.minDimension / 2f), size.minDimension / 2f)
                    }
                }
                Text(p?.name ?: "Add profile", Modifier.padding(top = 14.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (center && p != null && !touched && count > 0) Text("Opening in $count", Modifier.padding(top = 4.dp), fontSize = 12.sp, color = Color(0xFFFF8A80))
            }
        }
        // Red accent line under the profiles.
        Box(Modifier.align(Alignment.Center).offset(y = 150.dp).width(56.dp).height(3.dp).clip(CircleShape).background(XRed))
    }
    if (adding) ProfileEditor(vm, null) { adding = false }
}
