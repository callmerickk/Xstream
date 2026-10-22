package com.example.streamnest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Xstream: defaults the developer publishes for everyone from Developer's Corner (shared/devDefaults in the cloud, readable
 * by signed-in users only): the API keys (AI, TMDB, MDBList, GIPHY, Trakt), the AI's logo and its voice. Every phone
 * applies them when a profile opens, so nobody has to get their own keys.
 */
object DevDefaults {
    private fun textKeys(): List<String> = listOf("aiOn", "aiProvider", "aiLogoUrl", "tmdbKey", "mdbKey", "giphyKey", "traktClientId", "codeNames", "homeRows") +
        AiProviders.flatMap { listOf("aiKey_${it.id}", "aiModel_${it.id}") }
    private val storeKeys = listOf("aiVoice", "aiVoiceEn", "aiVoiceHi", "aiVoiceRate", "aiVoiceOut")

    /** Publishes what this phone has now (only the owner's account may write). Null = done. */
    suspend fun publish(vm: AppViewModel): String? = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext "Sign in first."
        val texts = vm.texts.value
        val body = buildJsonObject {
            textKeys().forEach { k -> texts[k]?.let { put("t_$k", it) } }
            storeKeys.forEach { k -> vm.store(k)?.let { put("s_$k", it) } }
            put("at", System.currentTimeMillis())
        }
        runCatching {
            Api.http.newCall(Request.Builder().url("${CloudConfig.DB_URL}/shared/devDefaults.json?auth=$t")
                .put(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                if (r.isSuccessful) null else if (r.code == 401 || r.code == 403) "Not allowed: add the Firebase rule for shared/devDefaults (write: owner only, read: signed-in)." else "HTTP ${r.code}"
            }
        }.getOrElse { "Couldn't reach the cloud." }
    }

    /** Applies the published defaults on this phone (keys that changed only). */
    suspend fun sync(vm: AppViewModel) {
        val t = Cloud.token() ?: return
        val o = withContext(Dispatchers.IO) {
            runCatching { Api.json.parseToJsonElement(Api.get("${CloudConfig.DB_URL}/shared/devDefaults.json?auth=$t")) as? JsonObject }.getOrNull()
        } ?: return
        val changed = ArrayList<String>()
        withContext(Dispatchers.Main) {
            o.forEach { (k, v) ->
                val value = runCatching { v.jsonPrimitive.contentOrNull }.getOrNull() ?: return@forEach
                when {
                    // The developer's filters are the base; the user's own filter changes stay on top (never replaced).
                    k == "t_tagRules" -> if (vm.texts.value["devTagRules"] != value) { vm.setText("devTagRules", value); changed += "devTagRules" }
                    k.startsWith("t_") -> { val key = k.removePrefix("t_"); if (vm.texts.value[key] != value) { vm.setText(key, value); changed += key } }
                    k.startsWith("s_") -> { val key = k.removePrefix("s_"); if (vm.store(key) != value) { vm.storePut(key, value); changed += key } }
                }
            }
        }
        if (changed.isNotEmpty()) ActLog.add("APP", "published defaults applied: ${changed.size} setting(s)")
    }
}
