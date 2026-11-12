package de.jobisoft.amster.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import de.jobisoft.amster.ui.theme.AmsterTheme

/** The gradient behind every screen: near black at the top, deep violet at the bottom, with a soft glow of the accent. */
@Composable
fun AmsterBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val glow = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AmsterTheme.extras.backgroundBrush)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(glow.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.9f, size.height * 0.04f),
                        radius = size.minDimension * 0.95f
                    )
                )
            },
        content = content
    )
}
