package de.jobisoft.amster.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

private fun c(argb: Long) = Color(argb)

/** Colors and brushes that the Material color scheme has no place for. Get them with [AmsterTheme.extras]. */
@Immutable
data class AmsterExtras(
    val backgroundBrush: Brush,
    val accentBrush: Brush,
    val glassFill: Color,
    val glassBorder: Color,
    val textDim: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onAccent: Color
)

internal val DefaultExtras = AmsterExtras(
    backgroundBrush = Brush.verticalGradient(listOf(c(Palette.BG_TOP), c(Palette.BG_BOTTOM))),
    accentBrush = Brush.horizontalGradient(listOf(c(Palette.ACCENT_A), c(Palette.ACCENT_B))),
    glassFill = c(Palette.WHITE).copy(alpha = Palette.GLASS_FILL_ALPHA),
    glassBorder = c(Palette.WHITE).copy(alpha = Palette.GLASS_BORDER_ALPHA),
    textDim = c(Palette.TEXT_DIM),
    success = c(Palette.SUCCESS),
    onSuccess = c(Palette.ON_SUCCESS),
    successContainer = c(Palette.SUCCESS_CONTAINER),
    onAccent = c(Palette.ON_ACCENT)
)

internal val LocalAmsterExtras = staticCompositionLocalOf { DefaultExtras }

internal fun palette(argb: Long): Color = c(argb)
