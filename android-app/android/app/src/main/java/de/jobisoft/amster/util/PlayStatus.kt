package de.jobisoft.amster.util

import android.media.session.PlaybackState

/** The one line under "Sending song to the Amazon Music app." while a play request runs. */
object PlayStatus {
    const val WAKING = "connecting to Amazon Music"
    const val LOADING = "loading"
    const val SEARCHING = "searching"

    /** The line of the pre-load of a cold start, with the seconds that are left. */
    fun preload(secondsLeft: Int): String = "$WAKING (${secondsLeft}s)"

    /** The word for the state of the session while the track is loaded and does not play yet. */
    fun forState(state: Int?): String = when (state) {
        null, PlaybackState.STATE_NONE -> "waiting"
        PlaybackState.STATE_STOPPED, PlaybackState.STATE_PAUSED -> "preparing"
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_CONNECTING -> "connecting"
        PlaybackState.STATE_ERROR -> "error"
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> "positioning"
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "loading"
        else -> "unknown"
    }

    /** The text of a phase. */
    fun forPhase(phase: PlayFlow.Phase): String = when (phase) {
        PlayFlow.Phase.WAKE -> WAKING
        PlayFlow.Phase.LOAD, PlayFlow.Phase.START -> LOADING
        PlayFlow.Phase.SEARCH -> SEARCHING
    }
}
