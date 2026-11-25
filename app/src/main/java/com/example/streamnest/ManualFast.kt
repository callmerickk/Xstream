package com.example.streamnest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 0.9.7 (fastManual): a stream tapped by hand (title page › Sources, from the start) begins saving its first ~15 s in a
 * few pieces while the player opens; the player then reads them from the saved video. Stopped when the player closes
 * or another stream is tapped. Files only (playlists need nothing saved).
 */
object ManualFast {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null

    fun cancel() { job?.cancel(); job = null }

    fun warm(vm: AppViewModel, s: StreamItem) {
        if (!Feature.fastManual || BuildConfig.IS_TV) return
        val url = s.stream.url ?: return
        if (!url.startsWith("http", true) || isPlaylistLink(s, url) || !vm.upgradeAllowed()) return
        cancel()
        val len = (if (Feature.dataDiet) 12L else 24L) * 1024 * 1024
        job = scope.launch {
            val t0 = System.currentTimeMillis()
            StreamCache.register(s); StreamHeaders.remember(s)
            val raced = StreamCache.racedProbe(url) != null
            // 0.9.10 (burstPlan): the first 8 MB with many pieces (the switch is checked inside saveParts).
            val r = withTimeoutOrNull(20_000) { Result.success(StreamCache.saveParts(vm.appContext, url, 0L, len, 4, null, burstHead = true) { }) }
            val ok = r != null && r.getOrNull() == null
            if (ok && Feature.dataDiet) DataDiet.addBackground(len)
            ActLog.add("PLAY", "manual start · head ${len / 1048576} MB ${if (ok) "saved" else "not saved"} in ${System.currentTimeMillis() - t0} ms · raced probe ${if (raced) "yes" else "no"}")
        }
    }
}
