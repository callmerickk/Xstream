package com.example.streamnest

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Source health (developer, 0.8): for each plugin / addon, how often it answered with links over the last 7 days,
 * how fast, how often its links failed to play and the last reason in plain words. Kept on this phone.
 */
object SourceHealth {
    @Serializable data class Day(val d: String, val asked: Int = 0, val hits: Int = 0, val ms: Long = 0, val played: Int = 0, val failed: Int = 0)
    @Serializable data class Src(val name: String, val days: List<Day> = emptyList(), val last: String = "", val lastAt: Long = 0)
    private var prefs: android.content.SharedPreferences? = null
    private val map = java.util.concurrent.ConcurrentHashMap<String, Src>()
    val tick = kotlinx.coroutines.flow.MutableStateFlow(0)
    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences("source_health", Context.MODE_PRIVATE)
        runCatching { Api.json.decodeFromString<List<Src>>(prefs!!.getString("all", "[]")!!).forEach { map[it.name] = it } }
    }
    private fun save() {
        tick.value++
        val p = prefs ?: return
        runCatching { p.edit().putString("all", Api.json.encodeToString(map.values.toList())).apply() }
    }
    private fun upd(name0: String, f: (Day) -> Day, last: String? = null) {
        val name = name0.removePrefix("CloudStream · ").removePrefix("Nuvio · ").trim().ifBlank { return }
        val t = today()
        map.compute(name) { _, s0 ->
            val s = s0 ?: Src(name)
            val keep = s.days.filter { it.d >= java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(System.currentTimeMillis() - 7 * 86_400_000L)) }
            val day = keep.firstOrNull { it.d == t } ?: Day(t)
            s.copy(days = keep.filter { it.d != t } + f(day), last = last ?: s.last, lastAt = if (last != null) System.currentTimeMillis() else s.lastAt)
        }
        save()
    }
    /** A plugin answered for a title: [links] found, in [ms]. */
    fun answered(name: String, links: Int, ms: Long) = upd(name, { it.copy(asked = it.asked + 1, hits = it.hits + if (links > 0) 1 else 0, ms = it.ms + ms) })
    fun played(name: String) = upd(name, { it.copy(played = it.played + 1) })
    fun failed(name: String, why: String) = upd(name, { it.copy(failed = it.failed + 1) }, why)

    fun all(): List<Src> = map.values.sortedBy { it.name.lowercase() }
}

@Composable
fun SourceHealthPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { SourceHealth.init(ctx) }
    val t by SourceHealth.tick.collectAsState()
    val list = remember(t) { SourceHealth.all() }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color(0x1FFFFFFF)).clickable(onClick = onBack), Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", tint = Color.White)
                }
                Text("Source health", Modifier.padding(start = 12.dp), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Text("Last 7 days on this phone. Struggling sources are never switched off: they keep searching in the background and are used when nothing else plays.",
                Modifier.padding(vertical = 12.dp), fontSize = 13.sp, color = Color(0xFF9AA1B2))
            if (list.isEmpty()) Text("Nothing yet — play a few titles first.", fontSize = 14.sp, color = Color(0xFF9AA1B2))
        }
        items(list, key = { it.name }) { s ->
            val asked = s.days.sumOf { it.asked }; val hits = s.days.sumOf { it.hits }
            val played = s.days.sumOf { it.played }; val failed = s.days.sumOf { it.failed }
            val rate = if (asked > 0) hits * 100 / asked else if (played + failed > 0) played * 100 / (played + failed) else 0
            val ms = if (asked > 0) s.days.sumOf { it.ms } / asked else 0L
            val (label, col) = when { rate >= 75 -> "Working" to Color(0xFF34D17A); rate >= 40 -> "Slow" to Color(0xFFF5B640); else -> "Struggling" to Color(0xFFFF7A70) }
            Column(Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF16181F))
                .border(1.dp, Color(0xFF2A2E39), RoundedCornerShape(16.dp)).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(label, Modifier.clip(RoundedCornerShape(9.dp)).background(col.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = col)
                    Text("$rate%", Modifier.padding(start = 10.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = col)
                }
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF2A2E39))) {
                    Box(Modifier.fillMaxWidth(rate / 100f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(col))
                }
                Text("Asked $asked · links $hits" + (if (ms > 0) " · ${"%.1f".format(ms / 1000f)} s" else "") + " · played $played · failed $failed",
                    Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Color(0xFF9AA1B2))
                if (s.last.isNotBlank()) Text("Last problem: ${s.last}", Modifier.padding(top = 2.dp), fontSize = 12.sp, color = Color(0xFFC9CEDB))
            }
        }
    }
}
