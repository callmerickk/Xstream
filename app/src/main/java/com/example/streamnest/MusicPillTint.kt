package com.example.streamnest

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline

/**
 * Music prompt 5 (musicLook2), part 8: the mini player / pill takes a tint of the cover colour.
 * Only called when Feature.musicLook2 is on. With the switch off, or with a grey or missing cover,
 * the tint is fully see-through and the pill looks exactly as before.
 */

/** The tint colour and its strength, kept apart so a grey cover fades out instead of passing through dark colours. */
data class MusicTint(val color: Color, val strength: Float)

/**
 * Cover colour for the pill. Strength is 0.35 at most, so the title, artist and buttons stay readable.
 * Colour and strength both change over 600 ms, the same time as the other cross-fades.
 */
@Composable
fun rememberMusicTint(artworkUrl: String?): MusicTint {
    val cover by rememberMusicCover(if (Feature.musicLook2) artworkUrl else null)
    val color by animateColorAsState(if (cover.found) cover.deep else Color.Black, tween(600), label = "tintColor")
    val strength by animateFloatAsState(if (Feature.musicLook2 && cover.found) 0.35f else 0f, tween(600), label = "tintStrength")
    return MusicTint(color, strength)
}

/**
 * Paints the tint over what is already drawn. Put it AFTER the pill's own .background(...) in the modifier chain,
 * and give it the pill's shape, so the tint follows the rounded corners without clipping anything.
 * No layout change: the pill keeps its size.
 */
fun Modifier.musicTint(tint: MusicTint, shape: Shape): Modifier =
    if (tint.strength <= 0.001f) this
    else this.drawBehind {
        drawOutline(shape.createOutline(size, layoutDirection, this), tint.color.copy(alpha = tint.strength))
    }
