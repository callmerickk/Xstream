package com.example.streamnest

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar
import java.util.Locale

/** What we know about one song for a profile's stats. */
@Serializable
data class MusicSongStat(
    val title: String = "",
    val artist: String = "",
    val thumb: String = "",
    val ms: Long = 0L,
    val finished: Int = 0,
)

/** Everything the profile page shows, saved per profile. */
@Serializable
data class MusicStatsData(
    val since: Long = 0L,
    val totalMs: Long = 0L,
    val finished: Int = 0,
    /** "yyyy-MM-dd" to milliseconds listened that day. */
    val dayMs: Map<String, Long> = emptyMap(),
    /** Milliseconds listened in each hour of the day (24 entries). */
    val hourMs: List<Long> = List(24) { 0L },
    val songs: Map<String, MusicSongStat> = emptyMap(),
    val artists: Map<String, Long> = emptyMap(),
)

object MusicStats {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _data = MutableStateFlow(MusicStatsData())
    val data: StateFlow<MusicStatsData> = _data

    private val lock = Mutex()
    @Volatile private var id: String? = null
    @Volatile private var dirty = false

    private fun file(ctx: Context, profileId: String): File {
        val dir = File(ctx.filesDir, "music-stats").apply { mkdirs() }
        return File(dir, profileId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")
    }

    fun key(c: Calendar): String =
        String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

    /** Load a profile's stats. Saves the previous profile first. Safe to call many times. */
    suspend fun open(ctx: Context, profileId: String) = lock.withLock {
        if (id == profileId) return@withLock
        writeNow(ctx)
        val app = ctx.applicationContext
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val f = file(app, profileId)
                if (f.exists()) json.decodeFromString<MusicStatsData>(f.readText()) else null
            }.getOrNull()
        }
        _data.value = loaded ?: MusicStatsData(since = System.currentTimeMillis())
        id = profileId
        dirty = false
        MusicLog.add("Stats: opened profile $profileId (${if (loaded == null) "new, starts empty" else "loaded from file"})")
    }

    suspend fun save(ctx: Context) = lock.withLock { writeNow(ctx) }

    private suspend fun writeNow(ctx: Context) {
        val pid = id ?: return
        if (!dirty) return
        dirty = false
        val text = json.encodeToString(_data.value)
        val app = ctx.applicationContext
        withContext(Dispatchers.IO) {
            runCatching {
                val f = file(app, pid)
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }.onFailure { MusicLog.add("Stats: could not save (${it.message})") }
        }
    }

    private fun mainArtist(a: String) = a.split(",", "&", " • ", " · ").first().trim()

    /** Adds listened time to today, to this hour, to the song and to the artist. */
    fun listened(profileId: String, ms: Long, trackId: String, title: String, artist: String, thumb: String) {
        if (id != profileId || ms <= 0L) return
        val cal = Calendar.getInstance()
        val day = key(cal)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val who = mainArtist(artist)
        _data.update { d ->
            val hours = d.hourMs.toMutableList().also {
                while (it.size < 24) it.add(0L)
                it[hour] = it[hour] + ms
            }
            val old = d.songs[trackId] ?: MusicSongStat()
            val song = old.copy(
                title = title.ifBlank { old.title },
                artist = artist.ifBlank { old.artist },
                thumb = thumb.ifBlank { old.thumb },
                ms = old.ms + ms,
            )
            d.copy(
                totalMs = d.totalMs + ms,
                dayMs = d.dayMs + (day to ((d.dayMs[day] ?: 0L) + ms)),
                hourMs = hours,
                songs = d.songs + (trackId to song),
                artists = if (who.isBlank()) d.artists else d.artists + (who to ((d.artists[who] ?: 0L) + ms)),
            ).pruned(trackId)
        }
        dirty = true
    }

    fun songFinished(profileId: String, trackId: String) {
        if (id != profileId) return
        _data.update { d ->
            val s = d.songs[trackId] ?: MusicSongStat()
            d.copy(finished = d.finished + 1, songs = d.songs + (trackId to s.copy(finished = s.finished + 1)))
        }
        dirty = true
    }

    /** Keeps the file small: only trims when a list grows far beyond what the page can use. */
    private fun MusicStatsData.pruned(keep: String): MusicStatsData {
        var s = songs
        var a = artists
        var days = dayMs
        if (s.size > 500) {
            val top = s.entries.sortedByDescending { it.value.ms }.take(400).associate { it.key to it.value }
            s = if (keep in top || keep !in s) top else top + (keep to s.getValue(keep))
        }
        if (a.size > 300) a = a.entries.sortedByDescending { it.value }.take(250).associate { it.key to it.value }
        if (days.size > 400) days = days.entries.sortedBy { it.key }.takeLast(365).associate { it.key to it.value }
        return if (s === songs && a === artists && days === dayMs) this else copy(songs = s, artists = a, dayMs = days)
    }
}

/**
 * Counts listening for the stats page. Put it once in the Music screen.
 * Once a second: if a song is playing, add one second. A song counts as "finished"
 * once it has reached 90% of its length. Saves every 15 seconds and when it leaves.
 * It only counts while the Music screen is open.
 */
@Composable
fun MusicStatsTracker(profileId: String) {
    val app = LocalContext.current.applicationContext
    LaunchedEffect(profileId) {
        MusicStats.open(app, profileId)
        var lastId: String? = null
        var counted: String? = null
        var sinceSave = 0
        try {
            while (true) {
                delay(1000)
                val n = MusicPlayer.now.value
                val t = n.track
                if (t == null) { lastId = null; continue }
                if (t.id != lastId) { lastId = t.id; counted = null }
                if (n.playing && !n.buffering) {
                    MusicStats.listened(
                        profileId, 1000L, t.id, t.title.orEmpty(), t.artist.orEmpty(),
                        t.artworkURL?.toString().orEmpty(),
                    )
                }
                val dur = n.durMs
                if (dur > 30_000) {
                    if (counted != t.id && n.posMs >= dur * 9 / 10) {
                        counted = t.id
                        MusicStats.songFinished(profileId, t.id)
                    } else if (counted == t.id && n.posMs < dur / 5) {
                        counted = null // the song was started again
                    }
                }
                sinceSave++
                if (sinceSave >= 15) { sinceSave = 0; MusicStats.save(app) }
            }
        } finally {
            withContext(NonCancellable) { MusicStats.save(app) }
        }
    }
}
