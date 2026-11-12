package de.jobisoft.hitsterconnector.util

import android.media.session.PlaybackState
import de.jobisoft.hitsterconnector.util.PlayFlow.Item

/**
 * What the play request does on each report of the media session of Amazon Music. One pure function,
 * [onSnapshot], decides it, the caller executes the action and passes the returned [Ctx] to the next call.
 *
 * The rule has two parts:
 *
 *  1. While the request is armed (from its start until it sends its own final play()), every change of the
 *     session that is not paused gets a pause() at once, whether the item is Amazon's own restored one or
 *     ours. At most one pause goes out per distinct state and media id, the next report that still shows an
 *     active player gets another one, until the session says PAUSED.
 *     The first report of our item that already says PAUSED (Amazon shows a loaded item paused at 0 before it
 *     starts it by itself) gets the pause too, together with the seek, so the pause never waits for the start.
 *  2. Only then the item matters. Ours is moved to the start offset (seekTo) and gets its final play() when it
 *     is paused near the offset (after a cold start only once it stayed ours for [PlayFlow.SETTLE_MS]). A new
 *     foreign item that is paused is a replacement, the caller loads the id again.
 *
 * After the final play() nothing is paused or seeked any more. Only the wait for PLAYING goes on (see
 * [PlayWait]: a play() again from PAUSED, give up after the limit) and a seek if the player starts far
 * below the offset (counted in [SEEKS_MAX]).
 */
object PlayReactor {
    /** A hard stop for pause() calls in one request, so a player that resumes by itself cannot start a loop. */
    const val PAUSES_MAX = 12

    /** How often the seek is sent at most. */
    const val SEEKS_MAX = 4

    /** A seek that did not move the playhead is sent again after this time. */
    const val SEEK_WAIT_MS = 1_200L

    /** Without a duration or without the seek action, the play() goes out this long after the pause was confirmed. */
    const val JUMP_WAIT_MS = 1_500L

    fun isActive(state: Int): Boolean = when (state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM, PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING -> true
        else -> false
    }

    fun isIdle(state: Int): Boolean = state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_STOPPED ||
        state == PlaybackState.STATE_NONE

    /** The state of one request. All times are on the `SystemClock.elapsedRealtime` clock. */
    data class Ctx(
        /** True from the start of the request until its final play() is sent. */
        val armed: Boolean = true,
        /** A foreign item that replaced ours ends the attempt with [Action.Reload] (the id route), the search just waits. */
        val canReload: Boolean = false,
        /** The request began from a cold or bare session, the final play() waits for the settle time. */
        val cold: Boolean = false,
        /** Where the song is moved to after it loaded (see [StartJump.startFor]). */
        val position: StartPosition = StartPosition.DEFAULT,
        /** The item the session showed when the request began: the old song, not a replacement. */
        val baselineMediaId: String? = null,
        /** How long the wait for PLAYING may take, counted from the first report of our item. */
        val limitMs: Long = PlayWait.BUFFER_WAIT_MS,
        val oursSeenAtMs: Long? = null,
        val oursSinceMs: Long? = null,
        val lastPauseKey: String? = null,
        val pauses: Int = 0,
        val ownPauses: Int = 0,
        val pausedSinceMs: Long? = null,
        val seeks: Int = 0,
        val seekSentAtMs: Long? = null,
        val seekSentFromMs: Long? = null,
        val playsSent: Int = 0,
        val lastPlayAtMs: Long? = null
    )

    sealed interface Action {
        data object None : Action
        data class Pause(val reason: String) : Action
        data class Seek(val toMs: Long, val reason: String) : Action
        data class Play(val reason: String) : Action

        /** A foreign item replaced ours and is paused now: the caller loads the id again. */
        data class Reload(val foreignMediaId: String?) : Action

        /** Our item plays at its start offset. */
        data object Done : Action

        data class GiveUp(val reason: String) : Action
    }

    /**
     * [wakeAtMs] is the time the caller must look again without a new report (a deadline), null if none.
     * [andThen] is a second action for the same report, executed right after [action] (the seek that goes out
     * together with the first pause of our item).
     */
    data class Step(val action: Action, val ctx: Ctx, val wakeAtMs: Long? = null, val andThen: Action? = null)

    private fun key(s: SessionSnapshot) = "${s.state}/${s.mediaId}"

    fun onSnapshot(s: SessionSnapshot, item: Item, c: Ctx, nowMs: Long): Step {
        var ctx = c
        when (item) {
            Item.OURS -> ctx = ctx.copy(oursSeenAtMs = ctx.oursSeenAtMs ?: nowMs, oursSinceMs = ctx.oursSinceMs ?: nowMs)
            Item.FOREIGN -> ctx = ctx.copy(oursSinceMs = null)
            Item.NONE -> {}
        }
        if (s.state == PlaybackState.STATE_PAUSED) ctx = ctx.copy(lastPauseKey = null) // the pause took, the next change counts again

        return if (ctx.armed) armed(s, item, ctx, nowMs) else afterFinalPlay(s, item, ctx, nowMs)
    }

    private fun armed(s: SessionSnapshot, item: Item, c: Ctx, nowMs: Long): Step {
        var ctx = c
        ctx.oursSeenAtMs?.let { if (nowMs - it >= ctx.limitMs) return Step(Action.GiveUp("not playing"), ctx) }

        // 0. start position Off: our item is not moved, so there is nothing to hide. It is left alone while it
        // starts, and played if it stays paused. A foreign item is paused as below.
        if (item == Item.OURS && ctx.position == StartPosition.OFF) {
            if (s.state != PlaybackState.STATE_PLAYING) return ours(s, ctx, nowMs)
            val oursFor = ctx.oursSinceMs?.let { nowMs - it } ?: 0L
            return if (PlayFlow.settled(ctx.cold, oursFor)) Step(Action.Done, ctx.copy(armed = false))
            else Step(Action.None, ctx, wake(s, ctx, nowMs))
        }

        // 1. pause first, whoever the item is
        val seekBuffering = item == Item.OURS && s.state == PlaybackState.STATE_BUFFERING && ctx.seekSentAtMs != null &&
            ctx.pausedSinceMs != null
        if (isActive(s.state) && key(s) != ctx.lastPauseKey && ctx.pauses < PAUSES_MAX && !seekBuffering) {
            ctx = ctx.copy(
                lastPauseKey = key(s), pauses = ctx.pauses + 1,
                ownPauses = ctx.ownPauses + if (item == Item.FOREIGN) 0 else 1
            )
            return Step(Action.Pause("${stateWord(s.state)} ${itemWord(item)}"), ctx, wake(s, ctx, nowMs))
        }

        // 1b. the first report of our item that says PAUSED: Amazon shows a freshly loaded item paused at 0 and
        // starts it by itself a moment later, so the pause goes out at once and the seek with it. Without this
        // the pause would wait for the start (SKIPPING, BUFFERING) and the intro would play for a moment.
        if (item == Item.OURS && s.state == PlaybackState.STATE_PAUSED && ctx.ownPauses == 0 && ctx.pauses < PAUSES_MAX) {
            val inner = ours(s, ctx, nowMs)
            val next = inner.action
            if (next !is Action.None && next !is Action.Seek) return inner // the final play, an error: nothing to protect
            if (next is Action.None && isNear(s, ctx, nowMs)) return inner // paused at the offset already
            val after = inner.ctx.copy(pauses = inner.ctx.pauses + 1, ownPauses = inner.ctx.ownPauses + 1)
            return Step(Action.Pause("PAUSED our item, first report"), after, inner.wakeAtMs, andThen = next.takeIf { it is Action.Seek })
        }

        // 2. the item decides
        return when (item) {
            Item.FOREIGN -> {
                val replacement = ctx.canReload && s.mediaId != null && s.mediaId != ctx.baselineMediaId && isIdle(s.state)
                Step(if (replacement) Action.Reload(s.mediaId) else Action.None, ctx, wake(s, ctx, nowMs))
            }
            Item.NONE -> Step(Action.None, ctx, wake(s, ctx, nowMs))
            Item.OURS -> ours(s, ctx, nowMs)
        }
    }

    /** The playhead is at the start offset (an unknown duration is no arrival). */
    private fun isNear(s: SessionSnapshot, ctx: Ctx, nowMs: Long): Boolean {
        val target = if (s.durationS > 0f) StartJump.startFor(s.durationS, ctx.position) else 0f
        return s.durationS > 0f && (target <= 0f || StartJump.near(s.positionS(nowMs), target))
    }

    private fun ours(s: SessionSnapshot, c: Ctx, nowMs: Long): Step {
        var ctx = c
        if (s.state == PlaybackState.STATE_ERROR) return Step(Action.GiveUp("error"), ctx)
        val idle = isIdle(s.state)
        if (idle && ctx.pausedSinceMs == null) ctx = ctx.copy(pausedSinceMs = nowMs)
        // the player answered the seek: the position moved
        if (ctx.seekSentFromMs != null && s.positionMs != ctx.seekSentFromMs) ctx = ctx.copy(seekSentFromMs = null)

        val target = if (s.durationS > 0f) StartJump.startFor(s.durationS, ctx.position) else 0f
        // an unknown duration is no arrival: the play waits for the jump wait then
        val near = s.durationS > 0f && (target <= 0f || StartJump.near(s.positionS(nowMs), target))
        val sentAt = ctx.seekSentAtMs
        val seekWaiting = sentAt != null && ctx.seekSentFromMs != null && nowMs - sentAt < SEEK_WAIT_MS

        // seek: as soon as a pause was sent or the item is paused, and the player can do it
        if (target > 0f && !near && s.canSeek && ctx.seeks < SEEKS_MAX && !seekWaiting && (idle || ctx.ownPauses > 0)) {
            ctx = ctx.copy(seeks = ctx.seeks + 1, seekSentAtMs = nowMs, seekSentFromMs = s.positionMs)
            return Step(Action.Seek((target * 1000).toLong(), "to the start offset"), ctx, wake(s, ctx, nowMs))
        }

        // play: paused, at the offset (or the jump cannot happen), and settled
        val pausedFor = ctx.pausedSinceMs?.let { nowMs - it } ?: 0L
        val jumpOk = near ||
            ((s.durationS <= 0f || !s.canSeek) && idle && pausedFor >= JUMP_WAIT_MS) ||
            (ctx.seeks >= SEEKS_MAX && !seekWaiting)
        val oursFor = ctx.oursSinceMs?.let { nowMs - it } ?: 0L
        if (idle && jumpOk && PlayFlow.settled(ctx.cold, oursFor)) {
            ctx = ctx.copy(armed = false, playsSent = ctx.playsSent + 1, lastPlayAtMs = nowMs)
            return Step(Action.Play("paused at the start offset"), ctx, wake(s, ctx, nowMs))
        }
        return Step(Action.None, ctx, wake(s, ctx, nowMs))
    }

    private fun afterFinalPlay(s: SessionSnapshot, item: Item, c: Ctx, nowMs: Long): Step {
        var ctx = c
        val seenAt = ctx.oursSeenAtMs ?: nowMs
        val lastPlay = ctx.lastPlayAtMs
        val sinceLast = if (lastPlay == null) Long.MAX_VALUE else nowMs - lastPlay
        val elapsed = nowMs - seenAt

        if (s.state == PlaybackState.STATE_PLAYING && item == Item.OURS) {
            val target = if (s.durationS > 0f) StartJump.startFor(s.durationS, ctx.position) else 0f
            val farBelow = target > 0f && s.positionS(nowMs) < target - StartJump.NEAR_S
            if (farBelow && s.canSeek && ctx.seeks < SEEKS_MAX) {
                // the player started again at the beginning: one position correction, no pause
                ctx = ctx.copy(seeks = ctx.seeks + 1, seekSentAtMs = nowMs, seekSentFromMs = s.positionMs)
                return Step(Action.Seek((target * 1000).toLong(), "playing below the start offset"), ctx, ctx.oursSeenAtMs?.plus(ctx.limitMs))
            }
            return Step(Action.Done, ctx)
        }
        return when (PlayWait.decide(s.state, elapsed, sinceLast, ctx.limitMs)) {
            PlayWait.Action.DONE -> Step(Action.None, ctx, wake(s, ctx, nowMs)) // PLAYING of another item, no success
            PlayWait.Action.GIVE_UP -> Step(Action.GiveUp(if (s.state == PlaybackState.STATE_ERROR) "error" else "not playing"), ctx)
            PlayWait.Action.REPLAY ->
                if (item == Item.OURS && ctx.playsSent < PLAYS_MAX) {
                    ctx = ctx.copy(playsSent = ctx.playsSent + 1, lastPlayAtMs = nowMs)
                    Step(Action.Play("paused after play()"), ctx, wake(s, ctx, nowMs))
                } else {
                    Step(Action.None, ctx, wake(s, ctx, nowMs))
                }
            PlayWait.Action.WAIT -> Step(Action.None, ctx, wake(s, ctx, nowMs))
        }
    }

    /** A paused item gets play() again after the delay of [PlayWait], at most this many calls. */
    const val PLAYS_MAX = 6

    /** The next deadline for the caller: the limit, the settle time, the seek answer, the jump wait, the play again. */
    private fun wake(s: SessionSnapshot, ctx: Ctx, nowMs: Long): Long? {
        val times = mutableListOf<Long>()
        ctx.oursSeenAtMs?.let { times += it + ctx.limitMs }
        if (ctx.armed) {
            if (ctx.cold) ctx.oursSinceMs?.let { times += it + PlayFlow.SETTLE_MS }
            ctx.seekSentAtMs?.let { if (ctx.seekSentFromMs != null) times += it + SEEK_WAIT_MS }
            ctx.pausedSinceMs?.let { times += it + JUMP_WAIT_MS }
        } else {
            ctx.lastPlayAtMs?.let { if (isIdle(s.state)) times += it + PlayWait.REPLAY_AFTER_MS }
        }
        return times.filter { it > nowMs }.minOrNull()
    }

    private fun stateWord(state: Int): String = when (state) {
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        PlaybackState.STATE_CONNECTING -> "CONNECTING"
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING_TO_QUEUE_ITEM"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_TO_NEXT"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_TO_PREVIOUS"
        PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
        PlaybackState.STATE_REWINDING -> "REWINDING"
        else -> "state $state"
    }

    private fun itemWord(item: Item): String = when (item) {
        Item.OURS -> "our item"
        Item.FOREIGN -> "foreign item"
        Item.NONE -> "item without id"
    }
}
