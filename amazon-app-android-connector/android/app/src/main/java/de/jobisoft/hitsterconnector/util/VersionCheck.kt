package de.jobisoft.hitsterconnector.util

/**
 * Tells whether the title Amazon loaded looks like another version of the song (remix, live, karaoke ...).
 * Only the additions in brackets or after a dash are looked at, and a marker that the wanted title has too is fine.
 * Remastered, single version, explicit, radio edit, year numbers and "feat." are harmless.
 */
object VersionCheck {
    private val markers = listOf(
        "remix", "mix", "live", "karaoke", "instrumental", "cover", "tribute", "acoustic", "demo",
        "sped up", "slowed", "reverb", "lullaby", "originally performed", "made famous", "in the style of"
    )

    private val additions = Regex("""[(\[]([^)\]]*)[)\]]|\s[-\u2013\u2014]\s(.*)$""")

    private fun words(text: String): String =
        " " + text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() + " "

    private fun hasMarker(text: String, marker: String): Boolean = words(text).contains(" $marker ")

    /** The text of all brackets and the part after a dash of [shown]. */
    fun additionsOf(shown: String): String =
        additions.findAll(shown).joinToString(" ") { it.groupValues[1] + " " + it.groupValues[2] }

    fun suspicious(wantedTitle: String, shownTitle: String?): Boolean {
        if (shownTitle.isNullOrBlank()) return false
        val extra = additionsOf(shownTitle)
        if (extra.isBlank()) return false
        return markers.any { hasMarker(extra, it) && !hasMarker(wantedTitle, it) }
    }

    /** The marker words found, for the debug log. */
    fun found(wantedTitle: String, shownTitle: String?): List<String> {
        if (shownTitle.isNullOrBlank()) return emptyList()
        val extra = additionsOf(shownTitle)
        return markers.filter { hasMarker(extra, it) && !hasMarker(wantedTitle, it) }
    }
}
