package de.jobisoft.hitsterconnector.util

import kotlin.math.abs

/** The numbers behind "load, pause, seek, play": when the playhead counts as arrived, and how much of the intro leaked. */
object StartJump {
    /** The playhead is at the target if it is within this many seconds of it. */
    const val NEAR_S = 1.5f

    fun near(position: Float, target: Float): Boolean = abs(position - target) <= NEAR_S

    /**
     * The furthest the playhead was seen before the jump, from samples taken while the song
     * played or was loaded. Samples at the target do not count, those are after the jump.
     * 0 if nothing was seen before it, then nothing of the intro was heard.
     */
    fun leakedSeconds(samples: List<Float>, target: Float): Float =
        samples.filter { it < target - NEAR_S }.maxOrNull() ?: 0f
}
