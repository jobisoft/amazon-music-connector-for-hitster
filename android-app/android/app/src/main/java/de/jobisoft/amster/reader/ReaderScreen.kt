package de.jobisoft.amster.reader

import de.jobisoft.amster.ui.theme.AmsterTheme
import de.jobisoft.amster.ui.components.StatusChip
import de.jobisoft.amster.ui.components.GlassCardFull
import de.jobisoft.amster.ui.components.SecondaryButton
import de.jobisoft.amster.ui.components.PrimaryButton
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.jobisoft.amster.ui.ReportCard
import de.jobisoft.amster.ui.ReportWrongSection
import de.jobisoft.amster.ui.components.ScreenScaffold
import de.jobisoft.amster.ui.components.QRCodeScanner
import de.jobisoft.amster.ui.components.SquareScanWindow
import de.jobisoft.amster.ui.components.SongTexts
import de.jobisoft.amster.ui.screens.ErrorBoxes
import de.jobisoft.amster.ui.screens.KeepScreenOn

/** Reader mode: scan a card and see its values at once. If something is wrong: "Update manually" or "Update via OCR". If not: scan the next card. */
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

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    ScreenScaffold(title = "Reader Mode") {
        Column(modifier = Modifier.fillMaxSize()) {
            SquareScanWindow(modifier = Modifier.fillMaxWidth()) {
                if (hasPermission) {
                    val analysing = ui.ocr?.phase == OcrPhase.ANALYSING
                    QRCodeScanner(
                        onQRCodeScanned = { viewModel.onCode(it) },
                        modifier = Modifier.fillMaxSize(),
                        // the camera is off while the analysis reads the collected pictures
                        isActive = !analysing,
                        // in the OCR mode the camera also hands over a picture of the frame now and then
                        frameRequests = ui.ocr != null && !analysing,
                        onFrame = viewModel::onOcrFrame
                    )
                    if (analysing) AnalysisCover(ui.analysisFrame)
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Camera permission is required to scan QR codes")
                        PrimaryButton(text = "Grant Permission", onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) })
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val card = ui.card
                when {
                    ui.ocr != null -> OcrBanner(ui.ocr!!, onCancel = viewModel::cancelOcr)
                    ui.unknown != null -> UnknownCardPanel(ui.unknown!!, ui.ocrAvailable, onAdd = viewModel::addUnknown)
                    card != null -> CardResult(card, onOcr = if (ui.ocrAvailable) viewModel::startOcrForShownCard else null)
                    ui.error != null -> ErrorBoxes(ui.error!!)
                    else -> Text(
                        ui.message ?: "Scan a card",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }
        }
    }
    // the overlay that asks for the name of an unknown card set; scans are dropped while it is open
    ui.askEdition?.let { pending ->
        EditionDialog(key = pending, onContinue = viewModel::confirmEdition, onCancel = viewModel::cancelEdition)
    }
    // the overlay to add an unknown card by hand: the same fields as "Update manually", all empty
    ui.manualAdd?.let { pending ->
        WrongDialog(
            title = "Add manually", key = pending, ourYear = null, ourTitle = "", ourArtist = "", ourAmazonId = null, ourAlbum = null,
            note = ReaderLogic.UPDATES_NOTE,
            onSave = viewModel::saveManual, onCancel = viewModel::cancelManual
        )
    }
}

/** An unknown card: the screen says so and offers the two ways to add it. */
@Composable
private fun UnknownCardPanel(unknown: UnknownCard, ocrAvailable: Boolean, onAdd: (AddMode) -> Unit) {
    GlassCardFull(modifier = Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Unknown card ${unknown.pending.number}", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            unknown.setName,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
        )
        if (!unknown.setKnown) {
            Text(
                "This card set is not in the app.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        SecondaryButton(text = "Add manually", onClick = { onAdd(AddMode.MANUAL) }, modifier = Modifier.fillMaxWidth())
        if (ocrAvailable) {
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryButton(text = "Add via OCR", onClick = { onAdd(AddMode.OCR) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Over the camera window while the analysis runs: the last good picture blurred (where the system can), a dark layer and a spinner. */
@Composable
private fun AnalysisCover(frame: android.graphics.Bitmap?) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(24.dp)
            )
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

/** The OCR mode: what the user is to do, and a button to leave it. */
@Composable
private fun OcrBanner(ocr: OcrState, onCancel: () -> Unit) {
    GlassCardFull(modifier = Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Scan song information (OCR)", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        val hint = when (ocr.phase) {
            OcrPhase.WAITING -> "Show the other side of the card."
            OcrPhase.ANALYSING -> "Analysing the card ..."
            OcrPhase.FAILED -> "Could not read the card. Try again."
        }
        Text(hint, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(4.dp))
        // the progress: the sharp pictures with the whole card that are collected, then the readings of the analysis
        val progress = if (ocr.phase == OcrPhase.ANALYSING) "Reading ${ocr.analysed}/${ocr.planned}" else "Photos ${ocr.captured}/${OcrCapture.NEEDED}"
        Text(progress, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(4.dp))
        Text(ReaderLogic.UPDATES_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(12.dp))
        SecondaryButton(text = "Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The scanned card: the four items one under the other, centered: artist, year (bigger, bold), title,
 * "(card#N)". The box has a thin green border. The set name is a small caption above the box, a card with a correction of this phone says so.
 */
@Composable
private fun CardResult(card: ReaderCard, onOcr: (() -> Unit)? = null) {
    val extras = AmsterTheme.extras
    val text = MaterialTheme.colorScheme.onSurface

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(card.setName, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        if (card.corrected) {
            Text("updated on this phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(4.dp))
        GlassCardFull(
            tint = extras.successContainer,
            borderColor = extras.success.copy(alpha = 0.5f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SongTexts(card.artist, card.year?.toString() ?: "?", card.title, color = text, yearBrush = extras.accentBrush) {
                    Text(ReaderLogic.cardNumberText(card.number), style = MaterialTheme.typography.bodySmall, color = text, textAlign = TextAlign.Center)
                }
            }
        }
        // the reader reads cards to check them: the values are shown at once, so the button is there at once, no reveal step
        ReportWrongSection(
            ReportCard(card.serial, card.setFile, card.number, card.year, card.title, card.artist, card.amazonId, card.album),
            revealed = true,
            source = ReportSource.READER,
            note = ReaderLogic.UPDATES_NOTE,
            onOcr = onOcr
        )
    }
}
