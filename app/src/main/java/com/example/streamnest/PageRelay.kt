package com.example.streamnest

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * TV 0.9.14: some internet providers in India cut the connection to the file sites behind VegaMovies / RogMovies /
 * UHDMovies / HDHub (vcloud, hubcloud…) — "Connection reset" during the secure handshake. Only for those sites, a page
 * request that fails that way is asked again through StreamX's Cloudflare Worker (`/p`), which fetches the page for us.
 * Only small pages go through it — never the video itself. Phone 0.9.78: the phone's plugins use it too.
 */
object PageRelay : Interceptor {
    private const val RELAY = "https://streamx-home.callmerick093.workers.dev/p"
    private const val KEY = "sx-home-1"
    private val allowed = Regex("(?i)(^|\\.)(vcloud\\.[a-z.]+|hubcloud\\.[a-z.]+|vegamovies\\.[a-z.]+|rogmovies\\.[a-z.]+|gamerxyt\\.com|fastdl\\.[a-z.]+|" +
        "nexdrive\\.[a-z.]+|uhdmovies\\.[a-z.]+|hdhub4u\\.[a-z.]+|hubdrive\\.[a-z.]+|driveseed\\.[a-z.]+|driveleech\\.[a-z.]+)$")
    private val videoRx = Regex("(?i)\\.(mkv|mp4|avi|ts|m4s|webm)(\\?|$)")
    /** Hosts that were cut this session: asked through the relay straight away. */
    private val blocked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // 0.9.7 (addonFirst): a host that was cut is remembered 24 h (not only this session).
    private const val DAY = 24L * 3600_000
    private val rememberedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var loaded = false
    private fun loadRemembered() {
        if (loaded) return
        val p = CsPlugins.prefsOrNull() ?: return
        loaded = true
        p.getString("relayHosts24", null)?.split(';')?.forEach { e ->
            val x = e.split('='); if (x.size == 2) x[1].toLongOrNull()?.let { rememberedAt[x[0]] = it }
        }
    }
    private fun remembered(host: String): Boolean {
        loadRemembered()
        val t = rememberedAt[host] ?: return false
        if (System.currentTimeMillis() - t > DAY) { rememberedAt.remove(host); return false }
        return true
    }
    private fun remember(host: String) {
        rememberedAt[host] = System.currentTimeMillis()
        CsPlugins.prefsOrNull()?.edit()?.putString("relayHosts24", rememberedAt.entries.joinToString(";") { "${it.key}=${it.value}" })?.apply()
    }

    private fun relayable(req: Request): Boolean = allowed.containsMatchIn(req.url.host) && !videoRx.containsMatchIn(req.url.encodedPath) &&
        (req.method == "GET" || req.method == "POST") && (req.body?.contentLength() ?: 0L) < 256 * 1024

    private fun isCut(e: Throwable): Boolean {
        val m = (e.message ?: "") + " " + e.javaClass.simpleName + " " + (e.cause?.message ?: "")
        return e is javax.net.ssl.SSLException || m.contains("reset", true) || m.contains("handshake", true) || m.contains("Connection closed", true) ||
            (Feature.addonFirst && (m.contains("refused", true) || m.contains("Failed to connect", true)))   // 0.9.7: refused counts too
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (!relayable(req)) return chain.proceed(req)
        if (req.url.host in blocked) return viaRelay(chain, req)
        // 0.9.7: remembered (24 h): the relay first; if the relay itself fails, the direct way once more (never worse than before).
        if (Feature.addonFirst && remembered(req.url.host)) return try { viaRelay(chain, req) } catch (e: java.io.IOException) { chain.proceed(req) }
        return try { chain.proceed(req) } catch (e: java.io.IOException) {
            if (!isCut(e)) throw e
            blocked += req.url.host
            if (Feature.addonFirst) remember(req.url.host)
            ActLog.add("NET", "relay: ${req.url.host} via Cloudflare (${e.javaClass.simpleName})")
            viaRelay(chain, req, e)
        }
    }

    private fun viaRelay(chain: Interceptor.Chain, req: Request, first: java.io.IOException? = null): Response {
        val rb = SxWorker.stamp(Request.Builder().url("$RELAY?u=" + java.net.URLEncoder.encode(req.url.toString(), "UTF-8")))
        SxWorker.approvalToken()?.let { rb.header("Authorization", "Bearer $it") }   // 0.9.4: only while approval is required
        fun fwd(h: String) { req.header(h)?.let { rb.header("x-h-" + h.lowercase(), it) } }
        fwd("User-Agent"); fwd("Referer"); fwd("Cookie"); fwd("Content-Type"); fwd("Accept")
        if (req.method == "POST" && req.body != null) rb.post(req.body!!) else rb.get()
        val r = try { chain.proceed(rb.build()) } catch (e: java.io.IOException) { throw first ?: e }
        if (r.header("x-final-status") == null) { r.close(); throw first ?: java.io.IOException("relay refused (${r.code})") }
        val status = r.header("x-final-status")?.toIntOrNull() ?: r.code
        val body = r.body?.bytes() ?: ByteArray(0)
        val out = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(status).message("relayed")
            .body(body.toResponseBody(r.header("content-type")?.toMediaTypeOrNull()))
        r.header("location")?.let { out.header("Location", it) }
        r.headers("set-cookie").forEach { out.addHeader("Set-Cookie", it) }
        r.header("content-type")?.let { out.header("Content-Type", it) }
        r.close()
        return out.build()
    }
}
