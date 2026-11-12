package de.jobisoft.amster.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jobisoft.amster.util.CardOverride
import de.jobisoft.amster.util.CorrectionLogic
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The corrections of the user on this phone (`filesDir/card_overrides.csv`). They lie over the
 * bundled card data, so the scanner, the quiz, the reveal box and the reader show the corrected
 * values at once, and they survive app updates because they live in a file of their own. Only a
 * "wrong" verdict writes one. The last correction of a card wins. Cached in memory.
 */
@Singleton
class CardOverrides @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val file = File(context.filesDir, "card_overrides.csv")
    private val rows by lazy { load() }

    private fun load(): MutableMap<String, CardOverride> = try {
        val list = if (file.exists()) file.readLines().mapNotNull { CorrectionLogic.parseLine(it) } else emptyList()
        LinkedHashMap(CorrectionLogic.lastWins(list))
    } catch (_: Exception) {
        LinkedHashMap()
    }

    /** The corrections of one card set, by card number. */
    @Synchronized
    fun forSet(setFile: String): Map<Int, CardOverride> =
        rows.values.filter { it.setFile == setFile }.associateBy { it.card }

    /** The name of a set that is known from corrections: the first non-empty edition of its rows, null for none. */
    @Synchronized
    fun editionOf(setFile: String): String? =
        rows.values.firstOrNull { it.setFile == setFile && !it.edition.isNullOrBlank() }?.edition

    /** The set files that have corrections, in the order they were first corrected. */
    @Synchronized
    fun sets(): List<String> = rows.values.map { it.setFile }.distinct()

    @Synchronized
    fun all(): List<CardOverride> = rows.values.toList()

    /**
     * The card set was deleted: its corrections go with it, in memory and in the file. Returns how many were removed. The file
     * is rewritten with the latest correction per card of the other sets.
     */
    @Synchronized
    fun removeSet(setFile: String): Int {
        val keep = CorrectionLogic.withoutSet(rows.values, setFile)
        val removed = rows.size - keep.size
        if (removed == 0) return 0
        rows.clear()
        for (row in keep) rows[row.key] = row
        try {
            file.writeText(CorrectionLogic.HEADER + "\n" + keep.joinToString("") { CorrectionLogic.line(it) + "\n" })
        } catch (_: Exception) {
            // gone from memory for this run, the file is rewritten at the next change
        }
        return removed
    }

    /** Saves a correction: appended to the file, and in memory at once. */
    @Synchronized
    fun add(override: CardOverride) {
        rows[override.key] = override
        try {
            if (!file.exists()) file.writeText(CorrectionLogic.HEADER + "\n")
            file.appendText(CorrectionLogic.line(override) + "\n")
        } catch (_: Exception) {
            // kept in memory for this run, the reader log still has the verdict
        }
    }
}
