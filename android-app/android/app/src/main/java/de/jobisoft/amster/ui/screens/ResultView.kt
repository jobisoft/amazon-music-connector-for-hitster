package de.jobisoft.amster.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import de.jobisoft.amster.ui.components.BackButton
import de.jobisoft.amster.ui.components.SongTexts
import de.jobisoft.amster.ui.viewmodel.SendState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.text.style.TextAlign
import de.jobisoft.amster.ui.ReportCard
import de.jobisoft.amster.ui.ReportWrongSection
import de.jobisoft.amster.ui.viewmodel.GameUiState
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import de.jobisoft.amster.util.ControlAction
import de.jobisoft.amster.util.ControlButtons
import de.jobisoft.amster.util.HideStatusBarSetting
import de.jobisoft.amster.ui.viewmodel.SongInfo

/** Keeps the screen on while a card is being handled. */
@Composable
internal fun KeepScreenOn(active: Boolean) {
    val context = LocalContext.current
    DisposableEffect(active) {
        val window = (context as? Activity)?.window
        if (active) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/**
 * Hides the status bar while a card song is on the screen ([songOnScreen]) and the switch "Hide the status bar while
 * playing" on the Options screen is on. Some phones show the title of the song in the status bar, which would give the card
 * away. A swipe from the top shows the bar for a moment. The bar comes back when the screen is left. The navigation bar
 * stays as it is.
 */
@Composable
internal fun HideStatusBar(songOnScreen: Boolean) {
    val context = LocalContext.current
    val enabled = remember { HideStatusBarSetting.isEnabled(context) }
    val hide = HideStatusBarSetting.shouldHide(enabled, songOnScreen)
    DisposableEffect(hide) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (hide) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.statusBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.statusBars())
        }
    }
}

/**
 * The two lines of the green box: our text, then the card set. It shows which card is on the screen
 * and has nothing to do with the player (pause, stop, the end of the song). Without a known set
 * there is only "Displaying card".
 */
internal fun displayingLines(setName: String?): Pair<String, String?> = when {
    setName != null -> "Displaying card from" to setName
    else -> "Displaying card" to null
}

/**
 * Shows what happened to the card, shared by the scanner and the quiz. The song itself stays
 * hidden in the reveal box until it is tapped, it would spoil the game.
 */
@Composable
internal fun ResultView(
    state: GameUiState,
    nextLabel: String,
    reportSource: String,
    onControl: (String, Float?) -> Unit,
    onToggleReveal: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit
) {
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
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Sending card info to the Amazon Music app ...", style = MaterialTheme.typography.titleMedium)
                if (state.status != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        state.status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
                if (state.canCancel) {
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            }

            // same look as SENDING: a centered column, nothing is sent to Amazon Music
            SendState.UNKNOWN -> {
                val unknown = state.unknown
                Text(
                    unknown?.title ?: "Unknown card",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                unknown?.lines?.forEach { line ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }

            SendState.SUCCESS -> {
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
                val (displayingText, displayingSet) = displayingLines(state.setName)
                StatusCard(title = displayingText, subtitle = displayingSet, isError = false)
                Spacer(modifier = Modifier.height(20.dp))
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
                        style = MaterialTheme.typography.bodySmall,
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

            SendState.FAILED -> ErrorBoxes(state.message ?: "Something went wrong.")

            SendState.IDLE -> Unit
        }

        Spacer(modifier = Modifier.height(24.dp))

        val finished = state.sendState == SendState.SUCCESS || state.sendState == SendState.FAILED
        if (finished) {
            Button(
                onClick = onNext,
                enabled = !state.stopping,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(if (state.stopping) "Stopping the song ..." else nextLabel)
            }
            Spacer(modifier = Modifier.height(8.dp))
            BackButton(onClick = onBack)
        }
    }
}

/** What the hidden box shows instead of the song, only the shape of a year, a title and an artist. */
private const val DUMMY_YEAR = "0000"
private const val DUMMY_TITLE = "Xxxxxxxx xxxxx"
private const val DUMMY_ARTIST = "Xxxxxxx"

/**
 * A big card that hides year, title and artist of the song until it is tapped. Hidden, it shows
 * blurred dummy text (the blur works from Android 12, so the real text is never composed
 * while hidden: older phones cannot leak it). A tap shows the song, another tap hides it.
 */
@Composable
internal fun RevealBox(song: SongInfo, revealed: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp)
            .clickable(onClickLabel = if (revealed) "Hide the song" else "Show the song", onClick = onToggle),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 150.dp)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (revealed) {
                // the only place where the real song is used
                SongTexts(artist = song.artist, year = song.year?.toString() ?: "?", title = song.title)
            } else {
                SongTexts(
                    artist = DUMMY_ARTIST, year = DUMMY_YEAR, title = DUMMY_TITLE,
                    modifier = Modifier
                        .blur(16.dp)
                        .alpha(0.45f)
                )
                Text("Tap to reveal", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

internal fun clock(seconds: Float): String {
    val total = seconds.toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * 10 s back, Pause <-> Play, Stop <-> Restart, 10 s forward, then the slider. The two toggles show
 * the NEXT possible action (see [ControlButtons]). Sizes for a 360 dp wide phone: Pause/Play and Stop/Restart
 * are both 72 dp with a 40 dp icon, the two 10 s buttons 56 dp with a 32 dp icon: 256 dp of buttons in 328 dp.
 */
@Composable
private fun PlayerControls(
    playing: Boolean,
    stopped: Boolean,
    songEnded: Boolean,
    busy: Boolean,
    position: Float,
    duration: Float,
    onControl: (String, Float?) -> Unit
) {
    val toggles = ControlButtons.from(playing, stopped, songEnded)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalIconButton(onClick = { onControl("back10", null) }, enabled = !busy, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.Replay10, contentDescription = "10 seconds back", modifier = Modifier.size(32.dp))
        }
        FilledIconButton(
            onClick = { onControl(toggles.pausePlay.wire, null) },
            enabled = !busy,
            modifier = Modifier.size(72.dp)
        ) {
            val pause = toggles.pausePlay == ControlAction.PAUSE
            Icon(
                if (pause) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (pause) "Pause" else "Play",
                modifier = Modifier.size(40.dp)
            )
        }
        FilledIconButton(
            onClick = { onControl(toggles.stopRestart.wire, null) },
            enabled = !busy,
            modifier = Modifier.size(72.dp)
        ) {
            val stop = toggles.stopRestart == ControlAction.STOP
            Icon(
                if (stop) Icons.Filled.Stop else Icons.Filled.Replay,
                contentDescription = if (stop) "Stop" else "Play again",
                modifier = Modifier.size(40.dp)
            )
        }
        FilledTonalIconButton(onClick = { onControl("forward10", null) }, enabled = !busy, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.Forward10, contentDescription = "10 seconds forward", modifier = Modifier.size(32.dp))
        }
    }
    // the slider follows the song, a finger on it takes over until it is let go
    var dragging by remember { mutableStateOf<Float?>(null) }
    val known = duration > 0f
    val shown = (dragging ?: position).coerceIn(0f, if (known) duration else 1f)
    Spacer(modifier = Modifier.height(16.dp))
    Slider(
        value = if (known) shown else 0f,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { onControl("seek", it) }
            dragging = null
        },
        valueRange = 0f..(if (known) duration else 1f),
        enabled = known && !busy
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(clock(shown), style = MaterialTheme.typography.bodyMedium)
        Text(if (known) clock(duration) else "--:--", style = MaterialTheme.typography.bodyMedium)
    }
    }
}

/**
 * The first web address in the text, if it is one of Hitster or YouTube. The text comes
 * from a QR code. Anyone can print one, so an address of another site is not offered.
 */
private fun trustedUrl(text: String): String? {
    val url = Regex("https?://\\S+").find(text)?.value ?: return null
    val host = try {
        java.net.URI(url).host?.lowercase().orEmpty()
    } catch (_: Exception) {
        ""
    }
    val trusted = listOf("hitstergame.com", "hitster.com", "youtube.com", "youtu.be")
        .any { host == it || host.endsWith(".$it") }
    return if (trusted) url else null
}

/** Makes that address tappable inside the text. */
private fun linkify(text: String, linkColor: Color): AnnotatedString {
    val url = trustedUrl(text) ?: return AnnotatedString(text)
    val start = text.indexOf(url)
    return buildAnnotatedString {
        append(text.substring(0, start))
        withLink(
            LinkAnnotation.Url(
                url,
                TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            )
        ) {
            append(url)
        }
        append(text.substring(start + url.length))
    }
}

/** A red box "Error" with a cross, the message in a box of its own below it. */
@Composable
internal fun ErrorBoxes(message: String) {
    StatusCard(title = "Error", isError = true)
    Spacer(modifier = Modifier.height(8.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Text(
            text = linkify(message, MaterialTheme.colorScheme.primary),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
    trustedUrl(message)?.let { url ->
        val context = LocalContext.current
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open the card page")
        }
    }
}

/** A coloured box. Green: the title and, below it, the card set. Red: the title with a cross behind it. */
@Composable
private fun StatusCard(title: String, isError: Boolean, subtitle: String? = null) {
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                Color(0xFFC8E6C9)
            }
        )
    ) {
        val tint = if (isError) contentColor else Color(0xFF1B5E20)
        if (isError) {
            // red box: the word and a cross behind it
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = tint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(imageVector = Icons.Filled.Close, contentDescription = null, tint = tint)
            }
        } else {
            // green box: our text on the first line, the card set on the second, no check mark
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = tint,
                    textAlign = TextAlign.Center
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = tint,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
