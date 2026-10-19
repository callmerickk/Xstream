package com.example.streamnest

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest

/* ───────────── Xstream TV: shared focus helpers, rows and cards ───────────── */

/** Left padding of TV rows / screens: 48dp on full-screen pages, smaller next to the left rail (see TvApp). */
val LocalTvEdge = staticCompositionLocalOf { 48.dp }
val TvEdge: Dp
    @Composable @androidx.compose.runtime.ReadOnlyComposable get() = LocalTvEdge.current
private val TvGlowRed = Color(0xFFE50914)
val TvSubText = Color(0xFF9AA3B5)
val TvTitleText = Color(0xFFE6E9F2)

/** Requests focus without crashing when the requester is not attached (yet / any more). */
fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus() }.isSuccess

/**
 * A focusable TV card: scales 1.0 → [scale] when focused (draw phase only), draws a 2dp white rounded ring a little
 * outside the card plus a soft red glow (stacked translucent rects, no Modifier.shadow), and reacts to
 * DPAD_CENTER / ENTER through clickable (no ripple).
 *
 * Put it BEFORE the card's own clip/background in the chain (so the ring and glow can draw outside the card), and put
 * focusRequester / onPreviewKeyEvent modifiers before it.
 */
fun Modifier.tvCard(
    corner: Dp = 10.dp,
    scale: Float = 1.06f,
    ring: Boolean = true,
    onFocus: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val anim = animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "tvFocus")
    val click = rememberUpdatedState(onClick)
    val focusCb = rememberUpdatedState(onFocus)
    val interaction = remember { MutableInteractionSource() }
    this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            val s = 1f + (scale - 1f) * anim.value
            scaleX = s
            scaleY = s
        }
        .drawWithCache {
            val r = corner.toPx()
            val stroke = 2.dp.toPx()
            val o = 2.5.dp.toPx() + stroke / 2f
            val spread = 9.dp.toPx()
            val ringStroke = Stroke(stroke)
            onDrawWithContent {
                val v = anim.value
                if (false && ring && v > 0.01f) {
                    for (i in 1..4) {
                        val g = spread * i / 4f
                        drawRoundRect(
                            color = TvGlowRed,
                            topLeft = Offset(-g, -g),
                            size = Size(size.width + 2 * g, size.height + 2 * g),
                            cornerRadius = CornerRadius(r + g, r + g),
                            alpha = 0.11f * v,
                        )
                    }
                }
                drawContent()
                if (ring && v > 0.01f) {
                    drawRoundRect(
                        color = TvFocusEdge,
                        topLeft = Offset(-o, -o),
                        size = Size(size.width + 2 * o, size.height + 2 * o),
                        cornerRadius = CornerRadius(r + o, r + o),
                        style = ringStroke,
                        alpha = v,
                    )
                }
            }
        }
        .onFocusChanged { st ->
            if (st.isFocused != focused) {
                focused = st.isFocused
                focusCb.value?.invoke(st.isFocused)
            }
        }
        .clickable(interactionSource = interaction, indication = null) { click.value() }
}

/** Calls [onLeftEdge] (and consumes the key) when LEFT is pressed while focus is inside this element. */
fun Modifier.tvLeftEdge(onLeftEdge: (() -> Unit)?): Modifier =
    if (onLeftEdge == null) this else onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onLeftEdge(); true } else false
    }

/** A Coil request decoded at the card's size (not the image's full size) — keeps memory low on 1 GB devices. */
@Composable
fun tvImage(url: String?, w: Dp, h: Dp): ImageRequest? {
    val ctx = LocalContext.current
    val d = LocalDensity.current
    return remember(url, w, h) {
        if (url.isNullOrBlank()) null else tvRequest(ctx, url, with(d) { w.roundToPx() }, with(d) { h.roundToPx() })
    }
}

/** The same request outside composition (used to pre-load the next hero card into the memory cache). */
fun tvRequest(ctx: android.content.Context, url: String, wPx: Int, hPx: Int): ImageRequest =
    ImageRequest.Builder(ctx).data(url).size(wPx, hPx).allowRgb565(true).crossfade(false).build()

// ───────────── Scrolling ─────────────

/** Never scrolls on its own when something inside gets focus (the screen scrolls itself, see TvManualScroll). */
@OptIn(ExperimentalFoundationApi::class)
private val TvNoAutoScroll = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = spring()
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

@OptIn(ExperimentalFoundationApi::class)
private val LocalTvRowSpec = staticCompositionLocalOf<BringIntoViewSpec?> { null }

/**
 * Turns off focus-driven scrolling for the vertical list inside [content] (the screen scrolls it itself with
 * animateScrollToItem when a row gets focus). Rows made with [TvRow] get the platform's normal behaviour back.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvManualScroll(content: @Composable () -> Unit) {
    val platform = LocalBringIntoViewSpec.current
    CompositionLocalProvider(LocalTvRowSpec provides platform, LocalBringIntoViewSpec provides TvNoAutoScroll, content = content)
}

// ───────────── Rows ─────────────

@Composable
fun TvRowTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.padding(start = TvEdge, end = TvEdge, top = 14.dp),
        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TvTitleText, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A horizontal TV row (LazyRow) with stable keys, start padding [TvEdge] and focus restoration (coming back into the
 * row focuses the item that was focused last). LEFT on the first item calls [onLeftEdge] with that item's
 * FocusRequester (to give focus back later). [itemContent] must apply the given modifier first on its focusable card.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun <T> TvRow(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    title: String? = null,
    state: LazyListState = rememberLazyListState(),
    spacing: Dp = 12.dp,
    restoreIndex: Int = -1,
    restoreRequester: FocusRequester? = null,
    onLeftEdge: ((FocusRequester) -> Unit)? = null,
    onRowFocus: (() -> Unit)? = null,
    onItemFocus: ((Int) -> Unit)? = null,
    itemContent: @Composable (index: Int, item: T, itemModifier: Modifier) -> Unit,
) {
    val firstFr = remember { FocusRequester() }
    val leftEdge = rememberUpdatedState(onLeftEdge)
    val rowFocus = rememberUpdatedState(onRowFocus)
    val itemFocus = rememberUpdatedState(onItemFocus)
    val edgeMod = remember(firstFr) {
        Modifier.focusRequester(firstFr).onPreviewKeyEvent { e ->
            val cb = leftEdge.value
            if (cb != null && e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { cb(firstFr); true } else false
        }
    }
    // Coming back into the row focuses the card that was focused last. (Done by hand: Modifier.focusRestorer pins
    // lazy items and crashed with "Release should only be called once" when a screen left while holding a pin.)
    var lastIdx by remember { mutableIntStateOf(-1) }
    val lastFr = remember { FocusRequester() }
    val attachedIdx = remember { intArrayOf(-1) }
    val rowSpec = LocalTvRowSpec.current ?: LocalBringIntoViewSpec.current
    Column(modifier.fillMaxWidth()) {
        if (title != null) TvRowTitle(title)
        CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
            LazyRow(
                Modifier.fillMaxWidth()
                    .onFocusChanged { if (it.hasFocus) rowFocus.value?.invoke() }
                    .focusProperties {
                        enter = { if (lastIdx >= 0 && attachedIdx[0] == lastIdx) lastFr else FocusRequester.Default }
                    }
                    .focusGroup(),
                state = state,
                contentPadding = PaddingValues(start = TvEdge, end = TvEdge, top = 10.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                itemsIndexed(items, key = { _, it -> key(it) }) { i, it ->
                    var m: Modifier = if (i == 0) edgeMod else Modifier
                    if (i == restoreIndex && restoreRequester != null) m = m.focusRequester(restoreRequester)
                    if (i == lastIdx) {
                        m = m.focusRequester(lastFr)
                        DisposableEffect(i) {
                            attachedIdx[0] = i
                            onDispose { if (attachedIdx[0] == i) attachedIdx[0] = -1 }
                        }
                    }
                    m = m.onFocusChanged { f -> if (f.hasFocus) { if (lastIdx != i) lastIdx = i; itemFocus.value?.invoke(i) } }
                    itemContent(i, it, m)
                }
            }
        }
    }
}

/** Grey placeholder cards with a gentle pulse (animated in the draw phase only). */
@Composable
fun TvLoadingRow(w: Dp, h: Dp, count: Int = 6, title: Boolean = true) {
    val pulse = rememberInfiniteTransition(label = "tvPulse")
    val a = pulse.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "tvPulseA")
    Column(Modifier.fillMaxWidth()) {
        if (title) Box(
            Modifier.padding(start = TvEdge, top = 16.dp).size(140.dp, 14.dp).graphicsLayer { alpha = a.value }
                .clip(RoundedCornerShape(4.dp)).background(Surface1),
        )
        Row(Modifier.padding(start = TvEdge, top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(count) {
                Box(Modifier.size(w, h).graphicsLayer { alpha = a.value }.clip(RoundedCornerShape(10.dp)).background(Surface1))
            }
        }
    }
}

// ───────────── Cards ─────────────

private val CardLabelShade = Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD9000000))

/** Poster card (2:3 by default; also used for the addon's own landscape posters). Image only, title on focus ring. */
@Composable
fun TvPosterCard(m: Meta, modifier: Modifier = Modifier, w: Dp = 120.dp, h: Dp = 180.dp, image: String? = m.poster ?: backdropOf(m), onClick: () -> Unit) {
    val shape = remember { RoundedCornerShape(10.dp) }
    val req = tvImage(image, w, h)
    Box(modifier.size(w, h).tvCard(10.dp, onClick = onClick).clip(shape).background(Surface1)) {
        if (req != null) AsyncImage(req, m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (image == null) Text(
            m.name, Modifier.align(Alignment.Center).padding(8.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Wide 16:9 card from the backdrop, with the title logo (or the title) at the bottom-left. */
@Composable
fun TvWideCard(m: Meta, modifier: Modifier = Modifier, w: Dp = 180.dp, h: Dp = 101.dp, onClick: () -> Unit) {
    val shape = remember { RoundedCornerShape(10.dp) }
    val img = backdropOf(m) ?: m.poster
    val req = tvImage(img, w, h)
    val logo = logoOf(m)
    val logoReq = tvImage(logo, w * 0.55f, 30.dp)
    var logoFailed by remember(m.id) { mutableStateOf(false) }
    Box(modifier.size(w, h).tvCard(10.dp, onClick = onClick).clip(shape).background(Surface1)) {
        if (req != null) AsyncImage(req, m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(CardLabelShade))
        Box(Modifier.align(Alignment.BottomStart).padding(8.dp).fillMaxWidth(0.6f)) {
            if (logoReq != null && !logoFailed) {
                AsyncImage(logoReq, m.name, Modifier.fillMaxWidth().height(28.dp), contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart, onError = { logoFailed = true })
            } else {
                Text(m.name, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Card shape for a home/catalog row: the addon's landscape posters stay landscape, everything else is a poster. */
fun tvLandscapeRow(items: List<Meta>): Boolean =
    items.firstOrNull { it.posterShape != null }?.posterShape.equals("landscape", ignoreCase = true)

/** A row of titles (posters, or wide cards for landscape catalogs). */
@Composable
fun TvMetaRow(
    title: String?,
    items: List<Meta>,
    onOpen: (Meta) -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = tvLandscapeRow(items),
    backdrops: Boolean = false,
    state: LazyListState = rememberLazyListState(),
    restoreIndex: Int = -1,
    restoreRequester: FocusRequester? = null,
    onLeftEdge: ((FocusRequester) -> Unit)? = null,
    onRowFocus: (() -> Unit)? = null,
    onItemFocus: ((Int) -> Unit)? = null,
) {
    TvRow(
        items, key = { it.type + it.id }, modifier = modifier, title = title, state = state,
        restoreIndex = restoreIndex, restoreRequester = restoreRequester,
        onLeftEdge = onLeftEdge, onRowFocus = onRowFocus, onItemFocus = onItemFocus,
    ) { _, m, mod ->
        when {
            backdrops -> TvWideCard(m, mod) { onOpen(m) }
            wide -> TvPosterCard(m, mod, 200.dp, 112.dp) { onOpen(m) }
            else -> TvPosterCard(m, mod) { onOpen(m) }
        }
    }
}

/**
 * A page that scrolls with the remote too: UP/DOWN move to the next control as usual, and where there is none
 * (stats, text, pictures) the page scrolls instead, so everything on it can be reached. Phones: a plain scroll.
 */
@androidx.compose.runtime.Composable
fun Modifier.pageScroll(): Modifier {
    val st = androidx.compose.foundation.rememberScrollState()
    if (!BuildConfig.IS_TV) return this.verticalScroll(st)
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return this.onPreviewKeyEvent { e ->
        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            androidx.compose.ui.input.key.Key.DirectionDown -> {
                if (!fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)) {
                    if (st.value >= st.maxValue) return@onPreviewKeyEvent false
                    scope.launch { st.animateScrollBy(260f) }
                }
                true
            }
            androidx.compose.ui.input.key.Key.DirectionUp -> {
                if (!fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)) {
                    if (st.value <= 0) return@onPreviewKeyEvent false
                    scope.launch { st.animateScrollBy(-260f) }
                }
                true
            }
            else -> false
        }
    }.verticalScroll(st)
}

/**
 * TV: text boxes don't open the keyboard just because the remote moves onto them — only when you press OK.
 * (Phones: unchanged.)
 */
fun tvKeyboard(base: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default): androidx.compose.foundation.text.KeyboardOptions =
    if (BuildConfig.IS_TV) base.copy(showKeyboardOnFocus = false) else base

/**
 * TV: a text box the remote can move onto WITHOUT the keyboard opening. The box itself can't take focus until you press
 * OK on it — then it does, and the keyboard opens. Moving away makes it a plain stop again. (Phones: unchanged.)
 */
/**
 * [top]: the gap a labelled outlined field keeps above its box (its ring starts there), [corner]: the field's corner,
 * [ring] = false when the field draws its own focus edge.
 */
fun Modifier.tvOkKeyboard(top: Dp = 0.dp, corner: Dp = 12.dp, ring: Boolean = true): Modifier = if (!BuildConfig.IS_TV) this else composed {
    var editing by remember { mutableStateOf(false) }
    var outer by remember { mutableStateOf(false) }
    val inner = remember { FocusRequester() }
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    this
        .onPreviewKeyEvent { e ->
            val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
            if (ok && !editing && outer) {
                if (e.type == KeyEventType.KeyUp) {
                    editing = true
                    scope.launch { androidx.compose.runtime.withFrameNanos { }; inner.tryFocus(); kb?.show() }
                }
                true
            } else false
        }
        .onFocusChanged { outer = it.isFocused }
        .drawWithContent {
            drawContent()
            // The ring sits just outside the field's own box with the same rounded corners.
            if (outer && ring) {
                val g = 3.dp.toPx(); val t = top.toPx()
                drawRoundRect(TvFocusEdge, topLeft = Offset(-g, t - g), size = Size(size.width + 2 * g, size.height - t + 2 * g),
                    cornerRadius = CornerRadius(corner.toPx() + g), style = Stroke(2.dp.toPx()))
            }
        }
        .focusable()
        .focusProperties { canFocus = editing }
        .focusRequester(inner)
        .onFocusChanged { if (editing && !it.isFocused && !it.hasFocus) editing = false }
}
