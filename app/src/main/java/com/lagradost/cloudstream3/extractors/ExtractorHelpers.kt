package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.JsUnpacker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder

internal const val CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"
internal const val CALL_TIMEOUT_MS = 20_000L

internal fun browserHeaders(vararg extra: Pair<String, String>): Map<String, String> =
    mapOf("User-Agent" to CHROME_UA, "Accept-Language" to "en-US,en;q=0.9") + extra

/** Runs [block] with a 20 s total limit and never lets an error escape (except cancellation). */
internal suspend fun guarded(block: suspend () -> Unit) {
    try {
        withTimeoutOrNull(CALL_TIMEOUT_MS) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
    }
}

internal fun hostOf(url: String): String? =
    Regex("^(https?://[^/?#]+)").find(url)?.groupValues?.get(1)

internal fun absUrl(u: String, base: String?): String = when {
    u.startsWith("//") -> "https:$u"
    u.startsWith("http") -> u
    base != null && u.startsWith("/") -> base.trimEnd('/') + u
    else -> u
}

internal fun fileNameOf(url: String): String {
    val raw = url.substringBefore('?').substringAfterLast('/')
    return try { URLDecoder.decode(raw, "UTF-8") } catch (_: Throwable) { raw }
}

private const val ALNUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
internal fun randomString(n: Int): String = (1..n).map { ALNUM.random() }.joinToString("")
internal fun randomHex(n: Int): String = (1..n).map { "0123456789abcdef".random() }.joinToString("")
internal fun toHex(s: String): String = s.toByteArray().joinToString("") { "%02x".format(it) }

internal fun findPacked(html: String): String? =
    Regex("(eval\\(function\\(p,a,c,k,e,.+?)</script>", RegexOption.DOT_MATCHES_ALL)
        .find(html)?.groupValues?.get(1)?.trim()

internal fun unpackAll(html: String): String? =
    findPacked(html)?.let { try { JsUnpacker(it).unpack() } catch (_: Throwable) { null } }

internal fun pageTitle(html: String): String? =
    Regex("<title>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        .find(html)?.groupValues?.get(1)?.trim()

internal val MEDIA_REGEX = Regex("https?://[^\"'\\s<>\\\\]+\\.(?:m3u8|mp4|mkv|webm|avi)(?:\\?[^\"'\\s<>\\\\]*)?")

/** Generic XFileSharing-style flow used by Indishare and ClickNUpload. Returns a direct file URL. */
internal suspend fun xfsDirectLink(pageUrl: String, referer: String?): String? {
    var resp = app.get(pageUrl, headers = browserHeaders(), referer = referer)
    var current = resp.url.ifBlank { pageUrl }
    for (round in 0 until 3) {
        MEDIA_REGEX.find(resp.text)?.value?.let { return it }
        val form = resp.document.select("form").firstOrNull { f ->
            f.select("input[name=op]").any { it.attr("value").startsWith("download") }
        } ?: return null
        val data = linkedMapOf<String, String>()
        form.select("input[name], button[name]").forEach { el ->
            val n = el.attr("name")
            if (n.isNotBlank() && n != "method_premium") {
                data[n] = el.attr("value")
            }
        }
        if (data.containsKey("method_free") && data["method_free"].isNullOrBlank()) {
            data["method_free"] = "Free Download"
        }
        val secs = Regex("<span id=\"countdown_str\"[^>]*>.*?<span[^>]*>(\\d+)</span>", RegexOption.DOT_MATCHES_ALL)
            .find(resp.text)?.groupValues?.get(1)?.toLongOrNull()
        if (secs != null && secs in 1..10) delay((secs + 1) * 1000)
        val action = form.attr("action").ifBlank { current }
        val target = absUrl(action, hostOf(current))
        resp = app.post(target, data = data, headers = browserHeaders(), referer = current)
        current = target
    }
    return MEDIA_REGEX.find(resp.text)?.value
}
