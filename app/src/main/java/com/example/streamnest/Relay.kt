package com.example.streamnest

import android.net.Uri
import android.util.Base64
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors

/*
 * Header relay: a tiny web server inside the app (127.0.0.1 only).
 * VLC can only send a User-Agent and a Referer, but some addons (e.g. MovieBox) need more headers
 * (Origin, Cookie, custom ones…). VLC asks the relay for the video; the relay fetches it with every
 * header the addon gave, through the app's HTTP client (so the DNS setting applies too).
 * Seeking works: the Range header is passed through. HLS playlists are rewritten so every
 * segment also goes through the relay.
 */
object Relay {
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "relay").apply { isDaemon = true } }

    /** Opens the relay in the background at app start, so the first video doesn't wait for it. */
    fun warmUp() { pool.execute { runCatching { ensure() } } }

    private fun ensure(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        synchronized(this) {
            server?.takeIf { !it.isClosed }?.let { return it.localPort }
            val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            server = s
            pool.execute {
                while (!s.isClosed) {
                    val c = runCatching { s.accept() }.getOrNull() ?: break
                    pool.execute { runCatching { handle(c) }; runCatching { c.close() } }
                }
            }
            return s.localPort
        }
    }

    private fun enc(u: String) = Base64.encodeToString(u.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun dec(s: String) = String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))

    /** Local address that plays [url] with all of its headers. */
    fun wrap(url: String): String {
        val port = ensure()
        // Keep the file extension at the end, so players can guess the format.
        val ext = Uri.parse(url).lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.length in 2..4 && it.all { c -> c.isLetterOrDigit() } }
        return "http://127.0.0.1:$port/r/${enc(url)}" + (ext?.let { "/v.$it" } ?: "")
    }

    fun isWrapped(url: String) = url.startsWith("http://127.0.0.1:") && url.contains("/r/")

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 16_384) return sb.toString()
        }
    }

    private fun handle(c: Socket) {
        c.soTimeout = 30_000
        c.sendBufferSize = 512 * 1024; c.tcpNoDelay = true
        val inp = BufferedInputStream(c.getInputStream())
        val out = c.getOutputStream()
        val first = readLine(inp) ?: return
        val parts = first.split(" ")
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val reqHeaders = HashMap<String, String>()
        while (true) {
            val l = readLine(inp) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) reqHeaders[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val path = parts[1]
        val token = path.substringAfter("/r/", "").substringBefore("/").substringBefore("?")
        val target = runCatching { dec(token) }.getOrNull()
        if (target.isNullOrBlank() || !target.startsWith("http")) { status(out, 400, "Bad Request"); return }

        val h = StreamHeaders.forUrl(target).ifEmpty { StreamHeaders.active }
        val b = Request.Builder().url(target)
        if (method == "HEAD") b.head()
        h.forEach { (k, v) -> runCatching { b.header(k, v) } }
        if (h.keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", PlayerUserAgent)
        reqHeaders["range"]?.let { b.header("Range", it) }
        b.header("Accept-Encoding", "identity")

        val resp = try { Api.http.newCall(b.build()).execute() } catch (e: Exception) { status(out, 502, "Bad Gateway"); return }
        resp.use { r ->
            val body = r.body
            val type = r.header("Content-Type").orEmpty()
            val isPlaylist = type.contains("mpegurl", true) || target.substringBefore('?').endsWith(".m3u8", true)
            if (isPlaylist && r.isSuccessful && method != "HEAD") {
                val text = body?.string().orEmpty()
                val base = r.request.url.toString()
                val bytes = rewritePlaylist(text, base).toByteArray()
                val head = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                out.write(head.toByteArray()); out.write(bytes); out.flush()
                return
            }
            val sb = StringBuilder("HTTP/1.1 ${r.code} ${r.message.ifBlank { "OK" }}\r\n")
            listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag").forEach { k ->
                r.header(k)?.let { sb.append(k).append(": ").append(it).append("\r\n") }
            }
            if (r.header("Accept-Ranges") == null) sb.append("Accept-Ranges: bytes\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray())
            if (method != "HEAD" && body != null) {
                val src = body.byteStream()
                val buf = ByteArray(256 * 1024)
                try {
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                } catch (e: Exception) { /* the player closed the connection (seek / stop) */ }
            }
            runCatching { out.flush() }
        }
    }

    private fun status(out: OutputStream, code: Int, msg: String) {
        runCatching { out.write("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush() }
    }

    private fun resolve(base: String, ref: String): String = runCatching { URI(base).resolve(ref.trim()).toString() }.getOrDefault(ref)

    /** Every link in an HLS playlist (segments, variants, keys, audio/subtitle renditions) goes through the relay too. */
    private fun rewritePlaylist(text: String, base: String): String = text.lines().joinToString("\n") { line ->
        val t = line.trim()
        when {
            t.isEmpty() -> line
            t.startsWith("#") -> Regex("URI=\"([^\"]+)\"").replace(line) { m -> "URI=\"${wrap(resolve(base, m.groupValues[1]))}\"" }
            else -> wrap(resolve(base, t))
        }
    }
}
