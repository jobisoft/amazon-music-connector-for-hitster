package de.jobisoft.hitsterconnector.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.jobisoft.hitsterconnector.reader.ReportViewModel
import de.jobisoft.hitsterconnector.reader.WrongDialog

/**
 * "Report as wrong" under the info box, in every build and the same in the game and in the reader: a button
 * that opens the overlay ([WrongDialog]) with the fields at once. In the game it exists only while the card is
 * revealed (the overlay shows the answer), in the reader always ([revealed] = true). After "Save" the overlay
 * closes and the button reads "Reported, Edit again" and opens the overlay again. A new card resets everything.
 * While the overlay is open the screen below is blocked, the card under it is not replaced (see
 * [de.jobisoft.hitsterconnector.reader.ReportOverlayState]).
 */
@Composable
fun ReportWrongSection(card: ReportCard?, revealed: Boolean, source: String) {
    if (card == null) return
    val viewModel: ReportViewModel = hiltViewModel()
    var open by remember(card.serial) { mutableStateOf(false) }
    var reported by remember(card.serial) { mutableStateOf(false) }
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
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(if (reported) "Reported, Edit again" else "Report as wrong")
        }
    }
    if (open) {
        WrongDialog(
            key = card.serial, ourYear = card.year, ourTitle = card.title, ourArtist = card.artist,
            onSave = { edit ->
                viewModel.report(card, edit, source)
                reported = true
                open = false
            },
            // only the overlay closes, the button stays
            onCancel = { open = false }
        )
    }
}
