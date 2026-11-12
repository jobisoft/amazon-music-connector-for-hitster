package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.reader.OcrCapture
import de.jobisoft.hitsterconnector.reader.OcrCard
import de.jobisoft.hitsterconnector.reader.OcrCardParser
import de.jobisoft.hitsterconnector.reader.OcrImage
import de.jobisoft.hitsterconnector.reader.OcrLine
import de.jobisoft.hitsterconnector.reader.OcrOrientation
import de.jobisoft.hitsterconnector.reader.OcrQuality
import de.jobisoft.hitsterconnector.reader.OcrVote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrCaptureTest {

    private fun box(text: String, left: Int, top: Int, right: Int, bottom: Int) = OcrLine(text, bottom - top, left, top, right, bottom)

    // the card of the log: "Herbert Grönemeyer" / 1984 / "Männer" in a 649 x 649 picture
    private fun fullCard() = listOf(
        box("Herbert Grönemeyer", 122, 120, 493, 160),
        box("1984", 145, 260, 490, 375),
        box("Männer", 255, 480, 385, 515)
    )

    // ── sharpness ───────────────────────────────────────────────────────────

    @Test
    fun aFlatPictureIsNotSharpAndAHardEdgeIs() {
        val w = 40
        val h = 40
        assertEquals(0.0, OcrQuality.sharpness(IntArray(w * h) { 128 }, w, h), 0.0001)
        val edge = IntArray(w * h) { if (it % w < w / 2) 30 else 220 }
        val blurred = IntArray(w * h) { i -> val x = i % w; (30 + 190 * ((x - 10).coerceIn(0, 20)) / 20) }
        assertTrue(OcrQuality.sharpness(edge, w, h) > OcrQuality.sharpness(blurred, w, h) * 10)
        assertTrue(OcrQuality.sharpness(edge, w, h) > OcrQuality.MIN_SHARPNESS)
        assertTrue(OcrQuality.sharpness(blurred, w, h) < OcrQuality.MIN_SHARPNESS)
    }

    @Test
    fun aPictureThatIsTooSmallHasNoSharpness() {
        assertEquals(0.0, OcrQuality.sharpness(IntArray(4), 2, 2), 0.0)
    }

    // ── the whole card on the picture ───────────────────────────────────────

    @Test
    fun aWholeCardIsComplete() {
        val parsed = OcrCardParser.parseDetailed(fullCard())!!
        assertTrue(OcrCardParser.isComplete(parsed, 649, 649))
    }

    @Test
    fun aTitleCutAtTheEdgeIsNotComplete() {
        // "Fireflies" touches the lower edge
        val lines = listOf(box("Owl City", 280, 230, 430, 266), box("2009", 175, 380, 545, 523), box("Fireflies", 300, 600, 435, 646))
        val parsed = OcrCardParser.parseDetailed(lines)!!
        assertFalse(OcrCardParser.isComplete(parsed, 649, 649))
    }

    @Test
    fun aCardWithTheTitleFarFromTheYearIsNotComplete() {
        // the gap below the year is much bigger than the gap above: something else was read as the title
        val lines = listOf(box("Artist", 250, 200, 400, 236), box("1999", 175, 270, 545, 390), box("Title", 300, 590, 430, 625))
        assertFalse(OcrCardParser.isComplete(OcrCardParser.parseDetailed(lines)!!, 649, 649))
    }

    @Test
    fun linesWithoutBoxesAreNeverComplete() {
        val parsed = OcrCardParser.parseDetailed(listOf(OcrLine("Artist", 30), OcrLine("1999", 100), OcrLine("Title", 30)))!!
        assertFalse(OcrCardParser.isComplete(parsed, 649, 649))
    }

    // ── the area of the card ────────────────────────────────────────────────

    @Test
    fun theAreaOfTheCardIsTheBoxOfItsLinesAndStaysInsideThePicture() {
        val area = OcrImage.cardArea(fullCard(), 649, 649)
        assertTrue(area[0] < 122 && area[1] < 120 && area[2] > 493 && area[3] > 515)
        assertTrue(area[0] >= 0 && area[1] >= 0 && area[2] <= 649 && area[3] <= 649)
        val atTheEdge = OcrImage.cardArea(listOf(box("a", 0, 0, 100, 40)), 120, 60)
        assertEquals(listOf(0, 0, 120, 60).take(2), atTheEdge.toList().take(2))
        assertTrue(atTheEdge[2] <= 120 && atTheEdge[3] <= 60)
    }

    @Test
    fun theAreaIsEnlargedToAboutAThousandPixelsAtMostTwoAndAHalfTimes() {
        assertEquals(1.0, OcrImage.scaleFor(1200), 0.0)
        assertEquals(2.0, OcrImage.scaleFor(500), 0.0)
        assertEquals(OcrImage.MAX_SCALE, OcrImage.scaleFor(100), 0.0)
    }

    // ── the vote ────────────────────────────────────────────────────────────

    @Test
    fun theMostFrequentValueOfEachFieldWins() {
        val votes = listOf(
            OcrCard("Herbert Gronemeyer", 1984, "Manner"),
            OcrCard("Herbert Grönemeyer", 1984, "Männer"),
            OcrCard("Herbert Grönemeyer", 1984, "Männer"),
            OcrCard("Herbert Grönemeyer", 1986, "Männer"),
            OcrCard("Herbert Grönemeyer", 1984, "Manner")
        )
        assertEquals(OcrCard("Herbert Grönemeyer", 1984, "Männer"), OcrVote.decide(votes))
    }

    @Test
    fun theSpellingWithAccentsWinsEvenWhenItIsTheMinority() {
        // the log of a phone: "Manner" 19 times, "Männer" 6 times, the OCR drops the dots but never invents them
        val votes = List(19) { OcrCard("Herbert Gronemeyer", 1984, "Manner") } + List(6) { OcrCard("Herbert Grönemeyer", 1984, "Männer") }
        assertEquals(OcrCard("Herbert Grönemeyer", 1984, "Männer"), OcrVote.decide(votes))
    }

    @Test
    fun aFieldNeedsTwoEqualVotes() {
        assertNull(OcrVote.decide(emptyList()))
        assertNull(OcrVote.decide(listOf(OcrCard("A", 1990, "T"))))
        assertNull("three different titles", OcrVote.decide(listOf(OcrCard("A", 1990, "T1"), OcrCard("A", 1990, "T2"), OcrCard("A", 1990, "T3"))))
        assertNotNull(OcrVote.decide(listOf(OcrCard("A", 1990, "T"), OcrCard("a", 1990, "t"))))
    }

    @Test
    fun twoGoodPicturesAreCollected() {
        assertEquals(2, OcrCapture.NEEDED)
    }

    // ── the direction of the card ───────────────────────────────────────────

    @Test
    fun uprightTextNeedsNoTurn() {
        assertEquals(0, OcrOrientation.rotationFor(listOf(0.0, 1.5, -2.0)))
        assertEquals(0, OcrOrientation.rotationFor(emptyList()))
    }

    @Test
    fun aCardHeldUpsideDownIsTurnedByAHalfTurn() {
        assertEquals(180, OcrOrientation.rotationFor(listOf(180.0, -179.0, 178.0)))
    }

    @Test
    fun textThatReadsFromTheTopToTheBottomIsTurnedBackCounterClockwise() {
        // text turned 90 degrees clockwise: the picture is turned 270 degrees clockwise (90 counter clockwise)
        assertEquals(270, OcrOrientation.rotationFor(listOf(90.0, 88.0, 92.0)))
        assertEquals(90, OcrOrientation.rotationFor(listOf(-90.0, -88.0, -91.0)))
    }

    @Test
    fun aCrookedCardIsNotTurned() {
        assertEquals(0, OcrOrientation.rotationFor(listOf(45.0, 47.0, 44.0)))
    }

    @Test
    fun oneWrongLineDoesNotTurnThePicture() {
        assertEquals(0, OcrOrientation.rotationFor(listOf(0.0, 0.0, 180.0, 1.0, -1.0)))
    }

    @Test
    fun theNumberOfTheCardInTheCornerIsNotPartOfTheTitle() {
        val card = OcrCardParser.parse(
            listOf(
                box("Ich + Ich", 250, 120, 400, 161),
                box("2007", 200, 280, 460, 400),
                box("Vom selben Stern", 190, 480, 470, 521),
                box("82", 560, 560, 590, 577),
                box("DE07", 60, 560, 100, 574)
            )
        )
        assertEquals("Vom selben Stern", card!!.title)
    }

    @Test
    fun aCapitalIAndASmallLAreTheSameLetterForTheVote() {
        val votes = List(6) { OcrCard("Ich + Ich", 2007, "Vom selben Stern") } + List(2) { OcrCard("lch + lch", 2007, "Vom selben Stern") }
        assertEquals("Ich + Ich", OcrVote.decide(votes)!!.artist)
    }
}
