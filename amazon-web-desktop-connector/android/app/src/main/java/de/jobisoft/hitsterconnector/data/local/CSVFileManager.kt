package de.jobisoft.hitsterconnector.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import de.jobisoft.hitsterconnector.util.CSVUrlParser
import java.io.File
import java.io.FileWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages local storage of CSV files
 */
@Singleton
class CSVFileManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val csvDir: File by lazy {
        File(context.filesDir, "csv_files").apply {
            if (!exists()) {
                mkdirs()
            }
            seedBundledCardSets(this)
        }
    }

    /**
     * Copy the card sets that come with the app into the storage, once. A set the user
     * deletes later stays deleted. The sets are a snapshot of songseeker-hitster-playlists,
     * the "add all" button of the card config still loads the current ones.
     */
    private fun seedBundledCardSets(dir: File) {
        val marker = File(context.filesDir, ".bundled_card_sets_v2")
        if (marker.exists()) return
        try {
            for (name in context.assets.list("csv").orEmpty().filter { it.endsWith(".csv") }) {
                val target = File(dir, name)
                if (!target.exists()) {
                    context.assets.open("csv/$name").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            marker.writeText("1")
        } catch (_: Exception) {
            // Nothing is lost, the import button of the card config still works.
        }
    }

    /**
     * Get the filename from a URL or use the URL itself as identifier
     */
    private fun getFileName(csvUrlOrName: String): String {
        return if (csvUrlOrName.startsWith("http://", ignoreCase = true) || 
                   csvUrlOrName.startsWith("https://", ignoreCase = true)) {
            // Extract filename from URL or use a hash
            val fileName = csvUrlOrName.substringAfterLast("/")
            if (fileName.contains(".")) {
                fileName
            } else {
                "${csvUrlOrName.hashCode()}.csv"
            }
        } else {
            // It's already a filename
            csvUrlOrName
        }
    }

    /**
     * Get the full URL for a CSV file (converts filename to GitHub URL if needed)
     */
    private fun getFullUrl(csvUrlOrName: String): String {
        return if (csvUrlOrName.startsWith("http://", ignoreCase = true) || 
                   csvUrlOrName.startsWith("https://", ignoreCase = true)) {
            csvUrlOrName
        } else {
            // Convert filename to GitHub raw URL
            CSVUrlParser.toGitHubRawUrl(csvUrlOrName)
        }
    }

    /**
     * Download [SONGSEEKER_PLAYLIST_INDEX_URL] and return the first-column CSV filenames (skips header row).
     */
    suspend fun fetchPlaylistIndexFilenames(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(CSVUrlParser.SONGSEEKER_PLAYLIST_INDEX_URL)
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to download playlist index: ${response.code}")
                )
            }
            var body = response.body.string()
            if (body.startsWith("\uFEFF")) {
                body = body.substring(1)
            }
            val names = mutableListOf<String>()
            var isFirstDataLine = true
            for (rawLine in body.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val firstField = parseFirstCsvField(line) ?: continue
                val fileName = firstField.trim()
                if (isFirstDataLine) {
                    isFirstDataLine = false
                    if (fileName.equals("File", ignoreCase = true)) continue
                }
                if (fileName.endsWith(".csv", ignoreCase = true)) {
                    names.add(fileName)
                }
            }
            Result.success(names)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseFirstCsvField(line: String): String? {
        if (line.isEmpty()) return null
        return if (line.first() == '"') {
            val end = line.indexOf('"', startIndex = 1)
            if (end <= 0) null else line.substring(1, end).trim().ifEmpty { null }
        } else {
            line.substringBefore(',').trim().ifEmpty { null }
        }
    }

    /**
     * Download and save a CSV file locally
     */
    suspend fun downloadAndSave(csvUrlOrName: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            val fileName = getFileName(csvUrlOrName)
            val file = File(csvDir, fileName)
            val fullUrl = getFullUrl(csvUrlOrName)

            val request = Request.Builder()
                .url(fullUrl)
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Failed to download CSV: ${response.code}"))
            }

            val csvContent = response.body.string()

            FileWriter(file).use { writer ->
                writer.write(csvContent)
            }

            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get the local file for a CSV (returns null if file doesn't exist)
     */
    fun getLocalFile(csvUrlOrName: String): File? {
        val fileName = getFileName(csvUrlOrName)
        val file = File(csvDir, fileName)
        return if (file.exists()) file else null
    }

    /**
     * Check if a CSV file exists locally
     */
    fun exists(csvUrlOrName: String): Boolean {
        return getLocalFile(csvUrlOrName) != null
    }

    /**
     * Delete a CSV file from local storage
     */
    suspend fun delete(csvUrlOrName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = getLocalFile(csvUrlOrName)
            if (file != null && file.delete()) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("File not found or could not be deleted"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get all locally stored CSV files
     */
    fun getAllLocalFiles(): List<File> {
        return csvDir.listFiles()?.filter { it.extension == "csv" } ?: emptyList()
    }

    /**
     * Delete all CSV files
     */
    suspend fun deleteAll(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            getAllLocalFiles().forEach { it.delete() }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
