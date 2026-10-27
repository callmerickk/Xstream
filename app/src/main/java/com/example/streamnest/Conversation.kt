package com.example.streamnest

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * "X", the Xstream movie buddy: a real multi-turn conversation for the voice assistant.
 * Every turn the AI answers with a tiny JSON object: what to say aloud, plus an optional action
 * (search / recommend / play / open) that the app carries out with its own catalog search.
 */

/** One parsed AI turn. */
data class XTurn(val say: String, val action: String, val query: String, val titles: List<String>)

/** What the overlay shows / does after a turn. [open] = a title to open right after speaking. */
data class XResult(val say: String, val items: List<Meta>, val rowTitle: String, val open: Meta?, val failed: Boolean = false,
    /** Close the card after speaking (a player command was done). */
    val closeAfter: Boolean = false,
    /** Start playing [open] right away (Continue Watching for a series), or this episode id (tt…:season:episode). */
    val play: Boolean = false, val episode: String? = null)

/** "play dark season 2 episode 3" → ("dark", 2, 3); also "s2 e3", "season 2". */
private val epAsk = Regex("\\s+(?:season|s)\\s*(\\d{1,2})(?:\\s*(?:,|and)?\\s*(?:episode|ep|e)\\s*(\\d{1,3}))?\\s*$")

/** True when the text contains Devanagari letters (Hindi written in Hindi script). */
fun hasDevanagari(s: String): Boolean = s.any { it in '\u0900'..'\u097F' }

/** Robust parser: strips code fences, finds the first {...}; if that fails the whole text is what to say. */
fun parseXTurn(raw: String): XTurn {
    val cleaned = raw.trim().removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
    val start = cleaned.indexOf('{')
    val obj: JsonObject? = if (start < 0) null else {
        // Find the matching closing brace of the first object (ignoring braces inside strings).
        var depth = 0; var inStr = false; var esc = false; var end = -1
        for (i in start until cleaned.length) {
            val c = cleaned[i]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        val piece = if (end > start) cleaned.substring(start, end + 1) else extractJson(cleaned)
        runCatching { Api.json.parseToJsonElement(piece) as? JsonObject }.getOrNull()
    }
    if (obj == null) return XTurn(cleanSpoken(cleaned), "none", "", emptyList())
    fun str(k: String): String = (obj[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val say = str("say").ifBlank { str("reply") }.ifBlank { str("text") }
    val titles = (obj["titles"] as? JsonArray)?.mapNotNull { e ->
        when (e) {
            is JsonPrimitive -> e.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            is JsonObject -> {
                val n = (e["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val y = (e["year"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (n.isEmpty()) null else if (y.isNotEmpty()) "$n ($y)" else n
            }
            else -> null
        }
    }.orEmpty()
    val action = str("action").lowercase().let { if (it in listOf("search", "recommend", "play", "open")) it else "none" }
    return XTurn(cleanSpoken(say), action, str("query"), titles)
}

/** Removes markdown, emojis and other things that sound silly when read aloud. */
fun cleanSpoken(s: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val emoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp == 0xFE0F || cp == 0x200D || cp in 0x1F1E6..0x1F1FF
        if (!emoji) sb.appendCodePoint(cp)
        i += Character.charCount(cp)
    }
    return sb.toString().replace(Regex("[*_#`>]+"), "").replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("\\s+"), " ").trim()
}

private val titleYear = Regex("^(.*?)\\s*\\((\\d{4})\\)\\s*$")

private fun toAiTitle(s: String): AiTitle {
    val m = titleYear.find(s.trim())
    return if (m != null) AiTitle(m.groupValues[1].trim(), m.groupValues[2]) else AiTitle(s.trim())
}

/** Words that mean "pick something for me" rather than a specific title. */
private val genericWords = Regex("\\b(something|anything|some|any|movie|movies|film|films|show|shows|series|good|best|new|latest|funny|scary|like|similar|me|a|an|kuch|koi|accha|achha)\\b")

/**
 * A chat session. One lives as long as the voice overlay is open.
 * Keeps the last 16 turns; long-term memory comes from [aiMemory].
 */
class XConversation(private val vm: AppViewModel) {
    private val turns = ArrayList<Pair<String, String>>()
    private var keyHintGiven = false

    private fun systemPrompt(): String {
        val p = vm.current.value
        val name = p?.name?.takeIf { it.isNotBlank() && it != "Me" }
        val today = SimpleDateFormat("EEEE d MMMM yyyy", Locale.ENGLISH).format(Date())
        val home = vm.shelves.value.flatMap { it.items }.distinctBy { it.type + it.id }.take(20)
            .joinToString("; ") { m -> m.name + (m.releaseInfo?.take(4)?.let { " ($it)" } ?: "") }
        val watching = vm.progress.value.take(6).joinToString("; ") { it.title.substringBefore("  S") }
        val past = vm.aiMemory().takeLast(6).joinToString("\n") { t ->
            "- They said \"${t.q.take(120)}\"" + if (t.titles.isNotEmpty()) "; you suggested ${t.titles.take(4).joinToString()}" else ""
        }
        return """
You are ${aiName()}, the Xstream movie buddy: a confident, warm, witty friend (a man's voice) who lives inside the Xstream streaming app and knows movies and TV inside out. Today is $today, it is the ${partOfDay()} for them.
You know this person well and talk like an old friend who remembers what they love: use their name now and then, refer to what they watched or liked, notice their mood, and personalise every suggestion. Their audio languages, in order: ${vm.qsLangs().joinToString()}.
You are talking${if (name != null) " to $name" else ""} by VOICE: everything in "say" is read aloud.${if (p?.kids == true) " This is a KIDS profile: only suggest titles suitable for children (G / PG / U)." else ""}
Their taste:
${vm.tasteSummary().trim()}
${if (watching.isNotEmpty()) "Continue Watching: $watching." else ""}
${if (home.isNotEmpty()) "On their Home screen right now (available to play): $home." else ""}
${if (past.isNotEmpty()) "Earlier chats:\n$past" else ""}

How to talk:
- Sound like a real person chatting, not a search engine. React to what they said, be playful, have opinions.
- Keep "say" SHORT: 1 or 2 sentences, at most 35 words. No markdown, no lists, no emojis, no titles in quotes.
- Ask a short follow-up question when it helps (mood, who they're watching with, movie or series).
- Mirror their language exactly: English -> English; Hindi in Latin letters or Hinglish -> Hinglish in Latin letters; Hindi in Devanagari -> Devanagari. Never switch script on your own.
- Small talk is fine; gently bring it back to what to watch.

Actions (the app does them for you, so just talk naturally about it):
- "recommend": you suggest specific titles; put 4 to 10 real, existing titles in "titles" as "Title (Year)". Prefer things on their Home screen, things they haven't watched, never ones they disliked.
- "search": they want to find something by name, actor, genre or keyword; put the search text in "query".
- "play": they clearly want to start one specific title now; put it first in "titles". The app starts it immediately (a series resumes where they left off).
- "open": they want to see the details of one specific title; put it first in "titles".
- "none": just chatting or asking a question back.

Answer ONLY with JSON, exactly this shape:
{"say":"what you say aloud","action":"none|search|recommend|play|open","query":"search text or empty","titles":["Title (Year)"]}
""".trim().replace(Regex("\n{3,}"), "\n\n")
    }

    private fun partOfDay(): String = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..21 -> "evening"; else -> "late night" }

    /** Video controls while a movie plays: "pause", "resume", "next episode", "forward 30", "stop". */
    private fun playerCommand(text: String): XResult? {
        if (!AiPlayerBus.open) return null
        val t = text.lowercase().trim().trimEnd('.', '!', '?')
        val n = Regex("(\\d{1,3})").find(t)?.value?.toIntOrNull()
        val mins = t.contains("minute")
        val (act, secs, say) = when {
            Regex("^(pause|stop it|hold on|ruko)\\b").containsMatchIn(t) -> Triple("pause", 0, "Paused.")
            Regex("^(resume|continue|play( it)?|carry on|unpause|chalao)$").matches(t) -> Triple("resume", 0, "Back to it.")
            t.contains("next episode") -> Triple("next_episode", 0, "Next episode coming up.")
            Regex("(forward|skip|aage)").containsMatchIn(t) -> Triple("forward", (n ?: 10) * (if (mins) 60 else 1), "Skipping ahead.")
            Regex("(rewind|back|go back|peeche|piche)").containsMatchIn(t) && !t.startsWith("go back home") -> Triple("rewind", (n ?: 10) * (if (mins) 60 else 1), "Going back.")
            Regex("^(stop|close|exit)( the)?( movie| video| player)?$").matches(t) -> Triple("stop", 0, "Stopped.")
            else -> return null
        }
        AiPlayerBus.send(act, secs)
        return XResult(say, emptyList(), "", null, closeAfter = act != "pause")
    }

    /** Fast local path for "play Interstellar" / "open Dark" / "play Dark season 2 episode 3": no AI round-trip. */
    private suspend fun fastPath(text: String): XResult? {
        val m = Regex("^(please )?(play|open|start|put on|chalao|lagao)\\s+(.+)$").find(text.lowercase().trim().trimEnd('.', '!', '?')) ?: return null
        val verb = m.groupValues[2]
        var rest = m.groupValues[3].trim().removeSuffix(" please").removePrefix("the movie ").removePrefix("the series ").trim()
        val ep = epAsk.find(rest)
        if (ep != null) rest = rest.substring(0, ep.range.first).trim()
        if (rest.isEmpty() || rest.split(" ").size > 6 || (ep == null && genericWords.matches(rest))) return null
        if (Regex("^(something|anything|a movie|a film|a show|some movie|kuch)\\b").containsMatchIn(rest)) return null
        val hit = findOne(rest) ?: return null
        val play = verb != "open"
        val episode = ep?.let { e -> if (hit.type == "series") "${hit.id.substringBefore(":")}:${e.groupValues[1]}:${e.groupValues[2].ifEmpty { "1" }}" else null }
        val say = when {
            !play -> if (hasDevanagari(text)) "${hit.name} खोल रहा हूँ!" else "Opening ${hit.name}."
            episode != null -> "Playing ${hit.name}, season ${ep!!.groupValues[1]} episode ${ep.groupValues[2].ifEmpty { "1" }}. Enjoy!"
            hit.type == "series" -> "Playing ${hit.name} from where you left off. Enjoy!"
            else -> if (hasDevanagari(text)) "${hit.name} चला रहा हूँ!" else "Playing ${hit.name}. Enjoy!"
        }
        return XResult(say, listOf(hit), hit.name, hit, play = play, episode = episode)
    }

    /** The one title a name means: Continue Watching first, then Home, then search (exact name, newest / most popular first). */
    private suspend fun findOne(name: String): Meta? {
        val norm = { s: String -> s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim() }
        val want = norm(name)
        val yearAsk = Regex("\\b(19|20)\\d{2}\\b").find(want)?.value
        val bare = if (yearAsk != null) norm(want.replace(yearAsk, "")) else want
        fun ok(m: Meta) = norm(m.name) == bare && (yearAsk == null || m.releaseInfo?.startsWith(yearAsk) == true)
        vm.progress.value.firstOrNull { norm(it.title.substringBefore("  S")) == bare }?.let { p ->
            val id = p.key.substringBefore(":")
            vm.shelves.value.flatMap { it.items }.firstOrNull { it.id.substringBefore(":") == id }?.let { return it }
        }
        vm.shelves.value.flatMap { it.items }.firstOrNull(::ok)?.let { return it }
        val res = runCatching { vm.searchNow(bare) }.getOrDefault(emptyList())
        return res.firstOrNull(::ok) ?: res.firstOrNull { norm(it.name) == bare }
            ?: res.firstOrNull { norm(it.name).startsWith(bare) && bare.length >= 4 }
    }

    /** One user utterance in, one reply out. Never throws (except cancellation). */
    suspend fun reply(text: String): XResult {
        playerCommand(text)?.let { return it }
        fastPath(text)?.let { r ->
            turns.add(text to turnJson(XTurn(r.say, if (r.play) "play" else "open", "", listOfNotNull(r.open?.name))))
            return r
        }
        if (!vm.aiReady()) return offline(text, hint = !keyHintGiven).also { keyHintGiven = true }

        val raw = try {
            vm.aiChat(systemPrompt(), turns.takeLast(16), text, maxTokens = 220)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: "The AI didn't answer."
            Snack.show(msg)
            val off = offline(text, hint = false)
            return off.copy(say = if (off.items.isNotEmpty()) "I couldn't reach my brain just now, but here's what I found." else "Hmm, I couldn't reach my brain just now. Try again in a moment?", failed = true)
        }
        val turn = parseXTurn(raw).let { if (it.say.isBlank() && it.action == "none") it.copy(say = "Sorry, I lost my train of thought. Say that again?") else it }
        turns.add(text to turnJson(turn))
        while (turns.size > 16) turns.removeAt(0)

        val items: List<Meta>
        var open: Meta? = null
        var row = ""
        when (turn.action) {
            "search" -> {
                val q = turn.query.ifBlank { turn.titles.firstOrNull()?.let { toAiTitle(it).name } ?: text }
                items = runCatching { vm.searchNow(q) }.getOrDefault(emptyList()).take(20)
                row = "Results for \"$q\""
            }
            "recommend" -> {
                items = if (turn.titles.isNotEmpty()) runCatching { vm.resolveTitles(turn.titles.map(::toAiTitle)) }.getOrDefault(emptyList())
                else if (turn.query.isNotBlank()) runCatching { vm.searchNow(turn.query) }.getOrDefault(emptyList()).take(20)
                else emptyList()
                row = "Picked for you"
            }
            "play", "open" -> {
                val first = turn.titles.firstOrNull()?.let(::toAiTitle) ?: turn.query.takeIf { it.isNotBlank() }?.let { AiTitle(it) }
                items = if (first != null) runCatching { vm.resolveTitles(listOf(first)) }.getOrDefault(emptyList()) else emptyList()
                open = items.firstOrNull()
                if (open == null && first != null) {
                    // Couldn't pin it down: show search results instead of opening the wrong thing.
                    return XResult(
                        turn.say.ifBlank { "Here's what I found." },
                        runCatching { vm.searchNow(first.name) }.getOrDefault(emptyList()).take(20), "Results for \"${first.name}\"", null,
                    ).also { memorize(text, it) }
                }
                row = open?.name ?: ""
            }
            else -> items = emptyList()
        }
        val result = XResult(turn.say.ifBlank { if (items.isNotEmpty()) "Here you go." else "Okay!" }, items, row, open, play = turn.action == "play" && open != null)
        memorize(text, result, turn.titles)
        return result
    }

    private fun turnJson(t: XTurn): String = buildJsonObject {
        put("say", t.say); put("action", t.action); put("query", t.query)
        putJsonArray("titles") { t.titles.take(10).forEach { add(it) } }
    }.toString()

    private fun memorize(text: String, r: XResult, titles: List<String> = emptyList()) {
        runCatching { vm.aiRemember(AiTurn(text, r.say.take(400), titles.ifEmpty { r.items.take(6).map { it.name } }.take(8), System.currentTimeMillis())) }
    }

    /** No AI key: the app's own offline understanding (moods, genres, "play …", search). */
    private suspend fun offline(text: String, hint: Boolean): XResult {
        val a = runCatching { vm.interpret(text) }.getOrElse { VoiceAnswer("Sorry, something went wrong. Please try again.", "", emptyList()) }
        if (hint) Snack.show("Add a free Groq key in Integrations to chat with X")
        val say = if (hint) "${a.say} Add a free Groq key in Integrations so I can chat properly." else a.say
        memorize(text, XResult(a.say, a.items, a.title, null))
        return XResult(say, a.items, a.title, null)
    }
}
