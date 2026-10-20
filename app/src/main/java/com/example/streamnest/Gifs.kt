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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
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
 * GIF search without any key: a web image search limited to animated GIFs (safe search on), like Nuvio's picker.
 * Unofficial — if it ever stops answering, pasting a link and GIPHY (with a key) still work.
 */
object WebGifs {
    private val mRe = Regex("""\sm="(\{[^"]*\})"""")
    suspend fun search(q: String): List<GifHit>? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val qq = java.net.URLEncoder.encode((q.ifBlank { "movie reaction" }) + " gif", "UTF-8")
            val req = okhttp3.Request.Builder()
                .url("https://www.bing.com/images/async?q=$qq&first=0&count=40&qft=+filterui:photo-animatedgif&adlt=strict&mmasync=1")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9").build()
            val html = Api.http.newCall(req).execute().use { r -> if (!r.isSuccessful) return@runCatching null; r.body?.string().orEmpty() }
            mRe.findAll(html).mapNotNull { mr ->
                val js = mr.groupValues[1].replace("&quot;", "\"").replace("&amp;", "&")
                val o = runCatching { Api.json.parseToJsonElement(js).jsonObject }.getOrNull() ?: return@mapNotNull null
                val full = o["murl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (!full.startsWith("https://") || !full.substringBefore('?').lowercase().endsWith(".gif")) return@mapNotNull null
                // The grid plays the real GIF (the search's own thumbnail is a still picture).
                GifHit(full, full, full)
            }.distinctBy { it.full }.take(24).toList()
        }.getOrNull()
    }

    /** A pasted link as a picture link: Tenor / GIPHY pages become their GIF file; direct links stay as they are. */
    suspend fun resolve(link: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val u = link.trim()
        if (!u.startsWith("http")) return@withContext null
        val path = u.substringBefore('?').lowercase()
        if (path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".mp4")) return@withContext u
        runCatching {
            val html = Api.http.newCall(okhttp3.Request.Builder().url(u).header("User-Agent", "Mozilla/5.0").build()).execute().use { it.body?.string().orEmpty() }
            Regex("""https://media[0-9]*\.(?:tenor|giphy)\.com/[^"'\s<>]+?\.gif""").find(html)?.value
                ?: Regex("""<meta[^>]+property="og:image"[^>]+content="([^"]+)"""").find(html)?.groupValues?.get(1)
        }.getOrNull()
    }
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
    var link by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(q, key) {
        delay(if (q.isBlank()) 0 else 450) // wait for typing to pause
        loading = true
        // No key needed: the built-in GIF search; GIPHY results are added when you have a key.
        val web = WebGifs.search(q)
        val gi = if (key.isNotEmpty()) Giphy.search(key, q) else null
        failed = web == null && gi == null
        hits = (gi.orEmpty() + web.orEmpty()).distinctBy { it.full }
        loading = false
    }
    PremiumAlert(
        onDismissRequest = onDismiss,
        title = { Text("Choose a GIF") },
        text = {
            Column(Modifier.fillMaxWidth()) {

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
                // Or paste any GIF link (Tenor, GIPHY or a direct .gif link).
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(link, { link = it.trim() }, Modifier.weight(1f).tvOkKeyboard(), singleLine = true, placeholder = { Text("…or paste a GIF link", fontSize = 13.sp) },
                        shape = RoundedCornerShape(12.dp), keyboardOptions = tvKeyboard())
                    TextButton({ scope.launch { val r = WebGifs.resolve(link); if (r != null) sel = GifHit(r, r, r) else Snack.show("That link isn't a picture") } }, enabled = link.startsWith("http")) { Text("Preview") }
                }
                Row(Modifier.padding(top = 8.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("avengers", "anime", "cats", "reaction", "cars", "dance").forEach { c ->
                        Text(c, Modifier.clip(RoundedCornerShape(14.dp)).background(if (q == c) Color.White else Color(0x1FFFFFFF)).clickable { q = c }
                            .padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 12.sp, color = if (q == c) Color.Black else Color.White)
                    }
                }
                Box(Modifier.fillMaxWidth().height(300.dp).padding(top = 10.dp)) {
                    when {
                        loading && hits.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        failed -> Text("Couldn't search right now. Paste a GIF link instead.", Modifier.align(Alignment.Center), fontSize = 13.sp, color = Color(0xFFFF8A80))
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
                Text(if (key.isNotEmpty()) "GIFs from the web and GIPHY" else "GIFs from the web · add a GIPHY key in Integrations for more", Modifier.fillMaxWidth().padding(top = 6.dp), fontSize = 11.sp, color = Color(0xFF9AA3B5))
            }
        },
        confirmButton = { TextButton({ sel?.let { onPick(it.full) } }, enabled = sel != null) { Text("Use this GIF") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
