package de.jobisoft.hitsterconnector.util

import android.media.session.PlaybackState

/** What happened when a song was stopped before a new request. */
data class StopResult(
    /** False only if the player still played after the timeout. */
    val confirmed: Boolean,
    /** False if nothing played, then nothing was paused. */
    val needed: Boolean,
    val polls: Int,
    val elapsedMs: Long
)

/**
 * The decision "stop the song and wait until the player says it stopped", without Android and
 * without real time, so that it can be tested. The repository passes its session and its clock.
 */
object StopConfirm {
    /** A player in these states has to be paused first. */
    fun needsStop(state: Int?): Boolean =
        state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING

    /** These states (or no session at all) confirm that nothing plays. */
    fun isStopped(state: Int?): Boolean =
        state == null || state == PlaybackState.STATE_PAUSED ||
            state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE

    suspend fun run(
        readState: () -> Int?,
        pause: () -> Unit,
        now: () -> Long,
        sleep: suspend (Long) -> Unit,
        timeoutMs: Long = 3000,
        intervalMs: Long = 100
    ): StopResult {
        val begin = now()
        if (!needsStop(readState())) return StopResult(confirmed = true, needed = false, polls = 0, elapsedMs = 0)
        pause()
        var polls = 0
        while (true) {
            polls++
            if (isStopped(readState())) return StopResult(true, true, polls, now() - begin)
            if (now() - begin >= timeoutMs) return StopResult(false, true, polls, now() - begin)
            sleep(intervalMs)
        }
    }
}
