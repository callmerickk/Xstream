package com.example.streamnest

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

object BurstPlan {
    const val BURST_BYTES = 8L * 1024 * 1024
    private val conns = Semaphore(12)                  // phone-wide connection budget
    val stopped = AtomicBoolean(false)                 // set true on first picture; reset per play

    fun reset() = stopped.set(false)
    fun onFirstFrame() = stopped.set(true)

    /** noBurst(host) should read the 24 h flag from HostStats. */
    fun parts(host: String, offset: Long, wifi: Boolean, normal: Int, noBurst: (String) -> Boolean): Int {
        if (!Feature.burstPlan || BuildConfig.IS_TV) return normal
        if (host.contains("pixeldrain", true)) return 2
        if (stopped.get() || offset >= BURST_BYTES || noBurst(host)) return normal
        return if (wifi) 12 else 8
    }

    fun acquire(n: Int): Int { var got = 0; while (got < n && conns.tryAcquire()) got++; return got }
    fun release(n: Int) = conns.release(n)

    fun logDone(n: Int, host: String, mbPerSec: Double) =
        ActLog.add("PLAY", "burst: $n pieces · $host · ${"%.1f".format(mbPerSec)} MB/s")
    fun logRefused(host: String) =
        ActLog.add("PLAY", "burst refused by $host → 4 pieces")
}
