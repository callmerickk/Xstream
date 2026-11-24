package com.example.streamnest

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class MusicPlaylist(val id: String, val name: String, val items: List<YtmItem> = emptyList())
@Serializable
data class MusicHistoryItem(val item: YtmItem, val playedAt: Long)
@Serializable
data class MusicProfileData(
    val liked: List<YtmItem> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(),
    val history: List<MusicHistoryItem> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val subscriptions: List<YtmItem> = emptyList(),
    val pinned: List<YtmItem> = emptyList(),
    val playCounts: Map<String, Int> = emptyMap(),
)

object MusicLibraryStore {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var profileId: String? = null
    @Volatile private var current = MusicProfileData()

    fun active(): MusicProfileData = current

    suspend fun open(context: Context, id: String): MusicProfileData = withContext(Dispatchers.IO) {
        lock.withLock {
            if (profileId == id) return@withLock current
            val safe = id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(80).ifBlank { "default" }
            val file = File(File(context.filesDir, "music"), safe + ".json")
            val data = runCatching { if (file.isFile) json.decodeFromString<MusicProfileData>(file.readText()) else MusicProfileData() }
                .getOrDefault(MusicProfileData())
            current = data
            profileId = id
            data
        }
    }

    private suspend fun edit(context: Context, block: (MusicProfileData) -> MusicProfileData): MusicProfileData =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val next = block(current)
                val safe = profileId.orEmpty().ifBlank { "default" }.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(80)
                val dir = File(context.filesDir, "music").apply { mkdirs() }
                val target = File(dir, safe + ".json")
                val temp = File(dir, safe + ".json.tmp")
                temp.writeText(json.encodeToString(MusicProfileData.serializer(), next))
                if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) }
                current = next
                next
            }
        }

    suspend fun toggleLiked(context: Context, item: YtmItem): Boolean {
        var added = false
        edit(context) { old ->
            added = old.liked.none { it.id == item.id }
            old.copy(liked = if (added) listOf(item) + old.liked else old.liked.filterNot { it.id == item.id })
        }
        return added
    }

    suspend fun createPlaylist(context: Context, name: String): MusicPlaylist {
        val playlist = MusicPlaylist(java.util.UUID.randomUUID().toString(), name.trim().ifBlank { "New playlist" })
        edit(context) { it.copy(playlists = listOf(playlist) + it.playlists) }
        return playlist
    }

    suspend fun addToPlaylist(context: Context, playlistId: String, item: YtmItem) = edit(context) { old ->
        old.copy(playlists = old.playlists.map { p ->
            if (p.id == playlistId && p.items.none { it.id == item.id }) p.copy(items = p.items + item) else p
        })
    }

    suspend fun recordPlay(context: Context, item: YtmItem) = edit(context) { old ->
        old.copy(
            history = (listOf(MusicHistoryItem(item, System.currentTimeMillis())) + old.history).distinctBy { it.item.id }.take(500),
            playCounts = old.playCounts + (item.id to ((old.playCounts[item.id] ?: 0) + 1)),
        )
    }

    suspend fun addRecentSearch(context: Context, query: String) = edit(context) { old ->
        val q = query.trim().take(120)
        if (q.isBlank()) old else old.copy(recentSearches = (listOf(q) + old.recentSearches.filterNot { it.equals(q, true) }).take(20))
    }

    suspend fun togglePinned(context: Context, item: YtmItem) = edit(context) { old ->
        old.copy(pinned = if (old.pinned.any { it.id == item.id }) old.pinned.filterNot { it.id == item.id } else (listOf(item) + old.pinned).take(27))
    }

    suspend fun toggleSubscription(context: Context, artist: YtmItem) = edit(context) { old ->
        old.copy(subscriptions = if (old.subscriptions.any { it.id == artist.id }) old.subscriptions.filterNot { it.id == artist.id } else listOf(artist) + old.subscriptions)
    }

    fun speedDial(): List<YtmItem> = (current.pinned + current.playCounts.entries.sortedByDescending { it.value }
        .mapNotNull { count -> (current.liked + current.history.map { it.item } + current.playlists.flatMap { it.items }).firstOrNull { it.id == count.key } })
        .distinctBy { it.id }.take(27)
}
