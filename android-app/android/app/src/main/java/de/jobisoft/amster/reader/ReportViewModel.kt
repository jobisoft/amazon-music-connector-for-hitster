package de.jobisoft.amster.reader

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jobisoft.amster.ui.ReportCard
import javax.inject.Inject

/**
 * The edit overlay of a card of the game or of the reader: opens it ([open]), keeps the fields ([draft], the [EditDraftStore] survives
 * the OCR page) and records a saved edit through the same store as the reader mode.
 */
@HiltViewModel
class ReportViewModel @Inject constructor(
    private val store: ReaderStore,
    private val drafts: EditDraftStore
) : ViewModel() {
    val draft = drafts.draft

    /** The overlay for this card with its values in the fields. */
    fun open(card: ReportCard) {
        drafts.open(EditDraft.of("Edit Card", card.setFile, card.number, card.artist, card.year, card.title, card.amazonId, card.album))
    }

    fun update(draft: EditDraft) = drafts.update(draft)

    fun close() = drafts.close()

    /** Closes the overlay if it is the one of this card (the song is hidden again, for example). */
    fun closeFor(card: ReportCard) {
        if (drafts.draft.value?.key == EditDraft.key(card.setFile, card.number)) drafts.close()
    }

    fun report(card: ReportCard, edit: Edit, source: String) {
        store.recordWrong(card.setFile, card.number, card.year, card.title, card.artist, edit, source, card.amazonId, card.album)
    }

    /** The screen of this view model is gone for good (not only covered by the OCR page): no overlay stays open. */
    override fun onCleared() {
        drafts.close()
        super.onCleared()
    }
}
