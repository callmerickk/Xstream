package com.example.streamnest

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Music prompt 5 (musicLook2), part 7: full-screen lyrics.
 * Plain values and lambdas only, because Lyrics lives in MusicApp.kt.
 * Only called when Feature.musicLook2 is on; with the switch off the old lyric lines are used.
 */

/**
 * One lyric line.
 *  [lit]      this is the current line: white, with a soft glow in [accent], and a little larger (30 sp, others 24 sp).
 *  [distance] how many lines it is after the current one (1 = the next line). Lines before it, or far away, use 0 or more than 1.
 *  [synced]   false when the lyrics have no timing: nothing is lit and every line is drawn clearly readable.
 *  [onClick]  optional, for tap-to-seek. null = the line is not clickable.
 * The size is animated as a real font size (not a scale), so long lines still wrap inside the screen width.
 */
@Composable
fun MusicLyricLine(
    text: String,
    lit: Boolean,
    distance: Int,
    synced: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val size by animateFloatAsState(if (synced && lit) 30f else 24f, tween(250), label = "lyricSize")
    val target = when {
        !synced -> Color(0xE6FFFFFF)
        lit -> Color.White
        distance == 1 -> Color(0x99FFFFFF)
        else -> Color(0x66FFFFFF)
    }
    val color by animateColorAsState(target, tween(250), label = "lyricColor")
    val glow = if (synced && lit) Shadow(accent.copy(alpha = 0.55f), blurRadius = 28f) else null
    Text(
        text,
        color = color,
        fontSize = size.sp,
        lineHeight = (size * 1.25f).sp,
        fontWeight = FontWeight.Bold,
        style = TextStyle(shadow = glow),
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { if (synced && lit) stateDescription = "Now singing" }
            .padding(vertical = 8.dp),
    )
}

/**
 * Full-screen lyrics backdrop in the cover's colour: the cover's deep tone fading down to its dark tone.
 * Put it as the FIRST child of the lyrics screen, under the lines.
 * [fallback] is the colour the old screen used (NowSheet's colour); it is used when the cover has no clear colour.
 * With Glass on, the gradient is made see-through so the blurred cover behind the app still shows.
 */
@Composable
fun MusicLyricsBackdrop(artworkUrl: String?, fallback: Color, modifier: Modifier = Modifier) {
    val cover by rememberMusicCover(artworkUrl)
    val glass by MusicLook2.glass.collectAsStateWithLifecycle()
    val deep by animateColorAsState(if (cover.found) cover.deep else fallback, tween(600), label = "lyricsDeep")
    val dark by animateColorAsState(if (cover.found) cover.bg else Color(0xFF0E0E0E), tween(600), label = "lyricsDark")
    val a = if (Feature.musicLook2 && glass) 0.55f else 1f
    LaunchedEffect(artworkUrl, cover.found) {
        if (artworkUrl != null) {
            MusicLog.add("Lyrics backdrop: " + if (cover.found) "colour taken from the cover" else "cover has no clear colour, keeping the old colour")
        }
    }
    Box(Modifier.then(modifier).fillMaxSize().background(Brush.verticalGradient(listOf(deep.copy(alpha = a), dark.copy(alpha = a)))))
}
