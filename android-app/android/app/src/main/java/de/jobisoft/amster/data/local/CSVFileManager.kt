package de.jobisoft.amster.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import de.jobisoft.amster.util.CardSetSeeding
import de.jobisoft.amster.util.SeedAction
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages local storage of CSV files
 */
@Singleton
class CSVFileManager @Inject constructor(
    @param:ApplicationContext private val context: Context
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
     * Copy the card sets that come with the app into the storage, see [CardSetSeeding]. They are
     * app-owned and refreshed when the bundled file changes (the phone keeps per file the hash of the
     * copy it seeded last), a file the user deleted stays deleted, files with other names are never
     * touched. The corrections of the user are in `card_overrides.csv`, a file of their own.
     */
    private fun seedBundledCardSets(dir: File) {
        try {
            val prefs = context.getSharedPreferences("seeded_card_sets", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            for (name in context.assets.list("card-data").orEmpty().filter { it.endsWith(".csv") }) {
                val bytes = context.assets.open("card-data/$name").use { it.readBytes() }
                val target = File(dir, name)
                val decision = CardSetSeeding.decide(CardSetSeeding.sha256(bytes), target.exists(), prefs.getString(name, null))
                if (decision.action != SeedAction.LEAVE) target.writeBytes(bytes)
                decision.record?.let { editor.putString(name, it) }
            }
            editor.commit()
            // a marker file of an earlier app version is removed
            File(context.filesDir, ".bundled_card_sets_v2").delete()
        } catch (_: Exception) {
            // Nothing is lost, the import in the card config still works.
        }
    }

    /** The file name of a card set, a path in front of it is dropped. */
    private fun getFileName(csvUrlOrName: String): String =
        csvUrlOrName.substringAfterLast("/").substringAfterLast("\\")

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
}
