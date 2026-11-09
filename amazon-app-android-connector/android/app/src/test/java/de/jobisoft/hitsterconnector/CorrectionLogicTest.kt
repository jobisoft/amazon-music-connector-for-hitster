package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.data.repository.CSVSong
import de.jobisoft.hitsterconnector.util.CardOverride
import de.jobisoft.hitsterconnector.util.CorrectionLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionLogicTest {

    private val set = "hitster-de-aaaa0075.csv"

    private fun override(card: Int, year: Int?, title: String = "New Title", artist: String = "New Artist", time: String = "t1") =
        CardOverride(set, card, year, title, artist, time, "reader")

    private val song = CSVSong(17, "Männer", "Herbert Grönemeyer", 2008, "https://example.org/17")

    // ── apply ───────────────────────────────────────────────────────────────

    @Test
    fun aCorrectionReplacesYearTitleAndArtistAndMarksTheSong() {
        val fixed = CorrectionLogic.apply(song, override(17, 1984))
        assertEquals(1984, fixed.year)
        assertEquals("New Title", fixed.title)
        assertEquals("New Artist", fixed.artist)
        assertTrue(fixed.corrected)
        assertEquals("the rest of the song stays", "https://example.org/17", fixed.url)
        assertEquals(17, fixed.index)
        assertFalse("the original is not marked", song.corrected)
    }

    @Test
    fun aCorrectionWithoutAYearKeepsTheYear() {
        assertEquals(2008, CorrectionLogic.apply(song, override(17, null)).year)
    }

    // ── last wins ───────────────────────────────────────────────────────────

    @Test
    fun theLastCorrectionOfACardWinsInTheFirstPlace() {
        val rows = listOf(override(17, 1990, time = "t1"), override(5, 2001, time = "t2"), override(17, 1984, time = "t3"))
        val last = CorrectionLogic.lastWins(rows)
        assertEquals(2, last.size)
        assertEquals(1984, last["$set#17"]!!.year)
        assertEquals(listOf("$set#17", "$set#5"), last.keys.toList()) // order of the first correction
    }

    @Test
    fun correctionsOfOtherSetsDoNotMix() {
        val other = override(17, 1111).copy(setFile = "hitster-de.csv")
        assertEquals(2, CorrectionLogic.lastWins(listOf(override(17, 1984), other)).size)
    }

    // ── the file and the export ───────────────────────────────────────────────

    @Test
    fun aLineGoesThereAndBackAlsoWithCommasAndQuotes() {
        for (o in listOf(
            override(17, 1984),
            override(126, 1987, "The Time of My Life, \"Remastered\"", "Medley, Warnes & \"Friends\""),
            override(3, null, "T", "A")
        )) {
            assertEquals(o, CorrectionLogic.parseLine(CorrectionLogic.line(o)))
        }
        assertEquals("$set,17,1984,New Title,New Artist,t1,reader", CorrectionLogic.line(override(17, 1984)))
    }

    @Test
    fun headerAndBrokenLinesAreNoCorrections() {
        assertNull(CorrectionLogic.parseLine(CorrectionLogic.HEADER))
        assertNull(CorrectionLogic.parseLine(""))
        assertNull(CorrectionLogic.parseLine("a,b"))
        assertNull(CorrectionLogic.parseLine("$set,x,1984,T,A,t,reader"))
        assertNull(CorrectionLogic.parseLine(",5,1984,T,A,t,reader"))
    }

    @Test
    fun theExportHasTheHeaderAndTheLatestCorrectionPerCard() {
        val content = CorrectionLogic.exportContent(listOf(override(17, 1990, time = "t1"), override(17, 1984, time = "t2"), override(5, 2001)))
        val lines = content.trimEnd().lines()
        assertEquals("set,card,year,title,artist,time,source", lines[0])
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("$set,17,1984,"))
        assertTrue(lines[2].startsWith("$set,5,2001,"))
        assertTrue(content.endsWith("\n"))
    }

    @Test
    fun anEmptyExportHasOnlyTheHeader() {
        assertEquals("set,card,year,title,artist,time,source\n", CorrectionLogic.exportContent(emptyList()))
    }

    @Test
    fun theFileNameHasTheDate() {
        assertEquals("hitster-corrections-2026-11-09.csv", CorrectionLogic.exportFileName("2026-11-09"))
    }
}
