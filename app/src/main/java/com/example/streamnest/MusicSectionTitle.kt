package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Music prompt 5 (musicLook2), part 4: the new section title.
 * Big bold title, a small grey reason line under it, and a "More" pill on the right.
 *
 * Plain values and lambdas only, because the old SectionTitle and the shelf types live inside MusicApp.kt.
 * Only called when Feature.musicLook2 is on; with the switch off the old SectionTitle is used.
 *
 * [reason] null or blank: no second line, the title sits alone.
 * [moreLabel] / [onMore]: the pill shows only when [onMore] is not null.
 */
@Composable
fun MusicSectionTitle(
    title: String,
    reason: String? = null,
    moreLabel: String = "More",
    onMore: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Same spacing as the old title (start 16, end 12, top 22, bottom 10), so the lists below do not jump.
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!reason.isNullOrBlank()) {
                Text(
                    reason,
                    color = Color(0xFFAAAAAA), fontSize = 12.sp, lineHeight = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (onMore != null) {
            val pill = glassOr(Color(0x14FFFFFF))
            Text(
                moreLabel,
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(pill)
                    .border(1.dp, Color(0x33FFFFFF), CircleShape)
                    .clickable(onClick = onMore)
                    .semantics { contentDescription = "$moreLabel: $title" }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * The reason line under a section title. Only says something that is true:
 *  1) If the shelf comes with its own small line (the "strapline" from YouTube Music), that line is used as it is,
 *     in normal letters (the old title showed it in capitals).
 *  2) Otherwise, if one of the shelf's artists is also in the user's play history, "Because you played <artist>".
 *     The artist who is played most comes first.
 *  3) Otherwise null: no second line. We do not invent a reason.
 *
 * [shelfArtists]: artist names of the songs in the shelf.
 * [historyArtists]: artist names from the play history (any order; repeats count as plays).
 *
 * This is not a composable and writes to the log, so call it inside remember(shelf.title) { ... }.
 */
fun musicReasonLine(strap: String?, shelfArtists: List<String>, historyArtists: List<String>, title: String = ""): String? {
    val s = strap?.trim().orEmpty()
    if (s.isNotEmpty()) {
        // A strapline that is all capitals ("LISTEN AGAIN") is turned into "Listen again".
        val line = if (s == s.uppercase() && s.any { it.isLetter() }) s.lowercase().replaceFirstChar { it.uppercase() } else s
        MusicLog.add("Section \"$title\": reason line taken from the shelf's own strapline: $line")
        return line
    }
    val inShelf = shelfArtists.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    if (inShelf.isEmpty()) {
        MusicLog.add("Section \"$title\": no artists in this shelf, so no reason line")
        return null
    }
    val ranked = historyArtists.map { it.trim() }.filter { it.isNotEmpty() }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    val hit = ranked.firstOrNull { it in inShelf }
    return if (hit != null) {
        MusicLog.add("Section \"$title\": reason line \"Because you played $hit\" (this artist is in the shelf and in the history)")
        "Because you played $hit"
    } else {
        MusicLog.add("Section \"$title\": no shelf artist is in the play history, so no reason line")
        null
    }
}
