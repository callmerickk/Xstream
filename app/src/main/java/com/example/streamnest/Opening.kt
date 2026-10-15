package com.example.streamnest

import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** IMDb parents guide (as Nuvio shows it when a video starts): category → mild / moderate / severe. */
object ParentalGuide {
    private val cache = ConcurrentHashMap<String, List<Pair<String, String>>>()
    private val names = linkedMapOf(
        "SEXUAL_CONTENT" to "Sex & nudity", "VIOLENCE" to "Violence", "PROFANITY" to "Language",
        "ALCOHOL_DRUGS" to "Alcohol & drugs", "FRIGHTENING_INTENSE_SCENES" to "Frightening scenes",
    )

    /** Up to 5 lines like ("Violence", "Moderate"), most severe first. Empty when unknown. */
    suspend fun get(id: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val imdb = Regex("tt\\d+").find(id)?.value ?: return@withContext emptyList()
        cache[imdb]?.let { return@withContext it }
        val out = runCatching {
            val root = Api.json.parseToJsonElement(Api.get("https://api.tiffara.com/titles/$imdb/parentsGuide")).jsonObject
            root["parentsGuide"]!!.jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val cat = o["category"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: return@mapNotNull null
                val label = names[cat] ?: return@mapNotNull null
                val votes = o["severityBreakdowns"]?.jsonArray?.mapNotNull { b ->
                    val bo = b.jsonObject
                    val lvl = bo["severityLevel"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return@mapNotNull null
                    lvl to (bo["voteCount"]?.jsonPrimitive?.intOrNull ?: 0)
                }.orEmpty()
                val none = votes.firstOrNull { it.first == "none" }?.second ?: 0
                val top = votes.filter { it.first != "none" }.maxByOrNull { it.second } ?: return@mapNotNull null
                if (top.second <= none) null else label to top.first.replaceFirstChar { it.uppercase() }
            }.sortedBy { when (it.second.lowercase()) { "severe" -> 0; "moderate" -> 1; else -> 2 } }.take(5)
        }.getOrDefault(emptyList())
        cache[imdb] = out
        out
    }
}

/** What the playing file really is, read from the video track: "3840 × 1608 · HEVC · HDR10 · 23.976 fps". */
@OptIn(UnstableApi::class)
fun realVideoLine(tracks: Tracks): String? {
    val g = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } ?: tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return null
    val f: Format = (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } ?: g.getTrackFormat(0)
    if (f.width <= 0 || f.height <= 0) return null
    val codec = when {
        f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        f.sampleMimeType == MimeTypes.VIDEO_H265 -> "HEVC"
        f.sampleMimeType == MimeTypes.VIDEO_H264 -> "AVC"
        f.sampleMimeType == MimeTypes.VIDEO_AV1 -> "AV1"
        f.sampleMimeType == MimeTypes.VIDEO_VP9 -> "VP9"
        else -> f.sampleMimeType?.substringAfter('/')?.uppercase()
    }
    val hdr = f.colorInfo?.let { ci ->
        when (ci.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> "HDR10"
            C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
    }
    val fps = f.frameRate.takeIf { it > 0 }?.let { r -> if (kotlin.math.abs(r - Math.round(r)) < 0.01f) "${Math.round(r)} fps" else "${"%.3f".format(r)} fps" }
    return listOfNotNull("${f.width} × ${f.height}", codec, hdr, fps).joinToString(" · ")
}

/**
 * The start of a video: a red line draws down at the top left and the age-guide lines appear one by one, then the
 * real picture size; after 5 s they leave in reverse order.
 */
@Composable
fun OpeningOverlay(warnings: List<Pair<String, String>>, videoLine: String?, modifier: Modifier = Modifier) {
    val items = warnings.map { "${it.first} · ${it.second}" } + listOfNotNull(videoLine)
    if (items.isEmpty()) return
    val line = remember { Animatable(0f) }
    val alphas = remember(items.size) { List(items.size) { Animatable(0f) } }
    LaunchedEffect(items.size) {
        line.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
        alphas.forEach { a -> launch { a.animateTo(1f, tween(220)) }; delay(90) }
        delay(5000)
        alphas.asReversed().forEach { a -> launch { a.animateTo(0f, tween(150)) }; delay(60) }
        delay(150)
        line.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
    }
    val shadow = TextStyle(shadow = Shadow(Color.Black, blurRadius = 10f))
    Row(modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().graphicsLayer { scaleY = line.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f) }
            .clip(RoundedCornerShape(2.dp)).background(Color(0xFFE50914)))
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEachIndexed { i, t ->
                val isVideo = videoLine != null && i == items.lastIndex
                Text(t, Modifier.graphicsLayer { alpha = alphas[i].value; translationX = (1f - alphas[i].value) * -12f }.then(if (isVideo) Modifier.padding(top = 4.dp) else Modifier),
                    fontSize = if (isVideo) 11.sp else 12.sp, fontWeight = if (isVideo) FontWeight.Medium else FontWeight.SemiBold,
                    fontFamily = if (isVideo) FontFamily.Monospace else null, color = if (isVideo) Color(0xFFE9E9EF) else Color.White, style = shadow)
            }
        }
    }
}
