package com.lagradost.nicehttp

import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.reflect.KClass
import kotlinx.coroutines.suspendCancellableCoroutine

// Xstream's own small HTTP helper with the same shape as the one CloudStream plugins call (`app.get / post / head`).

interface ResponseParser {
    fun <T : Any> parse(text: String, kClass: KClass<T>): T
    fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T?
    fun writeValueAsString(obj: Any): String
}

class NiceFile(val name: String, val fileName: String, val file: File?, val fileType: String?) {
    constructor(name: String, value: String) : this(name, value, null, null)
    constructor(file: File) : this(file.name, file.name, file, null)
}

class NiceResponse(val okhttpResponse: Response, val parser: ResponseParser?) {
    val code: Int get() = okhttpResponse.code
    val isSuccessful: Boolean get() = okhttpResponse.isSuccessful
    val headers: Headers get() = okhttpResponse.headers
    val url: String get() = okhttpResponse.request.url.toString()
    val body get() = okhttpResponse.body
    val text: String by lazy { runCatching { okhttpResponse.body?.string() }.getOrNull().orEmpty() }
    /** Same as [text] (CloudStream reads big pages this way). */
    val textLarge: String get() = text
    val document: Document by lazy { Jsoup.parse(text, url) }
    val cookies: Map<String, String> by lazy {
        okhttpResponse.headers("set-cookie").mapNotNull { Cookie.parse(okhttpResponse.request.url, it) }.associate { it.name to it.value }
    }
    val size: Long? get() = okhttpResponse.headers["Content-Length"]?.toLongOrNull()
    inline fun <reified T : Any> parsed(): T = parser!!.parse(text, T::class)
    inline fun <reified T : Any> parsedSafe(): T? = parser?.parseSafe(text, T::class)
    override fun toString(): String = text
}

class Requests(
    var baseClient: OkHttpClient = OkHttpClient(),
    var defaultHeaders: Map<String, String> = mapOf("user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"),
    var defaultReferer: String? = null,
    var defaultData: Map<String, String> = mapOf(),
    var defaultCookies: Map<String, String> = mapOf(),
    var defaultCacheTime: Int = 0,
    var defaultCacheTimeUnit: TimeUnit = TimeUnit.MINUTES,
    var defaultTimeOut: Long = 0L,
    var responseParser: ResponseParser? = null,
) {
    private fun client(allowRedirects: Boolean, timeout: Long, interceptor: Interceptor?, verify: Boolean): OkHttpClient {
        if (allowRedirects && timeout <= 0L && interceptor == null && verify) return baseClient
        val b = baseClient.newBuilder().followRedirects(allowRedirects).followSslRedirects(allowRedirects)
        if (timeout > 0L) b.callTimeout(timeout, TimeUnit.SECONDS)
        if (interceptor != null) b.addInterceptor(interceptor)
        if (!verify) {
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
            b.sslSocketFactory(ssl.socketFactory, tm).hostnameVerifier { _, _ -> true }
        }
        return b.build()
    }

    private fun build(method: String, url: String, headers: Map<String, String>, referer: String?, params: Map<String, String>, cookies: Map<String, String>, body: RequestBody?): Request {
        val u = url.toHttpUrlOrNull()?.newBuilder()?.apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }?.build()
        val rb = Request.Builder()
        if (u != null) rb.url(u) else rb.url(url)
        (defaultHeaders + headers).forEach { (k, v) -> rb.header(k, v) }
        (referer ?: defaultReferer)?.let { rb.header("referer", it) }
        val ck = defaultCookies + cookies
        if (ck.isNotEmpty()) rb.header("cookie", ck.entries.joinToString("; ") { "${it.key}=${it.value}" })
        rb.method(method, body)
        return rb.build()
    }

    private suspend fun execute(c: OkHttpClient, r: Request, parser: ResponseParser?): NiceResponse = suspendCancellableCoroutine { cont ->
        val call = c.newCall(r)
        val t0 = System.currentTimeMillis()
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                com.lagradost.cloudstream3.CsBridge.note("http ${r.method} ${r.url.toString().take(110)} ✗ ${e.javaClass.simpleName}: ${e.message?.take(80)} (${System.currentTimeMillis() - t0} ms)")
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                val nr = NiceResponse(response, parser ?: responseParser)
                // Read the body here (off the main thread) so .text never blocks a caller later.
                val body = runCatching { nr.text }.getOrDefault("")
                // Every request a plugin makes, for the plugin test log (status, size, time, Cloudflare).
                val cf = response.header("server")?.contains("cloudflare", true) == true && (response.code == 403 || response.code == 503)
                com.lagradost.cloudstream3.CsBridge.note("http ${r.method} ${r.url.toString().take(110)} → ${response.code}${if (cf) " (Cloudflare check)" else ""} · ${body.length / 1024} KB · ${System.currentTimeMillis() - t0} ms" +
                    (if (response.request.url != r.url) " · ended at ${response.request.url.host}" else ""))
                if (cont.isActive) cont.resume(nr)
            }
        })
    }

    suspend fun get(
        url: String, headers: Map<String, String> = mapOf(), referer: String? = null, params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(), allowRedirects: Boolean = true, cacheTime: Int = defaultCacheTime,
        cacheUnit: TimeUnit = defaultCacheTimeUnit, timeout: Long = defaultTimeOut, interceptor: Interceptor? = null,
        verify: Boolean = true, responseParser: ResponseParser? = this.responseParser,
    ): NiceResponse = execute(client(allowRedirects, timeout, interceptor, verify), build("GET", url, headers, referer, params, cookies, null), responseParser)

    suspend fun head(
        url: String, headers: Map<String, String> = mapOf(), referer: String? = null, params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(), allowRedirects: Boolean = true, cacheTime: Int = defaultCacheTime,
        cacheUnit: TimeUnit = defaultCacheTimeUnit, timeout: Long = defaultTimeOut, interceptor: Interceptor? = null,
        verify: Boolean = true, responseParser: ResponseParser? = this.responseParser,
    ): NiceResponse = execute(client(allowRedirects, timeout, interceptor, verify), build("HEAD", url, headers, referer, params, cookies, null), responseParser)

    suspend fun post(
        url: String, headers: Map<String, String> = mapOf(), referer: String? = null, params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(), data: Map<String, String>? = defaultData, files: List<NiceFile>? = null, json: Any? = null,
        requestBody: RequestBody? = null, allowRedirects: Boolean = true, cacheTime: Int = defaultCacheTime,
        cacheUnit: TimeUnit = defaultCacheTimeUnit, timeout: Long = defaultTimeOut, interceptor: Interceptor? = null,
        verify: Boolean = true, responseParser: ResponseParser? = this.responseParser,
    ): NiceResponse {
        val body: RequestBody = requestBody
            ?: json?.let { j -> (if (j is String) j else (responseParser ?: this.responseParser)?.writeValueAsString(j) ?: j.toString()).toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()) }
            ?: files?.takeIf { it.isNotEmpty() }?.let { fs ->
                MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                    fs.forEach { f -> if (f.file != null) addFormDataPart(f.name, f.fileName, f.file.asRequestBody(f.fileType?.toMediaTypeOrNull())) else addFormDataPart(f.name, f.fileName) }
                    data?.forEach { (k, v) -> addFormDataPart(k, v) }
                }.build()
            }
            ?: FormBody.Builder().apply { data?.forEach { (k, v) -> add(k, v) } }.build()
        return execute(client(allowRedirects, timeout, interceptor, verify), build("POST", url, headers, referer, params, cookies, body), responseParser)
    }

    suspend fun put(
        url: String, headers: Map<String, String> = mapOf(), referer: String? = null, params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(), data: Map<String, String>? = defaultData, json: Any? = null, requestBody: RequestBody? = null,
        timeout: Long = defaultTimeOut, responseParser: ResponseParser? = this.responseParser,
    ): NiceResponse {
        val body = requestBody ?: json?.let { j -> (if (j is String) j else this.responseParser?.writeValueAsString(j) ?: j.toString()).toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()) }
            ?: FormBody.Builder().apply { data?.forEach { (k, v) -> add(k, v) } }.build()
        return execute(client(true, timeout, null, true), build("PUT", url, headers, referer, params, cookies, body), responseParser)
    }
}

/** For plugins that build requests themselves. */
fun getHeaders(headers: Map<String, String>, referer: String?, cookie: Map<String, String>): Headers {
    val m = headers.toMutableMap()
    referer?.let { m["referer"] = it }
    if (cookie.isNotEmpty()) m["cookie"] = cookie.entries.joinToString("; ") { "${it.key}=${it.value}" }
    return Headers.Builder().apply { m.forEach { (k, v) -> add(k, v) } }.build()
}
