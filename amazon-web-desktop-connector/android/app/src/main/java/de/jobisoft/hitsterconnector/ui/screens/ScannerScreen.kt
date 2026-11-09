package de.jobisoft.hitsterconnector.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.jobisoft.hitsterconnector.ui.components.BackButton
import de.jobisoft.hitsterconnector.ui.components.QRCodeScanner
import de.jobisoft.hitsterconnector.ui.viewmodel.GameViewModel
import de.jobisoft.hitsterconnector.ui.viewmodel.SendState
import de.jobisoft.hitsterconnector.util.QRCodeParser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onNavigateBack: () -> Unit,
    viewModel: GameViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Keep the screen on while a card is being handled
    val keepScreenOn = uiState.sendState != SendState.IDLE
    DisposableEffect(keepScreenOn) {
        val window = (context as? Activity)?.window
        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Scan QR Code") })
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.sendState != SendState.IDLE) {
                ResultView(
                    sendState = uiState.sendState,
                    message = uiState.message,
                    warning = uiState.warning,
                    playing = uiState.playing,
                    position = uiState.position,
                    duration = uiState.duration,
                    controlBusy = uiState.controlBusy,
                    controlError = uiState.controlError,
                    onControl = viewModel::control,
                    onScanNext = viewModel::resetToScanner,
                    onBack = {
                        viewModel.resetToScanner()
                        onNavigateBack()
                    }
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (hasPermission) {
                            QRCodeScanner(
                                onQRCodeScanned = { qrCode ->
                                    QRCodeParser.parseHitsterUrl(qrCode)?.let {
                                        viewModel.onQRCodeScanned(it)
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                                isActive = true
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text("Camera permission is required to scan QR codes")
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                                    Text("Grant Permission")
                                }
                            }
                        }
                    }

                    BackButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}

/**
 * Shows what happened to the scanned card. The song itself stays hidden,
 * it would spoil the game.
 */
@Composable
private fun ResultView(
    sendState: SendState,
    message: String?,
    warning: String?,
    playing: Boolean,
    position: Float,
    duration: Float,
    controlBusy: Boolean,
    controlError: String?,
    onControl: (String, Float?) -> Unit,
    onScanNext: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (sendState) {
            SendState.SENDING -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Sending to your computer ...", style = MaterialTheme.typography.titleMedium)
            }

            SendState.SUCCESS -> {
                // The error stays visible, even though a test song plays in its place
                if (warning != null) {
                    ErrorBoxes(warning)
                    Spacer(modifier = Modifier.height(12.dp))
                }
                // gone once the song is stopped, by the button or because it reached its end
                if (playing) {
                    StatusCard(title = "Playing", isError = false)
                    Spacer(modifier = Modifier.height(20.dp))
                }
                PlayerControls(
                    playing = playing,
                    busy = controlBusy,
                    position = position,
                    duration = duration,
                    onControl = onControl
                )
                if (controlError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(controlError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }

            SendState.FAILED -> ErrorBoxes(message ?: "Something went wrong.")

            SendState.IDLE -> Unit
        }

        Spacer(modifier = Modifier.height(24.dp))

        val finished = sendState == SendState.SUCCESS || sendState == SendState.FAILED
        if (finished) {
            Button(
                onClick = onScanNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Scan next card")
            }
            Spacer(modifier = Modifier.height(8.dp))
            BackButton(onClick = onBack)
        }
    }
}

private fun clock(seconds: Float): String {
    val total = seconds.toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/** 10 seconds back, stop (once stopped: play again from the start offset), 10 seconds forward. */
@Composable
private fun PlayerControls(
    playing: Boolean,
    busy: Boolean,
    position: Float,
    duration: Float,
    onControl: (String, Float?) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalIconButton(onClick = { onControl("back10", null) }, enabled = !busy, modifier = Modifier.size(64.dp)) {
            Icon(Icons.Filled.Replay10, contentDescription = "10 seconds back", modifier = Modifier.size(36.dp))
        }
        FilledIconButton(
            onClick = { onControl(if (playing) "stop" else "restart", null) },
            enabled = !busy,
            modifier = Modifier.size(80.dp)
        ) {
            Icon(
                if (playing) Icons.Filled.Stop else Icons.Filled.Replay,
                contentDescription = if (playing) "Stop" else "Play again",
                modifier = Modifier.size(44.dp)
            )
        }
        FilledTonalIconButton(onClick = { onControl("forward10", null) }, enabled = !busy, modifier = Modifier.size(64.dp)) {
            Icon(Icons.Filled.Forward10, contentDescription = "10 seconds forward", modifier = Modifier.size(36.dp))
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
private fun ErrorBoxes(message: String) {
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

/** A coloured box with the title and a check mark (green) or a cross (red) behind it. */
@Composable
private fun StatusCard(title: String, isError: Boolean) {
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tint = if (isError) contentColor else Color(0xFF1B5E20)
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = tint
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = if (isError) Icons.Filled.Close else Icons.Filled.Check,
                contentDescription = null,
                tint = tint
            )
        }
    }
}
