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
    val amazonId: String? = null,
    /** The album of the card at the time of the correction, handled like [amazonId]: "" clears it, null (no album column in the row) keeps the album of the song. */
    val album: String? = null
) {
    val key: String get() = "$setFile#$card"
}

/** `card_overrides.csv` and the export file: lines, the last-wins rule, applying one to a song. */
object CorrectionLogic {
    const val HEADER = "set,card,year,title,artist,time,source,amazonId,album"

    /** In the id column of a row that has an album but keeps the id of the song. It is no id, so it reads back as null. */
    const val KEEP_ID = "-"

    fun line(o: CardOverride): String = CsvLines.line(
        listOf(o.setFile, o.card.toString(), o.year?.toString().orEmpty(), o.title, o.artist, o.time, o.source) +
            when {
                // an album needs the id column in front of it, KEEP_ID stands for "the id of the song stays"
                o.album != null -> listOf(o.amazonId ?: KEEP_ID, o.album)
                // a correction without an id (null) has seven fields
                else -> listOfNotNull(o.amazonId)
            }
    )

    /** A line of the file, null for the header and for lines that are not one. */
    fun parseLine(line: String): CardOverride? {
        if (line.isBlank() || line.startsWith("set,")) return null
        val f = CsvLines.parse(line)
        if (f.size < 7) return null
        val card = f[1].toIntOrNull() ?: return null
        if (f[0].isEmpty()) return null
        // the eighth column is the id, a row without it (or with a text that is no id) keeps the id of the song.
        // The ninth column is the album: a row without it keeps the album of the song, an empty one clears it
        return CardOverride(
            f[0], card, f[2].toIntOrNull(), f[3], f[4], f[5], f[6],
            f.getOrNull(7)?.let { AmazonId.fromInput(it) }, f.getOrNull(8)?.let { it.trim() }
        )
    }

    /** The last row per (set, card), in the order the cards were first corrected. */
    fun lastWins(rows: List<CardOverride>): Map<String, CardOverride> {
        val latest = LinkedHashMap<String, CardOverride>()
        for (row in rows) latest[row.key] = row
        return latest
    }

    /** The song with the corrected values. A correction without a year keeps the year, one without an id column keeps the id of the song, one without an album column keeps the album. */
    fun apply(song: CSVSong, o: CardOverride): CSVSong = song.copy(
        title = o.title, artist = o.artist, year = o.year ?: song.year, corrected = true,
        amazonId = when {
            o.amazonId == null -> song.amazonId
            o.amazonId.isBlank() -> null
            else -> o.amazonId
        },
        album = when {
            o.album == null -> song.album
            o.album.isBlank() -> null
            else -> o.album
        }
    )

    /** The content of `hitster-corrections-<date>.csv`: one line per corrected card, the latest correction. */
    fun exportContent(rows: Collection<CardOverride>): String =
        HEADER + "\n" + lastWins(rows.toList()).values.joinToString("") { line(it) + "\n" }

    fun exportFileName(date: String) = "hitster-corrections-$date.csv"
}
