package com.lagradost.cloudstream3

import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import kotlin.reflect.KClass

/** The shared HTTP client plugins use (`app.get(...)`), set up by Xstream with its own OkHttp client. */
var app: Requests = Requests(responseParser = object : ResponseParser {
    override fun <T : Any> parse(text: String, kClass: KClass<T>): T = mapper.readValue(text, kClass.java)
    override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? = runCatching { mapper.readValue(text, kClass.java) }.getOrNull()
    override fun writeValueAsString(obj: Any): String = mapper.writeValueAsString(obj)
}).apply { defaultHeaders = mapOf("user-agent" to USER_AGENT) }

const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
