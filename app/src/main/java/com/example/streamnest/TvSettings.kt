package com.example.streamnest

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private data class TvSetCat(val key: String, val title: String, val sub: String, val icon: ImageVector, val section: String? = null)

/** Same order as the phone's Settings page: Account · General · About · Advanced. */
private val TvSetCats = listOf(
    TvSetCat("account", "Account & Cloud Sync", "Sign in, auto sync, watch history.", Icons.Default.AccountCircle),
    TvSetCat("layout", "Layout", "Theme, Home rows and posters.", Icons.Default.Palette, "GENERAL"),
    TvSetCat("content", "Content & Discovery", "Addons, Stream Filters, Quick Start and Collections.", Icons.Default.Extension),
    TvSetCat("playback", "Playback", "Streaming mode (Elite / Regular), player, auto-play and subtitles.", Icons.Default.PlayArrow),
    TvSetCat("network", "Network & DNS", "DNS provider for addons and links.", Icons.Default.Dns),
    TvSetCat("integrations", "Integrations", "AI assistant, TMDB, MDBList ratings and Trakt.", Icons.Default.Link),
    TvSetCat("notifications", "Notifications", "New episode alerts for shows in My List.", Icons.Default.Notifications),
    TvSetCat("about", "Licenses & Attribution", "Data sources, acknowledgements and licenses.", Icons.Default.Info, "ABOUT"),
    TvSetCat("updates", "Update Log", "What's new in this version and earlier ones.", Icons.Default.NewReleases),
    TvSetCat("advanced", "Advanced", "Startup, cache and app guide.", Icons.Default.Tune, "ADVANCED"),
)

/**
 * Xstream TV settings: the sections on the left (phone order); OK opens a section on the RIGHT, and focus moves into
 * it. BACK inside a section goes back to the list (or to the previous page inside the section).
 */
@Composable
fun TvSettingsScreen(vm: AppViewModel, lastIdx: MutableIntState, onOpen: (String) -> Unit, onLeftEdge: (FocusRequester) -> Unit) {
    val frs = remember { List(TvSetCats.size) { FocusRequester() } }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    var paneFocused by remember { mutableStateOf(false) }
    // When the remote leaves the opened section with ◀, focus lands back on that section (not on whichever row is nearest).
    var paneLeftAt by remember { mutableLongStateOf(0L) }
    val paneFr = remember { FocusRequester() }
    val openTick = remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        withFrameNanos { }
        repeat(4) { if (frs[lastIdx.intValue.coerceIn(0, TvSetCats.lastIndex)].tryFocus()) return@LaunchedEffect; delay(50) }
    }
    LaunchedEffect(openTick.intValue) {
        if (openTick.intValue == 0) return@LaunchedEffect
        withFrameNanos { }
        repeat(6) { if (paneFr.tryFocus()) return@LaunchedEffect; delay(60) }
    }
    fun backToList() { frs[lastIdx.intValue.coerceIn(0, TvSetCats.lastIndex)].tryFocus() }
    BackHandler(enabled = paneFocused) { backToList() }

    Row(Modifier.fillMaxSize().padding(start = TvEdge, top = 24.dp)) {
        Column(Modifier.width(270.dp).fillMaxHeight()) {
            Text("Settings", Modifier.padding(start = 14.dp, bottom = 8.dp), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 40.dp)) {
                itemsIndexed(TvSetCats, key = { _, c -> c.key }) { i, c ->
                    if (c.section != null) Text(c.section, Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp), fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Color(0xFF7C8394))
                    TvSetRow(c, frs[i], selected = openKey == c.key, onFocused = {
                        val open = TvSetCats.indexOfFirst { it.key == openKey }
                        if (open >= 0 && open != i && android.os.SystemClock.uptimeMillis() - paneLeftAt < 400) { paneLeftAt = 0L; frs[open].tryFocus() }
                        else lastIdx.intValue = i
                    }, onLeft = { onLeftEdge(frs[i]) }) {
                        openKey = c.key; openTick.intValue++
                    }
                }
            }
        }
        // Right side: the opened section, or what the app is.
        Box(
            Modifier.padding(start = 18.dp, end = 24.dp).fillMaxSize()
                .onFocusChanged { if (paneFocused && !it.hasFocus) paneLeftAt = android.os.SystemClock.uptimeMillis(); paneFocused = it.hasFocus },
        ) {
            val k = openKey
            if (k == null) {
                Column(Modifier.padding(top = 80.dp, start = 30.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppLogo(vm, 30.dp); Spacer(Modifier.width(10.dp)); Wordmark(t("appName"), 22.sp)
                    }
                    Text("Version ${vm.appVersion}", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = TvSubText)
                    Text("Pick a section and press OK — it opens here.", Modifier.padding(top = 24.dp), fontSize = 14.sp, color = TvSubText)
                }
            } else androidx.compose.runtime.key(k) {
                Box(Modifier.fillMaxSize().focusRequester(paneFr)) {
                    SettingsHost(vm, k, onClose = { openKey = null; backToList() })
                }
            }
        }
    }
}

@Composable
private fun TvSetRow(c: TvSetCat, fr: FocusRequester, selected: Boolean, onFocused: () -> Unit, onLeft: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).height(44.dp)
            .graphicsLayer { val s = if (focused) 1.03f else 1f; scaleX = s; scaleY = s }
            .then(if (focused) Modifier.shadow(12.dp, shape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
            .focusRequester(fr)
            .tvLeftEdge(onLeft)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .clip(shape)
            .background(if (focused) Color.White else if (selected) Color(0x1FFFFFFF) else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(c.icon, null, Modifier.size(19.dp), tint = if (focused) Color.Black else Color(0xFFC9CED9))
        Spacer(Modifier.width(12.dp))
        Text(c.title, fontSize = 14.sp, fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium,
            color = if (focused) Color.Black else Color(0xFFE6E9F2), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
