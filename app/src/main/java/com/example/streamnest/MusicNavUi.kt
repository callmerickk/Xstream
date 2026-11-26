package com.example.streamnest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp

/**
 * musicFix1 · a tab or page that stays alive while another page is on top of it. When [hidden] it is still measured
 * but not placed: not drawn, not touchable. Its scroll position and loaded rows are kept for Back.
 */
@Composable
fun KeptTab(hidden: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().layout { measurable, constraints ->
        val p = measurable.measure(constraints)
        layout(p.width, p.height) { if (!hidden) p.place(0, 0) }
    }) { content() }
}

/** musicFix1 · the mini player as a rounded card: 8 dp from the sides, 18 dp corners, slight elevation. */
fun Modifier.miniPlayerCard(): Modifier =
    this.padding(horizontal = 8.dp, vertical = 4.dp).shadow(6.dp, RoundedCornerShape(18.dp))
