package com.example.streamnest

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

// ───────────── Streaming mode: Elite / Regular ─────────────

/** "elite" / "regular" — null until chosen on first start. Device setting (not per profile). */
fun AppViewModel.streamMode(): String? = texts.value["streamMode"]?.takeIf { it == "elite" || it == "regular" }

/**
 * Elite features (title-page preload, next episode ready, auto quality, trailers by themselves) are on right now:
 * Elite is chosen and — when "Only on Wi-Fi" is on — the device is on Wi-Fi / Ethernet. With the switch off, Elite
 * also runs on mobile data.
 */
fun AppViewModel.eliteOn(): Boolean =
    streamMode() == "elite" && (!isOn(texts.value["eliteWifiStrict"] ?: "no") || isOnWifi(appContext))

private val EliteBg = Brush.linearGradient(listOf(Color(0xFF3A0A10), Color(0xFF1B0B10), Color(0xFF12121A)))
private val RegularBg = Brush.linearGradient(listOf(Color(0xFF282A34), Color(0xFF16171E)))
private val Crown = Brush.verticalGradient(listOf(Color(0xFFFFD84D), Color(0xFFD99B00)))

@Composable
private fun ModeCard(
    elite: Boolean, selected: Boolean, compact: Boolean, modifier: Modifier, requester: FocusRequester?, onFocus: () -> Unit, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (focused || (compact && selected)) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 300f), label = "mode")
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .graphicsLayer { val s = 1f + 0.03f * lift; scaleX = s; scaleY = s }
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .clip(shape)
            .background(if (elite) EliteBg else RegularBg)
            .border(
                if (focused || (compact && selected)) 2.5.dp else 1.dp,
                if (focused || (compact && selected)) Color.White else if (elite) Color(0x8CE50914) else Color(0x1FFFFFFF), shape,
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(if (compact) 16.dp else 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(if (compact) 24.dp else 30.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (elite) Crown else Brush.verticalGradient(listOf(Color(0xFF9AA3B5), Color(0xFF5C6475)))),
                Alignment.Center,
            ) { Text(if (elite) "♛" else "▶", fontSize = if (compact) 12.sp else 15.sp, color = if (elite) Color(0xFF3A2500) else Color.White) }
            Spacer(Modifier.width(10.dp))
            Text(if (elite) "Elite" else "Regular", fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = if (compact) 18.sp else 22.sp)
        }
        Text(
            if (elite) "RECOMMENDED ON WI-FI" else "LIGHT ON DATA",
            Modifier.padding(top = 8.dp, bottom = 6.dp).clip(RoundedCornerShape(6.dp))
                .background(if (elite) BrandRed else Color(0x24FFFFFF)).padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
        )
        val lines = if (elite) listOf(
            "Title page picks the best stream and loads its first minute — Play starts instantly",
            "Next episode ready at 75% — no loading between episodes",
            "Auto quality: 4K ⇄ 1080p ⇄ 720p without a buffering stop",
            "Trailers start by themselves on cards",
        ) else listOf(
            "Pick a stream yourself, or let your regex / Quick Start rules choose",
            "Loads only when you press Play",
            "The quality you choose stays fixed",
            "Trailers only when you ask",
        )
        (if (compact) lines.take(3) else lines).forEach { l ->
            Row(Modifier.padding(top = 5.dp)) {
                Text(if (elite) "✓" else "•", Modifier.width(16.dp), color = if (elite) Color(0xFF7BD88F) else Color(0xFF9AA3B5), fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                Text(l, fontSize = if (compact) 11.sp else 12.sp, lineHeight = if (compact) 15.sp else 17.sp, color = Color(0xFFD6D9E1))
            }
        }
        if (!compact) {
            Spacer(Modifier.weight(1f))
            Text(if (elite) "Uses more internet data" else "How Xstream has always worked", fontSize = 11.sp, color = Muted)
        }
    }
}

/**
 * First start: "Choose your experience" (Elite / Regular). The two cards rise in one after the other; on the TV the
 * focused one grows and glows, OK picks it. Saved as a device setting; changeable in Settings › Playback.
 */
@Composable
fun ModeChooser(vm: AppViewModel, onDone: () -> Unit) {
    val tv = BuildConfig.IS_TV
    var pick by remember { mutableStateOf("elite") }
    val enter = remember { Animatable(0f) }
    val eliteFr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) { if (tv) { withFrameNanos { }; repeat(4) { if (eliteFr.tryFocus()) return@LaunchedEffect; delay(60) } } }
    fun choose(m: String) { vm.setText("streamMode", m); onDone() }
    BackHandler { choose("regular") }
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF17080E), Color(0xFF0B0B10), Color(0xFF07080C)))),
        Alignment.TopCenter,
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 18.dp, vertical = if (tv) 26.dp else 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Wordmark(t("appName"), if (tv) 22.sp else 20.sp)
            Text("Choose your experience", Modifier.padding(top = 10.dp), fontSize = if (tv) 24.sp else 22.sp, fontWeight = FontWeight.ExtraBold)
            Text("You can change this any time in Settings › Playback", Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Muted)
            Spacer(Modifier.height(if (tv) 22.dp else 18.dp))
            if (tv) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    ModeCard(true, pick == "elite", false, Modifier.width(320.dp).height(300.dp).graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 40.dp.toPx() },
                        eliteFr, onFocus = { pick = "elite" }) { choose("elite") }
                    ModeCard(false, pick == "regular", false, Modifier.width(320.dp).height(300.dp).graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 70.dp.toPx() },
                        null, onFocus = { pick = "regular" }) { choose("regular") }
                }
            } else {
                Column(Modifier.widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ModeCard(true, pick == "elite", true, Modifier.fillMaxWidth().graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 40.dp.toPx() },
                        null, onFocus = {}) { pick = "elite" }
                    ModeCard(false, pick == "regular", true, Modifier.fillMaxWidth().graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 70.dp.toPx() },
                        null, onFocus = {}) { pick = "regular" }
                    Spacer(Modifier.height(4.dp))
                    RedPill(if (pick == "elite") "Continue with Elite" else "Continue with Regular", Modifier.fillMaxWidth(), height = 52, fontSize = 17, onClick = { choose(pick) })
                }
            }
        }
    }
}

/** Settings › Playback: Streaming mode + "Elite only on Wi-Fi". */
@Composable
internal fun StreamingModeSection(vm: AppViewModel) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    val mode = texts["streamMode"] ?: "regular"
    SectionLabel("Streaming mode")
    SettingsCard {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("elite" to "♛  Elite", "regular" to "Regular").forEach { (id, label) ->
                val sel = mode == id
                Box(
                    Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (sel && id == "elite") EliteBg else if (sel) Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x1AFFFFFF))) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                        .border(if (sel) 1.5.dp else 0.dp, if (sel) (if (id == "elite") Color(0x99E50914) else Color(0x66FFFFFF)) else Color.Transparent, RoundedCornerShape(14.dp))
                        .clickable { vm.setText("streamMode", id) },
                    Alignment.Center,
                ) { Text(label, fontSize = 16.sp, fontWeight = if (sel) FontWeight.ExtraBold else FontWeight.SemiBold, color = if (sel) Color.White else Muted) }
            }
        }
        Text(
            if (mode == "elite") "Elite loads the first minute of what you open and of the next episode, switches quality by itself and plays trailers on cards. Uses more data."
            else "Regular loads only when you press Play: pick streams yourself or let your regex / Quick Start rules choose.",
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), fontSize = 13.sp, color = Muted,
        )
        RowDivider(20.dp)
        ToggleRow(
            "Elite only on Wi-Fi",
            if (isOn(texts["eliteWifiStrict"] ?: "no")) "On: Elite features run only on Wi-Fi (Ethernet counts). On mobile data Xstream works like Regular."
            else "Off: Elite features run on Wi-Fi and on mobile data.",
            isOn(texts["eliteWifiStrict"] ?: "no"),
        ) { vm.setText("eliteWifiStrict", if (it) "yes" else "no") }
        if (mode == "elite") {
            RowDivider(20.dp)
            ToggleRow(
                "Auto quality",
                if (BuildConfig.IS_TV) "With Auto picked in the player, switch between 1080p and 720p at the same second when the connection changes."
                else "With Auto picked in the player, switch between 4K, 1080p and 720p at the same second when the connection changes.",
                isOn(texts["autoQuality"] ?: "yes"),
            ) { vm.setText("autoQuality", if (it) "yes" else "no") }
        }
    }
}

// ───────────── Quick guide (first start, replay from Settings › Advanced) ─────────────

private data class GuideTip(val title: String, val text: String)

private val TvTips = listOf(
    GuideTip("Featured titles", "◀ ▶ moves between the big cards. ▼ goes to Play and My List. Rest on a title and its trailer starts."),
    GuideTip("The menu", "Press ◀ on the left edge to open the menu: Search, Home, Library, My Space and Settings."),
    GuideTip("Title pages", "Play starts the best stream for you. Trailer: OK for the newest, hold OK for all of them."),
    GuideTip("In the player", "▲ shows the controls and pauses, ▼ hides them and plays on. ◀ ▶ jump 30 s, or 10 s while the controls are up."),
    GuideTip("Audio & Subtitles", "Change the language, add subtitles from OpenSubtitles and set their size and colour — all in one place."),
    GuideTip("Make it yours", "Settings › Playback switches Elite / Regular. Your account keeps every profile in sync with your phone."),
)
private val PhoneTips = listOf(
    GuideTip("Featured titles", "Swipe the big cards. Long-press a card to start trailers, long-press again to stop. Unmute once and the next cards keep the sound."),
    GuideTip("Continue Watching", "Shows the time left, the next episode when you've finished one, and when a new episode is coming."),
    GuideTip("Title pages", "Play starts the best stream for you. Trailer: tap for the newest, hold for all of them."),
    GuideTip("In the player", "Double-tap the sides to jump (30 s with the controls hidden, 10 s with them shown), drag the bar to see a preview, and use Audio & Subtitles for languages and online subtitles."),
    GuideTip("Make it yours", "Settings › Playback switches Elite / Regular. Sign in to keep every profile in sync with your TV."),
)

/** One tip at a time over the app, with Next / Skip. */
@Composable
fun QuickGuide(onDone: () -> Unit) {
    val tips = if (BuildConfig.IS_TV) TvTips else PhoneTips
    var i by remember { mutableIntStateOf(0) }
    val nextFr = remember { FocusRequester() }
    LaunchedEffect(i) { withFrameNanos { }; repeat(4) { if (nextFr.tryFocus()) return@LaunchedEffect; delay(60) } }
    BackHandler { onDone() }
    val tip = tips[i]
    Box(
        Modifier.fillMaxSize().background(Color(0xB3050608)).clickable(remember { MutableInteractionSource() }, null) {},
        if (BuildConfig.IS_TV) Alignment.BottomEnd else Alignment.BottomCenter,
    ) {
        Column(
            Modifier.padding(if (BuildConfig.IS_TV) 40.dp else 18.dp).widthIn(max = 380.dp).fillMaxWidth()
                .clip(RoundedCornerShape(20.dp)).background(Color.White).padding(18.dp),
        ) {
            Text(tip.title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF111111))
            Text(tip.text, Modifier.padding(top = 6.dp), fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFF333333))
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                tips.indices.forEach { k ->
                    Box(Modifier.padding(end = 4.dp).size(width = if (k == i) 16.dp else 6.dp, height = 6.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (k == i) BrandRed else Color(0xFFCCCCCC)))
                }
                Spacer(Modifier.weight(1f))
                GuideBtn("Skip", false, null) { onDone() }
                Spacer(Modifier.width(8.dp))
                GuideBtn(if (i == tips.lastIndex) "Done" else "Next ▸", true, nextFr) { if (i < tips.lastIndex) i++ else onDone() }
            }
        }
    }
}

@Composable
private fun GuideBtn(label: String, primary: Boolean, fr: FocusRequester?, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Text(
        label,
        Modifier.then(if (fr != null) Modifier.focusRequester(fr) else Modifier)
            .onFocusChanged { f = it.isFocused }
            .clip(RoundedCornerShape(16.dp))
            .background(if (primary) BrandRed else if (f) Color(0x22000000) else Color.Transparent)
            .border(if (f) 2.dp else 0.dp, if (f) Color(0xFF111111) else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (primary) Color.White else Color(0xFF777777),
    )
}
