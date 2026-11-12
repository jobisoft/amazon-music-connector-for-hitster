package de.jobisoft.amster.ui.theme

/**
 * The colors of the app as plain ARGB numbers, so that the contrast of every text and background pair can be
 * checked in a unit test without Android (see ThemeContrastTest). [Color.kt] and [Theme.kt] build the theme from them.
 */
object Palette {
    /** Top and bottom of the gradient behind every screen. */
    const val BG_TOP = 0xFF070B14L
    const val BG_BOTTOM = 0xFF0F1A3DL

    /** The accent gradient of buttons and the slider: cyan to blue. Text on it is [ON_ACCENT]. */
    const val ACCENT_A = 0xFF4DD4E8L
    const val ACCENT_B = 0xFF6C8CFFL
    const val ON_ACCENT = 0xFF06121FL

    const val TEXT = 0xFFF5F3FAL
    const val TEXT_DIM = 0xFFC2BDD3L

    /** Cards of a calm green: success text, and the dark base of a success card. */
    const val SUCCESS = 0xFF6BE3A4L
    const val ON_SUCCESS = 0xFF06150DL
    const val SUCCESS_CONTAINER = 0xFF12382AL

    const val ERROR = 0xFFFF8E8EL
    const val ERROR_CONTAINER = 0xFF4A1A26L

    /** Surfaces of dialogs and menus (opaque, they sit above the gradient). */
    const val SURFACE = 0xFF0F1830L
    const val SURFACE_HIGH = 0xFF18254AL

    /** Glass: white over the gradient. Fill and border are [WHITE] with these opacities. */
    const val GLASS_FILL_ALPHA = 0.10f
    const val GLASS_BORDER_ALPHA = 0.16f
    const val WHITE = 0xFFFFFFFFL
}
