package com.example.streamnest

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Music prompt 5 (musicLook2), part 2: the colours the Music app should use right now, and the Glass backdrop.
 *
 * Nothing here is private to MusicApp.kt, so any screen can call it. Every function does nothing special
 * when Feature.musicLook2 is off (it returns the plain look colours / the old colour).
 */

/** The colours to use now. bg = page background, accent = buttons / rings / lit lyric, deep = gradients and sheets. */
data class MusicThemeColors(
    val bg: Color,
    val accent: Color,
    val deep: Color,
    /** Glass is on (and the switch is on). */
    val glass: Boolean,
    /** The colours come from the playing cover (Follow the song worked for this cover). */
    val fromCover: Boolean,
)

/**
 * Chosen look (Elegant, Midnight, ...) unless "Follow the song" is on and the cover has a clear colour;
 * then the cover's colours. Changes fade over 600 ms, the same time as the Glass cross-fade.
 * A grey or missing cover keeps the chosen look's colours.
 */
@Composable
fun rememberMusicTheme(artworkUrl: String?): MusicThemeColors {
    val look by MusicLook.current.collectAsStateWithLifecycle()
    val glass by MusicLook2.glass.collectAsStateWithLifecycle()
    val follow by MusicLook2.follow.collectAsStateWithLifecycle()
    val on = Feature.musicLook2
    // When Follow is off we do not even sample the cover.
    val cover by rememberMusicCover(if (on && follow) artworkUrl else null)
    val fromCover = on && follow && cover.found

    val bg by animateColorAsState(if (fromCover) cover.bg else look.bg, tween(600), label = "musicBg")
    val accent by animateColorAsState(if (fromCover) cover.accent else look.accent, tween(600), label = "musicAccent")
    val deep by animateColorAsState(if (fromCover) cover.deep else look.glow, tween(600), label = "musicDeep")
    return MusicThemeColors(bg, accent, deep, on && glass, fromCover)
}

/**
 * Glass backdrop: a blurred copy of the current cover (blur 40) under a dark scrim, cross-fading in 600 ms
 * each time the song (cover) changes. Put it as the FIRST child of the root Box, under everything.
 *
 * Android 12+ (API 31): real blur. Older phones: Modifier.blur does nothing there, so the cover stays sharp and
 * a heavier dark scrim goes over it. [scrim] is normally MusicThemeColors.bg, so Follow the song tints it.
 */
@Composable
fun MusicGlassBackdrop(artworkUrl: String?, scrim: Color, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val canBlur = MusicLook2.canBlur
    LaunchedEffect(canBlur) {
        if (!canBlur) MusicLog.add("Glass: this phone is on Android 11 or older and cannot blur, so a dark scrim goes over the cover instead")
    }
    val top = if (canBlur) 0.70f else 0.86f
    val bottom = if (canBlur) 0.94f else 0.97f
    Box(modifier.fillMaxSize().clipToBounds()) {
        Crossfade(targetState = artworkUrl, animationSpec = tween(600), label = "glassCover") { url ->
            if (url != null) {
                val req = remember(url) { ImageRequest.Builder(ctx).data(url).size(256).build() }
                AsyncImage(
                    model = req,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().then(
                        if (canBlur) Modifier.blur(40.dp, BlurredEdgeTreatment.Unbounded) else Modifier
                    ),
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(scrim.copy(alpha = top), scrim.copy(alpha = (top + bottom) / 2f), scrim.copy(alpha = bottom)))
            )
        )
    }
}

/**
 * "Frosted" panels. Compose cannot live-blur what sits behind a panel without extra layers, so Glass works like this:
 * one blurred cover at the very back, and bars, cards and sheets above it become see-through white / dark
 * with a thin light edge. Use glassOr(MCard) where a card has a solid colour today.
 */
@Composable
fun glassOr(base: Color, glassFill: Color = Color(0x1AFFFFFF)): Color {
    val g by MusicLook2.glass.collectAsStateWithLifecycle()
    return if (Feature.musicLook2 && g) glassFill else base
}

/** Thin light edge for a glass panel; nothing when Glass is off. */
@Composable
fun Modifier.glassEdge(shape: Shape): Modifier {
    val g by MusicLook2.glass.collectAsStateWithLifecycle()
    return if (Feature.musicLook2 && g) this.border(1.dp, Color(0x24FFFFFF), shape) else this
}
