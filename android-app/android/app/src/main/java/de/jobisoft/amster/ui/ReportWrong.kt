package de.jobisoft.amster.ui

import de.jobisoft.amster.ui.components.SecondaryButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.jobisoft.amster.reader.ReportViewModel
import de.jobisoft.amster.reader.WrongDialog

/**
 * "Update manually" under the info box, in every build and the same in the game and in the reader: a button
 * that opens the overlay ([WrongDialog]) with the fields at once. In the game it exists only while the card is
 * revealed (the overlay shows the answer), in the reader always ([revealed] = true). After "Save" the overlay
 * closes and the button keeps its name ([label], "Update manually"). A new card resets everything.
 * While the overlay is open the screen below is blocked, the card under it is not replaced (see
 * [de.jobisoft.amster.reader.ReportOverlayState]). With [onOcr] (only the reader, only in a build with OCR) a second button
 * "Update via OCR" stands next to it: it reads the back of the card again and replaces year, title and artist.
 */
@Composable
fun ReportWrongSection(card: ReportCard?, revealed: Boolean, source: String, label: String = "Update manually", note: String? = null, onOcr: (() -> Unit)? = null) {
    if (card == null) return
    val viewModel: ReportViewModel = hiltViewModel()
    var open by remember(card.serial) { mutableStateOf(false) }
    LaunchedEffect(revealed) { if (!revealed) open = false }
    // the others (reader scans, the quiz) must know while the overlay is up
    val overlayOpen = open && revealed
    DisposableEffect(overlayOpen) {
        viewModel.setOverlayOpen(overlayOpen)
        onDispose { viewModel.setOverlayOpen(false) }
    }
    if (!revealed) return

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(text = label, onClick = { open = true }, modifier = Modifier.weight(1f))
            if (onOcr != null) {
                SecondaryButton(text = "Update via OCR", onClick = onOcr, modifier = Modifier.weight(1f))
            }
        }
    }
    if (open) {
        WrongDialog(
            title = label, note = note, key = card.serial, ourYear = card.year, ourTitle = card.title, ourArtist = card.artist, ourAmazonId = card.amazonId, ourAlbum = card.album,
            onSave = { edit ->
                viewModel.report(card, edit, source)
                open = false
            },
            // only the overlay closes, the button stays
            onCancel = { open = false }
        )
    }
}
