package de.jobisoft.amster.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val AmsterColorScheme = darkColorScheme(
    primary = palette(Palette.ACCENT_A),
    onPrimary = palette(Palette.ON_ACCENT),
    primaryContainer = palette(Palette.SURFACE_HIGH),
    onPrimaryContainer = palette(Palette.TEXT),
    secondary = palette(Palette.ACCENT_B),
    onSecondary = palette(Palette.ON_ACCENT),
    secondaryContainer = palette(Palette.SURFACE_HIGH),
    onSecondaryContainer = palette(Palette.TEXT),
    tertiary = palette(Palette.SUCCESS),
    onTertiary = palette(Palette.ON_SUCCESS),
    tertiaryContainer = palette(Palette.SUCCESS_CONTAINER),
    onTertiaryContainer = palette(Palette.SUCCESS),
    background = palette(Palette.BG_TOP),
    onBackground = palette(Palette.TEXT),
    surface = palette(Palette.SURFACE),
    onSurface = palette(Palette.TEXT),
    surfaceVariant = palette(Palette.SURFACE_HIGH),
    onSurfaceVariant = palette(Palette.TEXT_DIM),
    surfaceContainerLowest = palette(Palette.BG_TOP),
    surfaceContainerLow = palette(Palette.SURFACE),
    surfaceContainer = palette(Palette.SURFACE),
    surfaceContainerHigh = palette(Palette.SURFACE_HIGH),
    surfaceContainerHighest = palette(Palette.SURFACE_HIGH),
    error = palette(Palette.ERROR),
    onError = palette(Palette.ON_ACCENT),
    errorContainer = palette(Palette.ERROR_CONTAINER),
    onErrorContainer = palette(Palette.TEXT),
    outline = Color.White.copy(alpha = 0.32f),
    outlineVariant = Color.White.copy(alpha = Palette.GLASS_BORDER_ALPHA)
)

/** The one theme of the app: always dark, gradient background, glass cards. */
@Composable
fun AmsterTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAmsterExtras provides DefaultExtras) {
        MaterialTheme(
            colorScheme = AmsterColorScheme,
            typography = Typography,
            shapes = AmsterShapes,
            content = content
        )
    }
}

object AmsterTheme {
    val extras: AmsterExtras
        @Composable get() = LocalAmsterExtras.current
}
