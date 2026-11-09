package de.jobisoft.hitsterconnector.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import de.jobisoft.hitsterconnector.util.CardOverride
import de.jobisoft.hitsterconnector.util.CorrectionLogic
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export of the corrections of this phone: `hitster-corrections-<date>.csv` in the cache folder, handed to
 * the Android share sheet through a FileProvider (not exported, the receiving app gets a temporary
 * read permission for this one file). No server, the user sends the file.
 */
object ExportShare {
    /** Where to send it, one short line for the screen. */
    const val WHERE_TO_SEND = "Send the file to the author: GitHub project jobisoft/amazon-music-connector-for-hitster (issue or mail)."

    /** Builds the file and opens the share sheet. False if there is nothing to export or sharing fails. */
    fun share(context: Context, corrections: List<CardOverride>): Boolean {
        if (corrections.isEmpty()) return false
        return try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, CorrectionLogic.exportFileName(date))
            file.writeText(CorrectionLogic.exportContent(corrections))
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Send corrections").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }
}
