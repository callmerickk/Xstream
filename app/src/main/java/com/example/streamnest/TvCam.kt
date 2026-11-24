package com.example.streamnest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * StreamX TV 0.8.7 — cinema copies (cam). A film that is already out at home (web / disc / TV, TMDB release dates) should
 * play a WEB-DL / WEBRip / BluRay copy, not a cinema recording. A link is a cinema copy when its name says so
 * (CAM, HDCAM, TS, HDTS, TC, PreDVD) or when it comes from Netflix / Prime Video / Hotstar but TMDB doesn't list the film
 * on that service in India (The Odyssey on "Netflix" was a 1 Mbps cam).
 */
object TvCam {
    /** imdb → (out at home?, the services that have it in India, lower-case names). */
    private val info = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean?, Set<String>?>>()

    private val camRx = Regex("(?i)(^|[^a-z0-9])(cam|camrip|hdcam|hd-cam|ts|hdts|hd-ts|telesync|tc|hdtc|telecine|pre-?dvd|predvdrip)([^a-z0-9]|$)")

    fun namedCam(s: StreamItem): Boolean = listOfNotNull(s.stream.name, s.stream.title, s.stream.description, s.stream.behaviorHints?.filename)
        .any { camRx.containsMatchIn(it) }

    fun known(imdb: String): Pair<Boolean?, Set<String>?>? = info[imdb]

    /** Reads (once per title) whether the film is out at home and which services have it in India. */
    suspend fun load(vm: AppViewModel, imdb: String) {
        if (!imdb.startsWith("tt") || info.containsKey(imdb)) return
        val key = vm.tmdbKey().ifEmpty { return }
        val r = withTimeoutOrNull(6_000) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val out = CinemaOnly.isOut(key, imdb)
                    val f = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/find/$imdb?api_key=$key&external_source=imdb_id")) as JsonObject
                    val id = ((f["movie_results"] as? JsonArray)?.firstOrNull() as? JsonObject)?.let { (it["id"] as? JsonPrimitive)?.contentOrNull }
                    val services = id?.let {
                        val w = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/movie/$it/watch/providers?api_key=$key")) as JsonObject
                        val inn = ((w["results"] as? JsonObject)?.get("IN") as? JsonObject)
                        listOf("flatrate", "free", "ads", "rent", "buy").flatMap { k -> (inn?.get(k) as? JsonArray).orEmpty() }
                            .mapNotNull { ((it as? JsonObject)?.get("provider_name") as? JsonPrimitive)?.contentOrNull?.lowercase() }.toSet()
                    }
                    out to services
                }.getOrNull()
            }
        } ?: return
        info[imdb] = r
        ActLog.add("PLAY", "TV: ${if (r.first == true) "out at home" else if (r.first == false) "only in cinemas" else "release unknown"}" +
            (r.second?.let { s -> " · in India on: ${s.joinToString().ifEmpty { "no service" }}" } ?: ""))
    }

    /** True when [s] is (very likely) a cinema copy of a film that is already out at home. */
    fun suspect(imdb: String, s: StreamItem): Boolean {
        val (out, services) = info[imdb] ?: (null to null)
        if (out != true) return false
        if (namedCam(s)) return true
        if (!s.isOtt() || services == null) return false
        val a = s.addonName.lowercase()
        val svc = when { "netflix" in a -> "netflix"; "prime" in a -> "prime"; "hotstar" in a -> "hotstar"; else -> return false }
        return services.none { svc in it }
    }
}
