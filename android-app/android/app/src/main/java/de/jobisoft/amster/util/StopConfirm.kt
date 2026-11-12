package de.jobisoft.amster.util

import android.media.session.PlaybackState

/**
 * The states of a player that is stopped or must be stopped, without Android, to test the rules. The song identity and the end
 * guard use them.
 */
object StopConfirm {
    /** A player in these states has to be paused first. */
    fun needsStop(state: Int?): Boolean =
        state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING

    /** These states (or no session at all) confirm that nothing plays. */
    fun isStopped(state: Int?): Boolean =
        state == null || state == PlaybackState.STATE_PAUSED ||
            state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE
}
