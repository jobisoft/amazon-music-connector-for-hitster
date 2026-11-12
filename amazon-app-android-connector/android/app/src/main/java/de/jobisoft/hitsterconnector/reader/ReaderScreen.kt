package de.jobisoft.hitsterconnector.reader

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.jobisoft.hitsterconnector.ui.ExportShare
import de.jobisoft.hitsterconnector.ui.ReportCard
import de.jobisoft.hitsterconnector.ui.ReportWrongSection
import de.jobisoft.hitsterconnector.ui.components.BackButton
import de.jobisoft.hitsterconnector.ui.components.QRCodeScanner
import de.jobisoft.hitsterconnector.ui.components.SongTexts
import de.jobisoft.hitsterconnector.ui.screens.ErrorBoxes
import de.jobisoft.hitsterconnector.ui.screens.KeepScreenOn

/** Reader mode: scan a card and see its values at once. If something is wrong: "Report as wrong". If not: scan the next card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onNavigateBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel()
) {
    val ui by viewModel.state.collectAsState()
    val context = LocalContext.current
    KeepScreenOn(true)

    val scroll = rememberScrollState()

    // the user may come back from the settings (notification access) or have installed the app
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshAmazon() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reader Mode") },
                actions = {
                    TextButton(onClick = {
                        val corrections = viewModel.corrections()
                        if (corrections.isEmpty()) {
                            viewModel.say("Nothing to export yet. Use \"Report as wrong\" on a card first.")
                        } else if (!ExportShare.share(context, corrections)) {
                            viewModel.say("The export did not work.")
                        }
                    }) { Text("Export") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            CountersBar(scanned = ui.scanned, updated = ui.counters.updated)
            Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                if (hasPermission) {
                    QRCodeScanner(
                        onQRCodeScanned = { viewModel.onCode(it) },
                        modifier = Modifier.fillMaxSize(),
                        isActive = true
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Camera permission is required to scan QR codes")
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Grant Permission") }
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f).verticalScroll(scroll).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val card = ui.card
                when {
                    card != null -> CardResult(card, ui, onSend = viewModel::sendToAmazon)
                    ui.error != null -> ErrorBoxes(ui.error!!)
                    else -> Text(
                        ui.message ?: "Scan a card",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Corrections stay on this phone. \"Export\" sends them as a file. " + ExportShare.WHERE_TO_SEND,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            BackButton(
                onClick = {
                    viewModel.leave() // stops a song sent from here and ends the end guard
                    onNavigateBack()
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

/** "Scanned n, updated u": the cards scanned in this visit, and the cards with a correction on this phone. */
@Composable
private fun CountersBar(scanned: Int, updated: Int) {
    Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("Scanned $scanned, updated $updated", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun greenColor(): Color = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)

/**
 * The scanned card: the four items one under the other, centered: artist, year (bigger, bold), title,
 * "(card#N)". The box has a thin green border. The set name is a small caption above the box, a card with a correction of this phone says so.
 */
@Composable
private fun CardResult(card: ReaderCard, ui: ReaderUiState, onSend: () -> Unit) {
    val accent = greenColor()
    val text = MaterialTheme.colorScheme.onSurface

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(card.setName, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        if (card.corrected) {
            Text("corrected on this phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, accent),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SongTexts(card.artist, card.year?.toString() ?: "?", card.title, color = text) {
                    Text(ReaderLogic.cardNumberText(card.number), style = MaterialTheme.typography.bodySmall, color = text, textAlign = TextAlign.Center)
                }
            }
        }
        // the reader reads cards to check them: the values are shown at once, so the button is there at once, no reveal step
        ReportWrongSection(
            ReportCard(card.serial, card.setFile, card.number, card.year, card.title, card.artist, card.amazonId),
            revealed = true,
            source = ReportSource.READER
        )
        // plays the card through the Amazon Music app, the user stays here and the card stays
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onSend,
            enabled = SendToAmazon.enabled(true, ui.amazonProblem, ui.send),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("Send to Amazon Music app") }
        ui.amazonProblem?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        val line = SendToAmazon.statusLine(ui.send, ui.songEnded, ui.versionNote, ui.playStatus)
        if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        if (ui.send == SendStatus.FAILED && ui.sendError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            ErrorBoxes(ui.sendError)
        }
    }
}
