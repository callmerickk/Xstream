package com.example.streamnest

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/** MovieBox MP4 that goes through PenguPlay's own server (pengu.uk/direct): plays, but starts late and counts against
 *  PenguPlay's request limit — used only when nothing else can play. */
fun StreamItem.slowMp4(): Boolean = stream.url?.contains("pengu.uk/direct", ignoreCase = true) == true

fun hostOf(url: String?): String = runCatching { java.net.URI(url ?: "").host?.lowercase() }.getOrNull()
    ?.split('.')?.takeLast(2)?.joinToString(".") ?: (url ?: "")

/** HD upgrade, background saving, remembered HD file, next-episode HD: off only when Elite's "Only on Wi-Fi" is on and
 *  the phone is on mobile data. */
fun AppViewModel.upgradeAllowed(): Boolean =
    streamMode() != "elite" || !isOn(texts.value["eliteWifiStrict"] ?: "no") || isOnWifi(appContext)

/**
 * An addon's name in Xstream: the name you gave it (Settings › Addons › pencil, kept per addon link), else its own
 * name; two addons with the same own name are numbered ("AIOStreams Stable 2") so each stays separate. This name is
 * what links, Quick Start, rankings and roles (Pengu → quick server, HdHub → HD step…) go by.
 */
fun Addon.label(): String {
    Public.vm?.texts?.value?.get("addonLabel:$baseUrl")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val same = Public.vm?.addons?.value.orEmpty().filter { it.manifest.name == manifest.name }
    val i = same.indexOfFirst { it.baseUrl == baseUrl }
    return if (same.size > 1 && i > 0) "${manifest.name} ${i + 1}" else manifest.name
}

/** Names are already the addon's own label (kept for older callers). */
fun addonLabel(name: String?): String = name?.let { if (it.startsWith("CloudStream · ") || it.startsWith("Nuvio · ")) Public.codeName(it) else it } ?: ""

/** Is this a CNCVerse file from Vegamovies (the preferred HD source)? */
fun StreamItem.isVegamovies(): Boolean =
    listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename, stream.url).any { it.contains("vegamovies", true) }

/**
 * Picture preference (Settings › Playback): which picture tag comes first in every quality, the upgrade and Sources.
 * Default SDR first, HDR / HDR10 last. "Match my screen": HDR tags move up when the screen can show HDR.
 */
object PicturePref {
    val ALL = listOf("SDR", "Dolby Vision", "HDR10+", "HDR", "HDR10")
    private var hdrScreen: Boolean? = null

    fun order(vm: AppViewModel): List<String> {
        val saved = (vm.texts.value["picPref"] ?: "").split("|").filter { it in ALL }
        val base = (saved + ALL.filter { it !in saved }).distinct()
        if (!isOn(vm.texts.value["picMatch"] ?: "yes") || !hdrScreen(vm.appContext)) return base
        return base.filter { it != "SDR" } + "SDR"
    }

    fun rank(vm: AppViewModel, s: StreamItem): Int = order(vm).indexOf(VisualTags.primary(s)).let { if (it < 0) 99 else it }

    fun hdrScreen(ctx: Context): Boolean = hdrScreen ?: runCatching {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val d = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        if (android.os.Build.VERSION.SDK_INT >= 26) d.isHdr else (d.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true)
    }.getOrDefault(false).also { hdrScreen = it }
}

/**
 * The HD file that played for a title (remembered 2 days): the next time its title page opens, that file is made ready
 * in the background after the quick file, and — if it answers before Play — Play starts straight in HD.
 */
object HdMemo {
    @Serializable private data class Entry(val at: Long, val addon: String, val stream: StreamInfo)
    private const val TTL = 2L * 24 * 3600_000
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hdmemo", Context.MODE_PRIVATE)

    fun put(ctx: Context, key: String, s: StreamItem) {
        if (s.stream.url.isNullOrBlank() || s.isMovieBox() || s.isPlaylist()) return
        runCatching { prefs(ctx).edit().putString(key, Api.json.encodeToString(Entry.serializer(), Entry(System.currentTimeMillis(), s.addonName, s.stream))).apply() }
    }

    fun get(ctx: Context, key: String): StreamItem? = runCatching {
        val e = Api.json.decodeFromString(Entry.serializer(), prefs(ctx).getString(key, null) ?: return null)
        if (System.currentTimeMillis() - e.at > TTL) { forget(ctx, key); null } else StreamItem(e.addon, e.stream).also { StreamHeaders.remember(it) }
    }.getOrNull()

    fun forget(ctx: Context, key: String) { runCatching { prefs(ctx).edit().remove(key).apply() } }
}

/** Server check + jump test + start and index saved (used by the upgrade, the remembered HD file and next-episode HD). */
suspend fun saveHdStart(ctx: Context, c: StreamItem, startMs: Long = 0, durMs: Long = 0): Boolean = withContext(Dispatchers.IO) {
    val url = c.stream.url ?: return@withContext false
    StreamCache.register(c)
    val pr = StreamCache.probe(url)
    if (!pr.ok || isPlaylistLink(c, url) || !pr.seekable || pr.size <= 0) {
        if (Ranker.isDeadReason(pr.status, pr.type, url)) Ranker.markDeadUrl(url)
        return@withContext false
    }
    val mid = (pr.size / 2) / (1024 * 1024) * (1024 * 1024)
    val jumpOk = withTimeoutOrNull(12_000) { StreamCache.saveParts(ctx, url, mid, 256L * 1024, 1, null) { } } == null &&
        StreamCache.savedBytes(ctx, url, mid, 256L * 1024) > 0
    if (!jumpOk) return@withContext false
    val start = if (startMs > 5_000 && durMs > 0) ((pr.size.toDouble() * startMs / durMs).toLong() - 1024 * 1024).coerceAtLeast(0) / (1024 * 1024) * (1024 * 1024) else 0L
    withTimeoutOrNull(45_000) {
        kotlinx.coroutines.coroutineScope {
            val head = async { StreamCache.saveParts(ctx, url, 0, minOf(3L * 1024 * 1024, pr.size), 1, null) { } }
            val at = async { if (start > 0) StreamCache.saveParts(ctx, url, start, minOf(4L * 1024 * 1024, pr.size - start), 1, null) { } else null }
            val mp4 = url.contains(".mp4", true) || c.fileName()?.endsWith(".mp4", true) == true
            val t = if (mp4) minOf(24L * 1024 * 1024, pr.size / 60) else minOf(8L * 1024 * 1024, pr.size / 200)
            val tail = async { if (pr.size > 40L * 1024 * 1024) StreamCache.saveParts(ctx, url, pr.size - t, t, 2, null) { } else null }
            head.await() == null && at.await() == null && tail.await() == null
        }
    } == true
}

/** A few MB of a file saved from [posMs] (the Upgrade button after a jump: the HD file opens instantly there too). */
suspend fun saveAround(ctx: Context, url: String, posMs: Long, durMs: Long) = withContext(Dispatchers.IO) {
    val pr = StreamCache.probe(url)
    if (!pr.ok || pr.size <= 0 || durMs <= 0) return@withContext
    val start = ((pr.size.toDouble() * posMs / durMs).toLong() - 1024 * 1024).coerceAtLeast(0) / (1024 * 1024) * (1024 * 1024)
    withTimeoutOrNull(10_000) { StreamCache.saveParts(ctx, url, start, minOf(4L * 1024 * 1024, pr.size - start), 1, null) { } }
}

/** Asks the slow-server addons (CNCVerse) for [id] every [everyMs] until [until] says enough, for at most [forMs]. */
suspend fun askCncverse(vm: AppViewModel, type: String, id: String, everyMs: Long = 2_000, forMs: Long = 20_000, until: (List<StreamItem>) -> Boolean): List<StreamItem> {
    val slow = vm.slowStreamAddons(type, id)
    if (slow.isEmpty()) return emptyList()
    // Smart logic (phone): timed, overlapping asks; ends as soon as [until] is happy or [forMs] ran out.
    if (AutoPlayRules.smart) {
        val t0 = System.currentTimeMillis()
        // The title page's schedule is still asking: follow it instead of asking twice.
        while (SlowAddons.watching(id) && System.currentTimeMillis() - t0 < forMs) {
            val l = SlowAddons.links.value[id].orEmpty()
            if (l.isNotEmpty() && until(l)) return l
            delay(500)
        }
        val known = SlowAddons.links.value[id].orEmpty()
        if (known.isNotEmpty() && until(known)) return known
        val left = forMs - (System.currentTimeMillis() - t0)
        if (left < 3_000) return known
        val got = runCncSchedule(vm, type, id, maxMs = left, stop = { l -> until((known + l).distinctBy { it.stream.url }) })
        return (known + got).distinctBy { it.stream.url }
    }
    val got = LinkedHashMap<String, StreamItem>()
    val t0 = System.currentTimeMillis()
    while (true) {
        for (a in slow) runCatching {
            Api.fetch<StreamResponse>(vm.streamUrl(a, type, id)).streams.filter { !it.url.isNullOrBlank() }
                .map { StreamItem(a.label(), it).also { si -> StreamHeaders.remember(si) } }
        }.getOrNull()?.forEach { got.putIfAbsent(it.stream.url!!, it) }
        val l = got.values.toList()
        if (until(l) || System.currentTimeMillis() - t0 >= forMs) return l
        delay(everyMs)
    }
}
