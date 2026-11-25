package com.example.streamnest

/** 0.9.10 (bgCap): one instance per play. */
class BgCap(private val wifi: Boolean) {
    @Volatile var hit = false; private set
    private var logged = false

    fun capBytes(videoBytes: Long, playMs: Long): Long {
        val early = if (playMs < 5 * 60_000L) 25L * 1024 * 1024 else Long.MAX_VALUE
        return minOf(videoBytes, early) * (if (wifi) 2 else 1)
    }

    /** false = pause background saving now. It resumes by itself when the video has caught up. */
    fun allow(bgBytes: Long, videoBytes: Long, playMs: Long): Boolean {
        val cap = capBytes(videoBytes, playMs)
        if (bgBytes < cap) return true
        if (bgBytes == 0L) return false   // nothing saved yet: only waiting for the video, not a cap hit
        hit = true
        if (!logged) {
            logged = true
            fun mb(b: Long) = "%.1f".format(b / 1048576.0)
            ActLog.add("PLAY", "background saving paused at ${mb(bgBytes)} MB (video ${mb(videoBytes)} MB) · cap ${mb(cap)} MB")
        }
        return false
    }

    fun dataLineSuffix() = " · cap hit: ${if (hit) "yes" else "no"}"
}
