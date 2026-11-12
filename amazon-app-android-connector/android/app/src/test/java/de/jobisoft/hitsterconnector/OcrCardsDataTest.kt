package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.data.repository.CSVSong
import de.jobisoft.hitsterconnector.util.CardOverride
import de.jobisoft.hitsterconnector.util.CorrectionLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cards that only exist as corrections on the phone (read by the reader) and the edition column. */
class OcrCardsDataTest {

    private val set = "hitster-de-aaaa0099.csv"

    private fun o(card: Int, year: Int? = 1990, title: String = "Song", artist: String = "Artist", edition: String? = null) =
        CardOverride(set, card, year, title, artist, "t", "ocr", edition = edition)

    private val base = mapOf(
        1 to CSVSong(1, "One", "A", 1980, "B000000001", "Album One"),
        2 to CSVSong(2, "Two", "B", 1981)
    )

    // ── addMissing ──────────────────────────────────────────────────────────

    @Test
    fun aCorrectionForAMissingCardMakesTheCard() {
        val songs = CorrectionLogic.addMissing(base, mapOf(3 to o(3, 1999, "Three", "C")))
        assertEquals(setOf(1, 2, 3), songs.keys)
        val s = songs.getValue(3)
        assertEquals(3, s.index)
        assertEquals("Three", s.title)
        assertEquals("C", s.artist)
        assertEquals(1999, s.year)
        assertTrue(s.corrected)
        assertNull(s.amazonId)
        assertNull(s.album)
    }

    @Test
    fun aCorrectionForAnExistingCardReplacesYearTitleAndArtistAndKeepsIdAndAlbum() {
        val songs = CorrectionLogic.addMissing(base, mapOf(1 to o(1, 1979, "One!", "A2")))
        val s = songs.getValue(1)
        assertEquals("One!", s.title)
        assertEquals("A2", s.artist)
        assertEquals(1979, s.year)
        assertEquals("B000000001", s.amazonId)
        assertEquals("Album One", s.album)
        assertEquals(2, songs.size)
    }

    @Test
    fun withoutAnyBaseTheCardsAreTheCorrections() {
        val songs = CorrectionLogic.addMissing(emptyMap(), mapOf(5 to o(5), 7 to o(7)))
        assertEquals(listOf(5, 7), songs.keys.toList())
    }

    @Test
    fun aCorrectionWithoutYearTitleOrArtistDoesNotMakeACard() {
        val songs = CorrectionLogic.addMissing(
            emptyMap(),
            mapOf(1 to o(1, year = null), 2 to o(2, title = ""), 3 to o(3, artist = " "))
        )
        assertTrue(songs.isEmpty())
    }

    @Test
    fun noCorrectionsGiveTheSameMap() {
        assertEquals(base, CorrectionLogic.addMissing(base, emptyMap()))
    }

    // ── a set without a card yet ────────────────────────────────────────────

    @Test
    fun aRowThatOnlyNamesASetMakesNoCardButGivesTheName() {
        val row = CardOverride(set, CorrectionLogic.SET_ROW, null, "", "", "t", "edition", edition = "Hitster Foo")
        assertEquals(0, CorrectionLogic.SET_ROW)
        assertTrue("no card", CorrectionLogic.addMissing(emptyMap(), mapOf(CorrectionLogic.SET_ROW to row)).isEmpty())
        val back = CorrectionLogic.parseLine(CorrectionLogic.line(row))!!
        assertEquals("Hitster Foo", back.edition)
        assertEquals(CorrectionLogic.SET_ROW, back.card)
        assertNull(back.year)
    }

    // ── a deleted set takes its corrections with it ─────────────────────────

    @Test
    fun theCorrectionsOfADeletedSetAreDropped() {
        val other = CardOverride("hitster-nl.csv", 1, 1990, "T", "A", "t", "reader")
        val rows = listOf(o(1), o(2), other)
        assertEquals(listOf(other), CorrectionLogic.withoutSet(rows, set))
        assertEquals(rows, CorrectionLogic.withoutSet(rows, "hitster-xx.csv"))
        assertTrue(CorrectionLogic.withoutSet(emptyList(), set).isEmpty())
    }

    // ── the edition column ──────────────────────────────────────────────────

    @Test
    fun theEditionSurvivesALineRoundTrip() {
        val row = o(4, edition = "Hitster Foo, Bar")
        val back = CorrectionLogic.parseLine(CorrectionLogic.line(row))!!
        assertEquals("Hitster Foo, Bar", back.edition)
        assertNull("the id stays unchanged", back.amazonId)
        assertEquals(set, back.setFile)
        assertEquals(4, back.card)
    }

    @Test
    fun aRowWithoutAnEditionHasNone() {
        val back = CorrectionLogic.parseLine(CorrectionLogic.line(o(4)))!!
        assertNull(back.edition)
    }

    @Test
    fun anEditionNeedsTheIdAndAlbumColumnsInFrontOfItSoTheyReadBackAsUnchanged() {
        val line = CorrectionLogic.line(o(4, edition = "E"))
        val fields = line.split(",")
        assertEquals(10, fields.size)
        assertEquals(CorrectionLogic.KEEP_ID, fields[7])
        assertEquals("", fields[8])
        val back = CorrectionLogic.parseLine(line)!!
        assertEquals("an empty album column clears the album, so the row says so", "", back.album)
    }

    @Test
    fun oldRowsWithSevenToNineFieldsStillParse() {
        assertNull(CorrectionLogic.parseLine("hitster-de.csv,1,1990,T,A,t,reader")!!.edition)
        assertNull(CorrectionLogic.parseLine("hitster-de.csv,1,1990,T,A,t,reader,B006LSV05O,Alb")!!.edition)
        assertFalse(CorrectionLogic.HEADER.isEmpty())
        assertTrue(CorrectionLogic.HEADER.endsWith(",edition"))
    }
}
