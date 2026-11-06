package com.example.streamnest

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Xstream Music in the Activity log ("MUSIC" lines, all of them sent to the developer's cloud log): every song started
 * and why, how long it took to start, buffering, errors, songs skipped and how far they got, pauses (by you, a call,
 * headphones out), quality changes, what you tapped in the player and the library, searches and how long pages took.
 * Logging never throws: a failure here can't stop the music.
 */
object MusicLog {
    fun add(text: String) { runCatching { ActLog.add("MUSIC", text) } }

    fun t(ms: Long): String { val s = (ms.coerceAtLeast(0) / 1000); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

    private fun name(m: MediaItem?): String {
        val md = m?.mediaMetadata ?: return "?"
        val a = md.artist?.toString().orEmpty()
        return "\"${md.title ?: m.mediaId}\"" + (if (a.isNotBlank()) " — $a" else "")
    }

    private fun kind(m: MediaItem?): String {
        val u = m?.localConfiguration?.uri ?: return "?"
        return when (u.scheme) {
            "ytm" -> "YouTube Music song"
            "ytmpd" -> "YouTube Music video"
            "xlive" -> "Live server"
            else -> u.host ?: u.scheme ?: "?"
        }
    }

    /** Watches the music player itself (in the music service). */
    fun attach(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            var songAt = 0L
            var started = false
            var bufAt = 0L
            var dur = 0L

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = safe {
                val why = when (reason) {
                    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "next in queue (previous one ended)"
                    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "chosen / skipped to"
                    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
                    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "new queue"
                    else -> "reason $reason"
                }
                songAt = SystemClock.elapsedRealtime(); started = false; bufAt = 0L; dur = 0L
                if (mediaItem != null) add("start · ${name(mediaItem)} · ${kind(mediaItem)} · $why · queue ${player.currentMediaItemIndex + 1}/${player.mediaItemCount}")
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = safe {
                if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
                    val how = if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) "finished" else "left"
                    if (how == "left") add("left ${name(oldPosition.mediaItem)} at ${t(oldPosition.positionMs)}${if (dur > 0) " of ${t(dur)}" else ""}")
                    else add("finished ${name(oldPosition.mediaItem)}")
                } else if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    add("jump ${t(oldPosition.positionMs)} → ${t(newPosition.positionMs)}")
                }
            }

            override fun onPlaybackStateChanged(state: Int) = safe {
                val now = SystemClock.elapsedRealtime()
                if (player.duration > 0) dur = player.duration
                when (state) {
                    Player.STATE_READY -> {
                        if (!started && player.playWhenReady) {
                            started = true
                            val f = player.audioFormat
                            val audio = f?.let { listOfNotNull(it.sampleMimeType?.substringAfter('/'), it.bitrate.takeIf { b -> b > 0 }?.let { b -> "${b / 1000} kbps" },
                                it.sampleRate.takeIf { r -> r > 0 }?.let { r -> "${r / 1000} kHz" }).joinToString(" ") }.orEmpty()
                            val v = player.videoFormat?.let { " · video ${it.height}p" }.orEmpty()
                            add("= ✓ playing ${name(player.currentMediaItem)} after %.2f s".format((now - songAt) / 1000f) +
                                (if (audio.isNotBlank()) " · $audio" else "") + v + " · quality ${if (MusicQuality.hi) "high" else "low"}")
                        } else if (bufAt > 0) {
                            add("playing again after %.1f s".format((now - bufAt) / 1000f)); bufAt = 0L
                        }
                    }
                    Player.STATE_BUFFERING -> if (started) { bufAt = now; add("buffering at ${t(player.currentPosition)} · ${name(player.currentMediaItem)}") }
                    Player.STATE_ENDED -> add("queue ended")
                    else -> {}
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = safe {
                val why = when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "by you"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "another app / a call took the sound"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "headphones / speaker disconnected"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "from the notification / another device"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> return@safe
                    else -> "reason $reason"
                }
                add("${if (playWhenReady) "resumed" else "paused"} at ${t(player.currentPosition)} · $why")
            }

            override fun onPlayerError(error: PlaybackException) = safe {
                val cause = generateSequence(error.cause) { it.cause }.lastOrNull()
                add("can't play ${name(player.currentMediaItem)} · ${error.errorCodeName}" + (cause?.let { " · ${it.javaClass.simpleName}: ${it.message?.take(160)}" } ?: "") +
                    " · failed after %.1f s".format((SystemClock.elapsedRealtime() - songAt) / 1000f))
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = safe {
                if (!started) return@safe
                val hasVideo = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                add("tracks now: ${if (hasVideo) "audio + video" else "audio only"}")
            }

            override fun onShuffleModeEnabledChanged(on: Boolean) = safe { add("shuffle ${if (on) "on" else "off"}") }
            override fun onRepeatModeChanged(mode: Int) = safe { add("repeat " + when (mode) { Player.REPEAT_MODE_ONE -> "one"; Player.REPEAT_MODE_ALL -> "all"; else -> "off" }) }
        })
    }

    private inline fun safe(block: () -> Unit) { runCatching(block) }
}
