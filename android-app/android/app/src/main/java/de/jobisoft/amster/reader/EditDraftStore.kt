package de.jobisoft.amster.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What is in the edit overlay: the card it is for and the text of its fields. The overlay lives in the screen that shows the card,
 * and that screen is left while the OCR page is open, so the fields are kept here and not in the overlay.
 * [key] says which card the draft is for, [dialogTitle] is "Edit Card" or "Add Card", [ocrUsed] is set when the fields were filled by the OCR.
 */
data class EditDraft(
    val key: String,
    val dialogTitle: String,
    val setFile: String,
    val number: Int,
    val artist: String,
    val yearText: String,
    val title: String,
    val albumText: String,
    val amazonIdText: String,
    val ocrUsed: Boolean = false
) {
    /** What the OCR read goes into artist, year and title. The album and the Amazon ID stay as they are. */
    fun withOcr(card: OcrCard): EditDraft = copy(artist = card.artist, yearText = card.year.toString(), title = card.title, ocrUsed = true)

    companion object {
        fun key(setFile: String, number: Int) = "$setFile#$number"

        fun of(
            dialogTitle: String, setFile: String, number: Int,
            artist: String, year: Int?, title: String, amazonId: String?, album: String?
        ) = EditDraft(
            key(setFile, number), dialogTitle, setFile, number,
            artist, year?.toString().orEmpty(), title, album.orEmpty(), amazonId.orEmpty()
        )
    }
}

/**
 * The one edit overlay of the app: Play Mode, Quiz Mode and Reader Mode all use it. [draft] is null when no overlay is open.
 * An open draft also tells [ReportOverlayState] that an overlay is up (the reader drops scans, the quiz holds its next card back), also
 * while the OCR page is on top of the screen.
 */
@Singleton
class EditDraftStore @Inject constructor(private val overlay: ReportOverlayState) {
    private val _draft = MutableStateFlow<EditDraft?>(null)
    val draft: StateFlow<EditDraft?> = _draft.asStateFlow()

    fun open(draft: EditDraft) {
        _draft.value = draft
        overlay.set(true)
    }

    fun update(draft: EditDraft) {
        if (_draft.value?.key == draft.key) _draft.value = draft
    }

    /** The OCR page read a card: its values go into the fields of the open overlay. */
    fun applyOcr(card: OcrCard) {
        _draft.value = _draft.value?.withOcr(card)
    }

    fun close() {
        _draft.value = null
        overlay.set(false)
    }
}
