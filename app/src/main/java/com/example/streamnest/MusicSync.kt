package com.example.streamnest

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Sends the signed-in account's changes back to YouTube Music.
 * Every function does nothing when the open profile isn't signed in, so with the
 * musicOrbit switch off (or "Xstream's own library" chosen) nothing is ever sent.
 * Calls run in the background and only write plain-word results to MusicLog.
 */
object MusicSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    /** Walks a JSON tree: strings pick object keys, ints pick array items. Null if any step is missing. */
    private fun JsonElement?.at(vararg path: Any): JsonElement? {
        var cur: JsonElement? = this
        for (p in path) {
            cur = when {
                cur == null -> return null
                p is String -> (cur as? JsonObject)?.get(p)
                p is Int -> (cur as? JsonArray)?.getOrNull(p)
                else -> null
            }
        }
        return cur
    }

    private fun bareVideoId(id: String): String? =
        if (id.startsWith("yt:")) id.removePrefix("yt:").takeIf { it.isNotBlank() } else null

    private suspend fun post(endpoint: String, extra: JsonObject, cookie: String): JsonObject? =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject {
                    put("context", buildJsonObject {
                        put("client", buildJsonObject {
                            put("clientName", "WEB_REMIX")
                            put("clientVersion", YtmAuth.CLIENT_VERSION)
                            put("hl", "en")
                            put("gl", "US")
                        })
                    })
                    extra.forEach { (k, v) -> put(k, v) }
                }.toString()
                val req = YtmAuth.apply(
                    Request.Builder()
                        .url("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
                        .header("Referer", YtmAuth.ORIGIN + "/")
                        .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                        .post(body.toRequestBody("application/json".toMediaType())),
                    cookie,
                ).build()
                YtDirect.http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        MusicLog.add("Sync: $endpoint answered ${r.code}")
                        return@runCatching null
                    }
                    json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                }
            }.onFailure { MusicLog.add("Sync: $endpoint failed (${it.javaClass.simpleName})") }.getOrNull()
        }

    /** state: 1 = like, -1 = dislike, 0 = remove the like or dislike. */
    fun rate(trackId: String, state: Int) {
        val cookie = MusicAccount.activeCookie() ?: return
        val vid = bareVideoId(trackId) ?: return
        val ep = when (state) { 1 -> "like/like"; -1 -> "like/dislike"; else -> "like/removelike" }
        scope.launch {
            val ok = post(ep, buildJsonObject { put("target", buildJsonObject { put("videoId", vid) }) }, cookie) != null
            MusicLog.add("Sync: ${when (state) { 1 -> "like"; -1 -> "dislike"; else -> "remove like" }} ${if (ok) "sent to your account" else "did not go through"}")
        }
    }

    /** playlistId can be the "VL…" browse id or the plain "PL…" id. */
    fun addToPlaylist(playlistId: String, trackId: String) {
        val cookie = MusicAccount.activeCookie() ?: return
        val vid = bareVideoId(trackId) ?: return
        val pl = playlistId.removePrefix("VL").takeIf { it.isNotBlank() } ?: return
        scope.launch {
            val body = buildJsonObject {
                put("playlistId", pl)
                put("actions", buildJsonArray {
                    add(buildJsonObject {
                        put("action", "ACTION_ADD_VIDEO")
                        put("addedVideoId", vid)
                        put("dedupeOption", "DEDUPE_OPTION_SKIP")
                    })
                })
            }
            val ok = post("browse/edit_playlist", body, cookie) != null
            MusicLog.add("Sync: add to playlist ${if (ok) "sent to your account" else "did not go through"}")
        }
    }

    /**
     * Asks YouTube who the cookie belongs to and stores the name for Settings.
     * Takes the cookie as a parameter so it works the moment sign-in finishes.
     * Runs on its own scope, so it survives the sign-in page closing.
     */
    fun refreshLabel(ctx: Context, profileId: String, cookie: String) {
        val app = ctx.applicationContext
        scope.launch {
            val resp = post(
                "account/account_menu",
                buildJsonObject {
                    put("deviceTheme", "DEVICE_THEME_SELECTED")
                    put("userInterfaceTheme", "USER_INTERFACE_THEME_DARK")
                },
                cookie,
            )
            if (resp == null) {
                MusicLog.add("Sync: could not read the account name (no answer)")
                return@launch
            }
            val loggedIn = (resp.at("responseContext", "serviceTrackingParams") as? JsonArray)
                ?.flatMap { (it.at("params") as? JsonArray) ?: JsonArray(emptyList()) }
                ?.firstOrNull { it.at("key")?.jsonPrimitive?.contentOrNull == "logged_in" }
                ?.at("value")?.jsonPrimitive?.contentOrNull
            if (loggedIn == "0") MusicLog.add("Sync: YouTube says this sign-in cookie is not logged in")
            val name = resp.at(
                "actions", 0, "openPopupAction", "popup", "multiPageMenuRenderer", "header",
                "activeAccountHeaderRenderer", "accountName", "runs", 0, "text",
            )?.jsonPrimitive?.contentOrNull
            if (name.isNullOrBlank()) {
                MusicLog.add("Sync: account name not found in the answer, Settings will show a plain 'signed in'")
            } else {
                MusicAccount.setLabel(app, profileId, name)
                MusicLog.add("Sync: account name saved for profile $profileId")
            }
        }
    }
}
