package com.example.streamnest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.Coil
import coil.ImageLoader
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                var openingDone by remember { mutableStateOf(skipIntro) }
                var picked by remember { mutableStateOf(skipIntro && !requestedProfile.isNullOrBlank()) }
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
                    !picked && !skipIntro -> WhoIsWatchingScreen(vm, active = true) {
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

/**
 * Xstream Music opening (option A): the mark (red X + signal with music notes) appears with a glow, "stream" slides
 * out from behind it, "Music" pops in under "eam" in a serif italic, then the red X zooms toward you and the profile
 * picker comes in.
 */
@Composable
private fun MusicOpening(onDone: () -> Unit) {
    val mark = remember { Animatable(0f) }
    val notes = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val music = remember { Animatable(0f) }
    val zoom = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { mark.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 300f)) }
        delay(180); launch { notes.animateTo(1f, androidx.compose.animation.core.tween(600)) }
        delay(240); word.animateTo(1f, androidx.compose.animation.core.tween(480, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        music.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 320f))
        delay(260)
        zoom.animateTo(1f, androidx.compose.animation.core.tween(420, easing = androidx.compose.animation.core.FastOutLinearInEasing))
        onDone()
    }
    val red = Color(0xFFE50914)
    Box(Modifier.fillMaxSize().background(Color(0xFF030303)), Alignment.Center) {
        Column(
            Modifier.graphicsLayer {
                // The X zooms toward you (scale around the X, left of the lockup) and the rest fades.
                val z = zoom.value
                scaleX = 1f + 13f * z * z; scaleY = scaleX
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.13f, 0.38f)
                alpha = 1f - (z - 0.6f).coerceAtLeast(0f) * 2.5f
            },
            horizontalAlignment = Alignment.End,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Canvas(Modifier.size(78.dp).graphicsLayer {
                    val k = 0.5f + 0.5f * mark.value; scaleX = k; scaleY = k; alpha = mark.value.coerceIn(0f, 1f)
                }) {
                    val u = size.width / 100f
                    fun o(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * u, y * u)
                    val cap = androidx.compose.ui.graphics.StrokeCap.Round
                    // soft red glow behind the X
                    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(red.copy(alpha = 0.35f * mark.value), Color.Transparent), o(31f, 48f), 34f * u), 34f * u, o(31f, 48f))
                    drawLine(red, o(18f, 32f), o(44f, 64f), 10f * u, cap); drawLine(red, o(18f, 64f), o(44f, 32f), 10f * u, cap)
                    val arcs = listOf(20f to 1f, 29f to 0.7f, 38f to 0.4f)
                    arcs.forEach { (r, a) ->
                        drawArc(Color(0xFFF4F4F6).copy(alpha = a), -40f, 80f, false, o(39f - r, 50f - r),
                            androidx.compose.ui.geometry.Size(2 * r * u, 2 * r * u), style = androidx.compose.ui.graphics.drawscope.Stroke(6f * u, cap = cap))
                    }
                    // music notes riding the signal, twinkling in one by one
                    listOf(Triple(62f, 26f, 1f), Triple(76f, 44f, 0.8f), Triple(80f, 66f, 0.6f)).forEachIndexed { i, (x, y, s) ->
                        val a = ((notes.value * 3f) - i).coerceIn(0f, 1f) * s
                        val c = Color.White.copy(alpha = a)
                        drawOval(c, o(x - 3f * s, y - 2.3f * s), androidx.compose.ui.geometry.Size(6f * s * u, 4.6f * s * u))
                        drawLine(c, o(x + 2.6f * s, y), o(x + 2.6f * s, y - 11f * s), 1.8f * u, cap)
                        drawLine(c, o(x + 2.6f * s, y - 11f * s), o(x + 8f * s, y - 13f * s), 1.8f * u, cap)
                    }
                }
                // "stream" slides out from behind the mark.
                Box(Modifier.clipToBounds()) {
                    androidx.compose.material3.Text("stream", Modifier.graphicsLayer {
                        translationX = -(1f - word.value) * size.width; alpha = word.value
                    }, fontSize = 50.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = (-1).sp)
                }
            }
            // "Music" under "eam", elegant serif italic, red to blush.
            androidx.compose.material3.Text(
                "Music",
                Modifier.padding(end = 2.dp).graphicsLayer {
                    val s = 0.6f + 0.4f * music.value; scaleX = s; scaleY = s; alpha = music.value.coerceIn(0f, 1f)
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                },
                style = androidx.compose.ui.text.TextStyle(
                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFFFF4A3D), Color(0xFFFFD1C4))),
                    fontSize = 40.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontWeight = FontWeight.Normal, letterSpacing = 1.sp,
                ),
            )
        }
    }
}
