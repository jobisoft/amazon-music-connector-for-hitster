package de.jobisoft.amster.reader

/**
 * A line of text that the OCR found, [height] is the height of its letters in pixels (0 if unknown). [left], [top], [right] and [bottom]
 * are its box in the picture (all 0 if unknown).
 */
data class OcrLine(
    val text: String, val height: Int = 0, val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0,
    /** The direction of the text in degrees, 0 for upright, 180 for upside down (0 if the engine does not tell). */
    val angle: Double = 0.0
) {
    val hasBox: Boolean get() = right > left && bottom > top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/** What the back of a card says. */
data class OcrCard(val artist: String, val year: Int, val title: String)

/**
 * Reads artist, year and title from the lines of the back of a Hitster card. The back shows the artist at
 * the top, the year in the middle (big) and the title at the bottom. The lines above the year are the artist,
 * the lines below it are the title. Without a year, or without text on one of the two sides, there is no card.
 * This is the one place to adapt if the layout of a card back is different.
 */
/** What [OcrCardParser.parseDetailed] found: the card and the lines it is made of (their boxes tell where the card is on the picture). */
data class OcrParsed(val card: OcrCard, val artistLines: List<OcrLine>, val yearLine: OcrLine, val titleLines: List<OcrLine>) {
    val allLines: List<OcrLine> get() = artistLines + yearLine + titleLines
}

object OcrCardParser {
    const val MIN_YEAR = 1900
    const val MAX_YEAR = 2099

    /** A line needs at least this many letters or digits ("U2" is the shortest artist; dashes, bars and single letters are noise). */
    const val MIN_CHARS = 2

    /** A line smaller than this share of the biggest text line is not the artist or the title (the number of the card is such a line). */
    const val MIN_SHARE_OF_BIGGEST = 0.6

    /** A line smaller than this share of the median text height is a speck, not text. */
    const val MIN_RELATIVE_HEIGHT = 0.45

    /** Letters the OCR often reads instead of a digit, used only for a short line that is mostly digits. */
    private val LOOKALIKES = mapOf('O' to '0', 'o' to '0', 'D' to '0', 'l' to '1', 'I' to '1', '|' to '1', 'S' to '5', 'B' to '8', 'Z' to '2')

    fun parse(lines: List<OcrLine>): OcrCard? = parseDetailed(lines)?.card

    fun parseDetailed(lines: List<OcrLine>): OcrParsed? {
        val clean = withoutNoise(
            lines.map { it.copy(text = it.text.trim().replace(Regex("\\s+"), " ")) }
                .filter { line -> line.text.count { it.isLetterOrDigit() } >= MIN_CHARS }
        )
        val candidates = clean.mapIndexedNotNull { i, line -> yearOf(line.text)?.let { Triple(i, line, it) } }
        if (candidates.isEmpty()) return null
        // the biggest letters are the year; with the same size the one nearest to the middle
        val middle = (clean.size - 1) / 2.0
        val best = candidates.sortedWith(
            compareByDescending<Triple<Int, OcrLine, Int>> { it.second.height }.thenBy { kotlin.math.abs(it.first - middle) }
        ).first()
        val yearLine = best.second
        val above = clean.subList(0, best.first)
        val below = clean.subList(best.first + 1, clean.size)
        val artistLines: List<OcrLine>
        val titleLines: List<OcrLine>
        if (yearLine.hasBox && clean.all { it.hasBox }) {
            // the card is in the middle of a picture with a table, a poster, other cards around it: only the lines next to the year count
            artistLines = near(above.asReversed(), yearLine).asReversed()
            titleLines = near(below, yearLine)
        } else {
            artistLines = above
            titleLines = below
        }
        val artist = artistLines.joinToString(" ") { it.text }.trim()
        val title = titleLines.joinToString(" ") { it.text }.trim()
        if (artist.isEmpty() || title.isEmpty()) return null
        return OcrParsed(OcrCard(artist, best.third, title), artistLines, yearLine, titleLines)
    }

    /** The picture must leave this share of its side free around the text of the card, else the card may be cut off. */
    const val EDGE_MARGIN_SHARE = 0.02

    /** The gap from the year to the artist and the gap from the year to the title are about equal on a card. */
    const val MAX_GAP_RATIO = 2.2

    /**
     * Is the whole back of the card on the picture? The lines of the card (artist above the year, title below it) have boxes, all of them
     * lie inside the picture with a margin, and the gaps above and below the year are about the same. A card whose title is cut at the
     * edge of the picture, or held crooked so that one side is out of the frame, fails. This looks at the text, not at the outline of the card.
     */
    fun isComplete(parsed: OcrParsed, width: Int, height: Int): Boolean {
        val lines = parsed.allLines
        if (width <= 0 || height <= 0 || lines.any { !it.hasBox }) return false
        val mx = (width * EDGE_MARGIN_SHARE).toInt()
        val my = (height * EDGE_MARGIN_SHARE).toInt()
        if (lines.any { it.left < mx || it.top < my || it.right > width - mx || it.bottom > height - my }) return false
        val gapAbove = parsed.yearLine.top - parsed.artistLines.maxOf { it.bottom }
        val gapBelow = parsed.titleLines.minOf { it.top } - parsed.yearLine.bottom
        if (gapAbove <= 0 || gapBelow <= 0) return false
        val ratio = maxOf(gapAbove, gapBelow).toDouble() / minOf(gapAbove, gapBelow)
        return ratio <= MAX_GAP_RATIO
    }

    /** How far from the year a line of the card can be, in heights of the year. */
    const val MAX_DISTANCE_IN_YEAR_HEIGHTS = 2.6

    /** How far the middle of a line may be from the middle of the year, in widths of the year. */
    const val MAX_OFF_CENTER_IN_YEAR_WIDTHS = 0.75

    /** The next line of the same block follows within this many heights of the line. */
    const val BLOCK_GAP_IN_LINE_HEIGHTS = 1.8

    /**
     * The lines of the card that are next to the year, [fromNearest] is the lines on one side, the nearest to the year first: those that are
     * near enough and centered like the year (the text of a card is centered), and further lines of the same block. A line far away or off
     * to the side is a part of the picture around the card.
     */
    internal fun near(fromNearest: List<OcrLine>, year: OcrLine): List<OcrLine> {
        val yearHeight = maxOf(year.bottom - year.top, 1)
        val maxDistance = yearHeight * MAX_DISTANCE_IN_YEAR_HEIGHTS
        val maxOffCenter = maxOf(year.right - year.left, 1) * MAX_OFF_CENTER_IN_YEAR_WIDTHS
        val result = ArrayList<OcrLine>()
        var last = year
        for (line in fromNearest) {
            val gap = if (line.centerY < year.centerY) last.top - line.bottom else line.top - last.bottom
            val limit = if (result.isEmpty()) maxDistance else maxOf(line.bottom - line.top, 1) * BLOCK_GAP_IN_LINE_HEIGHTS
            if (gap > limit || kotlin.math.abs(line.centerX - year.centerX) > maxOffCenter) break
            result.add(line)
            last = line
            if (result.size >= 3) break
        }
        return result
    }

    /**
     * Drops the specks the OCR sees on the card: lines whose letters are much smaller than the text of the card
     * (less than [MIN_RELATIVE_HEIGHT] of the median height of the lines that are no year). The year is bigger than the text, so it stays.
     */
    internal fun withoutNoise(lines: List<OcrLine>): List<OcrLine> {
        val heights = lines.filter { yearOf(it.text) == null && it.height > 0 }.map { it.height }.sorted()
        if (heights.isEmpty()) return lines
        val median = heights[heights.size / 2]
        // the number of the card and the code of the set stand small in the corners of the back (half the height of the title)
        val biggest = heights.last()
        val limit = maxOf(median * MIN_RELATIVE_HEIGHT, biggest * MIN_SHARE_OF_BIGGEST)
        return lines.filter { it.height == 0 || it.height >= limit }
    }

    /** The year if the whole line is one (a little noise around it is fine), else null. */
    internal fun yearOf(text: String): Int? {
        val core = text.trim().trim('.', ',', ':', ';', '-', '*', '\'', '"', '|', ' ')
        val digits = core.map { c -> if (c.isDigit()) c else LOOKALIKES[c] ?: return null }.joinToString("")
        if (digits.length != 4) return null
        // a short line of letters that merely look like digits ("SOS", "BOZO") is no year: at least two real digits
        if (core.count { it.isDigit() } < 2) return null
        return digits.toIntOrNull()?.takeIf { it in MIN_YEAR..MAX_YEAR }
    }
}

/**
 * A card counts only when it was read twice within the last [WINDOW] reads, so a single misread frame is never saved. Reads in
 * between that have noise or no card at all do not spoil it (the camera shakes, a speck lands in the title).
 */
class OcrStability {
    private val recent = ArrayDeque<OcrCard?>()

    /** Feeds a read; returns the card once an equal read is among the previous ones, else null. A read without a card takes a place too. */
    fun feed(card: OcrCard?): OcrCard? {
        val match = card != null && recent.any { it != null && same(it, card) }
        recent.addLast(card)
        while (recent.size > WINDOW) recent.removeFirst()
        return if (match) card else null
    }

    fun reset() {
        recent.clear()
    }

    companion object {
        const val WINDOW = 4
    }

    private fun same(a: OcrCard, b: OcrCard) = a.year == b.year && key(a.artist) == key(b.artist) && key(a.title) == key(b.title)

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
