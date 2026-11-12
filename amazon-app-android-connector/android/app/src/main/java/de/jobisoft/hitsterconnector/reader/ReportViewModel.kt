package de.jobisoft.hitsterconnector.reader

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jobisoft.hitsterconnector.ui.ReportCard
import javax.inject.Inject

/** Records a "Report as wrong" of the game, through the same store as the reader mode. */
@HiltViewModel
class ReportViewModel @Inject constructor(
    private val store: ReaderStore,
    private val overlay: ReportOverlayState
) : ViewModel() {
    /** The overlay is open or closed: the reader drops scans and the quiz holds its next card back meanwhile. */
    fun setOverlayOpen(open: Boolean) = overlay.set(open)

    fun report(card: ReportCard, edit: Edit, source: String) {
        store.recordWrong(card.setFile, card.number, card.year, card.title, card.artist, edit, source, card.amazonId)
    }
}
