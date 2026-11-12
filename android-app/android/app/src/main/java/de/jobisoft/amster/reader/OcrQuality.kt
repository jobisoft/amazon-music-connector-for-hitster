package de.jobisoft.amster.reader

/** Is a picture sharp enough to be read? The camera moves, focuses and blurs. */
object OcrQuality {
    /**
     * A picture below this value is blurred. Measured with the frames of a phone: the blurred ones were 4 to 40, the sharp ones 150 to 700.
     */
    const val MIN_SHARPNESS = 100.0

    /** The variance of the Laplacian of [gray] (one value 0 to 255 per pixel, row by row): small for a blurred picture, large for a sharp one. */
    fun sharpness(gray: IntArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3 || gray.size < width * height) return 0.0
        var sum = 0.0
        var sumSquares = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val laplacian = 4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - width] - gray[i + width]
                sum += laplacian
                sumSquares += laplacian.toDouble() * laplacian
                count++
            }
        }
        val mean = sum / count
        return sumSquares / count - mean * mean
    }
}

/**
 * Many readings of the same pictures vote: every reading that found a card is a vote. The year, the artist and the title are decided one by one
 * (a letter wrong in the title does not spoil the year), each by the most frequent value, with the letters and digits compared only.
 * The OCR often drops the dots of an umlaut ("Männer" becomes "Manner"), but it never invents them. So the readings that differ only in the
 * accents are one group, and in the winning group the spelling with accents is taken.
 */
object OcrVote {
    /** A field needs at least this many equal votes. */
    const val MIN_VOTES = 2

    /** The card of the most frequent values, or null if a field has too few equal votes. */
    fun decide(cards: List<OcrCard>): OcrCard? {
        if (cards.isEmpty()) return null
        val year = mostFrequent(cards.map { it.year.toString() to it.year.toString() })?.toIntOrNull() ?: return null
        val artist = mostFrequent(cards.map { key(it.artist) to it.artist }) ?: return null
        val title = mostFrequent(cards.map { key(it.title) to it.title }) ?: return null
        return OcrCard(artist, year, title)
    }

    private fun mostFrequent(votes: List<Pair<String, String>>): String? {
        val counts = LinkedHashMap<String, Int>()
        for ((k, _) in votes) counts[k] = (counts[k] ?: 0) + 1
        val best = counts.maxByOrNull { it.value } ?: return null
        if (best.value < MIN_VOTES) return null
        val spellings = votes.filter { it.first == best.key }.map { it.second }
        // the spelling with accents wins, then the most frequent one
        val withAccents = spellings.filter { hasAccent(it) }
        val pool = withAccents.ifEmpty { spellings }
        return pool.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }

    private fun hasAccent(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).any { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }

    /**
     * Letters and digits, lower case, without accents. The engines read a capital I as a small l ("lch + lch" for "Ich + Ich") and the other way
     * round, so l, 1 and I count as the same letter here (only for finding the group, the shown spelling is a real reading).
     */
    private fun key(s: String) =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).filter { it.isLetterOrDigit() }
            .map { if (it == 'l' || it == '1') 'i' else it }.joinToString("")
}

/** How many good pictures of the card are collected before the analysis starts. */
object OcrCapture {
    const val NEEDED = 2
}

/** Cards are held in any direction. The engine tells the direction of the text of every line, the picture is turned until the text is upright. */
object OcrOrientation {
    /** A line needs a direction within this many degrees of a quarter turn, else the direction is not trusted (a crooked card stays crooked). */
    private const val TOLERANCE = 35.0

    /**
     * How many degrees to turn the picture clockwise to make the text upright: 0, 90, 180 or 270. [angles] are the directions of the lines (0 is
     * upright, 90 is text that reads from the top to the bottom). The median of the lines decides, so one line read wrongly does not turn the picture.
     */
    fun rotationFor(angles: List<Double>): Int {
        if (angles.isEmpty()) return 0
        // the directions are on a circle: take the median of the unit vectors by summing them
        val x = angles.sumOf { Math.cos(Math.toRadians(it)) }
        val y = angles.sumOf { Math.sin(Math.toRadians(it)) }
        val mean = Math.toDegrees(Math.atan2(y, x))
        val quarter = Math.round(mean / 90.0).toInt()
        if (Math.abs(mean - quarter * 90.0) > TOLERANCE) return 0
        // text turned by +quarter quarters clockwise is made upright by turning the picture back
        return ((-quarter * 90) % 360 + 360) % 360
    }
}
