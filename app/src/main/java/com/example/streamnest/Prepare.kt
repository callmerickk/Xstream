package com.example.streamnest

import android.content.Context
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the title page has prepared for instant play. */
data class Prepared(
    val key: String,
    /** Short status shown under the Play button. */
    val status: String,
    val progress: Float = 0f,
    /** Saved enough: Play starts straight from storage. */
    val ready: Boolean = false,
    /** Nothing could be prepared: Play uses the normal search. */
    val failed: Boolean = false,
    val item: StreamItem? = null,
    val list: List<StreamItem> = emptyList(),
    val subs: List<Subtitle> = emptyList(),
    /** Bytes saved so far and the target. */
    val saved: Long = 0, val target: Long = 0,
    /** The paused player has opened the file and can show a picture at once (the light turns green only then). */
    val playerReady: Boolean = false,
)

/**
 * Instant play: as soon as a title page opens, the streams are found, the best link (your quality group → language →
 * Source Test ranking) is checked, and its first minute — or the minute from your resume point — is saved to storage.
 * Play then starts from storage like a file on a hard disk. If a link refuses, the next one is tried.
 */
object Prepare {
    val state = MutableStateFlow<Prepared?>(null)
    private var job: Job? = null
    private var saveJob: Job? = null
    private var lastResume = 0L
    private var preparedAt = 0L

    /** Links of each title that failed while preparing: the player's own search never tries them again. */
    private val failed = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    fun failedFor(id: String): Set<String> = failed[id]?.toSet().orEmpty()

    fun start(vm: AppViewModel, ctx: Context, type: String, id: String, resumeMs: Long, durMs: Long?, quick: Boolean, level: String) {
        val cur = state.value
        // Same title, same resume point (within 30 s), prepared or still preparing: keep it.
        val sameSpot = kotlin.math.abs(resumeMs - lastResume) < 30_000 && System.currentTimeMillis() - preparedAt < 15 * 60_000L
        if (cur != null && cur.key == id && sameSpot && !cur.failed && (job?.isActive == true || cur.ready)) return
        cancel()
        lastResume = resumeMs
        preparedAt = System.currentTimeMillis()
        state.value = Prepared(id, "Finding streams…")
        job = vm.viewModelScope.launch {
            // Links arrive addon by addon: start checking as soon as the fast addons answered (no waiting for the slowest).
            val live = MutableStateFlow<Pair<List<StreamItem>, Boolean>>(emptyList<StreamItem>() to false)
            var useQuick = quick
            // Quick play's links and (after 6 s of silence) every addon's links, merged.
            var quickL: List<StreamItem> = emptyList(); var quickDone = false
            var wideL: List<StreamItem> = emptyList(); var wideDone = false
            var wide: Job? = null
            var wideOn = false
            fun publish() {
                val l = (quickL + wideL + if (wideOn) SlowAddons.links.value[id].orEmpty() else emptyList()).distinctBy { it.stream.url }
                val merged = if (wideOn) vm.filtered(l).list else l
                live.value = merged to (if (wideOn) wideDone else quickDone)
            }
            /** Quick play is slow or empty: ask every addon (HdHub…) and CNCVerse again, and prepare the best of them. */
            fun goWide(why: String) {
                if (wideOn) return
                wideOn = true
                useQuick = false
                state.value = Prepared(id, why)
                wide = launch {
                    launch { SlowAddons.links.collect { m -> if (m[id].orEmpty().isNotEmpty()) publish() } }
                    runCatching { vm.progressiveStreams(type, id, false).collect { (l, d) -> wideL = (wideL + l).distinctBy { it.stream.url }; wideDone = d; publish() } }
                    wideDone = true; publish()
                }
            }
            val feeder = launch {
                if (!quick) {
                    runCatching { vm.progressiveStreams(type, id, false).collect { live.value = it } }
                    if (!live.value.second) live.value = live.value.first to true
                    return@launch
                }
                // 6-second rule: no streaming playlist (MPD / HLS) from quick play within 6 s → the other addons' links are
                // prepared instead (MovieBox MP4s start late; they're used only when nothing else exists).
                // Instant plays never reach this (MovieBox answers in well under a second).
                val timer = launch { delay(6_000); if (quickL.none { it.isPlaylist() }) goWide("Quick play is slow — preparing a backup server…") }
                runCatching { vm.progressiveStreams(type, id, true).collect { (l, d) -> quickL = l; quickDone = d; if (l.isNotEmpty() || wideOn) publish() } }
                quickDone = true
                // Quick play's addons had nothing (MovieBox empty / busy): straight on with every addon.
                if (quickL.none { it.isPlaylist() }) { timer.cancel(); goWide("Quick play had nothing — checking backup servers…") }
                if (!wideOn) publish() else wide?.join()
            }
            // Nothing playing or being saved 10 s after the title page opened: CNCVerse is asked again (every 2 s until
            // it returns links, at most 20 s). It was asked once together with the quick addons.
            launch {
                delay(10_000)
                val st = state.value
                if (st != null && st.key == id && !st.ready && !st.status.startsWith("Saving")) {
                    PlayTiming.mark("nothing ready 10 s after the title page opened — CNCVerse asked again")
                    SlowAddons.poll(vm, type, id)
                }
            }
            val subsJob = async { withTimeoutOrNull(2500) { runCatching { vm.subtitles(type, id) }.getOrDefault(emptyList()) }.orEmpty() }
            // Wait for the first links, then up to 2 s more so a better addon can still answer.
            val first = withTimeoutOrNull(30_000) { live.first { (l, d) -> d || (if (quick && !wideOn) l.any { it.isPlaylist() } else l.isNotEmpty()) } }
            // A link in your top language that starts at once (MovieBox playlist) needs no waiting; otherwise ~1 s more.
            val instant = live.value.first.any { vm.langRank(it) == 0 && it.isPlaylist() && (!quick || vm.isQuick(it)) }
            if (first != null && !first.second && !instant) withTimeoutOrNull(1_000) { live.first { it.second } }
            var list = live.value.first
            // Subtitles never hold the preparing back: whatever answered within 0.7 s goes along (the player finds the rest).
            val subs = withTimeoutOrNull(700) { subsJob.await() }.orEmpty()
            val tried = HashSet<String>()
            val bad = failed.getOrPut(id) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
            var lastWhy = ""

            /** Checks one link and saves its start. true = ready. */
            suspend fun attempt(s: StreamItem, i: Int, n: Int, known: LinkProbe? = null): Boolean {
                val url = s.stream.url ?: return false
                val label = if (Public.dev) "${Ranker.labelOf(Ranker.groupKey(s)).substringAfter(" · ", "")} · ${qualityName(s.resolution())}" else qualityName(s.resolution())
                state.value = Prepared(id, "Checking ${i + 1}/$n · $label", list = list, subs = subs)
                StreamCache.register(s)
                val pr = known ?: StreamCache.probe(url)
                if (!pr.ok) {
                    lastWhy = pr.error ?: "HTTP ${pr.status}"
                    bad += url
                    // Dead (web page / not found / bad request / expired signed link): never again. Busy (429): just the next one.
                    if (Ranker.isDeadReason(pr.status, pr.type, url)) { Ranker.markDeadUrl(url); Ranker.recordFail(s, "prepare: $lastWhy") }
                    return false
                }
                Ranker.noteHostOk(url)
                // Playlists (HLS / DASH) stream in small pieces: nothing to save, they start fast.
                val mime = StreamType.mimeFor(url, s.fileName())
                if (mime != null) {
                    state.value = Prepared(id, "Ready · streaming playlist · $label", 1f, ready = true, item = s, list = list, subs = subs)
                    readyPlayer(vm, ctx, id, s, url, mime, subs, list, resumeMs)
                    return true
                }
                if (!pr.seekable) { lastWhy = "file can't be jumped inside (not seekable)"; bad += url; return false }
                // One minute from the start (or from your resume point); very big files: ~15 seconds.
                val perMin = minuteBytes(s, pr.size, durMs)
                val big = perMin > 150L * 1024 * 1024
                val want = if (big) perMin / 4 else perMin
                val startByte = if (resumeMs > 5_000 && pr.size > 0 && durMs != null && durMs > 0)
                    ((pr.size.toDouble() * resumeMs / durMs).toLong() - 2L * 1024 * 1024).coerceAtLeast(0L) / (1024 * 1024) * (1024 * 1024)
                else 0L
                val len = if (pr.size > 0) minOf(want, pr.size - startByte) else want
                val minStart = if (big) want else want / 4 // enough to start without waiting
                state.value = Prepared(id, "Saving ${if (big) "15 s" else "1:00"} · $label", 0f, item = s, list = list, subs = subs, target = len)
                // The player first reads the file's header (start) and its index (end: MP4 "moov", MKV "cues"), then jumps
                // to your resume point: save those too, so nothing has to come from the server before the picture.
                // The start (when resuming) and the end are fetched at the same time as each other.
                kotlinx.coroutines.coroutineScope {
                    if (startByte > 0) launch { StreamCache.save(ctx, url, 0, minOf(2L * 1024 * 1024, pr.size.coerceAtLeast(1))) { } }
                    if (pr.size > 40L * 1024 * 1024) launch {
                        val mp4 = url.contains(".mp4", true) || s.stream.behaviorHints?.filename?.endsWith(".mp4", true) == true
                        val tail = if (mp4) minOf(24L * 1024 * 1024, pr.size / 60) else minOf(8L * 1024 * 1024, pr.size / 200)
                        StreamCache.saveParts(ctx, url, pr.size - tail, tail, 2, null) { }
                    }
                }
                // Save until there is enough to start, then hand over to a paused player and stop saving: the paused player
                // keeps filling the saved video itself (two downloads of one file at once make pixeldrain & co. slow down).
                val enough = java.util.concurrent.atomic.AtomicBoolean(false)
                val savedNow = java.util.concurrent.atomic.AtomicLong(0)
                val saver = async {
                    // 4 pieces at once; the first piece done = enough to start.
                    StreamCache.saveParts(ctx, url, startByte, len, 4, onHeadDone = { enough.set(true) }) { f ->
                        val done = (len * f).toLong()
                        savedNow.set(done)
                        if (!enough.get()) state.value = Prepared(id, "Saving ${(f * 100).toInt()}% · $label", f, item = s, list = list, subs = subs, saved = done, target = len)
                    }
                }
                while (!enough.get() && saver.isActive) delay(80)
                if (enough.get()) {
                    saver.cancel()
                    state.value = Prepared(id, "⚡ Ready to play instantly · ${if (big) "15 s" else "1:00"} start saved · $label", 1f, ready = true,
                        item = s, list = list, subs = subs, saved = savedNow.get(), target = len)
                    readyPlayer(vm, ctx, id, s, url, null, subs, list, resumeMs)
                    return true
                }
                val err = runCatching { saver.await() }.getOrNull()
                if (err == null) {
                    state.value = Prepared(id, "⚡ Ready to play instantly · ${if (big) "15 s" else "1:00"} saved · $label", 1f, ready = true,
                        item = s, list = list, subs = subs, saved = len, target = len)
                    readyPlayer(vm, ctx, id, s, url, null, subs, list, resumeMs)
                    return true
                }
                lastWhy = err
                bad += url
                return false
            }

            while (true) {
                list = live.value.first.ifEmpty { list }
                val all0 = vm.rankedCandidates(list, level, quickOnly = useQuick).filter { it.stream.url !in tried }
                    .let { if (quick && level == "auto") vm.movieBoxFirst(it) else it }
                    .let { l -> if (quick && !wideOn) l.filter { it.isPlaylist() } else l }
                // MovieBox MP4s through PenguPlay's server: only when nothing else can play.
                val cands = (all0.filter { !it.slowMp4() }.ifEmpty { all0 }).take(5)
                // Race the best three at once: the first server to answer with a video is prepared first.
                state.value = Prepared(id, "Checking ${minOf(3, cands.size)} links at once…", list = list, subs = subs)
                // The best one is a streaming playlist (MovieBox .mpd / .m3u8): it starts at once, no race needed.
                // One link per server in the race (three links on one slow server would all be slow).
                val win = if (cands.size > 1 && cands.first().isPlaylist().not()) StreamCache.race(cands.distinctBy { hostOf(it.stream.url) }.take(3).mapNotNull { it.stream.url }, 6000) else null
                val ordered = win?.let { (u, _) -> cands.sortedBy { if (it.stream.url == u) 0 else 1 } } ?: cands
                var i = 0
                for (s in ordered) {
                    s.stream.url?.let { tried += it }
                    val known = win?.takeIf { it.first == s.stream.url }?.second
                    if (attempt(s, i++, ordered.size, known)) {
                        // The HD file that played for this title last time (remembered 2 days): made ready now in the
                        // background; if it's ready before Play, Play starts straight in HD.
                        val memo = if (quick && !BuildConfig.IS_TV && vm.upgradeAllowed()) HdMemo.get(ctx, id) else null
                        if (memo != null && memo.stream.url != s.stream.url) {
                            val ok = saveHdStart(ctx, memo, resumeMs, durMs ?: 0)
                            if (ok) {
                                Preloader.release(id)
                                val hdLabel = qualityName(memo.effectiveRes())
                                state.value = Prepared(id, "⚡ Ready in HD · $hdLabel", 1f, ready = true, item = memo, list = (list + memo).distinctBy { it.stream.url }, subs = subs)
                                readyPlayer(vm, ctx, id, memo, memo.stream.url!!, null, subs, list, resumeMs)
                                feeder.cancel(); return@launch
                            } else HdMemo.forget(ctx, id)
                        }
                        // Prepared another addon's file because quick play was slow: if MovieBox's instant playlist
                        // still answers before Play, it takes over (it starts like Netflix and plays more smoothly).
                        if (quick && !vm.isQuick(s)) {
                            val mb = withTimeoutOrNull(90_000) {
                                live.first { (l, _) -> l.any { it.isMovieBox() && it.isPlaylist() && vm.langRank(it) <= vm.langRank(s) && it.stream.url !in tried } }
                            }?.first?.filter { it.isMovieBox() && it.isPlaylist() && vm.langRank(it) <= vm.langRank(s) && it.stream.url !in tried }?.minByOrNull { vm.langRank(it) }
                            if (mb != null) {
                                mb.stream.url?.let { tried += it }
                                list = live.value.first
                                if (Preloader.has(id)) Preloader.release(id)
                                if (!attempt(mb, 0, 1)) readyPlayer(vm, ctx, id, s, s.stream.url!!, null, subs, list, resumeMs)
                            }
                        }
                        feeder.cancel(); return@launch
                    }
                }
                // Nothing worked yet. Addons still answering, or a slow addon (CNCVerse) still collecting: wait for new links.
                val known = list.mapNotNull { it.stream.url }.toSet() + tried
                if (!live.value.second) {
                    state.value = Prepared(id, "Waiting for more addons…", list = list, subs = subs)
                    val m = withTimeoutOrNull(25_000) { live.first { (l, done) -> done || l.any { it.stream.url != null && it.stream.url !in known } } }
                    if (m != null) continue
                }
                if (!SlowAddons.watching(id)) break
                state.value = Prepared(id, "Waiting for more links…", list = list, subs = subs)
                val m = withTimeoutOrNull(90_000) {
                    SlowAddons.links.first { mm -> mm[id].orEmpty().any { it.stream.url != null && it.stream.url !in known } }
                }
                val more = m?.get(id)?.filter { it.stream.url != null && it.stream.url !in known } ?: break
                list = vm.filtered((list + more).distinctBy { it.stream.url }).list
                live.value = list to true
            }
            feeder.cancel()
            state.value = Prepared(id, "Couldn't prepare (${lastWhy.ifBlank { "no link answered" }}) — Play will search again",
                failed = true, list = list, subs = subs)
        }
    }

    /**
     * Phones: once the start is saved, a player opens it in the background and waits, paused, so Play shows the video
     * in a fraction of a second (the player is handed over). Not on TV (memory).
     */
    private fun readyPlayer(vm: AppViewModel, ctx: Context, id: String, s: StreamItem, url: String, mime: String?, subs: List<Subtitle>, list: List<StreamItem>, resumeMs: Long) {
        if (BuildConfig.IS_TV || Preloader.has(id)) return
        runCatching {
            StreamHeaders.use(url)
            val p = buildPlayer(ctx, vm, true)
            p.setMediaItem(androidx.media3.common.MediaItem.Builder().setUri(url).apply { if (mime != null) setMimeType(mime) }
                .setSubtitleConfigurations(subtitleConfigs(subs)).build(), resumeMs)
            p.playWhenReady = false
            // Green only when the paused player has really opened the file (it can show a picture at once).
            p.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(st: Int) {
                    if (st == androidx.media3.common.Player.STATE_READY) state.value?.takeIf { it.key == id && !it.playerReady }?.let { state.value = it.copy(playerReady = true) }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    // Broken while waiting: drop it (Play then opens the link the normal way).
                    android.os.Handler(android.os.Looper.getMainLooper()).post { if (Preloader.has(id)) Preloader.release(id) }
                    state.value?.takeIf { it.key == id }?.let { state.value = it.copy(playerReady = false) }
                }
            })
            p.prepare()
            Preloader.set(Preloaded(id, url, subs, s.stream.name ?: s.addonName, s.addonName, list, p))
        }
    }

    /** Stop downloading (Play was pressed — the player takes over; what was saved stays saved). */
    fun stopSaving() { job?.cancel(); job = null }

    fun cancel() { job?.cancel(); job = null; saveJob?.cancel(); saveJob = null }

    fun qualityName(res: Int): String = when { res >= 1440 -> "4K"; res >= 1080 -> "1080p"; res >= 720 -> "720p"; res > 0 -> "${res}p"; else -> "SD" }

    /**
     * While a video plays: save one minute (from the current point) of the top-ranked links in the other quality groups,
     * so switching quality starts almost at once. Runs one link at a time.
     */
    fun saveOthers(vm: AppViewModel, ctx: Context, list: List<StreamItem>, playingUrl: String, posMs: Long, durMs: Long) {
        saveJob?.cancel()
        saveJob = vm.viewModelScope.launch {
            val playing = list.firstOrNull { it.stream.url == playingUrl }
            val levels = listOf("4k", "fhd", "mhd").filter { lv -> playing == null || Ranker.tier(playing.resolution()) != Ranker.tier(AllQualityLevels.first { it.id == lv }.res) }
            for (lv in levels) {
                val s = vm.rankedCandidates(list, lv, quickOnly = true).firstOrNull { it.stream.url != playingUrl &&
                    Ranker.tier(it.resolution()) == Ranker.tier(AllQualityLevels.first { q -> q.id == lv }.res) } ?: continue
                val url = s.stream.url ?: continue
                if (isPlaylistLink(s, url)) continue
                StreamCache.register(s)
                val pr = StreamCache.probe(url)
                if (!pr.ok || !pr.seekable || pr.size <= 0) continue
                val perMin = minuteBytes(s, pr.size, durMs)
                val start = ((pr.size.toDouble() * posMs / durMs.coerceAtLeast(1)).toLong() - 1024 * 1024).coerceAtLeast(0L) / (1024 * 1024) * (1024 * 1024)
                StreamCache.save(ctx, url, start, minOf(perMin, pr.size - start)) { }
            }
        }
    }

    fun stopOthers() { saveJob?.cancel(); saveJob = null }
}
