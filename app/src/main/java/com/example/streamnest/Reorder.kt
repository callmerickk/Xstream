package com.example.streamnest

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * A list you reorder by holding and dragging (no up / down arrows): hold a row (or drag its ⠿ handle) and move it; the
 * others slide out of the way, and it settles where you let go. [locked] rows can't move and nothing can pass them.
 */
@Composable
fun <T> ReorderColumn(
    items: List<T>,
    key: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    locked: (T) -> Boolean = { false },
    holdWholeRow: Boolean = true,
    row: @Composable (item: T, index: Int, dragging: Boolean, handle: Modifier) -> Unit,
) {
    var dragKey by remember { mutableStateOf<Any?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Any, Int>() }
    val haptics = LocalHapticFeedback.current
    val cur by rememberUpdatedState(items)
    // The drag gestures live as long as their row; they must call the newest onMove / locked (the list behind them
    // changes after every move). With the first ones, a second move was computed on the old order and undid the first.
    val moveNow by rememberUpdatedState(onMove)
    val lockedNow by rememberUpdatedState(locked)
    fun h(i: Int): Int = cur.getOrNull(i)?.let { heights[key(it)] } ?: 0
    fun from(): Int = cur.indexOfFirst { key(it) == dragKey }
    fun target(): Int {
        val f = from(); if (f < 0) return -1
        var t = f; var y = dragY
        if (y > 0) while (t < cur.lastIndex && !lockedNow(cur[t + 1]) && y > h(t + 1) / 2f) { y -= h(t + 1); t++ }
        else while (t > 0 && !lockedNow(cur[t - 1]) && -y > h(t - 1) / 2f) { y += h(t - 1); t-- }
        return t
    }
    fun end() {
        val f = from(); val t = target()
        dragKey = null; dragY = 0f
        if (f >= 0 && t >= 0 && f != t) moveNow(f, t)
    }
    Column(modifier) {
        items.forEachIndexed { i, item ->
            val k = key(item)
            val isLocked = locked(item)
            key(k) {
                val gesture: suspend androidx.compose.ui.input.pointer.PointerInputScope.(Boolean) -> Unit = { afterHold ->
                    val start: (androidx.compose.ui.geometry.Offset) -> Unit = { dragKey = k; dragY = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                    val drag: (androidx.compose.ui.input.pointer.PointerInputChange, androidx.compose.ui.geometry.Offset) -> Unit = { c, d ->
                        c.consume(); val before = target(); dragY += d.y
                        if (target() != before) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    if (afterHold) detectDragGesturesAfterLongPress(onDragStart = start, onDrag = drag, onDragEnd = { end() }, onDragCancel = { end() })
                    else detectDragGestures(onDragStart = start, onDrag = drag, onDragEnd = { end() }, onDragCancel = { end() })
                }
                val handle = if (isLocked) Modifier else Modifier.pointerInput(k) { gesture(false) }
                val dragging = dragKey == k
                Box(
                    Modifier.onSizeChanged { heights[k] = it.height }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            val f = from(); val t = if (f >= 0) target() else -1
                            translationY = when {
                                f < 0 -> 0f
                                dragging -> dragY
                                i in (f + 1)..t -> -h(f).toFloat()
                                i in t until f -> h(f).toFloat()
                                else -> 0f
                            }
                            if (dragging) { scaleX = 1.03f; scaleY = 1.03f; shadowElevation = 18f; alpha = 0.96f }
                        }
                        .then(if (holdWholeRow && !isLocked) Modifier.pointerInput(k) { gesture(true) } else Modifier),
                ) { row(item, i, dragging, handle) }
            }
        }
    }
}

/** The ⠿ grip at the end of a row: drag it straight away (the row itself needs a hold first). */
@Composable
fun DragGrip(handle: Modifier, enabled: Boolean = true) {
    Box(handle.size(44.dp), Alignment.Center) {
        Icon(Icons.Default.DragIndicator, "Hold and drag to move", tint = if (enabled) Color(0xFFB6BDCC) else Color(0x33FFFFFF))
    }
}
