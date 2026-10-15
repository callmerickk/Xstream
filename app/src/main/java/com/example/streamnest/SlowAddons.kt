package com.example.streamnest

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Addons whose server keeps collecting links after it first answers (CNCVerse). On the title page they are asked ONCE
 * (with the quick addons). They are asked again only on two triggers: nothing playing or being saved 10 s after the title
 * page opened ([poll], every 2 s until links come, at most 20 s), and the HD search after the first picture (player).
 * New links appear by themselves in Sources, Quick Start and instant play.
 */
object SlowAddons {
    /** videoId → links found so far. */
    val links = MutableStateFlow<Map<String, List<StreamItem>>>(emptyMap())
    private val jobs = ConcurrentHashMap<String, Job>()
    private val polling = ConcurrentHashMap.newKeySet<String>()
    private val askedAt = ConcurrentHashMap<String, Long>()
    private val played = ConcurrentHashMap.newKeySet<String>()

    fun isSlow(a: Addon): Boolean = a.manifest.name.contains("cncverse", true) || a.manifest.id.contains("cncverse", true) ||
        a.baseUrl.contains("cncverse", true)

    fun watching(id: String): Boolean = jobs[id]?.isActive == true

    /** One ask for this title (skipped when it was asked in the last minute, or a video of it already played). */
    fun watch(vm: AppViewModel, type: String, id: String, evenIfPlayed: Boolean = false) {
        if ((id in played && !evenIfPlayed) || watching(id)) return
        if (evenIfPlayed) played -= id
        if (System.currentTimeMillis() - (askedAt[id] ?: 0L) < 60_000) return
        start(vm, type, id, everyMs = 0, forMs = 0)
    }

    /** Asks every [everyMs] until links come (or [forMs] ran out). */
    fun poll(vm: AppViewModel, type: String, id: String, everyMs: Long = 2_000, forMs: Long = 20_000) {
        if (id in polling && watching(id)) return
        // Smart logic: the schedule started on the title page is already asking — don't restart it.
        if (AutoPlayRules.smart && watching(id)) return
        jobs.remove(id)?.cancel()
        played -= id
        start(vm, type, id, everyMs, forMs, poll = true)
    }

    private fun start(vm: AppViewModel, type: String, id: String, everyMs: Long, forMs: Long, poll: Boolean = false) {
        val slow = vm.slowStreamAddons(type, id)
        if (slow.isEmpty()) return
        // Only a few titles at a time (the ones you looked at last).
        if (jobs.size >= 3) jobs.entries.sortedBy { it.key == id }.take(jobs.size - 2).forEach { it.value.cancel(); jobs.remove(it.key) }
        if (poll) polling += id
        askedAt[id] = System.currentTimeMillis()
        if (AutoPlayRules.smart) {
            // Smart logic (phone 0.9.44): the timed schedule from Developer's Corner › Auto-play logic.
            jobs[id] = vm.viewModelScope.launch {
                try {
                    runCncSchedule(vm, type, id, onNew = { fresh ->
                        val cur = links.value[id].orEmpty()
                        val add = fresh.filter { g -> cur.none { it.stream.url == g.stream.url } }
                        if (add.isNotEmpty()) {
                            links.value = links.value + (id to (cur + add))
                            appendCached(id, add, vm.qsAddons())
                        }
                    })
                } finally { val me = coroutineContext[Job]; if (me != null && jobs.remove(id, me) && poll) polling -= id }
            }
            return
        }
        jobs[id] = vm.viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            try {
                while (isActive) {
                    for (a in slow) {
                        val got = runCatching {
                            Api.fetch<StreamResponse>(vm.streamUrl(a, type, id)).streams
                                .filter { !it.url.isNullOrBlank() }.map { StreamItem(a.label(), it).also { si -> StreamHeaders.remember(si) } }
                        }.getOrDefault(emptyList())
                        if (got.isNotEmpty()) {
                            val cur = links.value[id].orEmpty()
                            val fresh = got.filter { g -> cur.none { it.stream.url == g.stream.url } }
                            if (fresh.isNotEmpty()) {
                                links.value = links.value + (id to (cur + fresh))
                                appendCached(id, fresh, vm.qsAddons())
                            }
                        }
                    }
                    if (everyMs <= 0 || links.value[id].orEmpty().isNotEmpty() || System.currentTimeMillis() - t0 >= forMs) break
                    delay(everyMs)
                }
            } finally { val me = coroutineContext[Job]; if (me != null && jobs.remove(id, me) && poll) polling -= id }
        }
    }

    /** A video of this title started: stop asking. */
    fun played(id: String) {
        played += id
        jobs.remove(id)?.cancel()
    }
}
