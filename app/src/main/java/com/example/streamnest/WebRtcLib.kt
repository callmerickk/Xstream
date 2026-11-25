package com.example.streamnest

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * 0.9.6 (webrtcOnDemand): Watch together's native library (libjingle_peerconnection_so.so, ~11 MB) is not in the APK.
 * It is downloaded once, checked against a SHA-256 written in this file, kept in filesDir/native/ and loaded from there.
 * Any failure returns null -> Call.kt's existing "couldn't connect" path. Nothing else is touched.
 */
object WebRtcLib {
    private const val BASE = "https://raw.githubusercontent.com/callmerickk/Xstream/assets/webrtc"
    private const val FILE = "libjingle_peerconnection_so.so"
    private const val LIB_VERSION = "1.3.9"    // = io.getstream:stream-webrtc-android in app/build.gradle.kts; bump with the SHA values

    /** SHA-256 of the files on the assets branch (webrtc/<abi>/), for stream-webrtc-android 1.3.9. The workflow
     *  (tools/webrtc-assets.sh) checks these against the AAR on every build and fails the build if they differ. */
    private val SHA = mapOf(
        "arm64-v8a" to "4e9d2df81f7db6d184a9d0024e6f9825c6325c3ee54a8d61ef657f02ef8d85e7",
        "armeabi-v7a" to "5c4e33201809f56eb41ead202000f7ba8afa0a87ce159af96d18d940622c5c78",
    )

    /** 0..100 while downloading, null otherwise. The call UI shows "Getting Watch together ready… 40 %". */
    val progress = MutableStateFlow<Int?>(null)
    private val lock = Any()
    @Volatile private var verified: String? = null

    /** The ABI this process runs as (an arm64 APK on a 64-bit process, the arm32 APK on a 32-bit one). */
    private fun abi() = if (android.os.Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"

    private fun dir(ctx: Context) = File(ctx.filesDir, "native").apply { mkdirs() }
    private fun target(ctx: Context) = File(dir(ctx), "libjingle_peerconnection_so-$LIB_VERSION-${abi()}.so")

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun wanted(): String? = SHA[abi()]?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }

    /** The checked local file, or null. Never touches the network. */
    fun cached(ctx: Context): File? {
        val want = wanted() ?: return null
        val f = target(ctx)
        if (!f.isFile) return null
        if (verified == f.path) return f
        if (!sha256(f).equals(want, true)) { f.delete(); return null }     // damaged / replaced: start over
        verified = f.path
        return f
    }

    private fun unmetered(ctx: Context) = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }.getOrDefault(false)

    /** 0.9.9: with webrtcOnDemand off the APK carries the library; a copy downloaded by 0.9.6–0.9.8 is deleted at start
     *  (nothing loads it any more: Call.ensureFactory only sets the loader when the switch is on). */
    fun forgetDownloaded(ctx: Context) {
        if (Feature.webrtcOnDemand) return
        runCatching {
            val d = File(ctx.applicationContext.filesDir, "native")
            val old = d.listFiles()?.filter { it.name.startsWith("libjingle_peerconnection_so") }.orEmpty()
            if (old.isEmpty()) return
            var kb = 0L
            old.forEach { kb += it.length() / 1024; it.setWritable(true); it.delete() }
            if (d.listFiles().isNullOrEmpty()) d.delete()
            ActLog.add("ROOM", "webrtc lib: removed the downloaded copy ($kb KB) — the app carries it now")
        }
    }

    /** Optional: quiet pre-download on Wi-Fi only (e.g. when a room is opened), so the first call starts at once. */
    fun prefetchOnWifi(ctx: Context) {
        val app = ctx.applicationContext
        if (cached(app) != null || !unmetered(app)) return
        Thread({ fetch(app, userStarted = false) }, "webrtc-lib").apply { isDaemon = true }.start()
    }

    /**
     * Returns the library file, downloading it when needed. [userStarted] = the user pressed call: allowed on mobile data too.
     * BLOCKS (call it from the call's own thread, as Call.ensureFactory runs). On the main thread it only starts the download.
     */
    fun fetch(ctx: Context, userStarted: Boolean): File? {
        val app = ctx.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread({ fetch(app, userStarted) }, "webrtc-lib").apply { isDaemon = true }.start(); return null
        }
        cached(app)?.let { return it }
        if (!userStarted && !unmetered(app)) return null
        val abi = abi()
        val want = wanted()
        if (want == null) { ActLog.add("ROOM", "webrtc lib: no checksum for $abi in this build"); return null }
        synchronized(lock) {
            cached(app)?.let { return it }
            val out = target(app)
            val tmp = File(dir(app), out.name + ".part")
            try {
                progress.value = 0
                Api.http.newCall(Request.Builder().url("$BASE/$abi/$FILE").build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val body = r.body ?: error("empty answer")
                    val total = body.contentLength()
                    var done = 0L; var last = -1
                    body.byteStream().use { inp -> tmp.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            o.write(buf, 0, n); done += n
                            if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; progress.value = p } }
                        }
                    } }
                }
                if (!sha256(tmp).equals(want, true)) { tmp.delete(); error("sha mismatch") }
                out.delete()
                if (!tmp.renameTo(out)) error("could not save")
                out.setReadOnly()     // Android 14 (target 34) wants dynamically loaded files read-only
                verified = out.path
                ActLog.add("ROOM", "webrtc lib downloaded · $abi · ${out.length() / 1024} KB · sha ok")
                return out
            } catch (e: Exception) {
                tmp.delete()
                ActLog.add("ROOM", "webrtc lib download failed: ${e.message}")
                return null
            } finally { progress.value = null }
        }
    }
}

