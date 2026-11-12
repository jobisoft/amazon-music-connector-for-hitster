package de.jobisoft.hitsterconnector.data.repository

import de.jobisoft.hitsterconnector.data.local.CSVFileManager
import de.jobisoft.hitsterconnector.data.local.CardOverrides
import de.jobisoft.hitsterconnector.util.CorrectionLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.StringReader
import javax.inject.Inject

/**
 * Repository for loading and parsing the CSV files of the card sets in local storage
 */
@javax.inject.Singleton
class CSVRepository @Inject constructor(
    private val csvFileManager: CSVFileManager,
    private val overrides: CardOverrides
) {
    internal data class ParsedCSV(
        val songsByIndex: Map<Int, CSVSong>
    )
    
    /**
     * Load a CSV file from local storage
     * Returns a map of index (from first column) to song information
     */
    suspend fun loadCSV(csvUrlOrName: String): Result<Map<Int, CSVSong>> = withContext(Dispatchers.IO) {
        try {
            val csvContent = loadCSVContent(csvUrlOrName)
            val parsed = parseCSV(csvContent)
            Result.success(withOverrides(csvUrlOrName, parsed.songsByIndex))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * The corrections of the user on this phone lie over the bundled values: title, artist and year of
     * a corrected card are replaced, the song is marked `corrected`. So the scanner, the quiz, the
     * reveal box and the reader all show the corrected values.
     */
    private fun withOverrides(csvUrlOrName: String, songs: Map<Int, CSVSong>): Map<Int, CSVSong> {
        val setFile = if (csvUrlOrName.startsWith("http", ignoreCase = true)) csvUrlOrName.substringAfterLast("/") else csvUrlOrName
        val corrections = overrides.forSet(setFile)
        if (corrections.isEmpty()) return songs
        return songs.mapValues { (index, song) -> corrections[index]?.let { CorrectionLogic.apply(song, it) } ?: song }
    }

    /**
     * Get song from CSV by index (from first column)
     */
    suspend fun getSongFromCSV(
        csvUrl: String,
        index: Int
    ): Result<CSVSong?> = withContext(Dispatchers.IO) {
        try {
            val csvContent = loadCSVContent(csvUrl)
            val parsed = parseCSV(csvContent)
            Result.success(withOverrides(csvUrl, parsed.songsByIndex)[index])
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun loadCSVContent(csvUrlOrName: String): String {
        // Extract filename if it's a full URL
        val fileName = if (csvUrlOrName.contains("/") || csvUrlOrName.contains("\\")) {
            csvUrlOrName.substringAfterLast("/").substringAfterLast("\\")
        } else {
            csvUrlOrName
        }

        // First try to load from local storage using filename
        val localFile = csvFileManager.getLocalFile(fileName)
        if (localFile != null && localFile.exists()) {
            return localFile.readText()
        }

        throw Exception("This card set is not installed. Import its CSV file in Card Config.")
    }

    // the parsing is pure: it does not use the files of the app
    companion object {
        /** The text of the error for a file without our columns. */
        const val MISSING_COLUMNS = "This CSV file needs the columns Card#, Title and Artist."

        /**
         * Parse CSV content into a map from card number to song. The columns are found by their names in the
         * header, in any case and in any place: `Card#`, `Title` and `Artist` are needed, `Year` and `AmazonID`
         * may be missing or empty. Every other column (the upstream files of SongSeeker have `URL`,
         * `Hashed Info`, `Youtube-Title`, `ISRC`, `Album`) is ignored. A file without the three needed columns
         * in its header gives an error. Handles RFC 4180 compliant CSV with quoted fields.
         */
        internal fun parseCSV(csvContent: String): ParsedCSV {
            val lines = StringReader(csvContent.removePrefix("\uFEFF")).readLines()
            val header = lines.firstOrNull()?.let { parseCSVLine(it).map { name -> name.trim().lowercase() } }.orEmpty()
            val cardIndex = header.indexOf("card#")
            val titleIndex = header.indexOf("title")
            val artistIndex = header.indexOf("artist")
            val yearIndex = header.indexOf("year")
            val amazonIdIndex = header.indexOf("amazonid")
            if (cardIndex < 0 || titleIndex < 0 || artistIndex < 0) throw Exception(MISSING_COLUMNS)

            val songsByIndex = mutableMapOf<Int, CSVSong>()
            for (line in lines.drop(1)) {
                if (line.isBlank()) continue
                val columns = parseCSVLine(line)
                val card = columns.getOrNull(cardIndex)?.trim()?.toIntOrNull() ?: continue
                val title = columns.getOrNull(titleIndex)?.trim().orEmpty()
                val artist = columns.getOrNull(artistIndex)?.trim().orEmpty()
                if (title.isBlank() || artist.isBlank()) continue
                songsByIndex[card] = CSVSong(
                    index = card,
                    title = title,
                    artist = artist,
                    year = columns.getOrNull(yearIndex)?.trim()?.toIntOrNull(),
                    amazonId = columns.getOrNull(amazonIdIndex)?.trim()?.ifBlank { null }
                )
            }
            return ParsedCSV(songsByIndex = songsByIndex)
        }

        /**
         * Parse a single CSV line handling quoted fields according to RFC 4180
         * Handles:
         * - Fields with commas: "Title, with comma"
         * - Fields with quotes: "Title with ""quotes"""
         * - Mixed quoted and unquoted fields
         */
        private fun parseCSVLine(line: String): List<String> {
            val result = mutableListOf<String>()
            val currentField = StringBuilder()
            var inQuotes = false
            var i = 0
        
            while (i < line.length) {
                val char = line[i]
            
                when {
                    // Handle quote character
                    char == '"' -> {
                        if (inQuotes) {
                            // Check if this is an escaped quote (two quotes in a row)
                            if (i + 1 < line.length && line[i + 1] == '"') {
                                // Escaped quote - add one quote to field
                                currentField.append('"')
                                i++ // Skip the second quote
                            } else {
                                // End of quoted field
                                inQuotes = false
                            }
                        } else {
                            // Start of quoted field
                            inQuotes = true
                        }
                    }
                    // Handle comma (field separator)
                    char == ',' && !inQuotes -> {
                        // End of field
                        result.add(currentField.toString())
                        currentField.clear()
                    }
                    // Regular character
                    else -> {
                        currentField.append(char)
                    }
                }
                i++
            }
        
            // Add the last field
            result.add(currentField.toString())
        
            return result
        }
    }

}

/**
 * Data class representing a song from CSV
 */
data class CSVSong(
    val index: Int,  // Index from first column of CSV
    val title: String,
    val artist: String,
    val year: Int? = null,
    /** The Amazon track id of the song (column `AmazonID`, optional). The Android Amazon app plays it first. */
    val amazonId: String? = null,
    /** True if a correction of the user on this phone lies over the bundled values (see CardOverrides). */
    val corrected: Boolean = false
)
