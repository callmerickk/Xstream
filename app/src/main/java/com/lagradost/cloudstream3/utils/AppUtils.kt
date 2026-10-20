package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.mapper

object AppUtils {
    fun Any.toJson(): String = if (this is String) this else mapper.writeValueAsString(this)
    inline fun <reified T> parseJson(value: String): T = mapper.readValue(value, T::class.java)
    inline fun <reified T> tryParseJson(value: String?): T? = runCatching { mapper.readValue(value ?: return null, T::class.java) }.getOrNull()
}
