package de.jobisoft.amster

import de.jobisoft.amster.reader.EditDraft
import de.jobisoft.amster.reader.OcrCard
import de.jobisoft.amster.reader.ReaderLogic
import de.jobisoft.amster.reader.ReaderView
import de.jobisoft.amster.reader.ResultRow
import de.jobisoft.amster.reader.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLogicTest {

    private val file = "hitster-de-aaaa0075.csv"

    private fun row(card: Int, our: Int? = 2000, onCard: Int? = 1984, time: String = "2026-11-09T10:00:00") =
        ResultRow(time, file, card, Verdict.WRONG, our, "Our Title", "Our Artist", onCard, "Our Title", "Our Artist", ourAmazonId = "", cardAmazonId = "", ourAlbum = "", cardAlbum = "")

    private fun wrongRow(year: Int?, title: String?, artist: String?) =
        ResultRow("t", file, 1, Verdict.WRONG, 2000, "Our Title", "Our Artist", year, title, artist, ourAmazonId = "", cardAmazonId = "", ourAlbum = "", cardAlbum = "")

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
            "2026-11-09T10:00:00,$file,265,wrong,2008,Our Title,Our Artist,1984,Our Title,Our Artist,reader,,,,",
            ReaderLogic.resultLine(row(265, 2008, 1984))
        )
    }

    @Test
    fun commasAndQuotesInTitleAndArtistSurviveTheRoundTrip() {
        val r = ResultRow(
            "2026-11-09T10:00:00", file, 126, Verdict.WRONG, 2004,
            "(I've Had) The Time of My Life - From \"Dirty Dancing\", Soundtrack", "Bill Medley, Jennifer Warnes",
            1987, "The Time of My Life, \"Remastered\"", "Medley, Warnes & \"Friends\"",
            ourAmazonId = "", cardAmazonId = "", ourAlbum = "", cardAlbum = ""
        )
        val line = ReaderLogic.resultLine(r)
        assertEquals(15, ReaderLogic.parseCsvLine(line).size)
        assertEquals(r, ReaderLogic.parseResultLine(line))
    }

    @Test
    fun theSourceGoesThereAndBackAndRowsWithoutOneAreFromTheReader() {
        for (source in listOf("reader", "scan", "quiz")) {
            val r = row(265).copy(source = source)
            assertEquals(source, ReaderLogic.parseResultLine(ReaderLogic.resultLine(r))!!.source)
        }
        assertEquals("reader", ReaderLogic.parseResultLine("t,$file,5,wrong,2000,T,A,1999,T2,A2,0")!!.source) // 11 fields, the last one is a flag 0 or 1: reader
        assertEquals("quiz", ReaderLogic.parseResultLine("t,$file,5,wrong,2000,T,A,1999,T2,A2,0,quiz")!!.source) // 12 fields, source in the last one
        assertEquals("scan", ReaderLogic.parseResultLine("t,$file,5,wrong,2000,T,A,1999,T2,A2,scan")!!.source) // 11 fields, source in the last one
        assertEquals("reader", ReaderLogic.parseResultLine("t,$file,5,2000,wrong,1999,0")!!.source) // a row with only seven fields
        assertEquals("reader", ReaderLogic.parseResultLine("t,$file,5,wrong,2000,T,A,1999,T2,A2,0,")!!.source) // empty field
        assertTrue(ReaderLogic.RESULT_HEADER.endsWith(",cardArtist,source,ourAmazonId,cardAmazonId,ourAlbum,cardAlbum"))
    }

    @Test
    fun theAmazonIdsGoThereAndBackAtTheEndOfTheLine() {
        val r = row(103, 2006, 2006).copy(source = "scan", ourAmazonId = "", cardAmazonId = "B006LSV05O")
        val line = ReaderLogic.resultLine(r)
        assertTrue(line.endsWith(",scan,,B006LSV05O,,"))
        assertEquals(r, ReaderLogic.parseResultLine(line))
        val cleared = row(103, 2006, 2006).copy(ourAmazonId = "B006LSV05O", cardAmazonId = "")
        assertEquals(cleared, ReaderLogic.parseResultLine(ReaderLogic.resultLine(cleared)))
    }

    @Test
    fun rowsWithoutTheAmazonIdsAreStillRead() {
        val r11 = ReaderLogic.parseResultLine("t,$file,5,wrong,2000,T,A,1999,T2,A2,scan")!!
        assertNull(r11.ourAmazonId)
        assertNull(r11.cardAmazonId)
        assertEquals("scan", r11.source)
        val r12 = ReaderLogic.parseResultLine("t,$file,5,wrong,2000,T,A,1999,T2,A2,0,quiz")!!
        assertNull(r12.cardAmazonId)
        assertEquals("quiz", r12.source)
        assertEquals("a row without the ids has nothing to say about the id", listOf("year", "title", "artist"), ReaderLogic.changed(r11))
        val r7 = ReaderLogic.parseResultLine("t,$file,5,2000,wrong,1999,0")!!
        assertNull(r7.cardAmazonId)
    }

    @Test
    fun theAlbumsGoThereAndBackAtTheEndOfTheLine() {
        val r = row(103, 2006, 2006).copy(ourAlbum = "Old Album", cardAlbum = "New, \"Album\"")
        val line = ReaderLogic.resultLine(r)
        assertEquals(15, ReaderLogic.parseCsvLine(line).size)
        assertEquals(r, ReaderLogic.parseResultLine(line))
        val cleared = row(103, 2006, 2006).copy(ourAlbum = "Old Album", cardAlbum = "")
        assertEquals(cleared, ReaderLogic.parseResultLine(ReaderLogic.resultLine(cleared)))
    }

    @Test
    fun oldRowsWithoutTheAlbumsAreStillRead() {
        val r13 = ReaderLogic.parseResultLine("t,$file,5,wrong,2000,T,A,1999,T2,A2,scan,B006LSV05O,B00FU4OCDW")!!
        assertEquals("B00FU4OCDW", r13.cardAmazonId)
        assertNull(r13.ourAlbum)
        assertNull(r13.cardAlbum)
        assertEquals(listOf("year", "title", "artist", "amazonId"), ReaderLogic.changed(r13))
        assertNull(ReaderLogic.parseResultLine("t,$file,5,wrong,2000,T,A,1999,T2,A2,scan")!!.cardAlbum)
    }

    @Test
    fun theAlbumIsAChangedFieldWhenTheTwoDiffer() {
        val base = wrongRow(2000, "Our Title", "Our Artist")
        assertEquals(listOf("album"), ReaderLogic.changed(base.copy(ourAlbum = "", cardAlbum = "X")))
        assertEquals(listOf("album"), ReaderLogic.changed(base.copy(ourAlbum = "X", cardAlbum = "")))
        assertEquals(emptyList<String>(), ReaderLogic.changed(base.copy(ourAlbum = "X", cardAlbum = "X")))
        assertEquals(emptyList<String>(), ReaderLogic.changed(base.copy(ourAlbum = null, cardAlbum = null)))
    }

    private fun editAlbum(albumText: String, ourAlbum: String? = null) =
        ReaderLogic.evaluateEdit("2000", "Our Title", "Our Artist", 2000, "Our Title", "Our Artist", 2026, "", null, albumText, ourAlbum)

    @Test
    fun theAlbumCanBeTheOnlyChange() {
        assertNull("nothing changed", editAlbum("", null))
        assertNull("the same album", editAlbum("Same Album", "Same Album"))
        assertNull("only spaces differ", editAlbum("  Same   Album ", "Same Album"))
        assertEquals("New Album", editAlbum("  New\n Album ", null)!!.album)
        assertEquals("replacing", "B", editAlbum("B", "A")!!.album)
        assertEquals("clearing the album is a change", "", editAlbum("", "A")!!.album)
        assertEquals("the other fields keep an empty album", "", edit("1984", "Our Title", "Our Artist")!!.album)
    }

    @Test
    fun rowsWithSevenFieldsAreRead() {
        val old = ReaderLogic.parseResultLine("2026-11-08T09:00:00,$file,265,2008,wrong,1984,1")!!
        assertEquals(Verdict.WRONG, old.verdict)
        assertEquals(2008, old.ourYear)
        assertEquals(1984, old.cardYear)
        assertEquals("", old.ourTitle)
        assertNull(old.cardTitle)
        assertEquals(listOf("year"), ReaderLogic.changed(old)) // year-only
    }

    @Test
    fun rowsWithTheVerdictMatchOrSkippedAreNotReports() {
        assertNull(ReaderLogic.parseResultLine("t,$file,5,match,2000,T,A,,,,0,reader"))
        assertNull(ReaderLogic.parseResultLine("t,$file,5,skipped,2000,T,A,,,,0,reader"))
        assertNull(ReaderLogic.parseResultLine("t,$file,5,2000,match,,0")) // a row with only seven fields
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
    fun theAmazonIdIsAChangedFieldWhenTheTwoDiffer() {
        val base = wrongRow(2000, "Our Title", "Our Artist")
        assertEquals(listOf("amazonId"), ReaderLogic.changed(base.copy(ourAmazonId = "", cardAmazonId = "B006LSV05O")))
        assertEquals(listOf("amazonId"), ReaderLogic.changed(base.copy(ourAmazonId = "B006LSV05O", cardAmazonId = "")))
        assertEquals(listOf("amazonId"), ReaderLogic.changed(base.copy(ourAmazonId = "B006LSV05O", cardAmazonId = "B00FU4OCDW")))
        assertEquals(emptyList<String>(), ReaderLogic.changed(base.copy(ourAmazonId = "B006LSV05O", cardAmazonId = "B006LSV05O")))
        assertEquals(listOf("year", "amazonId"), ReaderLogic.changed(wrongRow(1984, "Our Title", "Our Artist").copy(cardAmazonId = "B006LSV05O")))
    }

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

    private fun editId(idText: String, ourId: String? = null) =
        ReaderLogic.evaluateEdit("2000", "Our Title", "Our Artist", 2000, "Our Title", "Our Artist", 2026, idText, ourId)

    @Test
    fun theAmazonIdCanBeTheOnlyChangeAndIsTakenFromALinkToo() {
        assertNull("nothing changed", editId("", null))
        assertNull("the same id", editId("B006LSV05O", "B006LSV05O"))
        assertNull("the same id as a link", editId("https://music.amazon.de/tracks/B006LSV05O?do=play", "B006LSV05O"))
        assertEquals("B006LSV05O", editId("b006lsv05o", null)!!.amazonId)
        assertEquals("B006LSV05O", editId("https://music.amazon.de/tracks/B006LSV05O?do=play", null)!!.amazonId)
        assertEquals("clearing the id is a change", "", editId("", "B006LSV05O")!!.amazonId)
        assertEquals("B00FU4OCDW", editId("B00FU4OCDW", "B006LSV05O")!!.amazonId)
    }

    @Test
    fun saveStaysOffForTextThatIsNoAmazonId() {
        assertNull(editId("not an id"))
        assertNull(editId("B006LSV05"))
        assertNull(editId("https://music.amazon.de/albums/B006LSUZVY"))
        assertEquals("Not an Amazon track ID", ReaderLogic.amazonIdError("nope"))
        assertNull(ReaderLogic.amazonIdError(""))
        assertNull(ReaderLogic.amazonIdError("B006LSV05O"))
        assertEquals("the id is saved together with the other values", 1984,
            ReaderLogic.evaluateEdit("1984", "Our Title", "Our Artist", 2000, "Our Title", "Our Artist", 2026, "B006LSV05O", null)!!.year)
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

    @Test
    fun `the reader shows the camera until something is on the screen`() {
        assertEquals(ReaderView.CAMERA, ReaderLogic.view(card = false, unknown = false, error = false))
        assertEquals(ReaderView.DATA, ReaderLogic.view(card = true, unknown = false, error = false))
        assertEquals(ReaderView.DATA, ReaderLogic.view(card = false, unknown = true, error = false))
        assertEquals(ReaderView.DATA, ReaderLogic.view(card = false, unknown = false, error = true))
    }

    @Test
    fun `the OCR result goes into artist, year and title and keeps album and Amazon ID`() {
        val draft = EditDraft.of("Edit Card", "hitster-de-aaaa0075.csv", 82, "Ich", 2007, "Vom", "B0ABC12345", "Album")
        val read = draft.withOcr(OcrCard("Ich + Ich", 2007, "Vom selben Stern"))
        assertEquals("Ich + Ich", read.artist)
        assertEquals("2007", read.yearText)
        assertEquals("Vom selben Stern", read.title)
        assertEquals("Album", read.albumText)
        assertEquals("B0ABC12345", read.amazonIdText)
        assertTrue(read.ocrUsed)
        assertFalse(draft.ocrUsed)
    }

    @Test
    fun `values that the OCR read the same as ours leave Save off`() {
        val draft = EditDraft.of("Edit Card", "hitster-de-aaaa0075.csv", 82, "Ich + Ich", 2007, "Vom selben Stern", null, null)
        val read = draft.withOcr(OcrCard("Ich + Ich", 2007, "Vom selben Stern"))
        val edit = ReaderLogic.evaluateEdit(read.yearText, read.title, read.artist, 2007, "Vom selben Stern", "Ich + Ich", 2030, read.amazonIdText, null, read.albumText, null)
        assertNull(edit)
    }

    @Test
    fun `values that the OCR read differently turn Save on`() {
        val draft = EditDraft.of("Edit Card", "hitster-de-aaaa0075.csv", 82, "Ich + Ich", 2007, "Vom selben Stern", null, null)
        val read = draft.withOcr(OcrCard("Ich + Ich", 2008, "Vom selben Stern"))
        val edit = ReaderLogic.evaluateEdit(read.yearText, read.title, read.artist, 2007, "Vom selben Stern", "Ich + Ich", 2030, read.amazonIdText, null, read.albumText, null)
        assertEquals(2008, edit!!.year)
    }
}
