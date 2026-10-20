package com.lagradost.cloudstream3.network

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CsBridge
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Passes Cloudflare's "checking your browser" page (Xstream's own version): when a site answers with the challenge,
 * the page is opened once in a hidden WebView until it hands out its clearance cookie, then the request is repeated with
 * that cookie and the WebView's user agent.
 */
class CloudflareKiller : Interceptor {
    companion object {
        @Volatile private var webUa: String? = null
        private val solvedHosts = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }

    private fun cookieFor(url: String): String? = CookieManager.getInstance().getCookie(url)

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val host = req.url.host
        val withCookies = { r: okhttp3.Request ->
            val c = cookieFor(r.url.toString())
            r.newBuilder().apply { if (!c.isNullOrBlank()) header("cookie", c); webUa?.let { header("user-agent", it) } }.build()
        }
        val first = chain.proceed(if (solvedHosts.containsKey(host)) withCookies(req) else req)
        val challenged = (first.code == 403 || first.code == 503) && (first.header("server")?.contains("cloudflare", true) == true)
        if (!challenged) return first
        first.close()
        CsBridge.note("Cloudflare check on $host: solving in a hidden browser…")
        val t0 = System.currentTimeMillis()
        solve(req.url.toString())
        CsBridge.note("Cloudflare on $host: ${if (cookieFor(req.url.toString())?.contains("cf_clearance") == true) "passed" else "no clearance"} in ${System.currentTimeMillis() - t0} ms")
        solvedHosts[host] = System.currentTimeMillis()
        return chain.proceed(withCookies(req))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun solve(url: String) {
        val ctx = CloudStreamApp.context ?: return
        val done = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())
        var web: WebView? = null
        main.post {
            runCatching {
                val w = WebView(ctx)
                web = w
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                webUa = w.settings.userAgentString
                CookieManager.getInstance().setAcceptCookie(true)
                w.webViewClient = object : WebViewClient() {}
                w.loadUrl(url)
                fun poll(n: Int) {
                    if (cookieFor(url)?.contains("cf_clearance") == true || n > 40) { done.countDown(); return }
                    main.postDelayed({ poll(n + 1) }, 500)
                }
                poll(0)
            }.onFailure { done.countDown() }
        }
        done.await(25, TimeUnit.SECONDS)
        main.post { runCatching { web?.stopLoading(); web?.destroy() } }
    }
}
