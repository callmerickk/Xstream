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
    val q = query.value
    var recent by remember { mutableStateOf(vm.store(RecentKey).orEmpty().split("\n").filter { it.isNotBlank() }) }
    val leftFr = remember { List(KeyRows.size + 1) { FocusRequester() } }
    val textFr = remember { FocusRequester() }
    val editRef = remember { arrayOfNulls<android.widget.EditText>(1) }
    val barFr = remember { FocusRequester() }
    var textActive by remember { mutableStateOf(false) }
    var barFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    fun setQuery(v: String) { query.value = v; vm.searchPremium(v) }
    fun type(s: String) = setQuery((query.value + s).take(60))
    fun addRecent(v: String) {
        val s = v.trim()
        if (s.length < 3) return
        recent = (listOf(s) + recent.filter { !it.equals(s, ignoreCase = true) }).take(8)
        vm.storePut(RecentKey, recent.joinToString("\n"))
    }
    val open: (Meta) -> Unit = { m -> addRecent(query.value); onOpen(m) }

    LaunchedEffect(Unit) {
        val tq = query.value.trim()
        if (tq.length >= 3 && tq != SearchState.query.value) vm.searchPremium(tq)
        else if (tq.length < 3) vm.searchPremium("")
        withFrameNanos { }
        leftFr[0].tryFocus()
    }
    // The hidden text field only becomes focusable when the search bar is pressed (system / voice keyboard).
    LaunchedEffect(textActive) {
        if (!textActive) return@LaunchedEffect
        // Open the Fire TV keyboard on the real text box (hold the Alexa button there to speak).
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

    Box(Modifier.fillMaxSize()) {
        // ── Keyboard ──
        Column(Modifier.padding(start = TvEdge, top = 35.dp).width(220.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            KeyRows.forEachIndexed { r, keys ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    keys.forEachIndexed { c, ch ->
                        val mod = if (c == 0) Modifier.focusRequester(leftFr[r]).tvLeftEdge { onLeftEdge(leftFr[r]) } else Modifier
                        TvKey(ch.toString(), 1, mod) { type(ch.toString()) }
                    }
                }
            }
            val last = KeyRows.size
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                TvKey("space", 2, Modifier.focusRequester(leftFr[last]).tvLeftEdge { onLeftEdge(leftFr[last]) }) { if (query.value.isNotEmpty()) type(" ") }
                TvKey("⌫", 2, Modifier) { if (query.value.isNotEmpty()) setQuery(query.value.dropLast(1)) }
                TvKey("clear", 2, Modifier) { setQuery("") }
            }
            Text("Press OK on the search bar to use the remote's keyboard or voice.", Modifier.padding(top = 10.dp),
                fontSize = 8.5.sp, lineHeight = 12.sp, color = TvSubText)
        }

        // ── Query, recent searches, results ──
        Column(Modifier.padding(start = 300.dp, top = 35.dp, end = 40.dp)) {
            Box {
                Row(
                    Modifier.fillMaxWidth().height(38.dp).focusRequester(barFr)
                        .onFocusChanged { barFocused = it.isFocused }
                        .tvCard(19.dp, scale = 1.02f, ring = false) { textActive = true }
                        .clip(BarShape).background(Color(0x14FFFFFF))
                        .border(if (barFocused) 2.dp else 1.dp, if (barFocused) BrandRed else Color.White, BarShape)
                        .padding(horizontal = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    if (q.isEmpty()) Text(t("searchHint"), fontSize = 14.sp, color = Color(0xFF6B7385), maxLines = 1)
                    else Text(q, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.padding(start = 2.dp).size(1.dp, 17.dp).background(BrandRed))
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.Keyboard, null, Modifier.size(15.dp), tint = TvSubText)
                }
                // A real Android text box for the Fire TV keyboard and Alexa voice typing (a Compose text box stopped
                // receiving voice text after a Fire OS update). Invisible; it takes focus only when the bar is pressed.
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { c ->
                        android.widget.EditText(c).apply {
                            setSingleLine()
                            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
                            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                            setTextColor(android.graphics.Color.TRANSPARENT); background = null; isCursorVisible = false
                            isFocusable = false; isFocusableInTouchMode = false
                            addTextChangedListener(object : android.text.TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                                override fun afterTextChanged(e: android.text.Editable?) {
                                    val t = e?.toString().orEmpty()
                                    if (t != query.value) { ActLog.add("APP", "search box got text from the keyboard (${t.length} letters)"); setQuery(t) }
                                }
                            })
                            setOnEditorActionListener { v, action, _ ->
                                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || action == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                                    (c.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                                    textActive = false; addRecent(query.value); barFr.tryFocus(); true
                                } else false
                            }
                            setOnFocusChangeListener { _, f -> if (!f && textActive) textActive = false }
                            editRef[0] = this
                        }
                    },
                    update = { et ->
                        et.isFocusable = textActive; et.isFocusableInTouchMode = textActive
                        val cur = query.value; if (et.text.toString() != cur) { et.setText(cur); et.setSelection(cur.length) }
                    },
                    modifier = Modifier.size(1.dp).graphicsLayer { alpha = 0f },
                )
            }
            if (recent.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(top = 6.dp).offset(x = (-6).dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(recent, key = { it }) { s -> TvRecentChip(s) { setQuery(s) } }
                }
            }
            Text("Results", Modifier.padding(top = 10.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            val tq = q.trim()
            when {
                tq.length < 3 -> TvSearchNote("Type at least 3 letters to search movies and series.")
                hits.isEmpty() && (loading || shownQuery != tq) -> TvSearchNote("Searching…")
                hits.isEmpty() -> TvSearchNote("No results for “$tq”.")
                else -> LazyVerticalGrid(
                    GridCells.Fixed(4),
                    Modifier.width(613.dp).offset(x = (-10).dp),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    items(hits, key = { it.meta.type + it.meta.id }) { h -> TvSearchCard(h) { open(h.meta) } }
                }
            }
        }
    }
}

@Composable
private fun RowScope.TvKey(label: String, span: Int, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.weight(span.toFloat()).height(31.dp).then(modifier)
            .tvCard(6.dp, scale = 1.08f, ring = false, onFocus = { focused = it }, onClick = onClick)
            .clip(KeyShape).background(if (focused) Color.White else Color(0x12FFFFFF)),
        Alignment.Center,
    ) {
        Text(label, fontSize = if (label.length > 1) 10.sp else 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (focused) Color.Black else Color(0xFFD6D9E1), maxLines = 1)
    }
}

@Composable
private fun TvRecentChip(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.tvCard(12.dp, scale = 1.05f, ring = false, onFocus = { focused = it }, onClick = onClick)
            .clip(RecentShape).background(if (focused) Color.White else Color(0x14FFFFFF))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = if (focused) Color.Black else Color(0xFFC9CED9)
        Icon(Icons.Default.History, null, Modifier.size(11.dp), tint = c)
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 9.5.sp, fontWeight = FontWeight.Medium, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
