package de.jobisoft.hitsterconnector.data.local

import de.jobisoft.hitsterconnector.data.repository.CSVRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Display names of the card sets. The name is in the file of the set itself: the first non-empty value
 * of its column `Edition`. A set without a file gets it from its corrections ([CardOverrides.editionOf]). A set
 * without one, for example one the user added, shows its file name without ".csv".
 */
@Singleton
class CardSetNames @Inject constructor(
    private val csvFileManager: CSVFileManager,
    private val overrides: CardOverrides
) {
    private data class Entry(val stamp: Long, val size: Long, val name: String?)

    private val cache = HashMap<String, Entry>()

    @Synchronized
    fun displayName(fileName: String): String {
        val file = csvFileManager.getLocalFile(fileName)
        val name = if (file != null && file.exists()) {
            val cached = cache[file.name]
            if (cached != null && cached.stamp == file.lastModified() && cached.size == file.length()) {
                cached.name
            } else {
                editionOf(file).also { cache[file.name] = Entry(file.lastModified(), file.length(), it) }
            }
        } else {
            null
        }
        // a set that is only known from corrections on this phone (its cards were read by the reader) has its name there
        return name ?: overrides.editionOf(fileName) ?: fallback(fileName)
    }

    companion object {
        /** The name in the column `Edition` of a card set file, null if there is none or the file is not readable. */
        internal fun editionOf(file: File): String? = editionOf(try { file.readText() } catch (_: Exception) { return null })

        internal fun editionOf(csv: String): String? = try {
            CSVRepository.parseCSV(csv).edition
        } catch (_: Exception) {
            null
        }

        /** The file name without ".csv". */
        fun fallback(fileName: String): String = fileName.removeSuffix(".csv")
    }
}
