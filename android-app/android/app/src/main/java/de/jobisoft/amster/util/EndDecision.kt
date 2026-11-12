package de.jobisoft.amster.util

import android.media.session.PlaybackState

/** Where does the end of a song begin, and when is it too short to have one? */
object EndDecision {
    /**
     * The end zone: the last seconds of a song. The song is paused when its playhead reaches it, and
     * nothing is allowed to play inside it. Change this one value to move the pause.
     */
    const val END_ZONE_S = 1.0f

    /** A length below this counts as unknown, then the end is not guessed. */
    const val MIN_DURATION_S = 3f

    /**
     * Amazon may move on (another queue item, or a stop) a little before the timer of the end runs out. A
     * switch or a stop within the last seconds of the song is its end as well.
     */
    const val SWITCH_END_WINDOW_S = 5.0f

    /**
     * A song that was paused for its end counts as not ended any more once the playhead is this far in
     * front of the end zone (someone moved it back). Inside this margin a pause that landed a little
     * early does not undo itself.
     */
    const val REARM_MARGIN_S = 1.0f

    /** True if the playhead is in the end zone of a song with a known length. */
    fun inEndZone(positionS: Float, durationS: Float): Boolean =
        durationS >= MIN_DURATION_S && positionS >= durationS - END_ZONE_S
}

/** When has a running song to be paused for its end? Pure, so the timer arithmetic is tested. */
object EndPlanner {
    /**
     * Milliseconds from [nowMs] until the playhead reaches the end zone, for a report taken from the session:
     * the position [positionMs] was valid at [updatedAtMs] (same clock as [nowMs]) and moves with [speed].
     * Null: no timer, because nothing plays, the speed is not positive, or the length is unknown or too short.
     * Zero: the playhead is in the end zone already.
     */
    fun delayUntilPauseMs(
        state: Int,
        positionMs: Long,
        updatedAtMs: Long,
        speed: Float,
        durationMs: Long,
        nowMs: Long,
        zoneS: Float = EndDecision.END_ZONE_S
    ): Long? {
        if (state != PlaybackState.STATE_PLAYING || speed <= 0f) return null
        if (durationMs < (EndDecision.MIN_DURATION_S * 1000).toLong()) return null
        val elapsed = if (updatedAtMs > 0) (nowMs - updatedAtMs).coerceAtLeast(0L) else 0L
        val position = positionMs + elapsed * speed
        val remaining = (durationMs - zoneS * 1000f - position) / speed
        return if (remaining <= 0f) 0L else Math.ceil(remaining.toDouble()).toLong()
    }

    /** The same for a [SessionSnapshot]. */
    fun delayUntilPauseMs(snapshot: SessionSnapshot, nowMs: Long): Long? = delayUntilPauseMs(
        snapshot.state, snapshot.positionMs, snapshot.updatedAtMs, snapshot.speed, snapshot.durationMs, nowMs
    )
}
