package de.jobisoft.hitsterconnector.util

import de.jobisoft.hitsterconnector.data.repository.CSVSong

/** A correction made on this phone: the values the card shows. Last one per (set, card) wins. */
data class CardOverride(
    val setFile: String,
    val card: Int,
    val year: Int?,
    val title: String,
    val artist: String,
    val time: String,
    val source: String,
    /** The Amazon track id of the card at the time of the correction, "" for none, null if the file has no such column (then the id of the song stays). */
    val amazonId: String? = null
) {
    val key: String get() = "$setFile#$card"
}

/** `card_overrides.csv` and the export file: lines, the last-wins rule, applying one to a song. */
object CorrectionLogic {
    const val HEADER = "set,card,year,title,artist,time,source,amazonId"

    fun line(o: CardOverride): String = CsvLines.line(
        listOf(o.setFile, o.card.toString(), o.year?.toString().orEmpty(), o.title, o.artist, o.time, o.source) +
            listOfNotNull(o.amazonId) // a correction without an id (null) has seven fields
    )

    /** A line of the file, null for the header and for lines that are not one. */
    fun parseLine(line: String): CardOverride? {
        if (line.isBlank() || line.startsWith("set,")) return null
        val f = CsvLines.parse(line)
        if (f.size < 7) return null
        val card = f[1].toIntOrNull() ?: return null
        if (f[0].isEmpty()) return null
        // the eighth column is the id, a row without it (or with a text that is no id) keeps the id of the song
        return CardOverride(f[0], card, f[2].toIntOrNull(), f[3], f[4], f[5], f[6], f.getOrNull(7)?.let { AmazonId.fromInput(it) })
    }

    /** The last row per (set, card), in the order the cards were first corrected. */
    fun lastWins(rows: List<CardOverride>): Map<String, CardOverride> {
        val latest = LinkedHashMap<String, CardOverride>()
        for (row in rows) latest[row.key] = row
        return latest
    }

    /** The song with the corrected values. A correction without a year keeps the year, one without an id column keeps the id of the song. */
    fun apply(song: CSVSong, o: CardOverride): CSVSong = song.copy(
        title = o.title, artist = o.artist, year = o.year ?: song.year, corrected = true,
        amazonId = when {
            o.amazonId == null -> song.amazonId
            o.amazonId.isBlank() -> null
            else -> o.amazonId
        }
    )

    /** The content of `hitster-corrections-<date>.csv`: one line per corrected card, the latest correction. */
    fun exportContent(rows: Collection<CardOverride>): String =
        HEADER + "\n" + lastWins(rows.toList()).values.joinToString("") { line(it) + "\n" }

    fun exportFileName(date: String) = "hitster-corrections-$date.csv"
}
