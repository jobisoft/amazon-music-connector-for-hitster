package de.jobisoft.amster.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import de.jobisoft.amster.ui.theme.AmsterTheme

/** A small glass pill with one line of text, for a status like the camera countdown. */
@Composable
fun StatusChip(text: String, modifier: Modifier = Modifier) {
    val extras = AmsterTheme.extras
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .clip(CircleShape)
            .background(extras.glassFill)
            .border(1.dp, extras.glassBorder, CircleShape)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
