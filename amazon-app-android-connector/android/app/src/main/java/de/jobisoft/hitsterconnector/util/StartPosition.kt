package de.jobisoft.hitsterconnector.util

/**
 * Where a card song starts after it loaded: not moved at all ([OFF]), at a fixed offset ([S10], [S20], [S30]) or in
 * the middle of its length ([MIDDLE]). [key] is what is stored, [label] what the Options screen shows.
 */
enum class StartPosition(val key: String, val label: String, val offsetS: Float) {
    OFF("off", "Off", 0f),
    S10("10", "10 s", 10f),
    S20("20", "20 s", 20f),
    S30("30", "30 s", 30f),
    MIDDLE("middle", "Middle of the song", 0f);

    companion object {
        val DEFAULT = S20

        /** The position of a stored key, the default for anything unknown. */
        fun fromKey(key: String?): StartPosition = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
