package com.example.streamnest

import androidx.media3.common.Player
import kotlinx.coroutines.*

/** 0.9.10 (prepRescue): one rescue for a prepared paused player that gives no picture. true = the picture came. Once per play. */
suspend fun prepRescue(
    player: Player,
    hasSurface: () -> Boolean,
    reattach: suspend () -> Unit,
    firstFrame: () -> Boolean,
    linkAgeMin: Long?,
    startBackupSearch: () -> Job,
    stopBackupSearch: (Job) -> Unit,
): Boolean {
    val t0 = System.currentTimeMillis()
    val st = when (player.playbackState) {
        Player.STATE_IDLE -> "IDLE"; Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"; else -> "ENDED"
    }
    ActLog.add("PLAY", "prepared player stuck · state=$st · buffered=${player.totalBufferedDuration} · " +
        "tracks=${player.currentTracks.groups.size} · surface=${if (hasSurface()) "yes" else "no"} · link age=${linkAgeMin ?: "?"}")
    try {
        val backup = startBackupSearch()   // only collects links; it never switches the player
        if (player.playbackState == Player.STATE_IDLE || player.playerError != null) player.prepare()
        reattach()
        player.seekTo(player.currentPosition)
        player.play()
        val ok = withTimeoutOrNull(3_000L) { while (!firstFrame()) delay(50); true } == true
        if (ok) {
            ActLog.add("PLAY", "prepared player rescued in ${System.currentTimeMillis() - t0} ms")
            stopBackupSearch(backup)
            return true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        ActLog.add("PLAY", "rescue error: ${e.javaClass.simpleName}")
    }
    ActLog.add("PLAY", "rescue failed")
    return false
}
