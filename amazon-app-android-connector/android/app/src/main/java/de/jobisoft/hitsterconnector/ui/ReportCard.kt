package de.jobisoft.hitsterconnector.ui

/**
 * The card on the result screen, as the "Report as wrong" button needs it: the card set
 * file and the number tell which card, the rest are OUR values (year, title, artist). [serial]
 * changes with every new card. Null for the fallback test song of an unknown card.
 */
data class ReportCard(
    val serial: Int,
    val setFile: String,
    val number: Int,
    val year: Int?,
    val title: String,
    val artist: String
)
