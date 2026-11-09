package de.jobisoft.hitsterconnector.util

/** When does the song get muted for its end? */
object EndDecision {
    /** A length below this counts as unknown, then the end is not guessed. */
    const val MIN_DURATION_S = 3f

    /**
     * True once, one second (the window) before the true end of a song that plays. No pause, only the mute.
     * Not when it was done already for this song, not when the player is paused, not without a known length.
     */
    fun shouldMuteAtEnd(alreadyMuted: Boolean, playing: Boolean, durationS: Float, positionS: Float, windowS: Float): Boolean =
        !alreadyMuted && playing && durationS >= MIN_DURATION_S && durationS - positionS <= windowS
}

enum class MutePhase {
    /** From before the request until the new song plays at its start offset. */
    LOAD,

    /** From one second before the end of a song until the next card has jumped to its start offset. */
    END
}

/** How long a mute of ours may last at the most, the timer unmutes by itself. */
object MuteHold {
    const val LOAD_MS = 12_000L
    const val END_MS = 30 * 60_000L

    fun holdMs(phase: MutePhase): Long = if (phase == MutePhase.LOAD) LOAD_MS else END_MS
}

/**
 * The state of the end guard without Android, to test what it must and must not do. The one rule
 * that was the bug: **the guard never disarms itself**, not after the end either.
 */
class GuardModel {
    var watching = false
        private set
    var endMuted = false
        private set
    var songEnded = false
        private set

    /** Starts watching a song (or starts again after a seek). Returns true if the song had been muted for its end. */
    fun arm(): Boolean {
        val wasEndMuted = endMuted
        watching = true
        endMuted = false
        songEnded = false
        return wasEndMuted
    }

    /** A new request, an explicit stop, the screen left, the watch timeout. Returns false if nothing was watched. */
    fun disarm(): Boolean {
        if (!watching) return false
        watching = false
        endMuted = false
        songEnded = false
        return true
    }

    /** One look of the ticker. True: mute the stream now. The model stays watching. */
    fun onTick(playing: Boolean, durationS: Float, positionS: Float, windowS: Float): Boolean {
        if (!watching) return false
        if (!EndDecision.shouldMuteAtEnd(endMuted, playing, durationS, positionS, windowS)) return false
        endMuted = true
        songEnded = true
        return true
    }
}
