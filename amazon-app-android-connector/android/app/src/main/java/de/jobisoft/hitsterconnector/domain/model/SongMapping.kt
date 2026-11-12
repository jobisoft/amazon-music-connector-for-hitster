package de.jobisoft.hitsterconnector.domain.model

/**
 * Mapping from QR code URL to song information
 */
data class SongMapping(
    val url: String,
    val title: String,
    val artist: String,
    val year: Int?,
    /** The Amazon track id from the card set, played first if there is one. */
    val amazonId: String? = null
)