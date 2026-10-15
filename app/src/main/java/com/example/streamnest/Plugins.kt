package com.example.streamnest

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/** An installed Xstream plugin (a JavaScript file, see PLUGINS.md). */
@Serializable
data class PluginInfo(
    val id: String, val name: String, val version: String = "1.0.0", val description: String = "", val author: String = "",
    val types: List<String> = listOf("movie", "series"), val source: String = "", val enabled: Boolean = true, val code: String = "",
)

/**
 * Xstream plugins: small JavaScript files that find streams for a title, run inside the app (Rhino engine, no Java
 * access — only the helpers given here: http.get / http.post, log, atob / btoa). Each enabled plugin is asked
 * alongside your addons; its results appear in Sources like an addon's and go through the same ranking and filters.
 * The format is described in PLUGINS.md (API version 1).
 */
object Plugins {
    const val API = 1
    private var prefs: android.content.SharedPreferences? = null
    val list = MutableStateFlow<List<PluginInfo>>(emptyList())
    /** The last lines plugins wrote with log() (Settings › Plugins › Log). */
    val logLines = MutableStateFlow<List<String>>(emptyList())

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences("plugins", Context.MODE_PRIVATE)
        list.value = runCatching { Api.json.decodeFromString<List<PluginInfo>>(prefs?.getString("all", null) ?: "[]") }.getOrDefault(emptyList())
    }

    private fun save(l: List<PluginInfo>) { list.value = l; prefs?.edit()?.putString("all", Api.json.encodeToString(l))?.apply() }

    fun enabled(): List<PluginInfo> = list.value.filter { it.enabled }

    fun setEnabled(id: String, on: Boolean) = save(list.value.map { if (it.id == id) it.copy(enabled = on) else it })
    fun remove(id: String) = save(list.value.filter { it.id != id })

    private fun log(name: String, m: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        logLines.value = (logLines.value + "$t [$name] ${m.take(300)}").takeLast(200)
    }

    /** Installs (or updates) a plugin from its code. Null = installed, else why not. */
    suspend fun install(code: String, source: String = ""): String? = withContext(Dispatchers.Default) {
        val manifest = runCatching { exec(code, "JSON.stringify(plugin)", "{}", 8_000, "install") }.getOrElse { return@withContext "The plugin didn't load: ${it.message?.take(160)}" }
            ?: return@withContext "The plugin has no `plugin` object (its name, id and version)."
        val o = runCatching { Api.json.parseToJsonElement(manifest).jsonObject }.getOrNull() ?: return@withContext "`plugin` must be an object."
        fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        val id = str("id")?.takeIf { it.isNotBlank() } ?: return@withContext "`plugin.id` is missing."
        val name = str("name")?.takeIf { it.isNotBlank() } ?: return@withContext "`plugin.name` is missing."
        val api = str("api")?.toIntOrNull() ?: 1
        if (api > API) return@withContext "This plugin needs a newer Xstream (plugin API $api, this app has $API)."
        val types = runCatching { o["types"]!!.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()?.ifEmpty { null } ?: listOf("movie", "series")
        if (!code.contains("getStreams")) return@withContext "The plugin has no getStreams(request) function."
        val p = PluginInfo(id, name, str("version") ?: "1.0.0", str("description").orEmpty(), str("author").orEmpty(), types, source, true, code)
        withContext(Dispatchers.Main) { save(list.value.filter { it.id != id } + p) }
        null
    }

    suspend fun installFromUrl(url: String): String? {
        val code = runCatching { Api.get(url.trim()) }.getOrElse { return "Couldn't download it: ${it.message?.take(100)}" }
        return install(code, url.trim())
    }

    /** Asks one plugin for the streams of a title. Never throws; empty on failure (the reason goes to the plugin log). */
    suspend fun streams(p: PluginInfo, type: String, id: String, title: String? = null, year: String? = null): List<StreamItem> = withContext(Dispatchers.Default) {
        if (type !in p.types) return@withContext emptyList()
        val parts = id.split(":")
        val req = buildString {
            append("{\"type\":").append(q(type)).append(",\"id\":").append(q(id)).append(",\"imdbId\":").append(q(parts[0]))
            if (parts.size >= 3) append(",\"season\":").append(parts[1].toIntOrNull() ?: 0).append(",\"episode\":").append(parts[2].toIntOrNull() ?: 0)
            if (title != null) append(",\"title\":").append(q(title))
            if (year != null) append(",\"year\":").append(q(year))
            append("}")
        }
        val out = runCatching { exec(p.code, "JSON.stringify(getStreams(JSON.parse(__req)) || [])", req, 25_000, p.name) }
            .onFailure { log(p.name, "error: ${it.message}") }.getOrNull() ?: return@withContext emptyList()
        runCatching {
            Api.json.parseToJsonElement(out).jsonArray.mapNotNull { el ->
                val s = runCatching { Api.json.decodeFromJsonElement(StreamInfo.serializer(), el) }.getOrNull() ?: return@mapNotNull null
                // Plugin shorthand: "headers" next to the url = the request headers the video needs.
                val extra = (el.jsonObject["headers"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
                val fixed = if (extra.isNullOrEmpty() || s.behaviorHints?.proxyHeaders != null) s
                    else s.copy(behaviorHints = (s.behaviorHints ?: BehaviorHints()).copy(proxyHeaders = ProxyHeaders(extra)))
                if (fixed.url.isNullOrBlank()) null else StreamItem(p.name, fixed).also { StreamHeaders.remember(it) }
            }
        }.onFailure { log(p.name, "bad result: ${it.message}") }.getOrDefault(emptyList())
    }

    private fun q(s: String) = Api.json.encodeToString(s)

    /** The helpers every plugin gets. */
    private const val PRELUDE = """
var http = {
  get: function (url, headers) { return JSON.parse(__http('GET', String(url), JSON.stringify(headers || {}), '')); },
  post: function (url, body, headers) { return JSON.parse(__http('POST', String(url), JSON.stringify(headers || {}), typeof body === 'string' ? body : JSON.stringify(body || {}))); }
};
function log(m) { __log(String(m)); }
function atob(s) { return __b64d(String(s)); }
function btoa(s) { return __b64e(String(s)); }
"""

    /** Stops a plugin that runs too long (checked every 10 000 steps). */
    private class TimedFactory(val deadline: Long) : ContextFactory() {
        override fun observeInstructionCount(cx: org.mozilla.javascript.Context, instructionCount: Int) {
            if (System.currentTimeMillis() > deadline) throw Error("The plugin took too long")
        }
    }

    /** Runs [code], then [expr] (with __req = [reqJson]); returns the string result. */
    private fun exec(code: String, expr: String, reqJson: String, timeoutMs: Long, name: String): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val factory = TimedFactory(deadline)
        val cx = factory.enterContext()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = org.mozilla.javascript.Context.VERSION_ES6
            cx.instructionObserverThreshold = 10_000
            val scope = cx.initSafeStandardObjects()
            fun fn(f: (Array<out Any?>) -> Any?) = object : BaseFunction() {
                override fun call(c: org.mozilla.javascript.Context?, s: Scriptable?, t: Scriptable?, args: Array<Any?>): Any? = f(args)
            }
            ScriptableObject.putProperty(scope, "__req", reqJson)
            ScriptableObject.putProperty(scope, "__log", fn { a -> log(name, a.getOrNull(0)?.toString().orEmpty()); null })
            ScriptableObject.putProperty(scope, "__b64d", fn { a -> String(android.util.Base64.decode(a.getOrNull(0)?.toString().orEmpty(), android.util.Base64.DEFAULT)) })
            ScriptableObject.putProperty(scope, "__b64e", fn { a -> android.util.Base64.encodeToString(a.getOrNull(0)?.toString().orEmpty().toByteArray(), android.util.Base64.NO_WRAP) })
            ScriptableObject.putProperty(scope, "__http", fn { a ->
                if (System.currentTimeMillis() > deadline) throw Error("The plugin took too long")
                httpCall(a.getOrNull(0)?.toString() ?: "GET", a.getOrNull(1)?.toString().orEmpty(), a.getOrNull(2)?.toString() ?: "{}", a.getOrNull(3)?.toString().orEmpty())
            })
            cx.evaluateString(scope, PRELUDE, "prelude", 1, null)
            cx.evaluateString(scope, code, name, 1, null)
            val r = cx.evaluateString(scope, expr, "call", 1, null)
            return if (r == null || r is org.mozilla.javascript.Undefined) null else org.mozilla.javascript.Context.toString(r)
        } finally {
            org.mozilla.javascript.Context.exit()
        }
    }

    /** http.get / http.post for plugins: {status, url, body, headers}. */
    private fun httpCall(method: String, url: String, headersJson: String, body: String): String {
        val h = runCatching { Api.json.parseToJsonElement(headersJson).jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() } }.getOrDefault(emptyMap())
        return runCatching {
            val b = Request.Builder().url(url)
            h.forEach { (k, v) -> b.header(k, v) }
            if (h.keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", PlayerUserAgent)
            if (method.equals("POST", true)) b.post(body.toRequestBody((h.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value ?: "application/json").toMediaTypeOrNull()))
            Api.http.newCall(b.build()).execute().use { r ->
                val text = r.body?.string().orEmpty().take(4_000_000)
                val hdrs = r.headers.toMultimap().mapValues { it.value.joinToString(", ") }
                "{\"status\":${r.code},\"url\":${q(r.request.url.toString())},\"body\":${q(text)},\"headers\":${Api.json.encodeToString(hdrs)}}"
            }
        }.getOrElse { "{\"status\":0,\"url\":${q(url)},\"body\":\"\",\"headers\":{},\"error\":${q(it.message ?: it.javaClass.simpleName)}}" }
    }
}

/** Settings › Content & Discovery › Plugins. */
@Composable
fun PluginsPage(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val plugins by Plugins.list.collectAsStateWithLifecycle()
    val logs by Plugins.logLines.collectAsStateWithLifecycle()
    var link by remember { mutableStateOf("") }
    var paste by remember { mutableStateOf("") }
    var pasting by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var testOut by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    Column(Modifier.fillMaxSize().pageScroll().navigationBarsPadding().padding(bottom = 40.dp)) {
        PageHeader("Plugins", onBack)
        Text("Plugins are small JavaScript files that find streams for a title. They are asked together with your addons and their links " +
            "go through the same ranking and filters. Format: plugin API ${Plugins.API} (see PLUGINS.md).",
            Modifier.padding(horizontal = 20.dp), fontSize = 13.sp, color = Muted)
        SectionLabel("Install")
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(link, { link = it.trim() }, Modifier.fillMaxWidth().tvOkKeyboard(), singleLine = true, label = { Text("Plugin link (.js)") },
                    shape = RoundedCornerShape(12.dp), keyboardOptions = tvKeyboard())
                Row(Modifier.padding(top = 6.dp)) {
                    TextButton({ busy = true; scope.launch { msg = Plugins.installFromUrl(link) ?: "Installed"; busy = false; if (msg == "Installed") link = "" } },
                        enabled = !busy && link.startsWith("http")) { Text("Install") }
                    TextButton({ pasting = !pasting }) { Text(if (pasting) "Hide" else "Paste code") }
                }
                if (pasting) {
                    OutlinedTextField(paste, { paste = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp).tvOkKeyboard(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp), label = { Text("Plugin code") }, keyboardOptions = tvKeyboard())
                    TextButton({ busy = true; scope.launch { msg = Plugins.install(paste) ?: "Installed"; busy = false; if (msg == "Installed") { paste = ""; pasting = false } } },
                        enabled = !busy && paste.isNotBlank()) { Text("Install pasted code") }
                }
                msg?.let { Text(it, Modifier.padding(top = 6.dp), fontSize = 13.sp, color = if (it == "Installed") Color(0xFF7DE2B8) else Color(0xFFFF8A80)) }
            }
        }
        SectionLabel("Installed (${plugins.size})")
        if (plugins.isEmpty()) Text("No plugins yet.", Modifier.padding(horizontal = 20.dp), fontSize = 14.sp, color = Muted)
        plugins.forEach { p ->
            SettingsCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${p.name}  ·  v${p.version}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(p.description.ifBlank { null }, p.author.ifBlank { null }?.let { "by $it" }, p.types.joinToString(" / ")).joinToString(" · "),
                                fontSize = 12.sp, color = Muted)
                        }
                        Switch(p.enabled, { Plugins.setEnabled(p.id, it) })
                    }
                    Row(Modifier.padding(top = 4.dp)) {
                        TextButton({
                            testOut = testOut + (p.id to "Testing with The Shawshank Redemption…")
                            scope.launch {
                                val t0 = System.currentTimeMillis()
                                val r = Plugins.streams(p, "movie", "tt0111161", "The Shawshank Redemption", "1994")
                                testOut = testOut + (p.id to "${r.size} links in ${(System.currentTimeMillis() - t0) / 1000.0} s" +
                                    (r.firstOrNull()?.let { "\nfirst: ${it.stream.name ?: ""} ${it.stream.title ?: ""}".take(160) } ?: "\n(see the log below)"))
                            }
                        }) { Text("Test") }
                        if (p.source.startsWith("http")) TextButton({ scope.launch { msg = Plugins.installFromUrl(p.source)?.let { "Update failed: $it" } ?: "Updated ${p.name}" } }) { Text("Update") }
                        TextButton({ Plugins.remove(p.id) }) { Text("Remove", color = Color(0xFFFF8A80)) }
                    }
                    testOut[p.id]?.let { Text(it, fontSize = 12.sp, color = Color(0xFFC9CDD6)) }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        SectionLabel("Log")
        SettingsCard {
            Column(Modifier.padding(16.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                if (logs.isEmpty()) Text("Plugin messages (log) appear here.", fontSize = 12.sp, color = Muted)
                logs.asReversed().forEach { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFC9CDD6)) }
            }
        }
    }
}
