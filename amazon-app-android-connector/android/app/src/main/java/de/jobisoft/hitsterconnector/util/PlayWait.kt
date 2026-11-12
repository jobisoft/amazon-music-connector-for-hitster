package de.jobisoft.hitsterconnector.util

import android.media.session.PlaybackState

/**
 * The wait for a track that was asked by its id and is loaded but not playing yet. A stall of the network shows as
 * BUFFERING for a few seconds, the track is the right one and the search must not take over.
 */
object PlayWait {
    /** How long the right track may take to start playing. */
    const val BUFFER_WAIT_MS = 15_000L

    /** True for the states that mean "on its way", used for the reason in the log. */
    fun isLoading(state: Int): Boolean = state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_CONNECTING ||
        state == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM || state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
        state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
}
