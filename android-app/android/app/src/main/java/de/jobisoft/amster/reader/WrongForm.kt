package de.jobisoft.amster.reader

import de.jobisoft.amster.ui.theme.AmsterTheme
import de.jobisoft.amster.ui.components.SecondaryButton
import de.jobisoft.amster.ui.components.PrimaryButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.jobisoft.amster.BuildConfig
import de.jobisoft.amster.util.AmazonId

/**
 * The overlay for a card that is not right: a window on top of the screen with a scrim behind it, so the
 * screen below takes no touches and its back button is out of reach. The fields are there at once, in this
 * order: artist, year, title, album, Amazon ID, prefilled with OUR values so only the wrong one has to be changed, then the button
 * "Read via OCR" (not in a build without OCR, it opens the OCR page, the values it reads go into the fields of the draft), then
 * "Cancel" and "Save" (the same size, Save filled, Cancel outlined) at the bottom. "Save" is on when at
 * least one value differs from ours and all are valid ([ReaderLogic.evaluateEdit]). Shared by the reader
 * mode and the game (a long press on the shown song). The text of the fields is the [draft] of the [EditDraftStore], it survives the OCR page.
 *
 * Title, artist and album are multiline fields (2 to 4 lines, they grow with the text, Enter is a line break). The
 * line breaks become one space on saving ([ReaderLogic.collapse]). The year stays a single-line number field.
 * An empty album means no album. The Amazon ID is a single-line field that takes a track ID or a pasted link of Amazon Music, empty means
 * "search only" ([AmazonId.fromInput]). Text that is no ID shows an error under the field and "Save" stays off.
 *
 * "Cancel" and the system Back close only the overlay and discard the edits (the dialog window takes the
 * back press, the screen below does not see it, the song goes on). A tap on the scrim does nothing, so an
 * edit is not lost by a stray touch. With the keyboard open the fields scroll and the buttons stay in view
 * (the window resizes, and the area with the fields scrolls).
 */
@Composable
fun WrongDialog(
    draft: EditDraft,
    onDraft: (EditDraft) -> Unit,
    ourYear: Int?, ourTitle: String, ourArtist: String, ourAmazonId: String?,
    ourAlbum: String? = null,
    /** Opens the OCR page. Null in a build without OCR: the button is not there. */
    onReadOcr: (() -> Unit)? = null,
    onSave: (Edit) -> Unit, onCancel: () -> Unit
) {
    val amazonIdError = ReaderLogic.amazonIdError(draft.amazonIdText)
    val edit = ReaderLogic.evaluateEdit(
        draft.yearText, draft.title, draft.artist, ourYear, ourTitle, ourArtist, ReaderLogic.currentYear(), draft.amazonIdText, ourAmazonId, draft.albumText, ourAlbum
    )

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, AmsterTheme.extras.glassBorder),
            modifier = Modifier.fillMaxWidth().padding(16.dp).imePadding()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(draft.dialogTitle, style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = draft.artist,
                        onValueChange = { onDraft(draft.copy(artist = it)) },
                        label = { Text("Artist on the card") },
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Default),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draft.yearText,
                        onValueChange = { onDraft(draft.copy(yearText = it.filter { c -> c in '0'..'9' }.take(4))) },
                        label = { Text("Year on the card") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draft.title,
                        onValueChange = { onDraft(draft.copy(title = it)) },
                        label = { Text("Title on the card") },
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Default),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draft.albumText,
                        onValueChange = { onDraft(draft.copy(albumText = it)) },
                        label = { Text("Album") },
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Default),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draft.amazonIdText,
                        onValueChange = { onDraft(draft.copy(amazonIdText = it)) },
                        label = { Text("Amazon ID") },
                        isError = amazonIdError != null,
                        supportingText = { Text(amazonIdError ?: "Track ID or link from music.amazon.de, empty = search only") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (onReadOcr != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SecondaryButton(text = "Read via OCR", onClick = onReadOcr, modifier = Modifier.fillMaxWidth())
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton(text = "Cancel", onClick = onCancel, modifier = Modifier.weight(1f))
                    PrimaryButton(text = "Save", onClick = { edit?.let(onSave) }, enabled = edit != null, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** The OCR button of the overlay: only in a build with OCR (`--no-ocr` leaves it out). */
fun ocrAction(onReadOcr: () -> Unit): (() -> Unit)? = if (BuildConfig.OCR_ENABLED) onReadOcr else null
