package com.example.streamnest

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 0.9.4 (Feature.homeSteady): a Netflix-style Home — what is on screen never changes while you look at it.
 *  - Rows: the saved Home shows first (no network wait). Rows already shown stay as they are for the session; a row
 *    that is new joins at the end; the fresh rows are saved and shown the next time Home opens (HomeFreeze).
 *  - Top 10: each numbered row's order is fixed for the session — the order saved last time is shown at once (no
 *    waiting for the Hindi titles), and today's order is saved for next time.
 *  - Posters: while the profile page shows, the first screen's posters are fetched small (TMDB w342) into the picture
 *    cache, and each card's own colour (worked out once from the small picture, kept on the phone) is shown instead of
 *    grey until its picture is drawn.
 * Off: none of this runs, nothing is read or written.
 */
object HomeSteady {
    @Serializable private data class Saved(
        val top: Map<String, List<Meta>> = emptyMap(),
        val posters: Map<String, String> = emptyMap(),
        val tints: Map<String, Int> = emptyMap(),
    )

    private var file: File? = null
    private var pid: String? = null
    /** Top 10 order shown this session (row → titles). */
    private val sessionTop = ConcurrentHashMap<String, List<Meta>>()
    /** Top 10 order worked out this session, for the next open. */
    private val nextTop = ConcurrentHashMap<String, List<Meta>>()
    /** Card colours by title (ARGB), drawn instead of grey. Compose reads it, so a colour shows as soon as it is known. */
    val tints = mutableStateMapOf<String, Int>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    /** The profile's saved Top 10 orders, tall posters and colours (a small file; read while the profile page shows). */
    fun load(ctx: Context, profileId: String) {
        if (!Feature.homeSteady || BuildConfig.IS_TV) return
        if (pid == profileId) return
        pid = profileId
        sessionTop.clear(); nextTop.clear()
        val f = File(ctx.filesDir, "homesteady_$profileId.json")
        file = f
        val s = runCatching { Api.json.decodeFromString<Saved>(f.readText()) }.getOrNull() ?: Saved()
        sessionTop.putAll(s.top)
        s.posters.forEach { (id, url) -> TmdbPosters.remember(Meta(id = id), url) }
        android.os.Handler(android.os.Looper.getMainLooper()).post { tints.clear(); tints.putAll(s.tints) }
    }

    /** The Top 10 order for this row this session (null = none yet: the row works it out, then [keepTop] fixes it). */
    fun top(row: String): List<Meta>? = if (Feature.homeSteady) sessionTop[row] else null

    /** A freshly worked-out Top 10: fixed for the session if the row had none; always saved for the next open. */
    fun keepTop(row: String, list: List<Meta>) {
        if (!Feature.homeSteady || list.isEmpty()) return
        sessionTop.putIfAbsent(row, list)
        if (nextTop[row]?.map { it.id } == list.map { it.id }) return
        nextTop[row] = list
        saveSoon()
    }

    /** The small poster size (w342) of a TMDB poster link; other links unchanged. */
    fun small(url: String?): String? = if (!Feature.homeSteady) url else url?.replace(Regex("/t/p/(w500|w780|original)/"), "/t/p/w342/")

    /** The card's colour while its picture loads: its own colour when known, else the theme's surface. */
    fun cardBg(m: Meta): Color = if (!Feature.homeSteady) Surface1 else tints[m.id]?.let { Color(it) } ?: Surface1

    private fun saveSoon() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2_000)
            val f = file ?: return@launch
            val slim = nextTop.mapValues { (_, l) -> l.map { it.copy(videos = null, cast = null, director = null, writer = null, trailerStreams = null, description = null) } }
            val posters = slim.values.flatten().mapNotNull { m -> TmdbPosters.cached(m)?.let { m.id to it } }.toMap()
            val t = HashMap(tints).entries.toList().takeLast(600).associate { it.key to it.value }
            runCatching { f.writeText(Api.json.encodeToString(Saved.serializer(), Saved(slim, posters, t))) }
        }
    }

    /**
     * While the profile page shows: the tall posters of the first two (numbered) rows are looked up and fetched small,
     * and every first-screen card's colour is worked out from its small picture (once; kept on the phone).
     */
    fun warm(ctx: Context, rows: List<Shelf>) {
        if (!Feature.homeSteady || BuildConfig.IS_TV) return
        scope.launch {
            val first = rows.take(2).flatMap { r -> (sessionTop[r.title] ?: r.items).take(10) }
            // Tall posters: the saved ones are known already; the rest asked now (one quick TMDB call each).
            val tall = first.mapNotNull { m -> (TmdbPosters.cached(m) ?: kotlinx.coroutines.withTimeoutOrNull(4_000) { runCatching { TmdbPosters.portrait(m) }.getOrNull() })?.let { m to small(it)!! } }
            Posters.warm(ctx, tall.map { it.second })
            val cards = rows.take(4).flatMap { r -> r.items.take(12).map { m -> m to hiResArt(m.poster ?: hdPoster(m)) } }
            colours(ctx, tall + cards.mapNotNull { (m, u) -> u?.let { m to it } })
            if (tall.isNotEmpty()) saveSoon()
        }
    }

    /** Each title's colour from a 16-pixel copy of its picture (darkened so white text stays readable). */
    private fun colours(ctx: Context, items: List<Pair<Meta, String>>) {
        val loader = coil.Coil.imageLoader(ctx)
        items.distinctBy { it.first.id }.filter { it.first.id !in tints }.take(80).forEach { (m, url) ->
            loader.enqueue(coil.request.ImageRequest.Builder(ctx).data(url).size(16).allowHardware(false)
                .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                .target(onSuccess = { d ->
                    val c = runCatching { average((d as? android.graphics.drawable.BitmapDrawable)?.bitmap) }.getOrNull()
                    if (c != null) android.os.Handler(android.os.Looper.getMainLooper()).post { tints[m.id] = c; saveSoon() }
                }).build())
        }
    }

    private fun average(b: android.graphics.Bitmap?): Int? {
        if (b == null || b.width == 0 || b.height == 0) return null
        var r = 0L; var g = 0L; var bl = 0L; var n = 0L
        for (x in 0 until b.width) for (y in 0 until b.height) {
            val p = b.getPixel(x, y)
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; bl += p and 0xFF; n++
        }
        if (n == 0L) return null
        fun dark(v: Long) = ((v / n) * 0.55).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (dark(r) shl 16) or (dark(g) shl 8) or dark(bl)
    }
}
