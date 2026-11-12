package de.jobisoft.amster.reader

/** What the reader makes of a scanned code that it cannot show. */
enum class UnknownCardKind {
    /** The code is no Hitster card at all. */
    NOT_HITSTER,

    /** The card is from a set that is not on this phone (no file, no corrections). The reader asks for its name. */
    NEW_EDITION,

    /** The set is known, the card is not in it. The reader reads the back of the card. */
    NEW_CARD
}

/** Where the OCR stands. */
enum class OcrPhase {
    /** Collecting sharp pictures with the whole card on them. */
    WAITING,

    /** The pictures are being read with several methods (the "Analysing" screen). */
    ANALYSING,

    /** Nothing good came for a long time (the mode goes on, the screen says so). */
    FAILED
}

/** The card to be added or read again, and the name of its set if the user just gave one. */
data class PendingCard(val setFile: String, val number: Int, val edition: String? = null)

/** An unknown card on the screen: [setKnown] is false for a card of a set the app does not know (then it asks for the name first). */
data class UnknownCard(val pending: PendingCard, val setKnown: Boolean, val setName: String)

/** The state of the OCR page: what is collected and analysed so far. */
data class OcrState(
    val pending: PendingCard,
    val phase: OcrPhase = OcrPhase.WAITING,
    val startedAt: Long = 0L,
    /** Pictures of the camera that were sharp enough to be read. */
    val scans: Int = 0,
    /** Good pictures collected so far (of [OcrCapture.NEEDED]). */
    val captured: Int = 0,
    /** In the analysis: readings done of readings planned. */
    val analysed: Int = 0,
    val planned: Int = 0
)

object OcrFlow {
    /** One frame for the OCR at most this often. */
    const val FRAME_EVERY_MS = 1_000L

    /**
     * What a scanned code is. [hasInfo]: the code is a Hitster card URL (set file and number found). [cardFound]: the card is known.
     * [setKnown]: the set is on this phone (its file is installed) or has a name from corrections. Null when the card is known.
     */
    fun kind(hasInfo: Boolean, cardFound: Boolean, setKnown: Boolean): UnknownCardKind? = when {
        !hasInfo -> UnknownCardKind.NOT_HITSTER
        cardFound -> null
        setKnown -> UnknownCardKind.NEW_CARD
        else -> UnknownCardKind.NEW_EDITION
    }
}
