package com.example.streamnest

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The red Play pill, and the old in-app trailer route. Trailers now play in [TrailerActivity] (a plain Activity with
 * a real WebView, which fixes the black picture); [TrailerScreen] only forwards to it.
 */

/** The red Play pill (same look as the title page's Play button). */
@Composable
fun RedPill(text: String, modifier: Modifier = Modifier, height: Int = 60, fontSize: Int = 22, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit = {}) {
    val shape = RoundedCornerShape((height / 2).dp)
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val hasLong = onLongClick != null
    val tap = Modifier.pointerInput(hasLong) {
        detectTapGestures(onTap = { click() }, onLongPress = if (hasLong) { _: Offset -> longClick?.invoke() } else null)
    }
    Box(
        modifier.height(height.dp)
            .shadow(10.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .border(1.dp, Color(0x59E50914), shape)
            .padding(1.dp)
            .clip(shape)
            .background(Brush.verticalGradient(0f to Color(0xFFF0242E), 0.55f to Color(0xFFD10A14), 1f to Color(0xFFA8060E)))
            .drawBehind {
                // thin top highlight
                val y = 3.dp.toPx()
                val inset = size.height * 0.45f
                drawLine(Color(0x59FFFFFF), Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 1.dp.toPx())
            }
            .border(2.dp, Color.Black, shape)
            .then(tap),
        Alignment.Center,
    ) {
        Text(
            text, Modifier.padding(horizontal = 14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = fontSize.sp, letterSpacing = (-0.2).sp, color = Color.White,
                shadow = Shadow(Color(0x73000000), Offset(0f, 2f), 4f),
            ),
        )
        content()
    }
}

/**
 * Kept for the Screen.Trailer route: opens [TrailerActivity] straight away and leaves this screen when it closes
 * ("Play the movie" calls [onPlayMovie], anything else [onBack]).
 */
@Composable
fun TrailerScreen(p: Screen.Trailer, onBack: () -> Unit, onPlayMovie: () -> Unit) {
    val ctx = LocalContext.current
    val back by rememberUpdatedState(onBack)
    val play by rememberUpdatedState(onPlayMovie)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data?.getBooleanExtra(TrailerActivity.RESULT_PLAY, false) == true) play() else back()
    }
    var launched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (launched) return@LaunchedEffect
        launched = true
        if (p.videos.isEmpty()) { back(); return@LaunchedEffect }
        runCatching { launcher.launch(TrailerActivity.intent(ctx, p.videos, p.titles, p.start, p.title)) }.onFailure { back() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black))
}
