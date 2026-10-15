package com.example.streamnest

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

/** A mood and the genres that fit it. */
private data class MoodPick(val label: String, val genres: Set<String>)

private val MoodPicks = listOf(
    MoodPick("Chill", setOf("comedy", "romance", "animation", "family", "music")),
    MoodPick("Thriller night", setOf("thriller", "crime", "mystery", "horror")),
    MoodPick("Family", setOf("family", "animation", "adventure", "fantasy")),
    MoodPick("Laugh out loud", setOf("comedy")),
    MoodPick("Edge of the seat", setOf("action", "war", "thriller", "adventure")),
    MoodPick("Mind-bending", setOf("science fiction", "sci-fi", "sci-fi & fantasy", "mystery", "fantasy")),
    MoodPick("Feel something", setOf("drama", "romance", "biography", "history")),
)

/**
 * Search › Discover (shown before you type): pick a mood for a row of fitting titles from your Home rows, or let
 * "Surprise me" pick a well-rated one for you.
 */
@Composable
fun MoodDiscover(vm: AppViewModel, onOpen: (Meta) -> Unit) {
    val shelves by vm.shelves.collectAsStateWithLifecycle()
    val pool = remember(shelves) { shelves.flatMap { it.items }.distinctBy { it.id } }
    if (pool.isEmpty()) return
    var mood by remember { mutableIntStateOf(0) }
    val picks = remember(pool, mood) {
        val m = MoodPicks[mood]
        pool.filter { it.genres.orEmpty().any { g -> g.lowercase() in m.genres } }
            .sortedByDescending { it.imdbRating?.toDoubleOrNull() ?: 0.0 }.take(20)
    }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text("What's your mood?", Modifier.padding(horizontal = 20.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(MoodPicks.size) { i ->
                val on = i == mood
                Text(MoodPicks[i].label,
                    Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) Color.White else Color(0xFF15161C))
                        .border(1.dp, if (on) Color.White else Color(0xFF2A3242), RoundedCornerShape(20.dp))
                        .clickable { mood = i }.padding(horizontal = 16.dp, vertical = 9.dp),
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.Black else Color.White)
            }
        }
        AnimatedContent(picks, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) }, label = "mood") { list ->
            if (list.isEmpty()) Text("Nothing for this mood in your rows yet.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 13.sp, color = Color(0xFF9AA3B5))
            else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { m ->
                    Column(Modifier.width(108.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpen(m) }) {
                        AsyncImage(m.poster, m.name, Modifier.width(108.dp).height(160.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF15161C)),
                            contentScale = ContentScale.Crop)
                        Text(m.name, Modifier.padding(top = 4.dp), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFFD5D9E2))
                    }
                }
            }
        }
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp))
                .border(1.dp, Accent, RoundedCornerShape(26.dp))
                .clickable {
                    val good = pool.filter { (it.imdbRating?.toDoubleOrNull() ?: 0.0) >= 7.0 }.ifEmpty { pool }
                    good.randomOrNull()?.let(onOpen)
                }.padding(vertical = 13.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Surprise me", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
