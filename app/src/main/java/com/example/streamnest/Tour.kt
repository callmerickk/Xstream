package com.example.streamnest

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** A hand gesture drawn in a tour step. */
enum class TourGesture { Tap, Hold, DoubleTapSides, SwipeUpDownLeft, SwipeUpDownRight, Pinch, Swipe }

/** One step: a highlighted button ([target], or none) with a title and a line of explanation, or a gesture to show. */
data class TourStep(val target: String?, val title: String, val text: String, val gesture: TourGesture? = null,
    /** The app version (versionCode) that added this step: after an update, only steps newer than the last tour seen show. */
    val since: Int = 0)

/**
 * The first-time visual tour: on each main screen, the screen dims, one button at a time is lit up with a ring and a
 * short explanation (Next / Skip), and the player shows its gestures with an animated hand. Shown once per screen on
 * this phone; "Replay the tour" (User manual) shows it again.
 */
object Tour {
    /** Where the highlightable buttons are on screen (filled by [tourTarget]). */
    val rects = mutableStateMapOf<String, Rect>()
    private var prefs: android.content.SharedPreferences? = null
    /** Bumped when the tour is reset, so the overlays show again. */
    var epoch by mutableIntStateOf(0)

    fun init(ctx: Context) { if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("tour", Context.MODE_PRIVATE) }
    fun done(screen: String): Boolean = prefs?.getBoolean("v1_$screen", false) == true
    /** Version of the last tour finished on this screen (0 = before versions were kept). */
    fun seen(screen: String): Int = prefs?.getInt("seen_$screen", 0) ?: 0
    fun finish(screen: String) { prefs?.edit()?.putBoolean("v1_$screen", true)?.putInt("seen_$screen", BuildConfig.VERSION_CODE)?.apply(); epoch++ }
    /** The steps to show now: all of them the first time on this phone (new sign-in, new device), else only new ones. */
    fun pending(screen: String, steps: List<TourStep>): List<TourStep> =
        if (!done(screen)) steps else steps.filter { it.since > seen(screen) }
    fun reset() { prefs?.edit()?.clear()?.apply(); epoch++ }
}

/** Marks a button for the tour. */
fun Modifier.tourTarget(id: String): Modifier = onGloballyPositioned { Tour.rects[id] = it.boundsInRoot() }

val HomeTour = listOf(
    TourStep(null, "Welcome to Xstream", "A quick look around. Tap Next for each tip, or Skip any time. You can replay it from Settings › User manual."),
    TourStep("deck", "Hero cards", "Swipe to see more. Tap a card to open the title. Hold (long-press) a card to turn trailers on inside the cards — hold again to turn them off.", TourGesture.Hold),
    TourStep("cw", "Continue Watching", "Pick up where you stopped. Long-press a card for details, choosing a source, starting over or removing it."),
    TourStep("capsule", "Filters", "Show only Movies or Series. The arrow takes you back to the top."),
    TourStep("ai", "Talk to X", "Your AI movie buddy: say or type what you're in the mood for. Add a free key first in Settings › Integrations."),
    TourStep("deck", "Cards glide on their own", "The cards move by themselves every few seconds; swipe to take over — they start again when you leave them alone.", since = 27),
    TourStep("deck", "Trailer mode", "Hold a card: every card's trailer plays one after another (the screen stays on), then it turns itself off. Hold again to stop.", TourGesture.Hold, since = 29),
    TourStep("nav", "Home · Search · My Space", "Search has moods and Surprise me. My Space has your profiles, watchlist, downloads and Watch together. Long-press My Space to switch profiles."),
)

val DetailTour = listOf(
    TourStep("play", "Play", "Starts or resumes the title. Long-press for more ways: choose a source, start from the beginning, download…"),
    TourStep("light", "The status light", "Grey blinking = getting ready · green = plays instantly · amber = plays the normal way · red = no stream. Long-press it for details."),
    TourStep("trailer", "Trailer", "Tap for the newest trailer, hold for every trailer and clip."),
    TourStep("more", "More", "Watchlist, trailers, Download for offline, Share, Play manually."),
)

val PlayerTour = listOf(
    TourStep(null, "Show or hide the controls", "Tap the video once.", TourGesture.Tap),
    TourStep(null, "Jump back or forward", "Double-tap the left or right side (each extra tap adds more).", TourGesture.DoubleTapSides),
    TourStep(null, "Speed up", "Press and hold anywhere for 2× — keep holding and slide left / right to change the speed.", TourGesture.Hold),
    TourStep(null, "Brightness", "Swipe up or down on the left side.", TourGesture.SwipeUpDownLeft),
    TourStep(null, "Volume", "Swipe up or down on the right side.", TourGesture.SwipeUpDownRight),
    TourStep(null, "Zoom", "Pinch out to fill the screen, pinch in for the original size.", TourGesture.Pinch),
    TourStep(null, "Age rating and real quality", "When a video starts, its age rating and the real picture size of the file show for a few seconds at the top left (also in ⓘ).", since = 27),
    TourStep(null, "Audio & Subtitles", "Opens as a bubble from its button. ⓘ beside a language shows its details; subtitles from the internet come in your language, already downloaded; drag Size to see it on the video. Close it with the back button.", since = 27),
    TourStep(null, "Upgrade to 4K", "Videos can start on a fast stream; when a sharper file is ready, a small \"Upgrade\" button counts down at the bottom right — one tap switches at once.", since = 28),
    TourStep(null, "Invite friends", "Watch together (people icon, top right) › Share / Invite copies a link for any chat. Friends who joined you before are listed — ring 🔔 them to call them in.", since = 28),
    TourStep(null, "The buttons", "Tap the video to see them: Watch Next, Audio & Subtitles (with Audio boost and Night mode), Episodes, Next Episode and › for Sleep timer, Picture-in-picture, Sources, Aspect, Speed and Info. Top right: Watch together, lock and quality."),
)

val MySpaceTour = listOf(
    TourStep("profiles", "Profiles", "Switch profile, or tap Edit to change a picture (photo, video, GIF search), or add a PIN / fingerprint lock."),
    TourStep("together", "Watch together", "Join a friend's room with its code. To start one: play something › the people icon at the top right."),
    TourStep("settings", "Help & Settings", "Everything else — and the User manual, with every feature explained."),
)

/** The tour overlay for one screen (draws nothing once that screen's tour is done). */
@Composable
fun TourOverlay(screen: String, allSteps: List<TourStep>) {
    val ep = Tour.epoch
    val steps = remember(ep) { Tour.pending(screen, allSteps) }
    if (steps.isEmpty()) return
    var i by remember(ep) { mutableIntStateOf(0) }
    // Give the screen a moment to lay out its buttons first.
    var shown by remember(ep) { mutableStateOf(false) }
    LaunchedEffect(ep) { kotlinx.coroutines.delay(900); shown = true }
    if (!shown) return
    val step = steps.getOrNull(i) ?: run { Tour.finish(screen); return }
    val density = LocalDensity.current
    val rect = step.target?.let { Tour.rects[it] }?.takeIf { it.width > 4 && it.height > 4 && it.top >= 0 }
    val pulse = rememberInfiniteTransition(label = "tour")
    val ring by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Restart), label = "ring")
    BoxWithConstraints(
        Modifier.fillMaxSize()
            // The tour takes every touch (the app underneath waits).
            .pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } } },
    ) {
        val pad = with(density) { 10.dp.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            val dim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                if (rect != null) addRoundRect(RoundRect(rect.inflate(pad), CornerRadius(pad * 2.2f)))
            }
            drawPath(dim, Color(0xC7000000))
            if (rect != null) {
                val r = rect.inflate(pad + ring * pad * 1.6f)
                drawRoundRect(Color(0xFFE50914).copy(alpha = 1f - ring), r.topLeft, r.size, CornerRadius(pad * 2.4f), style = Stroke(width = 3.dp.toPx()))
                drawRoundRect(Color.White, rect.inflate(pad).topLeft, rect.inflate(pad).size, CornerRadius(pad * 2.2f), style = Stroke(width = 2.dp.toPx()))
            }
        }
        step.gesture?.let { g -> GestureHand(g, Modifier.align(Alignment.Center)) }
        // The explanation card: under the highlighted button when there is room, else above it; centred otherwise.
        val hPx = constraints.maxHeight.toFloat()
        val cardBelow = rect == null || rect.bottom < hPx * 0.58f
        val yPx = when {
            rect == null && step.gesture != null -> hPx * 0.66f
            rect == null -> hPx * 0.36f
            cardBelow -> rect.bottom + pad * 2.5f
            else -> rect.top - pad * 2.5f
        }
        val est = with(density) { 190.dp.toPx() }
        val topPx = (if (rect != null && !cardBelow) yPx - est else yPx).coerceIn(0f, (hPx - est).coerceAtLeast(0f))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                .offset { IntOffset(0, topPx.roundToInt()) }
                .clip(RoundedCornerShape(20.dp)).background(Color(0xF2151820)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                .padding(18.dp),
        ) {
            AnimatedContent(i, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "tourText") { k ->
                val s = steps.getOrNull(k) ?: step
                Column {
                    Text(s.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(s.text, Modifier.padding(top = 6.dp), fontSize = 14.sp, color = Color(0xFFD0D5DE), lineHeight = 20.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    steps.indices.forEach { k ->
                        Box(Modifier.size(width = if (k == i) 16.dp else 6.dp, height = 6.dp).clip(CircleShape).background(if (k == i) Color.White else Color(0x55FFFFFF)))
                    }
                }
                Text("Skip", Modifier.clip(RoundedCornerShape(16.dp)).clickable { Tour.finish(screen) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 14.sp, color = Color(0xFFB6BDCC))
                Text(if (i == steps.lastIndex) "Got it" else "Next",
                    Modifier.padding(start = 6.dp).clip(RoundedCornerShape(18.dp)).background(Color.White)
                        .clickable { if (i == steps.lastIndex) Tour.finish(screen) else i++ }.padding(horizontal = 18.dp, vertical = 9.dp),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    }
}

/** An animated hand showing a gesture. */
@Composable
private fun GestureHand(g: TourGesture, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "hand")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val d = with(density) { 60.dp.toPx() }
        // Where the hand is and how it looks at this moment.
        val (x, y, sc, ripple) = when (g) {
            TourGesture.Tap -> Quad(w / 2, h * 0.42f, if (p in 0.3f..0.45f) 0.85f else 1f, if (p in 0.35f..0.8f) (p - 0.35f) / 0.45f else -1f)
            TourGesture.Hold -> Quad(w / 2, h * 0.42f, if (p in 0.2f..0.85f) 0.85f else 1f, if (p in 0.25f..0.85f) ((p - 0.25f) / 0.6f) else -1f)
            TourGesture.DoubleTapSides -> {
                val left = p < 0.5f; val q = if (left) p * 2 else (p - 0.5f) * 2
                Quad(if (left) w * 0.2f else w * 0.8f, h * 0.42f, if (q in 0.2f..0.3f || q in 0.45f..0.55f) 0.85f else 1f, if (q > 0.2f) (q - 0.2f) / 0.8f else -1f)
            }
            TourGesture.SwipeUpDownLeft -> Quad(w * 0.18f, h * 0.55f - kotlin.math.sin(p * 2 * Math.PI).toFloat() * d * 1.6f, 0.9f, -1f)
            TourGesture.SwipeUpDownRight -> Quad(w * 0.82f, h * 0.55f - kotlin.math.sin(p * 2 * Math.PI).toFloat() * d * 1.6f, 0.9f, -1f)
            TourGesture.Pinch -> Quad(w / 2 + d * (0.3f + p), h * 0.42f - d * (0.3f + p) * 0.6f, 0.9f, -1f)
            TourGesture.Swipe -> Quad(w * (0.75f - p * 0.5f), h * 0.42f, 0.9f, -1f)
        }
        if (ripple >= 0f) Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White.copy(alpha = (1f - ripple) * 0.6f), radius = d * (0.3f + ripple), center = Offset(x, y), style = Stroke(3.dp.toPx()))
        }
        if (g == TourGesture.Pinch) {
            // Second finger, mirrored.
            HandIcon(w / 2 - d * (0.3f + p), h * 0.42f + d * (0.3f + p) * 0.6f, 0.9f)
        }
        HandIcon(x, y, sc)
    }
}

private data class Quad(val x: Float, val y: Float, val s: Float, val r: Float)

@Composable
private fun HandIcon(x: Float, y: Float, s: Float) {
    val density = LocalDensity.current
    val half = with(density) { 28.dp.toPx() }
    Icon(Icons.Default.TouchApp, null,
        Modifier.offset { IntOffset((x - half).roundToInt(), (y - half * 0.4f).roundToInt()) }.size(56.dp).graphicsLayer { scaleX = s; scaleY = s },
        tint = Color.White)
}
