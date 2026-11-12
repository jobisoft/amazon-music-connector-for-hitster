package de.jobisoft.hitsterconnector.util

/** The text sent to Amazon Music as the search. Kept in one place. */
object SearchQuery {
    /** The query: artist first, then the title. */
    fun query(title: String, artist: String): String = "$artist, $title"
}
