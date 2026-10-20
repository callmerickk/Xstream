package com.lagradost.cloudstream3.utils

/** Unpacks "eval(function(p,a,c,k,e,d)...)" packed JavaScript (our own implementation). */
class JsUnpacker(private val packed: String?) {
    fun detect(): Boolean = packed?.replace(" ", "")?.contains("eval(function(p,a,c,k,e,") == true

    fun unpack(): String? {
        val js = packed ?: return null
        val m = Regex("""\}\s*\(\s*'(.*?)'\s*,\s*(\d+|\[\])\s*,\s*(\d+)\s*,\s*'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL).find(js) ?: return null
        val payload = m.groupValues[1].replace("\\'", "'")
        val radix = m.groupValues[2].toIntOrNull() ?: 62
        val count = m.groupValues[3].toIntOrNull() ?: 0
        val words = m.groupValues[4].split("|")
        if (words.size != count && count != 0 && words.size < count) return null
        fun decode(word: String): Int {
            var n = 0
            for (ch in word) {
                val d = when (ch) { in '0'..'9' -> ch - '0'; in 'a'..'z' -> ch - 'a' + 10; in 'A'..'Z' -> ch - 'A' + 36; else -> return -1 }
                n = n * radix + d
            }
            return n
        }
        return Regex("\\b\\w+\\b").replace(payload) { w ->
            val i = decode(w.value)
            if (i in words.indices && words[i].isNotEmpty()) words[i] else w.value
        }
    }
}
