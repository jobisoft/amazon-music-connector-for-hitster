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

    private val song = CSVSong(17, "Männer", "Herbert Grönemeyer", 2008, "B006LSV05O")

    // ── apply ───────────────────────────────────────────────────────────────

    @Test
    fun aCorrectionReplacesYearTitleAndArtistAndMarksTheSong() {
        val fixed = CorrectionLogic.apply(song, override(17, 1984))
        assertEquals(1984, fixed.year)
        assertEquals("New Title", fixed.title)
        assertEquals("New Artist", fixed.artist)
        assertTrue(fixed.corrected)
        assertEquals("a correction without the id column keeps the id", "B006LSV05O", fixed.amazonId)
        assertEquals(17, fixed.index)
        assertFalse("the original is not marked", song.corrected)
    }

    @Test
    fun theAmazonIdOfACorrectionReplacesClearsOrKeepsTheIdOfTheSong() {
        assertEquals("B00FU4OCDW", CorrectionLogic.apply(song, override(17, 1984).copy(amazonId = "B00FU4OCDW")).amazonId)
        assertNull("an empty id clears it", CorrectionLogic.apply(song, override(17, 1984).copy(amazonId = "")).amazonId)
        assertEquals("no id column keeps it", "B006LSV05O", CorrectionLogic.apply(song, override(17, 1984).copy(amazonId = null)).amazonId)
        assertEquals("B006LSV05O", CorrectionLogic.apply(song.copy(amazonId = null), override(17, 1984).copy(amazonId = "B006LSV05O")).amazonId)
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
    fun theAmazonIdColumnGoesThereAndBackAndRowsWithoutItHaveNone() {
        val withId = override(103, 2006).copy(amazonId = "B006LSV05O")
        val cleared = override(103, 2006).copy(amazonId = "")
        assertEquals("$set,103,2006,New Title,New Artist,t1,reader,B006LSV05O", CorrectionLogic.line(withId))
        assertEquals("$set,103,2006,New Title,New Artist,t1,reader,", CorrectionLogic.line(cleared))
        assertEquals(withId, CorrectionLogic.parseLine(CorrectionLogic.line(withId)))
        assertEquals(cleared, CorrectionLogic.parseLine(CorrectionLogic.line(cleared)))
        // a row without the last column: seven fields, the id of the song stays
        assertNull(CorrectionLogic.parseLine("$set,17,1984,New Title,New Artist,t1,reader")!!.amazonId)
        // a text in the column that is no id is not taken
        assertNull(CorrectionLogic.parseLine("$set,17,1984,New Title,New Artist,t1,reader,nonsense")!!.amazonId)
        assertEquals("a link in the column gives the id", "B006LSV05O",
            CorrectionLogic.parseLine("$set,17,1984,T,A,t1,reader,https://music.amazon.de/tracks/B006LSV05O")!!.amazonId)
    }

    @Test
    fun theAlbumColumnGoesThereAndBackWithAndWithoutAnId() {
        val withBoth = override(103, 2006).copy(amazonId = "B006LSV05O", album = "The Album")
        val noId = override(103, 2006).copy(album = "The Album")
        val cleared = override(103, 2006).copy(amazonId = "", album = "")
        val clearedAlbum = override(103, 2006).copy(album = "")
        assertEquals("$set,103,2006,New Title,New Artist,t1,reader,B006LSV05O,The Album", CorrectionLogic.line(withBoth))
        assertEquals("$set,103,2006,New Title,New Artist,t1,reader,-,The Album", CorrectionLogic.line(noId))
        assertEquals("$set,103,2006,New Title,New Artist,t1,reader,,", CorrectionLogic.line(cleared))
        for (o in listOf(withBoth, noId, cleared, clearedAlbum)) assertEquals(o, CorrectionLogic.parseLine(CorrectionLogic.line(o)))
        assertNull("a row with an album but no id keeps the id", CorrectionLogic.parseLine(CorrectionLogic.line(noId))!!.amazonId)
        // rows of older files have no album column: the album of the song stays
        assertNull(CorrectionLogic.parseLine("$set,17,1984,T,A,t1,reader")!!.album)
        assertNull(CorrectionLogic.parseLine("$set,17,1984,T,A,t1,reader,B006LSV05O")!!.album)
        assertEquals("$set,17,1984,New Title,New Artist,t1,reader", CorrectionLogic.line(override(17, 1984)))
    }

    @Test
    fun theAlbumOfACorrectionReplacesClearsOrKeepsTheAlbumOfTheSong() {
        val withAlbum = song.copy(album = "Old Album")
        assertEquals("Old Album", CorrectionLogic.apply(withAlbum, override(17, 1984)).album)
        assertEquals("a row with old columns only", "Old Album", CorrectionLogic.apply(withAlbum, override(17, 1984).copy(amazonId = "B00FU4OCDW")).album)
        assertEquals("New Album", CorrectionLogic.apply(withAlbum, override(17, 1984).copy(album = "New Album")).album)
        assertEquals("New Album", CorrectionLogic.apply(song, override(17, 1984).copy(album = "New Album")).album)
        assertNull("an empty album clears it", CorrectionLogic.apply(withAlbum, override(17, 1984).copy(album = "")).album)
        assertEquals("the id is independent", "B006LSV05O", CorrectionLogic.apply(withAlbum, override(17, 1984).copy(album = "X")).amazonId)
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
        assertEquals("set,card,year,title,artist,time,source,amazonId,album", lines[0])
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("$set,17,1984,"))
        assertTrue(lines[2].startsWith("$set,5,2001,"))
        assertTrue(content.endsWith("\n"))
    }

    @Test
    fun theExportHasTheAmazonIdOfTheLatestCorrection() {
        val content = CorrectionLogic.exportContent(listOf(
            override(103, 2006, time = "t1").copy(amazonId = ""),
            override(103, 2006, time = "t2").copy(amazonId = "B006LSV05O")
        ))
        val lines = content.trimEnd().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[1].endsWith(",t2,reader,B006LSV05O"))
    }

    @Test
    fun anEmptyExportHasOnlyTheHeader() {
        assertEquals("set,card,year,title,artist,time,source,amazonId,album\n", CorrectionLogic.exportContent(emptyList()))
    }

    @Test
    fun theFileNameHasTheDate() {
        assertEquals("hitster-corrections-2026-11-09.csv", CorrectionLogic.exportFileName("2026-11-09"))
    }
}
