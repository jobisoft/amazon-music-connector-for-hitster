package de.jobisoft.hitsterconnector.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Display names of the card sets, from the asset `card_sets.csv` (columns File,Game, like the
 * `playlists.csv` of songseeker-hitster-playlists). A set without an entry, for example one the
 * user added, shows its file name without ".csv".
 */
@Singleton
class CardSetNames @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val names: Map<String, String> by lazy {
        try {
            context.assets.open("card_sets.csv").bufferedReader().use { parse(it.readText()) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun displayName(fileName: String): String = displayName(names, fileName)

    companion object {
        /** "File,Game" lines (the first line is the header) to file name -> name. */
        fun parse(csv: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            for (line in csv.lineSequence().drop(1)) {
                val comma = line.indexOf(',')
                if (comma <= 0) continue
                val file = line.substring(0, comma).trim().trim('"')
                val game = line.substring(comma + 1).trim().trim('"')
                if (file.isNotEmpty() && game.isNotEmpty()) result[file] = game
            }
            return result
        }

        fun displayName(names: Map<String, String>, fileName: String): String =
            names[fileName] ?: fileName.removeSuffix(".csv")
    }
}
