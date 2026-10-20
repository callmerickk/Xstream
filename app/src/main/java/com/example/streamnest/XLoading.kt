package com.example.streamnest

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Xstream phone loading screen (StreamX TV style): no poster behind, only a soft dark blur; a landscape frame in the
 * middle plays the title's trailer (muted) — or its stills, slowly, when there's no trailer — with the progress running
 * around the frame, and the heartbeat line and percentage under it.
 */
@Composable
fun XMomentLoader(vm: AppViewModel, meta: Meta?, imdbId: String, type: String, title: String, fallbackArt: String?, stage: String, percent: Int, modifier: Modifier = Modifier) {
    var info by remember(imdbId) { mutableStateOf<TmdbInfo?>(null) }
    var src by remember(imdbId) { mutableStateOf<YtDirect.Src?>(null) }
    var videoOn by remember(imdbId) { mutableStateOf(false) }
    LaunchedEffect(imdbId) {
        if (imdbId.startsWith("tt")) info = runCatching { vm.tmdbInfo(imdbId, type) }.getOrNull()
        val m = meta ?: return@LaunchedEffect
        val id = runCatching { vm.trailerId(m) }.getOrNull() ?: return@LaunchedEffect
        src = YtDirect.resolve(id)
    }
    val stills = remember(info, fallbackArt) { info?.backdrops?.takeIf { it.isNotEmpty() } ?: listOfNotNull(info?.backdrop ?: fallbackArt) }
    var still by remember(stills) { mutableIntStateOf(0) }
    LaunchedEffect(stills) { while (stills.size > 1) { delay(3_500); still = (still + 1) % stills.size } }
    var shown by remember { mutableIntStateOf(0) }
    LaunchedEffect(percent) { if (percent > shown) shown = percent }
    val p by animateFloatAsState(shown / 100f, tween(400), label = "xload")
    val drift by animateFloatAsState(if (still % 2 == 0) 1.06f else 1.12f, tween(3_500), label = "drift")
    val red = Accent

    Box(modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF261519), Color(0xFF0C0B10), Color.Black), radius = 1600f)), Alignment.Center) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val landscape = maxWidth > maxHeight
            val frameW = if (landscape) (maxWidth * 0.5f).coerceAtMost(560.dp) else maxWidth * 0.86f
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(frameW).aspectRatio(16f / 9f)) {
                    Box(Modifier.fillMaxSize().padding(6.dp).shadow(26.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(Color(0xFF15151B))) {
                        Crossfade(stills.getOrNull(still), Modifier.fillMaxSize(), animationSpec = tween(900), label = "still") { u ->
                            AsyncImage(u, null, Modifier.fillMaxSize().graphicsLayer { scaleX = drift; scaleY = drift }, contentScale = ContentScale.Crop)
                        }
                        src?.let { s ->
                            DirectTrailer(s, muted = true, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (videoOn) 1f else 0f }, crop = true, zoom = 1.05f,
                                onPlaying = { videoOn = true }, onEnded = { videoOn = false; src = null }, onError = { videoOn = false; src = null })
                        }
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xB3000000))))
                        Text(title, Modifier.align(Alignment.BottomStart).padding(14.dp), fontFamily = BrandFont, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.fillMaxSize().drawWithCache {
                        val sw = 3.dp.toPx(); val inset = sw / 2f + 1.dp.toPx(); val r = 22.dp.toPx()
                        val path = Path().apply { addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(r, r))) }
                        val pm = PathMeasure().apply { setPath(path, false) }
                        val seg = Path()
                        val track = Stroke(sw); val glow = Stroke(sw + 5.dp.toPx(), cap = StrokeCap.Round); val line = Stroke(sw, cap = StrokeCap.Round)
                        onDrawBehind {
                            drawPath(path, Color(0x1FFFFFFF), style = track)
                            if (p > 0.002f) {
                                seg.reset(); pm.getSegment(0f, pm.length * p.coerceIn(0f, 1f), seg, true)
                                drawPath(seg, red, style = glow, alpha = 0.28f); drawPath(seg, red, style = line)
                            }
                        }
                    })
                }
                Column(Modifier.width(frameW * 0.82f).padding(top = 14.dp)) {
                    HeartbeatBar(p, Modifier.fillMaxWidth().height(40.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
                        Text("$stage…", Modifier.weight(1f), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Color(0xFFC9CED8))
                        Text("$shown%", fontFamily = BrandFont, fontSize = 20.sp, fontWeight = FontWeight.Light)
                    }
                }
            }
        }
    }
}
