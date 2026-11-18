package com.example.streamnest

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import java.util.Calendar

/**
 * Xstream's profile page ("Horizon"): a night sky over the glowing curved ground of the StreamX bar. The last profile used
 * is already chosen, so its Continue watching floats above the horizon at once (tap a poster: it plays straight away).
 * No Continue watching (new, or cleared): "Picked for you" from the profile's taste (picked titles, genres, languages,
 * what it finished). Profiles sit on the ground, two per row, the main profile bottom-right where the right thumb rests.
 * The chosen profile opens by itself after 10 s; touching the page stops the timer; tapping another profile shows its
 * posters (tap it again to open it).
 */

private data class HItem(val key: String, val title: String, val sub: String, val poster: String?, val wide: String?, val progress: Float?,
                         val cw: Progress? = null, val meta: Meta? = null)

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Late night"
}

private fun cwItem(p: Progress): HItem {
    val imdb = p.key.substringBefore(":")
    val show = p.title.substringBefore("  S").trim()
    val ep = Regex("S(\\d+)E(\\d+)").find(p.title.substringAfter("  ", ""))?.let { "S${it.groupValues[1]} E${it.groupValues[2]} · " } ?: ""
    val left = if (p.durationMs > 0) ((p.durationMs - p.positionMs) / 60_000).coerceAtLeast(1) else null
    return HItem(p.key, show, ep + (left?.let { "$it min left" } ?: "Continue"),
        if (imdb.startsWith("tt")) "https://images.metahub.space/poster/medium/$imdb/img" else p.image, p.image,
        if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else null, cw = p)
}

private fun pickItem(m: Meta): HItem = HItem(m.id, m.name,
    listOfNotNull(m.releaseInfo?.take(4), m.genres?.firstOrNull(), if (m.type == "series") "Series" else "Movie").joinToString(" · "),
    hdPoster(m) ?: m.poster, m.background ?: m.poster, null, meta = m)

@Composable
fun HorizonWhoIsWatching(vm: AppViewModel, active: Boolean, onPicked: () -> Unit, onPlay: (Screen.Player) -> Unit, onOpen: (Meta) -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val texts by vm.texts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lastId = texts["lastProfile"] ?: ""
    var selId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(profiles, lastId) { if (selId == null || profiles.none { it.id == selId }) selId = (profiles.firstOrNull { it.id == lastId } ?: profiles.firstOrNull())?.id }
    val sel = profiles.firstOrNull { it.id == selId }
    var editMode by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var adding by remember { mutableStateOf(false) }
    var stopped by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf<String?>(null) }
    var after by remember { mutableStateOf<(() -> Unit)?>(null) }
    val allowAuto = remember { !StartupState.autoPickDone && JoinIntent.code.value == null }
    LaunchedEffect(Unit) { StartupState.autoPickDone = true }
    val secs = texts["autoProfileSecs"]?.toIntOrNull() ?: 10
    val countdown = remember { Animatable(0f) }
    val leave = remember { Animatable(0f) }
    val enter = remember { Animatable(0f) }
    var restart by remember { mutableIntStateOf(0) }

    fun go(p: Profile, then: (() -> Unit)? = null) {
        if (leaving != null) return
        stopped = true
        ProfileLock.ask(p) { after = then; leaving = p.id }
    }
    LaunchedEffect(leaving) {
        val id = leaving ?: return@LaunchedEffect
        leave.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        profiles.firstOrNull { it.id == id }?.let { vm.selectProfile(it) }
        onPicked()
        after?.invoke(); after = null
    }
    LaunchedEffect(active) { if (active) enter.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    LaunchedEffect(active, selId, stopped, editMode, secs, restart) {
        countdown.snapTo(0f)
        if (!allowAuto || !active || stopped || editMode || secs <= 0 || sel == null || leaving != null) return@LaunchedEffect
        delay(900)
        countdown.animateTo(1f, tween(secs * 1000, easing = LinearEasing))
        sel.let { go(it) }
    }
    val left = (secs - (countdown.value * secs).toInt()).coerceAtLeast(1)

    // What floats above the horizon for the chosen profile.
    val locked = sel != null && ProfileLock.hasPin(sel.id)
    val fallback = remember { mutableStateMapOf<String, List<Meta>>() }
    val items: List<HItem> = remember(selId, texts, fallback[selId]) {
        val id = selId ?: return@remember emptyList()
        val cw = vm.progressOf(id).take(7).map(::cwItem)
        cw.ifEmpty { (vm.pickedOf(id).ifEmpty { fallback[id].orEmpty() }).filter { it.type == "movie" || it.type == "series" }.take(7).map(::pickItem) }
    }
    val isCw = items.firstOrNull()?.cw != null
    LaunchedEffect(selId) {
        val p = sel ?: return@LaunchedEffect
        if (vm.progressOf(p.id).isNotEmpty() || vm.pickedOf(p.id).isNotEmpty() || fallback.containsKey(p.id)) return@LaunchedEffect
        // Nothing yet: popular titles in the profile's genres (kids: animation and family).
        val g = if (p.kids) listOf("Animation", "Family") else vm.textOf(p.id, "tasteGenres")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        fallback[p.id] = runCatching {
            if (g.isNotEmpty()) vm.genreTitles(g, listOf("movie", "series"))
            else Api.fetch<CatalogResponse>("$CINEMETA/catalog/movie/top.json").metas
        }.getOrDefault(emptyList())
    }
    var center by remember(selId) { mutableIntStateOf(0) }
    val cur = items.getOrNull(center) ?: items.firstOrNull()

    fun play(it: HItem) {
        val p = sel ?: return
        val c = it.cw; val m = it.meta
        go(p) {
            when {
                c != null -> onPlay(Screen.Player(c.title, c.url, c.subs, c.key, c.image, c.positionMs))
                m != null && m.type == "series" -> vm.viewModelScope.launch {
                    val full = vm.fullMeta(m) ?: m
                    val first = full.videos.orEmpty().filter { (it.season ?: 0) > 0 && it.episode != null }.sortedWith(compareBy({ it.season }, { it.episode })).firstOrNull()
                    if (first == null) onOpen(full)
                    else onPlay(Screen.Player("${full.name}  S${first.season}E${first.episode}", "", emptyList(), key = first.id,
                        image = first.thumbnail ?: full.background ?: full.poster, type = "series", meta = full, find = true, quick = vm.qsActive()))
                }
                m != null -> onPlay(Screen.Player(m.name, "", emptyList(), key = m.id, image = m.background ?: m.poster, type = "movie", meta = m, find = true, quick = vm.qsActive()))
            }
        }
    }
    fun info(it: HItem) {
        val p = sel ?: return
        val m = it.meta ?: it.cw?.let { c -> Meta(id = c.key.substringBefore(":"), type = if (c.key.contains(":")) "series" else "movie", name = it.title, poster = it.poster) }
        if (m != null) go(p) { onOpen(m) }
    }

    BackHandler(editMode) { editMode = false }
    val accent = Palette.accent
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B0D18), Color(0xFF0F1014))))
            .graphicsLayer { alpha = 1f - leave.value }
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); if (countdown.value > 0f) stopped = true } },
    ) {
        val h = maxHeight
        val groundH = (h * 0.36f).coerceIn(250.dp, 330.dp)
        // Stars and the red glow rising from behind the horizon.
        Canvas(Modifier.fillMaxSize()) {
            val r = java.util.Random(7)
            repeat(40) { drawCircle(Color.White.copy(alpha = 0.15f + r.nextFloat() * 0.35f), 0.6f + r.nextFloat() * 1.3f, Offset(r.nextFloat() * size.width, r.nextFloat() * size.height * 0.55f)) }
            val gy = size.height - groundH.toPx()
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.26f), Color.Transparent), center = Offset(size.width / 2, gy), radius = size.width * 0.75f),
                radius = size.width * 0.75f, center = Offset(size.width / 2, gy))
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = groundH - 12.dp).graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 14.dp.toPx() }) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Wordmark("Xstream", 22.sp)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(greeting(), fontSize = 11.sp, color = Muted)
                    Text((if (isCw || locked) "" else "Welcome, ") + (sel?.name ?: ""), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Text(when { locked -> "LOCKED PROFILE"; isCw -> "CONTINUE WATCHING"; items.isEmpty() -> ""; else -> "PICKED FOR YOU" },
                Modifier.padding(start = 20.dp, top = 16.dp), fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF6A5C))
            AnimatedContent(selId to locked, Modifier.weight(1f).fillMaxWidth(), transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(200)) }, label = "stage") { (_, lk) ->
                if (lk) Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Lock, null, Modifier.size(42.dp), tint = Color(0x99FFFFFF))
                        Text("Unlock to see what's playing", Modifier.padding(top = 10.dp), fontSize = 13.sp, color = Muted)
                    }
                } else if (items.isEmpty()) Box(Modifier.fillMaxSize())
                else Stage(items, center, { center = it; stopped = true }, { play(it) })
            }
            // Title, details and the buttons.
            if (!locked && cur != null) Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(cur.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(cur.sub, fontSize = 12.sp, color = Color(0xFFC3C7D6), maxLines = 1)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.clip(RoundedCornerShape(22.dp)).background(Color.White).clickable { play(cur) }.padding(horizontal = 22.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp), tint = Color.Black)
                        Text(if (isCw) "Resume" else "Play", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0x1FFFFFFF)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(22.dp))
                        .clickable { info(cur) }.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = Color.White)
                        Text(" Info", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        // The ground with the profiles.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(groundH)) {
            HorizonGround(Modifier.matchParentSize(), edgeTop = 0.08f)
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(BrandRed))
                Text(if (editMode) "  Tap a profile to edit" else "  Who's watching?", fontSize = 13.sp, color = Color(0xFFC6C9D4))
                Spacer(Modifier.weight(1f))
                Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable { editMode = !editMode; stopped = true }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Edit, null, Modifier.size(14.dp), tint = Muted)
                    Text(if (editMode) " Done" else " Edit", fontSize = 12.sp, color = Muted)
                }
            }
            ProfileGround(vm, profiles, selId, editMode, countdown.value.takeIf { it > 0f && !stopped }, left,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 14.dp, top = 76.dp),
                onAdd = { stopped = true; adding = true },
            ) { p ->
                when {
                    editMode -> editing = p
                    p.id == selId -> go(p)
                    else -> { selId = p.id; stopped = false; restart++ }
                }
            }
        }
    }
    editing?.let { p -> ProfileEditor(vm, p) { editing = null } }
    if (adding) ProfileEditor(vm, null) { adding = false }
}

/** The fan of posters: the centre one big with its progress; the next two tilted at its sides; up to four more below. */
@Composable
private fun Stage(items: List<HItem>, center: Int, onCenter: (Int) -> Unit, onPlay: (HItem) -> Unit) {
    val c = center.coerceIn(0, items.lastIndex)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bigH = (maxHeight - 64.dp).coerceIn(150.dp, 290.dp)
        val bigW = bigH * 0.68f
        val sideH = bigH * 0.66f; val sideW = sideH * 0.7f
        @Composable fun Card(i: Int, w: Dp, h: Dp, x: Dp, y: Dp, rot: Float, big: Boolean) {
            val it = items[i]
            val pop = remember(it.key) { Animatable(0.85f) }
            LaunchedEffect(it.key) { pop.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 300f)) }
            Box(Modifier.offset(x, y).size(w, h).graphicsLayer { rotationZ = rot; scaleX = pop.value; scaleY = pop.value }
                .clip(RoundedCornerShape(if (big) 16.dp else 12.dp)).background(Color(0xFF1A1C23))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(if (big) 16.dp else 12.dp))
                .clickable { if (big) onPlay(it) else onCenter(i) }) {
                AsyncImage(it.poster, it.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (big) Box(Modifier.align(Alignment.Center).size(46.dp).clip(CircleShape).background(Color(0x66000000)).border(1.5.dp, Color(0xCCFFFFFF), CircleShape), Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, "Play", Modifier.size(26.dp), tint = Color.White)
                }
                it.progress?.let { p ->
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(BrandRed))
                    }
                }
            }
        }
        val cx = maxWidth / 2
        val top = 10.dp
        val l = if (c > 0) c - 1 else if (items.size > 2) items.lastIndex else -1
        val r = if (c + 1 <= items.lastIndex) c + 1 else if (items.size > 2) 0 else -1
        if (l >= 0 && l != c) Card(l, sideW, sideH, cx - bigW / 2 - sideW * 0.62f, top + bigH * 0.16f, -9f, false)
        if (r >= 0 && r != c && r != l) Card(r, sideW, sideH, cx + bigW / 2 - sideW * 0.38f, top + bigH * 0.16f, 9f, false)
        Card(c, bigW, bigH, cx - bigW / 2, top, 0f, true)
        // The rest, small, under the fan.
        val rest = items.indices.filter { it != c && it != l && it != r }.take(4)
        if (rest.isNotEmpty()) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rest.forEach { i ->
                val it = items[i]
                Box(Modifier.size(62.dp, 38.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1A1C23)).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(8.dp)).clickable { onCenter(i) }) {
                    AsyncImage(it.wide ?: it.poster, it.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
    }
}

/** Profiles on the ground: two per row, the main profile bottom-right (right-thumb reach), Add bottom-left. */
@Composable
private fun ProfileGround(vm: AppViewModel, profiles: List<Profile>, selId: String?, editMode: Boolean, ring: Float?, left: Int, modifier: Modifier,
                          onAdd: () -> Unit, onTap: (Profile) -> Unit) {
    val main = profiles.firstOrNull() ?: return
    val others = profiles.drop(1)
    val canAdd = profiles.size < 5
    // Cells before the main one; with an even count, "Add" moves to a small line below so main stays bottom-right.
    var before: List<Profile?> = others + (if (canAdd) listOf(null) else emptyList())
    var addLine = false
    if (before.size % 2 == 0 && canAdd) { before = others; addLine = true }
    val rows: List<List<Profile?>> = before.chunked(2).let { ch ->
        if (before.size % 2 == 1) ch.dropLast(1) + listOf(ch.last() + main) else ch + listOf(listOf<Profile?>(null, main).let { if (canAdd && !addLine) it else listOf(main) })
    }
    val avD = when (rows.size) { 1 -> 96.dp; 2 -> 72.dp; else -> 60.dp }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(verticalArrangement = Arrangement.spacedBy(if (rows.size > 2) 4.dp else 8.dp)) {
            rows.forEach { row ->
                Row(Modifier.width(300.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (row.size == 1) Spacer(Modifier.width(avD + 30.dp))
                    row.forEach { p ->
                        Column(Modifier.width(avD + 30.dp).clickable(remember { MutableInteractionSource() }, null) { if (p == null) onAdd() else onTap(p) },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            val selected = p != null && p.id == selId && !editMode
                            val d = if (selected) avD + 10.dp else avD
                            Box(Modifier.size(avD + 22.dp), Alignment.Center) {
                                if (p == null) Box(Modifier.size(avD * 0.85f).clip(CircleShape).background(Color(0xFF24262F)).border(1.dp, Color(0x33FFFFFF), CircleShape), Alignment.Center) {
                                    Icon(Icons.Default.Add, "Add profile", Modifier.size(28.dp), tint = Color(0xFFCFD2DC))
                                } else {
                                    ProfileAvatar(vm, p, d, Modifier.graphicsLayer { alpha = if (selected || editMode || selId == null) 1f else 0.6f })
                                    if (selected) Canvas(Modifier.size(d + 14.dp)) {
                                        val sw = 3.dp.toPx()
                                        drawArc(Color(0x22FFFFFF), 0f, 360f, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw))
                                        drawArc(BrandRed, -90f, 360f * (ring ?: 1f), false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
                                    }
                                    if (editMode) Box(Modifier.size(d).clip(CircleShape).background(Color(0x88000000)), Alignment.Center) { Icon(Icons.Default.Edit, "Edit ${p.name}", Modifier.size(20.dp)) }
                                    if (p.id == main.id) Box(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 6.dp).size(22.dp).clip(CircleShape).background(BrandRed)
                                        .border(2.dp, Color(0xFF0F1014), CircleShape), Alignment.Center) { Icon(Icons.Default.Star, "Main profile", Modifier.size(12.dp), tint = Color.White) }
                                }
                            }
                            Text(p?.name ?: "Add", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (p == null) Muted else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (selected && ring != null) Text("opening in ${left}s", fontSize = 10.sp, color = Muted)
                            else if (p?.kids == true) Text("KIDS", fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7DA2FF))
                        }
                    }
                }
            }
        }
        if (addLine) Text("＋ Add profile", Modifier.padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onAdd).padding(8.dp), fontSize = 12.sp, color = Muted)
    }
}
