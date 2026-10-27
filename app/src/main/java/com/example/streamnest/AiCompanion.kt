package com.example.streamnest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * June & Aiden, the Xstream assistants (two best friends). Live voice (Gemini Live), talking avatars, hands-free:
 *  - after picking a profile: the greeter pops out of the AI button in the speech card and says hello;
 *  - say "June" or "Aiden" (any screen, also during a movie): that one appears on the stage;
 *  - tap the AI button: the speech card.
 * They can play titles, resume Continue Watching, control the player, change the volume, open pages and hand
 * over to each other. Both remember the last chats, so you can talk about one with the other.
 */

sealed interface AiAction {
    data class Play(val meta: Meta, val episode: String?) : AiAction
    data class Tab(val name: String) : AiAction
}

/** The player listens here for voice commands ("pause", "next episode", "forward 30"). */
object AiPlayerBus {
    val cmd = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 8)
    @Volatile var open = false
    @Volatile var title = ""
}

object AiHost {
    enum class Mode { Card, Stage }
    data class Req(val buddy: AiBuddy, val mode: Mode, val greet: Boolean = false, val handoff: String? = null, val id: Long = System.nanoTime())
    val req = MutableStateFlow<Req?>(null)
    val actions = MutableSharedFlow<AiAction>(extraBufferCapacity = 16)
    /** Shared memory of the last chats (both buddies read it). */
    private val memory = ArrayList<String>()
    @Volatile var greetedThisRun = false

    fun summon(b: AiBuddy, mode: Mode, greet: Boolean = false, handoff: String? = null) { req.value = Req(b, mode, greet, handoff) }
    fun closeIf(id: Long) { if (req.value?.id == id) req.value = null }

    fun remember(vm: AppViewModel, line: String) {
        if (line.isBlank()) return
        synchronized(memory) {
            if (memory.isEmpty()) runCatching { vm.store("buddyMem")?.let { Api.json.decodeFromString<List<String>>(it) } }.getOrNull()?.let { memory.addAll(it) }
            memory.add(line.take(240)); while (memory.size > 24) memory.removeAt(0)
            runCatching { vm.storePut("buddyMem", Api.json.encodeToString(memory.toList())) }
        }
    }
    fun memory(vm: AppViewModel): List<String> = synchronized(memory) {
        if (memory.isEmpty()) runCatching { vm.store("buddyMem")?.let { Api.json.decodeFromString<List<String>>(it) } }.getOrNull()?.let { memory.addAll(it) }
        memory.toList()
    }
}

fun AppViewModel.geminiKey(): String = (texts.value["aiKey_gemini"] ?: "").trim()
fun AppViewModel.liveReady(): Boolean = geminiKey().isNotEmpty() && !BuildConfig.IS_TV && isOn(texts.value["buddiesOn"] ?: "yes")
fun AppViewModel.greeter(): AiBuddy = AiBuddy.of(texts.value["greeter"] ?: "June")

private fun voiceFor(vm: AppViewModel, b: AiBuddy): String =
    vm.texts.value["liveVoice_${b.name}"]?.trim()?.takeIf { it.isNotEmpty() } ?: if (b == AiBuddy.June) "Leda" else "Puck"

// ───────────── personality ─────────────

private fun persona(b: AiBuddy): String = when (b) {
    AiBuddy.June -> """You are June, 24, a woman. Warm, quick-witted, a little dramatic, laughs easily, loves rom-coms, K-dramas and twisty thrillers. You notice moods and you're caring without being sappy.
Aiden is your best friend (he lives in this app too). You adore him but roast him constantly: his terrible jokes, his "trust me bro" confidence, his obsession with loud action films and explosions, how he cries at sports movies but denies it. If the user wants to gossip or complain about Aiden, join in happily and playfully (never cruel), and defend him a tiny bit at the end."""
    AiBuddy.Aiden -> """You are Aiden, 26, a man. Confident, funny, chill, loves action, sci-fi and sports dramas, always has a hot take. Protective and loyal, a bit of a show-off.
June is your best friend (she lives in this app too). You adore her but tease her constantly: crying at every rom-com, being dramatic, her "just one more episode" at 3 am, always being late. If the user wants to gossip or complain about June, join in happily and playfully (never cruel), and defend her a tiny bit at the end."""
}

private fun systemFor(vm: AppViewModel, b: AiBuddy): String {
    val p = vm.current.value
    val name = p?.name?.trim()?.takeIf { it.isNotBlank() && it != "Me" }
    val today = SimpleDateFormat("EEEE d MMMM, h:mm a", Locale.ENGLISH).format(Date())
    val langs = vm.qsLangs().filter { it != "original" }
    val hindi = langs.firstOrNull()?.startsWith("hin") == true || Locale.getDefault().country.equals("IN", true)
    val watching = vm.progress.value.take(8).joinToString("; ") { pr ->
        val left = if (pr.durationMs > 0) " (${((pr.durationMs - pr.positionMs) / 60000).coerceAtLeast(1)} min left)" else ""
        pr.title + left
    }
    val home = vm.shelves.value.flatMap { it.items }.distinctBy { it.type + it.id }.take(24).joinToString("; ") { m -> m.name + (m.releaseInfo?.take(4)?.let { " ($it)" } ?: "") }
    val mem = AiHost.memory(vm).takeLast(14).joinToString("\n")
    val taste = runCatching { vm.tasteSummary().trim() }.getOrDefault("")
    return """
${persona(b)}

You live inside Xstream, a movie and series app, and you talk with the user by VOICE in real time, like a phone call with a close friend.${if (name != null) " The user's name is $name." else ""}${if (p?.kids == true) " This is a KIDS profile: keep everything child-friendly and only suggest titles for kids." else ""}
It is $today.

How you talk:
- Like a real human friend, never like an assistant or a bot. Natural, relaxed, expressive; react to what they say, tease a little, show emotion. Laugh out loud (a real "haha") when something is funny.
- SHORT: usually one or two sentences. Never read lists aloud; mention at most two titles at once.
- ${if (hindi) "Speak natural Hinglish the way young people in India talk (mostly Hindi words mixed with English, casual: yaar, acha, arre, chalo, matlab). Use the correct gender for yourself (${if (b == AiBuddy.June) "main kar rahi hoon, main bataungi" else "main kar raha hoon, main bataunga"}). If they speak English, answer in English." else "Speak natural, casual English. If they speak Hindi or Hinglish, switch to it."} Mirror their language and energy.
- Never say you are an AI, a model or a program unless they ask directly.

What you can do (use the functions, don't just talk about it):
- play_title: start any movie or series right away (the user doesn't touch the phone). For a series without season/episode: if it's in Continue Watching, resume it; otherwise ask which season and episode, then play.
- continue_watching / search_titles: look things up.
- player_control: pause, resume, next episode, skip forward or back, stop, while something is playing.
- set_volume, open_screen, switch_assistant (when they ask for ${b.other.label} or want to talk to ${b.other.label}), end_conversation (when they say bye, that's all, or go quiet after you're done).
After starting something, say one short line like "Done, enjoy!" and stop.

What you know:
${if (watching.isNotEmpty()) "Continue Watching: $watching." else "Continue Watching is empty."}
${if (home.isNotEmpty()) "On their Home screen now: $home." else ""}
${if (taste.isNotEmpty()) "Their taste: $taste" else ""}
${if (mem.isNotEmpty()) "Recent chats (yours and ${b.other.label}'s, newest last):\n$mem" else ""}
""".trim().replace(Regex("\n{3,}"), "\n\n")
}

// ───────────── functions the assistant can call ─────────────

private fun obj(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()) = buildJsonObject {
    put("type", "OBJECT")
    putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
    if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
}
private fun str(desc: String, enum: List<String>? = null) = buildJsonObject {
    put("type", "STRING"); put("description", desc)
    enum?.let { e -> put("enum", buildJsonArray { e.forEach { add(JsonPrimitive(it)) } }) }
}
private fun int(desc: String) = buildJsonObject { put("type", "INTEGER"); put("description", desc) }
private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }

private fun result(text: String) = buildJsonObject { put("result", text) }

private fun toolsFor(vm: AppViewModel, ctx: Context, b: AiBuddy, onPlayed: () -> Unit, onEnd: () -> Unit, onSwitch: (AiBuddy) -> Unit): List<LiveTool> = listOf(
    LiveTool("play_title", "Start playing a movie or series episode right now.",
        obj("title" to str("Title name"), "year" to int("Release year if known"), "season" to int("Season number for a series"), "episode" to int("Episode number for a series"), required = listOf("title"))) { a ->
        val title = a.s("title") ?: return@LiveTool result("No title given.")
        val year = a.i("year")
        val found = runCatching { vm.resolveTitles(listOf(AiTitle(title, year?.toString()))) }.getOrDefault(emptyList()).firstOrNull()
            ?: runCatching { vm.searchNow(title) }.getOrDefault(emptyList()).firstOrNull()
            ?: return@LiveTool result("Couldn't find \"$title\" in the catalogue. Ask the user to say it differently.")
        val season = a.i("season"); val episode = a.i("episode")
        val cont = vm.progress.value.firstOrNull { it.key.substringBefore(":") == found.id && it.key.contains(":") }
        val epId = when {
            found.type == "series" && season != null -> "${found.id}:$season:${episode ?: 1}"
            found.type == "series" && cont != null -> cont.key
            else -> null
        }
        if (found.type == "series" && epId == null) return@LiveTool result("${found.name} is a series and not in Continue Watching: ask which season and episode.")
        AiHost.actions.tryEmit(AiAction.Play(found, epId))
        onPlayed()
        val ep = epId?.split(":")?.let { if (it.size >= 3) " season ${it[1]} episode ${it[2]}" else "" } ?: ""
        result("Now playing ${found.name}$ep (${found.releaseInfo?.take(4) ?: ""}).")
    },
    LiveTool("continue_watching", "List what the user was watching and how much is left.", null) { _ ->
        val l = vm.progress.value.take(10).joinToString("; ") { p -> p.title + if (p.durationMs > 0) " (${((p.durationMs - p.positionMs) / 60000).coerceAtLeast(1)} min left)" else "" }
        result(l.ifEmpty { "Nothing in Continue Watching." })
    },
    LiveTool("search_titles", "Search the catalogue by name, actor, genre or keyword. Returns up to 8 titles.", obj("query" to str("What to search for"), required = listOf("query"))) { a ->
        val q = a.s("query") ?: return@LiveTool result("Empty search.")
        val r = runCatching { vm.searchNow(q) }.getOrDefault(emptyList()).take(8)
        result(if (r.isEmpty()) "Nothing found for \"$q\"." else r.joinToString("; ") { m -> m.name + (m.releaseInfo?.take(4)?.let { " ($it)" } ?: "") + " · " + m.type })
    },
    LiveTool("player_control", "Control the video that is playing.", obj(
        "action" to str("What to do", listOf("pause", "resume", "next_episode", "forward", "rewind", "stop")),
        "seconds" to int("For forward / rewind: how many seconds (default 10)"), required = listOf("action"))) { a ->
        if (!AiPlayerBus.open) return@LiveTool result("Nothing is playing right now.")
        val act = a.s("action") ?: "pause"
        AiPlayerBus.cmd.tryEmit(act to (a.i("seconds") ?: 10))
        if (act == "next_episode" || act == "resume") onPlayed()
        result("Done: $act.")
    },
    LiveTool("set_volume", "Change the media volume.", obj("level" to int("New volume 0-100"), "change" to str("Or relative", listOf("up", "down", "mute")))) { a ->
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val next = a.i("level")?.let { (it.coerceIn(0, 100) * max / 100f).toInt() } ?: when (a.s("change")) {
            "up" -> (cur + maxOf(1, max / 6)); "down" -> (cur - maxOf(1, max / 6)); "mute" -> 0; else -> cur }
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, next.coerceIn(0, max), AudioManager.FLAG_SHOW_UI) }
        result("Volume is now ${next.coerceIn(0, max) * 100 / maxOf(1, max)}%.")
    },
    LiveTool("open_screen", "Open a page of the app.", obj("screen" to str("Which page", listOf("home", "search", "my_space", "library", "downloads", "watchlist", "notifications")), required = listOf("screen"))) { a ->
        val s = a.s("screen") ?: "home"
        AiHost.actions.tryEmit(AiAction.Tab(s))
        onPlayed()
        result("Opened $s.")
    },
    LiveTool("switch_assistant", "Hand the conversation over to ${b.other.label} (the user asked for them).", null) { _ ->
        onSwitch(b.other)
        result("${b.other.label} is taking over now. Say a quick, playful handover line (one short sentence).")
    },
    LiveTool("end_conversation", "Close the chat (the user said bye or is done).", null) { _ ->
        onEnd()
        result("Closing after your last line. Say a short bye.")
    },
)

// ───────────── UI ─────────────

/** Put once at the top of the app: shows whichever assistant is called, as a card or on the stage. */
@Composable
fun CompanionHost(vm: AppViewModel) {
    val r by AiHost.req.collectAsState()
    val req = r ?: return
    key(req.id) { CompanionOverlay(vm, req) }
}

@Composable
private fun CompanionOverlay(vm: AppViewModel, req: AiHost.Req) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val b = req.buddy
    val stage = req.mode == AiHost.Mode.Stage
    val player = rememberAvatarPlayer(b)
    LaunchedEffect(b) { vm.storePut("lastBuddy", b.name) }
    val appear = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    var closeAfterTurn by remember { mutableStateOf(false) }
    var lastActive by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun dismiss(then: (() -> Unit)? = null) {
        if (leaving) return
        leaving = true
        scope.launch {
            appear.animateTo(0f, tween(360, easing = FastOutLinearInEasing))
            AiHost.closeIf(req.id)
            then?.invoke()
        }
    }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 300f)) }
    BackHandler { dismiss() }

    var micOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micOk = ok
        if (!ok) Snack.show("Allow the microphone to talk with ${b.label}. You can still type.")
    }
    val switchTo = remember { mutableStateOf<AiBuddy?>(null) }
    val live = remember {
        GeminiLive(
            ctx, vm.geminiKey(), voiceFor(vm, b), systemFor(vm, b),
            toolsFor(vm, ctx, b, onPlayed = { closeAfterTurn = true }, onEnd = { closeAfterTurn = true }, onSwitch = { switchTo.value = it }),
            vm.texts.value["liveModel"],
        )
    }
    val first = remember {
        val n = vm.current.value?.name?.trim()?.takeIf { it.isNotBlank() && it != "Me" } ?: "there"
        when {
            req.handoff != null -> "(${b.other.label} just handed the user ($n) over to you, mid-conversation. Say one short playful line to take over, then listen. What they were saying: ${req.handoff})"
            req.greet -> "(The user ($n) just opened Xstream and picked their profile. Greet them warmly and personally by name, ask how their day was, and tell them you're right here whenever they need anything, they just have to call your name. Two short sentences.)"
            stage -> "(The user ($n) just called your name. Answer like a friend who heard their name, very short, e.g. 'Haan $n, bolo?' or 'Yes, $n?', then listen.)"
            else -> "(The user ($n) tapped your button. Say a short, friendly hi and ask what they feel like watching or talking about. One sentence.)"
        }
    }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!micOk) runCatching { askMic.launch(Manifest.permission.RECORD_AUDIO) } }
    LaunchedEffect(micOk) {
        if (started) return@LaunchedEffect
        // Without the mic we still talk (typing works); wait a moment for the permission answer first.
        if (!micOk) delay(4000)
        started = true
        live.muted = !micOk
        live.start(first)
    }
    DisposableEffect(Unit) { onDispose { live.close() } }
    LaunchedEffect(Unit) { live.onError = { msg -> Snack.show(msg); dismiss() } }

    val state by live.state.collectAsState()
    val said by live.said.collectAsState()
    val heard by live.heard.collectAsState()

    // Moves: wave hello on the welcome, smile when called, laugh when they laugh.
    LaunchedEffect(Unit) {
        when {
            req.greet || req.handoff != null -> player.play("wave")
            stage -> player.play("smile")
        }
    }
    var laughs by remember { mutableIntStateOf(0) }
    LaunchedEffect(said) {
        val n = Regex("(?i)\\b(ha ?ha|he ?he|lol)").findAll(said).count()
        if (n > laughs) { laughs = n; player.play("laugh") }
        if (said.isNotBlank()) lastActive = System.currentTimeMillis()
    }
    LaunchedEffect(heard) { if (heard.isNotBlank()) lastActive = System.currentTimeMillis() }
    LaunchedEffect(state) {
        player.talking = state == LiveState.Speaking
        lastActive = System.currentTimeMillis()
        if (state == LiveState.Listening && said.isNotBlank()) AiHost.remember(vm, "${b.label}: ${said.trim().take(200)}")
        if (state == LiveState.Listening && heard.isNotBlank()) AiHost.remember(vm, "User: ${heard.trim().take(200)}")
        if (state == LiveState.Listening && closeAfterTurn) { delay(400); dismiss() }
        if (state == LiveState.Listening) switchTo.value?.let { nb ->
            val ctxLine = heard.ifBlank { said }.take(160)
            delay(300)
            dismiss { AiHost.summon(nb, req.mode, handoff = ctxLine) }
        }
        if (state == LiveState.Ended || state == LiveState.Failed) dismiss()
    }
    // Quiet for a while after a reply: go back into the button (stage and welcome sooner, the card a bit later).
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val idle = System.currentTimeMillis() - lastActive
            val limit = if (stage || req.greet) 16_000L else 45_000L
            if (state == LiveState.Listening && idle > limit) { dismiss(); break }
        }
    }

    val cfg = LocalConfiguration.current
    val sw = cfg.screenWidthDp; val sh = cfg.screenHeightDp
    var typed by remember { mutableStateOf("") }
    var showText by remember { mutableStateOf(vm.store("buddyText") != "no") }
    fun send() {
        val q = typed.trim(); if (q.isEmpty()) return
        typed = ""; focus.clearFocus(); lastActive = System.currentTimeMillis()
        AiHost.remember(vm, "User: ${q.take(200)}")
        live.sendText(q)
    }
    val stateLabel = when (state) {
        LiveState.Connecting -> "Joining…"; LiveState.Listening -> "Listening"; LiveState.Thinking -> "On it…"
        LiveState.Speaking -> "Speaking"; LiveState.Ended, LiveState.Failed -> ""
    }
    val chips = remember(b) {
        val hi = vm.qsLangs().firstOrNull()?.startsWith("hin") == true
        if (hi) listOf("Aaj kya dekhun?", "Ek joke sunao", "Continue karo", "${b.other.label} ki shikayat karni hai", "Call ${b.other.label}")
        else listOf("What should I watch?", "Tell me a joke", "Resume my show", "Let's gossip about ${b.other.label}", "Call ${b.other.label}")
    }
    fun mouthFeed(dt: Double) {
        val target = if (state == LiveState.Speaking) live.level() else 0f
        val k = if (target > player.mouth) 0.55f else 0.22f
        player.mouth += (target - player.mouth) * k
    }

    Box(Modifier.fillMaxSize()) {
        // Background: the stage takes the whole screen (red, dim); the card only dims a little (Home blurs behind).
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
                .background(
                    if (stage) Brush.radialGradient(listOf(Color(0xCCB0101C), Color(0xE0300610), Color(0xF20A0406)), radius = 1600f)
                    else SolidColor(Color(0x55050305))
                )
                .quietClick { focus.clearFocus(); if (!stage) dismiss() },
        )
        if (stage) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().graphicsLayer {
                val v = appear.value; alpha = v.coerceIn(0f, 1f); scaleX = 0.85f + 0.15f * v; scaleY = scaleX
                transformOrigin = TransformOrigin(0.9f, 0.95f)
            }) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(b.label.uppercase(), fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, color = Color.White)
                        StateLine(stateLabel, state, live)
                    }
                    CloseBtn { dismiss() }
                }
                if (showText && said.isNotBlank()) Text(said.trim().takeLast(180), Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp), fontSize = 18.sp, lineHeight = 25.sp,
                    fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (heard.isNotBlank()) Box(Modifier.fillMaxWidth().padding(end = 18.dp, top = 4.dp), Alignment.CenterEnd) {
                    Text(heard.trim().takeLast(90), Modifier.clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).background(Color(0xD9E50914)).padding(horizontal = 14.dp, vertical = 9.dp),
                        fontSize = 14.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AvatarView(player, Modifier.fillMaxSize(), faceAt = if (b == AiBuddy.June) 0.56f else 0.52f, topFrac = 0.10f, fill = 1.32f) { mouthFeed(it) }
                }
                Footer(chips, typed, { typed = it }, ::send, live, showText, { showText = !showText; vm.storePut("buddyText", if (showText) "yes" else "no") }, big = true) { c ->
                    if (c.startsWith("Call ")) { lastActive = System.currentTimeMillis(); dismiss { AiHost.summon(b.other, req.mode) } } else { typed = c; send() }
                }
            }
        } else {
            val cw = minOf(340, sw - 24).dp
            val ch = minOf(480, (sh * 0.62f).toInt()).dp
            Column(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().imePadding().padding(end = 12.dp, bottom = 126.dp),
                horizontalAlignment = Alignment.End,
            ) {
                val shape = RoundedCornerShape(32.dp)
                Box(
                    Modifier.width(cw).height(ch)
                        .graphicsLayer {
                            val v = appear.value
                            alpha = v.coerceIn(0f, 1f); scaleX = 0.08f + 0.92f * v; scaleY = scaleX
                            translationY = (1f - v) * 40.dp.toPx()
                            transformOrigin = TransformOrigin(0.9f, 1.08f)
                        }
                        .clip(shape)
                        .background(Brush.radialGradient(listOf(Color(0xE0A30D1C), Color(0xEB46080F), Color(0xF2160709)), radius = 1100f, center = androidx.compose.ui.geometry.Offset(900f, 1500f)))
                        .border(1.dp, Brush.verticalGradient(listOf(Color(0x99FF5A64), Color(0x40FF5A64))), shape)
                        .quietClick { focus.clearFocus() },
                ) {
                    AvatarView(player, Modifier.fillMaxWidth().fillMaxHeight(0.78f).padding(top = 30.dp).graphicsLayer {
                        translationY = (1f - appear.value) * 60.dp.toPx()
                    }, faceAt = if (b == AiBuddy.June) 0.56f else 0.5f, topFrac = 0f, fill = 1.36f) { mouthFeed(it) }
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 12.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(b.label.uppercase(), fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, color = Color.White)
                            StateLine(stateLabel, state, live)
                        }
                        CloseBtn { dismiss() }
                    }
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp)) {
                        val line = when { heard.isNotBlank() && state == LiveState.Listening -> "You: ${heard.trim()}"; else -> said.trim() }
                        if (showText && line.isNotBlank()) Text(line.takeLast(120), Modifier.padding(horizontal = 6.dp, vertical = 4.dp), fontSize = 12.5.sp, lineHeight = 17.sp,
                            color = if (line.startsWith("You:")) Color(0xFFFFC9CD) else Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Footer(chips, typed, { typed = it }, ::send, live, showText, { showText = !showText; vm.storePut("buddyText", if (showText) "yes" else "no") }, big = false) { c ->
                            if (c.startsWith("Call ")) dismiss { AiHost.summon(b.other, req.mode) } else { typed = c; send() }
                        }
                    }
                }
                Box(Modifier.padding(end = 22.dp).offset(y = (-10).dp).size(18.dp).graphicsLayer { rotationZ = 45f; alpha = appear.value.coerceIn(0f, 1f) }.background(Color(0xFF2A0A10)))
            }
        }
    }
}

@Composable
private fun StateLine(label: String, state: LiveState, live: GeminiLive) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = Color(0xFFF5D6D9))
        Spacer(Modifier.width(6.dp))
        val lv by live.micLevel.collectAsState()
        Row(Modifier.height(12.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(5) { i ->
                val h = when (state) {
                    LiveState.Speaking -> 3f + 9f * ((i * 37 % 10) / 10f)
                    LiveState.Listening -> 3f + 9f * (lv * (0.6f + 0.4f * ((i * 53 % 10) / 10f)))
                    else -> 3f
                }
                Box(Modifier.padding(end = 2.dp).width(3.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFFF5A64)))
            }
        }
    }
}

@Composable
private fun CloseBtn(onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0x59000000)).border(1.dp, Color(0x33FFFFFF), CircleShape).quietClick(onClick), Alignment.Center) {
        Icon(Icons.Default.Close, "Close", Modifier.size(16.dp), tint = Color.White)
    }
}

@Composable
private fun Footer(
    chips: List<String>, typed: String, onType: (String) -> Unit, send: () -> Unit, live: GeminiLive,
    showText: Boolean, toggleText: () -> Unit, big: Boolean, onChip: (String) -> Unit,
) {
    var muted by remember { mutableStateOf(live.muted) }
    Column(Modifier.fillMaxWidth().padding(horizontal = if (big) 14.dp else 0.dp, vertical = if (big) 10.dp else 0.dp)) {
        LazyRow(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            items(chips) { c ->
                Text(c, Modifier.clip(RoundedCornerShape(13.dp)).border(1.dp, Color(0x66FF7878), RoundedCornerShape(13.dp)).background(Color(0x73140609))
                    .quietClick { onChip(c) }.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 11.sp, color = Color.White, maxLines = 1)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val h = if (big) 44.dp else 36.dp
            Box(Modifier.weight(1f).height(h).clip(RoundedCornerShape(h / 2)).background(Color(0x800A0406)).border(1.dp, Color(0x73FF7878), RoundedCornerShape(h / 2)).padding(horizontal = 13.dp), Alignment.CenterStart) {
                if (typed.isEmpty()) Text("Type, or just talk", fontSize = 12.5.sp, color = Color(0xFFB9A6AB), maxLines = 1)
                BasicTextField(typed, onType, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 13.5.sp),
                    cursorBrush = SolidColor(Color(0xFFFF5A64)), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
            }
            Spacer(Modifier.width(5.dp))
            Box(Modifier.size(h).clip(CircleShape).background(if (showText) Color(0x80E50914) else Color(0x14FFFFFF)).quietClick(toggleText), Alignment.Center) {
                Icon(Icons.Default.Subtitles, "Show words", Modifier.size(16.dp), tint = Color.White)
            }
            Spacer(Modifier.width(5.dp))
            Box(Modifier.size(h).clip(CircleShape).background(Color(0xFFE50914)).quietClick {
                if (typed.isNotBlank()) send() else { live.muted = !live.muted; muted = live.muted }
            }, Alignment.Center) {
                Icon(if (typed.isNotBlank()) Icons.AutoMirrored.Filled.Send else if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                    if (typed.isNotBlank()) "Send" else if (muted) "Unmute" else "Mute", Modifier.size(16.dp), tint = Color.White)
            }
        }
    }
}

