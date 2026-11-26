package com.example.streamnest

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import java.util.concurrent.ConcurrentHashMap

/** musicFix1 · the accent colour of the Music look. Default #E50914; the look code can set it. */
object MusicFix1 {
    val accent: Color get() = MusicLook.current.value.accent
}

/** musicFix1 · sharp YouTube pictures: address rules and the video-frame fallback chain. */
object MusicImages {
    // i.ytimg.com, i1..i9.ytimg.com, /vi/ and /vi_webp/, with or without "?sqp=..."
    private val frame = Regex("^https?://i[1-9]?\\.ytimg\\.com/vi(?:_webp)?/([^/?#]+)/")
    /** Frame addresses that already answered "not found" (so a scrolled-back card doesn't ask again). */
    val bad: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The chain for a video frame: maxresdefault -> hq720 -> sddefault -> hqdefault. Null when [url] is not a video frame. */
    fun frames(url: String?): List<String>? {
        val id = url?.let { frame.find(it)?.groupValues?.get(1) } ?: return null
        return listOf("maxresdefault", "hq720", "sddefault", "hqdefault").map { "https://i.ytimg.com/vi/$id/$it.jpg" }
    }

    fun startStep(chain: List<String>): Int {
        val i = chain.indexOfFirst { it !in bad }
        return if (i < 0) chain.lastIndex else i
    }

    /** What ytHd() / hd() return. Video frames stay at hq720 here (notification and widget pictures); [HdImage] walks the chain. */
    fun hd(url: String?, wide: Boolean, px: Int): String? {
        if (url.isNullOrBlank()) return url
        frames(url)?.let { return it[1] }
        if (url.contains("lh3.googleusercontent.com") || url.contains("yt3.ggpht.com"))
            return url.substringBefore("=") + if (wide) "=w1280-h720" else "=w$px-h$px-l90-rj"
        return url
    }
}

/**
 * A YouTube picture at display time: the address goes through ytHd() (so old saved small addresses become sharp), and a
 * video frame that is missing (404) falls back maxresdefault -> hq720 -> sddefault -> hqdefault. Crops the middle.
 * With the switch off it is the plain image, as before.
 */
@Composable
fun HdImage(url: String?, description: String?, modifier: Modifier = Modifier, wide: Boolean = false, px: Int = 1080) {
    val chain = remember(url) { if (Feature.musicFix1) MusicImages.frames(url) else null }
    var step by remember(url) { mutableIntStateOf(chain?.let { MusicImages.startStep(it) } ?: 0) }
    if (url.isNullOrBlank()) return
    val model = when {
        !Feature.musicFix1 -> url
        chain != null -> chain[step.coerceIn(0, chain.lastIndex)]
        else -> ytHd(url, wide, px)
    }
    AsyncImage(model, description, modifier, contentScale = ContentScale.Crop, onError = {
        if (chain != null) {
            if (MusicImages.bad.size > 500) MusicImages.bad.clear()
            MusicImages.bad.add(chain[step.coerceIn(0, chain.lastIndex)])
            if (step < chain.lastIndex) {
                MusicLog.add("cover: ${model.toString().substringAfterLast('/')} not found, trying the next size")
                step++
            } else MusicLog.add("cover: no size found for ${model.toString().substringAfter("/vi/").substringBefore('/')}")
        }
    })
}
