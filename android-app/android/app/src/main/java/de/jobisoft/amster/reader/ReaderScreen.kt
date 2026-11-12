package de.jobisoft.amster.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.jobisoft.amster.ui.ReportCard
import de.jobisoft.amster.ui.ReportWrongOverlay
import de.jobisoft.amster.ui.components.BottomButtonScreen
import de.jobisoft.amster.ui.components.GlassCardFull
import de.jobisoft.amster.ui.components.PrimaryButton
import de.jobisoft.amster.ui.components.QRCodeScanner
import de.jobisoft.amster.ui.components.ScanCameraView
import de.jobisoft.amster.ui.components.ScreenScaffold
import de.jobisoft.amster.ui.components.SecondaryButton
import de.jobisoft.amster.ui.components.SongTexts
import de.jobisoft.amster.ui.components.rememberCameraPermission
import de.jobisoft.amster.ui.components.rememberScanCountdown
import de.jobisoft.amster.ui.rememberOpenEdit
import de.jobisoft.amster.ui.screens.ErrorBoxes
import de.jobisoft.amster.ui.screens.KeepScreenOn
import de.jobisoft.amster.ui.screens.StatusCard
import de.jobisoft.amster.ui.screens.UnknownCardView
import de.jobisoft.amster.ui.screens.displayingLine
import de.jobisoft.amster.ui.theme.AmsterTheme

/**
 * Reader mode: two views. The camera view scans a card (with the countdown and the way back to the main screen, like Play Mode).
 * The data view shows the card, a long press on the song box opens the edit overlay (which can read the back of the card), "Scan next card"
 * brings the camera back. An unknown card offers "Add Card", the same overlay with empty fields.
 */
@Composable
fun ReaderScreen(
    onNavigateBack: () -> Unit,
    onReadOcr: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel()
) {
    val ui by viewModel.state.collectAsState()
    val draft by viewModel.draft.collectAsState()
    KeepScreenOn(true)

    val permission = rememberCameraPermission()
    val view = ReaderLogic.view(card = ui.card != null, unknown = ui.unknown != null, error = ui.error != null)

    ScreenScaffold(title = "Reader Mode") {
        when (view) {
            ReaderView.CAMERA -> {
                val secondsLeft = rememberScanCountdown(permission.granted) { onNavigateBack() }
                ScanCameraView(
                    permission = permission,
                    secondsLeft = secondsLeft,
                    camera = {
                        QRCodeScanner(
                            onQRCodeScanned = { viewModel.onCode(it) },
                            modifier = Modifier.fillMaxSize(),
                            isActive = true
                        )
                    }
                )
            }

            ReaderView.DATA -> ReaderDataView(
                card = ui.card, unknown = ui.unknown, error = ui.error,
                onScanNext = viewModel::scanNext, onAdd = viewModel::addUnknown, onReadOcr = onReadOcr
            )
        }
    }
    // the overlay that asks for the name of an unknown card set; scans are dropped while it is open
    ui.askEdition?.let { pending ->
        EditionDialog(key = pending, onContinue = viewModel::confirmEdition, onCancel = viewModel::cancelEdition)
    }
    // the overlay to add an unknown card: the same fields as "Edit Card", all empty
    val adding = draft
    if (ui.manualAdd != null && adding != null) {
        WrongDialog(
            draft = adding, onDraft = viewModel::updateDraft,
            ourYear = null, ourTitle = "", ourArtist = "", ourAmazonId = null, ourAlbum = null,
            onReadOcr = ocrAction(onReadOcr),
            onSave = viewModel::saveManual, onCancel = viewModel::cancelManual
        )
    }
}

/**
 * The data view of the reader, stateless: the card (set name box on top, song box, hints), an unknown card with "Add Card" and "Cancel", or
 * an error, and "Scan next card" at the bottom (not for an unknown card, it has its own buttons).
 */
@Composable
internal fun ReaderDataView(
    card: ReaderCard?,
    unknown: UnknownCard?,
    error: String?,
    onScanNext: () -> Unit,
    onAdd: () -> Unit,
    onReadOcr: () -> Unit
) {
    BottomButtonScreen(
        // the set name sits at the top of the screen, apart from the centered block
        top = card?.let { c -> { StatusCard(title = displayingLine(c.setName), isError = false) } },
        // an unknown card has its own buttons (Add Card, Cancel), the others go on with "Scan next card"
        bottomButton = if (unknown != null) null else {
            {
                PrimaryButton(text = "Scan next card", onClick = onScanNext, modifier = Modifier.fillMaxWidth())
            }
        }
    ) {
        when {
            card != null -> CardResult(card, onReadOcr)
            unknown != null -> UnknownCardPanel(unknown, onAdd = onAdd, onCancel = onScanNext)
            error != null -> ErrorBoxes(error)
        }
    }
}

/** An unknown card: the same plain text as in Play Mode ([UnknownCardView]) and the buttons to add it or to go back to the camera. */
@Composable
private fun UnknownCardPanel(unknown: UnknownCard, onAdd: () -> Unit, onCancel: () -> Unit) {
    val lines = if (unknown.setKnown) listOf(unknown.setName) else listOf(unknown.setName, "This card set is not in the app.")
    UnknownCardView("Unknown card ${unknown.pending.number}", lines)
    Spacer(modifier = Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton(text = "Add Card", onClick = onAdd)
        SecondaryButton(text = "Cancel", onClick = onCancel)
    }
}

/**
 * The scanned card like the result of Play Mode and Quiz Mode: the song in a glass box (artist, year bigger, title, "(card#N)"),
 * a long press on it opens the edit overlay. Under the box the hint, and the note that the card has a modification of this phone.
 * The set name box is at the top of the screen ([BottomButtonScreen]).
 */
@Composable
private fun CardResult(card: ReaderCard, onReadOcr: () -> Unit) {
    val text = MaterialTheme.colorScheme.onSurface
    val reportCard = ReportCard(card.serial, card.setFile, card.number, card.year, card.title, card.artist, card.amazonId, card.album)
    val openEdit = rememberOpenEdit(reportCard)

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassCardFull(
            modifier = Modifier.heightIn(min = 180.dp),
            onLongClick = { openEdit?.invoke() },
            onLongClickLabel = "Edit the song",
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SongTexts(card.artist, card.year?.toString() ?: "?", card.title, color = text, yearBrush = AmsterTheme.extras.accentBrush) {
                Text(ReaderLogic.cardNumberText(card.number), style = MaterialTheme.typography.bodySmall, color = text, textAlign = TextAlign.Center)
            }
        }
        HintLine("Long press to edit")
        if (card.corrected) HintLine("Already modified on this phone")
    }
    // the reader reads cards to check them: the values are shown at once, so the overlay can be opened at once, no reveal step
    ReportWrongOverlay(reportCard, revealed = true, source = ReportSource.READER, onReadOcr = onReadOcr)
}

/** A small line of text under a box, always the same height. */
@Composable
internal fun HintLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.heightIn(min = 24.dp)
    )
}
