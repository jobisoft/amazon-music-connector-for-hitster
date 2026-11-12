package de.jobisoft.amster

import de.jobisoft.amster.ui.theme.Palette
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** Every text color of the theme is readable on the background it is drawn on (WCAG contrast). */
class ThemeContrastTest {
    private fun channel(argb: Long, shift: Int) = ((argb shr shift) and 0xFF) / 255.0

    private fun linear(c: Double) = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(rgb: DoubleArray) =
        0.2126 * linear(rgb[0]) + 0.7152 * linear(rgb[1]) + 0.0722 * linear(rgb[2])

    private fun rgb(argb: Long) = doubleArrayOf(channel(argb, 16), channel(argb, 8), channel(argb, 0))

    /** [fg] drawn over [bg] with [alpha] opacity. */
    private fun over(fg: Long, bg: DoubleArray, alpha: Double): DoubleArray {
        val f = rgb(fg)
        return DoubleArray(3) { f[it] * alpha + bg[it] * (1 - alpha) }
    }

    private fun contrast(a: DoubleArray, b: DoubleArray): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun check(name: String, fg: Long, bg: DoubleArray, minimum: Double = 4.5) {
        val ratio = contrast(rgb(fg), bg)
        assertTrue("$name: contrast $ratio is below $minimum", ratio >= minimum)
    }

    private val glassOnTop = over(Palette.WHITE, rgb(Palette.BG_TOP), Palette.GLASS_FILL_ALPHA.toDouble())
    private val glassOnBottom = over(Palette.WHITE, rgb(Palette.BG_BOTTOM), Palette.GLASS_FILL_ALPHA.toDouble())

    @Test
    fun textOnTheGradient() {
        for (bg in listOf(rgb(Palette.BG_TOP), rgb(Palette.BG_BOTTOM))) {
            check("text", Palette.TEXT, bg)
            check("dim text", Palette.TEXT_DIM, bg)
            check("accent text", Palette.ACCENT_A, bg)
            check("second accent text", Palette.ACCENT_B, bg)
        }
    }

    @Test
    fun textOnGlass() {
        for (bg in listOf(glassOnTop, glassOnBottom)) {
            check("text on glass", Palette.TEXT, bg)
            check("dim text on glass", Palette.TEXT_DIM, bg)
            check("accent on glass", Palette.ACCENT_A, bg)
        }
    }

    @Test
    fun textOnTheAccentGradient() {
        check("on accent A", Palette.ON_ACCENT, rgb(Palette.ACCENT_A))
        check("on accent B", Palette.ON_ACCENT, rgb(Palette.ACCENT_B))
    }

    @Test
    fun successAndErrorCards() {
        check("success text", Palette.SUCCESS, rgb(Palette.SUCCESS_CONTAINER))
        check("error card text", Palette.TEXT, rgb(Palette.ERROR_CONTAINER))
        check("error text on the gradient", Palette.ERROR, rgb(Palette.BG_BOTTOM))
        check("link on error card", Palette.ACCENT_B, rgb(Palette.ERROR_CONTAINER))
        check("on success", Palette.ON_SUCCESS, rgb(Palette.SUCCESS))
    }

    @Test
    fun dialogs() {
        check("dialog text", Palette.TEXT, rgb(Palette.SURFACE))
        check("dialog dim text", Palette.TEXT_DIM, rgb(Palette.SURFACE_HIGH))
    }
}
