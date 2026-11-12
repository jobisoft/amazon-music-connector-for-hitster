package de.jobisoft.hitsterconnector.util

import android.media.session.PlaybackState

/**
 * What to do while a track that was asked by its id is loaded but not playing yet. A stall of the
 * network shows as BUFFERING for a few seconds, a second play() would not help then, the track is
 * the right one and the search must not take over.
 */
object PlayWait {
    /** How long the right track may take to start playing. */
    const val BUFFER_WAIT_MS = 15_000L

    /** A paused or stopped session gets play() again after this time. */
    const val REPLAY_AFTER_MS = 1_500L

    enum class Action { DONE, WAIT, REPLAY, GIVE_UP }

    /**
     * [elapsedMs] since the wait began, [sinceLastPlayMs] since the last play() call (large if there was none),
     * [limitMs] how long the wait may take.
     */
    fun decide(state: Int, elapsedMs: Long, sinceLastPlayMs: Long, limitMs: Long = BUFFER_WAIT_MS): Action = when {
        state == PlaybackState.STATE_PLAYING -> Action.DONE
        elapsedMs >= limitMs || state == PlaybackState.STATE_ERROR -> Action.GIVE_UP
        state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE ->
            if (sinceLastPlayMs >= REPLAY_AFTER_MS) Action.REPLAY else Action.WAIT
        else -> Action.WAIT // BUFFERING, CONNECTING, SKIPPING and the like
    }

    /** True for the states that mean "on its way", used for the reason in the log. */
    fun isLoading(state: Int): Boolean = state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_CONNECTING ||
        state == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM || state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
        state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
}
