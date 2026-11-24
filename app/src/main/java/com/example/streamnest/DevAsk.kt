package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/*
 * 0.9.3 (Feature.devAsk): "Ask about this" in Developer's Corner.
 * Each section has a short logic-book entry (what it does, which file, how its values are used). A question goes to
 * the AI with that entry, the section's current values and the last ~150 matching lines of this phone's activity log
 * (e-mail addresses removed). The conversation is kept for the session. The AI is reached through the streamx-home
 * Worker (POST /ai, the developer's sign-in checked there, Gemini key kept as a Worker secret); while the Worker has no
 * key (503) the app's own Gemini key is used, else the app's AI.
 */

data class LogicEntry(val id: String, val title: String, val text: String, val logRx: Regex)

object LogicBook {
    private fun rx(s: String) = Regex(s, RegexOption.IGNORE_CASE)
    val entries = listOf(
        LogicEntry("quick", "Quick Play sources",
            "Rules.kt key quickSources (empty = built-in): Quick.kt qsAddons()/isQuick() decide which links are 'quick'. Addon mode: the Quick Start addons " +
            "(texts qsAddons, the Source Test can change them). Plugin mode: ticked CloudStream providers + every MovieBox / Netflix-type / VegaMovies plugin. " +
            "A rule list replaces both; it may mix addons, CloudStream providers and 'Nuvio · name' plugins (AppViewModel.mixedStreamsFlow asks both kinds). " +
            "Prepare.kt (title page green light): quick links only; if none that starts fast after quickWaitAddonsSec (6 s) / quickWaitPluginsSec (20 s, plugin mode) " +
            "or Quick Play finished empty → goWide(): every source (or the backup list) is asked. pickQuick() order: language, quality up to 1080p, heavy files last, rule order, score.",
            rx("PLAY|Quick play|quick|backup|PLUGIN|NET|green|TITLE")),
        LogicEntry("backup", "Backup",
            "Rules backupSources (empty = every source), backupRaceAfterMs (1200), backupQuickHeadStartMs (3000). Prepare.goWide() asks the backup sources. " +
            "PlayerScreen 'rescue': after backupRaceAfterMs the player races backup links (one per server); Quick Play gets backupQuickHeadStartMs to answer with an " +
            "instant playlist first. rankedCandidates() uses the backup order as a tie-break before the score.",
            rx("backup|rescue|racing|race|PLAY|SWITCH")),
        LogicEntry("addons", "Addon order",
            "Rules addonOrder (empty = keep). Applied with reorderAddonsByNames (AppViewModel). The Source Test (SourceTest.kt manageAddons) also orders addons by score " +
            "unless this rule is published or tested; its changes are noted as 'auto' in the Rules history.",
            rx("addon|RULES|order")),
        LogicEntry("rank", "Source ranking",
            "Ranker.scoreOf: played share × rankPlayedWeight(100) − min(median s, rankMaxSeconds 20) × rankPerSecond(4) − rankUnseekablePenalty(60) when not seekable. " +
            "Scores are per provider group (addon|label|server), quality bucket (4k/fhd/mhd/low) and language. rankedCandidates() sorts: observed last, can't-jump last, " +
            "unplayable, embeds, CAM, weak audio, plugin tiers, quality tier, host penalty, language (rankLanguageFirst), heavy, backup order, then score.",
            rx("RANK|rank|score|seek")),
        LogicEntry("checks", "Green-light checks",
            "PlayerScreen: a phone file under checkFakeUnderMb (20 MB) is a fake. Length check (checkLength): expected length from saved progress or the title's runtime; " +
            "film allowed checkMovieMin..checkMovieMax (0.75–1.35 ×), episode checkSeriesMin..checkSeriesMax (0.6–1.4 ×); two sources agreeing on a length win over the " +
            "title info. Smooth switch (ottSwapTo) only when both copies are within checkSwapLengthMs (1.5 s; 10 s when you tapped Upgrade).",
            rx("length|wrong|fake|SWITCH|swap")),
        LogicEntry("switch", "Auto-switch",
            "PlayerScreen plugin-mode ladder: rung 0 MovieBox playlist / OTT ≤720p, 1 MovieBox MP4, 2 OTT 1080p, 3 VegaMovies/RogMovies. Runs when autoSwitchPhone / " +
            "autoSwitchTv is on (phone on, TV off). Waits autoSwitchWaitBestSec (25 s) for rung 3, then the highest there; autoSwitchTries (3) per title; fades only.",
            rx("ladder|SWITCH|swap|fade")),
        LogicEntry("weak", "Weak network",
            "PlayerScreen auto quality (quality Auto + autoQuality): weakStalls (2) stalls in 90 s or one longer than weakLongStallMs (5 s) → lower quality; smooth for " +
            "weakUpAfterSmoothSec (90 s) with weakUpBufferedSec (45 s) loaded → higher. Ladder waits while speed < weakMinMbps (8), within weakSlowCooldownSec (180 s) of " +
            "a slow moment; weakStruggleTicks (8) buffering seconds in a minute → switch at once.",
            rx("buffering|slow|stall|quality|Mbps|SWITCH")),
        LogicEntry("memory", "Link memory & HDR",
            "LinkMemory.kt keeps links that started fast for linkMemoryHours (4 h) on disk; the title page checks them first. HdrRule.on: someone who never touched " +
            "the HDR switch gets hdrDefault (off).",
            rx("remembered|link memory|HDR|linkmem")),
        LogicEntry("test", "Source Test arranging",
            "SourceTest.kt manageAddons: an addon joins Quick Start with played share ≥ testQuickMinPlayed (0.7) and median ≤ testQuickMaxMedianMs (2 s); leaves below " +
            "testQuickRemoveBelow (0.3); needs testMinLinks (3) links. Never changes a published/tested Quick Play or addon order rule.",
            rx("Source Test|RANK|Quick Start")),
        LogicEntry("rules", "Rules page",
            "Developer's Corner › Rules (RulesUi.kt / Rules.kt). Defaults in code = the behaviour before 0.9.3. Published copy at Firebase xshared/devconfig/rules " +
            "(version, time, values), read at start in the background and kept on the phone; a test on this phone wins there. History (last 20) at " +
            "xshared/devconfig/rulesHistory plus this phone's automatic Source Test changes.",
            rx("RULES")),
        LogicEntry("src", "Sources & plugins",
            "Official set (Official.kt, shared/devAddons/official), CloudStream plugins (CsPlugins.kt), Quick Start servers (SettingsMore.kt QuickStartPage: qsAddons, qsCs, " +
            "qs4k, qsLangs), stream filters, built-in servers (BuiltIns). Plugin mode = srcMode 'plugins' (PluginSources.modeStreams), addon mode = addons only.",
            rx("PLUGIN|addon|CSLOAD|NUVIO|MBDIRECT")),
        LogicEntry("play", "Playback & tests",
            "Source Test (SourceTest.kt: random titles, every link played to its first picture, Ranker ranking), Auto-play logic (AutoPlayRules.kt: CNCVerse refresh plan, " +
            "checkpoints), Refresh Lab, Defaults for everyone (keys).",
            rx("PLAY|RANK|SWITCH|Source Test|CNCVerse")),
        LogicEntry("sourceTest", "Source Test", "SourceTest.kt: picks titles (TMDB discover or Home rows), asks the chosen addons, probes each link (one byte, mid-file seek), plays it " +
            "to the first picture (20 s max), builds RankFile entries (score per group/bucket/language) and arranges addons.", rx("Source Test|RANK|PLAY")),
        LogicEntry("combo", "Combined test", "ComboTest.kt: 10 titles (Hindi/Hollywood movies, English series, Hindi TV, old and new). Every addon, CloudStream and Nuvio " +
            "plugin at once; per source first link, first playable (one-byte check), first picture, links, unique files (size ±1 % + similar name). Replays strategies from " +
            "the recording: as set now, all at once, fastest 2–3 first + rest after X s, by title type, cloud first.", rx("COMBO|PLUGIN|NET")),
        LogicEntry("pub", "Publish for everyone", "Watch together call relay (TURN url/user/password, AppLook.publishTurn).", rx("ROOM|CALL|TOGETHER")),
        LogicEntry("look", "Look, logo & AI", "Logo & animation, app identity (name), published for everyone.", rx("APP|LOOK")),
        LogicEntry("logs", "Tests & logs", "Activity log (ActLog.kt: 3 days, light logging unless Detailed logs), Play timing log (Timing.kt), playback log, developer changelog.",
            rx("ERROR|crash|PLAY|APP")),
        LogicEntry("sec", "Access & security", "Dev access list, fingerprint unlock, password, UI editing.", rx("APP|ACCESS")),
    )
    fun of(id: String) = entries.firstOrNull { it.id == id }
}

object DevAsk {
    private const val URL = "https://streamx-home.callmerick093.workers.dev/ai"
    private const val KEY = "sx-home-1"
    /** The conversation per section, kept while the app runs. */
    private val convos = HashMap<String, MutableList<Pair<String, String>>>()
    fun history(id: String): List<Pair<String, String>> = synchronized(convos) { convos[id]?.toList().orEmpty() }
    fun forget(id: String) = synchronized(convos) { convos.remove(id) }

    private val emailRx = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    /** Never an e-mail address to the AI. */
    fun clean(s: String): String = emailRx.replace(ActLog.mask(s), "•@•")

    /** The last [n] lines of this phone's log that match the section (today and yesterday). */
    fun logLines(rx: Regex?, n: Int = 150): List<String> = runCatching {
        ActLog.days().take(2).reversed().flatMap { ActLog.read(it).lines() }.filter { it.isNotBlank() && (rx == null || rx.containsMatchIn(it)) }.takeLast(n).map { clean(it).take(400) }
    }.getOrDefault(emptyList())

    /** The values a section works with (no account details). */
    fun values(vm: AppViewModel, id: String): String = clean(when (id) {
        in Rules.groups.map { it.id } -> Rules.describe(id)
        "rules", "master" -> Rules.describe()
        "src" -> "source mode=${vm.texts.value["srcMode"].orEmpty().ifBlank { "plugins" }} · addons=${vm.addons.value.joinToString { it.label() }} · Quick Start addons=${vm.texts.value["qsAddons"].orEmpty()} · " +
            "Quick Play plugins=${qsCsParse(vm.texts.value["qsCs"]).joinToString { it.substringAfter('#') }} · languages=${vm.qsLangs().joinToString()} · CloudStream plugins on=${CsPlugins.enabled.value.size}"
        "play", "sourceTest" -> "ranking groups=${Ranker.file.entries.size} (at ${Rules.fmt(Ranker.file.at)}) · testedOnly=${vm.texts.value["testedOnly"] ?: "yes"} · srcAutoManage=${vm.texts.value["srcAutoManage"] ?: "yes"} · " +
            "CNCVerse logic: ${AutoPlayRules.current.value.summary()} · last results: ${SourceTester.results.value.size} links, ${SourceTester.results.value.count { it.ok }} played"
        "combo" -> ComboTest.report.value.lines().filter { it.startsWith("• ") || it.startsWith("    green") || "→" in it }.joinToString("\n").ifBlank { "no combined test recorded yet" }
        "logs" -> "detailed logs=${ActLog.detailed}"
        else -> ""
    })

    private fun system(vm: AppViewModel, id: String): String {
        val e = LogicBook.of(id)
        val book = if (id == "master") LogicBook.entries.joinToString("\n\n") { "[${it.title}] ${it.text}" } else "[${e?.title}] ${e?.text}"
        return "You help the developer of ${Brand.NAME}, an Android streaming app, understand and tune it. Answer in short plain English (no jargon), " +
            "say which rule or file to change and what to expect. If logs show a problem, quote the line. If unsure, say so.\n\n" +
            "Logic book:\n$book\n\nCurrent values:\n${values(vm, id).ifBlank { "(none)" }}"
    }

    /** One question in a section's conversation. (answer, how it was answered). */
    suspend fun ask(vm: AppViewModel, id: String, q: String, extra: String = ""): Pair<String, String> = withContext(Dispatchers.IO) {
        val logs = logLines(if (id == "master") null else LogicBook.of(id)?.logRx)
        val user = clean(q) + (if (extra.isNotBlank()) "\n\n$extra" else "") + "\n\nRecent log lines from this phone (${logs.size}):\n" + logs.joinToString("\n")
        val sys = system(vm, id)
        val hist = history(id)
        val (a, via) = viaWorker(sys, hist, user)?.let { it to "Gemini (Worker)" } ?: run {
            val g = AiProviders.first { it.id == "gemini" }
            when {
                vm.aiKey(g).isNotEmpty() -> vm.aiChat(sys, hist, user, p = g, key = vm.aiKey(g)) to "Gemini (app key)"
                vm.aiReady() -> vm.aiChat(sys, hist, user) to vm.aiProvider().label
                else -> throw IllegalStateException("No AI yet: add GEMINI_KEY to the Worker, or a Gemini key in Integrations.")
            }
        }
        // Only the question is kept in the conversation (the log lines are sent fresh each time).
        synchronized(convos) { convos.getOrPut(id) { mutableListOf() }.apply { add(clean(q) to a); while (size > 12) removeAt(0) } }
        a to via
    }

    /** null = the Worker can't answer yet (no GEMINI_KEY: 503) or isn't reachable: the app's key is used instead. */
    private suspend fun viaWorker(sys: String, hist: List<Pair<String, String>>, user: String): String? {
        val t = Cloud.token() ?: return null
        val body = buildJsonObject {
            put("system", sys)
            putJsonArray("messages") {
                hist.forEach { (q, a) -> addJsonObject { put("role", "user"); put("content", q) }; addJsonObject { put("role", "assistant"); put("content", a) } }
                addJsonObject { put("role", "user"); put("content", user) }
            }
        }
        return runCatching {
            val http = Api.http.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(70, TimeUnit.SECONDS).build()
            http.newCall(Request.Builder().url(URL).header("x-sx", KEY).header("Authorization", "Bearer $t")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (!r.isSuccessful) { ActLog.add("RULES", "AI via Worker: HTTP ${r.code}${if (r.code == 503) " (no GEMINI_KEY yet) — using the app's key" else ""}"); return@use null }
                Api.json.parseToJsonElement(txt).jsonObject["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }
}

/**
 * "What if I change this?" — the recorded test results replayed under the new values (no network): which source would
 * have been first and the time to the first playable link, per title; and the ranking's top groups under new weights.
 */
object WhatIf {
    fun run(vm: AppViewModel, draft: Map<String, String>): String = buildString {
        fun v(k: String) = draft[k] ?: Rules.value(k)
        fun items(k: String) = v(k).split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val keys = draft.filter { (k, x) -> x.trim() != Rules.value(k).trim() }.keys
        if (keys.isEmpty()) { appendLine("Nothing changed yet — edit a value first."); return@buildString }
        appendLine("Changed: ${keys.joinToString()}")
        val rec = ComboTest.lastRecording(vm.appContext)
        val quickKeys = setOf(Rules.QUICK_SOURCES, Rules.QUICK_WAIT_ADDONS, Rules.QUICK_WAIT_PLUGINS, Rules.BACKUP_SOURCES)
        if (keys.any { it in quickKeys }) {
            if (rec == null || rec.titles.isEmpty()) appendLine("No combined test recorded yet (Source Test › Combined) — can't replay Quick Play.")
            else {
                val names = rec.titles.flatMap { t -> t.sources.map { it.name } }.distinct()
                val plug = vm.pluginMode()
                fun plan(quickL: List<String>, waitS: Int, backL: List<String>): (ComboSource) -> Long? {
                    val q = ComboTest.quickNow(vm, names, quickL)
                    return { s -> when { s.name in q -> 0L; backL.isEmpty() || backL.any { Rules.matches(s.name, it) } -> waitS * 1000L; else -> null } }
                }
                val waitK = if (plug) Rules.QUICK_WAIT_PLUGINS else Rules.QUICK_WAIT_ADDONS
                val now = plan(Rules.quickSources(), Rules.int(waitK), Rules.backupSources())
                val then = plan(items(Rules.QUICK_SOURCES), v(waitK).toDoubleOrNull()?.toInt() ?: Rules.int(waitK), items(Rules.BACKUP_SOURCES))
                appendLine("Replayed on the last combined test (${rec.titles.size} titles, ${Rules.fmt(rec.at)}):")
                var a = 0L; var b = 0L; var na = 0; var nb = 0
                rec.titles.forEach { t ->
                    val x = ComboTest.sim(t, now); val y = ComboTest.sim(t, then)
                    fun f(r: SimResult) = r.greenMs?.let { "${ComboTest.short(r.first ?: "?")} at ${"%.1f".format(it / 1000f)} s" } ?: "nothing playable"
                    appendLine("• ${t.label.substringBefore(" · ").take(40)}: now ${f(x)} → with change ${f(y)}")
                    x.greenMs?.let { a += it; na++ }; y.greenMs?.let { b += it; nb++ }
                }
                appendLine("Average time to first playable: now ${if (na == 0) "—" else "%.1f s".format(a / 1000.0 / na)} ($na green) → with change ${if (nb == 0) "—" else "%.1f s".format(b / 1000.0 / nb)} ($nb green)")
            }
        }
        val rankKeys = setOf(Rules.RANK_PLAYED, Rules.RANK_PER_SEC, Rules.RANK_MAX_SEC, Rules.RANK_UNSEEK)
        if (keys.any { it in rankKeys }) {
            val es = Ranker.file.entries
            if (es.isEmpty()) appendLine("No Source Test ranking on this phone yet — can't re-rank.")
            else {
                fun score(e: RankEntry, w: (String) -> Double): Double {
                    if (e.tests == 0) return 0.0
                    val secs = (e.medianMs / 1000.0).coerceAtMost(w(Rules.RANK_MAX_SEC))
                    return e.ok.toDouble() / e.tests * w(Rules.RANK_PLAYED) - secs * w(Rules.RANK_PER_SEC) - (if (e.seekable) 0.0 else w(Rules.RANK_UNSEEK))
                }
                val cur = { k: String -> Rules.num(k) }
                val nw = { k: String -> v(k).toDoubleOrNull() ?: Rules.num(k) }
                listOf("fhd" to "Full HD", "mhd" to "HD 720p").forEach { (bk, label) ->
                    val l = es.filter { it.bucket == bk }
                    if (l.isEmpty()) return@forEach
                    val x = l.sortedByDescending { score(it, cur) }.take(5).map { Ranker.labelOf(it.key) }
                    val y = l.sortedByDescending { score(it, nw) }.take(5).map { Ranker.labelOf(it.key) }
                    appendLine("$label top 5 now: ${x.joinToString(" › ")}")
                    appendLine("$label top 5 with change: ${y.joinToString(" › ")}${if (x == y) " (same)" else ""}")
                }
            }
        }
        if (keys.none { it in quickKeys || it in rankKeys }) appendLine("No recorded data for these values — the AI's answer is all there is.")
    }
}

/** A small "Ask about this" box: question, answer, the conversation so far (this session). */
@Composable
fun DevAskBox(vm: AppViewModel, id: String, title: String = "Ask about this", whatIf: (() -> Map<String, String>)? = null) {
    if (!Feature.devAsk) return
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var turns by remember { mutableStateOf(DevAsk.history(id)) }
    var err by remember { mutableStateOf<String?>(null) }
    var via by remember { mutableStateOf("") }
    var effect by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x0FFFFFFF)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        turns.takeLast(4).forEach { (qq, a) ->
            Text("You: $qq", fontSize = 12.5.sp, color = Muted)
            Text(a, fontSize = 13.sp, color = Color(0xFFDDE1EA))
        }
        if (via.isNotBlank()) Text("answered by $via", fontSize = 10.5.sp, color = Muted)
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), label = { Text(title, fontSize = 12.sp) }, maxLines = 4)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (busy) "Thinking…" else "Ask", Modifier.clip(RoundedCornerShape(18.dp)).background(if (busy) Color(0x1FFFFFFF) else BrandRed)
                .clickable(enabled = !busy && q.isNotBlank()) {
                    busy = true; err = null
                    val question = q
                    scope.launch {
                        val extra = whatIf?.let { "What-if (recorded data, new values): " + WhatIf.run(vm, it()) }.orEmpty()
                        runCatching { DevAsk.ask(vm, id, question, extra) }.onSuccess { (_, v) -> via = v; q = ""; turns = DevAsk.history(id) }.onFailure { err = it.message ?: "No answer." }
                        busy = false
                    }
                }.padding(horizontal = 16.dp, vertical = 9.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (whatIf != null) Text("What if I change this?", Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x1FFFFFFF))
                .clickable { effect = WhatIf.run(vm, whatIf()) }.padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (turns.isNotEmpty()) Text("New chat", Modifier.clip(RoundedCornerShape(18.dp)).clickable { DevAsk.forget(id); turns = emptyList(); via = "" }.padding(horizontal = 10.dp, vertical = 9.dp), fontSize = 12.sp, color = Muted)
        }
        err?.let { Text(it, fontSize = 12.5.sp, color = Color(0xFFFF8A80)) }
        effect?.let { Text(it, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFD5D8DF)) }
    }
}
