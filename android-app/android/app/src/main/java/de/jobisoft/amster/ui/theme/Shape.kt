package de.jobisoft.amster.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Soft corners: 12 dp small things, 20 dp cards, 28 dp big cards and dialogs. Buttons are pills (full radius). */
val AmsterShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

val GlassShape = RoundedCornerShape(24.dp)
