package de.jobisoft.amster.ui.theme

import androidx.compose.ui.unit.dp

/** Spacing and sizes, one rule for all screens. */
object Dimens {
    val Space4 = 4.dp
    val Space8 = 8.dp
    val Space12 = 12.dp
    val Space16 = 16.dp
    val Space24 = 24.dp
    val Space32 = 32.dp

    /** Height of every button. */
    val Control = 56.dp

    /** Content is not wider than this on tablets and in landscape. */
    val ContentMaxWidth = 560.dp

    /** The two column screens (Home, the result of a card) are at most this wide. */
    val WideMaxWidth = 960.dp

    /** From this width the screens with two columns use them (a tablet, a phone in landscape). */
    val WideBreakpoint = 600.dp
}
