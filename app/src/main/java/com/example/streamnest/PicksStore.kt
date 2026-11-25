package com.example.streamnest

import android.content.Context
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale

/** 0.9.7 (smartCache): "Picked for you" stays the same all day (per profile) unless the taste moved a lot; kept in app storage. */
object PicksStore {
    @Serializable data class Saved(val day: String, val profile: String, val taste: Map<String, Double>, val items: List<Meta>)

    private fun day() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
    private fun f(ctx: Context, profile: String) = AppStore.file(ctx, "picks-" + profile.filter { it.isLetterOrDigit() } + ".json")

    /** The profile's taste: genre weights from likes (more for recent ones), My List and dislikes. */
    fun taste(vm: AppViewModel): Map<String, Double> {
        val m = HashMap<String, Double>()
        val liked = vm.votes.value.values.filter { it.v > 0 }.sortedByDescending { it.at }
        liked.forEachIndexed { i, l -> l.genres.forEach { g -> m[g] = (m[g] ?: 0.0) + (if (i < 5) 3.0 else 2.0) } }
        vm.myList.value.forEach { x -> x.genres.orEmpty().forEach { g -> m[g] = (m[g] ?: 0.0) + 1.0 } }
        vm.votes.value.values.filter { it.v < 0 }.forEach { d -> d.genres.forEach { g -> m[g] = (m[g] ?: 0.0) - 1.0 } }
        return m
    }

    fun similarity(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (k in a.keys + b.keys) { val x = a[k] ?: 0.0; val y = b[k] ?: 0.0; dot += x * y; na += x * x; nb += y * y }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / Math.sqrt(na * nb)
    }
    /** Today's picks of this profile, or null (new day, or taste moved: similarity < 0.85). */
    fun load(ctx: Context, profile: String, taste: Map<String, Double>): List<Meta>? = runCatching {
        if (!Feature.smartCache) return null
        val s = Api.json.decodeFromString(Saved.serializer(), f(ctx, profile).readText())
        s.takeIf { it.day == day() && it.profile == profile && it.items.isNotEmpty() && similarity(it.taste, taste) >= 0.85 }?.items
    }.getOrNull()
    fun save(ctx: Context, profile: String, taste: Map<String, Double>, items: List<Meta>) {
        if (!Feature.smartCache || items.isEmpty()) return
        runCatching { f(ctx, profile).writeText(Api.json.encodeToString(Saved.serializer(), Saved(day(), profile, taste, items.map { it.copy(videos = null) }))) }
    }
}
