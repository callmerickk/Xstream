package com.example.streamnest

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * Music prompt 5 (musicLook2), part 3: the "For you now" hero card at the top of Home.
 *
 * Nothing here knows the song type, so the caller (HomeTab) picks the songs and passes plain values and lambdas.
 * Only called when Feature.musicLook2 is on; with the switch off Home is exactly as before.
 */

/** The four times of day. */
enum class HeroSlot(val title: String, val reason: String) {
    MORNING("Morning mix", "To start the day"),
    AFTERNOON("Afternoon mix", "To keep you going"),
    COMMUTE("Commute mix", "For the ride"),
    NIGHT("Night mix", "To wind down");

    companion object {
        /**
         * Night 22:00-05:00. Commute: Monday-Friday 07:00-09:30 and 17:00-19:30. Morning: the rest until 12:00.
         * Afternoon: the rest from 12:00 until 22:00 (so the evening counts as afternoon).
         */
        fun of(millis: Long = System.currentTimeMillis()): HeroSlot {
            val c = Calendar.getInstance().apply { timeInMillis = millis }
            val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
            val day = c.get(Calendar.DAY_OF_WEEK)
            val weekday = day != Calendar.SATURDAY && day != Calendar.SUNDAY
            return when {
                m >= 22 * 60 || m < 5 * 60 -> NIGHT
                weekday && (m in 420..569 || m in 1020..1169) -> COMMUTE
                m < 12 * 60 -> MORNING
                else -> AFTERNOON
            }
        }
    }
}

/** The current slot; re-checked every minute so the card changes by itself at 12:00, 22:00 and so on. */
@Composable
fun rememberHeroSlot(): State<HeroSlot> = produceState(HeroSlot.of()) {
    while (true) {
        delay(60_000)
        val s = HeroSlot.of()
        if (s != value) {
            value = s
            MusicLog.add("Hero card: the time of day changed, now showing the ${s.title}")
        }
    }
}

/**
 * Picks the songs for the mix from the play history (newest first is fine).
 * 1) songs that were played in the same time-of-day slot, 2) if fewer than [min], filled up with the other recent songs.
 * [playedAt] is the play time in milliseconds. [key] removes repeats (use the video id).
 */
fun <T> heroPick(history: List<T>, playedAt: (T) -> Long, key: (T) -> Any, slot: HeroSlot, max: Int = 25, min: Int = 8): List<T> {
    val same = history.filter { HeroSlot.of(playedAt(it)) == slot }.distinctBy(key)
    val out = same.take(max).toMutableList()
    if (out.size < min) {
        val have = out.map(key).toSet()
        out += history.filter { key(it) !in have }.distinctBy(key).take(min - out.size)
    }
    MusicLog.add("Hero card: ${slot.title}, ${same.size} songs from this time of day, ${out.size} in the mix")
    return out
}

/** "Arijit Singh, Pritam, Shreya Ghoshal and more": most-played artists first, at most [max] names. */
fun heroArtistLine(artists: List<String>, max: Int = 4): String {
    val names = artists.map { it.trim() }.filter { it.isNotEmpty() }
    val ranked = names.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    if (ranked.isEmpty()) return ""
    val shown = ranked.take(max).joinToString(", ")
    return if (ranked.size > max) "$shown and more" else shown
}

/**
 * The card. Coloured from its cover (hand-written sampler, no Palette): gradient from the cover's deep tone to its dark
 * tone, play button in the bright tone. A grey or missing cover keeps the chosen look's colours.
 * [onPlay] starts the mix, [onOpen] is a tap on the card.
 */
@Composable
fun MusicHeroCard(
    slot: HeroSlot,
    coverUrl: String?,
    artistLine: String,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val look by MusicLook.current.collectAsStateWithLifecycle()
    val cover by rememberMusicCover(coverUrl)
    val deep by animateColorAsState(if (cover.found) cover.deep else look.glow, tween(600), label = "heroDeep")
    val dark by animateColorAsState(if (cover.found) cover.bg else look.bg, tween(600), label = "heroDark")
    val accent by animateColorAsState(if (cover.found) cover.accent else look.accent, tween(600), label = "heroAccent")
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White

    LaunchedEffect(slot, cover.found) {
        MusicLog.add("Hero card colours: " + if (cover.found) "taken from the cover" else "cover has no clear colour, using the ${look.name} look")
    }

    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(deep, dark)))
            .glassEdge(shape)
            .clickable(onClick = onOpen)
            .padding(18.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "FOR YOU NOW",
                    color = Color(0xB3FFFFFF), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(slot.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(slot.reason, color = Color(0xB3FFFFFF), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (artistLine.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(artistLine, color = Color(0x99FFFFFF), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(accent)
                        .clickable(onClick = onPlay)
                        .semantics { contentDescription = "Play the ${slot.title}" }
                        .padding(start = 12.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = onAccent, modifier = Modifier.size(22.dp))
                    Text("Play", color = onAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.width(16.dp))
            val coverShape = RoundedCornerShape(14.dp)
            Box(
                Modifier
                    .size(116.dp)
                    .shadow(22.dp, coverShape, ambientColor = accent, spotColor = accent)
                    .clip(coverShape)
                    .background(dark)
            ) {
                if (coverUrl != null) {
                    AsyncImage(model = coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                }
            }
        }
    }
}
