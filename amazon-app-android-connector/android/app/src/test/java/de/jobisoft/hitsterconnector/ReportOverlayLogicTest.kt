package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.reader.AdvanceGate
import de.jobisoft.hitsterconnector.reader.ReportRefresh
import de.jobisoft.hitsterconnector.reader.ResultRow
import de.jobisoft.hitsterconnector.reader.ScanFilter
import de.jobisoft.hitsterconnector.reader.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the reader and the quiz do while the overlay of "Report as wrong" is open. */
class ReportOverlayLogicTest {

    // ── the reader: scans ───────────────────────────────────────────────────────

    @Test
    fun aNewCardIsNotTakenWhileTheOverlayIsOpen() {
        assertTrue(ScanFilter.ignore(overlayOpen = true, raw = "B", lastCode = "A", sinceLastReadMs = 60_000))
        assertTrue(ScanFilter.ignore(overlayOpen = true, raw = "B", lastCode = null, sinceLastReadMs = 0))
        assertTrue("also the card that is shown", ScanFilter.ignore(true, "A", "A", 100))
    }

    @Test
    fun withoutTheOverlayANewCardIsTaken() {
        assertFalse(ScanFilter.ignore(overlayOpen = false, raw = "B", lastCode = "A", sinceLastReadMs = 10))
        assertFalse(ScanFilter.ignore(false, "A", null, 0))
    }

    @Test
    fun theCardInFrontOfTheLensIsReadAgainAndAgainAndIgnored() {
        assertTrue(ScanFilter.ignore(false, "A", "A", 0))
        assertTrue(ScanFilter.ignore(false, "A", "A", ScanFilter.DEBOUNCE_MS - 1))
    }

    @Test
    fun theSameCodeAfterAGapIsANewScanAlsoIfItsCardIsOnTheScreen() {
        // the card was taken away and shown again, after a correction: it is loaded again with the new values
        assertFalse(ScanFilter.ignore(false, "A", "A", ScanFilter.DEBOUNCE_MS))
        assertFalse(ScanFilter.ignore(false, "A", "A", 60_000))
    }

    // ── the card on the screen after a report was saved ───────────────────────

    private val file = "hitster-de-aaaa0075.csv"

    private fun report(card: Int, year: Int?, title: String?, artist: String?) =
        ResultRow("t", file, card, Verdict.WRONG, 2008, "Old Title", "Old Artist", year, title, artist)

    @Test
    fun aReportIsForTheCardThatIsShownOnlyIfSetAndNumberAreTheSame() {
        assertTrue(ReportRefresh.isFor(file, 17, report(17, 1984, "T", "A")))
        assertFalse(ReportRefresh.isFor(file, 18, report(17, 1984, "T", "A")))
        assertFalse(ReportRefresh.isFor("hitster-de.csv", 17, report(17, 1984, "T", "A")))
    }

    @Test
    fun theShownValuesBecomeWhatTheUserSaved() {
        assertEquals(Triple(1984, "New Title", "New Artist"), ReportRefresh.values(2008, "Old Title", "Old Artist", report(17, 1984, "New Title", "New Artist")))
        // a value the report does not have stays as it was (a row of the first version has a year only)
        assertEquals(Triple(1984, "Old Title", "Old Artist"), ReportRefresh.values(2008, "Old Title", "Old Artist", report(17, 1984, null, null)))
    }

    // ── the quiz: the next card ────────────────────────────────────────────────

    @Test
    fun theQuizAdvancesAtOnceWithoutAnOverlay() {
        assertTrue(AdvanceGate.advanceNow(overlayOpen = false))
    }

    @Test
    fun theQuizHoldsTheAdvanceBackWhileTheOverlayIsOpen() {
        assertFalse(AdvanceGate.advanceNow(overlayOpen = true))
    }

    @Test
    fun theHeldBackAdvanceRunsWhenTheOverlayIsClosedAndOnlyThen() {
        assertTrue(AdvanceGate.runPending(overlayOpen = false, pending = true))
        assertFalse(AdvanceGate.runPending(overlayOpen = true, pending = true))
        assertFalse("nothing was held back", AdvanceGate.runPending(overlayOpen = false, pending = false))
        assertFalse(AdvanceGate.runPending(overlayOpen = true, pending = false))
    }
}
