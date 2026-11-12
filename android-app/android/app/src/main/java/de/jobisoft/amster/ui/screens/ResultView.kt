package de.jobisoft.amster.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.jobisoft.amster.ui.ReportCard
import de.jobisoft.amster.ui.ReportWrongOverlay
import de.jobisoft.amster.ui.rememberOpenEdit
import de.jobisoft.amster.ui.components.BottomButtonScreen
import de.jobisoft.amster.ui.components.PrimaryButton
import de.jobisoft.amster.ui.components.SecondaryButton
import de.jobisoft.amster.ui.theme.AmsterTheme
import de.jobisoft.amster.ui.viewmodel.GameUiState
import de.jobisoft.amster.ui.viewmodel.SendState

/**
 * The text of the green box: the name of the card set, nothing else. It shows which card is on the screen
 * and has nothing to do with the player (pause, stop, the end of the song). Without a known set
 * there is only "Displaying card".
 */
internal fun displayingLine(setName: String?): String = setName ?: "Displaying card"

/**
 * Shows what happened to the card, shared by the scanner and the quiz. The song itself stays
 * hidden in the reveal box until it is tapped, it would spoil the game. One column: the content scrolls in the
 * free space, the next button sits at the bottom of the screen and nothing appears or disappears when the song
 * is shown (the edit overlay opens by a long press on the shown song).
 */
@Composable
internal fun ResultView(
    state: GameUiState,
    nextLabel: String,
    reportSource: String,
    onControl: (String, Float?) -> Unit,
    onToggleReveal: () -> Unit,
    onNext: () -> Unit,
    onCancel: () -> Unit,
    /** Null where the mode has no OCR (the quiz). */
    onReadOcr: (() -> Unit)?
) {
    val reportCard = state.cardRef?.let { ref ->
        state.song?.let { song -> ReportCard(state.cardSerial, ref.setFile, ref.number, song.year, song.title, song.artist, song.amazonId, song.album) }
    }
    val openEdit = rememberOpenEdit(reportCard)
    val finished = state.sendState == SendState.SUCCESS || state.sendState == SendState.FAILED

    BottomButtonScreen(
        // the set name sits at the top of the screen, apart from the centered block
        top = if (state.sendState != SendState.SUCCESS) null else {
            {
                if (state.info != null) {
                    Text(
                        state.info,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                // always there while a card is on the result screen: pause, stop and the end of the song do not touch it
                StatusCard(title = displayingLine(state.setName), isError = false)
            }
        },
        bottomButton = if (!finished) null else {
            {
                PrimaryButton(
                    text = if (state.stopping) "Stopping the song ..." else nextLabel,
                    onClick = onNext,
                    enabled = !state.stopping,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    ) {
        when (state.sendState) {
            SendState.SENDING -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = AmsterTheme.extras.glassBorder
                    )
                    Text(
                        "Sending song to the Amazon Music app.",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    if (state.status != null) {
                        Text(
                            state.status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }
                }
                if (state.canCancel) {
                    Spacer(modifier = Modifier.height(24.dp))
                    SecondaryButton(text = "Cancel", onClick = onCancel)
                }
            }

            // same look as SENDING: a centered column, nothing is sent to Amazon Music
            SendState.UNKNOWN -> {
                val unknown = state.unknown
                UnknownCardView(unknown?.title ?: "Unknown card", unknown?.lines.orEmpty())
                Spacer(modifier = Modifier.height(24.dp))
                SecondaryButton(text = "Cancel", onClick = onCancel)
            }

            SendState.SUCCESS -> {
                PlayerControls(
                    playing = state.playing,
                    stopped = state.stopped,
                    songEnded = state.songEnded,
                    busy = state.controlBusy,
                    position = state.position,
                    duration = state.duration,
                    onControl = onControl
                )
                if (state.songEnded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "The song ended",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                if (state.controlError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(state.controlError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                if (state.song != null) {
                    Spacer(modifier = Modifier.height(20.dp))
                    RevealBox(
                        song = state.song,
                        revealed = state.revealed,
                        onToggle = onToggleReveal,
                        onEdit = { openEdit?.invoke() }
                    )
                    ReportWrongOverlay(reportCard, state.revealed, reportSource, onReadOcr)
                }
            }

            SendState.FAILED -> ErrorBoxes(state.message ?: "Something went wrong.")

            SendState.IDLE -> Unit
        }
    }
}
