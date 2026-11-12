package de.jobisoft.amster.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.jobisoft.amster.ui.components.ScreenScaffold
import de.jobisoft.amster.ui.viewmodel.QuizViewModel
import de.jobisoft.amster.ui.viewmodel.SendState

/** The quiz: no camera, a random card plays at once, the same result view as the scanner. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizScreen(
    files: List<String>,
    onNavigateBack: () -> Unit,
    viewModel: QuizViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // the quiz screen has a card at all times, keep the screen on
    KeepScreenOn(true)
    // the status bar of some phones shows the title of the song, hide it as long as the quiz is on the screen
    HideStatusBar(true)
    LaunchedEffect(files) { viewModel.start(files) }

    ScreenScaffold(title = "Quiz Mode") {
        Box(modifier = Modifier.fillMaxSize()) {
            ResultView(
                // before the first card is on its way the screen shows "sending", not nothing
                state = if (uiState.sendState == SendState.IDLE) uiState.copy(sendState = SendState.SENDING) else uiState,
                nextLabel = "Play next random card",
                reportSource = "quiz",
                onControl = viewModel::control,
                onToggleReveal = viewModel::toggleReveal,
                onNext = viewModel::playNextRandom,
                onCancel = {
                    viewModel.cancelAndLeave() // like Back: stops the song, leaves to the picker
                    onNavigateBack()
                },
                // the quiz plays random cards, the card is not at hand: no OCR
                onReadOcr = null
            )
        }
    }
}
