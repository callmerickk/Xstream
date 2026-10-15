package com.example.streamnest

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ApGood = Color(0xFF7CD992)

@Composable
private fun ApPill(text: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(text, Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) Color(0xFF2E2E36) else Color(0x1FFFFFFF))
        .border(1.dp, if (on) Accent else Color.Transparent, RoundedCornerShape(18.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Color.White else Color(0x66FFFFFF))
}

private fun apFmt(t: Long) = if (t <= 0) "—" else SimpleDateFormat("dd MMM · HH:mm", Locale.US).format(Date(t))

// ───────────── Auto-play logic ─────────────

/**
 * Developer's Corner › Auto-play logic: the CNCVerse rule in use, Undo last change, every saved logic with Restore,
 * Classic (0.9.43) switch, and Paste a rule from the Refresh Lab Analyzer page.
 */
@Composable
fun AutoPlayLogicScreen(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val rule by AutoPlayRules.current.collectAsStateWithLifecycle()
    val history by AutoPlayRules.history.collectAsStateWithLifecycle()
    var msg by remember { mutableStateOf<String?>(null) }
    var paste by remember { mutableStateOf("") }
    var lab by remember { mutableStateOf(false) }
    if (lab) { RefreshLabScreen(vm) { lab = false }; return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 80.dp)) {
        PageHeader("Auto-play logic", onBack)
        Text("How the phone asks CNCVerse (Vegamovies) for links and picks the better-quality file. Every change — a Source Test, the shared ranking, a new rule or an app update — saves the logic before it first, so you can always go back.",
            Modifier.padding(horizontal = 20.dp), fontSize = 13.sp, color = Muted)

        SectionLabel("In use")
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                Text(if (rule.smart) "Smart" else "Classic", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (rule.smart) ApGood else Color.White)
                Text("${rule.source} · since ${apFmt(rule.at)}", Modifier.padding(top = 2.dp), fontSize = 12.sp, color = Muted)
                Text(rule.summary(), Modifier.padding(top = 10.dp), fontSize = 13.sp)
                if (rule.smart) {
                    Text("Upgrade servers: ${rule.hostRank.joinToString(" → ")}", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Color(0xFFC9CED9))
                    if (rule.blockHosts.isNotEmpty()) Text("Skipped: ${rule.blockHosts.joinToString(", ")}", Modifier.padding(top = 2.dp), fontSize = 12.sp, color = Color(0xFFC9CED9))
                }
                if (BuildConfig.IS_TV) Text("Xstream TV still uses the classic logic.", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = Muted)
                Spacer(Modifier.height(14.dp))
                val last = history.lastOrNull()
                Text("Undo last change",
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(if (last != null) BrandRed else Color(0x22FFFFFF))
                        .clickable(enabled = last != null) {
                            val cp = AutoPlayRules.undoLast(vm)
                            msg = cp?.let { "Back to: ${it.reason}. Undo again to switch back." }
                        }.padding(vertical = 13.dp),
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(if (last != null) "Brings back the logic saved ${last.reason.lowercase(Locale.US).let { if (it.startsWith("before")) it else "at “${last.reason}”" }}." else "Nothing saved yet.",
                    Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Muted)
            }
        }

        SectionLabel("Switches")
        SettingsCard {
            Row(Modifier.fillMaxWidth().clickable {
                if (rule.smart) AutoPlayRules.applyRule(vm, AutoPlayRules.CLASSIC, "Before switching to classic")
                else AutoPlayRules.applyRule(vm, AutoPlayRules.BUILT_IN, "Before switching to smart")
                msg = if (rule.smart) "Classic logic on (the smart one is saved below)." else "Smart logic on."
            }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Classic CNCVerse logic (0.9.43)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("One ask that waits up to 20 s, then every 2 s. Use it if the smart logic plays worse.", fontSize = 12.sp, color = Muted)
                }
                Switch(!rule.smart, null)
            }
            Text("Reset to built-in smart rule",
                Modifier.padding(start = 16.dp, bottom = 14.dp).clip(RoundedCornerShape(18.dp)).background(Color(0x1FFFFFFF))
                    .clickable { AutoPlayRules.applyRule(vm, AutoPlayRules.BUILT_IN, "Before reset to built-in"); msg = "Built-in smart rule in use." }
                    .padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        SectionLabel("Rule from Refresh Lab")
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                Text("Run Refresh Lab, load its log in the Refresh Lab Analyzer page, tap Copy rule JSON and paste it here.", fontSize = 13.sp, color = Muted)
                OutlinedTextField(paste, { paste = it }, Modifier.fillMaxWidth().padding(top = 10.dp), minLines = 3, maxLines = 8,
                    placeholder = { Text("{ \"refreshAtSec\": [ … ] … }") },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ApPill("Apply rule", enabled = paste.isNotBlank()) {
                        val (r, err) = AutoPlayRules.parseLabRule(paste)
                        msg = if (r != null) { AutoPlayRules.applyRule(vm, r, "Before ${r.source}"); paste = ""; "Rule applied: ${r.summary()}" } else err
                    }
                    ApPill("Open Refresh Lab") { lab = true }
                }
            }
        }
        msg?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 10.dp), fontSize = 13.sp, color = ApGood) }

        SectionLabel("Saved logic (${history.size})")
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                if (history.isEmpty()) Text("Nothing saved yet.", fontSize = 13.sp, color = Muted)
                history.asReversed().forEachIndexed { i, cp ->
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    Text(cp.reason, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    val s = cp.state
                    val parts = buildList {
                        add(if (s.rule.smart) "Smart · ${s.rule.source}" else "Classic")
                        s.ranking?.let { add("ranking ${it.entries.size} groups") }
                        s.qsAddons?.let { q -> add("Quick Start: ${q.split('|').filter { it.isNotBlank() }.joinToString(", ").ifEmpty { "none" }}") }
                        s.addonOrder?.takeIf { it.isNotEmpty() }?.let { add("order: ${it.take(4).joinToString(" → ")}${if (it.size > 4) "…" else ""}") }
                    }
                    Text(parts.joinToString(" · ") + (if (cp.appVersion.isNotBlank()) " · app ${cp.appVersion}" else ""),
                        Modifier.padding(top = 2.dp), fontSize = 12.sp, color = Muted)
                    Row(Modifier.padding(top = 6.dp)) {
                        ApPill("Restore") { AutoPlayRules.restore(vm, cp); msg = "Restored: ${cp.reason}. What was in use is saved at the top." }
                    }
                }
            }
        }
    }
}

// ───────────── Refresh Lab ─────────────

@Composable
fun RefreshLabScreen(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val running by RefreshLab.running.collectAsStateWithLifecycle()
    val step by RefreshLab.step.collectAsStateWithLifecycle()
    val done by RefreshLab.done.collectAsStateWithLifecycle()
    val total by RefreshLab.total.collectAsStateWithLifecycle()
    val last by RefreshLab.lastFile.collectAsStateWithLifecycle()
    var count by remember { mutableStateOf(25) }
    var play by remember { mutableStateOf(true) }
    var cache by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf<String?>(null) }
    val view = LocalView.current
    DisposableEffect(running) { view.keepScreenOn = running; onDispose { view.keepScreenOn = false } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 80.dp)) {
        PageHeader("Refresh Lab", onBack)
        Text("Measures how CNCVerse fills its link list: each fresh title is asked on a different schedule (every 2 s, one long wait, a 3 s kick, growing gaps…), every new link is checked the moment it appears, then played. Load the log in the Refresh Lab Analyzer page to get the best refresh times.",
            Modifier.padding(horizontal = 20.dp), fontSize = 13.sp, color = Muted)

        SectionLabel("Run")
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                Text("Titles", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 25, 40).forEach { n -> ApPill("$n", on = count == n, enabled = !running) { count = n } }
                }
                Text("25 ≈ 5 titles per schedule, about 40 minutes plus the cache checks.", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Muted)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp).clickable(enabled = !running) { play = !play }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Play each working link", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Time to the first picture, in the real player.", fontSize = 12.sp, color = Muted)
                    }
                    Switch(play, null, enabled = !running)
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp).clickable(enabled = !running) { cache = !cache }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Check how long links stay cached", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Asks each title again after 5 and 30 minutes (keeps the run open 30 min longer).", fontSize = 12.sp, color = Muted)
                    }
                    Switch(cache, null, enabled = !running)
                }
                Spacer(Modifier.height(12.dp))
                Text(if (running) "Stop" else "Start Refresh Lab",
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(if (running) Color(0xFF3A2228) else BrandRed)
                        .clickable { if (running) RefreshLab.stop() else RefreshLab.start(vm, ctx, count, play, cache) }.padding(vertical = 14.dp),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                if (SourceTester.running.collectAsStateWithLifecycle().value) Text("Source Test is running — start this after it finishes.", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = Muted)
                if (step.isNotEmpty()) {
                    Text(step, Modifier.padding(top = 12.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = Accent)
                }
                Text("Keep Xstream open while it runs (the screen stays on). Titles are picked fresh each time, so CNCVerse hasn't cached them yet.",
                    Modifier.padding(top = 10.dp), fontSize = 12.sp, color = Muted)
            }
        }

        SectionLabel("Logs")
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                val past = remember(last, running) { RefreshLab.pastLogs(ctx) }
                if (past.isEmpty()) Text("The log appears here once a run starts. The last five are kept.", fontSize = 13.sp, color = Muted)
                past.forEach { lf ->
                    Text(apFmt(lf.lastModified()) + "  ·  ${lf.length() / 1024} KB" + (if (running && lf == last) " · running" else ""),
                        Modifier.padding(top = 10.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ApPill("Save") { msg = SourceTester.saveToDownloads(ctx, lf) ?: "Saved to Downloads: ${lf.name}" }
                        ApPill("Copy") {
                            msg = runCatching {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("Refresh Lab log", lf.readText().take(900_000)))
                                "Log copied — paste it into the analyzer page"
                            }.getOrElse { "Couldn't copy — use Save or Share" }
                        }
                        ApPill("Share") { SourceTester.shareLog(ctx, lf) }
                    }
                }
                msg?.let { Text(it, Modifier.padding(top = 10.dp), fontSize = 13.sp, color = ApGood) }
            }
        }
    }
}
