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
 * handed to [PlayReactor.onSnapshot] and the action it returns is executed at once (the one seek). The waiting is
 * for the next report, or for the deadline the reactor names (the 125 ms look after the seek, the limit).
 * Nothing is paused and no play() is sent, Amazon plays the loaded song by itself.
 *
 * The outcome is [Outcome.PLAYING] when our item plays at its start offset (or the wait for that is over),
 * [Outcome.NOT_SHOWN] when it did not show up in time, [Outcome.START_FAILED] when it showed up but did not play in
 * time, and [Outcome.GONE] when the session went away.
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
    /** The first report that says PLAYING for our item: the guard grabs the song (id and title) here. */
    private val onOursPlaying: (SessionSnapshot) -> Unit = {},
    /** The song plays at its start offset (or the wait is over). The volume level of the ducked stream goes back now. */
    private val onStarted: () -> Unit = {}
) {
    enum class Outcome { PLAYING, NOT_SHOWN, START_FAILED, GONE }

    class Result(
        val outcome: Outcome,
        val last: SessionSnapshot?,
        val lastItem: PlayFlow.Item,
        val reason: String?
    )

    /** The numbers of the `start` event. */
    private class Numbers {
        var oursAt: Long? = null
        var firstPlayingAt: Long? = null
        var seekSentAt: Long? = null
        var doneAt: Long? = null
        var seekCalls = 0
        var firstPlayingPositionS = 0f
        var doneState: Int? = null
        var donePositionS = 0f
        var durationS = 0f
        var targetS = 0f
    }

    suspend fun run(send: () -> Boolean): Result {
        var ctx = ctx0
        val sentOk = send()
        val sentAt = SystemClock.elapsedRealtime()
        val nums = Numbers()
        var last: SessionSnapshot? = null
        var lastItem = PlayFlow.Item.NONE
        var oursNotified = false
        var playingNotified = false
        var wakeAt: Long? = null

        fun finish(outcome: Outcome, reason: String? = null): Result {
            logSummary(nums, outcome)
            return Result(outcome, last, lastItem, reason)
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
            if (item == PlayFlow.Item.OURS && snap.state == PlaybackState.STATE_PLAYING && !playingNotified) {
                playingNotified = true
                nums.firstPlayingAt = t
                nums.firstPlayingPositionS = snap.positionS(t)
                onOursPlaying(snap)
            }
            if (item == PlayFlow.Item.OURS && snap.durationS > 0f) {
                nums.durationS = snap.durationS
                nums.targetS = StartJump.startFor(snap.durationS, ctx.position)
            }

            val step = PlayReactor.onSnapshot(snap, item, ctx, t)
            ctx = step.ctx
            wakeAt = step.wakeAtMs
            when (val action = step.action) {
                PlayReactor.Action.None -> Unit
                is PlayReactor.Action.Seek -> {
                    nums.seekCalls++
                    nums.seekSentAt = t
                    act("seekTo", action.reason, t - sentAt, snap, action.toMs) { it.transportControls.seekTo(action.toMs) }
                }
                PlayReactor.Action.Done -> {
                    nums.doneAt = t
                    nums.doneState = snap.state
                    nums.donePositionS = snap.positionS(t)
                    onStarted()
                    return finish(Outcome.PLAYING)
                }
                is PlayReactor.Action.GiveUp ->
                    return finish(if (ctx.oursSeenAtMs == null) Outcome.NOT_SHOWN else Outcome.START_FAILED, action.reason)
            }
            if (ctx.oursSeenAtMs == null && SystemClock.elapsedRealtime() >= sentAt + loadLimitMs) {
                return finish(Outcome.NOT_SHOWN, "not shown in time")
            }
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
            "start", "load, playing, seek, arrived",
            mapOf(
                "step" to tag,
                "outcome" to outcome.name.lowercase(),
                "titleToPlayingMs" to n.firstPlayingAt?.let { it - oursAt },
                "firstPlayingPositionS" to n.firstPlayingPositionS,
                "playingToSeekMs" to if (n.seekSentAt != null && n.firstPlayingAt != null) n.seekSentAt!! - n.firstPlayingAt!! else null,
                "seekToArrivedMs" to if (n.doneAt != null && n.seekSentAt != null) n.doneAt!! - n.seekSentAt!! else null,
                "doneState" to n.doneState?.let { StateNames.of(it) },
                "donePositionS" to n.donePositionS,
                "durationS" to n.durationS,
                "targetS" to n.targetS,
                "seekCalls" to n.seekCalls,
                "startPosition" to ctx0.position.key
            )
        )
    }
}
