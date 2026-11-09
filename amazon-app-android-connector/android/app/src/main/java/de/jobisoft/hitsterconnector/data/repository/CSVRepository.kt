package de.jobisoft.hitsterconnector.data.repository

import de.jobisoft.hitsterconnector.data.local.CSVFileManager
import de.jobisoft.hitsterconnector.data.local.CardOverrides
import de.jobisoft.hitsterconnector.util.CorrectionLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.StringReader
import java.net.URI
import javax.inject.Inject

/**
 * Repository for loading and parsing the CSV files of the card sets in local storage
 */
@javax.inject.Singleton
class CSVRepository @Inject constructor(
    private val csvFileManager: CSVFileManager,
    private val overrides: CardOverrides
) {
    private data class ParsedCSV(
        val songsByIndex: Map<Int, CSVSong>,
        val songsByUrl: Map<String, CSVSong>
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

    /**
     * Get song from CSV by URL (supports YouTube URL normalization).
     */
    suspend fun getSongByUrl(
        csvUrl: String,
        scannedUrl: String
    ): Result<CSVSong?> = withContext(Dispatchers.IO) {
        try {
            val csvContent = loadCSVContent(csvUrl)
            val parsed = parseCSV(csvContent)
            val song = buildLookupKeys(scannedUrl).asSequence()
                .mapNotNull { key -> parsed.songsByUrl[key] }
                .firstOrNull()
            Result.success(song)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Parse CSV content into a map
     * Assumes CSV format: index,title,artist,year (or similar)
     * Handles RFC 4180 compliant CSV with quoted fields
     */
    private fun parseCSV(csvContent: String): ParsedCSV {
        val lines = StringReader(csvContent).readLines()
        if (lines.isEmpty()) {
            return ParsedCSV(emptyMap(), emptyMap())
        }

        val headerColumns = parseCSVLine(lines.first()).map { it.trim().lowercase() }
        val artistIndex = headerColumns.indexOf("artist")
        val titleIndex = headerColumns.indexOf("title")
        val yearIndex = headerColumns.indexOf("year")
        val urlIndex = headerColumns.indexOf("url")
        // The number of the card. Do not take the row number instead: some files list the cards
        // downwards (308, 307, ...), and then the row is not the card.
        val cardNumberIndex = headerColumns.indexOf("card#")
        val rowIsArtistTitleYearUrl = artistIndex >= 0 && titleIndex >= 0 && yearIndex >= 0 && urlIndex >= 0

        val songsByIndex = mutableMapOf<Int, CSVSong>()
        val songsByUrl = mutableMapOf<String, CSVSong>()
        var fallbackIndex = 1

        lines.drop(1).forEach { line ->
            if (line.isBlank()) {
                return@forEach
            }

            val columns = parseCSVLine(line)
            if (columns.size < 3) {
                return@forEach
            }

            val song = if (rowIsArtistTitleYearUrl) {
                val artist = columns.getOrNull(artistIndex)?.trim().orEmpty()
                val title = columns.getOrNull(titleIndex)?.trim().orEmpty()
                val year = columns.getOrNull(yearIndex)?.trim()?.toIntOrNull()
                val url = columns.getOrNull(urlIndex)?.trim().orEmpty()

                if (title.isBlank() || artist.isBlank()) {
                    return@forEach
                }

                val rowNumber = fallbackIndex++
                CSVSong(
                    index = columns.getOrNull(cardNumberIndex)?.trim()?.toIntOrNull() ?: rowNumber,
                    title = title,
                    artist = artist,
                    year = year,
                    url = url.ifBlank { null }
                )
            } else {
                // Legacy format: index,title,artist,year[,url]
                val index = columns[0].trim().toIntOrNull() ?: return@forEach
                val title = columns.getOrNull(1)?.trim().orEmpty()
                val artist = columns.getOrNull(2)?.trim().orEmpty()
                val year = columns.getOrNull(3)?.trim()?.toIntOrNull()
                val url = columns.getOrNull(4)?.trim().orEmpty()

                if (title.isBlank() || artist.isBlank()) {
                    return@forEach
                }

                CSVSong(
                    index = index,
                    title = title,
                    artist = artist,
                    year = year,
                    url = url.ifBlank { null }
                )
            }

            songsByIndex[song.index] = song
            song.url?.let { url ->
                val trimmed = url.trim()
                if (trimmed.isNotEmpty()) {
                    buildLookupKeys(trimmed).forEach { key ->
                        songsByUrl[key] = song
                    }
                }
            }
        }

        return ParsedCSV(
            songsByIndex = songsByIndex,
            songsByUrl = songsByUrl
        )
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

    private fun buildLookupKeys(url: String): Set<String> {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            return setOf(trimmed)
        }

        val keys = linkedSetOf(trimmed)
        val uri = parseLookupUri(trimmed) ?: run {
            return keys
        }
        val host = (uri.host ?: "").lowercase().removePrefix("www.")
        val path = uri.path ?: ""
        val fragment = uri.fragment?.trim().orEmpty()
        val normalizedPath = path.trimEnd('/').ifEmpty { "/" }

        if (host == "youtu.be" || host.endsWith("youtube.com")) {
            val videoId = when {
                host == "youtu.be" -> path.trim('/').substringBefore("/")
                path.startsWith("/watch") -> extractQueryParam(uri.query, "v")
                path.startsWith("/shorts/") -> path.removePrefix("/shorts/").substringBefore("/")
                path.startsWith("/embed/") -> path.removePrefix("/embed/").substringBefore("/")
                else -> null
            }?.takeIf { it.isNotBlank() }

            if (videoId != null) {
                keys.add("youtube:$videoId")
            }
        }

        if (host.contains("hitster.com") || host.contains("hitstergame.com")) {
            keys.add("hitster:$normalizedPath")
            val pathSegments = normalizedPath.trim('/').split("/").filter { it.isNotBlank() }
            if (pathSegments.isNotEmpty()) {
                keys.add("hitster-segment:${pathSegments.last()}")
            }
        }

        if (fragment.isNotEmpty()) {
            keys.add("hash:$fragment")
            keys.add(fragment)
        }

        keys.add(trimmed.substringBefore("#").trimEnd('/'))
        return keys
    }

    private fun parseLookupUri(rawUrl: String): URI? {
        val direct = try {
            URI(rawUrl)
        } catch (_: Exception) {
            null
        }
        if (direct?.host?.isNotBlank() == true) {
            return direct
        }

        val lower = rawUrl.lowercase()
        val looksLikeYoutubeWithoutScheme = lower.startsWith("youtube.com/") ||
            lower.startsWith("www.youtube.com/") ||
            lower.startsWith("m.youtube.com/") ||
            lower.startsWith("youtu.be/")

        if (!looksLikeYoutubeWithoutScheme) {
            return direct
        }

        return try {
            URI("https://$rawUrl")
        } catch (_: Exception) {
            direct
        }
    }

    private fun extractQueryParam(query: String?, paramName: String): String? {
        if (query.isNullOrBlank()) {
            return null
        }

        return query.split("&")
            .mapNotNull { keyValue ->
                val parts = keyValue.split("=", limit = 2)
                if (parts.size == 2 && parts[0] == paramName) {
                    parts[1]
                } else {
                    null
                }
            }
            .firstOrNull()
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

/**
 * Data class representing a song from CSV
 */
data class CSVSong(
    val index: Int,  // Index from first column of CSV
    val title: String,
    val artist: String,
    val year: Int? = null,
    val url: String? = null,
    /** True if a correction of the user on this phone lies over the bundled values (see CardOverrides). */
    val corrected: Boolean = false
)
