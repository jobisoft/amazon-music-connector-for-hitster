package de.jobisoft.hitsterconnector.reader

import de.jobisoft.hitsterconnector.util.AmazonId
import de.jobisoft.hitsterconnector.util.CsvLines

/**
 * What the user said about a card. Only "wrong" exists: a card that needs no change is not reported,
 * the next scan goes on. [wire] is the word in the results file and in the log.
 */
enum class Verdict(val wire: String) {
    WRONG("wrong");

    companion object {
        fun fromWire(text: String): Verdict? = entries.firstOrNull { it.wire == text }
    }
}

/** The value of [ResultRow.source] for the reader mode. Rows without the column are from the reader. */
object ReportSource {
    const val READER = "reader"
}

/**
 * One line of `reader_results.csv`, the local log of the reports (only "wrong" ones exist). [cardYear],
 * [cardTitle] and [cardArtist] are what the card says (all three, the unchanged ones equal ours). A row
 * with a year only has no titles: [cardTitle] and [cardArtist] are null there. The album is handled like
 * the Amazon id.
 */
data class ResultRow(
    val time: String,
    val setFile: String,
    val card: Int,
    val verdict: Verdict,
    val ourYear: Int?,
    val ourTitle: String,
    val ourArtist: String,
    val cardYear: Int?,
    val cardTitle: String?,
    val cardArtist: String?,
    /** Where the answer came from: the reader mode, or a card of the game ("scan", "quiz"). */
    val source: String = ReportSource.READER,
    /** Our Amazon track id and the one on the card as the user entered it, "" for none, null if the row has no such column. */
    val ourAmazonId: String? = null,
    val cardAmazonId: String? = null,
    /** Our album and the one on the card as the user entered it, "" for none, null if the row has no such column. */
    val ourAlbum: String? = null,
    val cardAlbum: String? = null
) {
    val key: String get() = ReaderLogic.key(setFile, card)
}

/** What the user entered for a wrong card, validated: all five values, at least one differs from ours. [amazonId] "" means no id, [album] "" means no album. */
data class Edit(val year: Int, val title: String, val artist: String, val amazonId: String = "", val album: String = "")

/** The cards with a correction: the number the screen shows next to the cards scanned in this visit. */
data class Counters(val updated: Int)

/** The logic of the reader mode without Android: CSV lines, the year check, the counters. */
object ReaderLogic {
    /** Said in the reader where the user changes a card: in the overlays "Update manually" and "Add manually" and in the OCR mode. */
    const val UPDATES_NOTE = "Updates stay on this phone. \"Export\" sends them as a file."

    const val RESULT_HEADER = "time,set,card,verdict,ourYear,ourTitle,ourArtist,cardYear,cardTitle,cardArtist,source,ourAmazonId,cardAmazonId,ourAlbum,cardAlbum"

    fun key(file: String, card: Int) = "$file#$card"

    fun currentYear(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

    fun parseCsvLine(line: String): List<String> = CsvLines.parse(line)

    fun csvField(value: String): String = CsvLines.field(value)

    /** "1984" is a year, "198", "19840", "abcd", "1899" and next year are not. Returns the year or null. */
    fun validateYear(text: String, currentYear: Int): Int? {
        val t = text.trim()
        if (t.length != 4 || !t.all { it in '0'..'9' }) return null
        val year = t.toInt()
        return if (year in 1900..currentYear) year else null
    }

    /** The line of a row, every field quoted when it needs it (commas and quotes in titles survive). */
    fun resultLine(row: ResultRow): String = CsvLines.line(
        listOf(
            row.time, row.setFile, row.card.toString(), row.verdict.wire,
            row.ourYear?.toString().orEmpty(), row.ourTitle, row.ourArtist,
            row.cardYear?.toString().orEmpty(), row.cardTitle.orEmpty(), row.cardArtist.orEmpty(),
            row.source, row.ourAmazonId.orEmpty(), row.cardAmazonId.orEmpty(),
            row.ourAlbum.orEmpty(), row.cardAlbum.orEmpty()
        )
    )

    /**
     * A line of `reader_results.csv`, null for a header and for lines that are not one. Accepted: 15 fields
     * (with the two ids and the two albums at the end), 13 fields (the ids only), 11 fields (without them), 12 fields (a column `suspect`, 0 or 1,
     * before `source`, it is dropped) and 7 fields (year only).
     */
    fun parseResultLine(line: String): ResultRow? {
        if (line.isBlank() || line.startsWith("time,")) return null
        val f = parseCsvLine(line)
        return when (f.size) {
            11, 12, 13, 15 -> {
                val card = f[2].toIntOrNull() ?: return null
                // only "wrong" is a report, any other word is dropped
                val verdict = Verdict.fromWire(f[3]) ?: return null
                ResultRow(
                    f[0], f[1], card, verdict, f[4].toIntOrNull(), f[5], f[6], f[7].toIntOrNull(),
                    f[8], f[9],
                    (if (f.size == 12) f[11] else f[10]).takeIf { it.isNotBlank() && it != "0" && it != "1" } ?: ReportSource.READER,
                    if (f.size >= 13) f[11] else null, if (f.size >= 13) f[12] else null,
                    if (f.size == 15) f[13] else null, if (f.size == 15) f[14] else null
                )
            }
            7 -> {
                val card = f[2].toIntOrNull() ?: return null
                val verdict = Verdict.fromWire(f[4]) ?: return null
                ResultRow(f[0], f[1], card, verdict, f[3].toIntOrNull(), "", "", f[5].toIntOrNull(), null, null)
            }
            else -> null
        }
    }

    /** Which of year, title, artist, amazonId and album the card says differently. A row with a year only can differ in the year only. */
    fun changed(row: ResultRow): List<String> {
        val list = ArrayList<String>()
        if (row.cardYear != null && row.cardYear != row.ourYear) list.add("year")
        if (row.cardTitle != null && row.cardTitle.trim() != row.ourTitle.trim()) list.add("title")
        if (row.cardArtist != null && row.cardArtist.trim() != row.ourArtist.trim()) list.add("artist")
        if (row.cardAmazonId != null && row.cardAmazonId.trim() != row.ourAmazonId.orEmpty().trim()) list.add("amazonId")
        if (row.cardAlbum != null && row.cardAlbum.trim() != row.ourAlbum.orEmpty().trim()) list.add("album")
        return list
    }

    /**
     * The "Save" rule of the form for a wrong card: the year is four digits from 1900 to this year, title
     * and artist are not empty (after trimming, a line break counts as a space), the Amazon id is empty, a
     * track id or a link with one ([AmazonId.fromInput]), the album is free text (collapsed, empty = no album),
     * and at least one of the five differs from ours.
     * Returns the values to save, or null if the form is not ready.
     */
    fun evaluateEdit(
        yearText: String, title: String, artist: String,
        ourYear: Int?, ourTitle: String, ourArtist: String, currentYear: Int,
        amazonIdText: String = "", ourAmazonId: String? = null,
        albumText: String = "", ourAlbum: String? = null
    ): Edit? {
        val year = validateYear(yearText, currentYear) ?: return null
        val t = collapse(title)
        val a = collapse(artist)
        if (t.isEmpty() || a.isEmpty()) return null
        val id = AmazonId.fromInput(amazonIdText) ?: return null
        val album = collapse(albumText)
        val differs = year != ourYear || t != ourTitle.trim() || a != ourArtist.trim() || id != ourAmazonId.orEmpty() ||
            album != collapse(ourAlbum.orEmpty())
        return if (differs) Edit(year, t, a, id, album) else null
    }

    /** The text under the field for the id when what was entered is no id, null if it is fine (an empty field is fine). */
    fun amazonIdError(text: String): String? = if (AmazonId.fromInput(text) == null) "Not an Amazon track ID" else null

    /** Line breaks, tabs and runs of spaces become one space, the ends are trimmed: no newline reaches the CSV or the search text. */
    fun collapse(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** The last report per card. */
    fun latestAnswers(rows: List<ResultRow>): Map<String, ResultRow> {
        val latest = LinkedHashMap<String, ResultRow>()
        for (row in rows) latest[row.key] = row
        return latest
    }

    /** How many cards have a report (one per card, the last one counts). */
    fun counters(rows: List<ResultRow>): Counters = Counters(latestAnswers(rows).size)

    /** "(card#300)", the number in the shape of the printed card. */
    fun cardNumberText(card: Int): String = "(card#$card)"
}
