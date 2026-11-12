package de.jobisoft.amster.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.jobisoft.amster.reader.EditDraft
import de.jobisoft.amster.reader.ReportViewModel
import de.jobisoft.amster.reader.WrongDialog
import de.jobisoft.amster.reader.ocrAction

/**
 * The function that opens the edit overlay for [card] (a long press on the shown song), the same in the game and in the reader.
 * Null while there is no card.
 */
@Composable
fun rememberOpenEdit(card: ReportCard?): (() -> Unit)? {
    if (LocalSampleMode.current) return null
    val viewModel: ReportViewModel = hiltViewModel()
    return if (card == null) null else ({ viewModel.open(card) })
}

/**
 * The edit overlay ([WrongDialog], titled "Edit Card") of the card on the screen, shown while it is open and the card is revealed
 * (the overlay shows the answer), in the reader always ([revealed] = true). "Save" reports the edit and closes it, Cancel only closes it. While it is open
 * the screen below is blocked, the card under it is not replaced (see [de.jobisoft.amster.reader.ReportOverlayState]). "Read via OCR" in it
 * ([onReadOcr], null where the mode has no OCR: the quiz plays random cards, the card is not at hand) opens the OCR page, the overlay is shown
 * again when the page is closed, with the values it read in the fields.
 */
@Composable
fun ReportWrongOverlay(card: ReportCard?, revealed: Boolean, source: String, onReadOcr: (() -> Unit)?) {
    if (card == null || LocalSampleMode.current) return
    val viewModel: ReportViewModel = hiltViewModel()
    val draft by viewModel.draft.collectAsState()
    LaunchedEffect(revealed) { if (!revealed) viewModel.closeFor(card) }
    val shown = draft
    if (revealed && shown != null && shown.key == EditDraft.key(card.setFile, card.number)) {
        WrongDialog(
            draft = shown, onDraft = viewModel::update,
            ourYear = card.year, ourTitle = card.title, ourArtist = card.artist, ourAmazonId = card.amazonId, ourAlbum = card.album,
            onReadOcr = onReadOcr?.let { ocrAction(it) },
            onSave = { edit ->
                viewModel.report(card, edit, source)
                viewModel.close()
            },
            // only the overlay closes
            onCancel = viewModel::close
        )
    }
}
