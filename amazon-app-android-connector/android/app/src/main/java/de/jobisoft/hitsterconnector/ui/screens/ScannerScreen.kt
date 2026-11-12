package de.jobisoft.hitsterconnector.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    KeepScreenOn(uiState.sendState != SendState.IDLE)
    // the status bar of some phones shows the title of the song, hide it while a card is on the screen
    HideStatusBar(uiState.sendState != SendState.IDLE)

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
            TopAppBar(title = { Text("Play Mode") })
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.sendState != SendState.IDLE) {
                ResultView(
                    state = uiState,
                    nextLabel = "Scan next card",
                    reportSource = "scan",
                    onControl = viewModel::control,
                    onToggleReveal = viewModel::toggleReveal,
                    onNext = viewModel::nextCard,
                    onCancel = viewModel::cancelSending,
                    onBack = {
                        viewModel.leave() // stops the song, the navigation does not wait for it
                        viewModel.reset()
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
                        onClick = {
                            viewModel.leave()
                            onNavigateBack()
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}
