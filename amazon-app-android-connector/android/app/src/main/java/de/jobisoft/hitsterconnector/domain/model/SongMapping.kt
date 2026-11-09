package de.jobisoft.hitsterconnector.domain.model

/**
 * Mapping from QR code URL to song information
 */
data class SongMapping(
    val url: String,
    val title: String,
    val artist: String,
    val year: Int?,
    val album: String? = null
)