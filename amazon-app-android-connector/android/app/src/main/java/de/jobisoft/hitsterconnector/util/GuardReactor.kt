package de.jobisoft.hitsterconnector.util

import android.media.session.PlaybackState

/**
 * The rules of the end guard for the song of a card, as pure functions of what the session reported.
 * [onSnapshot] is called for every report of the session, [onTimer] when the one-shot timer of the end
 * has run out. The answer is a [Decision], the guard executes it.
 *
 *  1. Another item than the card song that plays or buffers is paused at once (the switch detector, also
 *     after the song has ended: Amazon may go on with its queue).
 *  2. The card song that plays inside the end zone is paused at once. The first time it is the end of the
 *     song ([Why.END]), later ones are plays that somebody started inside the zone ([Why.END_ZONE]).
 *  3. A card song that plays in front of the end zone gets a timer for the moment it reaches the zone.
 *  4. If Amazon switches to another item, or stops the card song, within the last [EndDecision.SWITCH_END_WINDOW_S]
 *     seconds of the song, that is its end as well ([END_SWITCH], [END_STOPPED]), also when the timer was not
 *     due yet. A switch in the middle of the song is not an end.
 *  5. Everything else is left alone: a pause by hand, a song that buffers, a length that is unknown or too
 *     short, no session.
 */
object GuardReactor {
    /** How often one arming sends a pause at most. A player that resumes by itself cannot start a loop. */
    const val PAUSES_MAX = 6

    /** A timer that runs out this close in front of the end zone still counts as the end. */
    const val TIMER_TOLERANCE_S = 0.5f

    enum class Why { SWITCH, END, END_ZONE }

    /** The reason of an [EndEvent] for the pause in front of the end zone. */
    const val END_PAUSED = "end paused"

    /** The reason when Amazon switched to another item within the last seconds of the song. */
    const val END_SWITCH = "end switch"

    /** The reason when Amazon stopped the song within the last seconds. */
    const val END_STOPPED = "end stopped"

    /**
     * The state of one arming. [ended]: the song was paused for its end already. The last* values are the latest
     * report of the card song while it played, paused or buffered: its position at [lastAtMs] (same clock as the
     * `nowMs` of the calls), its length, and whether the playhead was moving.
     */
    data class Ctx(
        val guardedId: String?,
        val guardedTitle: String?,
        val ended: Boolean = false,
        val pausesSent: Int = 0,
        val lastPositionS: Float = 0f,
        val lastDurationS: Float = 0f,
        val lastAtMs: Long = 0L,
        val lastPlaying: Boolean = false
    )

    /**
     * [pause]: send a pause now, for this reason. [endNow]: the song has ended, mark it ended and tell the quiz with
     * [endReason]. [clearEnded]: the playhead was moved back, the song is not ended any more.
     * [timerMs]: arm the one-shot timer for the end, null cancels it.
     */
    data class Decision(
        val pause: Why? = null,
        val endNow: Boolean = false,
        val clearEnded: Boolean = false,
        val timerMs: Long? = null,
        val endReason: String = END_PAUSED
    )

    /** True if a song is expected (see EndGuard.expect). Without one the guard does nothing. */
    fun hasSong(ctx: Ctx): Boolean = ctx.guardedId != null || ctx.guardedTitle != null

    private fun otherItem(ctx: Ctx, s: SessionSnapshot): Boolean =
        (s.mediaId != null || s.title != null) && !SongIdentity.same(ctx.guardedId, ctx.guardedTitle, s.mediaId, s.title)

    /**
     * Takes a report of the card song into the context: its position, its length and whether it moves. Reports of
     * other items, and reports of the card song that is stopped or without state, are not taken (a stop resets
     * the position, and the last real position is what tells whether the song was near its end).
     */
    fun track(ctx: Ctx, snapshot: SessionSnapshot?, nowMs: Long): Ctx {
        val s = snapshot ?: return ctx
        if (!hasSong(ctx)) return ctx
        if (otherItem(ctx, s)) return ctx
        if (s.state != PlaybackState.STATE_PLAYING && s.state != PlaybackState.STATE_PAUSED &&
            s.state != PlaybackState.STATE_BUFFERING
        ) return ctx
        return ctx.copy(
            lastPositionS = s.positionS(nowMs),
            lastDurationS = if (s.durationS > 0f) s.durationS else ctx.lastDurationS,
            lastAtMs = nowMs,
            lastPlaying = s.state == PlaybackState.STATE_PLAYING
        )
    }

    /** The last known position of the card song carried on to [nowMs], while it was playing. */
    fun extrapolatedS(ctx: Ctx, nowMs: Long): Float =
        if (ctx.lastPlaying && ctx.lastAtMs > 0) ctx.lastPositionS + (nowMs - ctx.lastAtMs).coerceAtLeast(0L) / 1000f
        else ctx.lastPositionS

    /** True if the card song was within the last seconds of its length when it was seen last. */
    fun nearEnd(ctx: Ctx, nowMs: Long): Boolean =
        ctx.lastDurationS >= EndDecision.MIN_DURATION_S &&
            extrapolatedS(ctx, nowMs) >= ctx.lastDurationS - EndDecision.SWITCH_END_WINDOW_S

    fun onSnapshot(ctx: Ctx, snapshot: SessionSnapshot?, nowMs: Long): Decision {
        val s = snapshot ?: return Decision()
        if (!hasSong(ctx)) return Decision()
        val canPause = ctx.pausesSent < PAUSES_MAX

        if (otherItem(ctx, s)) {
            val pause = canPause && SwitchDetector.shouldPause(ctx.guardedId, ctx.guardedTitle, s.mediaId, s.title, s.state)
            // Amazon went on to another item within the last seconds of the song: that is the end of the song
            val ends = !ctx.ended && nearEnd(ctx, nowMs)
            return Decision(pause = if (pause) Why.SWITCH else null, endNow = ends, endReason = END_SWITCH)
        }

        // Amazon stopped the card song within its last seconds (the queue ended): the end as well, nothing to pause
        if (!ctx.ended && (s.state == PlaybackState.STATE_STOPPED || s.state == PlaybackState.STATE_NONE) && nearEnd(ctx, nowMs)) {
            return Decision(endNow = true, endReason = END_STOPPED)
        }

        val durationS = s.durationS
        if (durationS < EndDecision.MIN_DURATION_S) return Decision()
        val positionS = s.positionS(nowMs)

        // the playhead was moved well back in front of the zone: the song is not at its end any more
        val clearEnded = ctx.ended && positionS < durationS - EndDecision.END_ZONE_S - EndDecision.REARM_MARGIN_S
        val ended = ctx.ended && !clearEnded

        if (s.state != PlaybackState.STATE_PLAYING) return Decision(clearEnded = clearEnded)

        if (EndDecision.inEndZone(positionS, durationS)) {
            if (!canPause) return Decision(clearEnded = clearEnded)
            return Decision(pause = if (ended) Why.END_ZONE else Why.END, endNow = !ended, clearEnded = clearEnded)
        }
        return Decision(clearEnded = clearEnded, timerMs = EndPlanner.delayUntilPauseMs(s, nowMs))
    }

    /**
     * The timer of the end ran out. If the session still plays the card song near the end zone, that is the end:
     * pause. A snapshot that is not near any more (the playhead was moved, a report is missing) is judged as a
     * normal report, which arms the timer again.
     */
    fun onTimer(ctx: Ctx, snapshot: SessionSnapshot?, nowMs: Long): Decision {
        val s = snapshot ?: return Decision()
        if (!hasSong(ctx)) return Decision()
        val near = s.state == PlaybackState.STATE_PLAYING && !otherItem(ctx, s) && !ctx.ended &&
            ctx.pausesSent < PAUSES_MAX && s.durationS >= EndDecision.MIN_DURATION_S &&
            s.positionS(nowMs) >= s.durationS - EndDecision.END_ZONE_S - TIMER_TOLERANCE_S
        return if (near) Decision(pause = Why.END, endNow = true) else onSnapshot(ctx, s, nowMs)
    }
}
