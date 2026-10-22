package com.example.streamnest

import kotlinx.coroutines.launch
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CsBridge
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.util.zip.ZipFile

@Serializable data class CsRepo(val url: String, val name: String = "", val icon: String? = null)

@Serializable data class CsPluginEntry(
    val repo: String, val name: String, val internalName: String, val url: String, val version: Int = 0, val status: Int = 1,
    val language: String? = null, val tvTypes: List<String> = emptyList(), val iconUrl: String? = null, val description: String? = null,
    val fileSize: Long? = null,
) { val key: String get() = "$repo|$internalName" }

/**
 * Xstream: CloudStream plugins. Reads repositories (repo.json → plugin lists), downloads the .cs3 files you switch on,
 * and loads each in its own class loader on top of Xstream's own CloudStream library (com.lagradost.*, our code).
 * For now they're used only by Source Test › CloudStream — not by Quick Play.
 */
object CsPlugins {
    /** The repositories you gave, with the plugins you picked switched on at first. */
    val defaultRepos = listOf(
        "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json",
        "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json",
        "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json",
        ALLFORU,
    )
    const val ALLFORU = "https://raw.githubusercontent.com/RVRBEAST76/allforu-repo/builds/repo.json"
    private const val CNC = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json"
    private val defaultOn = setOf("FourKHDHub", "HDhub4u", "MPlayerProvider", "UHDmoviesProvider", "VegaMovies", "CNC Verse", "CNC Verse Mobile",
        "MovieBoxProvider", "MovieBoxProviderIN")

    val repos = MutableStateFlow<List<CsRepo>>(emptyList())
    val catalog = MutableStateFlow<List<CsPluginEntry>>(emptyList())
    val enabled = MutableStateFlow<Set<String>>(emptySet())
    /** key → installed version. */
    val installed = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** key → what's happening ("Downloading…", "Ready · 2 providers", "Error: …"). */
    val status = MutableStateFlow<Map<String, String>>(emptyMap())
    val loaded = java.util.concurrent.ConcurrentHashMap<String, CsBridge.Registered>()
    private val loadLock = Mutex()
    private var prefs: android.content.SharedPreferences? = null
    private lateinit var appCtx: Context

    fun init(ctx: Context) {
        if (prefs != null) return
        appCtx = ctx.applicationContext
        CloudStreamApp.context = appCtx
        // Plugins' requests: Xstream's network, with a hard 25 s limit per request (one hung request stalled a whole test).
        // No HTTP cache: plugins read their current site addresses from small JSON files (an old cached copy sent
        // VegaMovies to a dead domain).
        com.lagradost.cloudstream3.app.baseClient = Api.http.newBuilder().cache(null).callTimeout(25, java.util.concurrent.TimeUnit.SECONDS).build()
        val p = ctx.getSharedPreferences("cs_plugins", Context.MODE_PRIVATE); prefs = p
        repos.value = runCatching { Api.json.decodeFromString<List<CsRepo>>(p.getString("repos", null) ?: "") }.getOrNull() ?: defaultRepos.map { CsRepo(it) }
        catalog.value = runCatching { Api.json.decodeFromString<List<CsPluginEntry>>(p.getString("catalog", null) ?: "") }.getOrDefault(emptyList())
        enabled.value = p.getStringSet("enabled", null)?.toSet() ?: emptySet()
        installed.value = runCatching { Api.json.decodeFromString<Map<String, Int>>(p.getString("installed", null) ?: "") }.getOrDefault(emptyMap())
        // 0.5.4: AllForU (AllOtt: Netflix / Prime Video / Hotstar, and its MovieBox) and CNCVerse join every phone once.
        if (!p.getBoolean("seeded054", false)) {
            repos.value = repos.value + listOf(CNC, ALLFORU).filter { u -> repos.value.none { it.url == u } }.map { CsRepo(it) }
            p.edit().putString("repos", Api.json.encodeToString(repos.value)).apply()
        }
        if (!p.getBoolean("seededMovieBox", false) && catalog.value.any { it.internalName.startsWith("MovieBoxProvider") }) {
            enabled.value = enabled.value + catalog.value.filter { it.internalName.startsWith("MovieBoxProvider") }.map { it.key }
            p.edit().putBoolean("seededMovieBox", true).putStringSet("enabled", enabled.value).apply()
        }
    }
    private fun save() {
        prefs?.edit()?.putString("repos", Api.json.encodeToString(repos.value))?.putString("catalog", Api.json.encodeToString(catalog.value))
            ?.putStringSet("enabled", enabled.value)?.putString("installed", Api.json.encodeToString(installed.value))?.apply()
    }
    private fun setStatus(key: String, s: String) { status.value = status.value + (key to s) }

    fun addRepo(url: String) { val u = url.trim(); if (u.startsWith("http") && repos.value.none { it.url == u }) { repos.value = repos.value + CsRepo(u); save() } }
    fun removeRepo(url: String) { repos.value = repos.value.filter { it.url != url }; catalog.value = catalog.value.filter { it.repo != url }; save() }

    /** Reads every repository and its plugin lists. Null = all fine, else what failed. */
    suspend fun refresh(): String? = withContext(Dispatchers.IO) {
        val out = ArrayList<CsPluginEntry>(); val errors = ArrayList<String>()
        val named = ArrayList<CsRepo>()
        for (r in repos.value) {
            val o = runCatching { Api.json.parseToJsonElement(Api.get(r.url)) as JsonObject }.getOrNull()
            if (o == null) { errors += "can't read ${r.url.substringAfter("//").take(50)}"; named += r; continue }
            fun str(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
            named += r.copy(name = str("name") ?: r.name, icon = str("iconUrl"))
            val lists = (o["pluginLists"] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
            for (l in lists) {
                val arr = runCatching { Api.json.parseToJsonElement(Api.get(l)) as JsonArray }.getOrNull() ?: run { errors += "can't read a plugin list"; null } ?: continue
                arr.mapNotNull { it as? JsonObject }.forEach { p ->
                    fun s(k: String) = runCatching { p[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    val internal = s("internalName") ?: s("name") ?: return@forEach
                    out += CsPluginEntry(r.url, s("name") ?: internal, internal, s("url") ?: return@forEach,
                        runCatching { p["version"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0, runCatching { p["status"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 1,
                        s("language"), (p["tvTypes"] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty(),
                        s("iconUrl"), s("description"), runCatching { p["fileSize"]?.jsonPrimitive?.longOrNull }.getOrNull())
                }
            }
        }
        withContext(Dispatchers.Main) {
            repos.value = named
            catalog.value = out
            // First time: the plugins you chose are switched on.
            if (prefs?.getBoolean("seeded", false) != true) {
                enabled.value = enabled.value + out.filter { it.internalName in defaultOn }.map { it.key }
                prefs?.edit()?.putBoolean("seeded", true)?.apply()
            }
            // 0.4.7: MovieBox plugins (every repository that has one) switched on once — they were easy to miss.
            if (prefs?.getBoolean("seededMovieBox", false) != true && out.any { it.internalName.startsWith("MovieBoxProvider") }) {
                enabled.value = enabled.value + out.filter { it.internalName.startsWith("MovieBoxProvider") }.map { it.key }
                prefs?.edit()?.putBoolean("seededMovieBox", true)?.apply()
            }
            // 0.5.4: AllOtt + MovieBox from AllForU on; VegaMovies only from CNCVerse (the other copies point at a dead site).
            if (prefs?.getBoolean("seeded054", false) != true && out.any { it.repo == ALLFORU }) {
                val on = out.filter { (it.repo == ALLFORU && (it.internalName.contains("allott", true) || it.internalName.contains("moviebox", true))) ||
                    (it.repo == CNC && it.internalName.contains("vega", true)) }.map { it.key }
                val off = out.filter { it.repo != CNC && it.internalName.contains("vega", true) }.map { it.key }
                enabled.value = enabled.value - off.toSet() + on
                loaded.keys.filter { it in off }.forEach { loaded.remove(it) }
                prefs?.edit()?.putBoolean("seeded054", true)?.apply()
            }
            save()
        }
        errors.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** The developer's published plugin set: exactly these switched on (Official.apply). */
    fun setEnabledSet(keys: Set<String>) { enabled.value = keys; save(); loaded.keys.filter { it !in keys }.forEach { loaded.remove(it) } }

    fun setEnabled(key: String, on: Boolean) { enabled.value = if (on) enabled.value + key else enabled.value - key; save(); if (!on) loaded.remove(key) }

    private fun fileFor(e: CsPluginEntry): File {
        val dir = File(appCtx.filesDir, "cs/" + Integer.toHexString(e.repo.hashCode())).apply { mkdirs() }
        return File(dir, e.internalName.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".cs3")
    }

    /** Downloads (when missing or outdated) and loads one plugin. Returns its providers, or throws with the reason. */
    suspend fun ensureLoaded(e: CsPluginEntry): CsBridge.Registered = loadLock.withLock {
        loaded[e.key]?.let { if ((installed.value[e.key] ?: -1) == e.version) return it }
        val f = fileFor(e)
        if (!f.exists() || (installed.value[e.key] ?: -1) != e.version) {
            setStatus(e.key, "Downloading…")
            withContext(Dispatchers.IO) {
                val bytes = Api.http.newCall(okhttp3.Request.Builder().url(e.url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("download HTTP ${r.code}"); r.body?.bytes() ?: error("empty download")
                }
                if (f.exists()) { f.setWritable(true); f.delete() }
                f.writeBytes(bytes)
                // Android 14+ only loads code from files nobody can change.
                f.setReadOnly()
            }
            installed.value = installed.value + (e.key to e.version); save()
        }
        setStatus(e.key, "Loading…")
        val reg = withContext(Dispatchers.Main) { loadFile(e, f) }
        loaded[e.key] = reg
        setStatus(e.key, "Ready · " + reg.apis.joinToString { it.name }.ifEmpty { "no providers" } + (if (reg.extractors.isNotEmpty()) " · ${reg.extractors.size} link readers" else ""))
        reg
    }

    private fun loadFile(e: CsPluginEntry, f: File): CsBridge.Registered {
        val manifest = ZipFile(f).use { z -> z.getEntry("manifest.json")?.let { en -> z.getInputStream(en).bufferedReader().readText() } } ?: error("no manifest.json")
        val mo = Api.json.parseToJsonElement(manifest) as JsonObject
        val className = mo["pluginClassName"]?.jsonPrimitive?.contentOrNull ?: error("no pluginClassName")
        val needsRes = mo["requiresResources"]?.jsonPrimitive?.contentOrNull == "true"
        val loader = DexClassLoader(f.absolutePath, appCtx.codeCacheDir.absolutePath, null, CsPlugins::class.java.classLoader)
        val inst = loader.loadClass(className).getDeclaredConstructor().newInstance() as BasePlugin
        inst.__filename = f.absolutePath
        if (needsRes && inst is Plugin) inst.resources = runCatching {
            val am = AssetManager::class.java.getDeclaredConstructor().newInstance()
            AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(am, f.absolutePath)
            @Suppress("DEPRECATION") Resources(am, appCtx.resources.displayMetrics, appCtx.resources.configuration)
        }.getOrNull()
        val reg = CsBridge.Registered(inst, e.name)
        CsBridge.loading = reg
        // Plugins get the app's screen (CNC Verse casts it to AppCompatActivity), like in CloudStream.
        val loadCtx: Context = com.lagradost.cloudstream3.CommonActivity.activity?.takeIf { !it.isFinishing } ?: appCtx
        try { if (inst is Plugin) inst.load(loadCtx) else inst.load() } finally { CsBridge.loading = null }
        return reg
    }

    fun entry(key: String): CsPluginEntry? = catalog.value.firstOrNull { it.key == key }
    fun enabledEntries(): List<CsPluginEntry> = catalog.value.filter { it.key in enabled.value }

    /** "Keep warm": loads the switched-on plugins and asks each provider one small search, so the first real use is fast. */
    suspend fun warmAll(log: (String) -> Unit = { ActLog.add("CSWARM", it) }) {
        for (e in enabledEntries()) {
            val t0 = System.currentTimeMillis()
            val got = runCatching { ensureLoaded(e) }
            val reg = got.getOrNull()
            if (reg == null) { log("${e.name}: ${got.exceptionOrNull()?.message}"); continue }
            reg.apis.forEach { api ->
                val t1 = System.currentTimeMillis()
                val n = runCatching { kotlinx.coroutines.withTimeoutOrNull(20_000) { api.search("the", 1)?.items?.size ?: api.search("the")?.size } }.getOrNull()
                log("${api.name}: loaded in ${t1 - t0} ms, warm search ${n ?: "-"} results in ${System.currentTimeMillis() - t1} ms")
            }
        }
    }

    /**
     * Keeps only the given providers warm (Quick Play's: Netflix, Prime Video, Hotstar…): one tiny search each, all at
     * once, so their sites keep the connection and the sign-in cookie alive — the next title starts like in CloudStream.
     */
    suspend fun warmSome(names: Set<String>) = kotlinx.coroutines.coroutineScope {
        providers().filter { (_, api) -> "CloudStream · ${api.name}" in names || (MB_ANY in names && api.name.contains("moviebox", true)) || (VEGA_ANY in names && api.name.contains("vega", true)) || (OTT_ANY in names && isOttName(api.name)) }.forEach { (_, api) ->
            launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { kotlinx.coroutines.withTimeoutOrNull(15_000) { api.search("a", 1) ?: api.search("a") } } }
        }
    }

    fun providers(): List<Pair<CsPluginEntry, MainAPI>> = enabledEntries().flatMap { e -> loaded[e.key]?.apis?.map { e to it }.orEmpty() }
}
