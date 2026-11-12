package de.jobisoft.hitsterconnector.reader

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jobisoft.hitsterconnector.data.debug.DebugLog
import de.jobisoft.hitsterconnector.data.local.CardOverrides
import de.jobisoft.hitsterconnector.util.CardOverride
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reports of wrong cards, shared by the reader mode and the "Report as wrong" button of the
 * game: `reader_results.csv` in the files of the app (a local log of the verdicts, also in the
 * production build), the counters, and the events to the debug sink (`DebugLog`, invisible in the
 * screens, nothing in the production build). A "wrong" verdict also writes a correction
 * ([CardOverrides]) that lies over the card data at once. Only a report of a wrong card exists.
 */
@Singleton
class ReaderStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val overrides: CardOverrides
) {
    private val resultsFile = File(context.filesDir, "reader_results.csv")
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    private val rows = ArrayList<ResultRow>(loadRows())

    private val _saved = MutableSharedFlow<ResultRow>(extraBufferCapacity = 8)

    /** A report was saved: the screens that show this card refresh it in place ([ReportRefresh]). */
    val saved: SharedFlow<ResultRow> = _saved.asSharedFlow()

    private val _counters = MutableStateFlow(ReaderLogic.counters(rows))
    val counters: StateFlow<Counters> = _counters.asStateFlow()

    init {
        migrateFile()
    }

    private fun loadRows(): List<ResultRow> = try {
        if (resultsFile.exists()) resultsFile.readLines().mapNotNull { ReaderLogic.parseResultLine(it) } else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** A results file of an older version is rewritten in the current format, so that new rows can follow it. */
    private fun migrateFile() {
        try {
            if (!resultsFile.exists()) return
            if (resultsFile.useLines { it.firstOrNull() } == ReaderLogic.RESULT_HEADER) return
            resultsFile.writeText(ReaderLogic.RESULT_HEADER + "\n" + rows.joinToString("") { ReaderLogic.resultLine(it) + "\n" })
        } catch (e: Exception) {
            DebugLog.log("reader", "reader results file migration failed", mapOf("error" to e))
        }
    }

    /** The corrections on this phone, for the export. */
    fun corrections(): List<CardOverride> = overrides.all()

    /**
     * Records a report of a wrong card: the counter, the line in the file, the event `reader verdict`, and
     * the correction ([CardOverrides]) that lies over the card data at once. The file is written on the
     * calling thread, a line is small.
     */
    fun recordWrong(
        setFile: String, card: Int, ourYear: Int?, ourTitle: String, ourArtist: String, edit: Edit, source: String,
        ourAmazonId: String? = null, ourAlbum: String? = null
    ): ResultRow {
        val time = timeFormat.format(Date())
        val row = ResultRow(
            time, setFile, card, Verdict.WRONG, ourYear, ourTitle, ourArtist,
            edit.year, edit.title, edit.artist, source, ourAmazonId.orEmpty(), edit.amazonId,
            ourAlbum.orEmpty(), edit.album
        )
        synchronized(rows) {
            rows.add(row)
            _counters.value = ReaderLogic.counters(rows)
            try {
                if (!resultsFile.exists()) resultsFile.writeText(ReaderLogic.RESULT_HEADER + "\n")
                resultsFile.appendText(ReaderLogic.resultLine(row) + "\n")
            } catch (e: Exception) {
                DebugLog.log("reader", "reader results file failed", mapOf("error" to e))
            }
        }
        overrides.add(CardOverride(setFile, card, edit.year, edit.title, edit.artist, time, source, edit.amazonId, edit.album))
        _saved.tryEmit(row)
        DebugLog.log("reader", "reader verdict", eventData(row))
        return row
    }

    /** The event of a row. `title`, `artist` and `yearOnCard` repeat our values and the card year under the field names that tools/reader_summary.py reads. */
    fun eventData(row: ResultRow, ourTitle: String = row.ourTitle, ourArtist: String = row.ourArtist): Map<String, Any?> = mapOf(
        "set" to row.setFile, "card" to row.card, "verdict" to row.verdict.wire,
        "ok" to false,
        "ourYear" to row.ourYear, "ourTitle" to ourTitle, "ourArtist" to ourArtist,
        "cardYear" to row.cardYear, "cardTitle" to row.cardTitle, "cardArtist" to row.cardArtist,
        "ourAmazonId" to row.ourAmazonId, "cardAmazonId" to row.cardAmazonId,
        "ourAlbum" to row.ourAlbum, "cardAlbum" to row.cardAlbum,
        "changed" to ReaderLogic.changed(row), "source" to row.source,
        "yearOnCard" to row.cardYear, "title" to ourTitle, "artist" to ourArtist,
        "time" to row.time
    )
}
