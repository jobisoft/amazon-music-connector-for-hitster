package de.jobisoft.amster

import de.jobisoft.amster.reader.OcrImage
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrImageTest {

    /** A card in mid grey (100) with a shade from left (70) to right (130) and dark text (40). */
    private fun card(width: Int, height: Int): IntArray {
        val g = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) g[y * width + x] = 70 + 60 * x / (width - 1)
        // a block of "text", 4 pixels thick
        for (y in 30..33) for (x in 20..80) g[y * width + x] = 40
        return g
    }

    @Test
    fun darkTextBecomesBlackAndTheShadedGroundWhite() {
        val w = 100
        val h = 60
        val out = OcrImage.binarize(card(w, h), w, h)
        for (x in 20..80) assertEquals("text at $x", 0, out[31 * w + x])
        // the ground on the dark side and on the light side are both white
        assertEquals(255, out[5 * w + 3])
        assertEquals(255, out[50 * w + 95])
        assertEquals(255, out[5 * w + 50])
    }

    @Test
    fun aPlainPictureStaysWhite() {
        val out = OcrImage.binarize(IntArray(40 * 30) { 120 }, 40, 30)
        assertEquals(40 * 30, out.count { it == 255 })
    }
}
