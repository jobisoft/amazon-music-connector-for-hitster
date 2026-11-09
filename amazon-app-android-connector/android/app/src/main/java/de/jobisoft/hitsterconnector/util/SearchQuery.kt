package de.jobisoft.hitsterconnector.util

/** The text sent to Amazon Music as the search. Kept in one place. */
object SearchQuery {
    /** The one query: artist first, then the title and "[Single Version, <year>]", only "[Single Version]" without a year. */
    fun primary(title: String, artist: String, year: Int? = null): String =
        "$artist, ${extendedTitle(title, year)}"

    /** "<title> [Single Version, <year>]", "<title> [Single Version]" without a year. */
    fun extendedTitle(title: String, year: Int?): String =
        "$title [Single Version" + (if (year != null) ", $year" else "") + "]"


    /** The title extra of the first attempt is the extended title, of the second attempt the plain one. */
    fun titleExtra(title: String, year: Int?, second: Boolean): String = if (second) title else extendedTitle(title, year)

    /** Second attempt, after the first result was another version: the classic query without additions. */
    fun fallback(title: String, artist: String): String = "$title $artist"
}
