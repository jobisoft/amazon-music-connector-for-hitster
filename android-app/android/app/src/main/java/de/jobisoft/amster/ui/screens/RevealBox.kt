package de.jobisoft.amster.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import de.jobisoft.amster.ui.components.GlassCardFull
import de.jobisoft.amster.ui.components.SongTexts
import de.jobisoft.amster.ui.components.StatusChip
import de.jobisoft.amster.ui.theme.AmsterTheme
import de.jobisoft.amster.ui.viewmodel.SongInfo

/** What the hidden box shows instead of the song, only the shape of a year, a title and an artist. */
private const val DUMMY_YEAR = "0000"
private const val DUMMY_TITLE = "Xxxxxxxx xxxxx"
private const val DUMMY_ARTIST = "Xxxxxxx"

/**
 * A big glass card that hides year, title and artist of the song until it is tapped. Hidden, it shows
 * blurred dummy text (the blur works from Android 12, so the real text is never composed
 * while hidden: older phones cannot leak it). A tap shows the song, another tap hides it. The change is a short
 * crossfade, the real text exists only in the revealed state.
 */
@Composable
internal fun RevealBox(song: SongInfo, revealed: Boolean, onToggle: () -> Unit) {
    GlassCardFull(
        modifier = Modifier.heightIn(min = 180.dp),
        onClick = onToggle,
        onClickLabel = if (revealed) "Hide the song" else "Show the song",
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Crossfade(targetState = revealed, animationSpec = tween(220), label = "reveal") { show ->
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 148.dp),
                contentAlignment = Alignment.Center
            ) {
                if (show) {
                    // the only place where the real song is used
                    SongTexts(
                        artist = song.artist,
                        year = song.year?.toString() ?: "?",
                        title = song.title,
                        yearBrush = AmsterTheme.extras.accentBrush
                    )
                } else {
                    SongTexts(
                        artist = DUMMY_ARTIST, year = DUMMY_YEAR, title = DUMMY_TITLE,
                        modifier = Modifier
                            .blur(16.dp)
                            .alpha(0.45f)
                    )
                    StatusChip("Tap to reveal")
                }
            }
        }
    }
}
