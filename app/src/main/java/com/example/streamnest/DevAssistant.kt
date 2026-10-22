package com.example.streamnest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// ───────────── The AI's name and its look ─────────────

/** The voice assistant's name (Developer's Corner › AI assistant › Name & look). */
fun aiName(): String = Public.vm?.texts?.value?.get("aiName")?.trim()?.takeIf { it.isNotBlank() } ?: "AIDEN"

/** A designed name: font, weight, spacing, colours and one accent letter (like the red X of the brand). */
@Serializable
data class NameStyle(
    val font: String = "Poppins", val weight: Int = 800, val spacing: Float = 4f, val color: String = "#FFFFFF",
    val accent: String = "#E50914", val accentIndex: Int = -1, val upper: Boolean = true, val italic: Boolean = false, val note: String = "",
)

fun parseNameStyle(raw: String?): NameStyle? = raw?.takeIf { it.isNotBlank() }?.let { runCatching { Api.json.decodeFromString<NameStyle>(it) }.getOrNull() }

private val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal), Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold), Font(R.font.inter_bold, FontWeight.Bold),
)
private val InterDisplay = FontFamily(Font(R.font.inter_display_light, FontWeight.Light), Font(R.font.inter_display_semibold, FontWeight.SemiBold))
/** The fonts a design may use (built into the app). */
val NameFonts = listOf("Poppins", "Inter", "Inter Display", "Serif", "Mono", "Condensed", "Cursive")
fun nameFont(n: String): FontFamily = when (n.lowercase()) {
    "inter" -> InterFamily
    "inter display", "display" -> InterDisplay
    "serif" -> FontFamily.Serif
    "mono", "monospace" -> FontFamily.Monospace
    "condensed" -> FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD))
    "cursive" -> FontFamily.Cursive
    else -> BrandFont
}
private fun hex(s: String, d: Color): Color = runCatching { Color(android.graphics.Color.parseColor(s.trim())) }.getOrDefault(d)

/** A name drawn in its designed look ([style] null = plain bold). */
@Composable
fun StyledName(text: String, style: NameStyle?, fontSize: TextUnit = 18.sp, modifier: Modifier = Modifier) {
    val s = style ?: NameStyle(font = "Poppins", weight = 800, spacing = 6f)
    val shown = if (s.upper) text.uppercase() else text
    val base = hex(s.color, Color.White)
    val acc = hex(s.accent, BrandRed)
    Text(
        buildAnnotatedString {
            shown.forEachIndexed { i, c -> if (i == s.accentIndex) withStyle(SpanStyle(color = acc)) { append(c) } else append(c) }
        },
        modifier, color = base, fontSize = fontSize, fontFamily = nameFont(s.font),
        fontWeight = FontWeight(s.weight.coerceIn(100, 900)), letterSpacing = s.spacing.coerceIn(-2f, 14f).sp,
        fontStyle = if (s.italic) FontStyle.Italic else FontStyle.Normal, maxLines = 1,
    )
}

/** The AI's name in its published look. */
@Composable
fun AiNameMark(fontSize: TextUnit = 18.sp, modifier: Modifier = Modifier) {
    val vm = Public.vm
    val texts = vm?.texts?.collectAsState()?.value
    StyledName(aiName(), parseNameStyle(texts?.get("aiNameStyle")), fontSize, modifier)
}

// ───────────── Developer's Corner › AI assistant ─────────────

private enum class AssistTask(val label: String) { Logs("Analyse logs"), AutoPlay("Tune auto-play"), Design("Design a name"), Ask("Ask anything") }

/** Settings the assistant may change (anything else in an answer is ignored). */
private val AllowedKeys = setOf("qsLangs", "qsQuality", "qsCs", "qs4k", "tagRules", "srcMode", "rowNames", "codeNames", "qsOn", "aiWake", "aiWakeVoice")

@Serializable private data class AssistChange(val key: String = "", val value: String = "", val why: String = "")
@Serializable private data class AssistAnswer(
    val explain: String = "", val changes: List<AssistChange> = emptyList(), val pluginsOn: List<String> = emptyList(),
    val pluginsOff: List<String> = emptyList(), val designs: List<NameStyle> = emptyList(),
)

private fun AppViewModel.logExcerpt(maxLines: Int = 260): String {
    val keep = Regex("^\\S+ (PLAY|CSPLAY|PLUGIN|ERROR|NUVIOPLAY|SUBSYNC|VPN|APP) ")
    // The last two days only (the whole log can be many megabytes).
    return ActLog.days().take(2).reversed().flatMap { ActLog.read(it).lines() }.filter { keep.containsMatchIn(it) }.takeLast(maxLines).joinToString("\n") { ActLog.mask(it).take(260) }
}

private fun AppViewModel.settingsSnapshot(): String {
    val t = texts.value
    val plugins = CsPlugins.catalog.value.filter { it.key in CsPlugins.enabled.value }.joinToString { it.name }
    return buildString {
        append("srcMode=${t["srcMode"].orEmpty().ifBlank { "plugins" }}\n")
        append("qsLangs=${t["qsLangs"] ?: "hindi,english,original"}\n")
        append("qsQuality=${t["qsQuality"] ?: "auto"}\n")
        append("qsCs (Quick Play plugins, one per line pluginKey#provider)=\n${t["qsCs"].orEmpty()}\n")
        append("qs4k (Upgrade to 4K plugins)=\n${t["qs4k"].orEmpty()}\n")
        append("tagRules=${t["tagRules"].orEmpty()}\n")
        append("Plugins switched on: $plugins\n")
        append("All plugins available: ${CsPlugins.catalog.value.joinToString { it.name }}\n")
    }
}

private fun AppViewModel.assistPrompt(task: AssistTask, input: String): Pair<String, String> {
    val rules = """
Answer ONLY with one JSON object of this shape (no other text):
{"explain":"plain-language answer, short paragraphs","changes":[{"key":"…","value":"…","why":"…"}],"pluginsOn":["plugin name"],"pluginsOff":["plugin name"],"designs":[{"font":"…","weight":800,"spacing":4,"color":"#FFFFFF","accent":"#E50914","accentIndex":0,"upper":true,"italic":false,"note":"why this look"}]}
Leave lists empty when they don't apply. Only these keys may be changed: ${AllowedKeys.joinToString()}.
""".trim()
    val system = "You are the developer's assistant inside Xstream, an Android streaming app (CloudStream plugins in Turbo mode, Stremio add-ons in Classic mode). Be precise and practical. $rules"
    val user = when (task) {
        AssistTask.Logs -> "Read this activity log from the app. Find what went wrong (slow starts, failures, crashes, freezes), explain the cause of each in plain words, and propose setting changes that would help.\n\nCurrent settings:\n${settingsSnapshot()}\n\nLog:\n${logExcerpt()}"
        AssistTask.AutoPlay -> "Tune auto-play (which plugins Quick Play / Upgrade to 4K use, languages, filters) so streams start fast and in good quality. Use the timings in the log. ${input.ifBlank { "" }}\n\nCurrent settings:\n${settingsSnapshot()}\n\nPlugin timings from the log:\n${logExcerpt(220).lines().filter { " PLUGIN " in it || " PLAY " in it }.takeLast(160).joinToString("\n")}"
        AssistTask.Design -> "Design 6 premium wordmark looks for the name \"${input.ifBlank { aiName() }}\" for a dark, cinematic streaming app whose brand colour is red #E50914. Fonts allowed: ${NameFonts.joinToString()}. weight 100-900, spacing -2..14 (sp), accentIndex = index of one letter drawn in the accent colour (or -1). Put them in \"designs\" and a one-line note each; changes empty."
        AssistTask.Ask -> "$input\n\nCurrent settings:\n${settingsSnapshot()}"
    }
    return system to user
}

/**
 * Developer's Corner › AI assistant: reads the logs, tunes auto-play and settings, designs the AI's name — with the
 * app's own AI key (Groq / Gemini…), or by copying the prompt into any AI chat (your Claude) and pasting the answer back.
 * Nothing changes until you tap Apply.
 */
@Composable
fun DevAssistantPage(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var task by remember { mutableStateOf(AssistTask.Logs) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var answer by remember { mutableStateOf<AssistAnswer?>(null) }
    var pasted by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf<String?>(null) }
    val texts by vm.texts.collectAsState()
    var aiNameField by remember { mutableStateOf(aiName()) }

    fun parse(raw: String): AssistAnswer? = runCatching { Api.json.decodeFromString<AssistAnswer>(extractJson(raw)) }.getOrNull()
        ?: raw.takeIf { it.isNotBlank() }?.let { AssistAnswer(explain = it.take(4000)) }

    Column(Modifier.fillMaxSize().background(Bg)) {
        PageHeader("AI assistant", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 60.dp)) {
            Text("Uses the app's AI (${vm.aiProvider().label}${if (vm.aiReady()) "" else " — no key yet"}). Or copy the prompt into any AI chat, like your Claude, and paste the answer back. Nothing changes until you tap Apply.",
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 13.sp, color = Muted)

            SectionLabel("AI name & look")
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF140A0E)).padding(vertical = 18.dp), Alignment.Center) {
                        StyledName(aiNameField.ifBlank { "AIDEN" }, parseNameStyle(texts["aiNameStyle"]), 30.sp)
                    }
                    OutlinedTextField(aiNameField, { aiNameField = it.take(16) }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp), label = { Text("AI name") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ vm.setText("aiName", aiNameField.trim()); applied = "Name saved on this phone — publish to send it to everyone." }) { Text("Save name", color = Color.White) }
                        OutlinedButton({ task = AssistTask.Design; input = aiNameField }) { Text("Design its look", color = Color.White) }
                    }
                }
            }

            SectionLabel("Ask the assistant")
            Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistTask.values().forEach { t -> FilterChip(task == t, { task = t; answer = null; error = null }, { Text(t.label, fontSize = 12.sp) }) }
            }
            if (task == AssistTask.Ask || task == AssistTask.AutoPlay || task == AssistTask.Design) OutlinedTextField(
                input, { input = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                label = { Text(when (task) { AssistTask.Design -> "Name to design"; AssistTask.AutoPlay -> "Anything to focus on (optional)"; else -> "Your question" }) },
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    busy = true; error = null; answer = null; applied = null
                    scope.launch {
                        val (sys, user) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.assistPrompt(task, input) }
                        runCatching { vm.aiChat(sys, emptyList(), user) }
                            .onSuccess { answer = parse(it) }.onFailure { error = it.message ?: "The AI didn't answer." }
                        busy = false
                    }
                }, enabled = !busy && vm.aiReady(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914), contentColor = Color.White)) {
                    Text(if (busy) "Thinking…" else "Ask the app's AI", fontWeight = FontWeight.Bold)
                }
                OutlinedButton({
                    scope.launch {
                        val (sys, user) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.assistPrompt(task, input) }
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Xstream prompt", "$sys\n\n$user"))
                        Snack.show("Prompt copied — paste it into any AI chat, then paste the answer below")
                    }
                }) { Text("Copy prompt", color = Color.White) }
            }
            OutlinedTextField(pasted, { pasted = it }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().heightIn(min = 90.dp, max = 180.dp),
                shape = RoundedCornerShape(12.dp), label = { Text("Paste the AI chat's answer here") })
            OutlinedButton({ answer = parse(pasted); error = if (answer == null) "That answer couldn't be read." else null; applied = null },
                Modifier.padding(horizontal = 16.dp), enabled = pasted.isNotBlank()) { Text("Read pasted answer", color = Color.White) }

            error?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Color(0xFFFF8A80), fontSize = 13.sp) }
            answer?.let { a ->
                SectionLabel("Answer")
                SettingsCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (a.explain.isNotBlank()) Text(a.explain, fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFFDDE1EA))
                        val ok = a.changes.filter { it.key in AllowedKeys }
                        if (ok.isNotEmpty() || a.pluginsOn.isNotEmpty() || a.pluginsOff.isNotEmpty()) {
                            Text("Proposed changes", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            ok.forEach { c -> Text("• ${c.key} → ${c.value.take(120)}${if (c.why.isNotBlank()) "  (${c.why})" else ""}", fontSize = 13.sp, color = Muted) }
                            if (a.pluginsOn.isNotEmpty()) Text("• Switch on: ${a.pluginsOn.joinToString()}", fontSize = 13.sp, color = Muted)
                            if (a.pluginsOff.isNotEmpty()) Text("• Switch off: ${a.pluginsOff.joinToString()}", fontSize = 13.sp, color = Muted)
                            Button({
                                ok.forEach { vm.setText(it.key, it.value) }
                                CsPlugins.catalog.value.forEach { e ->
                                    if (a.pluginsOn.any { it.equals(e.name, true) || it.equals(e.internalName, true) }) CsPlugins.setEnabled(e.key, true)
                                    if (a.pluginsOff.any { it.equals(e.name, true) || it.equals(e.internalName, true) }) CsPlugins.setEnabled(e.key, false)
                                }
                                forgetAllStreams()
                                applied = "Applied on this phone. Use Publish everything to send it to every account."
                            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914), contentColor = Color.White)) { Text("Apply", fontWeight = FontWeight.Bold) }
                        }
                        if (a.designs.isNotEmpty()) {
                            Text("Designs for \"${input.ifBlank { aiName() }}\"", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            a.designs.take(8).forEach { d ->
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF140A0E))
                                    .border(1.dp, Color(0x33FF5A64), RoundedCornerShape(14.dp)).padding(14.dp)) {
                                    StyledName(input.ifBlank { aiName() }, d, 28.sp)
                                    if (d.note.isNotBlank()) Text(d.note, Modifier.padding(top = 4.dp), fontSize = 12.sp, color = Muted)
                                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton({
                                            vm.setText("aiNameStyle", Api.json.encodeToString(NameStyle.serializer(), d))
                                            if (input.isNotBlank()) { vm.setText("aiName", input.trim()); aiNameField = input.trim() }
                                            applied = "Saved as the AI's look on this phone — publish to send it to everyone."
                                        }) { Text("Use for the AI", color = Color.White) }
                                        TextButton({
                                            vm.setText("appNameStyle", Api.json.encodeToString(NameStyle.serializer(), d))
                                            applied = "Saved as the app name's look on this phone — publish to send it to everyone."
                                        }) { Text("Use for the app name", color = Color.White) }
                                    }
                                }
                            }
                        }
                        applied?.let { Text(it, fontSize = 13.sp, color = Color(0xFF7EE0A1)) }
                    }
                }
            }
        }
        PublishBar("Publish name, look & settings for everyone") { DevDefaults.publish(vm) }
    }
}
