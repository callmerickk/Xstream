package com.example.streamnest

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/*
 * AI assistant (Help & Settings > Integrations).
 * Every provider here speaks the same "OpenAI-compatible" chat API, so one small client serves all of them.
 * The key is the user's own and stays on the phone.
 */

data class AiProvider(val id: String, val label: String, val url: String, val model: String, val keyPage: String, val note: String, val jsonMode: Boolean)

val AiProviders = listOf(
    AiProvider("groq", "Groq", "https://api.groq.com/openai/v1/chat/completions", "openai/gpt-oss-120b",
        "https://console.groq.com/keys", "Free, very fast. Recommended.", true),
    AiProvider("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-2.5-flash",
        "https://aistudio.google.com/apikey", "Free tier from Google.", false),
    AiProvider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "meta-llama/llama-3.3-70b-instruct:free",
        "https://openrouter.ai/keys", "Many models, some free.", false),
    AiProvider("openai", "ChatGPT", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini",
        "https://platform.openai.com/api-keys", "OpenAI. Paid.", true),
)

@Serializable data class AiTitle(val name: String = "", val year: String? = null, val type: String? = null)
@Serializable data class AiReply(val reply: String = "", val title: String = "", val titles: List<AiTitle> = emptyList())
@Serializable data class AiTurn(val q: String, val a: String, val titles: List<String> = emptyList(), val at: Long = 0)

/** A vote from the "How was it?" capsule: 1 = Go for it, -1 = Skip, 0 = Just surfing. */
@Serializable data class Vote(val id: String, val type: String, val name: String, val poster: String? = null, val genres: List<String> = emptyList(), val v: Int, val at: Long)

private fun aiHttp() = Api.http.newBuilder().readTimeout(45, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()

fun AppViewModel.aiProvider(): AiProvider = AiProviders.firstOrNull { it.id == (texts.value["aiProvider"] ?: "groq") } ?: AiProviders[0]
fun AppViewModel.aiKey(p: AiProvider = aiProvider()): String = (texts.value["aiKey_${p.id}"] ?: "").trim()
fun AppViewModel.aiModel(p: AiProvider = aiProvider()): String = (texts.value["aiModel_${p.id}"] ?: "").trim().ifEmpty { p.model }
fun AppViewModel.aiReady(): Boolean = aiKey().isNotEmpty() && isOn(texts.value["aiOn"] ?: "yes")

private class AiHttpError(val code: Int, val msg: String) : Exception(msg)

/** Models each provider prefers, best first. The first one your key can use is picked automatically. */
private val PreferredModels = mapOf(
    "groq" to listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "meta-llama/llama-4-maverick-17b-128e-instruct",
        "openai/gpt-oss-20b", "meta-llama/llama-4-scout-17b-16e-instruct", "qwen/qwen3-32b", "llama-3.1-8b-instant"),
    "gemini" to listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-flash-latest", "gemini-1.5-flash"),
    "openrouter" to listOf("meta-llama/llama-3.3-70b-instruct:free", "openai/gpt-oss-120b:free", "google/gemini-2.0-flash-exp:free", "deepseek/deepseek-chat-v3-0324:free"),
    "openai" to listOf("gpt-4o-mini", "gpt-4.1-mini", "gpt-4o"),
)

private fun modelsUrl(p: AiProvider) = p.url.removeSuffix("/chat/completions") + "/models"

/** Chat models this key can use (from the provider's /models list). */
suspend fun AppViewModel.aiModels(p: AiProvider, key: String): List<String> = withContext(Dispatchers.IO) {
    runCatching {
        val req = Request.Builder().url(modelsUrl(p)).header("Authorization", "Bearer ${key.trim()}").build()
        aiHttp().newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use emptyList<String>()
            val o = Api.json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
            (o["data"] ?: o["models"])!!.jsonArray.mapNotNull { e ->
                val m = e.jsonObject
                (m["id"] ?: m["name"])?.jsonPrimitive?.contentOrNull?.removePrefix("models/")
            }.filterNot { id -> listOf("whisper", "tts", "guard", "embed", "playai", "distil", "vision-preview", "orpheus", "compound").any { id.contains(it, true) } }
        }
    }.getOrDefault(emptyList())
}

/** Models that "think" before answering: their hidden reasoning counts against max_tokens. */
private fun isReasoningModel(model: String): Boolean = listOf("gpt-oss", "gemini-2.5", "qwen3", "-r1", "o1", "o3", "o4-mini").any { model.contains(it, true) }

private suspend fun AppViewModel.rawChat(
    p: AiProvider, key: String, model: String, json: Boolean, system: String, history: List<Pair<String, String>>, user: String,
    maxTokens: Int? = null, lowEffort: Boolean = false,
): String {
    val body = buildJsonObject {
        put("model", model)
        put("temperature", 0.7)
        if (maxTokens != null) put("max_tokens", if (isReasoningModel(model)) maxOf(maxTokens, 1200) else maxTokens)
        if (lowEffort && isReasoningModel(model) && (p.id == "groq" || p.id == "gemini")) put("reasoning_effort", "low")
        if (json) putJsonObject("response_format") { put("type", "json_object") }
        putJsonArray("messages") {
            addJsonObject { put("role", "system"); put("content", system) }
            history.forEach { (q, a) ->
                addJsonObject { put("role", "user"); put("content", q) }
                addJsonObject { put("role", "assistant"); put("content", a) }
            }
            addJsonObject { put("role", "user"); put("content", user) }
        }
    }
    val req = Request.Builder().url(p.url)
        .header("Authorization", "Bearer $key")
        .header("Content-Type", "application/json")
        .apply { if (p.id == "openrouter") header("X-Title", "Xstream") }
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()
    return aiHttp().newCall(req).execute().use { r ->
        val text = r.body?.string().orEmpty()
        if (!r.isSuccessful) {
            val msg = runCatching { Api.json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull }.getOrNull()
            throw AiHttpError(r.code, msg ?: "AI error ${r.code}")
        }
        Api.json.parseToJsonElement(text).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }
}

/**
 * Chat call. If the model isn't available for this key, picks one that is (and remembers it);
 * if JSON mode isn't supported, retries without it. Throws with a readable message.
 * [maxTokens] (optional) caps the answer length and asks thinking models to think briefly (used by the live voice chat).
 */
suspend fun AppViewModel.aiChat(
    system: String, history: List<Pair<String, String>>, user: String, p: AiProvider = aiProvider(), key: String = aiKey(p),
    maxTokens: Int? = null,
): String =
    withContext(Dispatchers.IO) {
        var model = aiModel(p)
        var json = p.jsonMode
        var lowEffort = maxTokens != null
        var limit = maxTokens
        var lastError: Exception? = null
        repeat(4) {
            try {
                return@withContext rawChat(p, key, model, json, system, history, user, limit, lowEffort)
            } catch (e: AiHttpError) {
                lastError = e
                val m = e.msg.lowercase()
                when {
                    e.code == 401 || e.code == 403 -> throw IllegalStateException("The API key was rejected. Check it in Integrations.")
                    e.code == 429 -> throw IllegalStateException("Too many requests right now. Try again in a minute.")
                    lowEffort && m.contains("reasoning") -> lowEffort = false
                    limit != null && (m.contains("max_tokens") || m.contains("max_completion_tokens")) -> limit = null
                    json && (m.contains("response_format") || m.contains("json")) -> json = false
                    e.code == 404 || m.contains("model") -> {
                        val available = aiModels(p, key)
                        val pick = PreferredModels[p.id].orEmpty().firstOrNull { it in available && it != model }
                            ?: available.firstOrNull { it != model && !it.contains("instant") } ?: available.firstOrNull { it != model }
                            ?: throw IllegalStateException(e.msg)
                        model = pick
                        withContext(Dispatchers.Main) { setText("aiModel_${p.id}", pick) }
                    }
                    else -> throw IllegalStateException(e.msg)
                }
            }
        }
        throw IllegalStateException(lastError?.message ?: "The AI didn't answer.")
    }

/** Checks a key from the Integrations page. null = works. */
suspend fun AppViewModel.aiTest(p: AiProvider, key: String): String? = runCatching {
    aiChat("Reply with the JSON {\"reply\":\"ok\"}.", emptyList(), "ping", p, key.trim()); null
}.getOrElse { it.message ?: "Couldn't reach the AI." }

internal fun extractJson(s: String): String {
    val a = s.indexOf('{'); val b = s.lastIndexOf('}')
    return if (a >= 0 && b > a) s.substring(a, b + 1) else s
}

// ───────────── Memory (per profile) ─────────────

fun AppViewModel.aiMemory(): List<AiTurn> = store("ai_mem")?.let { runCatching { Api.json.decodeFromString<List<AiTurn>>(it) }.getOrNull() } ?: emptyList()

fun AppViewModel.aiRemember(turn: AiTurn) {
    storePut("ai_mem", Api.json.encodeToString((aiMemory() + turn).takeLast(30)))
}

fun AppViewModel.forgetAiMemory() = storePut("ai_mem", null)

/** What the assistant knows about this profile's taste. */
internal fun AppViewModel.tasteSummary(): String {
    val v = votes.value.values.sortedByDescending { it.at }
    val liked = v.filter { it.v > 0 }.take(15).joinToString { it.name }
    val skipped = v.filter { it.v < 0 }.take(10).joinToString { it.name }
    val watching = progress.value.take(8).joinToString { it.title.substringBefore("  S") }
    val saved = myList.value.take(10).joinToString { it.name }
    return buildString {
        if (liked.isNotEmpty()) append("Liked: $liked.\n")
        if (skipped.isNotEmpty()) append("Did not like: $skipped.\n")
        if (watching.isNotEmpty()) append("Recently watching: $watching.\n")
        if (saved.isNotEmpty()) append("Watchlist: $saved.\n")
    }.ifEmpty { "No history yet.\n" }
}

private fun AppViewModel.systemPrompt(): String {
    val p = current.value
    val today = SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).format(Date())
    return """
You are the friendly voice assistant inside Xstream, a movie and TV streaming app. Today is $today.
You are talking to ${p?.name ?: "the viewer"}.${if (p?.kids == true) " This is a KIDS profile: only suggest titles suitable for children (G / PG / U)." else ""}
What you know about their taste:
${tasteSummary()}
Rules:
- Answer the request, guess their mood, and recommend real, existing movies or TV series that fit. Use their taste and earlier messages.
- Never recommend something they did not like. Prefer things they haven't watched.
- Keep "reply" short and warm (1–2 sentences, it is read aloud). Speak the user's language.
- If they ask for a specific title, put that title first.
- Always answer ONLY with JSON in this exact shape:
{"reply":"…","title":"short row title","titles":[{"name":"Exact title","year":"2014","type":"movie|series"}]}
- Give 8 to 12 titles.
""".trim()
}

/** Ask the AI. Returns null when no key is set or the call fails (the offline assistant takes over). */
suspend fun AppViewModel.aiAsk(text: String): VoiceAnswer? {
    if (!aiReady()) return null
    val history = aiMemory().takeLast(6).map { it.q to it.a }
    val raw = runCatching { aiChat(systemPrompt(), history, text) }.getOrElse { e -> return VoiceAnswer(e.message ?: "The AI didn't answer.", "", emptyList()) }
    val parsed = runCatching { Api.json.decodeFromString<AiReply>(extractJson(raw)) }.getOrNull()
        ?: return VoiceAnswer(raw.take(240), "", emptyList())
    val items = resolveTitles(parsed.titles)
    aiRemember(AiTurn(text, raw.take(1500), parsed.titles.map { it.name }, System.currentTimeMillis()))
    return VoiceAnswer(parsed.reply.ifBlank { "Here's what I found." }, parsed.title.ifBlank { "Picked for you" }, items)
}

/** Turn AI title names into real catalog items (Cinemeta / your addons) you can open. */
suspend fun AppViewModel.resolveTitles(list: List<AiTitle>): List<Meta> = coroutineScope {
    list.take(14).map { t ->
        async {
            val res = runCatching { searchNow(t.name) }.getOrDefault(emptyList())
            val type = when (t.type?.lowercase()) { "movie", "film" -> "movie"; "series", "tv", "show" -> "series"; else -> null }
            res.filter { type == null || it.type == type }.ifEmpty { res }
                .sortedByDescending { m ->
                    var s = 0
                    if (m.name.equals(t.name, ignoreCase = true)) s += 4
                    if (t.year != null && m.releaseInfo?.startsWith(t.year.take(4)) == true) s += 3
                    s
                }.firstOrNull()
        }
    }.awaitAll().filterNotNull().distinctBy { it.id }.filter { m -> votes.value[m.id]?.v != -1 }
}

/** AI picks for the "Recommended for you" row (cached for 12 hours). */
suspend fun AppViewModel.aiRecommendations(): List<Meta> {
    if (!aiReady()) return emptyList()
    val cacheAt = store("ai_rec_at")?.toLongOrNull() ?: 0L
    val cached = store("ai_rec")?.let { runCatching { Api.json.decodeFromString<List<AiTitle>>(it) }.getOrNull() }
    val titles = if (cached != null && System.currentTimeMillis() - cacheAt < 12 * 3600_000L) cached else {
        val raw = runCatching {
            aiChat(systemPrompt(), emptyList(), "Recommend 12 titles I'm likely to love next, based on my taste. Mix movies and series. Don't include titles I already watched or liked.")
        }.getOrNull() ?: return emptyList()
        val t = runCatching { Api.json.decodeFromString<AiReply>(extractJson(raw)).titles }.getOrDefault(emptyList())
        storePut("ai_rec", Api.json.encodeToString(t)); storePut("ai_rec_at", System.currentTimeMillis().toString())
        t
    }
    return resolveTitles(titles)
}
