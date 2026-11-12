package de.jobisoft.amster.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.jobisoft.amster.ui.components.GlassCardFull
import de.jobisoft.amster.ui.components.RoundIconButton
import de.jobisoft.amster.ui.theme.AmsterTheme
import de.jobisoft.amster.util.ControlAction
import de.jobisoft.amster.util.ControlButtons

internal fun clock(seconds: Float): String {
    val total = seconds.toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * 10 s back, Pause <-> Play, Stop <-> Restart, 10 s forward, then the slider. The two toggles show
 * the NEXT possible action (see [ControlButtons]). The buttons are sized from the free width: the big
 * round Pause/Play button (accent gradient) is [BIG] at most, Stop/Restart 80 percent of it, the two 10 s buttons 70 percent.
 */
@Composable
internal fun PlayerControls(
    playing: Boolean,
    stopped: Boolean,
    songEnded: Boolean,
    busy: Boolean,
    position: Float,
    duration: Float,
    onControl: (String, Float?) -> Unit
) {
    val toggles = ControlButtons.from(playing, stopped, songEnded)
    GlassCardFull {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val big: Dp = minOf(BIG, maxWidth * 0.2f)
                val medium = big * 0.8f
                val small = big * 0.7f
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RoundIconButton(
                        icon = Icons.Filled.Replay10,
                        contentDescription = "10 seconds back",
                        onClick = { onControl("back10", null) },
                        enabled = !busy,
                        size = small
                    )
                    val pause = toggles.pausePlay == ControlAction.PAUSE
                    RoundIconButton(
                        icon = if (pause) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (pause) "Pause" else "Play",
                        onClick = { onControl(toggles.pausePlay.wire, null) },
                        enabled = !busy,
                        size = big,
                        iconSize = big * 0.55f,
                        filled = true
                    )
                    val stop = toggles.stopRestart == ControlAction.STOP
                    RoundIconButton(
                        icon = if (stop) Icons.Filled.Stop else Icons.Filled.Replay,
                        contentDescription = if (stop) "Stop" else "Play again",
                        onClick = { onControl(toggles.stopRestart.wire, null) },
                        enabled = !busy,
                        size = medium,
                        iconSize = medium * 0.5f
                    )
                    RoundIconButton(
                        icon = Icons.Filled.Forward10,
                        contentDescription = "10 seconds forward",
                        onClick = { onControl("forward10", null) },
                        enabled = !busy,
                        size = small
                    )
                }
            }
            // the slider follows the song, a finger on it takes over until it is let go
            var dragging by remember { mutableStateOf<Float?>(null) }
            val known = duration > 0f
            val shown = (dragging ?: position).coerceIn(0f, if (known) duration else 1f)
            Spacer(modifier = Modifier.height(12.dp))
            Slider(
                value = if (known) shown else 0f,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onControl("seek", it) }
                    dragging = null
                },
                valueRange = 0f..(if (known) duration else 1f),
                enabled = known && !busy,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                    disabledThumbColor = Color.White.copy(alpha = 0.3f),
                    disabledActiveTrackColor = Color.White.copy(alpha = 0.3f),
                    disabledInactiveTrackColor = Color.White.copy(alpha = 0.12f)
                )
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(shown), style = MaterialTheme.typography.bodyMedium, color = AmsterTheme.extras.textDim)
                Text(if (known) clock(duration) else "--:--", style = MaterialTheme.typography.bodyMedium, color = AmsterTheme.extras.textDim)
            }
        }
    }
}

/** The size of the big Pause/Play button at most. */
private val BIG = 84.dp
