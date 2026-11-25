package com.example.streamnest

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 0.9.3 (Feature.devRules): every rule that decides play, in one place.
 *
 * Each rule has a built-in default in code (= exactly what the app did before 0.9.3). On top of it, in this order:
 *   published  — Developer's Corner › Rules › Publish to everyone (Firebase xshared/devconfig/rules, both apps read it
 *                at start; kept on the phone, so a start never waits for the network)
 *   local test — "Try on this phone" (only ever set on the developer's phone; wins there)
 * With the switch off nothing is read or written and every rule is its default.
 * The Source Test's own changes (Quick Start addons, addon order) are noted as "auto" — shown and kept in the history,
 * but they never override a published or local value.
 */

enum class RuleKind { BOOL, INT, NUM, LIST }

/** One rule: [key] has no dots (Firebase keys can't have them). LIST values are one item per line. */
data class RuleDef(val key: String, val group: String, val label: String, val kind: RuleKind, val default: String, val help: String,
                   /** LIST picked from the installed addons / plugins / Nuvio plugins. */ val sources: Boolean = false)

data class RuleGroup(val id: String, val title: String, val sub: String)

@Serializable data class PublishedRules(val version: Int = 0, val at: Long = 0, val values: Map<String, String> = emptyMap())

/** A history line: a publish, a reset for everyone, or an automatic change by the Source Test. */
@Serializable data class RulesEvent(
    val id: Long = 0, val at: Long = 0, val kind: String = "publish", val what: String = "", val version: Int = 0,
    val before: Map<String, String> = emptyMap(), val after: Map<String, String> = emptyMap(),
)

@Serializable data class AutoNote(val value: String = "", val at: Long = 0)

@Serializable private data class LocalRules(
    val local: Map<String, String> = emptyMap(),
    val auto: Map<String, AutoNote> = emptyMap(),
    val autoHistory: List<RulesEvent> = emptyList(),
)

object Rules {
    // ───── Keys ─────
    const val QUICK_SOURCES = "quickSources"
    const val QUICK_WAIT_ADDONS = "quickWaitAddonsSec"
    const val QUICK_WAIT_PLUGINS = "quickWaitPluginsSec"
    const val BACKUP_SOURCES = "backupSources"
    const val BACKUP_RACE_MS = "backupRaceAfterMs"
    const val BACKUP_HEAD_MS = "backupQuickHeadStartMs"
    const val ADDON_ORDER = "addonOrder"
    const val RANK_PLAYED = "rankPlayedWeight"
    const val RANK_PER_SEC = "rankPerSecond"
    const val RANK_MAX_SEC = "rankMaxSeconds"
    const val RANK_UNSEEK = "rankUnseekablePenalty"
    const val RANK_LANG_FIRST = "rankLanguageFirst"
    const val CHECK_LENGTH = "checkLength"
    const val CHECK_MOVIE_MIN = "checkMovieMin"
    const val CHECK_MOVIE_MAX = "checkMovieMax"
    const val CHECK_SERIES_MIN = "checkSeriesMin"
    const val CHECK_SERIES_MAX = "checkSeriesMax"
    const val CHECK_FAKE_MB = "checkFakeUnderMb"
    const val CHECK_SWAP_MS = "checkSwapLengthMs"
    const val CHECK_SWAP_ASKED_MS = "checkSwapLengthAskedMs"
    const val SWITCH_PHONE = "autoSwitchPhone"
    const val SWITCH_TV = "autoSwitchTv"
    const val SWITCH_VEGA_WAIT = "autoSwitchWaitBestSec"
    const val SWITCH_TRIES = "autoSwitchTries"
    const val WEAK_STALLS = "weakStalls"
    const val WEAK_LONG_STALL = "weakLongStallMs"
    const val WEAK_UP_SMOOTH = "weakUpAfterSmoothSec"
    const val WEAK_AHEAD = "weakUpBufferedSec"
    const val WEAK_MIN_BW = "weakMinMbps"
    const val WEAK_COOLDOWN = "weakSlowCooldownSec"
    const val WEAK_STRUGGLE = "weakStruggleTicks"
    const val LINKMEM_HOURS = "linkMemoryHours"
    const val HDR_DEFAULT = "hdrDefault"
    const val TEST_QS_PLAYED = "testQuickMinPlayed"
    const val TEST_QS_MEDIAN = "testQuickMaxMedianMs"
    const val TEST_QS_REMOVE = "testQuickRemoveBelow"
    const val TEST_MIN_LINKS = "testMinLinks"
    const val LIGHT_PLUGINS = "lightPlugins"

    val groups = listOf(
        RuleGroup("quick", "Quick Play sources", "Which sources are asked first when a title opens, and how long before the backup"),
        RuleGroup("backup", "Backup", "What is asked when Quick Play is slow or empty, and when the player races it"),
        RuleGroup("addons", "Addon order", "The order of your addons (the Source Test can arrange it)"),
        RuleGroup("rank", "Source ranking", "How the Source Test's results turn into a score"),
        RuleGroup("checks", "Green-light checks", "Wrong-video checks: length, tiny files, same length for a smooth switch"),
        RuleGroup("switch", "Auto-switch", "The player climbing to a better copy by itself"),
        RuleGroup("weak", "Weak network", "When the player steps quality down and up again"),
        RuleGroup("memory", "Link memory & HDR", "How long found links are remembered; HDR on or off for new users"),
        RuleGroup("test", "Source Test arranging", "How the Source Test decides Quick Start"),
        RuleGroup("plugins", "Plugins on the phone", "Which plugins run on the phone every time (the rest only when nothing playable came)"),
    )

    val defs = listOf(
        // 0.9.7 (addonFirst): default = the Netflix-type plugins, then CNCVerse (only when a CNCVerse addon is on). Empty it here = as before.
        RuleDef(QUICK_SOURCES, "quick", "Quick Play asks only these (in order)", RuleKind.LIST, if (Feature.addonFirst) AddonFirst.QUICK.joinToString("\n") else "",
            "Empty = as before: the Quick Start picks (addon mode) or the ticked plugins + MovieBox / Netflix-type / VegaMovies (plugin mode).", sources = true),
        RuleDef(QUICK_WAIT_ADDONS, "quick", "Wait before backup — addons (s)", RuleKind.INT, "6", "No link that starts fast within this time → every source is asked (title page)."),
        RuleDef(QUICK_WAIT_PLUGINS, "quick", "Wait before backup — plugins (s)", RuleKind.INT, "20", "Plugin mode: nothing from Quick Play within this time → every plugin is asked."),
        RuleDef(BACKUP_SOURCES, "backup", "Backup asks only these (in order)", RuleKind.LIST, "",
            "Empty = every source is the backup. The order breaks ties between equally good links.", sources = true),
        RuleDef(BACKUP_RACE_MS, "backup", "Player: race backup links after (ms)", RuleKind.INT, "1200", "The player starts racing backup links this long after it opened."),
        RuleDef(BACKUP_HEAD_MS, "backup", "Player: Quick Play head start (ms)", RuleKind.INT, "3000", "Quick Play gets this long to answer with an instant playlist before a backup link may start."),
        RuleDef(ADDON_ORDER, "addons", "Addon order", RuleKind.LIST, "", "Empty = keep this phone's order. Addons not named keep their place after these.", sources = true),
        RuleDef(RANK_PLAYED, "rank", "Weight of played %", RuleKind.NUM, "100", "score = played share × this − seconds × per-second − not-seekable penalty."),
        RuleDef(RANK_PER_SEC, "rank", "Minus per second to start", RuleKind.NUM, "4", "Median time to the first picture, in seconds."),
        RuleDef(RANK_MAX_SEC, "rank", "Seconds counted at most", RuleKind.NUM, "20", "Slower starts count as this many seconds."),
        RuleDef(RANK_UNSEEK, "rank", "Penalty when you can't jump inside", RuleKind.NUM, "60", "Files without a seek index."),
        RuleDef(RANK_LANG_FIRST, "rank", "Your language before the score", RuleKind.BOOL, "true", "On = a link in your language always goes before a better-scored one in another language."),
        RuleDef(CHECK_LENGTH, "checks", "Length check", RuleKind.BOOL, "true", "A copy much shorter or longer than the title is skipped (wrong film / episode)."),
        RuleDef(CHECK_MOVIE_MIN, "checks", "Film: shortest allowed (× expected)", RuleKind.NUM, "0.75", ""),
        RuleDef(CHECK_MOVIE_MAX, "checks", "Film: longest allowed (× expected)", RuleKind.NUM, "1.35", ""),
        RuleDef(CHECK_SERIES_MIN, "checks", "Episode: shortest allowed (× expected)", RuleKind.NUM, "0.6", ""),
        RuleDef(CHECK_SERIES_MAX, "checks", "Episode: longest allowed (× expected)", RuleKind.NUM, "1.4", ""),
        RuleDef(CHECK_FAKE_MB, "checks", "A file under this is never the film (MB)", RuleKind.INT, "20", "Phones."),
        RuleDef(CHECK_SWAP_MS, "checks", "Smooth switch: same length within (ms)", RuleKind.INT, "1500", "Automatic switches fade only when both copies are this close in length."),
        RuleDef(CHECK_SWAP_ASKED_MS, "checks", "Upgrade you tapped: same length within (ms)", RuleKind.INT, "10000", ""),
        RuleDef(SWITCH_PHONE, "switch", "Auto-switch on phones", RuleKind.BOOL, "true", "Plugin mode: the player climbs to the best copy by itself (fade at the same frame)."),
        RuleDef(SWITCH_TV, "switch", "Auto-switch on TV", RuleKind.BOOL, "false", "Off on TV as before."),
        RuleDef(SWITCH_VEGA_WAIT, "switch", "Wait for the best copy (s)", RuleKind.INT, "25", "VegaMovies / RogMovies are waited for this long, then the highest copy that is there."),
        RuleDef(SWITCH_TRIES, "switch", "Tries per title", RuleKind.INT, "3", ""),
        RuleDef(WEAK_STALLS, "weak", "Step down after this many stalls", RuleKind.INT, "2", "Auto quality: buffering this often (in 90 s) → a lower quality."),
        RuleDef(WEAK_LONG_STALL, "weak", "…or one stall longer than (ms)", RuleKind.INT, "5000", ""),
        RuleDef(WEAK_UP_SMOOTH, "weak", "Step up after smooth for (s)", RuleKind.INT, "90", ""),
        RuleDef(WEAK_AHEAD, "weak", "…with this much loaded ahead (s)", RuleKind.INT, "45", ""),
        RuleDef(WEAK_MIN_BW, "weak", "No auto-switch below this speed (Mbps)", RuleKind.NUM, "8", "Phones: heavy upgrades wait while the measured speed is lower."),
        RuleDef(WEAK_COOLDOWN, "weak", "No auto-switch this long after a slow moment (s)", RuleKind.INT, "180", ""),
        RuleDef(WEAK_STRUGGLE, "weak", "Switch at once after this many buffering seconds in a minute", RuleKind.INT, "8", ""),
        RuleDef(LINKMEM_HOURS, "memory", "Link memory age (hours)", RuleKind.NUM, "4", "Links that started fast are remembered this long."),
        RuleDef(HDR_DEFAULT, "memory", "HDR on by default", RuleKind.BOOL, "false", "Only for people who never touched the HDR switch."),
        RuleDef(TEST_QS_PLAYED, "test", "Quick Start needs played share of at least", RuleKind.NUM, "0.7", ""),
        RuleDef(TEST_QS_MEDIAN, "test", "…and a median start of at most (ms)", RuleKind.INT, "2000", ""),
        RuleDef(TEST_QS_REMOVE, "test", "Removed from Quick Start below played share", RuleKind.NUM, "0.3", ""),
        RuleDef(TEST_MIN_LINKS, "test", "Links needed to decide", RuleKind.INT, "3", ""),
        // 0.9.4 (lightPlugins): the light plugins; every other plugin (VegaMovies, RogMovies, HDHub, UHD, 4KHDHub, Nuvio …)
        // runs once, quietly, only when nothing playable came within "Wait before backup — plugins".
        RuleDef(LIGHT_PLUGINS, "plugins", "Light plugins (always run on the phone)", RuleKind.LIST, "$MB_ANY\n$OTT_ANY",
            "Default: MovieBox (direct and plugins) and the Netflix / Prime / Hotstar-type (HLS) plugins. The others run only when nothing playable is ready after the plugin wait.", sources = true),
    )
    private val byKey = defs.associateBy { it.key }
    fun def(key: String) = byKey[key]
    fun inGroup(g: String) = defs.filter { it.group == g }

    private const val CLOUD = "xshared/devconfig/rules"
    private const val CLOUD_HISTORY = "xshared/devconfig/rulesHistory"
    private const val KEEP = 20

    private var file: File? = null
    private var pubFile: File? = null
    @Volatile private var lr = LocalRules()
    @Volatile var published = PublishedRules()
        private set
    @Volatile private var parsed: Map<String, Any> = parseAll(defs.associate { it.key to it.default })
    /** Bumped on every change (the Rules page redraws). */
    val changes = MutableStateFlow(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastOrder: List<String> = emptyList()

    fun init(ctx: Context) {
        if (file != null || !Feature.devRules) return
        file = File(ctx.filesDir, "rules-local.json")
        pubFile = File(ctx.filesDir, "rules-published.json")
        // Kept copies only (tiny files): the start never waits for the network.
        lr = runCatching { Api.json.decodeFromString<LocalRules>(file!!.readText()) }.getOrDefault(LocalRules())
        published = runCatching { Api.json.decodeFromString<PublishedRules>(pubFile!!.readText()) }.getOrDefault(PublishedRules())
        recompute()
        scope.launch { kotlinx.coroutines.delay(4_000); refresh() }
    }

    // ───── Reading (hot paths: one map lookup) ─────

    fun bool(key: String): Boolean = parsed[key] as? Boolean ?: (def(key)?.default == "true")
    fun int(key: String): Int = (parsed[key] as? Number)?.toInt() ?: def(key)?.default?.toIntOrNull() ?: 0
    fun num(key: String): Double = (parsed[key] as? Number)?.toDouble() ?: def(key)?.default?.toDoubleOrNull() ?: 0.0
    @Suppress("UNCHECKED_CAST") fun list(key: String): List<String> = parsed[key] as? List<String> ?: emptyList()
    fun ms(key: String): Long = int(key) * 1000L

    /** The value in use (text form). */
    fun value(key: String): String = if (!Feature.devRules) def(key)?.default.orEmpty() else lr.local[key] ?: published.values[key] ?: def(key)?.default.orEmpty()
    /** The value without this phone's test (published or default). */
    fun baseValue(key: String): String = published.values[key] ?: def(key)?.default.orEmpty()
    fun hasLocal(key: String) = key in lr.local
    /** A published or local value: the Source Test leaves these alone. */
    fun isSet(key: String) = Feature.devRules && (key in lr.local || key in published.values)

    /** Who set the value in use: default / test on this phone / published vN / auto by Source Test. */
    fun source(key: String): String = when {
        key in lr.local -> "test on this phone"
        key in published.values -> "published v${published.version} · ${fmt(published.at)}"
        lr.auto[key] != null -> "default · auto by Source Test ${fmt(lr.auto[key]!!.at)}"
        else -> "default"
    }
    fun autoNote(key: String): AutoNote? = lr.auto[key]

    private fun parse(d: RuleDef, v: String): Any = when (d.kind) {
        RuleKind.BOOL -> v.trim().equals("true", true)
        RuleKind.INT -> v.trim().toDoubleOrNull()?.toInt() ?: d.default.toInt()
        RuleKind.NUM -> v.trim().toDoubleOrNull() ?: d.default.toDouble()
        RuleKind.LIST -> v.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
    private fun parseAll(m: Map<String, String>): Map<String, Any> = defs.associate { d -> d.key to parse(d, m[d.key] ?: d.default) }

    private fun recompute() {
        val before = parsed
        parsed = parseAll(defs.associate { it.key to value(it.key) })
        changes.value = changes.value + 1
        // Ranking weights changed: the stored scores are worked out again.
        if (listOf(RANK_PLAYED, RANK_PER_SEC, RANK_MAX_SEC, RANK_UNSEEK).any { before[it] != parsed[it] }) runCatching { Ranker.rescore() }
        applyOrder()
    }

    /** A named addon order: put on this phone's addons (only when it is set and changed). */
    private fun applyOrder() {
        val order = list(ADDON_ORDER)
        if (order.isEmpty() || order == lastOrder) return
        val vm = Public.vm ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // Addons not loaded yet (very first moment of the start): tried again at the next change / refresh.
            if (vm.addons.value.isEmpty()) return@postDelayed
            lastOrder = order
            runCatching { if (vm.reorderAddonsByNames(order)) ActLog.add("RULES", "addon order from the rules: ${order.joinToString(" → ")}") }
        }, 1_500)
    }

    // ───── Quick Play / backup helpers ─────

    /** A source name matches a rule item (exact, or the MovieBox / Netflix-type / VegaMovies groups). */
    fun matches(name: String, item: String): Boolean = name == item ||
        (item == MB_ANY && name.contains("moviebox", true)) || (item == VEGA_ANY && isVegaName(name)) || (item == OTT_ANY && isOttName(name)) ||
        (item == CNC_ANY && AddonFirst.isCncName(name))   // 0.9.7
    fun quickSources(): List<String> = if (Feature.devRules) list(QUICK_SOURCES).let { if (Feature.addonFirst) AddonFirst.expand(Public.vm, it) else it } else emptyList()
    fun backupSources(): List<String> = if (Feature.devRules) list(BACKUP_SOURCES) else emptyList()
    /** Backup flows ask only these (null = every source, as before). */
    fun backupOnly(): Set<String>? = backupSources().takeIf { it.isNotEmpty() }?.toSet()
    /** Position in the list (0 when no list is set: no change to any order). */
    fun quickRank(name: String): Int { val l = quickSources(); if (l.isEmpty()) return 0; val i = l.indexOfFirst { matches(name, it) }; return if (i < 0) l.size else i }
    fun backupRank(name: String): Int { val l = backupSources(); if (l.isEmpty()) return if (Feature.addonFirst) AddonFirst.backupRank(name) else 0; val i = l.indexOfFirst { matches(name, it) }; return if (i < 0) l.size else i }
    /** Some rule names its own sources: streamsFlow may mix addons and plugins. */
    fun namesSources(): Boolean = quickSources().isNotEmpty() || backupSources().isNotEmpty()
    fun autoSwitchHere(): Boolean = bool(if (BuildConfig.IS_TV) SWITCH_TV else SWITCH_PHONE)
    fun weightsDefault(): Boolean = listOf(RANK_PLAYED, RANK_PER_SEC, RANK_MAX_SEC, RANK_UNSEEK).all { num(it) == def(it)!!.default.toDouble() }

    // ───── This phone ─────

    private fun saveLocal() { file?.let { f -> runCatching { f.writeText(Api.json.encodeToString(LocalRules.serializer(), lr)) } } }

    /** "Try on this phone": values equal to what would apply anyway are not kept as a test. */
    fun tryLocal(values: Map<String, String>) {
        if (!Feature.devRules) return
        val m = lr.local.toMutableMap()
        values.forEach { (k, v) -> if (norm(k, v) == norm(k, baseValue(k))) m.remove(k) else m[k] = norm(k, v) }
        lr = lr.copy(local = m); saveLocal(); recompute()
        ActLog.add("RULES", "try on this phone: ${values.keys.joinToString()}")
    }
    fun resetLocal(keys: Collection<String>) {
        lr = lr.copy(local = lr.local - keys.toSet()); saveLocal(); recompute()
        ActLog.add("RULES", "reset on this phone: ${keys.joinToString()}")
    }
    private fun norm(k: String, v: String): String = def(k)?.let { d -> if (d.kind == RuleKind.LIST) v.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n") else v.trim() } ?: v

    /** The Source Test changed something by itself: noted (and kept in the history), never over a set value. */
    fun noteAuto(key: String, before: String, after: String, what: String) {
        if (!Feature.devRules || before == after) return
        val now = System.currentTimeMillis()
        val ev = RulesEvent(now, now, "auto", what, published.version, mapOf(key to before), mapOf(key to after))
        lr = lr.copy(auto = lr.auto + (key to AutoNote(after, now)), autoHistory = (lr.autoHistory + ev).takeLast(KEEP))
        saveLocal(); changes.value = changes.value + 1
    }

    // ───── Everyone ─────

    private fun url(path: String, auth: String?) = "${CloudConfig.DB_URL}/$path.json" + (auth?.let { "?auth=$it" } ?: "")

    /** Reads the published copy (in the background at start; also from the Rules page). */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        if (!Feature.devRules) return@withContext false
        val txt = runCatching { Api.http.newCall(Request.Builder().url(url(CLOUD, null)).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } }.getOrNull()
            ?: return@withContext false
        val p = if (txt.trim() == "null") PublishedRules() else runCatching { Api.json.decodeFromString<PublishedRules>(txt) }.getOrNull() ?: return@withContext false
        if (p != published) {
            published = p
            runCatching { if (p.version == 0 && p.values.isEmpty()) pubFile?.delete() else pubFile?.writeText(Api.json.encodeToString(PublishedRules.serializer(), p)) }
            recompute()
            ActLog.add("RULES", if (p.values.isEmpty()) "no published rules (defaults)" else "published rules v${p.version} in use: ${p.values.keys.joinToString()}")
        } else applyOrder()
        true
    }

    /** Publish: [values] replace those keys in the published copy (a default value removes the key). null = OK. */
    suspend fun publish(values: Map<String, String>, what: String): String? = withContext(Dispatchers.IO) {
        if (!Feature.devRules) return@withContext "Rules are off in this app."
        val t = Cloud.token() ?: return@withContext "Sign in first."
        refresh()
        val before = published.values
        val m = before.toMutableMap()
        values.forEach { (k, v0) -> val v = norm(k, v0); if (v == norm(k, def(k)?.default.orEmpty())) m.remove(k) else m[k] = v }
        put(PublishedRules(published.version + 1, System.currentTimeMillis(), m), t, "publish", what, before, values.keys)
    }

    /** Reset for everyone: the published copy is deleted (every app back to its defaults). */
    suspend fun resetEveryone(): String? = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext "Sign in first."
        refresh()
        val before = published.values
        val err = runCatching {
            Api.http.newCall(Request.Builder().url(url(CLOUD, t)).delete().build()).execute().use { r -> if (r.isSuccessful) null else deny(r.code) }
        }.getOrElse { "Couldn't reach the cloud." }
        if (err != null) return@withContext err
        published = PublishedRules(); runCatching { pubFile?.delete() }; recompute()
        addHistory(RulesEvent(System.currentTimeMillis(), System.currentTimeMillis(), "reset", "Reset for everyone", 0, before, emptyMap()), t)
        ActLog.add("RULES", "reset for everyone")
        null
    }

    private suspend fun put(p: PublishedRules, t: String, kind: String, what: String, before: Map<String, String>, keys: Set<String>): String? {
        val err = runCatching {
            Api.http.newCall(Request.Builder().url(url(CLOUD, t)).put(Api.json.encodeToString(PublishedRules.serializer(), p).toRequestBody("application/json".toMediaType())).build())
                .execute().use { r -> if (r.isSuccessful) null else deny(r.code) }
        }.getOrElse { "Couldn't reach the cloud." }
        if (err != null) return err
        published = p
        runCatching { pubFile?.writeText(Api.json.encodeToString(PublishedRules.serializer(), p)) }
        // The published keys are no longer a test on this phone.
        lr = lr.copy(local = lr.local - keys); saveLocal()
        recompute()
        addHistory(RulesEvent(p.at, p.at, kind, what, p.version, before, p.values), t)
        ActLog.add("RULES", "published v${p.version}: $what")
        return null
    }

    private fun deny(code: Int) = if (code == 401 || code == 403) "Not allowed: the Firebase rule for xshared/devconfig and the developer's account." else "HTTP $code"

    private suspend fun cloudHistory(): List<RulesEvent> = runCatching {
        val txt = Api.http.newCall(Request.Builder().url(url(CLOUD_HISTORY, Cloud.token())).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        if (txt == null || txt.trim() == "null") emptyList() else Api.json.decodeFromString(ListSerializer(RulesEvent.serializer()), txt)
    }.getOrDefault(emptyList())

    private suspend fun addHistory(e: RulesEvent, t: String) {
        val l = (cloudHistory() + e).takeLast(KEEP)
        runCatching {
            Api.http.newCall(Request.Builder().url(url(CLOUD_HISTORY, t)).put(Api.json.encodeToString(ListSerializer(RulesEvent.serializer()), l).toRequestBody("application/json".toMediaType())).build()).execute().close()
        }
    }

    /** The last 20 publishes / resets (cloud) and this phone's automatic changes, newest first. */
    suspend fun history(): List<RulesEvent> = withContext(Dispatchers.IO) { (cloudHistory() + lr.autoHistory).sortedByDescending { it.at } }

    /** Undo: a publish or reset → the values before it are published again; an automatic change → the old setting back. */
    suspend fun undo(e: RulesEvent, vm: AppViewModel): String? = withContext(Dispatchers.IO) {
        if (e.kind == "auto") {
            val (k, v) = e.before.entries.firstOrNull() ?: return@withContext "Nothing to undo."
            withContext(Dispatchers.Main) {
                when (k) {
                    QUICK_SOURCES -> vm.setText("qsAddons", v.split("\n").filter { it.isNotBlank() }.joinToString("|"))
                    ADDON_ORDER -> { vm.reorderAddonsByNames(v.split("\n").filter { it.isNotBlank() }) }
                    else -> {}
                }
            }
            lr = lr.copy(auto = lr.auto - k); saveLocal(); changes.value = changes.value + 1
            ActLog.add("RULES", "undo of an automatic change: $k")
            return@withContext null
        }
        val t = Cloud.token() ?: return@withContext "Sign in first."
        refresh()
        put(PublishedRules(published.version + 1, System.currentTimeMillis(), e.before), t, "publish", "Undo of v${e.version} (${e.what})", published.values, (e.before.keys + e.after.keys))
    }

    fun fmt(at: Long): String = if (at <= 0) "—" else SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(at))

    /** Every rule and its value (for the AI and the logs). */
    fun describe(group: String? = null): String = defs.filter { group == null || it.group == group }.joinToString("\n") { d ->
        "${d.key} (${d.label}) = ${value(d.key).replace("\n", " | ").ifBlank { "(empty)" }} · default ${d.default.ifBlank { "(empty)" }} · set by ${source(d.key)}"
    }
}
