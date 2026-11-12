package de.jobisoft.amster.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.jobisoft.amster.ui.components.QRCodeScanner
import de.jobisoft.amster.ui.components.ScanCameraView
import de.jobisoft.amster.ui.components.ScreenScaffold
import de.jobisoft.amster.ui.components.rememberCameraPermission
import de.jobisoft.amster.ui.components.rememberScanCountdown
import de.jobisoft.amster.ui.viewmodel.GameViewModel
import de.jobisoft.amster.ui.viewmodel.SendState
import de.jobisoft.amster.util.QRCodeParser

@Composable
fun ScannerScreen(
    onNavigateBack: () -> Unit,
    onReadOcr: () -> Unit,
    viewModel: GameViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Keep the screen on while a card is being handled
    KeepScreenOn(uiState.sendState != SendState.IDLE)
    // the status bar of some phones shows the title of the song, hide it while a card is on the screen
    HideStatusBar(uiState.sendState != SendState.IDLE)

    val permission = rememberCameraPermission()

    // The camera timeout: it starts when the camera turns on (the screen opens, "Scan next card", Cancel). A scan ends it.
    // It runs out: back to the main screen, the volume is back (leave).
    val cameraOn = uiState.sendState == SendState.IDLE && permission.granted
    val secondsLeft = rememberScanCountdown(cameraOn) {
        viewModel.leave()
        onNavigateBack()
    }

    ScreenScaffold(title = "Play Mode") {
        Box(modifier = Modifier.fillMaxSize()) {
            if (uiState.sendState != SendState.IDLE) {
                ResultView(
                    state = uiState,
                    nextLabel = "Scan next card",
                    reportSource = "scan",
                    onControl = viewModel::control,
                    onToggleReveal = viewModel::toggleReveal,
                    onNext = viewModel::nextCard,
                    onCancel = viewModel::cancelSending,
                    onReadOcr = onReadOcr
                )
            } else {
                ScanCameraView(permission = permission, secondsLeft = secondsLeft, camera = {
                    QRCodeScanner(
                        onQRCodeScanned = { qrCode ->
                            QRCodeParser.parseHitsterUrl(qrCode)?.let {
                                viewModel.onQRCodeScanned(it)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        isActive = true
                    )
                })
            }
        }
    }
}
