package com.example.streamnest

import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
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
 * StreamX: CloudStream plugins. Reads repositories (repo.json → plugin lists), downloads the .cs3 files you switch on,
 * and loads each in its own class loader on top of StreamX's own CloudStream library (com.lagradost.*, our code).
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
    /** One lock per plugin: plugins now load side by side (each on its own thread), not one after another. */
    private val loadLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private var prefs: android.content.SharedPreferences? = null
    private lateinit var appCtx: Context
    fun prefsOrNull(): android.content.SharedPreferences? = prefs

    fun init(ctx: Context) {
        if (prefs != null) return
        appCtx = ctx.applicationContext
        CloudStreamApp.context = appCtx
        // Plugins' requests: StreamX's network, with a hard 25 s limit per request (one hung request stalled a whole test).
        // No HTTP cache: plugins read their current site addresses from small JSON files (an old cached copy sent
        // VegaMovies to a dead domain).
        // TV 0.9.14: file-site pages cut by the internet provider are asked again through StreamX's Cloudflare relay.
        com.lagradost.cloudstream3.app.baseClient = Api.http.newBuilder().cache(null).callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            // 0.9.1 (pluginGuard): PluginGuard sits in front of the relay and sees the final answer: no plugin reads a whole
            // video file into memory (the big-file crash). Off = exactly as before.
            .apply { interceptors().add(0, PageRelay); if (Feature.pluginGuard) interceptors().add(0, PluginGuard) }.build() // phone too since 0.9.78
        val p = ctx.getSharedPreferences("cs_plugins", Context.MODE_PRIVATE); prefs = p
        repos.value = runCatching { Api.json.decodeFromString<List<CsRepo>>(p.getString("repos", null) ?: "") }.getOrNull() ?: defaultRepos.map { CsRepo(it) }
        catalog.value = runCatching { Api.json.decodeFromString<List<CsPluginEntry>>(p.getString("catalog", null) ?: "") }.getOrDefault(emptyList())
        enabled.value = p.getStringSet("enabled", null)?.toSet() ?: emptySet()
        installed.value = runCatching { Api.json.decodeFromString<Map<String, Int>>(p.getString("installed", null) ?: "") }.getOrDefault(emptyMap())
        // 0.5.4: AllForU (AllOtt: Netflix / Prime Video / Hotstar, and its MovieBox) and CNCVerse join every phone once.
        if (!p.getBoolean("seeded054", false) || !p.getBoolean("seeded056", false)) {
            repos.value = repos.value + listOf(CNC, ALLFORU).filter { u -> repos.value.none { it.url == u } }.map { CsRepo(it) }
            p.edit().putString("repos", Api.json.encodeToString(repos.value)).apply()
            // The plugin lists are read again once, so AllOtt / MovieBox (AllForU) and CNCVerse's VegaMovies get switched on
            // (it only happened when the list was empty, so phones that had plugins already never got them).
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch { runCatching { refresh() } }
        }
        // TV 0.9.14: UHDMovies and HDHub4u (phisher98's repository) switched on once on TV.
        if (BuildConfig.IS_TV && !p.getBoolean("seededTv0914", false) && catalog.value.any { it.internalName == "HDhub4u" || it.internalName.startsWith("UHDmovies") }) {
            val add = catalog.value.filter { it.repo.contains("phisher98") && (it.internalName == "HDhub4u" || it.internalName.startsWith("UHDmovies")) }.map { it.key }
            enabled.value = enabled.value + add
            p.edit().putBoolean("seededTv0914", true).putStringSet("enabled", enabled.value).apply()
            ActLog.add("PLUGIN", "TV: UHDMovies and HDHub4u switched on (${add.size})")
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
        val failedRepos = HashSet<String>()
        for (r in repos.value) {
            val o = runCatching { Api.json.parseToJsonElement(Api.get(r.url)) as JsonObject }.getOrNull()
            if (o == null) { errors += "can't read ${r.url.substringAfter("//").take(50)}"; named += r; failedRepos += r.url; continue }
            fun str(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
            named += r.copy(name = str("name") ?: r.name, icon = str("iconUrl"))
            val lists = (o["pluginLists"] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
            for (l in lists) {
                val arr = runCatching { Api.json.parseToJsonElement(Api.get(l)) as JsonArray }.getOrNull() ?: run { errors += "can't read a plugin list"; failedRepos += r.url; null } ?: continue
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
            // A repository that couldn't be read keeps its plugins from last time (they used to vanish from the list).
            val kept = catalog.value.filter { it.repo in failedRepos && out.none { o -> o.key == it.key } }
            catalog.value = out + kept
            prefs?.edit()?.putLong("refreshedAt", System.currentTimeMillis())?.apply()
            if (kept.isNotEmpty()) ActLog.add("PLUGIN", "kept ${kept.size} plugins from repositories that didn't answer")
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
            // 0.5.6: VegaMovies only from CNCVerse (the Magix copy points at a dead site) — enforced once more.
            if (prefs?.getBoolean("seeded056", false) != true && out.any { it.repo == CNC && it.internalName.contains("vega", true) }) {
                val cncVega = out.filter { it.repo == CNC && it.internalName.contains("vega", true) }.map { it.key }
                val otherVega = out.filter { it.repo != CNC && it.internalName.contains("vega", true) }.map { it.key }.toSet()
                enabled.value = enabled.value - otherVega + cncVega
                loaded.keys.filter { it in otherVega }.forEach { loaded.remove(it) }
                prefs?.edit()?.putBoolean("seeded056", true)?.apply()
            }
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
    fun setEnabledSet(keys: Set<String>, why: String = "") {
        logChange(enabled.value, keys, why); enabled.value = keys; save(); loaded.keys.filter { it !in keys }.forEach { loaded.remove(it) }
        prefs?.edit()?.putStringSet("enabledBackup", keys)?.apply()
    }

    fun setEnabled(key: String, on: Boolean) {
        if (on && catalog.value.firstOrNull { it.key == key }?.let { foreignVega(it) } == true) { ActLog.add("PLUGIN", "VegaMovies/RogMovies from another repository can't be switched on (CNCVerse's only)"); return }
        val n = if (on) enabled.value + key else enabled.value - key
        logChange(enabled.value, n, "you"); enabled.value = n; save(); if (!on) loaded.remove(key)
        prefs?.edit()?.putStringSet("enabledBackup", n)?.apply()
    }

    /** Every change to the switched-on plugins goes in the log, with who made it. */
    private fun logChange(old: Set<String>, new: Set<String>, why: String) {
        val off = old - new; val on = new - old
        if (off.isEmpty() && on.isEmpty()) return
        fun n(k: String) = catalog.value.firstOrNull { it.key == k }?.name ?: k.substringAfterLast('|')
        ActLog.add("PLUGIN", "plugins changed (${why.ifBlank { "app" }})" + (if (on.isNotEmpty()) " · on: " + on.joinToString { n(it) } else "") + (if (off.isNotEmpty()) " · off: " + off.joinToString { n(it) } else ""))
    }

    /**
     * Self-repair: if the switched-on set shrank without you doing it (only one or two left while the last set you
     * chose had more), the last set is put back. Plugins also update themselves every 6 hours.
     */
    suspend fun upkeep() {
        val p = prefs ?: return
        val backup = p.getStringSet("enabledBackup", null)?.toSet()
        if (backup == null) p.edit().putStringSet("enabledBackup", enabled.value).apply()
        else if (backup.size >= 3 && enabled.value.size < backup.size && (backup - enabled.value).size >= 2) {
            withContext(Dispatchers.Main) {
                ActLog.add("PLUGIN", "switched-on plugins dropped from ${backup.size} to ${enabled.value.size} — putting them back")
                enabled.value = enabled.value + backup; save()
            }
        }
        val stale = System.currentTimeMillis() - p.getLong("refreshedAt", 0L) > 6 * 3600_000L
        if (catalog.value.isEmpty() || stale) runCatching { refresh() }.getOrNull()?.let { ActLog.add("PLUGIN", "update check: $it") }
    }

    /** The plugin (catalog key) a provider came from. */
    fun keyOf(api: com.lagradost.cloudstream3.MainAPI): String? = loaded.entries.firstOrNull { (_, r) -> r.apis.any { it === api } }?.key

    /** Loads a plugin again from scratch: a fresh copy reads its site's current address again. */
    suspend fun reload(key: String): CsBridge.Registered? {
        val e = catalog.value.firstOrNull { it.key == key } ?: return null
        loaded.remove(key)
        return runCatching { ensureLoaded(e) }.getOrNull()
    }

    private fun fileFor(e: CsPluginEntry): File {
        val dir = File(appCtx.filesDir, "cs/" + Integer.toHexString(e.repo.hashCode())).apply { mkdirs() }
        return File(dir, e.internalName.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".cs3")
    }

    /** Downloads (when missing or outdated) and loads one plugin. Returns its providers, or throws with the reason. */
    suspend fun ensureLoaded(e: CsPluginEntry): CsBridge.Registered = loadLocks.getOrPut(e.key) { Mutex() }.withLock {
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
        val reg = loadOffMain(e, f)
        loaded[e.key] = reg
        setStatus(e.key, readyText(reg))
        // Which site address each provider ended up with (a dead address is the usual reason a plugin finds nothing).
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            reg.apis.forEach { a -> runCatching { kotlinx.coroutines.withTimeoutOrNull(20_000) { a.mainUrl } }.getOrNull()?.let { ActLog.add("CSLOAD", "${e.name} v${e.version} · ${a.name} → $it") } }
            // VegaMovies / RogMovies: the address they start with is checked, and swapped for one that answers.
            reg.apis.filter { SiteAddress.handles(it) }.forEach { a -> runCatching { SiteAddress.fix(a, force = false) } }
        }
        reg
    }

    private fun readyText(reg: CsBridge.Registered) =
        "Ready · " + reg.apis.joinToString { it.name }.ifEmpty { "no providers" } + (if (reg.extractors.isNotEmpty()) " · ${reg.extractors.size} link readers" else "")

    /**
     * Runs a plugin's load() on its own thread (with a Looper, for plugins that make a Handler), never on the main one:
     * some plugins read their site address from the internet while loading (UHDmovies, 4KHDHub), which froze the app
     * ("not responding"). Gives up after 12 s so one slow site can't hold up the rest; if it finishes later it is
     * still kept. A plugin that really needs the main thread (it touches the screen while loading) gets one try there.
     */
    private suspend fun loadOffMain(e: CsPluginEntry, f: File): CsBridge.Registered {
        val done = kotlinx.coroutines.CompletableDeferred<CsBridge.Registered>()
        val t0 = System.currentTimeMillis()
        val ht = android.os.HandlerThread("cs-load-" + e.internalName.take(20)).apply { start() }
        android.os.Handler(ht.looper).post {
            try {
                val r = loadFile(e, f)
                if (!done.complete(r)) {
                    // Finished after we gave up on it: keep it for next time.
                    loaded[e.key] = r; setStatus(e.key, readyText(r) + " (slow start)")
                    ActLog.add("CSLOAD", "${e.name} finished late in ${System.currentTimeMillis() - t0} ms")
                }
            } catch (t: Throwable) { done.completeExceptionally(t) }
            finally { ht.quitSafely() }
        }
        // TV 0.8.7: 4K HDHUB reads its site address while loading and often needs more than 12 s on a 1 GB stick — it gets 35 s
        // (it's started in the background on Home, so it is usually ready long before you press play).
        val slowOk = BuildConfig.IS_TV && (e.internalName.contains("4khd", true) || e.name.contains("4khd", true))
        val limit = if (slowOk) 35_000L else 12_000L
        val res = kotlinx.coroutines.withTimeoutOrNull(limit) { runCatching { done.await() } }
        if (res == null) {
            ActLog.add("CSLOAD", "${e.name} took over ${limit / 1000} s to load: skipped for now")
            setStatus(e.key, "Slow to start (its site is slow): skipped for now")
            error("${e.name} took too long to start")
        }
        res.exceptionOrNull()?.let { err ->
            val needsMain = err.javaClass.simpleName.contains("WrongThread") || (err.message ?: "").contains("thread", true) || (err.message ?: "").contains("Looper", true)
            if (!needsMain) throw err
            ActLog.add("CSLOAD", "${e.name} needs the main thread: ${err.message}")
            return withContext(Dispatchers.Main) { loadFile(e, f) }
        }
        ActLog.add("CSLOAD", "${e.name} loaded in ${System.currentTimeMillis() - t0} ms")
        return res.getOrThrow()
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

    /** TV 0.8.7: starts the slow-to-load sources (4K HDHUB) in the background, so they're ready when you press play. */
    suspend fun tvPrewarm(ctx: Context) {
        if (!BuildConfig.IS_TV) return
        runCatching { init(ctx) }
        enabledEntries().filter { it.internalName.contains("4khd", true) || it.name.contains("4khd", true) }.forEach { e ->
            runCatching { ensureLoaded(e) }.onFailure { ActLog.add("CSLOAD", "${e.name}: background start failed — ${it.message}".take(160)) }
        }
    }

    fun entry(key: String): CsPluginEntry? = catalog.value.firstOrNull { it.key == key }
    fun enabledEntries(): List<CsPluginEntry> = catalog.value.filter { it.key in enabled.value && !foreignVega(it) && !tvLeanSkip(it) }
    /**
     * TV 0.8.5 — a 1 GB Fire TV stick runs each service once: CNC Verse (Netflix / Prime / Hotstar), MovieBox + MovieBoxIN,
     * VegaMovies / RogMovies, 4K HDHUB, M Player. Copies of the same services (CNC Verse Mobile, AllOtt, the older MovieBox
     * plugins) and the slow extra file sites are left out there — they doubled the searches and filled its memory.
     */
    fun tvLeanSkip(e: CsPluginEntry): Boolean {
        if (!BuildConfig.IS_TV || !::appCtx.isInitialized || !TvSmall.on(appCtx)) return false
        val n = e.name.lowercase(); val i = e.internalName.lowercase()
        // TV 0.9.14: HDHub4u and UHDMovies run again (asked for better-quality files; the Upgrade button offers them).
        return n == "cnc verse mobile" || n.startsWith("allott") || i == "movieboxprovider"
    }
    /** VegaMovies / RogMovies only from CNCVerse: copies from any other repository never run (they point at dead sites). */
    fun foreignVega(e: CsPluginEntry): Boolean = e.repo != CNC && (e.internalName.contains("vega", true) || e.internalName.contains("rogmovies", true) || e.name.contains("vega", true))

    /** "Keep warm": loads the switched-on plugins and asks each provider one small search, so the first real use is fast. */
    suspend fun warmAll(log: (String) -> Unit = { ActLog.add("CSWARM", it) }) = withContext(Dispatchers.IO) {
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
        providers().filter { (_, api) -> "CloudStream · ${api.name}" in names || (MB_ANY in names && api.name.contains("moviebox", true)) || (VEGA_ANY in names && isVegaName(api.name)) || (OTT_ANY in names && isOttName(api.name)) }.forEach { (_, api) ->
            launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { kotlinx.coroutines.withTimeoutOrNull(15_000) { api.search("a", 1) ?: api.search("a") } } }
        }
    }

    fun providers(): List<Pair<CsPluginEntry, MainAPI>> = enabledEntries().flatMap { e -> loaded[e.key]?.apis?.map { e to it }.orEmpty() }
}

/**
 * 0.8.20: plugin-only size guard. A plugin extractor (HubCloud's t3dl.php) got the real 1.75 GB video back and read it
 * into a String: out of memory. Before any body is read: a ranged read passes (player style); a video / audio type, a
 * binary type that is big or of unknown size, or anything over 16 MB is cut off (empty body, same code and headers,
 * plus X-SX-Cut: 1 — the plugin still gets the final address). A text / JSON answer with no length is capped at 12 MB.
 * 0.9.1: on the shared code behind Feature.pluginGuard (added to the plugin client only when on).
 */
object PluginGuard : okhttp3.Interceptor {
    private const val MB = 1024L * 1024
    private const val CHUNKED_MAX = 12L * MB
    private val binaryTypes = setOf("application/octet-stream", "binary/octet-stream", "application/x-matroska", "application/mp4")

    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val req = chain.request()
        val r = chain.proceed(req)
        // A ranged read is a player-style read: fine. HEAD has no body to read.
        if (req.header("Range") != null || req.method == "HEAD") return r
        val body = r.body ?: return r
        val mt = body.contentType()
        val type = if (mt != null) "${mt.type}/${mt.subtype}".lowercase() else ""
        val len = body.contentLength()          // -1 = unknown
        val cut = type.startsWith("video/") || type.startsWith("audio/") ||
            (type in binaryTypes && (len < 0 || len > 8 * MB)) ||
            len > 16 * MB
        val u = r.request.url
        val where = u.host + u.encodedPath.take(40)
        if (cut) {
            val size = if (len < 0) "unknown" else if (len >= MB) "${len / MB} MB" else "${len / 1024} KB"
            ActLog.add("NET", "plugin download blocked · $where · ${type.ifEmpty { "no type" }} · $size — a plugin tried to load a whole video file into memory")
            body.close()
            // Empty body, same code and headers; response.request.url stays as it was (extractors often only read .url).
            return r.newBuilder().body("".toResponseBody(mt)).header("X-SX-Cut", "1").build()
        }
        // Text / JSON with no length (chunked): reading past 12 MB throws an IOException the plugin's own try/catch handles.
        val textual = type.isEmpty() || type.startsWith("text/") || "json" in type || "xml" in type || "javascript" in type
        if (len < 0 && textual) {
            val limited = object : okio.ForwardingSource(body.source()) {
                var n = 0L
                var logged = false
                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    val got = super.read(sink, byteCount)
                    if (got > 0) {
                        n += got
                        if (n > CHUNKED_MAX) {
                            if (!logged) { logged = true; ActLog.add("NET", "plugin answer over 12 MB, no length given · $where — stopped reading") }
                            runCatching { close() }
                            throw java.io.IOException("response too large for a plugin")
                        }
                    }
                    return got
                }
            }
            val src = limited.buffer()
            val nb = object : okhttp3.ResponseBody() {
                override fun contentType() = mt
                override fun contentLength() = -1L
                override fun source(): okio.BufferedSource = src
            }
            return r.newBuilder().body(nb).build()
        }
        return r
    }
}
