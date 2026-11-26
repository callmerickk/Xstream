package com.example.streamnest

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest

/**
 * Prompt 7 (Feature.accessDevices): access per DEVICE instead of per account.
 *
 * Device id = SHA-256 of (account uid + phone model + Android build fingerprint), first 32 hex letters.
 * Same values after clearing data or reinstalling = same device = no new request.
 *
 * Database: access/devices/<uid>/<deviceHash> = { name, email, model, appVersion, requestedAt, lastSeen,
 * status: waiting | approved | revoked }.
 *
 * Only used when Feature.accessGate AND Feature.accessDevices are on (the callers check; nothing here checks).
 * The old per-account paths (access/allowed, access/requests) are not touched here.
 */
object AccessDevices {
    enum class Status { WAITING, APPROVED, REVOKED }

    data class Rec(
        val uid: String, val hash: String, val name: String, val email: String, val model: String,
        val appVersion: String, val requestedAt: Long, val lastSeen: Long, val status: Status,
    )

    /** What the database said about THIS phone. Unknown = could not ask (no sign-in, no network, rules refused). */
    sealed class Mine {
        object Unknown : Mine()
        object None : Mine()
        data class Has(val rec: Rec) : Mine()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("access", Context.MODE_PRIVATE)
    private fun url(path: String, t: String) = "${CloudConfig.DB_URL}/access/$path.json?auth=$t"

    /** Phone name for people: "samsung SM-S911B" style, without repeating the maker when the model already starts with it. */
    fun model(): String {
        val maker = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
    }

    /** The device id. Note: an Android system update changes Build.FINGERPRINT, so the phone then counts as a new device. */
    fun hash(uid: String): String {
        val raw = "$uid|${Build.MANUFACTURER}|${Build.MODEL}"   // owner: account + model only, so updates/reinstalls stay the same device
        val d = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(32)
    }

    /** null = could not ask (same meaning as Access.call). */
    private suspend fun call(method: String, path: String, body: String? = null): String? = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext null
        runCatching {
            val b = Request.Builder().url(url(path, t))
            val json = "application/json".toMediaType()
            when (method) {
                "PUT" -> b.put((body ?: "null").toRequestBody(json))
                "PATCH" -> b.patch((body ?: "{}").toRequestBody(json))
                "DELETE" -> b.delete()
                else -> b.get()
            }
            Api.http.newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull()
    }

    private fun parse(uid: String, hash: String, v: JsonElement?): Rec? {
        val j = v as? JsonObject ?: return null
        fun s(k: String) = (j[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun l(k: String) = (j[k] as? JsonPrimitive)?.longOrNull ?: 0L
        // An unknown or missing status counts as "waiting", never as approved.
        val st = when (s("status")) { "approved" -> Status.APPROVED; "revoked" -> Status.REVOKED; else -> Status.WAITING }
        return Rec(uid, hash, s("name"), s("email"), s("model"), s("appVersion"), l("requestedAt"), l("lastSeen"), st)
    }

    // ── This phone ──

    /** Asks the database about this phone. */
    suspend fun mine(uid: String): Mine {
        val h = hash(uid)
        val body = call("GET", "devices/$uid/$h")?.trim() ?: return Mine.Unknown
        if (body == "null") return Mine.None
        val el = runCatching { Api.json.parseToJsonElement(body) }.getOrNull()
        return parse(uid, h, el)?.let { Mine.Has(it) } ?: Mine.Unknown
    }

    /**
     * Sends (or re-sends, after "Access paused") the request. [name] is required: a blank name sends nothing and returns false.
     * Always writes status "waiting".
     */
    suspend fun request(uid: String, email: String, name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) { ActLog.add("APP", "access: device request not sent, the name box was empty"); return false }
        val now = System.currentTimeMillis()
        val body = buildJsonObject {
            put("name", n); put("email", email); put("model", model())
            put("appVersion", BuildConfig.VERSION_NAME)
            put("requestedAt", now); put("lastSeen", now); put("status", "waiting")
        }.toString()
        val ok = call("PUT", "devices/$uid/${hash(uid)}", body) != null
        ActLog.add("APP", if (ok) "access: device request sent (${model()})" else "access: device request failed")
        return ok
    }

    /**
     * Updates "last seen" and the app version, at most once an hour. Call it ONLY when the record exists
     * (status was just read as approved): on a missing record this would create a half record without a status.
     */
    suspend fun touch(ctx: Context, uid: String) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - p.getLong("devSeen", 0L) < 3_600_000L) return
        val body = buildJsonObject { put("lastSeen", now); put("appVersion", BuildConfig.VERSION_NAME) }.toString()
        if (call("PATCH", "devices/$uid/${hash(uid)}", body) != null) p.edit().putLong("devSeen", now).apply()
    }

    /**
     * Old per-account approval -> this device (first open after the update).
     * Only the phone that already has the old local approval (prefs allowedUid == uid) migrates, and only when the
     * database has no record for it yet. Other phones of the same account have to ask. Needs the migration rule in Part 5.
     * [fallbackName] = what the old request used (profile name or the start of the email).
     */
    suspend fun migrate(ctx: Context, uid: String, email: String, fallbackName: String): Boolean {
        if (prefs(ctx).getString("allowedUid", null) != uid) return false
        if (mine(uid) !is Mine.None) return false
        val now = System.currentTimeMillis()
        val body = buildJsonObject {
            put("name", fallbackName.ifBlank { email.substringBefore("@") }); put("email", email); put("model", model())
            put("appVersion", BuildConfig.VERSION_NAME)
            put("requestedAt", now); put("lastSeen", now); put("status", "approved")
        }.toString()
        val ok = call("PUT", "devices/$uid/${hash(uid)}", body) != null
        ActLog.add("APP", if (ok) "access: old account approval moved to this device" else "access: could not move the old approval yet, the old one keeps working")
        return ok
    }

    // ── Memory on the phone, so the app works offline after an approval ──

    fun rememberedApproved(ctx: Context, uid: String): Boolean = prefs(ctx).getString("devOk", null) == hash(uid)

    fun remember(ctx: Context, uid: String, approved: Boolean) {
        val e = prefs(ctx).edit()
        if (approved) e.putString("devOk", hash(uid)) else e.remove("devOk")
        e.apply()
    }

    // ── Developer side ──

    /** Every device of every account. null = could not ask (so "nothing there" is not shown by mistake). */
    suspend fun all(): List<Rec>? {
        val body = call("GET", "devices")?.trim() ?: return null
        if (body == "null") return emptyList()
        val root = runCatching { Api.json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val out = ArrayList<Rec>()
        root.forEach { (uid, devs) ->
            (devs as? JsonObject)?.forEach { (h, v) -> parse(uid, h, v)?.let { out += it } }
        }
        return out.sortedByDescending { it.lastSeen }
    }

    private suspend fun setStatus(uid: String, hash: String, status: String): Boolean {
        val ok = call("PUT", "devices/$uid/$hash/status", "\"$status\"") != null
        ActLog.add("APP", "access: device $hash of $uid set to $status" + if (ok) "" else " FAILED")
        return ok
    }

    suspend fun grant(rec: Rec): Boolean = setStatus(rec.uid, rec.hash, "approved")
    suspend fun revoke(rec: Rec): Boolean = setStatus(rec.uid, rec.hash, "revoked")

    /** Revokes every device of the account that is not revoked yet. Returns how many were changed. */
    suspend fun revokeAll(devices: List<Rec>): Int =
        devices.filter { it.status != Status.REVOKED }.count { revoke(it) }

    fun waiting(list: List<Rec>): Int = list.count { it.status == Status.WAITING }

    /** Groups by account email (lower case). Accounts with waiting devices first, then the most recently seen. */
    fun byAccount(list: List<Rec>): List<Pair<String, List<Rec>>> =
        list.groupBy { it.email.trim().lowercase().ifBlank { it.uid } }
            .toList()
            .sortedWith(compareByDescending<Pair<String, List<Rec>>> { waiting(it.second) > 0 }
                .thenByDescending { p -> p.second.maxOf { it.lastSeen } })
}
