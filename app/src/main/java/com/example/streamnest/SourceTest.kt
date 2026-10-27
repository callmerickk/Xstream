package com.example.streamnest

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One tested link. */
data class LinkResult(
    val title: String, val addon: String, val key: String, val bucket: String, val lang: String, val res: Int,
    val ok: Boolean, val firstFrameMs: Long?, val seekable: Boolean, val reason: String?,
    /** false = not the link's fault (server busy, 429): left out of the ranking. */
    val counted: Boolean = true,
    /** answered "not a video / not found / bad request" */
    val dead: Boolean = false,
)

/** A title picked for the test. */
internal data class TestTitle(val imdb: String, val type: String, val name: String, val year: String?, val videoId: String, val label: String)

/**
 * Source Test (Developer's Corner): picks 2 random movies and 2 random series (Hindi / English, any year), asks the
 * chosen addons for every link, and plays each link until its first picture (20 s at most), measuring every step.
 * Then it ranks the provider groups per quality group (4K / Full HD / Mid HD / Low) and language, uses that ranking,
 * and writes a full log to download.
 */
@OptIn(UnstableApi::class)
object SourceTester {
    val running = MutableStateFlow(false)
    val line = MutableStateFlow("")
    val step = MutableStateFlow("")
    val done = MutableStateFlow(0)
    val total = MutableStateFlow(0)
    val results = MutableStateFlow<List<LinkResult>>(emptyList())
    /** true = the test player is shown on screen; false = it plays off-screen (same hardware decoder). */
    val showPlayer = MutableStateFlow(false)
    val player = MutableStateFlow<ExoPlayer?>(null)
    val logFile = MutableStateFlow<File?>(null)
    private var job: Job? = null
    private val log = StringBuilder()
    /** Off-screen picture target. An ImageReader that is emptied after every frame — a never-read SurfaceTexture
     *  left the phone's decoder stuck after ~10 links, so every later link failed with "decoder init failed". */
    private var offReader: android.media.ImageReader? = null
    private var offThread: android.os.HandlerThread? = null
    private fun freshSurface(): Surface? = runCatching {
        offReader?.let { runCatching { it.close() } }
        val th = offThread ?: android.os.HandlerThread("test-frames").also { it.start(); offThread = it }
        val r = android.media.ImageReader.newInstance(640, 360, android.graphics.ImageFormat.PRIVATE, 3)
        r.setOnImageAvailableListener({ rd -> runCatching { rd.acquireLatestImage()?.close() } }, android.os.Handler(th.looper))
        offReader = r
        r.surface
    }.getOrNull()
    private var lastHost: String? = null
    private fun pr0Host(url: String): String? = runCatching { android.net.Uri.parse(url).host }.getOrNull()

    private fun stamp() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    private fun w(s: String) { synchronized(log) { log.append(s).append('\n') } }

    fun stop() { job?.cancel(); job = null }

    fun start(vm: AppViewModel, ctx: Context, addons: Set<String>) {
        if (running.value || RefreshLab.running.value) return
        job = vm.viewModelScope.launch {
            running.value = true
            results.value = emptyList(); done.value = 0; total.value = 0; logFile.value = null; lastHost = null
            synchronized(log) { log.setLength(0) }
            try {
                runTest(vm, ctx, addons)
            } finally {
                player.value?.let { runCatching { it.release() } }
                player.value = null
                offReader?.let { runCatching { it.close() } }; offReader = null
                running.value = false
                step.value = if (results.value.isEmpty()) "Stopped" else "Finished · ${results.value.count { it.ok }}/${results.value.size} links played"
                logFile.value = writeLog(ctx)
            }
        }
    }

    private suspend fun runTest(vm: AppViewModel, ctx: Context, addons: Set<String>) {
        val net = if (isOnWifi(ctx)) "Wi-Fi" else "mobile data"
        w("Xstream Source Test log")
        w("Started: ${SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US).format(Date())}")
        w("App: ${vm.appVersion} · Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · Network: $net")
        w("Addons tested: ${addons.joinToString(", ")}")
        w("Your languages: ${vm.qsLangs().joinToString(" → ")}")
        w("Each link: 1) one-byte request (server answer, speed, size, seekable)  2) player opens it until the first picture (max 20 s)")
        Ranker.playFails().let { pf ->
            w("")
            w("== From your real plays since the last test (${pf.size} failures) ==")
            if (pf.isEmpty()) w("  none") else pf.forEach { w("  $it") }
            Ranker.clearPlayFails()
            w("")
            w("== Servers in your real plays (starts that showed a picture / failed) ==")
            HostStats.summary().let { hs -> if (hs.isEmpty()) w("  none yet") else hs.forEach { w("  $it") } }
        }
        w("")
        w(TmdbAuth.label(TmdbAuth.check(vm.tmdbKey())) + " (titles come from the Home rows when TMDB can't be used)")
        // 2 movies + 2 series. A title no addon has links for is swapped for another (up to 6 tries per slot).
        val perTitle = mutableListOf<Pair<TestTitle, List<StreamItem>>>()
        for ((si, tv) in listOf(false, false, true, true).withIndex()) {
            var tries = 0
            while (tries < 6) {
                if (!kotlin.coroutines.coroutineContext.isActive) return
                tries++
                step.value = "Picking ${if (tv) "series" else "movie"} ${si % 2 + 1} (try $tries)…"
                val t = pickOne(vm, tv, perTitle.map { it.first.imdb }.toSet()) ?: continue
                w("== ${t.label} ==")
                val all = mutableListOf<StreamItem>()
                for (a in addons) {
                    step.value = "Asking $a · ${t.name}"
                    val t0 = SystemClock.elapsedRealtime()
                    var l = withTimeoutOrNull(35_000) {
                        runCatching { vm.addonStreamsFlow(t.type, t.videoId, setOf(a), null).first { it.second }.first }.getOrDefault(emptyList())
                    }
                    val ms = SystemClock.elapsedRealtime() - t0
                    if (l == null) w("  Addon $a: no answer in 35 s")
                    else w("  Addon $a: ${"%.1f".format(ms / 1000f)} s · ${l.size} links")
                    // Slow-server addons (CNCVerse) keep collecting links: ask again every 2 s for 60 s.
                    val addonObj = vm.addons.value.firstOrNull { it.label() == a }
                    if (addonObj != null && SlowAddons.isSlow(addonObj)) {
                        val acc = l.orEmpty().toMutableList()
                        val ts = SystemClock.elapsedRealtime()
                        while (SystemClock.elapsedRealtime() - ts < 60_000) {
                            step.value = "Asking $a again (collecting links) · ${t.name} · ${acc.size} so far"
                            delay(2_000)
                            val more = runCatching {
                                Api.fetch<StreamResponse>(vm.streamUrl(addonObj, t.type, t.videoId)).streams.filter { !it.url.isNullOrBlank() }
                                    .map { StreamItem(a, it).also { si -> StreamHeaders.remember(si) } }
                            }.getOrDefault(emptyList())
                            more.forEach { m -> if (acc.none { it.stream.url == m.stream.url }) acc += m }
                        }
                        w("  Addon $a after asking every 2 s for 60 s: ${acc.size} links")
                        l = acc
                    }
                    l.orEmpty().forEach { s -> if (s.stream.url != null && all.none { it.stream.url == s.stream.url }) all += s }
                }
                if (all.isEmpty()) { w("  → no links from any addon: picking another title"); w(""); continue }
                w("")
                perTitle += t to all
                break
            }
        }
        if (perTitle.isEmpty()) { w("Couldn't find titles with links (TMDB key missing in Integrations, or no internet)."); step.value = "Couldn't find titles with links"; return }
        total.value = perTitle.sumOf { it.second.size }
        for ((ti, pair) in perTitle.withIndex()) {
            val (t, links) = pair
            w("== Testing ${t.label} · ${links.size} links ==")
            // At most one MovieBox MP4 through PenguPlay's own server per title: each check counts against PenguPlay's
            // request limit (and they behave the same).
            var penguMp4 = 0
            for (s in links) {
                if (s.slowMp4() && penguMp4++ >= 1) { done.value = done.value + 1; continue }
                if (!kotlin.coroutines.coroutineContext.isActive) return
                val r = testLink(vm, ctx, t, s, ti, perTitle.size)
                results.value = results.value + r
                done.value = done.value + 1
            }
            w("")
        }
        val ranking = buildRanking(vm)
        // What the auto-play logic was before this test is saved first (Developer's Corner › Auto-play logic › Undo).
        AutoPlayRules.checkpoint(vm, "Before Source Test")
        Ranker.apply(ranking, fromTest = true)
        if (isOn(vm.texts.value["srcAutoManage"] ?: "yes")) manageAddons(vm)
        w("== Ranking — all test runs so far combined (used on this phone now; upload it to share with everyone) ==")
        listOf("4k" to "4K", "fhd" to "Full HD", "mhd" to "Mid HD", "low" to "Low").forEach { (b, name) ->
            listOf("hindi", "english", "other").forEach { lang ->
                val rows = ranking.entries.filter { it.bucket == b && it.lang == lang }.sortedByDescending { it.score }
                if (rows.isNotEmpty()) {
                    w("-- $name · $lang --")
                    rows.forEachIndexed { i, e ->
                        w("  ${i + 1}. ${Ranker.labelOf(e.key)} · played ${e.ok}/${e.tests} · median ${"%.1f".format(e.medianMs / 1000f)} s · ${if (e.seekable) "seekable" else "NOT seekable"} · score ${"%.0f".format(e.score)}${if (Ranker.isKeyDead(e.key)) " · DEAD (hidden)" else if (e.dead) " · dead links here (group still plays elsewhere)" else ""}")
                    }
                }
            }
        }
    }

    // ───── Picking titles (TMDB discover: Hindi or English, random year) ─────

    /** One random popular title (Hindi or English, any year since 1995); series get a random aired episode. */
    internal suspend fun pickOne(vm: AppViewModel, tv: Boolean, exclude: Set<String>): TestTitle? {
        val key = vm.tmdbKey()
        // TMDB first (a few tries: a random year can come back empty); when TMDB doesn't answer (no key, refused key,
        // no network to it) the tests still run, with titles from the Home rows / Cinemeta's popular list.
        if (key.isNotEmpty()) repeat(3) { runCatching { pickTmdb(vm, key, tv, exclude) }.getOrNull()?.let { return it } }
        return pickLocal(vm, tv, exclude)
    }

    /** A popular title without TMDB: Home rows first, then Cinemeta's top list; series get a random aired episode. */
    private suspend fun pickLocal(vm: AppViewModel, tv: Boolean, exclude: Set<String>): TestTitle? {
        val kind = if (tv) "series" else "movie"
        var pool = vm.shelves.value.flatMap { it.items }.filter { it.type == kind && it.id.startsWith("tt") && it.id.substringBefore(":") !in exclude }.distinctBy { it.id }
        if (pool.isEmpty()) pool = runCatching { Api.fetch<CatalogResponse>("$CINEMETA/catalog/$kind/top.json").metas }.getOrDefault(emptyList())
            .filter { it.id.startsWith("tt") && it.id !in exclude }
        val m = pool.shuffled().firstOrNull() ?: return null
        val imdb = m.id.substringBefore(":")
        if (!tv) return TestTitle(imdb, "movie", m.name, m.releaseInfo, imdb, "${m.name} · movie · $imdb (from Home)")
        val meta = runCatching { vm.fullMeta(Meta(id = imdb, type = "series")) }.getOrNull() ?: return null
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val ep = meta.videos.orEmpty().filter { (it.season ?: 0) > 0 && it.episode != null && (it.released?.take(10)?.let { r -> r <= today } ?: true) }.shuffled().firstOrNull() ?: return null
        return TestTitle(imdb, "series", m.name, m.releaseInfo, ep.id, "${m.name} S${ep.season} E${ep.episode} · series · ${ep.id} (from Home)")
    }

    private suspend fun pickTmdb(vm: AppViewModel, key: String, tv: Boolean, exclude: Set<String>): TestTitle? {
        val rnd = java.util.Random()
        val nowYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val lang = if (rnd.nextBoolean()) "hi" else "en"
        val year = 1995 + rnd.nextInt((nowYear - 1995).coerceAtLeast(1))
        val kind = if (tv) "tv" else "movie"
        val yearParam = if (tv) "first_air_date_year" else "primary_release_year"
        // Popular titles only (addons rarely have obscure ones).
        val votes = when { tv && lang == "hi" -> 15; tv -> 200; lang == "hi" -> 80; else -> 800 }
        val list = runCatching {
            Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/discover/$kind?api_key=$key&with_original_language=$lang&$yearParam=$year&sort_by=popularity.desc&vote_count.gte=$votes&page=1"))
                .jsonObject["results"]!!.jsonArray.map { it.jsonObject }
        }.onFailure { e -> ActLog.add("NET", "Source Test: TMDB discover failed: ${e.message}") }.getOrDefault(emptyList())
        val hit: JsonObject = list.take(10).shuffled().firstOrNull() ?: return null
        val tmdbId = hit["id"]?.jsonPrimitive?.content ?: return null
        val name = (hit["title"] ?: hit["name"])?.jsonPrimitive?.content ?: return null
        val imdb = runCatching {
            Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/$kind/$tmdbId/external_ids?api_key=$key")).jsonObject["imdb_id"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.startsWith("tt") } ?: return null
        if (imdb in exclude) return null
        val langName = if (lang == "hi") "Hindi" else "English"
        if (!tv) return TestTitle(imdb, "movie", name, year.toString(), imdb, "$name ($year) · movie · $langName · $imdb")
        val meta = runCatching { vm.fullMeta(Meta(id = imdb, type = "series")) }.getOrNull() ?: return null
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val eps = meta.videos.orEmpty().filter { (it.season ?: 0) > 0 && it.episode != null && (it.released?.take(10)?.let { r -> r <= today } ?: true) }
        val ep = eps.shuffled().firstOrNull() ?: return null
        return TestTitle(imdb, "series", name, year.toString(), ep.id, "$name ($year) S${ep.season} E${ep.episode} · series · $langName · ${ep.id}")
    }

    // ───── Testing one link ─────

    private suspend fun testLink(vm: AppViewModel, ctx: Context, t: TestTitle, s: StreamItem, ti: Int, tn: Int): LinkResult {
        val url = s.stream.url!!
        val key = Ranker.groupKey(s)
        val res = s.resolution()
        val bucket = Ranker.bucket(res)
        val lang = Ranker.lang(s)
        val name = (s.stream.name ?: "").replace("\n", " ").take(60)
        val file = (s.stream.behaviorHints?.filename ?: s.stream.title?.lineSequence()?.firstOrNull() ?: "").take(90)
        step.value = "Title ${ti + 1}/$tn · link ${done.value + 1}/${total.value}"
        line.value = "${Ranker.labelOf(key)} · ${Prepare.qualityName(res)} · $lang"
        w("#${done.value + 1} [${Ranker.labelOf(key)}] ${Prepare.qualityName(res)} · $lang · \"$name\" · $file")
        w("   link: ${url.take(140)}")
        // What the addon sends along with this link (MovieBox / VA Player need special request details).
        StreamHeaders.forUrl(url).let { h -> w("   request details from the addon: " + if (h.isEmpty()) "none" else h.entries.joinToString(", ") { "${it.key}: ${it.value.take(60)}" }) }
        // 1) one-byte request (a busy server — 429 — gets one more try after 5 s)
        val host = pr0Host(url)
        if (host != null && host == lastHost) delay(700) // don't hammer the same server
        lastHost = host
        var pr = StreamCache.probe(url)
        if (pr.status == 429) {
            w("   [${stamp()}] server busy (429) — waiting 5 s, trying once more")
            delay(5000)
            pr = StreamCache.probe(url)
        }
        val sizeTxt = if (pr.size > 0) "%.2f GB".format(pr.size / 1_073_741_824.0) else "size ?"
        w("   [${stamp()}] server: ${if (pr.ok) "HTTP ${pr.status}" else (pr.error ?: "failed")} in ${pr.ttfbMs} ms · $sizeTxt · ${if (!pr.ok) "seekable ? (no video sent)" else if (pr.seekable) "seekable" else "NOT seekable"} · type ${pr.type ?: "?"} · host ${pr.host}${if (pr.finalHost != pr.host) " → ${pr.finalHost}" else ""}")
        if (!pr.ok && pr.status == 429) {
            w("   ✗ still busy (429) — not counted in the ranking (the server limits requests, the link may be fine)")
            return LinkResult(t.label, s.addonName, key, bucket, lang, res, false, null, pr.seekable, "busy (429)", counted = false)
        }
        if (pr.ok) Ranker.noteHostOk(url)
        if (!pr.ok && (pr.status in 400..599 || Ranker.isDeadReason(pr.status, pr.type, url))) {
            val dead = Ranker.isDeadReason(pr.status, pr.type, url)
            if (dead) Ranker.markDeadUrl(url)
            w(if (dead) "   ✗ DEAD LINK (${pr.error ?: "HTTP ${pr.status}"}) — hidden from Sources" else "   ✗ refused by the server (${pr.error ?: "HTTP ${pr.status}"}) — player not tried")
            return LinkResult(t.label, s.addonName, key, bucket, lang, res, false, null, pr.seekable, pr.error ?: "HTTP ${pr.status}", dead = dead)
        }
        // 2) the player, until the first picture
        val fname = s.stream.behaviorHints?.filename
        val tm0 = SystemClock.elapsedRealtime()
        val mime = if (StreamType.needsProbe(url, fname)) withContext(Dispatchers.IO) { StreamType.probe(url) }.takeIf { it.isNotEmpty() } else StreamType.mimeFor(url, fname)
        val typeMs = SystemClock.elapsedRealtime() - tm0
        w("   [${stamp()}] type: ${mime ?: "plain file"} (${typeMs} ms)")
        // The link's own request headers go with every request — also the video pieces of playlists (VA Player…).
        StreamHeaders.active = StreamHeaders.forUrl(url)
        var att = playOnce(ctx, vm, url, mime)
        // "Decoder init failed" = the phone's video decoder was still busy (not the link's fault):
        // wait, give it a fresh player + picture target, try once more.
        if (att.err?.startsWith("DECODER_INIT_FAILED") == true) {
            w("   [${stamp()}] phone's video decoder busy — waiting 1.5 s, trying once more")
            delay(1500)
            att = playOnce(ctx, vm, url, mime)
        }
        val ok = !att.timedOut && att.err == null
        val decoderBusy = att.err?.startsWith("DECODER_INIT_FAILED") == true
        val reason = when {
            att.timedOut -> if (att.readyAt >= 0) "ready in ${att.readyAt} ms but no picture in 20 s (decoder?)" else "nothing after 20 s (server too slow or stuck)"
            else -> att.err
        }
        if (att.tracks.isNotEmpty()) w("   tracks: ${att.tracks}")
        if (ok) w("   [${stamp()}] ✓ first picture in ${att.ff} ms (player ready at ${if (att.readyAt >= 0) "${att.readyAt} ms" else "— picture came before the player said ready"})")
        else w("   [${stamp()}] ✗ $reason")
        if (decoderBusy) w("   → the phone's decoder, not the link — not counted in the ranking")
        line.value = (if (ok) "✓ ${"%.1f".format(att.ff / 1000f)} s · " else "✗ ") + "${Ranker.labelOf(key)} · ${Prepare.qualityName(res)}"
        return LinkResult(t.label, s.addonName, key, bucket, lang, res, ok, if (ok) att.ff else null, pr.seekable, reason, counted = !decoderBusy)
    }

    private class Attempt(val err: String?, val timedOut: Boolean, val readyAt: Long, val ff: Long, val tracks: String)

    /** Refresh Lab: opens one link in the real player until its first picture. ms, or null + why not. */
    internal suspend fun firstPicture(ctx: Context, vm: AppViewModel, url: String, fileName: String?): Pair<Long?, String?> {
        val mime = if (StreamType.needsProbe(url, fileName)) withContext(Dispatchers.IO) { StreamType.probe(url) }.takeIf { it.isNotEmpty() } else StreamType.mimeFor(url, fileName)
        StreamHeaders.active = StreamHeaders.forUrl(url)
        var att = playOnce(ctx, vm, url, mime)
        if (att.err?.startsWith("DECODER_INIT_FAILED") == true) { delay(1500); att = playOnce(ctx, vm, url, mime) }
        return if (!att.timedOut && att.err == null) att.ff to null else null to (if (att.timedOut) "nothing in 20 s" else att.err)
    }

    /** Refresh Lab finished: free the off-screen picture target. */
    internal fun releaseTestSurface() { offReader?.let { runCatching { it.close() } }; offReader = null }

    /** One fresh player on one fresh picture target, until the first picture (max 20 s); always fully released. */
    private suspend fun playOnce(ctx: Context, vm: AppViewModel, url: String, mime: String?): Attempt {
        val p = buildPlayer(ctx, vm, fast = true, cache = false, meter = false)
        p.volume = 0f
        freshSurface()?.let { p.setVideoSurface(it) }
        player.value = p
        val t0 = SystemClock.elapsedRealtime()
        val result = CompletableDeferred<String?>()
        var readyAt = -1L
        var tracksTxt = ""
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (readyAt < 0 && p.playbackState == Player.STATE_READY) readyAt = SystemClock.elapsedRealtime() - t0
                result.complete(null)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && readyAt < 0) readyAt = SystemClock.elapsedRealtime() - t0
                if (state == Player.STATE_READY && p.videoSize.width == 0 && p.currentTracks.groups.none { it.type == C.TRACK_TYPE_VIDEO }) result.complete(null)
            }
            override fun onPlayerError(error: PlaybackException) {
                result.complete("${error.errorCodeName.removePrefix("ERROR_CODE_")}${error.cause?.message?.let { " · ${it.take(120)}" } ?: ""}")
            }
            override fun onTracksChanged(tracks: Tracks) {
                tracksTxt = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO || it.type == C.TRACK_TYPE_AUDIO }.joinToString(" ; ") { g ->
                    (0 until g.length).joinToString(", ") { i ->
                        val f = g.getTrackFormat(i)
                        val kind = if (g.type == C.TRACK_TYPE_VIDEO) "video" else "audio"
                        val dims = if (f.width > 0) " ${f.width}x${f.height}" else ""
                        val ch = if (f.channelCount > 0) " ${f.channelCount}ch" else ""
                        "$kind ${f.sampleMimeType ?: f.codecs ?: "?"}$dims$ch ${if (g.isTrackSupported(i)) "ok" else "NOT SUPPORTED"}"
                    }
                }
            }
        }
        p.addListener(l)
        p.setMediaItem(MediaItem.Builder().setUri(url).apply { if (mime != null) setMimeType(mime) }.build())
        p.prepare(); p.playWhenReady = true
        val err = withTimeoutOrNull(20_000) { result.await() }
        val ff = SystemClock.elapsedRealtime() - t0
        val timedOut = err == null && !result.isCompleted
        p.removeListener(l)
        player.value = null
        // Stop first (lets the decoder finish cleanly), detach the picture target, then release.
        runCatching { p.stop(); p.clearVideoSurface(); p.release() }
        delay(250)
        return Attempt(err, timedOut, readyAt, ff, tracksTxt)
    }

    // ───── Ranking ─────

    /**
     * Uses the ranking for your addons: fastest / most reliable first, and Quick Start = addons whose links play
     * (70 %+ played, median under 4 s); addons that mostly fail move to "All sources" only. Logged.
     */
    private fun manageAddons(vm: AppViewModel) {
        data class St(val tests: Int, val ok: Int, val median: Long)
        // Judge each addon only by the groups you will actually see (dead and keeps-failing groups are hidden anyway).
        val usable = Ranker.file.entries.filter { !Ranker.isKeyDead(it.key) && !Ranker.keyFailing(it.key) }
        val byAddon = usable.groupBy { it.addon }.mapValues { (_, es) ->
            val tests = es.sumOf { it.tests }
            val ok = es.sumOf { it.ok }
            val withOk = es.filter { it.ok > 0 }
            St(tests, ok, if (withOk.isEmpty()) 20_000L else withOk.sumOf { it.medianMs * it.ok } / withOk.sumOf { it.ok })
        }
        if (byAddon.isEmpty()) return
        // Quick Start is judged on THIS test when it tried the addon enough (old results from before fixes — e.g.
        // MovieBox before its playlists were handled — must not keep a working addon out).
        // …and only on the link groups you will actually get (a group the server refuses — e.g. MovieBox's pengu.uk
        // mp4 links, HTTP 428 — is hidden, so it doesn't pull down the addon's fast .mpd / .m3u8 links).
        val thisRun = results.value.filter { it.counted && !Ranker.keyFailing(it.key) && !Ranker.isKeyDead(it.key) }.groupBy { it.addon }.mapValues { (_, l) ->
            val oks = l.filter { it.ok }.mapNotNull { it.firstFrameMs }.sorted()
            St(l.size, oks.size, if (oks.isEmpty()) 20_000L else oks[oks.size / 2])
        }
        val score = byAddon.mapValues { (_, v) -> Ranker.scoreOf(v.ok, v.tests, v.median, true) }
        val order = score.entries.sortedByDescending { it.value }.map { it.key }
        w("")
        w("== Addons arranged by the test (switch: Developer's Corner › Source Test) ==")
        if (vm.reorderAddonsByNames(order)) w("Addon order: ${order.joinToString(" → ")}") else w("Addon order: unchanged")
        val qs = vm.qsAddons().toMutableSet()
        val before = qs.toSet()
        val judged = byAddon.keys.associateWith { name -> thisRun[name]?.takeIf { it.tests >= 3 } ?: byAddon.getValue(name) }
        judged.forEach { (name, v) ->
            if (v.tests < 3) { w("  $name: too few links tested (${v.tests}) to decide Quick Start"); return@forEach }
            val rate = v.ok.toDouble() / v.tests
            when {
                // Quick Start is for links that start at once (MovieBox ~0.6 s): slower addons stay in All sources.
                rate >= 0.7 && v.median <= 2_000 -> qs += name
                rate >= 0.7 && name !in qs -> w("  $name: plays well but takes ${"%.1f".format(v.median / 1000f)} s to start — kept out of Quick Start (needs ≤ 2 s)")
                rate < 0.3 -> qs -= name
                else -> w("  $name: Quick Start unchanged — played ${(rate * 100).toInt()}% (needs 70%), median ${"%.1f".format(v.median / 1000f)} s (needs ≤ 2 s)")
            }
        }
        val added = qs - before
        val removed = before - qs
        if (added.isNotEmpty() || removed.isNotEmpty()) {
            vm.setText("qsAddons", qs.joinToString("|"))
            if (added.isNotEmpty()) w("Quick Start: added ${added.joinToString(", ")}")
            if (removed.isNotEmpty()) w("Quick Start: removed ${removed.joinToString(", ")} (still in All sources)")
        } else w("Quick Start: unchanged (${qs.joinToString(", ")})")
        byAddon.forEach { (name, v) -> w("  $name · usable groups played ${v.ok}/${v.tests} · median ${"%.1f".format(v.median / 1000f)} s") }
        val hidden = Ranker.file.entries.filter { Ranker.isKeyDead(it.key) || Ranker.keyFailing(it.key) }.map { it.key }.distinct()
        if (hidden.isNotEmpty()) {
            w("Hidden by \"Tested sources only\" / dead (${hidden.size} groups):")
            hidden.forEach { w("  ${Ranker.labelOf(it)}") }
        }
        Ranker.deadHostList().takeIf { it.isNotEmpty() }?.let { w("Dead servers (whole server hidden): ${it.joinToString(", ")}") }
    }

    /** This run's results added to every earlier run's (so reliability builds up over time). */
    private fun buildRanking(vm: AppViewModel): RankFile {
        val by = results.value.filter { it.counted }.groupBy { Triple(it.key, it.bucket, it.lang) }
        val fresh = by.map { (k, l) ->
            val oks = l.filter { it.ok }.mapNotNull { it.firstFrameMs }.sorted()
            val median = if (oks.isEmpty()) 20_000L else oks[oks.size / 2]
            // "Can jump inside" is known only from links that played (a refused link says nothing about the file).
            val played = l.filter { it.ok }
            val seekable = played.isEmpty() || played.count { it.seekable } * 2 >= played.size
            val parts = k.first.split('|')
            RankEntry(k.first, parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" }, k.second, k.third,
                l.size, oks.size, median, seekable, Ranker.scoreOf(oks.size, l.size, median, seekable), dead = oks.isEmpty() && l.all { it.dead })
        }
        // Results from tests before 0.9.21 are dropped once: the old test's stuck decoder failed most links after the
        // first ten ("decoder init failed"), so those counts blamed links and addons that work.
        val old = if (vm.texts.value["rankEpoch"] == "2") Ranker.file.entries else emptyList<RankEntry>().also {
            vm.setText("rankEpoch", "2"); w("(Older test results dropped once — the old test's decoder bug made them wrong.)")
        }
        val merged = (old + fresh).groupBy { "${it.key}|${it.bucket}|${it.lang}" }.map { (_, es) ->
            if (es.size == 1) es[0] else {
                val tests = es.sumOf { it.tests }
                val ok = es.sumOf { it.ok }
                val withOk = es.filter { it.ok > 0 }
                val median = if (withOk.isEmpty()) 20_000L else withOk.sumOf { it.medianMs * it.ok } / withOk.sumOf { it.ok }
                val seekable = es.filter { it.ok > 0 }.lastOrNull()?.seekable ?: true
                es.last().copy(tests = tests, ok = ok, medianMs = median, seekable = seekable,
                    score = Ranker.scoreOf(ok, tests, median, seekable), dead = ok == 0 && es.all { it.dead })
            }
        }
        return RankFile(System.currentTimeMillis(), Cloud.user.value?.email ?: "", merged)
    }

    /** The last three Source Test logs (kept on the phone, also after updates). */
    fun pastLogs(ctx: Context): List<File> {
        val d = File(ctx.filesDir, "source-tests").apply { mkdirs() }
        // Logs from older versions (kept in the cache folder) are moved here so they aren't lost.
        File(ctx.cacheDir, "logs").listFiles()?.filter { it.name.startsWith("streamx-source-test") }?.forEach { old ->
            runCatching { val to = File(d, old.name); if (!to.exists()) { old.copyTo(to); to.setLastModified(old.lastModified()) }; old.delete() }
        }
        return d.listFiles()?.filter { it.name.endsWith(".txt") }?.sortedByDescending { it.lastModified() }?.take(3).orEmpty()
    }

    private fun writeLog(ctx: Context): File? = runCatching {
        val d = File(ctx.filesDir, "source-tests").apply { mkdirs() }
        // Only the newest three are kept.
        d.listFiles()?.filter { it.name.endsWith(".txt") }?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
        val f = File(d, "streamx-source-test-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.txt")
        f.writeText(synchronized(log) { log.toString() })
        f
    }.getOrNull()

    /** Opens the share sheet for the log (save to Files / Drive, or send it). */
    fun shareLog(ctx: Context, f: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", f)
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { e -> ActLog.add("SHARE", "log share failed: ${e.message}"); Snack.show("Couldn't share the log — use Save or Copy") }
    }

    /** Saves the log into Downloads (Android 10+). Null = saved, else why not. */
    fun saveToDownloads(ctx: Context, f: File): String? {
        if (Build.VERSION.SDK_INT < 29) return "Use Share on this Android version"
        return runCatching {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return "Couldn't create the file"
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(f.readBytes()) }
            null
        }.getOrElse { it.message ?: "failed" }
    }
}

// ───────────── Screen ─────────────

@Composable
fun SourceTestScreen(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by SourceTester.running.collectAsStateWithLifecycle()
    val step by SourceTester.step.collectAsStateWithLifecycle()
    val line by SourceTester.line.collectAsStateWithLifecycle()
    val done by SourceTester.done.collectAsStateWithLifecycle()
    val total by SourceTester.total.collectAsStateWithLifecycle()
    val results by SourceTester.results.collectAsStateWithLifecycle()
    val show by SourceTester.showPlayer.collectAsStateWithLifecycle()
    val testPlayer by SourceTester.player.collectAsStateWithLifecycle()
    val logFile by SourceTester.logFile.collectAsStateWithLifecycle()
    val addonsAll by vm.addons.collectAsStateWithLifecycle()
    val labelTexts by vm.texts.collectAsStateWithLifecycle()
    val names = remember(addonsAll, labelTexts) {
        addonsAll.filter { vm.isAddonOn(it) && it.supports("stream", "movie", "tt0111161") }.map { it.label() }.distinct()
    }
    var chosen by remember(names) {
        mutableStateOf(names.filter { n -> n in vm.qsAddons() && !n.contains("aio", ignoreCase = true) }.toSet().ifEmpty { names.filter { !it.contains("aio", true) }.toSet() })
    }
    var msg by remember { mutableStateOf<String?>(null) }
    var reports by remember { mutableStateOf<List<FailReport>?>(null) }
    // The screen stays on while testing.
    val view = LocalView.current
    DisposableEffect(running) { view.keepScreenOn = running; onDispose { view.keepScreenOn = false } }
    // Leaving this screen while the player is shown: keep testing off-screen.
    DisposableEffect(Unit) { onDispose { SourceTester.showPlayer.value = false } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 60.dp)) {
        PageHeader("Source Test", onBack)
        Text("Picks 2 random movies and 2 random series (Hindi / English, any year), plays every link of the chosen addons until its first picture (max 20 s each), ranks the providers per quality and language, and writes a full log.",
            Modifier.padding(horizontal = 20.dp), fontSize = 13.sp, color = Muted)

        SectionLabel("Addons to test")
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                names.chunked(2).forEach { row ->
                    Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { n ->
                            val on = n in chosen
                            Text(n, Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Color(0xFF2E2E36) else Color.Transparent)
                                .border(1.dp, if (on) Accent else Color(0x33FFFFFF), RoundedCornerShape(14.dp))
                                .clickable(enabled = !running) { chosen = if (on) chosen - n else chosen + n }.padding(horizontal = 14.dp, vertical = 9.dp),
                                fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                        }
                    }
                }
                if (names.isEmpty()) Text("No stream addons are on.", fontSize = 14.sp, color = Muted)
            }
        }

        SectionLabel("Run")
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to "Run in background", true to "Show player").forEach { (v, label) ->
                        val on = show == v
                        Text(label, Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) Color(0xFF2E2E36) else Color.Transparent)
                            .border(1.dp, if (on) Accent else Color(0x33FFFFFF), RoundedCornerShape(14.dp))
                            .clickable { SourceTester.showPlayer.value = v }.padding(vertical = 11.dp),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                Text("Both use the phone's real player and hardware decoder. Keep Xstream open while it runs (the screen stays on).",
                    Modifier.padding(top = 8.dp), fontSize = 12.sp, color = Muted)
                Spacer(Modifier.height(12.dp))
                Text(if (running) "Stop test" else "Start test",
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(if (running) Color(0xFF3A2228) else BrandRed)
                        .clickable(enabled = running || chosen.isNotEmpty()) { if (running) SourceTester.stop() else SourceTester.start(vm, ctx, chosen) }
                        .padding(vertical = 14.dp),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (step.isNotEmpty()) {
                    Text(step, Modifier.padding(top = 12.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = Accent)
                    if (line.isNotEmpty()) Text(line, Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Color(0xFFC9CED9), maxLines = 2)
                }
            }
        }

        // The test player (shown mode): each link opens here, plays to its first picture, and closes.
        if (show && running) {
            Box(Modifier.padding(16.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                AndroidView(
                    factory = { c -> PlayerView(c).apply { useController = false } },
                    update = { v -> if (v.player !== testPlayer) v.player = testPlayer },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { v -> v.player = null },
                )
                Text(line, Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xB3000000))
                    .padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp, color = Color.White, maxLines = 2)
            }
        }

        if (results.isNotEmpty()) {
            SectionLabel("Results so far")
            SettingsCard {
                Column(Modifier.padding(16.dp)) {
                    val ok = results.count { it.ok }
                    Text("$ok of ${results.size} links played", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    listOf("4k" to "4K", "fhd" to "Full HD", "mhd" to "Mid HD", "low" to "Low").forEach { (b, label) ->
                        val inB = results.filter { it.bucket == b }
                        if (inB.isNotEmpty()) {
                            Text("$label · ${inB.count { it.ok }}/${inB.size} played", Modifier.padding(top = 10.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB6BDCC))
                            inB.groupBy { it.key }.map { (k, l) ->
                                val times = l.mapNotNull { it.firstFrameMs }.sorted()
                                Triple(k, l, if (times.isEmpty()) null else times[times.size / 2])
                            }.sortedWith(compareBy({ -(it.second.count { r -> r.ok }.toFloat() / it.second.size) }, { it.third ?: Long.MAX_VALUE })).take(8).forEach { (k, l, med) ->
                                Text("${Ranker.labelOf(k)} · ${l.count { it.ok }}/${l.size}" + (med?.let { " · ${"%.1f".format(it / 1000f)} s" } ?: "") +
                                    (if (l.any { !it.seekable }) " · not seekable" else ""), Modifier.padding(top = 3.dp), fontSize = 12.sp, color = Color(0xFFD5D8DF), fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        SectionLabel("Log & ranking")
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                val f = logFile
                val past = remember(f) { SourceTester.pastLogs(ctx) }
                Text(if (past.isEmpty()) "The log is ready when a test finishes (or is stopped). The last three are kept here." else "Your last ${past.size} test log(s):",
                    fontSize = 13.sp, color = Muted)
                past.forEach { lf ->
                    Text(SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.US).format(Date(lf.lastModified())) + "  ·  ${lf.length() / 1024} KB",
                        Modifier.padding(top = 10.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallBtn("Save") { msg = SourceTester.saveToDownloads(ctx, lf) ?: "Saved to Downloads: ${lf.name}" }
                        SmallBtn("Copy") {
                            msg = runCatching {
                                val text = lf.readText().let { if (it.length > 900_000) it.take(900_000) + "\n…(cut: use Save for the whole log)" else it }
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("Source Test log", text))
                                "Log copied — paste it anywhere"
                            }.getOrElse { "Couldn't copy (${it.javaClass.simpleName}) — use Save" }
                        }
                        SmallBtn("Share") { SourceTester.shareLog(ctx, lf) }
                    }
                }
                val rf = Ranker.file
                Text(if (rf.entries.isEmpty()) "No ranking in use yet." else "Ranking in use: ${rf.entries.size} groups · ${SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(rf.at))}" +
                    (if (rf.author.isNotBlank()) " · by ${rf.author}" else ""), Modifier.padding(top = 14.dp), fontSize = 13.sp)
                val texts by vm.texts.collectAsStateWithLifecycle()
                val hideDead = isOn(texts["hideDead"] ?: "yes")
                Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).clickable { vm.setText("hideDead", if (hideDead) "no" else "yes") }
                    .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Hide dead links in Sources", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Web pages, \"not found\" / \"bad request\" links and expired signed links are left out everywhere (${Ranker.deadGroupCount()} dead provider groups so far).",
                            fontSize = 12.sp, color = Muted)
                    }
                    androidx.compose.material3.Switch(hideDead, null)
                }
                val testedOnly = isOn(texts["testedOnly"] ?: "yes")
                Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).clickable { vm.setText("testedOnly", if (testedOnly) "no" else "yes") }
                    .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Tested sources only", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Sources and the player show only provider groups that passed the Source Test or were never tested. Groups that keep failing are hidden" +
                            (if (Ranker.deadHostList().isNotEmpty()) ". Dead servers: ${Ranker.deadHostList().joinToString(", ")}" else "") + ".",
                            fontSize = 12.sp, color = Muted)
                    }
                    androidx.compose.material3.Switch(testedOnly, null)
                }
                val autoManage = isOn(texts["srcAutoManage"] ?: "yes")
                Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).clickable { vm.setText("srcAutoManage", if (autoManage) "no" else "yes") }
                    .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Let Source Test arrange my addons", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("After each test: addons in ranked order, sources sorted by rank, and Quick Start = addons whose links play (70 %+, under 4 s).",
                            fontSize = 12.sp, color = Muted)
                    }
                    androidx.compose.material3.Switch(autoManage, null)
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallBtn("Upload ranking for everyone") { scope.launch { msg = Ranker.upload() ?: "Uploaded — every Xstream user gets this ranking." } }
                    SmallBtn("Reload shared") { scope.launch { msg = if (Ranker.fetchShared()) "Shared ranking checked." else "Couldn't read the shared ranking." } }
                }
                Row(Modifier.padding(top = 8.dp)) {
                    SmallBtn("Reset ranking on this phone") { Ranker.reset(); msg = "Ranking cleared — the next test starts from scratch." }
                }
                msg?.let { Text(it, Modifier.padding(top = 8.dp), fontSize = 13.sp, color = Color(0xFF7CD992)) }
            }
        }

        SectionLabel("Under observation")
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                Text("Provider groups that failed twice in a row while watching. They're tried last until the next Source Test re-tests them.",
                    fontSize = 13.sp, color = Muted)
                val local = Ranker.observed.toList()
                if (local.isEmpty()) Text("None on this phone.", Modifier.padding(top = 8.dp), fontSize = 13.sp)
                local.forEach { Text("• ${Ranker.labelOf(it)}", Modifier.padding(top = 4.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                Row(Modifier.padding(top = 10.dp)) {
                    SmallBtn("Load reports from all users") { scope.launch { val (l, e) = Ranker.allReports(); reports = l; if (e != null) msg = e } }
                }
                reports?.let { l ->
                    if (l.isEmpty()) Text("No reports.", Modifier.padding(top = 8.dp), fontSize = 13.sp)
                    l.take(30).forEach { r ->
                        Text("• ${r.label} · ${r.count} fails · ${r.device} · ${r.reason}", Modifier.padding(top = 4.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallBtn(text: String, onClick: () -> Unit) {
    Text(text, Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x1FFFFFFF)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}
