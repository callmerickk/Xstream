package com.example.streamnest

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One title on one day: how long you watched it that day, and where you were in it at the end. */
@Serializable data class WatchDay(
    val d: String, val k: String, val t: String, val img: String? = null, val type: String = "movie",
    val ms: Long = 0, val pos: Long = 0, val dur: Long = 0, val at: Long = 0,
    /** watched time per hour of the day (0..23) */
    val hours: Map<Int, Long> = emptyMap(),
)

/**
 * Xstream's watch diary (per profile, on this phone): filled while you watch, read by My Space's charts. Keeps 90 days.
 * Only real watching counts: the time between two saves of the same title (seeks and jumps are ignored).
 */
object WatchLog {
    val entries = MutableStateFlow<List<WatchDay>>(emptyList())
    private var pid: String? = null
    private val lastPos = HashMap<String, Pair<Long, Long>>() // key → (position, when)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("watchlog", Context.MODE_PRIVATE)
    private val dayFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun load(ctx: Context, profile: String?) {
        if (profile == null || profile == pid) return
        pid = profile; lastPos.clear()
        entries.value = runCatching { Api.json.decodeFromString<List<WatchDay>>(prefs(ctx).getString("w_$profile", null) ?: "[]") }.getOrDefault(emptyList())
    }

    /** Takes a title (a movie, or a whole series when [key] is one of its episodes) out of the diary. */
    fun remove(ctx: Context, profile: String?, key: String) {
        if (profile == null) return
        if (profile != pid) load(ctx, profile)
        val base = key.substringBefore(":")
        val left = entries.value.filterNot { it.k == key || it.k.substringBefore(":") == base }
        if (left.size == entries.value.size) return
        entries.value = left
        prefs(ctx).edit().putString("w_$profile", Api.json.encodeToString(left)).apply()
    }

    fun record(ctx: Context, profile: String?, p: Progress) {
        if (profile == null) return
        if (profile != pid) load(ctx, profile)
        val now = System.currentTimeMillis()
        val prev = lastPos[p.key]
        lastPos[p.key] = p.positionMs to now
        val delta = if (prev == null) 0L else (p.positionMs - prev.first).takeIf { it in 1..(now - prev.second + 5_000).coerceAtMost(600_000) } ?: 0L
        val today = dayFmt.format(Date(now))
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val list = entries.value.toMutableList()
        val i = list.indexOfFirst { it.d == today && it.k == p.key }
        val base = if (i >= 0) list[i] else WatchDay(today, p.key, p.title, p.image, if (p.key.contains(":")) "series" else "movie")
        val upd = base.copy(t = p.title, img = p.image ?: base.img, ms = base.ms + delta, pos = p.positionMs, dur = p.durationMs, at = now,
            hours = if (delta > 0) base.hours + (hour to ((base.hours[hour] ?: 0L) + delta)) else base.hours)
        if (i >= 0) list[i] = upd else list.add(0, upd)
        val cutoff = dayFmt.format(Date(now - 90L * 86_400_000L))
        entries.value = list.filter { it.d >= cutoff }
        prefs(ctx).edit().putString("w_$profile", Api.json.encodeToString(entries.value)).apply()
    }
}

private val ChartCard = Color(0xFF14161C)
private val ChartLine = Color(0xFF262A34)
private val ChartMuted = Color(0xFF9AA3B5)

private fun dur(ms: Long): String { val m = ms / 60_000L; return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" }

/** My Space charts: this week (tap a day to see what you watched and how far), movies vs series, and usual times. */
@Composable
fun WatchCharts(onOpenKey: (String) -> Unit) {
    val all by WatchLog.entries.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val days = remember { (6 downTo 0).map { back -> Date(System.currentTimeMillis() - back * 86_400_000L) } }
    var sel by remember { mutableIntStateOf(6) }
    val perDay = days.map { d -> val k = fmt.format(d); all.filter { it.d == k } }
    val totals = perDay.map { l -> l.sumOf { it.ms } }
    val max = (totals.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp).fillMaxWidth().clip(shape).background(ChartCard).border(1.dp, ChartLine, shape).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("This week", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(dur(totals.sum()), fontSize = 12.sp, color = ChartMuted)
        }
        Row(Modifier.fillMaxWidth().height(130.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            days.forEachIndexed { i, d ->
                val on = i == sel
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).clickable { sel = i }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    if (totals[i] > 0) Text(dur(totals[i]), fontSize = 8.5.sp, color = if (on) Color.White else ChartMuted, maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.fillMaxWidth().height((6 + 84 * totals[i] / max).toInt().dp).clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                        .background(if (on) Accent else Color(0xFF2E3442)))
                    Text(SimpleDateFormat("EEE", Locale.getDefault()).format(d).take(1), Modifier.padding(top = 5.dp), fontSize = 10.sp, color = ChartMuted)
                }
            }
        }
        val dayList = perDay[sel].sortedByDescending { it.at }
        Text(SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(days[sel]), Modifier.padding(top = 12.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ChartMuted)
        if (dayList.isEmpty()) Text("Nothing watched this day.", Modifier.padding(top = 6.dp), fontSize = 12.5.sp, color = ChartMuted)
        dayList.forEach { w ->
            val frac = if (w.dur > 0) w.pos.toFloat() / w.dur else 0f
            val (label, color) = when {
                frac >= 0.95f -> "Finished" to Color(0xFF3DD68C)
                w.ms < 10 * 60_000L && frac < 0.2f -> "Skipped after ${dur(w.ms)}" to Color(0xFFFF8A80)
                else -> "Stopped at ${(frac * 100).toInt()}%" to Color(0xFFFFB13B)
            }
            Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF101217)).clickable { onOpenKey(w.k) }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(w.img, null, Modifier.size(34.dp, 50.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1C2029)), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(w.t.replace("  ", " · "), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Watched ${dur(w.ms)} · ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(w.at))}", fontSize = 10.5.sp, color = ChartMuted)
                }
                Text(label, Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 3.dp), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = color)
            }
        }
    }
    // Movies vs series (last 30 days) and when you usually watch.
    val month = remember(all) { val cut = fmt.format(Date(System.currentTimeMillis() - 30L * 86_400_000L)); all.filter { it.d >= cut } }
    val movieMs = month.filter { it.type != "series" }.sumOf { it.ms }
    val seriesMs = month.filter { it.type == "series" }.sumOf { it.ms }
    val byHour = remember(month) { (0..23).map { h -> month.sumOf { it.hours[h] ?: 0L } } }
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp).fillMaxWidth().clip(shape).background(ChartCard).border(1.dp, ChartLine, shape).padding(14.dp)) {
        Text("Last 30 days", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        val tot = (movieMs + seriesMs).coerceAtLeast(1L)
        Row(Modifier.padding(top = 12.dp).fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2E3442))) {
            if (movieMs > 0) Box(Modifier.weight(movieMs.toFloat() / tot).fillMaxHeight().background(Accent))
            if (seriesMs > 0) Box(Modifier.weight(seriesMs.toFloat() / tot).fillMaxHeight().background(Color(0xFF3B8BFF)))
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(Accent, "Movies · ${dur(movieMs)}"); Legend(Color(0xFF3B8BFF), "Series · ${dur(seriesMs)}")
        }
        Text("When you watch", Modifier.padding(top = 16.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        val hmax = (byHour.maxOrNull() ?: 0L).coerceAtLeast(1L)
        Row(Modifier.padding(top = 8.dp).fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            byHour.forEach { v -> Box(Modifier.weight(1f).height((4 + 40 * v / hmax).toInt().dp).clip(RoundedCornerShape(2.dp)).background(Accent.copy(alpha = 0.25f + 0.75f * v / hmax))) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            listOf("12a", "6a", "12p", "6p", "11p").forEachIndexed { i, s -> Text(s, Modifier.weight(1f), fontSize = 9.sp, color = ChartMuted) }
        }
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(c)); Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 11.5.sp, color = Color(0xFFD5D9E2))
    }
}
