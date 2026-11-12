package de.jobisoft.amster.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.jobisoft.amster.ui.components.GlassCardFull
import de.jobisoft.amster.ui.components.SongTexts
import de.jobisoft.amster.ui.viewmodel.SongInfo

/** What the hidden box shows instead of the song, only the shape of a year, a title and an artist. */
private const val DUMMY_YEAR = "0000"
private const val DUMMY_TITLE = "Xxxxxxxx xxxxx"
private const val DUMMY_ARTIST = "Xxxxxxx"

/** The line under the box, the same height in both states so the screen does not move when the song is shown. */
internal fun revealHint(revealed: Boolean): String =
    if (revealed) "Tap to hide, long press to edit" else "Tap to reveal"

/**
 * A big glass card that hides year, title and artist of the song until it is tapped, with a hint line under it.
 * Hidden, it shows blurred dummy text (the blur works from Android 12, so the real text is never composed
 * while hidden: older phones cannot leak it). A tap shows the song, another tap hides it. The change is a short
 * crossfade, the real text exists only in the revealed state. A long press on the shown song calls [onEdit].
 */
@Composable
internal fun RevealBox(song: SongInfo, revealed: Boolean, onToggle: () -> Unit, onEdit: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassCardFull(
            modifier = Modifier.heightIn(min = 180.dp),
            onClick = onToggle,
            onClickLabel = if (revealed) "Hide the song" else "Show the song",
            onLongClick = if (revealed) onEdit else null,
            onLongClickLabel = if (revealed) "Edit the song" else null,
            verticalArrangement = Arrangement.Center,
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
                            yearBrush = de.jobisoft.amster.ui.theme.AmsterTheme.extras.accentBrush
                        )
                    } else {
                        SongTexts(
                            artist = DUMMY_ARTIST, year = DUMMY_YEAR, title = DUMMY_TITLE,
                            modifier = Modifier
                                .blur(16.dp)
                                .alpha(0.45f)
                        )
                    }
                }
            }
        }
        Text(
            revealHint(revealed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.heightIn(min = 24.dp)
        )
    }
}
