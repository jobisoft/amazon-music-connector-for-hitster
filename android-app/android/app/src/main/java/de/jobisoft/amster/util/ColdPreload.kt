package de.jobisoft.amster.util

import android.media.session.PlaybackState
import de.jobisoft.amster.util.PlayFlow.Item

/**
 * The pre-load of a cold start. Amazon Music that was just woken has an empty session (state NONE). A card loaded
 * into it is replaced a moment later by the old queue of Amazon, which plays another song (the "bad boy"). So the
 * card is loaded once for nothing with the volume at 0, the app watches until another song plays or [OBSERVE_MS]
 * are over, pauses, and then loads the card for real. The end guard has nothing to do with the pre-load.
 */
object ColdPreload {
    /** The longest time the pre-load watches. The screen counts down from it. */
    const val OBSERVE_MS = 15_000L

    /** The volume timeout during the pre-load. It must be longer than [OBSERVE_MS]. */
    const val DUCK_MAX_MS = 20_000L

    /** True if the pre-load is needed: the session of Amazon Music is empty. */
    fun needed(state: Int?): Boolean = state == null || state == PlaybackState.STATE_NONE

    /**
     * The bad boy: another song than ours plays. Our own song that plays is ignored on purpose, the pre-load wants to see
     * the replacement.
     */
    fun isBadBoy(state: Int, item: Item): Boolean = state == PlaybackState.STATE_PLAYING && item == Item.FOREIGN

    /** The whole seconds left for the countdown, rounded up: 15 at the start, 1 for the last second. */
    fun secondsLeft(startedAtMs: Long, nowMs: Long): Int =
        ((OBSERVE_MS - (nowMs - startedAtMs)).coerceAtLeast(0L) + 999L).div(1000L).toInt()

    fun over(startedAtMs: Long, nowMs: Long): Boolean = nowMs - startedAtMs >= OBSERVE_MS
}
