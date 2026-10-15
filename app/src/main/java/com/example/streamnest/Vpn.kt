package com.example.streamnest

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * A place the VPN can go out from. [src] = "sx" (Xstream's own servers, set in Developer's Corner — only the country is
 * shown) or the VPN the person connected with their own account ("Proton VPN"…; [conf] = its WireGuard file, kept only
 * on this phone). [mbps] / [ping] = the last speed test (0 = not tested).
 */
@Serializable
data class VpnServer(
    val id: String, val name: String, val cc: String = "", val src: String = "sx",
    val conf: String = "", val endpoint: String = "", val pub: String = "", val dns: String = "",
    val mbps: Double = 0.0, val ping: Int = 0, val testedAt: Long = 0,
)

/** One Xstream server as published in Developer's Corner (no secrets: its address and public key). */
@Serializable data class SxServer(val id: String, val name: String, val cc: String, val endpoint: String, val pub: String, val dns: String = "1.1.1.1")

/**
 * Xstream VPN: WireGuard (its official open-source library) for Xstream only — other apps keep the normal internet.
 * Smart (default): only hosts that don't answer from here go through it; Everything: all of Xstream.
 * Auto picks the fastest tested country and skips ones too slow for HD (< 15 Mbps).
 */
object Vpn {
    enum class St { OFF, CONNECTING, ON, ERROR }
    val state = MutableStateFlow(St.OFF)
    val message = MutableStateFlow("")
    val mode = MutableStateFlow("smart")
    val auto = MutableStateFlow(true)
    val pick = MutableStateFlow("")
    val guard = MutableStateFlow(true)
    val servers = MutableStateFlow<List<VpnServer>>(emptyList())
    val current = MutableStateFlow<VpnServer?>(null)
    val testing = MutableStateFlow(false)
    /** (ip, country code) as sources see Xstream: [direct] without the VPN, [via] through it. */
    val direct = MutableStateFlow<Pair<String, String>?>(null)
    val via = MutableStateFlow<Pair<String, String>?>(null)
    /** Another VPN app (Proton, Nord…) is on and Xstream goes through it. */
    val otherVpn = MutableStateFlow(false)
    /** Hosts that didn't answer from here: Smart mode sends only these through the VPN. */
    val smartHosts = MutableStateFlow<Set<String>>(emptySet())

    const val MIN_MBPS = 15.0
    /** Through the VPN in Smart mode too, so the IP check shows what blocked sources see. */
    private const val CHECK_VIA = "1.0.0.1"

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var app: Context? = null
    private var backend: GoBackend? = null
    private var sxList: List<SxServer> = emptyList()

    private object T : Tunnel {
        override fun getName() = "streamx"
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN && state.value == St.ON) { state.value = St.OFF; via.value = null }
        }
    }

    private fun prefs() = app!!.getSharedPreferences("vpn", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        val p = prefs()
        mode.value = p.getString("mode", "smart") ?: "smart"
        auto.value = p.getBoolean("auto", true)
        pick.value = p.getString("pick", "") ?: ""
        guard.value = p.getBoolean("guard", true)
        smartHosts.value = p.getStringSet("hosts", emptySet())?.toSet() ?: emptySet()
        reload()
    }

    private fun save(f: android.content.SharedPreferences.Editor.() -> Unit) { prefs().edit().apply(f).apply() }
    fun setMode(m: String) { mode.value = m; save { putString("mode", m) }; if (state.value == St.ON) io.launch { reapply() } }
    fun setAuto() { auto.value = true; save { putBoolean("auto", true) } }
    fun setPick(id: String) { auto.value = false; pick.value = id; save { putBoolean("auto", false); putString("pick", id) }; if (state.value == St.ON) io.launch { connect() } }
    fun setGuard(on: Boolean) { guard.value = on; save { putBoolean("guard", on) } }

    // ── Servers: Xstream's (from the cloud) + the person's own (imported files) ──

    private fun own(): List<VpnServer> = runCatching { Api.json.decodeFromString<List<VpnServer>>(prefs().getString("own", "[]") ?: "[]") }.getOrDefault(emptyList())
    private fun saveOwn(l: List<VpnServer>) = save { putString("own", Api.json.encodeToString(l)) }
    private fun tests(): Map<String, VpnServer> = runCatching { Api.json.decodeFromString<List<VpnServer>>(prefs().getString("tests", "[]") ?: "[]") }.getOrDefault(emptyList()).associateBy { it.id }

    /** Xstream servers published in Developer's Corner (AppLook.fetch hands the list over). */
    fun setShared(json: String?) {
        sxList = runCatching { Api.json.decodeFromString<List<SxServer>>(json ?: "[]") }.getOrDefault(emptyList())
        if (app != null) reload()
    }

    private fun reload() {
        val t = tests()
        val sx = sxList.map { s -> VpnServer("sx:" + s.id, s.name, s.cc, "sx", endpoint = s.endpoint, pub = s.pub, dns = s.dns) }
        servers.value = (sx + own()).map { s -> t[s.id]?.let { s.copy(mbps = it.mbps, ping = it.ping, testedAt = it.testedAt) } ?: s }
    }

    fun ownAccounts(): List<Pair<String, Int>> = own().groupBy { it.src }.map { (k, v) -> k to v.size }

    fun removeAccount(src: String) { saveOwn(own().filter { it.src != src }); reload(); if (current.value?.src == src) io.launch { disconnect() } }

    /** WireGuard files from a VPN's website, a file, a QR or paste: added under [src]. Returns how many were added. */
    suspend fun importConfs(src: String, files: List<Pair<String, String>>): Int = withContext(Dispatchers.IO) {
        val added = mutableListOf<VpnServer>()
        for ((fname, text) in files) {
            val cfg = runCatching { Config.parse(text.byteInputStream()) }.getOrNull() ?: continue
            val ep = cfg.peers.firstOrNull()?.endpoint?.orElse(null) ?: continue
            val host = ep.host
            val (cc, city) = locate(host, fname, text)
            val name = listOfNotNull(city?.takeIf { it.isNotBlank() }, countryName(cc)).distinct().joinToString(", ").ifBlank { host }
            added += VpnServer("own:" + Integer.toHexString((src + text).hashCode()), name, cc, src, conf = text, endpoint = "$host:${ep.port}")
        }
        val all = (own().filter { o -> added.none { it.id == o.id } } + added)
        saveOwn(all); reload()
        added.size
    }

    /** Where a server is: a country code in the file name / comments ("NL-FREE#12", "wg-JP-3"), else its address's location. */
    private suspend fun locate(host: String, fname: String, text: String): Pair<String, String?> {
        val ip = runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
        val geo = ip?.let { runCatching {
            val o = Api.json.parseToJsonElement(Api.get("https://ipwho.is/$it")).jsonObject
            (o["country_code"]?.jsonPrimitive?.contentOrNull ?: "") to o["city"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() }
        if (geo != null && geo.first.length == 2) return geo
        val hint = Regex("(?:^|[^A-Za-z])([A-Z]{2})(?:[-_#\\s]|FREE)").find(fname + " " + text.lines().filter { it.startsWith("#") }.joinToString(" "))?.groupValues?.get(1)
        return (hint ?: "") to null
    }

    fun flag(cc: String): String = if (cc.length != 2) "🌐" else cc.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    fun countryName(cc: String): String? = cc.takeIf { it.length == 2 }?.let { java.util.Locale("", it).displayCountry }

    // ── This phone's key for Xstream servers (the private key never leaves the phone) ──

    private fun deviceKeys(): KeyPair {
        prefs().getString("key", null)?.let { k -> runCatching { return KeyPair(Key.fromBase64(k)) } }
        val kp = KeyPair()
        val dev = java.util.UUID.randomUUID().toString().take(8)
        save { putString("key", kp.privateKey.toBase64()); putString("dev", dev) }
        return kp
    }
    private fun devId() = prefs().getString("dev", null) ?: run { deviceKeys(); prefs().getString("dev", "d") ?: "d" }

    /** Asks the Xstream server for an address (its helper adds this phone, usually in a few seconds). */
    private suspend fun enroll(s: VpnServer): String? {
        prefs().getString("ip:${s.id}", null)?.let { return it }
        val uid = Cloud.user.value?.uid ?: throw IOException("Sign in to Xstream first (Settings › Account) to use Xstream servers.")
        val t = Cloud.token() ?: throw IOException("Sign in again to use Xstream servers.")
        val sid = s.id.removePrefix("sx:")
        val url = "${CloudConfig.DB_URL}/vpnPeers/$uid/${devId()}/$sid.json?auth=$t"
        val body = buildJsonObject { put("pub", deviceKeys().publicKey.toBase64()); put("name", Together.myName.ifBlank { Cloud.user.value?.email?.substringBefore('@') ?: "" }); put("at", System.currentTimeMillis()) }
        Api.http.newCall(Request.Builder().url(url).patch(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
            if (!it.isSuccessful) throw IOException(if (it.code == 401 || it.code == 403) "Not allowed: the Firebase rule for vpnPeers is missing." else "Couldn't reach the cloud (HTTP ${it.code}).")
        }
        repeat(20) {
            delay(1500)
            val ip = runCatching { Api.json.parseToJsonElement(Api.get(url)).jsonObject["ip"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            if (!ip.isNullOrBlank()) { save { putString("ip:${s.id}", ip) }; return ip }
        }
        throw IOException("The ${s.name} server didn't answer. Is its helper running?")
    }

    // ── The tunnel ──

    private fun be(): GoBackend = backend ?: GoBackend(app!!).also { backend = it }

    private fun routesFor(all: Boolean): List<InetNetwork> {
        if (all) return listOf(InetNetwork.parse("0.0.0.0/0"), InetNetwork.parse("::/0"))
        val ips = LinkedHashSet<String>()
        ips += "$CHECK_VIA/32"
        smartHosts.value.forEach { h -> runCatching { InetAddress.getAllByName(h).forEach { a -> ips += if (a is java.net.Inet6Address) "${a.hostAddress}/128" else "${a.hostAddress}/32" } } }
        return ips.mapNotNull { runCatching { InetNetwork.parse(it) }.getOrNull() }
    }

    private suspend fun configFor(s: VpnServer, all: Boolean): Config {
        val pkg = app!!.packageName
        val ib = Interface.Builder().includeApplication(pkg)
        val pb = Peer.Builder().setPersistentKeepalive(25).addAllowedIps(routesFor(all))
        if (s.src == "sx") {
            val ip = enroll(s) ?: throw IOException("No address from the server.")
            ib.setKeyPair(deviceKeys()).addAddress(InetNetwork.parse(if ("/" in ip) ip else "$ip/32"))
            if (all) runCatching { ib.addDnsServer(InetAddress.getByName(s.dns.ifBlank { "1.1.1.1" })) }
            pb.setPublicKey(Key.fromBase64(s.pub)).setEndpoint(InetEndpoint.parse(s.endpoint))
        } else {
            val c = Config.parse(s.conf.byteInputStream())
            val i = c.`interface`
            ib.setKeyPair(i.keyPair).addAddresses(i.addresses)
            i.mtu.ifPresent { ib.setMtu(it) }
            if (all) ib.addDnsServers(i.dnsServers.ifEmpty { setOf(InetAddress.getByName("1.1.1.1")) })
            val p = c.peers.first()
            pb.setPublicKey(p.publicKey)
            p.preSharedKey.ifPresent { pb.setPreSharedKey(it) }
            p.endpoint.ifPresent { pb.setEndpoint(it) }
        }
        return Config.Builder().setInterface(ib.build()).addPeer(pb.build()).build()
    }

    /** The country to use: picked, else the fastest tested one fast enough for HD, else the first. */
    private fun choose(): VpnServer? {
        val l = servers.value
        if (!auto.value) l.firstOrNull { it.id == pick.value }?.let { return it }
        return l.filter { it.mbps >= MIN_MBPS }.maxByOrNull { it.mbps / Math.sqrt(it.ping.coerceAtLeast(1).toDouble()) }
            ?: l.filter { it.mbps == 0.0 }.firstOrNull() ?: l.firstOrNull()
    }

    /** Turn on (permission already given). */
    suspend fun connect(target: VpnServer? = null): Boolean {
        val s = lock.withLock {
            withContext(Dispatchers.IO) {
                val s = target ?: choose() ?: run { state.value = St.ERROR; message.value = "No country yet: connect your VPN account, or add a Xstream server in Developer's Corner."; return@withContext null }
                if (direct.value == null && state.value != St.ON) direct.value = trace("1.1.1.1")
                state.value = St.CONNECTING; message.value = "Connecting to ${s.name}…"
                runCatching {
                    be().setState(T, Tunnel.State.UP, configFor(s, mode.value == "all"))
                    current.value = s
                    delay(800)
                    via.value = trace(CHECK_VIA)
                    state.value = St.ON; message.value = ""
                    save { putBoolean("wasOn", true) }
                    s
                }.getOrElse { e ->
                    runCatching { be().setState(T, Tunnel.State.DOWN, null) }
                    state.value = St.ERROR; message.value = e.message ?: "Couldn't connect"
                    null
                }
            }
        } ?: return false
        guardCheck(s)
        return true
    }

    suspend fun disconnect() = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching { be().setState(T, Tunnel.State.DOWN, null) }
            state.value = St.OFF; via.value = null; current.value = null; message.value = ""
            save { putBoolean("wasOn", false) }
        }
    }

    /** Mode or Smart list changed: same country, new routes (connections to other addresses aren't touched). */
    private suspend fun reapply() = lock.withLock {
        withContext(Dispatchers.IO) {
            val s = current.value ?: return@withContext
            runCatching { be().setState(T, Tunnel.State.UP, configFor(s, mode.value == "all")) }
        }
    }

    /** "Never slower than direct": in Everything mode, a country much slower than this phone's own internet → Smart. */
    private suspend fun guardCheck(s: VpnServer) {
        if (!guard.value || mode.value != "all" || s.mbps <= 0) return
        val d = prefs().getFloat("directMbps", 0f)
        if (d > 0 && s.mbps < d * 0.5 && s.mbps < 25) {
            mode.value = "smart"; save { putString("mode", "smart") }
            reapply()
            withContext(Dispatchers.Main) { Snack.show("${s.name} is slower than your own internet — only blocked sources use the VPN now") }
        }
    }

    // ── Smart: a host that doesn't answer from here goes through the VPN, and the request is tried again ──

    private val neverSmart = listOf("firebaseio.com", "googleapis.com", "github.com", "githubusercontent.com", "cloudflare-dns.com", "dns.google", "ipwho.is", "speed.cloudflare.com")

    fun smartReady() = state.value == St.ON && mode.value == "smart"

    /** True = [host] now goes through the VPN (newly added), try again. */
    fun tryHost(host: String): Boolean {
        if (!smartReady() || host in smartHosts.value || neverSmart.any { host.endsWith(it) } || host == CHECK_VIA) return false
        smartHosts.value = smartHosts.value + host
        save { putStringSet("hosts", smartHosts.value) }
        kotlinx.coroutines.runBlocking { reapply() }
        return true
    }

    fun forgetHosts() { smartHosts.value = emptySet(); save { remove("hosts") }; if (state.value == St.ON) io.launch { reapply() } }

    object SmartRetry : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            if (!smartReady()) return chain.proceed(req)
            val host = req.url.host
            val r = try { chain.proceed(req) } catch (e: IOException) {
                if (tryHost(host)) return chain.proceed(req) else throw e
            }
            if ((r.code == 403 || r.code == 451) && tryHost(host)) { r.close(); return chain.proceed(req) }
            return r
        }
    }

    // ── IP check and speed test ──

    private fun fresh(): OkHttpClient = Api.http.newBuilder().connectionPool(okhttp3.ConnectionPool()).callTimeout(8, TimeUnit.SECONDS).build()

    /** (ip, country) as a server sees this phone, asking Cloudflare at [at]. */
    fun trace(at: String): Pair<String, String>? = runCatching {
        fresh().newCall(Request.Builder().url("https://$at/cdn-cgi/trace").build()).execute().use { r ->
            val m = r.body?.string().orEmpty().lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            (m["ip"] ?: return@use null) to (m["loc"] ?: "")
        }
    }.getOrNull()

    /** Time to answer (ms) and download speed (Mbps) through whatever route this phone has now (≈4 s). */
    private fun measure(): Pair<Int, Double> {
        val c = fresh()
        val t0 = System.nanoTime()
        val ok = runCatching { c.newCall(Request.Builder().url("https://$CHECK_VIA/cdn-cgi/trace").build()).execute().use { it.isSuccessful } }.getOrDefault(false)
        if (!ok) return 0 to 0.0
        val ping = ((System.nanoTime() - t0) / 1_000_000).toInt()
        val bytes = runCatching {
            c.newBuilder().callTimeout(6, TimeUnit.SECONDS).build().newCall(Request.Builder().url("https://speed.cloudflare.com/__down?bytes=6000000").build()).execute().use { r ->
                val src = r.body?.source() ?: return@use 0L to 1L
                val start = System.nanoTime(); var n = 0L
                val buf = okio.Buffer()
                while (System.nanoTime() - start < 4_000_000_000L) { val k = src.read(buf, 65536); if (k < 0) break; n += k; buf.clear() }
                n to (System.nanoTime() - start)
            }
        }.getOrDefault(0L to 1L)
        val mbps = bytes.first * 8.0 / (bytes.second / 1e9) / 1e6
        return ping to (Math.round(mbps * 10) / 10.0)
    }

    /** Tests every country one by one (≈5 s each), and this phone's own internet; then back to how it was. */
    suspend fun testAll() {
        if (testing.value) return
        testing.value = true
        val wasOn = state.value == St.ON
        val keep = current.value
        try {
            state.value = St.OFF; via.value = null
            withContext(Dispatchers.IO) {
                lock.withLock { runCatching { be().setState(T, Tunnel.State.DOWN, null) } }
                val (_, d) = measure(); if (d > 0) save { putFloat("directMbps", d.toFloat()) }
                val results = ArrayList<VpnServer>()
                for (s in servers.value) {
                    val r = lock.withLock {
                        runCatching { be().setState(T, Tunnel.State.UP, configFor(s, true)); delay(700); measure() }.getOrDefault(0 to 0.0)
                            .also { runCatching { be().setState(T, Tunnel.State.DOWN, null) } }
                    }
                    results += s.copy(ping = r.first, mbps = r.second, testedAt = System.currentTimeMillis())
                    servers.value = servers.value.map { if (it.id == s.id) results.last() else it }
                }
                save { putString("tests", Api.json.encodeToString(results.map { it.copy(conf = "") })) }
                state.value = St.OFF
            }
            if (wasOn) connect(if (auto.value) null else keep)
        } finally { testing.value = false }
    }

    /** Is another VPN app on (and not ours)? */
    fun checkOtherVpn(ctx: Context) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val vpnOn = runCatching { cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }.getOrDefault(false)
        otherVpn.value = vpnOn && state.value != St.ON && state.value != St.CONNECTING
    }

    /** App start: was on last time → on again (if Android still allows it without asking). */
    fun resume(ctx: Context) {
        init(ctx)
        if (prefs().getBoolean("wasOn", false) && android.net.VpnService.prepare(ctx) == null && state.value == St.OFF) io.launch { connect() }
    }
}

/** Sites where people sign in to their own VPN account and download WireGuard files (opened inside Xstream). */
data class VpnProvider(val name: String, val color: Long, val letter: String, val tag: String, val note: String, val url: String)

val VpnProviders = listOf(
    VpnProvider("Proton VPN", 0xFF6D4AFF, "P", "free", "Free plan works · sign in on Proton's site", "https://account.protonvpn.com/downloads"),
    VpnProvider("Mullvad", 0xFF294D73, "M", "paid", "Account number · mullvad.net", "https://mullvad.net/en/account/wireguard-config"),
    VpnProvider("Surfshark", 0xFF1EB6A8, "S", "paid", "Sign in · surfshark.com › Manual setup", "https://my.surfshark.com/vpn/manual-setup/main/wireguard"),
    VpnProvider("Windscribe", 0xFF1A5F9E, "W", "paid", "Pro plan · windscribe.com", "https://windscribe.com/getconfig/wireguard"),
    VpnProvider("IVPN", 0xFF3A7BD5, "I", "paid", "Account ID · ivpn.net", "https://www.ivpn.net/account/"),
    VpnProvider("NordVPN", 0xFF3E5FFF, "N", "no", "Doesn't give WireGuard files — keep its app on", ""),
    VpnProvider("ExpressVPN", 0xFFDA3940, "E", "no", "Doesn't give WireGuard files — keep its app on", ""),
)
