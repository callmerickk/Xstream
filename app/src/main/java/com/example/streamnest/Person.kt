package com.example.streamnest

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/* Actor page: photo, facts, biography and every acting credit (crew jobs are left out). */

/** Tap without any ripple / highlight. */
fun Modifier.quietClick(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

data class PersonCredit(
    val tmdbId: String, val kind: String, val title: String, val date: String?, val poster: String?,
    val character: String, val votes: Int,
)

data class PersonInfo(
    val name: String, val photo: String?, val bio: String, val birthday: String?, val deathday: String?, val place: String?,
    val department: String?, val gender: Int, val aka: List<String>, val credits: List<PersonCredit>,
)

private val personCache = HashMap<String, PersonInfo>()

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

/** Talk shows, news and self-appearances aren't roles. */
private val notRoles = setOf(10763, 10767)

suspend fun AppViewModel.tmdbPerson(id: String): PersonInfo {
    personCache[id]?.let { return it }
    val key = tmdbKey()
    val o = Api.json.parseToJsonElement(
        Api.get("https://api.themoviedb.org/3/person/$id?api_key=$key&append_to_response=combined_credits,external_ids")
    ).jsonObject
    val cast = ((o["combined_credits"] as? JsonObject)?.get("cast") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val credits = cast.mapNotNull { c ->
        val kind = c.s("media_type")?.takeIf { it == "movie" || it == "tv" } ?: return@mapNotNull null
        val genres = (c["genre_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
        if (genres.any { it in notRoles }) return@mapNotNull null
        val character = c.s("character").orEmpty()
        if (character.matches(Regex("(?i)^(self|himself|herself|themselves)\\b.*"))) return@mapNotNull null
        PersonCredit(
            c.s("id") ?: return@mapNotNull null, kind,
            c.s("title") ?: c.s("name") ?: return@mapNotNull null,
            c.s("release_date") ?: c.s("first_air_date"),
            c.s("poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" },
            character,
            (c["vote_count"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }
        // One entry per title (an actor can have several roles / episode credits in one show).
        .groupBy { it.kind + it.tmdbId }.map { (_, v) -> v.first().copy(character = v.map { it.character }.filter { it.isNotBlank() }.distinct().joinToString(" / ")) }
        .sortedByDescending { it.date ?: "" }
    return PersonInfo(
        name = o.s("name") ?: "",
        photo = o.s("profile_path")?.let { "https://image.tmdb.org/t/p/w342$it" },
        bio = o.s("biography").orEmpty(),
        birthday = o.s("birthday"), deathday = o.s("deathday"), place = o.s("place_of_birth"),
        department = o.s("known_for_department"),
        gender = (o["gender"] as? JsonPrimitive)?.intOrNull ?: 0,
        aka = (o["also_known_as"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
        credits = credits,
    ).also { personCache[id] = it }
}

private val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "1990-07-14" → "14 Jul 1990 (age 36)". */
private fun bornText(birthday: String?, deathday: String?): String? {
    val b = birthday?.split("-")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 } ?: return null
    val (y, m, d) = b
    val end = deathday?.split("-")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 }
        ?: java.util.Calendar.getInstance().let { listOf(it.get(java.util.Calendar.YEAR), it.get(java.util.Calendar.MONTH) + 1, it.get(java.util.Calendar.DAY_OF_MONTH)) }
    var age = end[0] - y
    if (end[1] < m || (end[1] == m && end[2] < d)) age--
    val date = "$d ${monthNames.getOrElse(m - 1) { "" }} $y"
    return if (deathday != null) "$date (died at $age)" else "$date (age $age)"
}

@Composable
fun PersonScreen(vm: AppViewModel, p: Screen.Person, onBack: () -> Unit, onOpen: (Meta) -> Unit) {
    val scope = rememberCoroutineScope()
    var info by remember(p.personId) { mutableStateOf<PersonInfo?>(null) }
    var error by remember(p.personId) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val noKey = vm.tmdbKey().isEmpty()
    LaunchedEffect(p.personId, attempt) {
        if (noKey || p.personId.isEmpty()) return@LaunchedEffect
        error = null
        runCatching { vm.tmdbPerson(p.personId) }
            .onSuccess { info = it }
            .onFailure { error = "Couldn't load this page. Check your connection and try again." }
    }
    var filter by remember { mutableStateOf("all") }
    var bioOpen by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }

    val credits = info?.credits.orEmpty()
    val shown = remember(credits, filter) {
        when (filter) { "movie" -> credits.filter { it.kind == "movie" }; "tv" -> credits.filter { it.kind == "tv" }; else -> credits }
    }

    fun openCredit(c: PersonCredit) {
        if (opening) return
        opening = true
        scope.launch {
            val imdb = vm.tmdbToImdb(TmRec(c.tmdbId, c.kind, c.title, c.date?.take(4), c.poster))
            opening = false
            if (imdb.isNullOrBlank()) Snack.show("This title isn't available to open yet")
            else onOpen(Meta(id = imdb, type = if (c.kind == "tv") "series" else "movie", name = c.title, poster = c.poster))
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── Header: radial red glow, round photo, name, role ──
            item(span = { GridItemSpan(maxLineSpan) }) {
                val nm = info?.name?.ifBlank { null } ?: p.name
                val photo = info?.photo ?: p.photo
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            // Full-bleed: reach past the grid's 16dp side padding.
                            .layout { m, cs ->
                                val extra = 32.dp.roundToPx()
                                val w = cs.maxWidth + extra
                                val pl = m.measure(cs.copy(minWidth = w, maxWidth = w))
                                layout(cs.maxWidth, pl.height) { pl.place(-extra / 2, 0) }
                            }
                            .height(250.dp).drawBehind {
                            val c = Offset(size.width / 2f, 0f)
                            val r = size.width * 0.9f
                            drawRect(Brush.radialGradient(0f to Color(0xFF3A0E17), 0.7f to Color(0xFF07080C), 1f to Color(0x0007080C), center = c, radius = r))
                        },
                    ) {
                        Box(
                            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 40.dp).size(150.dp)
                                .shadow(32.dp, CircleShape, ambientColor = Color(0xFFE50914), spotColor = Color(0xFFE50914))
                                .clip(CircleShape).background(Surface1).border(3.dp, Color(0xFFE50914), CircleShape),
                            Alignment.Center,
                        ) {
                            Text(nm.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8A94A8))
                            if (photo != null) AsyncImage(photo, nm, Modifier.fillMaxSize().padding(3.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        }
                    }
                    Text(nm, Modifier.padding(top = 8.dp), fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    info?.let { i ->
                        val role = when {
                            i.department == null || i.department == "Acting" -> when (i.gender) { 1 -> "Actress"; 2 -> "Actor"; else -> "Actor" }
                            else -> i.department
                        }
                        val country = i.place?.split(",")?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                        Text(listOfNotNull(role, country).joinToString(" · "), Modifier.padding(top = 6.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF9AA3B5))
                    }
                }
            }

            val err = error
            val inf = info
            when {
                noKey -> item(span = { GridItemSpan(maxLineSpan) }) {
                    PersonNote("Add a TMDB key in Help & Settings > Integrations to see actor pages.")
                }
                err != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        PersonNote(err)
                        Text("Try again", Modifier.padding(top = 12.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF15161C))
                            .quietClick { attempt++ }.padding(horizontal = 18.dp, vertical = 9.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                inf == null -> {
                    item(span = { GridItemSpan(maxLineSpan) }) { PersonShimmer(Modifier.padding(top = 18.dp).fillMaxWidth().height(78.dp), 20) }
                    item(span = { GridItemSpan(maxLineSpan) }) { PersonShimmer(Modifier.fillMaxWidth().height(120.dp), 16) }
                    items(6) { PersonShimmer(Modifier.fillMaxWidth().height(168.dp), 14) }
                }
                else -> {
                    val i = inf
                    // ── Stats ──
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val movies = i.credits.count { it.kind == "movie" }
                        val series = i.credits.count { it.kind == "tv" }
                        val first = i.credits.mapNotNull { it.date?.take(4)?.toIntOrNull() }.minOrNull()
                        Row(Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF0F1015))
                            .border(1.dp, Color(0xFF1D1E25), RoundedCornerShape(20.dp)).height(IntrinsicSize.Min)) {
                            PersonStat("$movies", "Movies")
                            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF1D1E25)))
                            PersonStat("$series", "Series")
                            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF1D1E25)))
                            PersonStat(first?.toString() ?: "—", "First role")
                        }
                    }
                    // ── Facts 2×2 ──
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val knownFor = i.credits.maxByOrNull { it.votes }?.title ?: i.department
                        val facts = listOf(
                            "BORN" to (bornText(i.birthday, i.deathday) ?: "—"),
                            "FROM" to (i.place ?: "—"),
                            "KNOWN FOR" to (knownFor ?: "—"),
                            "ALSO KNOWN AS" to (i.aka.firstOrNull() ?: "—"),
                        )
                        Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            facts.chunked(2).forEach { row ->
                                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    row.forEach { (k, v) ->
                                        Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Color(0xFF0F1015))
                                            .border(1.dp, Color(0xFF1D1E25), RoundedCornerShape(16.dp)).padding(12.dp)) {
                                            Text(k, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF8A94A8), letterSpacing = 0.4.sp)
                                            Text(v, Modifier.padding(top = 3.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // ── Biography ──
                    if (i.bio.isNotBlank()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.quietClick { bioOpen = !bioOpen }) {
                            Text(i.bio, fontSize = 14.5.sp, lineHeight = 21.sp, color = Color(0xFFD7DAE2),
                                maxLines = if (bioOpen) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                            if (i.bio.length > 220) Text(if (bioOpen) "Show less" else "Read more", Modifier.padding(top = 4.dp),
                                fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFF3B45))
                        }
                    }
                    // ── Filter chips ──
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("all" to "All", "movie" to "Movies", "tv" to "Series").forEach { (k, label) ->
                                val on = filter == k
                                Text(label, Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) Color.White else Color(0xFF15161C))
                                    .quietClick { filter = k }.padding(horizontal = 18.dp, vertical = 9.dp),
                                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.Black else Color(0xFFC9CED9))
                            }
                        }
                    }
                    if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { PersonNote("No credits to show.") }
                    // ── Credits grid ──
                    items(shown, key = { it.kind + it.tmdbId }) { c ->
                        Column(Modifier.quietClick { openCredit(c) }) {
                            Box(Modifier.fillMaxWidth().height(168.dp).clip(RoundedCornerShape(14.dp)).background(Surface1)) {
                                Text(c.title, Modifier.align(Alignment.BottomStart).padding(10.dp), fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 3)
                                if (c.poster != null) AsyncImage(c.poster, c.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                            Text(c.title, Modifier.padding(top = 6.dp), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(c.date?.take(4), if (c.kind == "tv") "Series" else "Movie").joinToString(" · "),
                                fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = Color(0xFF9AA3B5), maxLines = 1)
                            if (c.character.isNotBlank()) Text(c.character, fontSize = 11.sp, color = Color(0xFF6F788A), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        // Back button stays on top.
        Box(
            Modifier.statusBarsPadding().padding(start = 16.dp, top = 6.dp).size(42.dp).clip(CircleShape).background(Color(0xFF15161C)).quietClick(onBack),
            Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", Modifier.size(26.dp), tint = Color.White) }
        if (opening) Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color(0xE615161C)).padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text("Opening…", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RowScope.PersonStat(value: String, label: String) =
    Column(Modifier.weight(1f).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF8A94A8))
    }

@Composable
private fun PersonNote(text: String) =
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp), fontSize = 14.sp, color = Color(0xFF9AA3B5), textAlign = TextAlign.Center)

@Composable
private fun PersonShimmer(modifier: Modifier, corner: Int) {
    val tr = rememberInfiniteTransition(label = "shimmer")
    val a by tr.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Box(modifier.graphicsLayer { alpha = a }.clip(RoundedCornerShape(corner.dp)).background(Color(0xFF15161C)))
}
