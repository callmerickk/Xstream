package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

private val KeyRows = listOf("abcdef", "ghijkl", "mnopqr", "stuvwx", "yz1234", "567890")
private val KeyShape = RoundedCornerShape(6.dp)
private val BarShape = RoundedCornerShape(19.dp)
private val RecentShape = RoundedCornerShape(12.dp)
private const val RecentKey = "tvRecent"

/**
 * TV search: on-screen keyboard on the left (OK types), the query bar (OK on it opens the system / voice keyboard),
 * recent searches and a 4-column results grid. Uses the phone's premium search (TMDB + addon catalogs): starts at
 * 3 characters, debounced 350 ms. LEFT on the keyboard's left column opens the menu ([onLeftEdge]).
 * [query] is owned by TvApp so it survives opening a title page.
 */
@Composable
fun TvSearchScreen(vm: AppViewModel, query: MutableState<String>, onOpen: (Meta) -> Unit, onLeftEdge: (FocusRequester) -> Unit) {
    val hits by SearchState.hits.collectAsStateWithLifecycle()
    val loading by SearchState.loading.collectAsStateWithLifecycle()
    val shownQuery by SearchState.query.collectAsStateWithLifecycle()
    val groups by SearchState.groups.collectAsStateWithLifecycle()
    val failed by SearchState.failed.collectAsStateWithLifecycle()
    val q = query.value
    var recent by remember { mutableStateOf(vm.store(RecentKey).orEmpty().split("\n").filter { it.isNotBlank() }) }
    val leftFr = remember { List(KeyRows.size + 1) { FocusRequester() } }
    val textFr = remember { FocusRequester() }
    val editRef = remember { arrayOfNulls<android.widget.EditText>(1) }
    val barFr = remember { FocusRequester() }
    var textActive by remember { mutableStateOf(false) }
    // TV 0.9.3: OK on the bar first opens the search page (recent searches); OK again opens the keyboard (Alexa voice).
    var searchPage by remember { mutableStateOf(false) }
    var barFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    // TV 0.7.4: typing only fills the box; the search runs when you press Search (or finish in the keyboard / voice).
    // Searching on every letter sent dozens of addon requests at once and the Fire TV Stick ran out of memory.
    fun setQuery(v: String) { query.value = v; if (v.trim().length < 3) vm.searchPremium("") }
    fun type(s: String) = setQuery((query.value + s).take(60))
    fun addRecent(v: String) {
        val s = v.trim()
        if (s.length < 3) return
        recent = (listOf(s) + recent.filter { !it.equals(s, ignoreCase = true) }).take(8)
        vm.storePut(RecentKey, recent.joinToString("\n"))
    }
    fun runSearch() { val tq = query.value.trim(); if (tq.length >= 2) { vm.searchPremium(tq); addRecent(tq) } }
    val open: (Meta) -> Unit = { m -> addRecent(query.value); onOpen(m) }

    // TV 0.8.5: opening Search searches nothing (it used to re-run the last search at once); the bar takes the focus.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        barFr.tryFocus()
    }
    // The hidden text field only becomes focusable when the search bar is pressed (system / voice keyboard).
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(textActive) {
        if (!textActive) return@LaunchedEffect
        // The Fire TV keyboard (with its mic: hold the remote's voice button to speak) in its own small window, so it
        // always gets the focus. What you type or say shows in the results straight away.
        if (BuildConfig.IS_TV) {
            // The TV's own keyboard is a separate app: free the pictures kept in memory first (on a 1 GB stick Android
            // closed StreamX to make room for it).
            runCatching { coil.Coil.imageLoader(ctx).memoryCache?.clear() }
            tvTextPrompt(ctx, query.value, onText = { query.value = it.take(60) }) { runSearch(); textActive = false; barFr.tryFocus() }
            ActLog.add("APP", "search: keyboard window opened")
            return@LaunchedEffect
        }
        editRef[0]?.let { et ->
            et.isFocusable = true; et.isFocusableInTouchMode = true
            et.requestFocus()
            val imm = et.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            ActLog.add("APP", "search: keyboard opened")
            return@LaunchedEffect
        }
        withFrameNanos { }
        textFr.tryFocus()
        keyboard?.show()
    }

    // Google TV / Android TV voice search (the 🎤 button), when the TV has voice input; Fire TV: Alexa through the keyboard.
    val voiceIntent = remember { android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a movie or show") }
    val hasVoice = remember { runCatching { ctx.packageManager.queryIntentActivities(voiceIntent, 0).isNotEmpty() }.getOrDefault(false) }
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (said.isNotEmpty()) { query.value = said.take(60); runSearch(); ActLog.add("APP", "search: voice (${said.length} letters)") }
    }
    val micFr = remember { FocusRequester() }
    // Back: from results or the search page straight to Discover (it used to leave Search for Home).
    androidx.activity.compose.BackHandler(enabled = q.isNotEmpty() || searchPage) {
        query.value = ""; vm.searchPremium(""); searchPage = false; barFr.tryFocus()
        ActLog.add("KEY", "BACK · TV Search › Discover")
    }
    var micFocused by remember { mutableStateOf(false) }

    // TV 0.8.5 — like the phone: the search bar on top (OK opens the TV's own keyboard: Fire TV with Alexa voice,
    // Google TV with its mic), a 🎤 button where the TV has voice input, recent searches, and the results as posters.
    // It searches only when you finish typing or speaking.
    Column(Modifier.fillMaxSize().padding(start = TvEdge, top = 30.dp, end = 30.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth().height(44.dp).focusRequester(barFr)
                        .tvLeftEdge { onLeftEdge(barFr) }
                        .onFocusChanged { barFocused = it.isFocused }
                        .tvCard(22.dp, scale = 1.02f, ring = false) { if (!searchPage && q.isEmpty() && recent.isNotEmpty()) searchPage = true else textActive = true }
                        .clip(BarShape).background(Color(0x14FFFFFF))
                        .border(if (barFocused) 2.dp else 1.dp, if (barFocused) Color.White else Color(0x40FFFFFF), BarShape)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(10.dp))
                    if (q.isEmpty()) Text(t("searchHint"), fontSize = 15.sp, color = Color(0xFF8A92A3), maxLines = 1)
                    else Text(q, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.Keyboard, null, Modifier.size(16.dp), tint = TvSubText)
                }
            }
            if (hasVoice) {
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.size(44.dp).focusRequester(micFr).onFocusChanged { micFocused = it.isFocused }
                        .tvCard(22.dp, scale = 1.06f, ring = false) { runCatching { voice.launch(voiceIntent) } }
                        .clip(androidx.compose.foundation.shape.CircleShape).background(if (micFocused) Color.White else Color(0x14FFFFFF)),
                    Alignment.Center,
                ) { Text("🎤", fontSize = 18.sp) }
            }
        }
        val tq = q.trim()
        when {
            tq.length < 2 && searchPage -> Column(Modifier.padding(top = 18.dp)) {
                Text("RECENT SEARCHES", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = Color(0xFF7C8394))
                Text("Press OK on the bar again to type or speak.", Modifier.padding(top = 4.dp, bottom = 8.dp), fontSize = 12.sp, color = TvSubText)
                androidx.compose.foundation.lazy.LazyColumn(
                    Modifier.fillMaxWidth().offset(x = (-6).dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(recent, key = { it }) { s -> TvRecentChip(s, big = true) { query.value = s; runSearch() } }
                }
            }
            tq.length < 2 -> Column {
                TvSearchNote(if (hasVoice) "Press OK on the bar to type, or 🎤 to speak." else "Press OK on the bar to type — on Fire TV, hold the remote's voice button to speak.")
                TvDiscover(vm, open)
            }
            shownQuery != tq && !loading -> TvSearchNote("Finish typing to search for “$tq”.")
            hits.isEmpty() && (loading || shownQuery != tq) -> TvSearchNote("Searching…")
            hits.isEmpty() && failed -> TvSearchNote("Couldn't connect for “$tq” — press OK on the bar, then Search, to try again.")
            hits.isEmpty() -> TvSearchNote("No results for “$tq”.")
            else -> LazyVerticalGrid(
                GridCells.Adaptive(118.dp),
                Modifier.fillMaxWidth().offset(x = (-10).dp),
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                groups.firstOrNull()?.let { g ->
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "group") {
                        Text("${g.name} · ${g.sub}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                items(hits, key = { it.meta.type + it.meta.id }) { h -> TvSearchPoster(h) { open(h.meta) } }
            }
        }
    }
}

/** TV 0.8.5: a result as a portrait poster (like the phone), its name and year under it. */
@Composable
private fun TvSearchPoster(h: SearchHit, onClick: () -> Unit) {
    val shape = remember { RoundedCornerShape(10.dp) }
    val req = tvImage(h.meta.poster ?: h.backdrop, 118.dp, 177.dp)
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).tvCard(10.dp, onClick = onClick).clip(shape).background(Surface1)) {
            if (req != null) AsyncImage(req, h.meta.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(h.meta.name, Modifier.align(Alignment.Center).padding(8.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 3)
            if (h.isNew) Text(
                "NEW", Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(3.dp)).background(BrandRed)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                fontSize = 8.sp, fontWeight = FontWeight.Bold,
            )
        }
        Text(h.meta.name, Modifier.padding(top = 6.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        h.year?.let { Text(it, fontSize = 9.5.sp, color = TvSubText, maxLines = 1) }
    }
}

@Composable
private fun RowScope.TvKey(label: String, span: Int, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.weight(span.toFloat()).height(42.dp).then(modifier)
            .tvCard(6.dp, scale = 1.08f, ring = false, onFocus = { focused = it }, onClick = onClick)
            .clip(KeyShape).background(if (focused) Color.White else Color(0x12FFFFFF)),
        Alignment.Center,
    ) {
        Text(label, fontSize = if (label.length > 1) 13.sp else 19.sp, fontWeight = FontWeight.Bold,
            color = if (focused) Color.Black else Color(0xFFD6D9E1), maxLines = 1)
    }
}

@Composable
private fun TvRecentChip(text: String, big: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.tvCard(12.dp, scale = if (big) 1.03f else 1.05f, ring = false, onFocus = { focused = it }, onClick = onClick)
            .then(if (big) Modifier.width(420.dp) else Modifier)
            .clip(RecentShape).background(if (focused) Color.White else Color(0x14FFFFFF))
            .padding(horizontal = if (big) 16.dp else 10.dp, vertical = if (big) 11.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = if (focused) Color.Black else Color(0xFFC9CED9)
        Icon(Icons.Default.History, null, Modifier.size(if (big) 16.dp else 11.dp), tint = c)
        Spacer(Modifier.width(if (big) 10.dp else 4.dp))
        Text(text, fontSize = if (big) 15.sp else 9.5.sp, fontWeight = FontWeight.Medium, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TvSearchNote(text: String) {
    Text(text, Modifier.padding(top = 14.dp), fontSize = 11.sp, color = TvSubText)
}

private val SearchShade = Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xD9000000))

@Composable
private fun TvSearchCard(h: SearchHit, onClick: () -> Unit) {
    val shape = remember { RoundedCornerShape(9.dp) }
    val req = tvImage(h.backdrop ?: h.meta.poster, 140.dp, 79.dp)
    val logoReq = tvImage(h.logo, 80.dp, 22.dp)
    var logoFailed by remember(h.meta.id) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(79.dp).tvCard(9.dp, onClick = onClick).clip(shape).background(Surface1)) {
        if (req != null) AsyncImage(req, h.meta.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(SearchShade))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 7.dp, end = 7.dp, bottom = 6.dp)) {
            if (logoReq != null && !logoFailed) {
                AsyncImage(logoReq, h.meta.name, Modifier.size(80.dp, 22.dp), contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart, onError = { logoFailed = true })
            } else {
                Text(h.meta.name, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val info = listOfNotNull(h.year, h.info).joinToString(" · ")
            if (info.isNotEmpty()) Text(info, fontSize = 7.5.sp, color = Color(0xFFC9CED9), maxLines = 1)
        }
        if (h.isNew) Text(
            "NEW", Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(3.dp)).background(BrandRed)
                .padding(horizontal = 4.dp, vertical = 1.dp),
            fontSize = 7.sp, fontWeight = FontWeight.Bold,
        )
    }
}


/**
 * Fire TV keyboard in a small window of its own (a separate window always gets the focus, so the keyboard and its voice
 * typing really open). [onText] follows every change; [onDone] when it closes.
 */
fun tvTextPrompt(ctx: android.content.Context, initial: String, onText: (String) -> Unit, onDone: () -> Unit) {
    val act = ctx.tvActivity() ?: return onDone()
    val et = android.widget.EditText(act).apply {
        setSingleLine(); setText(initial); setSelection(initial.length)
        hint = "Type, or hold the voice button to speak"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        textSize = 22f
        setTextColor(android.graphics.Color.WHITE); setHintTextColor(0x99FFFFFF.toInt())
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            var last = initial.length
            override fun afterTextChanged(e: android.text.Editable?) {
                val t = e?.toString().orEmpty()
                // TV 0.8.8: several letters arriving at once is voice (Alexa / Google mic) — logged, so a voice problem shows.
                if (t.length - last >= 3) ActLog.add("APP", "search: ${t.length - last} letters at once (voice?) → \"${t.take(40)}\"")
                last = t.length
                onText(t)
            }
        })
    }
    val pad = (24 * act.resources.displayMetrics.density).toInt()
    val box = android.widget.FrameLayout(act).apply { setPadding(pad, pad / 2, pad, pad / 2); addView(et) }
    val d = android.app.AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle("Search").setView(box)
        .setPositiveButton("Done") { di, _ -> di.dismiss() }
        .create()
    et.setOnEditorActionListener { _, _, _ -> d.dismiss(); true }
    d.setOnDismissListener { onDone() }
    d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    d.setOnShowListener {
        et.requestFocus()
        et.postDelayed({
            (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(et, 0)
        }, 120)
    }
    d.show()
    runCatching {
        val ime = android.provider.Settings.Secure.getString(act.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        ActLog.add("APP", "search: keyboard = ${ime.substringBefore('/').ifEmpty { "unknown" }}")
    }
}


/**
 * TV 0.8.7: Discover under the empty search bar — this week's popular films and series from TMDB (one call), as posters
 * of different sizes (a staggered wall). Pictures load only as they come on screen.
 */
@Composable
private fun TvDiscover(vm: AppViewModel, onOpen: (Meta) -> Unit) {
    val items by produceState<List<Meta>>(DiscoverCache.items, Unit) {
        if (value.isNotEmpty()) return@produceState
        val key = vm.tmdbKey()
        if (key.isEmpty()) return@produceState
        value = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val o = Api.json.parseToJsonElement(Api.get("https://api.themoviedb.org/3/trending/all/week?api_key=$key")) as kotlinx.serialization.json.JsonObject
                (o["results"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
                    val j = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    fun str(k: String) = (j[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it != "null" && it.isNotBlank() }
                    val kind = str("media_type") ?: return@mapNotNull null
                    if (kind != "movie" && kind != "tv") return@mapNotNull null
                    Meta(id = "tmdb:$kind:${str("id") ?: return@mapNotNull null}", type = if (kind == "tv") "series" else "movie",
                        name = str("title") ?: str("name") ?: return@mapNotNull null,
                        poster = str("poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" } ?: return@mapNotNull null,
                        releaseInfo = (str("release_date") ?: str("first_air_date"))?.take(4))
                }
            }
        }.getOrDefault(emptyList()).also { DiscoverCache.items = it }
    }
    if (items.isEmpty()) return
    Text("Discover", Modifier.padding(top = 16.dp, bottom = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
    androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid(
        androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells.Adaptive(132.dp),
        Modifier.fillMaxWidth().offset(x = (-10).dp),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
    ) {
        items(items.size, key = { items[it].id }) { i ->
            val m = items[i]
            // Three heights in turn, so the wall looks hand-made rather than a grid.
            val ratio = when (i % 3) { 0 -> 0.62f; 1 -> 0.72f; else -> 0.54f }
            val req = tvImage(m.poster, 132.dp, (132f / ratio).dp)
            Box(Modifier.fillMaxWidth().aspectRatio(ratio).tvCard(10.dp) { onOpen(m) }.clip(RoundedCornerShape(10.dp)).background(Surface1)) {
                if (req != null) AsyncImage(req, m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
    }
}

private object DiscoverCache { @Volatile var items: List<Meta> = emptyList() }
