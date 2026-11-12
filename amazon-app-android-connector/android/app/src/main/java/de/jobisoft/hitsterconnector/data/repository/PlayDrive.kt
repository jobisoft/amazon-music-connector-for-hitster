package de.jobisoft.hitsterconnector.data.repository

import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import de.jobisoft.hitsterconnector.data.debug.DebugLog
import de.jobisoft.hitsterconnector.util.PlayFlow
import de.jobisoft.hitsterconnector.util.PlayReactor
import de.jobisoft.hitsterconnector.util.SessionSnapshot
import de.jobisoft.hitsterconnector.util.StartJump
import de.jobisoft.hitsterconnector.util.StateNames
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One load attempt, driven by the reports of the media session: the request goes out, then each report is
 * handed to [PlayReactor.onSnapshot] and the action it returns is executed at once (pause, seek, play).
 * The only waiting is for the next report. A timer runs only for a deadline the reactor names (the settle
 * time, the answer to a seek, the play again) and for the time the item may take to show up.
 *
 * The outcome is [Outcome.PLAYING] when our item plays at its start offset, [Outcome.NOT_SHOWN] when it did
 * not show up in time, [Outcome.REPLACED] when a foreign item took its place, [Outcome.START_FAILED] when it
 * showed up but did not play in time, and [Outcome.GONE] when the session went away.
 */
internal class PlayDrive(
    private val handle: SessionWatcher.Handle,
    private val tag: String,
    private val ctx0: PlayReactor.Ctx,
    private val loadLimitMs: Long,
    /** Is the reported item the one we asked for? The second argument is the time of the report. */
    private val classify: (SessionSnapshot, Long) -> PlayFlow.Item,
    /** Our item showed up for the first time. */
    private val onOurs: (SessionSnapshot, MediaController, Long) -> Unit,
    /** Every report of our item that is not PLAYING, for the status text. */
    private val onOursState: (SessionSnapshot) -> Unit,
    /** The final play() was sent (after the seek). The volume level of the ducked stream goes back now. */
    private val onPlaySent: () -> Unit = {}
) {
    enum class Outcome { PLAYING, NOT_SHOWN, REPLACED, START_FAILED, GONE }

    class Result(
        val outcome: Outcome,
        val last: SessionSnapshot?,
        val lastItem: PlayFlow.Item,
        /** "settle" when the final play() was not sent yet, "start" after it. */
        val phase: String,
        val reason: String?,
        val replacedBy: String?
    )

    /** The numbers of the `start` event. */
    private class Numbers(val sentAt: Long) {
        var oursAt: Long? = null
        var firstActionMs: Long? = null
        var foreignPausedMs: Long? = null
        var firstOwnPauseAt: Long? = null
        var seekConfirmedAt: Long? = null
        var playingAt: Long? = null
        var seekCalls = 0
        var pausedAgainBecausePlaying = 0
        var maxPositionS = 0f
        var leakedIntroS = 0f
        var durationS = 0f
        var targetS = 0f
    }

    suspend fun run(send: () -> Boolean): Result {
        var ctx = ctx0
        val sentOk = send()
        val sentAt = SystemClock.elapsedRealtime()
        val nums = Numbers(sentAt)
        var last: SessionSnapshot? = null
        var lastItem = PlayFlow.Item.NONE
        var oursNotified = false
        var wakeAt: Long? = null
        var loggedWaitState = Int.MIN_VALUE

        fun finish(outcome: Outcome, reason: String? = null, replacedBy: String? = null): Result {
            logSummary(nums, outcome)
            return Result(outcome, last, lastItem, if (ctx.playsSent == 0) "settle" else "start", reason, replacedBy)
        }

        if (!sentOk) return finish(Outcome.NOT_SHOWN, "request not sent")

        while (true) {
            val now = SystemClock.elapsedRealtime()
            val loadDeadline = if (ctx.oursSeenAtMs == null) sentAt + loadLimitMs else null
            val deadline = listOfNotNull(wakeAt, loadDeadline).minOrNull()
            val event = try {
                if (deadline == null) {
                    handle.events.receive()
                } else {
                    withTimeoutOrNull((deadline - now).coerceAtLeast(1L)) { handle.events.receive() }
                }
            } catch (_: ClosedReceiveChannelException) {
                return finish(Outcome.GONE, "listener closed")
            }
            val t = SystemClock.elapsedRealtime()
            val snap = if (event != null) event.snapshot else handle.current()
            if (snap == null) {
                if (ctx.oursSeenAtMs != null) return finish(Outcome.GONE, "session went away")
                if (t >= sentAt + loadLimitMs) return finish(Outcome.NOT_SHOWN, "no session")
                continue
            }
            val item = classify(snap, t)
            last = snap
            lastItem = item
            if (event != null) logEvent(snap, item, t - sentAt)

            if (item == PlayFlow.Item.OURS && !oursNotified) {
                oursNotified = true
                nums.oursAt = t
                handle.controller()?.let { onOurs(snap, it, t - sentAt) }
            }
            if (item == PlayFlow.Item.OURS && snap.state != PlaybackState.STATE_PLAYING) onOursState(snap)
            track(nums, snap, item, ctx, t)

            val step = PlayReactor.onSnapshot(snap, item, ctx, t)
            ctx = step.ctx
            wakeAt = step.wakeAtMs
            when (val action = step.action) {
                PlayReactor.Action.None -> {
                    // after the final play(): the wait for PLAYING, one event per state it shows
                    if (!ctx.armed && item == PlayFlow.Item.OURS && snap.state != PlaybackState.STATE_PLAYING &&
                        snap.state != loggedWaitState
                    ) {
                        loggedWaitState = snap.state
                        DebugLog.log("play", "buffering wait", mapOf("state" to StateNames.of(snap.state), "ms" to t - (ctx.lastPlayAtMs ?: t)))
                    }
                }
                is PlayReactor.Action.Pause -> {
                    if (nums.firstActionMs == null) nums.firstActionMs = t - sentAt
                    if (item == PlayFlow.Item.FOREIGN) {
                        if (nums.foreignPausedMs == null) nums.foreignPausedMs = t - sentAt
                    } else {
                        if (nums.firstOwnPauseAt == null) nums.firstOwnPauseAt = t
                        if (snap.state == PlaybackState.STATE_PLAYING && nums.firstOwnPauseAt != t) nums.pausedAgainBecausePlaying++
                    }
                    act("pause", action.reason, t - sentAt, snap) { it.transportControls.pause() }
                }
                is PlayReactor.Action.Seek -> {
                    nums.seekCalls++
                    act("seekTo", action.reason, t - sentAt, snap, action.toMs) { it.transportControls.seekTo(action.toMs) }
                }
                is PlayReactor.Action.Play -> {
                    if (ctx.cold && ctx.playsSent == 1) {
                        DebugLog.log("play", "settle", mapOf("outcome" to "settled", "ms" to t - (nums.oursAt ?: t)))
                    }
                    act("play", action.reason, t - sentAt, snap) { it.transportControls.play() }
                    onPlaySent()
                }
                is PlayReactor.Action.Reload -> {
                    if (ctx.cold && ctx.playsSent == 0) {
                        DebugLog.log("play", "settle", mapOf("outcome" to "replaced", "ms" to t - (nums.oursAt ?: t)))
                    }
                    return finish(Outcome.REPLACED, "foreign item", action.foreignMediaId)
                }
                PlayReactor.Action.Done -> {
                    nums.playingAt = t
                    return finish(Outcome.PLAYING)
                }
                is PlayReactor.Action.GiveUp ->
                    return finish(if (ctx.oursSeenAtMs == null) Outcome.NOT_SHOWN else Outcome.START_FAILED, action.reason)
            }
            // a second action of the same report (the seek that goes out together with the first pause)
            (step.andThen as? PlayReactor.Action.Seek)?.let {
                nums.seekCalls++
                act("seekTo", it.reason, SystemClock.elapsedRealtime() - sentAt, snap, it.toMs) { c -> c.transportControls.seekTo(it.toMs) }
            }
            if (ctx.oursSeenAtMs == null && SystemClock.elapsedRealtime() >= sentAt + loadLimitMs) {
                return finish(Outcome.NOT_SHOWN, "not shown in time")
            }
        }
    }

    private fun track(nums: Numbers, s: SessionSnapshot, item: PlayFlow.Item, ctx: PlayReactor.Ctx, t: Long) {
        val pos = s.positionS(t)
        if (item != PlayFlow.Item.OURS) return
        if (pos > nums.maxPositionS) nums.maxPositionS = pos
        if (s.durationS > 0f) {
            nums.durationS = s.durationS
            nums.targetS = StartJump.startFor(s.durationS, ctx.position)
        }
        if (s.state == PlaybackState.STATE_PLAYING && nums.targetS > 0f && pos < nums.targetS - StartJump.NEAR_S && pos > nums.leakedIntroS) {
            nums.leakedIntroS = pos
        }
        if (nums.seekConfirmedAt == null && nums.seekCalls > 0 && PlayReactor.isIdle(s.state) &&
            nums.targetS > 0f && StartJump.near(pos, nums.targetS)
        ) {
            nums.seekConfirmedAt = t
        }
    }

    private fun act(name: String, reason: String, sinceMs: Long, s: SessionSnapshot, arg: Long? = null, call: (MediaController) -> Unit) {
        val c = handle.controller()
        DebugLog.log(
            "act", name,
            mapOf("tag" to tag, "reason" to reason, "ms" to sinceMs, "state" to StateNames.of(s.state),
                "positionMs" to s.positionMs, "mediaId" to s.mediaId, "arg" to arg, "controller" to (c != null))
        )
        if (c == null) return
        try {
            call(c)
        } catch (e: Throwable) {
            DebugLog.log("act", "$name threw", mapOf("tag" to tag, "error" to e))
        }
    }

    private fun logEvent(s: SessionSnapshot, item: PlayFlow.Item, sinceMs: Long) {
        if (!DebugLog.ENABLED) return
        DebugLog.log(
            "ev", "snapshot",
            mapOf("tag" to tag, "ms" to sinceMs, "state" to StateNames.of(s.state), "positionMs" to s.positionMs,
                "mediaId" to s.mediaId, "title" to s.title, "item" to item.name.lowercase(), "actionsMask" to s.actions,
                "durationMs" to s.durationMs)
        )
    }

    private fun logSummary(n: Numbers, outcome: Outcome) {
        val oursAt = n.oursAt ?: return
        DebugLog.log(
            "start", "load, pause, seek, play",
            mapOf(
                "step" to tag,
                "outcome" to outcome.name.lowercase(),
                "firstActionMs" to n.firstActionMs,
                "foreignPausedMs" to n.foreignPausedMs,
                "titleToPauseMs" to n.firstOwnPauseAt?.let { (it - oursAt).coerceAtLeast(0L) },
                "titleToSeekConfirmedMs" to n.seekConfirmedAt?.let { it - oursAt },
                "titleToPlayingMs" to n.playingAt?.let { it - oursAt },
                "durationS" to n.durationS,
                "targetS" to n.targetS,
                "seekCalls" to n.seekCalls,
                "pausedAgainBecausePlaying" to n.pausedAgainBecausePlaying,
                "maxPositionSeenS" to n.maxPositionS,
                "leakedIntroS" to n.leakedIntroS,
                "startPosition" to ctx0.position.key
            )
        )
    }
}
