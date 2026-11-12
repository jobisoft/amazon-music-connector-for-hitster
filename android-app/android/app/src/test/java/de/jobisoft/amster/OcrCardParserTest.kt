package de.jobisoft.amster

import de.jobisoft.amster.reader.OcrCard
import de.jobisoft.amster.reader.OcrCardParser
import de.jobisoft.amster.reader.OcrFlow
import de.jobisoft.amster.reader.OcrLine
import de.jobisoft.amster.reader.OcrStability
import de.jobisoft.amster.reader.UnknownCardKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrCardParserTest {

    private fun lines(vararg l: Pair<String, Int>) = l.map { OcrLine(it.first, it.second) }

    @Test
    fun artistYearTitleFromTheBackOfACard() {
        val card = OcrCardParser.parse(lines("Whitney Houston" to 30, "1987" to 120, "I Wanna Dance With Somebody" to 34))
        assertEquals(OcrCard("Whitney Houston", 1987, "I Wanna Dance With Somebody"), card)
    }

    @Test
    fun severalLinesOnEachSideAreJoined() {
        val card = OcrCardParser.parse(lines("Kool &" to 28, "The Gang" to 28, "1980" to 110, "Celebration" to 30, "(Single Version)" to 24))
        assertEquals("Kool & The Gang", card!!.artist)
        assertEquals("Celebration (Single Version)", card.title)
    }

    @Test
    fun theBiggestYearLineWinsOverANumberInTheTitle() {
        val card = OcrCardParser.parse(lines("Prince" to 30, "1999" to 20, "1984" to 120, "Purple Rain" to 34))
        assertEquals(1984, card!!.year)
        assertEquals("a small number next to the text is a corner of the card, not part of the artist", "Prince", card.artist)
    }

    @Test
    fun withTheSameHeightTheLineNearestToTheMiddleWins() {
        val card = OcrCardParser.parse(lines("Artist" to 30, "1985" to 30, "Title" to 30))
        assertEquals(1985, card!!.year)
    }

    @Test
    fun lettersThatLookLikeDigitsInAYearLineAreRead() {
        assertEquals(1985, OcrCardParser.parse(lines("Abba" to 30, "l985" to 100, "Waterloo" to 30))!!.year)
        assertEquals(2006, OcrCardParser.parse(lines("Abba" to 30, "2OO6" to 100, "Waterloo" to 30))!!.year)
        assertEquals(1990, OcrCardParser.parse(lines("Abba" to 30, " 1990. " to 100, "Waterloo" to 30))!!.year)
    }

    @Test
    fun aWordIsNoYear() {
        assertNull(OcrCardParser.yearOf("BOZO"))
        assertNull(OcrCardParser.yearOf("1800"))
        assertNull(OcrCardParser.yearOf("2100"))
        assertNull(OcrCardParser.yearOf("19855"))
        assertEquals(2024, OcrCardParser.yearOf("2024"))
    }

    @Test
    fun noYearOrNothingAboveOrBelowIsNoCard() {
        assertNull(OcrCardParser.parse(lines("Artist" to 30, "Title" to 30)))
        assertNull(OcrCardParser.parse(lines("1987" to 100, "Title" to 30)))
        assertNull(OcrCardParser.parse(lines("Artist" to 30, "1987" to 100)))
        assertNull(OcrCardParser.parse(emptyList()))
    }

    private fun box(text: String, left: Int, top: Int, right: Int, bottom: Int) = OcrLine(text, bottom - top, left, top, right, bottom)

    @Test
    fun linesOfThePictureAroundTheCardAreIgnored() {
        // the card of the log: "Owl City" / 2009 / "Fireflies", with text of a poster behind and above it
        val card = OcrCardParser.parse(
            listOf(
                box("m a EN Wen PR", 10, 20, 180, 79),
                box("Owl City", 280, 230, 430, 266),
                box("2009", 175, 380, 545, 523),
                box("Fireflies", 300, 600, 435, 645),
                box("SALE 50", 5, 640, 120, 649)
            )
        )
        assertEquals(OcrCard("Owl City", 2009, "Fireflies"), card)
    }

    @Test
    fun twoLinesOfTheSameBlockAreJoinedOnlyWhenTheyAreClose() {
        val card = OcrCardParser.parse(
            listOf(
                box("Kool &", 250, 120, 380, 156),
                box("The Gang", 240, 162, 390, 198),
                box("1980", 200, 300, 520, 420),
                box("Celebration", 230, 520, 480, 566),
                box("(Single Version)", 220, 574, 490, 610),
                box("far away text", 240, 900, 490, 940)
            )
        )
        assertEquals("Kool & The Gang", card!!.artist)
        assertEquals("Celebration (Single Version)", card.title)
    }

    @Test
    fun aLineOffToTheSideIsNotPartOfTheCard() {
        val card = OcrCardParser.parse(
            listOf(box("Prince", 250, 150, 400, 186), box("1984", 200, 300, 520, 420), box("Purple Rain", 240, 520, 480, 566), box("other card", 640, 520, 700, 556))
        )
        assertEquals("Purple Rain", card!!.title)
    }

    @Test
    fun specksAreDroppedButShortNamesStay() {
        val card = OcrCardParser.parse(lines("20607" to 7, "U2" to 26, "1987" to 90, "With Or Without You" to 24, "=" to 40))
        assertEquals(OcrCard("U2", 1987, "With Or Without You"), card)
    }

    @Test
    fun emptyAndNoiseLinesAreIgnoredAndUmlautsStay() {
        val card = OcrCardParser.parse(lines("" to 10, "—" to 10, "Die Ärzte" to 30, "2007" to 100, "  " to 5, "Junge" to 34))
        assertEquals(OcrCard("Die Ärzte", 2007, "Junge"), card)
    }

    // ── two reads in a row ──────────────────────────────────────────────────

    @Test
    fun aCardCountsAfterTwoEqualReadsInARow() {
        val s = OcrStability()
        val a = OcrCard("Prince", 1984, "Purple Rain")
        assertNull(s.feed(a))
        val second = a.copy(title = "purple  rain")
        assertEquals("the second read is the result", second, s.feed(second))
    }

    @Test
    fun aDifferentReadDoesNotCountAndNoiseBetweenTwoGoodReadsIsFine() {
        val s = OcrStability()
        val a = OcrCard("Prince", 1984, "Purple Rain")
        assertNull(s.feed(a))
        assertNull("another year", s.feed(a.copy(year = 1985)))
        assertNull("no card", s.feed(null))
        assertEquals("the first read is still within the window", a, s.feed(a))
    }

    @Test
    fun aReadFallsOutOfTheWindow() {
        val s = OcrStability()
        val a = OcrCard("Prince", 1984, "Purple Rain")
        s.feed(a)
        repeat(OcrStability.WINDOW) { s.feed(null) }
        assertNull("too long ago", s.feed(a))
    }

    @Test
    fun theReadsOfARealCardWithSpecks() {
        // the log of a phone: speck lines ("20607", "ae", "zz", dashes) between the lines of "Manic Monday"
        val s = OcrStability()
        fun read(vararg l: Pair<String, Int>) = s.feed(OcrCardParser.parse(l.map { OcrLine(it.first, it.second) }))
        assertNull(read("20607" to 7, "The Bangles" to 23, "1984" to 84, "Manic Monday" to 24))
        assertNull(read("The Bangles" to 18, "1986" to 56, "Manic Monday" to 19))
        assertNull(read("The Bangles" to 16, "1986" to 50, "Manic Monday" to 16, "—— ESS" to 28))
        assertNull(read("= " to 74, "The Bangles" to 16, "1986" to 48, "Manic Monday" to 16, "ae" to 11))
        val result = read("The Bangles" to 16, "1986" to 49, "Manic Monday" to 15)
        assertEquals(OcrCard("The Bangles", 1986, "Manic Monday"), result)
    }

    // ── the cases of an unknown code ────────────────────────────────────────

    @Test
    fun theKindOfAnUnknownCode() {
        assertEquals(UnknownCardKind.NOT_HITSTER, OcrFlow.kind(hasInfo = false, cardFound = false, setKnown = false))
        assertEquals(UnknownCardKind.NEW_EDITION, OcrFlow.kind(hasInfo = true, cardFound = false, setKnown = false))
        assertEquals(UnknownCardKind.NEW_CARD, OcrFlow.kind(hasInfo = true, cardFound = false, setKnown = true))
        assertNull(OcrFlow.kind(hasInfo = true, cardFound = true, setKnown = true))
        assertNull("a card of a set without a name is known when it has a correction", OcrFlow.kind(hasInfo = true, cardFound = true, setKnown = false))
    }
}
