package de.jobisoft.hitsterconnector.util

import java.text.Normalizer

/**
 * Is the title the session shows the title that was requested? Used to classify an item of a text search.
 * Case, accents, text in brackets and punctuation do not count, one title may contain the other.
 *
 * This is stricter than [QuizPicker.normalize] and [SongIdentity.same], which also drop a trailing " - 2011 Remaster"
 * style suffix. Two titles that differ only in such a suffix are not the same song here.
 */
object TitleMatch {
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ") // "(Remastered)", "[Live]"
            .replace(Regex("[^a-z0-9]+"), "")

    /** False if nothing is shown or one side has no letters or digits. */
    fun same(shown: String?, wanted: String): Boolean {
        val a = normalize(shown ?: return false)
        val b = normalize(wanted)
        return a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a))
    }
}
