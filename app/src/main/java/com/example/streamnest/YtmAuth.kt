package com.example.streamnest

import okhttp3.Request
import java.security.MessageDigest

/** Builds the headers YouTube Music wants from a signed-in browser cookie. Never logs cookie values. */
object YtmAuth {
    const val ORIGIN = "https://music.youtube.com"

    /** Keep this the same as the client version in YtMusic.kt's request context. */
    val CLIENT_VERSION = YtMusic.CLIENT_VERSION

    fun cookieValue(cookie: String, name: String): String? =
        cookie.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }

    private fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** "SAPISIDHASH <time>_<sha1(time SAPISID origin)>", or null if the cookie has no SAPISID. */
    fun sapisidHash(cookie: String, nowSec: Long = System.currentTimeMillis() / 1000): String? {
        val sid = cookieValue(cookie, "SAPISID") ?: cookieValue(cookie, "__Secure-3PAPISID") ?: return null
        return "SAPISIDHASH ${nowSec}_${sha1Hex("$nowSec $sid $ORIGIN")}"
    }

    /** Adds Cookie + Authorization (and the origin headers) to a request. No cookie: the builder is returned unchanged. */
    fun apply(b: Request.Builder, cookie: String?): Request.Builder {
        if (cookie.isNullOrBlank()) return b
        val auth = sapisidHash(cookie) ?: return b
        return b.header("Cookie", cookie)
            .header("Authorization", auth)
            .header("X-Goog-AuthUser", "0")
            .header("X-Origin", ORIGIN)
            .header("Origin", ORIGIN)
    }

    /** Short, non-reversible tag for the cache: "anon" when signed out, else part of a hash of SAPISID. */
    fun cacheScope(cookie: String? = MusicAccount.activeCookie()): String {
        if (cookie.isNullOrBlank()) return "anon"
        val sid = cookieValue(cookie, "SAPISID") ?: cookieValue(cookie, "__Secure-3PAPISID") ?: return "anon"
        return "u" + sha1Hex("scope:$sid").take(10)
    }
}
