package de.jobisoft.hitsterconnector.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The three values of a card in one order and one look, centered: artist (titleMedium), year (64 sp, bold),
 * title (titleLarge). The reader box, the reveal box of the game and the quiz, and their hidden state
 * (dummy texts) all use it, so nothing jumps when a box is revealed. [content] adds lines below.
 */
@Composable
fun SongTexts(
    artist: String,
    year: String,
    title: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    content: @Composable () -> Unit = {}
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(artist, style = MaterialTheme.typography.titleMedium, color = color, textAlign = TextAlign.Center)
        Text(year, fontSize = 64.sp, fontWeight = FontWeight.Bold, color = color, textAlign = TextAlign.Center)
        Text(title, style = MaterialTheme.typography.titleLarge, color = color, textAlign = TextAlign.Center)
        content()
    }
}
