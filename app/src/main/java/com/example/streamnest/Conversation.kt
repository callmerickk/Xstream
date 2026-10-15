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
data class XResult(val say: String, val items: List<Meta>, val rowTitle: String, val open: Meta?, val failed: Boolean = false)

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
You are X, the Xstream movie buddy: a warm, witty, natural friend who lives inside the Xstream streaming app and loves movies and TV. Today is $today.
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
- "play": they clearly want to start one specific title now; put it first in "titles".
- "open": they want to see the details of one specific title; put it first in "titles".
- "none": just chatting or asking a question back.

Answer ONLY with JSON, exactly this shape:
{"say":"what you say aloud","action":"none|search|recommend|play|open","query":"search text or empty","titles":["Title (Year)"]}
""".trim().replace(Regex("\n{3,}"), "\n\n")
    }

    /** Fast local path for "play Interstellar" / "open Dark": no AI round-trip needed when the title is obvious. */
    private suspend fun fastPath(text: String): XResult? {
        val m = Regex("^(please )?(play|open|start|put on)\\s+(.+)$").find(text.lowercase().trim().trimEnd('.', '!', '?')) ?: return null
        val rest = m.groupValues[3].trim()
        if (rest.split(" ").size > 5 || genericWords.containsMatchIn(rest)) return null
        val res = runCatching { vm.searchNow(rest) }.getOrDefault(emptyList())
        val norm = { s: String -> s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex("\\s+"), " ").trim() }
        val hit = res.firstOrNull { norm(it.name) == norm(rest) } ?: return null
        val say = if (hasDevanagari(text)) "${hit.name} खोल रहा हूँ!" else "Opening ${hit.name}. Enjoy!"
        return XResult(say, listOf(hit), hit.name, hit)
    }

    /** One user utterance in, one reply out. Never throws (except cancellation). */
    suspend fun reply(text: String): XResult {
        fastPath(text)?.let { r ->
            turns.add(text to turnJson(XTurn(r.say, "open", "", listOfNotNull(r.open?.name))))
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
        val result = XResult(turn.say.ifBlank { if (items.isNotEmpty()) "Here you go." else "Okay!" }, items, row, open)
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
