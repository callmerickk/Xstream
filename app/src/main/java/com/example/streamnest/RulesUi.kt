package com.example.streamnest

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/*
 * 0.9.3 (Feature.devRules): Developer's Corner › Rules. One card per rule group: each value with its default and who set
 * it, edited in place; Try on this phone / Reset / Publish to everyone per card and for everything; Reset for everyone;
 * the history (last 20 publishes and the Source Test's automatic changes) with Undo. Developer only (Developer's Corner).
 */

@Composable
private fun Pill(text: String, strong: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(text, Modifier.clip(RoundedCornerShape(18.dp)).background(if (strong) BrandRed else Color(0x1FFFFFFF)).clickable(enabled = enabled) { onClick() }
        .padding(horizontal = 13.dp, vertical = 8.dp), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Color.White else Muted)
}

/** Every source this phone can ask, for the list rules: (stored name, shown name). */
private suspend fun sourceOptions(vm: AppViewModel, ctx: android.content.Context): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    vm.addons.value.filter { vm.isAddonOn(it) }.forEach { out += it.label() to "Addon · ${it.label()}" }
    if (!BuildConfig.IS_TV) {
        out += MB_ANY to "Every MovieBox plugin"
        out += OTT_ANY to "Every Netflix / Prime / Hotstar-type plugin"
        out += VEGA_ANY to "VegaMovies / RogMovies"
        out += "CloudStream · MovieBox" to "MovieBox (direct)"
        runCatching {
            CsPlugins.init(ctx)
            CsPlugins.enabledEntries().forEach { e -> runCatching { CsPlugins.ensureLoaded(e) }.getOrNull()?.apis?.forEach { a -> out += csDisplayName(a.name) to "CloudStream · ${a.name}" } }
        }
        runCatching { NuvioPlugins.init(ctx); NuvioPlugins.enabledScrapers().forEach { out += "Nuvio · ${it.name}" to "Nuvio · ${it.name}" } }
    }
    return out.distinctBy { it.first }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesPage(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ver by Rules.changes.collectAsStateWithLifecycle()
    val drafts: SnapshotStateMap<String, String> = remember(ver) { mutableStateMapOf<String, String>().apply { Rules.defs.forEach { put(it.key, Rules.value(it.key)) } } }
    var msg by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf<List<RulesEvent>>(emptyList()) }
    var options by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        Rules.refresh()
        history = Rules.history()
        options = sourceOptions(vm, ctx)
    }
    fun keysOf(g: String?) = Rules.defs.filter { g == null || it.group == g }.map { it.key }
    fun tryHere(g: String?) { Rules.tryLocal(keysOf(g).associateWith { drafts[it].orEmpty() }); msg = "Trying on this phone ✓ (only here)" }
    fun reset(g: String?) { Rules.resetLocal(keysOf(g)); msg = "Back to ${if (Rules.published.values.isEmpty()) "the defaults" else "the published values"} on this phone" }
    fun publish(g: String?) {
        msg = "Publishing…"
        scope.launch {
            val title = g?.let { id -> Rules.groups.firstOrNull { it.id == id }?.title } ?: "all rules"
            msg = Rules.publish(keysOf(g).associateWith { drafts[it].orEmpty() }, title) ?: "Published v${Rules.published.version} ✓ — every app uses it from its next start"
            history = Rules.history()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 130.dp)) {
        item { PageHeader("Rules", onBack) }
        item {
            Text("Every rule that decides play. Defaults = how the app worked before. \"Try on this phone\" changes only this phone; \"Publish to everyone\" sends it to every ${Brand.NAME} (read at the next start). Users never see any of this.",
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp), fontSize = 13.sp, color = Muted)
        }
        item {
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (Rules.published.values.isEmpty()) "Published: nothing (everyone on defaults)" else "Published: v${Rules.published.version} · ${Rules.fmt(Rules.published.at)} · ${Rules.published.values.size} values",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Try all on this phone") { tryHere(null) }
                        Pill("Reset all") { reset(null) }
                        Pill("Publish all to everyone", strong = true) { publish(null) }
                        Pill(if (confirmReset) "Tap again: reset for everyone" else "Reset for everyone") {
                            if (!confirmReset) { confirmReset = true; return@Pill }
                            confirmReset = false; msg = "Resetting…"
                            scope.launch { msg = Rules.resetEveryone() ?: "Everyone is back on the defaults ✓"; history = Rules.history() }
                        }
                    }
                    msg?.let { Text(it, fontSize = 12.5.sp, color = Color(0xFF7CD992)) }
                }
            }
        }
        if (Feature.devAsk) item { DevAskBox(vm, "rules", "Ask about the rules") }
        items(Rules.groups, key = { it.id }) { g -> RuleGroupCard(vm, g, drafts, options, { tryHere(g.id) }, { reset(g.id) }, { publish(g.id) }) }
        item { SectionLabel("History") }
        item {
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (history.isEmpty()) Text("Nothing published yet.", fontSize = 13.sp, color = Muted)
                    history.take(30).forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${Rules.fmt(e.at)} · " + when (e.kind) { "auto" -> "auto by Source Test"; "reset" -> "reset for everyone"; else -> "published v${e.version}" }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(e.what + " · " + (e.after.keys + e.before.keys).distinct().joinToString().take(140), fontSize = 11.5.sp, color = Muted)
                            }
                            Pill("Undo") { msg = "Undoing…"; scope.launch { msg = Rules.undo(e, vm) ?: "Undone ✓"; history = Rules.history() } }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleGroupCard(vm: AppViewModel, g: RuleGroup, drafts: SnapshotStateMap<String, String>, options: List<Pair<String, String>>,
                          onTry: () -> Unit, onReset: () -> Unit, onPublish: () -> Unit) {
    var open by remember { mutableStateOf(g.id == "quick") }
    Column {
    DevFold(g.title, g.sub, open) { open = !open }
    if (open) {
    SettingsCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Rules.inGroup(g.id).forEach { d -> RuleEditor(d, drafts, options) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Try on this phone") { onTry() }
                Pill("Reset") { onReset() }
                Pill("Publish to everyone", strong = true) { onPublish() }
            }
        }
    }
    if (Feature.devAsk) DevAskBox(vm, g.id, whatIf = { Rules.inGroup(g.id).associate { it.key to drafts[it.key].orEmpty() } })
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleEditor(d: RuleDef, drafts: SnapshotStateMap<String, String>, options: List<Pair<String, String>>) {
    val v = drafts[d.key].orEmpty()
    val changed = v.trim() != Rules.value(d.key).trim()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (d.kind) {
            RuleKind.BOOL -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.label, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Switch(v == "true", { drafts[d.key] = it.toString() })
            }
            RuleKind.INT, RuleKind.NUM -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.label, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(v, { drafts[d.key] = it.filter { c -> c.isDigit() || c == '.' }.take(9) }, Modifier.width(110.dp), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (d.kind == RuleKind.INT) KeyboardType.Number else KeyboardType.Decimal))
            }
            RuleKind.LIST -> {
                Text(d.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                val items = v.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                fun set(l: List<String>) { drafts[d.key] = l.joinToString("\n") }
                if (items.isEmpty()) Text("(empty — the built-in choice)", fontSize = 12.5.sp, color = Muted)
                items.forEachIndexed { i, it ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${i + 1}. " + (options.firstOrNull { o -> o.first == it }?.second ?: it), Modifier.weight(1f), fontSize = 13.sp)
                        Pill("↑", enabled = i > 0) { set(items.toMutableList().apply { add(i - 1, removeAt(i)) }) }
                        Pill("↓", enabled = i < items.size - 1) { set(items.toMutableList().apply { add(i + 1, removeAt(i)) }) }
                        Pill("✕") { set(items - it) }
                    }
                }
                if (d.sources) {
                    var adding by remember { mutableStateOf(false) }
                    Pill(if (adding) "Close" else "+ Add a source") { adding = !adding }
                    if (adding) {
                        val left = options.filter { it.first !in items }
                        if (left.isEmpty()) Text(if (options.isEmpty()) "Loading this phone's sources…" else "Every source is in the list.", fontSize = 12.5.sp, color = Muted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            left.forEach { (k, label) -> Pill(label) { set(items + k) } }
                        }
                    }
                }
            }
        }
        if (d.help.isNotBlank()) Text(d.help, fontSize = 11.5.sp, color = Muted)
        Text("default ${d.default.replace("\n", ", ").ifBlank { "(empty)" }} · now: ${Rules.source(d.key)}${if (changed) " · edited, not saved" else ""}",
            fontSize = 11.sp, color = if (changed) Color(0xFFFFC56B) else Color(0xFF6F7890))
    }
}
