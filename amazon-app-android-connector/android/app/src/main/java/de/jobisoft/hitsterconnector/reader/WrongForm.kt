package de.jobisoft.hitsterconnector.reader

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.jobisoft.hitsterconnector.util.AmazonId

/**
 * The overlay for a card that is not right: a window on top of the screen with a scrim behind it, so the
 * screen below takes no touches and its back button is out of reach. The fields are there at once, in this
 * order: artist, year, title, Amazon ID, prefilled with OUR values so only the wrong one has to be changed, then
 * "Cancel" and "Save" (the same size, Save filled, Cancel outlined) at the bottom. "Save" is on when at
 * least one value differs from ours and all are valid ([ReaderLogic.evaluateEdit]). Shared by the reader
 * mode and the "Report as wrong" button of the game. A new [key] (a new card) resets the fields.
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
    key: Any, ourYear: Int?, ourTitle: String, ourArtist: String, ourAmazonId: String?,
    ourAlbum: String? = null,
    onSave: (Edit) -> Unit, onCancel: () -> Unit
) {
    var artist by remember(key) { mutableStateOf(ourArtist) }
    var yearText by remember(key) { mutableStateOf(ourYear?.toString().orEmpty()) }
    var title by remember(key) { mutableStateOf(ourTitle) }
    var amazonIdText by remember(key) { mutableStateOf(ourAmazonId.orEmpty()) }
    var albumText by remember(key) { mutableStateOf(ourAlbum.orEmpty()) }
    val amazonIdError = ReaderLogic.amazonIdError(amazonIdText)
    val edit = ReaderLogic.evaluateEdit(
        yearText, title, artist, ourYear, ourTitle, ourArtist, ReaderLogic.currentYear(), amazonIdText, ourAmazonId, albumText, ourAlbum
    )

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().padding(16.dp).imePadding()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Report as wrong", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = artist,
                        onValueChange = { artist = it },
                        label = { Text("Artist on the card") },
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Default),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = yearText,
                        onValueChange = { yearText = it.filter { c -> c in '0'..'9' }.take(4) },
                        label = { Text("Year on the card") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title on the card") },
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Default),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = albumText,
                        onValueChange = { albumText = it },
                        label = { Text("Album") },
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Default),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = amazonIdText,
                        onValueChange = { amazonIdText = it },
                        label = { Text("Amazon ID") },
                        isError = amazonIdError != null,
                        supportingText = { Text(amazonIdError ?: "Track ID or link from music.amazon.de, empty = search only") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(56.dp)) { Text("Cancel") }
                    Button(onClick = { edit?.let(onSave) }, enabled = edit != null, modifier = Modifier.weight(1f).height(56.dp)) { Text("Save") }
                }
            }
        }
    }
}
