package de.jobisoft.amster.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Pink80,
    onPrimary = Color.Black,
    primaryContainer = PinkGrey80,
    onPrimaryContainer = Color.Black,
    secondary = PinkGrey80,
    onSecondary = Color.Black,
    tertiary = Magenta80,
    onTertiary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color.White,
    error = Color(0xFFFF5252),
    onError = Color.Black,
    errorContainer = Color(0xFF8B0000),
    onErrorContainer = Color.White,
    outline = Color(0xFF424242),
    outlineVariant = Color(0xFF2A2A2A)
)

private val LightColorScheme = lightColorScheme(
    primary = Pink40,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD6E7),
    onPrimaryContainer = Color.Black,
    secondary = PinkGrey40,
    onSecondary = Color.White,
    tertiary = Magenta40,
    onTertiary = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color.White,
    error = Color(0xFFFF5252),
    onError = Color.Black,
    errorContainer = Color(0xFF8B0000),
    onErrorContainer = Color.White,
    outline = Color(0xFF424242),
    outlineVariant = Color(0xFF2A2A2A)
)

@Composable
fun AmSterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color disabled to use our black theme consistently
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}