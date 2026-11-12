package de.jobisoft.amster.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** The scan window is this share of the free width (a phone held upright), so the card fills most of the screen. */
const val SCAN_WINDOW_WIDTH_SHARE = 0.9f

/** The frame inside the camera view leaves this share of the view free on each side. */
const val SCAN_FRAME_MARGIN = 0.05

/**
 * A square window for the camera. Hitster cards are square, so the view and its frame are square too.
 * The side is [SCAN_WINDOW_WIDTH_SHARE] of the free width, at most the free height. The square is centered.
 */
@Composable
fun SquareScanWindow(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val side: Dp = minOf(maxWidth * SCAN_WINDOW_WIDTH_SHARE, maxHeight)
        Box(modifier = Modifier.size(side), content = content)
    }
}
