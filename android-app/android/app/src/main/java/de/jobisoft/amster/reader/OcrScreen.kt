package de.jobisoft.amster.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.graphics.Bitmap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.jobisoft.amster.ui.components.QRCodeScanner
import de.jobisoft.amster.ui.components.CameraPermission
import de.jobisoft.amster.ui.components.ScanCameraView
import de.jobisoft.amster.ui.components.ScreenScaffold
import de.jobisoft.amster.ui.components.rememberCameraPermission
import de.jobisoft.amster.ui.components.rememberScanCountdown
import de.jobisoft.amster.ui.screens.HideStatusBar
import de.jobisoft.amster.ui.screens.KeepScreenOn

/**
 * The OCR page of the edit overlay: the camera view with the countdown (the same time as every camera view, it restarts after an
 * analysis without a clear result and stands still while the analysis runs) and under the window what the user is to do. A clear result
 * goes into the fields of the overlay. The page closes with a result, when the time is up and on the back button, and
 * the overlay is shown again.
 */
@Composable
fun OcrScreen(
    onNavigateBack: () -> Unit,
    viewModel: OcrViewModel = hiltViewModel()
) {
    val ui by viewModel.state.collectAsState()
    KeepScreenOn(true)
    // the status bar of some phones shows the title of the song of the game: it stays hidden here too
    HideStatusBar(true)

    val permission = rememberCameraPermission()
    val ocr = ui.ocr
    val analysing = ocr?.phase == OcrPhase.ANALYSING

    LaunchedEffect(ui.done) { if (ui.done) onNavigateBack() }
    val secondsLeft = rememberScanCountdown(active = ocr != null && !analysing && permission.granted) { viewModel.cancel() }

    ScreenScaffold(title = "Scan card") {
        OcrPageContent(
            permission = permission,
            secondsLeft = secondsLeft,
            ocr = ocr,
            analysisFrame = ui.analysisFrame,
            camera = {
                QRCodeScanner(
                    onQRCodeScanned = { },
                    modifier = Modifier.fillMaxSize(),
                    // the camera is off while the analysis reads the collected pictures
                    isActive = !analysing,
                    frameRequests = ocr != null && !analysing,
                    onFrame = viewModel::onFrame
                )
            }
        )
    }
}

/**
 * The body of the OCR page, stateless: the camera view with the countdown (not shown while the analysis runs), the analysis cover over the
 * window and the information under it. The [camera] is the picture source.
 */
@Composable
internal fun OcrPageContent(
    permission: CameraPermission,
    secondsLeft: Int,
    ocr: OcrState?,
    analysisFrame: Bitmap?,
    camera: @Composable BoxScope.() -> Unit
) {
    val analysing = ocr?.phase == OcrPhase.ANALYSING
    ScanCameraView(
        permission = permission,
        secondsLeft = if (analysing) null else secondsLeft,
        camera = {
            camera()
            if (analysing) AnalysisCover(analysisFrame)
        },
        below = { if (ocr != null) OcrInfo(ocr) }
    )
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

/**
 * What the user is to do and how it goes, plain text under the window (no box, no button: the time ending or the back button leave the page).
 * Two numbers: the pictures that were read, and the good ones (sharp, the whole card on them) of the ones that are needed.
 */
@Composable
private fun OcrInfo(ocr: OcrState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Read song information from card (OCR)", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        val hint = when (ocr.phase) {
            OcrPhase.WAITING -> "Show the back of the card."
            OcrPhase.ANALYSING -> "Analysing the card ..."
            OcrPhase.FAILED -> "Could not read the card. Try again."
        }
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Total OCR scans: ${ocr.scans}", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Text("Good OCR scans: ${ocr.captured}/${OcrCapture.NEEDED}", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
