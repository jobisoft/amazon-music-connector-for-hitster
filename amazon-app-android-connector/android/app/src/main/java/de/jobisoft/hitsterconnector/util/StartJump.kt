package de.jobisoft.hitsterconnector.util

import kotlin.math.abs

/** The numbers behind "load, pause, seek, play": where songs start and when the playhead counts as arrived. */
object StartJump {
    /** The playhead is at the target if it is within this many seconds of it. */
    const val NEAR_S = 1.5f

    fun near(position: Float, target: Float): Boolean = abs(position - target) <= NEAR_S

    /** A song shorter than the offset plus 30 s starts at a quarter of its length (rounded down), any other song at the offset. */
    fun startFor(durationS: Float, offsetS: Float): Float =
        if (durationS > 0 && durationS < offsetS + 30) kotlin.math.floor(durationS / 4f) else offsetS

    /**
     * Where a song of this length starts for the chosen [position]. Off gives 0 (no move). A fixed offset follows the
     * rule above. The middle is half of the length, and 0 while the length is not known yet (the reactor takes the
     * target again with every report, so it moves to the middle as soon as the length arrives).
     */
    fun startFor(durationS: Float, position: StartPosition): Float = when (position) {
        StartPosition.OFF -> 0f
        StartPosition.MIDDLE -> if (durationS > 0) durationS / 2f else 0f
        else -> startFor(durationS, position.offsetS)
    }
}
