package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A GIF from GIPHY: a small preview for the grid and a bigger one for the profile picture. */
data class GifHit(val id: String, val preview: String, val full: String)

object Giphy {
    /** Trending when [q] is blank. Null = the request failed. */
    suspend fun search(key: String, q: String): List<GifHit>? = runCatching {
        val base = if (q.isBlank()) "https://api.giphy.com/v1/gifs/trending?api_key=${enc(key)}&limit=30&rating=pg-13"
        else "https://api.giphy.com/v1/gifs/search?api_key=${enc(key)}&q=${enc(q)}&limit=30&rating=pg-13"
        val root = Api.json.parseToJsonElement(Api.get(base)).jsonObject
        root["data"]!!.jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val img = o["images"]?.jsonObject ?: return@mapNotNull null
            fun url(k: String) = (img[k] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
            val preview = url("fixed_width_small") ?: url("fixed_width") ?: return@mapNotNull null
            val full = url("fixed_height") ?: url("downsized") ?: preview
            GifHit(o["id"]?.jsonPrimitive?.contentOrNull ?: preview, preview, full)
        }
    }.getOrNull()

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}

/**
 * Profile picture from a GIF search: type or tap a suggestion, tap a GIF to see it inside the round frame, then
 * "Use this GIF". Needs a free GIPHY key (Settings › Integrations), shared with your settings.
 */
@Composable
fun GifPicker(vm: AppViewModel, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    val key = texts["giphyKey"].orEmpty().trim()
    val uri = LocalUriHandler.current
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<GifHit>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf<GifHit?>(null) }
    LaunchedEffect(q, key) {
        if (key.isEmpty()) return@LaunchedEffect
        delay(if (q.isBlank()) 0 else 450) // wait for typing to pause
        loading = true
        val r = Giphy.search(key, q)
        failed = r == null
        hits = r.orEmpty()
        loading = false
    }
    PremiumAlert(
        onDismissRequest = onDismiss,
        title = { Text("Choose a GIF") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (key.isEmpty()) {
                    Text("GIF search needs a free GIPHY key (one minute to get). Add it in Settings › Integrations › GIPHY — it is saved with your settings for all your devices.",
                        fontSize = 14.sp, color = Color(0xFFC9CDD6))
                    TextButton({ uri.openUri("https://developers.giphy.com/dashboard/") }) { Text("Get a free key") }
                    return@Column
                }
                // Preview in the round profile frame.
                Box(Modifier.fillMaxWidth().padding(bottom = 10.dp), Alignment.Center) {
                    Box(Modifier.size(104.dp).clip(CircleShape).background(Color(0xFF22252E)).border(2.dp, Color.White, CircleShape), Alignment.Center) {
                        val s = sel
                        if (s != null) AsyncImage(s.full, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text("Tap a GIF", fontSize = 12.sp, color = Color(0xFF9AA3B5))
                    }
                }
                OutlinedTextField(q, { q = it.take(40) }, Modifier.fillMaxWidth().tvOkKeyboard(), singleLine = true, placeholder = { Text("Search GIFs") },
                    shape = RoundedCornerShape(12.dp), keyboardOptions = tvKeyboard())
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("anime", "cats", "reaction", "cars", "dance").forEach { c ->
                        Text(c, Modifier.clip(RoundedCornerShape(14.dp)).background(if (q == c) Color.White else Color(0x1FFFFFFF)).clickable { q = c }
                            .padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 12.sp, color = if (q == c) Color.Black else Color.White)
                    }
                }
                Box(Modifier.fillMaxWidth().height(300.dp).padding(top = 10.dp)) {
                    when {
                        loading && hits.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        failed -> Text("Couldn't reach GIPHY (check the key in Integrations).", Modifier.align(Alignment.Center), fontSize = 13.sp, color = Color(0xFFFF8A80))
                        else -> LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(hits, key = { it.id }) { h ->
                                AsyncImage(h.preview, null,
                                    Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF22252E))
                                        .then(if (sel?.id == h.id) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
                                        .clickable { sel = h },
                                    contentScale = ContentScale.Crop)
                            }
                        }
                    }
                }
                Text("Powered by GIPHY", Modifier.fillMaxWidth().padding(top = 6.dp), fontSize = 11.sp, color = Color(0xFF9AA3B5))
            }
        },
        confirmButton = { TextButton({ sel?.let { onPick(it.full) } }, enabled = sel != null) { Text("Use this GIF") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
