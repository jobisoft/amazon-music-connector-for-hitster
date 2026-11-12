package de.jobisoft.amster.ui

/**
 * The card on the result screen, as the "Report as wrong" button needs it: the card set
 * file and the number tell which card, the rest are OUR values (year, title, artist, Amazon id, album). [serial]
 * changes with every new card.
 */
data class ReportCard(
    val serial: Int,
    val setFile: String,
    val number: Int,
    val year: Int?,
    val title: String,
    val artist: String,
    /** Our Amazon track id of the card, null if it has none. */
    val amazonId: String? = null,
    /** Our album of the card, null if it has none. */
    val album: String? = null
)
