package com.example.streamnest

import kotlinx.coroutines.sync.withLock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** DNS choices shown in Help & Settings > Network & DNS. */
val DnsProviders = linkedMapOf(
    "system" to ("System default" to "Use your phone's / Wi-Fi's normal DNS."),
    "cloudflare" to ("Cloudflare (1.1.1.1)" to "Fast and privacy-focused."),
    "google" to ("Google (8.8.8.8)" to "Reliable public DNS from Google."),
    "quad9" to ("Quad9 (9.9.9.9)" to "Blocks known malicious domains."),
    "adguard" to ("AdGuard" to "Blocks many ads and trackers."),
)

object Api {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val bootstrap: OkHttpClient by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build() }

    /** Shared HTTP client for catalogs, addons, images and video streams. Rebuilt when the DNS choice changes. */
    @Volatile var http: OkHttpClient = build("system")
        private set

    fun setDns(provider: String) { http = build(provider) }

    private fun build(provider: String): OkHttpClient {
        val b = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            // Keep connections to addons and video servers open for reuse (faster repeat requests).
            .connectionPool(okhttp3.ConnectionPool(16, 5, TimeUnit.MINUTES))
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 12 })
            .retryOnConnectionFailure(true)
            // Keep cookies like a browser (and like Nuvio): some video servers (MovieBox, VA Player) set a cookie on the
            // playlist and refuse the video pieces (403 / 428) without it.
            .cookieJar(MemoryCookies)
            // VPN Smart mode: a source that doesn't answer from here is sent through the VPN and tried again.
            .addInterceptor(TmdbAuth)
            .addInterceptor(Vpn.SmartRetry)
            .addInterceptor(ActLog.Net)
        b.dns(CachedDns(dohFor(provider)?.let { FallbackDns(it) } ?: Dns.SYSTEM))
        return b.build()
    }

    private fun dohFor(p: String): Dns? = runCatching {
        when (p) {
            "cloudflare" -> doh("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1")
            "google" -> doh("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4")
            "quad9" -> doh("https://dns.quad9.net/dns-query", "9.9.9.9", "149.112.112.112")
            "adguard" -> doh("https://dns.adguard-dns.com/dns-query", "94.140.14.14", "94.140.15.15")
            else -> null
        }
    }.getOrNull()

    private fun doh(url: String, vararg ips: String): Dns =
        DnsOverHttps.Builder().client(bootstrap).url(url.toHttpUrl())
            .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
            .includeIPv6(false).build()

    /** Cookies kept in memory while the app runs (shared by addons, probes and the player). */
    object MemoryCookies : okhttp3.CookieJar {
        private val store = java.util.concurrent.ConcurrentHashMap<String, MutableList<okhttp3.Cookie>>()
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            if (cookies.isEmpty()) return
            for (c in cookies) {
                val list = store.getOrPut(c.domain) { java.util.Collections.synchronizedList(ArrayList()) }
                synchronized(list) { list.removeAll { it.name == c.name && it.path == c.path }; list += c }
            }
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
            // The addon gave this server's links their own Cookie header: keep it (OkHttp would replace it with ours).
            if (url.host in StreamHeaders.ownCookieHosts) return emptyList()
            val now = System.currentTimeMillis()
            val out = ArrayList<okhttp3.Cookie>()
            for ((_, list) in store) synchronized(list) {
                list.removeAll { it.expiresAt < now }
                list.filterTo(out) { it.matches(url) }
            }
            return out
        }
        fun count(): Int = store.values.sumOf { it.size }
    }

    /** Remembers server addresses for 10 minutes, so repeat lookups skip DNS. */
    private class CachedDns(val base: Dns) : Dns {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
        override fun lookup(hostname: String): List<InetAddress> {
            cache[hostname]?.let { (at, ips) -> if (System.currentTimeMillis() - at < 600_000) return ips }
            return base.lookup(hostname).also { if (it.isNotEmpty()) cache[hostname] = System.currentTimeMillis() to it }
        }
    }

    /** If the chosen DNS can't answer, fall back to the normal one instead of failing. */
    private class FallbackDns(val primary: Dns) : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            try { primary.lookup(hostname).also { dohOk.incrementAndGet() } } catch (e: Exception) { dohFallbacks.incrementAndGet(); Dns.SYSTEM.lookup(hostname) }
    }

    /** Lookups the chosen secure DNS answered / that fell back to the phone's DNS (since the app started). */
    val dohOk = java.util.concurrent.atomic.AtomicInteger(0)
    val dohFallbacks = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Is the chosen DNS really answering? Asks it directly (no fallback) for a few addresses and compares with the
     * phone's own answer. Returns (working, line to show).
     */
    suspend fun dnsStatus(provider: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (provider == "system") return@withContext true to "Using your phone's DNS"
        val d = dohFor(provider) ?: return@withContext false to "This DNS couldn't be set up — using your phone's DNS"
        val hosts = listOf("v3-cinemeta.strem.io", "api.github.com", "image.tmdb.org")
        val t0 = System.nanoTime()
        val ok = hosts.count { h -> runCatching { d.lookup(h).isNotEmpty() }.getOrDefault(false) }
        val ms = (System.nanoTime() - t0) / 1_000_000 / hosts.size
        val fb = dohFallbacks.get(); val good = dohOk.get()
        when {
            ok == hosts.size -> true to "Active · answering in about $ms ms" + (if (good + fb > 0) " · ${good * 100 / (good + fb)}% of this app's lookups" else "")
            ok > 0 -> true to "Partly working ($ok of ${hosts.size} answered) — the rest goes through your phone's DNS"
            else -> false to "Not reachable on this network — the app is using your phone's DNS"
        }
    }

    // TV: addon manifests are asked for once and kept 10 minutes (opening a profile asked one addon ~70 times in 3 s and
    // the server started refusing with "too many requests").
    private val manifestCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    private val manifestLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** GET; cancelling the caller (a time limit, a newer search) also stops the request on the network. */
    suspend fun get(url0: String): String {
        val url = fixAddonUrl(url0)
        if (!BuildConfig.IS_TV || !url.substringBefore('?').endsWith("/manifest.json")) return getNet(url)
        manifestCache[url]?.let { (at, body) -> if (System.currentTimeMillis() - at < 600_000L) return body }
        val lock = manifestLocks.getOrPut(url) { kotlinx.coroutines.sync.Mutex() }
        return lock.withLock {
            manifestCache[url]?.let { (at, body) -> if (System.currentTimeMillis() - at < 600_000L) return@withLock body }
            getNet(url).also { manifestCache[url] = System.currentTimeMillis() to it }
        }
    }

    private suspend fun getNet(url: String): String = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val call = http.newCall(Request.Builder().url(url).build())
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { if (cont.isActive) cont.resumeWith(Result.failure(e)) }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val r = runCatching { response.use { require(it.isSuccessful) { "HTTP ${it.code}" }; it.body!!.string() } }
                if (cont.isActive) cont.resumeWith(r)
            }
        })
    }

    /** GET with extra headers (used for pages that need a browser-like request). */
    suspend fun getWith(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use {
            require(it.isSuccessful) { "HTTP ${it.code}" }
            it.body!!.string()
        }
    }

    // TV: the answer is read on a background thread (opening a profile read ~100 catalogs on the screen's own thread, and
    // the stick froze for ~4 s while the profile circle waited).
    suspend inline fun <reified T> fetch(url: String): T {
        val body = get(url)
        return if (BuildConfig.IS_TV) withContext(Dispatchers.Default) { json.decodeFromString<T>(body) } else json.decodeFromString<T>(body)
    }

    /**
     * TV: an addon saved with its settings in the link ("…/manifest.json?config=…") got every request glued after the
     * settings ("…/manifest.json?config=…/catalog/movie/top.json"), so the server kept answering with its manifest.
     * The request goes to "…/catalog/movie/top.json?config=…" instead.
     */
    fun fixAddonUrl(url: String): String {
        if (!BuildConfig.IS_TV) return url
        val m = Regex("^(.*?)/manifest\\.json(\\?.*?)(/(?:catalog|meta|stream|subtitles)/[^?]*)$").find(url) ?: return url
        return m.groupValues[1] + m.groupValues[3] + m.groupValues[2]
    }

    fun normalize(input: String): String =
        input.trim().replace("stremio://", "https://").removeSuffix("/manifest.json").trimEnd('/')
}
