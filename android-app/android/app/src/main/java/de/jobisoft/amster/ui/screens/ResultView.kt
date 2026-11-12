package de.jobisoft.amster.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.jobisoft.amster.ui.ReportCard
import de.jobisoft.amster.ui.ReportWrongSection
import de.jobisoft.amster.ui.components.GlassCardFull
import de.jobisoft.amster.ui.components.PrimaryButton
import de.jobisoft.amster.ui.components.SecondaryButton
import de.jobisoft.amster.ui.theme.AmsterTheme
import de.jobisoft.amster.ui.theme.Dimens
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
 * hidden in the reveal box until it is tapped, it would spoil the game. On a wide screen (a tablet, a phone in
 * landscape) the finished card has two columns: the card and the song on the left, the player and the next button
 * on the right. Otherwise everything is one column.
 */
@Composable
internal fun ResultView(
    state: GameUiState,
    nextLabel: String,
    reportSource: String,
    onControl: (String, Float?) -> Unit,
    onToggleReveal: () -> Unit,
    onNext: () -> Unit,
    onCancel: () -> Unit
) {
    // the parts of a finished card, placed in one or two columns below
    val top: @Composable ColumnScope.() -> Unit = {
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
    val controls: @Composable ColumnScope.() -> Unit = {
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
    }
    val song: @Composable ColumnScope.() -> Unit = {
        if (state.song != null) {
            RevealBox(song = state.song, revealed = state.revealed, onToggle = onToggleReveal)
            val ref = state.cardRef
            if (ref != null) {
                ReportWrongSection(
                    ReportCard(state.cardSerial, ref.setFile, ref.number, state.song.year, state.song.title, state.song.artist, state.song.amazonId, state.song.album),
                    state.revealed, reportSource
                )
            }
        }
    }
    val nextButton: @Composable () -> Unit = {
        PrimaryButton(
            text = if (state.stopping) "Stopping the song ..." else nextLabel,
            onClick = onNext,
            enabled = !state.stopping,
            modifier = Modifier.fillMaxWidth()
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (state.sendState == SendState.SUCCESS && maxWidth >= Dimens.WideBreakpoint) {
            Row(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
                ) {
                    top()
                    song()
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
                ) {
                    controls()
                    nextButton()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        unknown?.title ?: "Unknown card",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    unknown?.lines?.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                SecondaryButton(text = "Cancel", onClick = onCancel)
            }

            SendState.SUCCESS -> {
                    top()
                    Spacer(modifier = Modifier.height(20.dp))
                    controls()
                    if (state.song != null) Spacer(modifier = Modifier.height(20.dp))
                    song()
                }

                SendState.FAILED -> ErrorBoxes(state.message ?: "Something went wrong.")

                SendState.IDLE -> Unit
                }

                Spacer(modifier = Modifier.height(24.dp))

                val finished = state.sendState == SendState.SUCCESS || state.sendState == SendState.FAILED
                if (finished) nextButton()
            }
        }
    }
}
