package com.example.streamnest

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val MpBg = Color(0xFF030303)
private val MpCardBg = Color(0xFF1D1D1D)
private val MpSub = Color(0xFFAAAAAA)
// Same red as the Elegant look. Fixed here so this page does not depend on the Look type.
private val MpRed = Color(0xFFE50914)

private data class MpWeekDay(val label: String, val ms: Long)

private data class MpSummary(
    val total: String,
    val finished: Int,
    val liked: Int,
    val streak: Int,
    val week: List<MpWeekDay>,
    val weekMs: Long,
    val topSong: MusicSongStat?,
    val topArtist: Pair<String, Long>?,
    val chips: List<String>,
    val since: Long,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicProfilePage(vm: AppViewModel, profileName: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile by vm.current.collectAsStateWithLifecycle()
    val pid = profile?.id ?: "p1"
    val name = profile?.name ?: profileName

    val data by MusicStats.data.collectAsStateWithLifecycle()
    val liked = remember(pid) { likedCount() }
    val s = remember(data, liked) { summarize(data, liked) }
    val maxMs = (s.week.maxOfOrNull { it.ms } ?: 0L).coerceAtLeast(10 * 60_000L)

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MpBg)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text("Profile", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            // Header: picture, name, taste chips
            MpCard(Modifier.padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(vm, profile, 84.dp, animate = true)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (s.since > 0L) "Counting since " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.since))
                            else "Stats start with your next song",
                            color = MpSub, fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.chips.forEach { c ->
                        Text(
                            c, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x1AFFFFFF)).padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // Tiles
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MpTile(s.total, "Time listened", Modifier.weight(1f))
                MpTile("${s.finished}", "Songs finished", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MpTile("${s.liked}", "Songs liked", Modifier.weight(1f))
                MpTile(if (s.streak == 1) "1 day" else "${s.streak} days", "Listening streak", Modifier.weight(1f))
            }

            // Your week
            Spacer(Modifier.height(12.dp))
            MpCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Your week", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(mpTime(s.weekMs), color = MpSub, fontSize = 13.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.week.forEachIndexed { i, d -> MpBar(d, maxMs, today = i == s.week.lastIndex) }
                }
                Text("Minutes per day", color = MpSub, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }

            // Most played
            Spacer(Modifier.height(12.dp))
            MpCard {
                Text("Most played song", color = MpSub, fontSize = 12.sp)
                val song = s.topSong
                if (song == null) {
                    Text("Nothing yet. Play something.", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
                } else {
                    Text(song.title.ifBlank { "Unknown song" }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        listOf(song.artist, mpTime(song.ms), if (song.finished > 0) "finished ${song.finished}×" else "").filter { it.isNotBlank() }.joinToString(" · "),
                        color = MpSub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text("Most played artist", color = MpSub, fontSize = 12.sp)
                val artist = s.topArtist
                if (artist == null) {
                    Text("Nothing yet.", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
                } else {
                    Text(artist.first, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Text(mpTime(artist.second), color = MpSub, fontSize = 13.sp)
                }
            }

            if (Feature.musicOrbit) {
                Spacer(Modifier.height(12.dp))
                MusicAccountSettings(pid)
                if (Feature.musicLook2) { Spacer(Modifier.height(12.dp)); MusicLookCard() }
            }

            // Share
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MpRed)
                    .clickable {
                        MusicLog.add("Profile: share my week tapped (profile $pid)")
                        scope.launch {
                            val bmp = withContext(Dispatchers.Default) { weekCard(name, s) }
                            shareWeekCard(ctx, bmp, "My week on Xstream Music: ${s.total} listened")
                        }
                    }
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text("Share my week", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
            }

            Text(
                "Stats count what you play in Xstream Music on this phone, from the day counting started. Taste chips are guesses from song titles, artists and the hours you listen.",
                color = MpSub, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

@Composable
private fun MpCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MpCardBg)
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(22.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun MpTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF12151C))
            .border(1.dp, Color(0xFF262A34), RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(label, color = MpSub, fontSize = 12.sp)
    }
}

@Composable
private fun RowScope.MpBar(day: MpWeekDay, maxMs: Long, today: Boolean) {
    val frac by animateFloatAsState(
        if (maxMs <= 0L) 0f else (day.ms.toFloat() / maxMs).coerceIn(0f, 1f),
        tween(600), label = "weekBar",
    )
    Column(
        Modifier.weight(1f).fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(
            if (day.ms >= 60_000L) "${day.ms / 60_000L}" else " ",
            color = if (today) Color.White else MpSub, fontSize = 10.sp, maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(24.dp)
                .height(maxOf(4.dp, 96.dp * frac))
                .clip(RoundedCornerShape(8.dp))
                .background(if (today) MpRed else Color(0x40FFFFFF)),
        )
        Spacer(Modifier.height(4.dp))
        Text(day.label, color = if (today) Color.White else MpSub, fontSize = 11.sp, fontWeight = if (today) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

// ───────────────────────── numbers ─────────────────────────

/** Songs liked comes from the profile's library, not from the stats file. */
private fun likedCount(): Int = MusicLibraryStore.active().liked.size

private fun mpTime(ms: Long): String {
    val m = ms / 60_000L
    return if (m < 60) "$m min" else "${m / 60}h ${m % 60}m"
}

private fun summarize(d: MusicStatsData, liked: Int): MpSummary {
    val fmt = SimpleDateFormat("EEE", Locale.getDefault())
    val week = (6 downTo 0).map { back ->
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -back) }
        MpWeekDay(fmt.format(c.time).take(3), d.dayMs[MusicStats.key(c)] ?: 0L)
    }
    // Streak: days in a row with at least a minute of music. Today may still be empty.
    fun has(c: Calendar) = (d.dayMs[MusicStats.key(c)] ?: 0L) >= 60_000L
    val c = Calendar.getInstance()
    if (!has(c)) c.add(Calendar.DAY_OF_YEAR, -1)
    var streak = 0
    while (has(c) && streak < 400) { streak++; c.add(Calendar.DAY_OF_YEAR, -1) }

    return MpSummary(
        total = mpTime(d.totalMs),
        finished = d.finished,
        liked = liked,
        streak = streak,
        week = week,
        weekMs = week.sumOf { it.ms },
        topSong = d.songs.values.filter { it.ms > 0L }.maxByOrNull { it.ms },
        topArtist = d.artists.entries.maxByOrNull { it.value }?.let { it.key to it.value },
        chips = chipsFor(d),
        since = d.since,
    )
}

private val MoodWords = listOf(
    "Punjabi fan" to listOf("punjabi", "jatt", "jatti", "sidhu", "diljit", "dhillon", "aujla", "kudi", "munda"),
    "Romantic" to listOf("love", "pyar", "ishq", "mohabbat", "romantic", "dil"),
    "Sad songs" to listOf("sad", "bewafa", "judai", "broken", "alone", "tanha"),
    "Party mode" to listOf("party", "remix", "bass", "dance", "dj "),
    "Chill" to listOf("lofi", "lo-fi", "chill", "slowed", "relax", "sleep"),
)

private fun chipsFor(d: MusicStatsData): List<String> {
    val total = d.totalMs
    if (total < 10 * 60_000L) return listOf("New here")
    val out = mutableListOf<String>()
    fun share(hours: List<Int>): Float {
        var sum = 0L
        for (h in hours) sum += d.hourMs.getOrElse(h) { 0L }
        return sum.toFloat() / total
    }
    if (share(listOf(23, 0, 1, 2, 3)) >= 0.25f) out += "Night owl"
    if (share(listOf(5, 6, 7, 8)) >= 0.30f) out += "Early bird"
    if (share(listOf(18, 19, 20, 21, 22)) >= 0.45f) out += "Evening listener"
    d.artists.maxByOrNull { it.value }?.let {
        if (total >= 30 * 60_000L && it.value.toFloat() / total >= 0.25f) out += "${it.key.take(18)} fan"
    }
    MoodWords.map { (label, words) ->
        var w = 0L
        for (song in d.songs.values) {
            val t = (song.title + " " + song.artist).lowercase()
            if (words.any { t.contains(it) }) w += song.ms
        }
        label to w
    }.filter { it.second.toFloat() / total >= 0.20f }
        .sortedByDescending { it.second }.take(2).forEach { out += it.first }
    if (d.songs.values.any { it.finished >= 5 }) out += "On repeat"
    if (d.dayMs.values.any { it >= 3 * 3_600_000L }) out += "Marathon listener"
    return out.ifEmpty { listOf("Getting started") }.take(6)
}

// ───────────────────────── share card ─────────────────────────

private fun String.cut(n: Int) = if (length <= n) this else take(n - 1) + "…"

private fun weekCard(name: String, s: MpSummary): Bitmap {
    val w = 1080
    val h = 1350
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF2A0A10.toInt(), 0xFF030303.toInt(), Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    p.shader = null

    fun text(str: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
        p.color = color
        p.textSize = size
        p.isFakeBoldText = bold
        p.textAlign = align
        c.drawText(str, x, y, p)
    }

    val white = 0xFFFFFFFF.toInt()
    val grey = 0xFFAAAAAA.toInt()
    val red = 0xFFE50914.toInt()

    text("Xstream Music", 90f, 130f, 40f, red, bold = true)
    text("${name.cut(16)}'s week", 90f, 250f, 84f, white, bold = true)
    text("${s.weekMs.let { mpTime(it) }} listened", 90f, 330f, 52f, grey)

    val baseline = 930f
    val maxH = 380f
    val maxMs = (s.week.maxOfOrNull { it.ms } ?: 0L).coerceAtLeast(10 * 60_000L)
    s.week.forEachIndexed { i, d ->
        val left = 90f + i * 135f
        val bh = maxOf(10f, maxH * (d.ms.toFloat() / maxMs))
        val today = i == s.week.lastIndex
        p.color = if (today) red else 0x40FFFFFF
        c.drawRoundRect(RectF(left, baseline - bh, left + 90f, baseline), 20f, 20f, p)
        if (d.ms >= 60_000L) text("${d.ms / 60_000L}m", left + 45f, baseline - bh - 16f, 30f, if (today) white else grey, align = Paint.Align.CENTER)
        text(d.label, left + 45f, baseline + 52f, 32f, if (today) white else grey, bold = today, align = Paint.Align.CENTER)
    }

    text("Most played song", 90f, 1085f, 34f, grey)
    text((s.topSong?.title?.ifBlank { null } ?: "-").cut(30), 90f, 1145f, 50f, white, bold = true)
    text("Most played artist", 90f, 1215f, 34f, grey)
    text((s.topArtist?.first ?: "-").cut(30), 90f, 1275f, 50f, white, bold = true)
    return bmp
}

/**
 * Saves the card in Pictures/Xstream (Android 10+, no permission needed) and opens the share sheet.
 * Below Android 10 it shares the text only.
 */
private suspend fun shareWeekCard(ctx: Context, bmp: Bitmap, caption: String) {
    val uri: Uri? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext null
        runCatching {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "appx-week-${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Xstream")
            }
            val u = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            if (u != null) ctx.contentResolver.openOutputStream(u)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            u
        }.onFailure { MusicLog.add("Profile: could not save the week card (${it.message})") }.getOrNull()
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TEXT, caption)
        if (uri != null) {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            type = "text/plain"
        }
    }
    MusicLog.add(if (uri != null) "Profile: sharing the week card as a picture" else "Profile: sharing the week as text (no picture on this Android version)")
    ctx.startActivity(Intent.createChooser(send, "Share my week").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
