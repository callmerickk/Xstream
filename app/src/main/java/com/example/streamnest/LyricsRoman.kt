package com.example.streamnest

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.icu.text.Transliterator
import java.util.concurrent.ConcurrentHashMap

/**
 * musicFix1 · lyrics in Roman script ("तुम मेरे हो" -> "Tum mere ho").
 * Uses Android's own ICU Transliterator (API 29+). Below that, or on any error, the original lines are shown.
 * Results are cached per song in memory only.
 */
object LyricsRoman {
    private val cache = ConcurrentHashMap<String, List<String>>()
    private val devaRun = Regex("[\\u0900-\\u0963\\u0966-\\u097F]+")
    private val marks = Regex("\\p{M}+")
    private val spaces = Regex("\\s{2,}")
    private val aaEnd = Regex("aa$")
    private val iiEnd = Regex("ii$")
    private val uuEnd = Regex("uu$")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)

    /** "roman" (default), "orig" or "en". */
    fun savedMode(ctx: Context): String =
        prefs(ctx).getString("lyricsMode", "roman")?.takeIf { it == "roman" || it == "orig" || it == "en" } ?: "roman"

    fun saveMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString("lyricsMode", mode).apply()
        MusicLog.add("lyrics: showing " + when (mode) { "roman" -> "Roman letters"; "orig" -> "the original script"; else -> "the English translation" })
    }

    /** The lines in Roman letters (same count, same order). Lines already in Latin script are returned untouched. */
    fun lines(key: String, lines: List<String>): List<String> {
        val ck = key + "#" + lines.hashCode()
        cache[ck]?.let { return it }
        if (Build.VERSION.SDK_INT < 29) {
            MusicLog.add("lyrics: Roman letters need Android 10 or newer, showing the original script")
            return lines
        }
        val out = runCatching { convert(lines) }.getOrElse {
            ActLog.add("MUSIC", "lyrics Roman failed: " + it.message)
            MusicLog.add("lyrics: Roman conversion failed, showing the original script")
            lines
        }
        val changed = out.indices.count { out[it] != lines[it] }
        MusicLog.add("lyrics: Roman for \"$key\": $changed of ${lines.size} lines converted")
        if (cache.size > 40) cache.clear()
        cache[ck] = out
        return out
    }

    @RequiresApi(29)
    private fun convert(lines: List<String>): List<String> {
        val deva = Transliterator.getInstance("Devanagari-Latin; Latin-ASCII")
        val any = Transliterator.getInstance("Any-Latin; Latin-ASCII")
        return lines.map { romanLine(it, deva, any) }
    }

    private fun isNonLatinLetter(c: Char): Boolean =
        c.isLetter() && Character.UnicodeScript.of(c.code) != Character.UnicodeScript.LATIN

    private fun isDeva(c: Char) = c in '\u0900'..'\u097F'

    @RequiresApi(29)
    private fun romanLine(line: String, deva: Transliterator, any: Transliterator): String {
        if (line.none(::isNonLatinLetter)) return line
        var s = line
        val hindi = s.any(::isDeva)
        if (hindi) {
            // Danda marks are dropped; every Devanagari word is converted on its own (the silent-"a" rule is per word).
            s = devaRun.replace(s.replace("\u0964", "").replace("\u0965", "")) { m -> hindiWord(m.value, deva) }
        }
        // Anything else that is not Latin (Gurmukhi, Tamil, Bengali, Urdu, Arabic ...).
        if (s.any(::isNonLatinLetter)) s = any.transliterate(s)
        s = stripMarks(s)
        if (hindi) s = capital(spaces.replace(s, " ").trim())
        return s
    }

    @RequiresApi(29)
    private fun hindiWord(raw: String, t: Transliterator): String {
        val w = raw.filter { it != '\u200C' && it != '\u200D' }
        if (w.isEmpty()) return ""
        var r = stripMarks(t.transliterate(w))
        // A word that ends on a consonant with no vowel sign loses ICU's added final "a" (तुम -> tum, दिल -> dil).
        // Ends with a vowel sign (मेरा, हो), a virama or a nasal mark: left as it is.
        val last = w.last()
        val bareConsonant = last in '\u0915'..'\u0939' || last in '\u0958'..'\u095F' || last == '\u093C'
        if (bareConsonant && r.endsWith("a")) r = r.dropLast(1)
        r = r.replace("~", "").replace(".", "")
        r = aaEnd.replace(r, "a"); r = iiEnd.replace(r, "i"); r = uuEnd.replace(r, "u")
        return r
    }

    private fun stripMarks(s: String): String =
        marks.replace(java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD), "")

    private fun capital(s: String): String {
        val i = s.indexOfFirst { it.isLetter() }
        return if (i < 0) s else s.substring(0, i) + s[i].uppercaseChar() + s.substring(i + 1)
    }

    /** The label of the lyrics' own script, written in that script ("हिंदी"). Null when the lyrics are all Latin. */
    fun scriptLabel(lines: List<String>?): String? {
        if (lines.isNullOrEmpty()) return null
        val counts = HashMap<Character.UnicodeScript, Int>()
        var urdu = false
        for (l in lines) for (c in l) {
            if (!isNonLatinLetter(c)) continue
            counts.merge(Character.UnicodeScript.of(c.code), 1, Int::plus)
            if (c == '\u0679' || c == '\u0688' || c == '\u0691' || c == '\u06BA' || c == '\u06D2') urdu = true
        }
        if (counts.isEmpty()) return null
        val kana = (counts[Character.UnicodeScript.HIRAGANA] ?: 0) + (counts[Character.UnicodeScript.KATAKANA] ?: 0)
        if (kana > 0) return "日本語"
        val top = counts.maxByOrNull { it.value }!!.key
        return when (top) {
            Character.UnicodeScript.DEVANAGARI -> "हिंदी"
            Character.UnicodeScript.GURMUKHI -> "ਪੰਜਾਬੀ"
            Character.UnicodeScript.TAMIL -> "தமிழ்"
            Character.UnicodeScript.BENGALI -> "বাংলা"
            Character.UnicodeScript.TELUGU -> "తెలుగు"
            Character.UnicodeScript.KANNADA -> "ಕನ್ನಡ"
            Character.UnicodeScript.MALAYALAM -> "മലയാളം"
            Character.UnicodeScript.GUJARATI -> "ગુજરાતી"
            Character.UnicodeScript.ARABIC -> if (urdu) "اردو" else "العربية"
            Character.UnicodeScript.CYRILLIC -> "Русский"
            Character.UnicodeScript.HANGUL -> "한국어"
            Character.UnicodeScript.HAN -> "中文"
            Character.UnicodeScript.THAI -> "ไทย"
            Character.UnicodeScript.HEBREW -> "עברית"
            Character.UnicodeScript.GREEK -> "Ελληνικά"
            else -> top.name.lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}

/** Roman · original script · English, at the top of the Lyrics tab. */
@Composable
fun LyricsModeBar(mode: String, scriptLabel: String?, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val chips = buildList {
        add("roman" to "Roman")
        if (scriptLabel != null) add("orig" to scriptLabel)
        add("en" to "English")
    }
    val selected = if (mode == "orig" && scriptLabel == null) "roman" else mode
    Row(
        modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xF20E0E0E), Color.Transparent)))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { (k, label) ->
            val on = k == selected
            Text(label, Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Color.White else Color(0x22FFFFFF))
                .clickable { onPick(k) }.padding(horizontal = 14.dp, vertical = 7.dp),
                color = if (on) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
