package de.jobisoft.amster.util

import android.media.session.PlaybackState
import de.jobisoft.amster.util.PlayFlow.Item

/**
 * What the play request does on each report of the media session of Amazon Music. One pure function,
 * [onSnapshot], decides it, the caller executes the action and passes the returned [Ctx] to the next call.
 *
 * Amazon plays the loaded song by itself from 0 s, nothing is paused and no play() is sent. The rule:
 *
 *  1. Wait for the first report that says PLAYING for OUR item. The old song or another item that reports
 *     PLAYING is none of our business (the end guard pauses it).
 *  2. Then send one seekTo to the start offset, wherever the playhead is.
 *  3. From then on the session is looked at every [POLL_MS], with or without a report (a seek from a position near the
 *     offset may get no report at all). The song counts as started ([Action.Done]) when it plays and the playhead
 *     is at or after the offset (minus [StartJump.NEAR_S]), or after [ARRIVE_MAX_MS] at the latest.
 *
 * Without a start position (Off), with an unknown length or without the seek action, the first PLAYING report is
 * the end of the sequence. An item that does not play within [Ctx.limitMs] of its first report gives up.
 */
object PlayReactor {
    /** After the seek the session is looked at this often. */
    const val POLL_MS = 125L

    /** The longest wait for the playhead to arrive after the seek, then the sequence goes on anyway. */
    const val ARRIVE_MAX_MS = 3_000L

    fun isIdle(state: Int): Boolean = state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_STOPPED ||
        state == PlaybackState.STATE_NONE

    /** The state of one request. All times are on the `SystemClock.elapsedRealtime` clock. */
    data class Ctx(
        /** Where the song is moved to after it loaded (see [StartJump.startFor]). */
        val position: StartPosition = StartPosition.DEFAULT,
        /** How long our item may take to play, counted from its first report. */
        val limitMs: Long = PlayWait.BUFFER_WAIT_MS,
        val oursSeenAtMs: Long? = null,
        /** The time the one seek was sent, null before. */
        val seekSentAtMs: Long? = null
    )

    sealed interface Action {
        data object None : Action
        data class Seek(val toMs: Long, val reason: String) : Action

        /** Our item plays at its start offset (or the wait is over). */
        data object Done : Action
        data class GiveUp(val reason: String) : Action
    }

    /** [wakeAtMs] is the time the caller must look again without a new report (a deadline), null if none. */
    data class Step(val action: Action, val ctx: Ctx, val wakeAtMs: Long? = null)

    fun onSnapshot(s: SessionSnapshot, item: Item, c: Ctx, nowMs: Long): Step {
        var ctx = c
        if (item == Item.OURS && ctx.oursSeenAtMs == null) ctx = ctx.copy(oursSeenAtMs = nowMs)
        if (item == Item.OURS && s.state == PlaybackState.STATE_ERROR) return Step(Action.GiveUp("error"), ctx)

        val seekSentAt = ctx.seekSentAtMs
        if (seekSentAt != null) return afterSeek(s, item, ctx, seekSentAt, nowMs)

        if (item == Item.OURS && s.state == PlaybackState.STATE_PLAYING) {
            val target = if (s.durationS > 0f) StartJump.startFor(s.durationS, ctx.position) else 0f
            // nothing to move to, or the player cannot seek: PLAYING is all there is to wait for
            if (ctx.position == StartPosition.OFF || target <= 0f || !s.canSeek) return Step(Action.Done, ctx)
            ctx = ctx.copy(seekSentAtMs = nowMs)
            return Step(Action.Seek((target * 1000).toLong(), "to the start offset, after PLAYING"), ctx, nowMs + POLL_MS)
        }

        val limit = ctx.oursSeenAtMs?.let { it + ctx.limitMs }
        if (limit != null && nowMs >= limit) return Step(Action.GiveUp("not playing"), ctx)
        return Step(Action.None, ctx, limit)
    }

    private fun afterSeek(s: SessionSnapshot, item: Item, ctx: Ctx, seekSentAt: Long, nowMs: Long): Step {
        val target = if (s.durationS > 0f) StartJump.startFor(s.durationS, ctx.position) else 0f
        val arrived = item == Item.OURS && s.state == PlaybackState.STATE_PLAYING && s.positionS(nowMs) >= target - StartJump.NEAR_S
        val end = seekSentAt + ARRIVE_MAX_MS
        if (arrived || nowMs >= end) return Step(Action.Done, ctx)
        return Step(Action.None, ctx, minOf(nowMs + POLL_MS, end))
    }
}
