package de.jobisoft.hitsterconnector.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the overlay of "Report as wrong" is open. The screen behind it keeps running (the song plays,
 * the reader's camera scans), but nothing may replace the card under the overlay: the reader ignores
 * scans, and the quiz holds back its next card until the overlay is closed. See [ScanFilter], [AdvanceGate].
 */
@Singleton
class ReportOverlayState @Inject constructor() {
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()

    fun set(open: Boolean) {
        _open.value = open
    }
}

/** What the reader does with a code from the camera. */
object ScanFilter {
    /**
     * The same code again within this time of the last read is the same card in front of the lens: the camera
     * reads it again and again while it is there. If it was not seen for this long (the card was taken away
     * and shown again) it is a new scan and is processed, also if its card is on the screen: a correction
     * made meanwhile is then loaded again.
     */
    const val DEBOUNCE_MS = 4000L

    /**
     * True if the code is not to be processed: the overlay of "Report as wrong" is open (a new card must not
     * replace the card under it, the scan is dropped and has to be repeated after the overlay is closed), or
     * the same code was read a moment ago ([sinceLastReadMs] is the time since the last read of it).
     */
    fun ignore(overlayOpen: Boolean, raw: String, lastCode: String?, sinceLastReadMs: Long): Boolean =
        overlayOpen || (raw == lastCode && sinceLastReadMs < DEBOUNCE_MS)
}

/** The values of a card after a report of the user was saved: the card on the screen is refreshed in place. */
object ReportRefresh {
    /** Is the report for the card that is shown? */
    fun isFor(setFile: String, card: Int, row: ResultRow): Boolean = row.setFile == setFile && row.card == card

    /** The values of a card after a report. [amazonId] is null for no id. */
    data class Values(val year: Int?, val title: String, val artist: String, val amazonId: String?)

    /** The values after the report: what the user entered (a value the report does not have stays as it was). */
    fun values(year: Int?, title: String, artist: String, amazonId: String?, row: ResultRow): Values = Values(
        row.cardYear ?: year, row.cardTitle ?: title, row.cardArtist ?: artist,
        when {
            row.cardAmazonId == null -> amazonId
            row.cardAmazonId.isBlank() -> null
            else -> row.cardAmazonId
        }
    )
}

/** The quiz and the overlay: the next card waits while a report is being written. */
object AdvanceGate {
    /** The song of the quiz ended: advance now (true), or hold the advance back until the overlay closes (false). */
    fun advanceNow(overlayOpen: Boolean): Boolean = !overlayOpen

    /** The overlay changed: true if the held-back advance is to run now. */
    fun runPending(overlayOpen: Boolean, pending: Boolean): Boolean = pending && !overlayOpen
}
