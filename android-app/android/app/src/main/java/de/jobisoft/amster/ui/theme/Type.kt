package de.jobisoft.amster.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun style(weight: FontWeight, size: Int, line: Int, spacing: Double = 0.0) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp
)

/** The system font. Headlines are bold with tight spacing, labels medium, body text regular. */
val Typography = Typography(
    displayLarge = style(FontWeight.Bold, 57, 64, -0.5),
    displayMedium = style(FontWeight.Bold, 45, 52, -0.25),
    displaySmall = style(FontWeight.Bold, 36, 44, -0.25),
    headlineLarge = style(FontWeight.Bold, 32, 40, -0.25),
    headlineMedium = style(FontWeight.Bold, 28, 36),
    headlineSmall = style(FontWeight.SemiBold, 24, 32),
    titleLarge = style(FontWeight.SemiBold, 22, 28),
    titleMedium = style(FontWeight.SemiBold, 16, 24, 0.1),
    titleSmall = style(FontWeight.Medium, 14, 20, 0.1),
    bodyLarge = style(FontWeight.Normal, 16, 24, 0.3),
    bodyMedium = style(FontWeight.Normal, 14, 20, 0.25),
    bodySmall = style(FontWeight.Normal, 12, 16, 0.4),
    labelLarge = style(FontWeight.SemiBold, 16, 20, 0.3),
    labelMedium = style(FontWeight.Medium, 12, 16, 0.5),
    labelSmall = style(FontWeight.Medium, 11, 16, 0.5)
)
