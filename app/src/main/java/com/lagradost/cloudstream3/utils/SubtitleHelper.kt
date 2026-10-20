package com.lagradost.cloudstream3.utils

import java.util.Locale

/** Language names for subtitle tags ("en" / "eng" -> "English"). */
object SubtitleHelper {
    fun fromTagToEnglishLanguageName(input: String?): String? {
        val tag = input?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val loc = if (tag.length == 3) Locale.getAvailableLocales().firstOrNull { runCatching { it.isO3Language }.getOrNull() == tag.lowercase() }
            else Locale.forLanguageTag(tag)
        return loc?.getDisplayLanguage(Locale.ENGLISH)?.takeIf { it.isNotBlank() && it != tag }
    }
    fun fromTwoLettersToLanguage(input: String): String? = fromTagToEnglishLanguageName(input)
    fun fromThreeLettersToLanguage(input: String): String? = fromTagToEnglishLanguageName(input)
    fun fromLanguageToTwoLetters(input: String, looseCheck: Boolean = false): String? =
        Locale.getAvailableLocales().firstOrNull { it.getDisplayLanguage(Locale.ENGLISH).equals(input, true) || (looseCheck && it.getDisplayLanguage(Locale.ENGLISH).contains(input, true)) }?.language
}
