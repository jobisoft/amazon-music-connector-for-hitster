package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.reader.ReaderLogic
import de.jobisoft.hitsterconnector.reader.ResultRow
import de.jobisoft.hitsterconnector.reader.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLogicTest {

    private val file = "hitster-de-aaaa0075.csv"

    private fun row(card: Int, our: Int? = 2000, onCard: Int? = 1984, time: String = "2026-11-09T10:00:00") =
        ResultRow(time, file, card, Verdict.WRONG, our, "Our Title", "Our Artist", onCard, "Our Title", "Our Artist")

    private fun wrongRow(year: Int?, title: String?, artist: String?) =
        ResultRow("t", file, 1, Verdict.WRONG, 2000, "Our Title", "Our Artist", year, title, artist)

    // ── the year on the card ───────────────────────────────────────────────────

    @Test
    fun aYearIsFourDigitsBetween1900AndThisYear() {
        assertEquals(1984, ReaderLogic.validateYear("1984", 2026))
        assertEquals(1984, ReaderLogic.validateYear(" 1984 ", 2026))
        assertEquals(1900, ReaderLogic.validateYear("1900", 2026))
        assertEquals(2026, ReaderLogic.validateYear("2026", 2026))
        assertNull("next year", ReaderLogic.validateYear("2027", 2026))
        assertNull(ReaderLogic.validateYear("1899", 2026))
        assertNull(ReaderLogic.validateYear("198", 2026))
        assertNull(ReaderLogic.validateYear("19840", 2026))
        assertNull(ReaderLogic.validateYear("", 2026))
        assertNull(ReaderLogic.validateYear("19a4", 2026))
        assertNull(ReaderLogic.validateYear("-198", 2026))
        assertNull(ReaderLogic.validateYear("١٩٨٤", 2026)) // not ASCII digits
    }

    // ── the results file: only reports of wrong cards ─────────────────────────

    @Test
    fun aResultLineGoesThereAndBack() {
        for (r in listOf(row(265, 2008, 1984), row(234, 2014, 2014), row(7, null, 1999))) {
            assertEquals(r, ReaderLogic.parseResultLine(ReaderLogic.resultLine(r)))
        }
        assertEquals(
            "2026-11-09T10:00:00,$file,265,wrong,2008,Our Title,Our Artist,1984,Our Title,Our Artist,reader",
            ReaderLogic.resultLine(row(265, 2008, 1984))
        )
    }

    @Test
    fun commasAndQuotesInTitleAndArtistSurviveTheRoundTrip() {
        val r = ResultRow(
            "2026-11-09T10:00:00", file, 126, Verdict.WRONG, 2004,
            "(I've Had) The Time of My Life - From \"Dirty Dancing\", Soundtrack", "Bill Medley, Jennifer Warnes",
            1987, "The Time of My Life, \"Remastered\"", "Medley, Warnes & \"Friends\""
        )
        val line = ReaderLogic.resultLine(r)
        assertEquals(11, ReaderLogic.parseCsvLine(line).size)
        assertEquals(r, ReaderLogic.parseResultLine(line))
    }

    @Test
    fun theSourceGoesThereAndBackAndOldRowsAreFromTheReader() {
        for (source in listOf("reader", "scan", "quiz")) {
            val r = row(265).copy(source = source)
            assertEquals(source, ReaderLogic.parseResultLine(ReaderLogic.resultLine(r))!!.source)
        }
        assertEquals("reader", ReaderLogic.parseResultLine("t,$file,5,wrong,2000,T,A,1999,T2,A2,0")!!.source) // 11 fields, old: suspect instead of source
        assertEquals("quiz", ReaderLogic.parseResultLine("t,$file,5,wrong,2000,T,A,1999,T2,A2,0,quiz")!!.source) // 12 fields, old
        assertEquals("scan", ReaderLogic.parseResultLine("t,$file,5,wrong,2000,T,A,1999,T2,A2,scan")!!.source) // 11 fields, current
        assertEquals("reader", ReaderLogic.parseResultLine("t,$file,5,2000,wrong,1999,0")!!.source) // the first version
        assertEquals("reader", ReaderLogic.parseResultLine("t,$file,5,wrong,2000,T,A,1999,T2,A2,0,")!!.source) // empty field
        assertTrue(ReaderLogic.RESULT_HEADER.endsWith(",cardArtist,source"))
    }

    @Test
    fun rowsOfTheFirstVersionAreStillRead() {
        val old = ReaderLogic.parseResultLine("2026-11-08T09:00:00,$file,265,2008,wrong,1984,1")!!
        assertEquals(Verdict.WRONG, old.verdict)
        assertEquals(2008, old.ourYear)
        assertEquals(1984, old.cardYear)
        assertEquals("", old.ourTitle)
        assertNull(old.cardTitle)
        assertEquals(listOf("year"), ReaderLogic.changed(old)) // year-only
    }

    @Test
    fun rowsOfMatchAndSkippedFromOlderVersionsAreNotReportsAnyMore() {
        assertNull(ReaderLogic.parseResultLine("t,$file,5,match,2000,T,A,,,,0,reader"))
        assertNull(ReaderLogic.parseResultLine("t,$file,5,skipped,2000,T,A,,,,0,reader"))
        assertNull(ReaderLogic.parseResultLine("t,$file,5,2000,match,,0")) // the first version
        assertNull("there is no verdict match", Verdict.fromWire("match"))
        assertEquals(listOf("wrong"), Verdict.entries.map { it.wire })
    }

    @Test
    fun headerAndBrokenLinesAreNotResults() {
        assertNull(ReaderLogic.parseResultLine(ReaderLogic.RESULT_HEADER))
        assertNull(ReaderLogic.parseResultLine(""))
        assertNull(ReaderLogic.parseResultLine("a,b"))
        assertNull(ReaderLogic.parseResultLine("t,$file,x,wrong,2000,T,A,1999,T,A,0"))
        assertNull(ReaderLogic.parseResultLine("t,$file,5,maybe,2000,T,A,1999,T,A,0"))
    }

    @Test
    fun csvFieldsAreQuotedOnlyWhenNeeded() {
        assertEquals("plain", ReaderLogic.csvField("plain"))
        assertEquals("\"a,b\"", ReaderLogic.csvField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", ReaderLogic.csvField("say \"hi\""))
        assertEquals(listOf("a", "b,c", "d\"e"), ReaderLogic.parseCsvLine("a,\"b,c\",\"d\"\"e\""))
    }

    // ── what changed, and when Save is on ──────────────────────────────────────

    @Test
    fun theChangedFieldsAreTheOnesThatDiffer() {
        assertEquals(listOf("year"), ReaderLogic.changed(wrongRow(1984, "Our Title", "Our Artist")))
        assertEquals(listOf("title"), ReaderLogic.changed(wrongRow(2000, "Other Title", "Our Artist")))
        assertEquals(listOf("artist"), ReaderLogic.changed(wrongRow(2000, "Our Title", "Other Artist")))
        assertEquals(listOf("year", "title", "artist"), ReaderLogic.changed(wrongRow(1984, "T", "A")))
        assertEquals("trailing spaces do not count", emptyList<String>(), ReaderLogic.changed(wrongRow(2000, "Our Title ", " Our Artist")))
    }

    private fun edit(year: String, title: String, artist: String) =
        ReaderLogic.evaluateEdit(year, title, artist, 2000, "Our Title", "Our Artist", 2026)

    @Test
    fun saveNeedsAtLeastOneValueThatDiffersFromOurs() {
        assertNull("nothing changed", edit("2000", "Our Title", "Our Artist"))
        assertNull("only spaces differ", edit("2000", "  Our Title ", "Our   Artist"))
        assertEquals(1984, edit("1984", "Our Title", "Our Artist")!!.year)
        assertEquals("New Title", edit("2000", "New Title", "Our Artist")!!.title)
        assertEquals("New Artist", edit("2000", "Our Title", "New Artist")!!.artist)
    }

    @Test
    fun saveNeedsAllFieldsValid() {
        assertNull("year too short", edit("198", "Other", "Our Artist"))
        assertNull("year in the future", edit("2027", "Other", "Our Artist"))
        assertNull("year before 1900", edit("1899", "Other", "Our Artist"))
        assertNull("empty title", edit("1984", "", "Our Artist"))
        assertNull("blank title", edit("1984", "   ", "Our Artist"))
        assertNull("empty artist", edit("1984", "Our Title", ""))
        val e = edit("1984", "  Two\nLines  ", "Artist ")!! // trimmed and flattened to one line
        assertEquals("Two Lines", e.title)
        assertEquals("Artist", e.artist)
    }

    @Test
    fun saveWorksWhenOurYearIsMissing() {
        assertEquals(1984, ReaderLogic.evaluateEdit("1984", "T", "A", null, "T", "A", 2026)!!.year)
        assertNull(ReaderLogic.evaluateEdit("", "T", "A", null, "T", "A", 2026))
    }

    // ── the counter: the cards with a report ───────────────────────────────────

    @Test
    fun theCounterIsTheNumberOfCardsWithAReportAndTheLastReportPerCardCounts() {
        assertEquals(0, ReaderLogic.counters(emptyList()).updated)
        val rows = listOf(row(265, time = "t1"), row(234, time = "t2"), row(265, 2008, 1990, time = "t3"))
        assertEquals(2, ReaderLogic.counters(rows).updated) // two cards, 265 twice
        assertEquals(1990, ReaderLogic.latestAnswers(rows).getValue("$file#265").cardYear)
    }

    @Test
    fun theCardNumberHasTheShapeOfThePrintedCard() {
        assertEquals("(card#300)", ReaderLogic.cardNumberText(300))
        assertEquals("(card#7)", ReaderLogic.cardNumberText(7))
        assertFalse(ReaderLogic.cardNumberText(7).contains(" "))
    }

    @Test
    fun lineBreaksBecomeOneSpace() {
        assertEquals("Amy Winehouse", ReaderLogic.collapse("  Amy\n Winehouse \r\n"))
        assertEquals("a b c", ReaderLogic.collapse("a\t\tb\n\nc"))
        val edit = ReaderLogic.evaluateEdit("2006", "Re\nhab", "Amy\nWinehouse", 2006, "Rehab", "Amy Winehouse", 2030)
        assertEquals("Re hab", edit!!.title)
        assertEquals("Amy Winehouse", edit.artist)
    }
}
