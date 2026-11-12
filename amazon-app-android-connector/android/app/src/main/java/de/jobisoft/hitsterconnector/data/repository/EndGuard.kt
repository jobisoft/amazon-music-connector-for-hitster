package de.jobisoft.hitsterconnector.data.repository

import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jobisoft.hitsterconnector.data.debug.DebugLog
import de.jobisoft.hitsterconnector.data.media.AmazonMusicListener
import de.jobisoft.hitsterconnector.util.EndDecision
import de.jobisoft.hitsterconnector.util.GuardReactor
import de.jobisoft.hitsterconnector.util.Safe
import de.jobisoft.hitsterconnector.util.SessionSnapshot
import de.jobisoft.hitsterconnector.util.StopConfirm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The song has ended. [reason] says how: "end paused" (it was paused in front of its end), "end switch" (Amazon
 * went on to another item within the last seconds) or "end stopped" (Amazon stopped it there). The quiz mode
 * plays the next card on it. [song] is the title that was guarded.
 */
data class EndEvent(val song: String?, val reason: String)

/**
 * Protects the card song of this app, from its start to the next request. It listens to the media session of
 * Amazon Music ([SessionWatcher]) and decides with [GuardReactor]. Nothing is polled.
 *
 *  1. **The end.** Every report of the session re-plans one one-shot timer for the moment the playhead
 *     reaches the end zone ([EndDecision.END_ZONE_S] before the end). When it runs out the song is paused
 *     (not stopped), it counts as ended and [ended] fires. A report that shows the song playing inside the
 *     zone (somebody moved the playhead there and pressed play) is paused at once.
 *  2. **The switch detector**, the second safety net. If the session is on another item than the card song
 *     and plays or buffers, it pauses at once. This holds for the whole life of the card song, also after the song has
 *     ended: Amazon may go on to the next item of its queue, and that must not be heard.
 *  3. **Switch or stop at the end.** If Amazon goes on to another item, or stops the card song, within the
 *     last [EndDecision.SWITCH_END_WINDOW_S] seconds, the song has ended as well and [ended] fires, also when
 *     the timer was not due yet. A switch in the middle of the song fires nothing.
 *
 * The guard never disarms itself, except for the watch timeout. It is disarmed by a new request (a scan, the
 * next quiz card, "Play again"), by the stop button, by leaving the screen, and after 30 minutes.
 *
 * It does not depend on any screen of the app. It has its own scope, and it runs as long as the process
 * lives, which the bound NotificationListenerService (notification access) keeps up while the app is in the
 * background or the screen is off. A partial wake lock keeps the CPU awake for the timer of the end (the pause
 * has to come on time, with the screen off as well).
 *
 * Every step is in the debug log with the tag "guard".
 */
@Singleton
class EndGuard @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val WAKE_LOCK_MS = 35 * 60_000L
        /** The guard disarms itself this long after its last [arm]. Every arm (a new song, a move of the playhead) starts the time anew. */
        private const val WATCH_MAX_MS = 30 * 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + Safe.handler)
    private val watcher by lazy {
        SessionWatcher(context, ComponentName(context, AmazonMusicListener::class.java), ConnectorRepository.AMAZON_MUSIC)
    }
    private var wakeLock: PowerManager.WakeLock? = null

    /** Everything below is touched by the collector of the reports and by the timer, always under [lock]. */
    private val lock = Any()
    private var watching = false
    private var handle: SessionWatcher.Handle? = null
    private var listenJob: Job? = null
    private var watchJob: Job? = null
    private var timerJob: Job? = null
    private var ctx = GuardReactor.Ctx(null, null)
    private var switchPausedAt: Long? = null

    private val _ended = MutableSharedFlow<EndEvent>(extraBufferCapacity = 4)

    /** Emitted when a song is paused for its end. The quiz mode plays the next card on it. */
    val ended: SharedFlow<EndEvent> = _ended.asSharedFlow()

    private val _songEnded = MutableStateFlow(false)

    /** True from the pause at the end of the song until the next request of the app. The screen may use it. */
    val songEnded: StateFlow<Boolean> = _songEnded.asStateFlow()

    // ── arming ────────────────────────────────────────────────────────────────────

    /**
     * Guard the song that plays now. Call after the app started it, and after every move of the playhead
     * (the song is then no longer at its end, the timer is planned again).
     */
    fun arm(reason: String) {
        synchronized(lock) {
            val wasWatching = watching
            stopListening()
            val opened = watcher.open()
            val first = opened.current()
            if (first == null) {
                opened.close()
                watching = false
                releaseWakeLock()
                DebugLog.log("guard", "not armed, Amazon Music has no session", mapOf("reason" to reason))
                return
            }
            handle = opened
            watching = true
            ctx = GuardReactor.Ctx(first.mediaId, first.title)
            switchPausedAt = null
            _songEnded.value = false
            acquireWakeLock()
            DebugLog.log(
                "guard", if (wasWatching) "re-armed" else "armed",
                mapOf("reason" to reason, "song" to first.title, "mediaId" to first.mediaId, "durationS" to first.durationS,
                    "positionS" to first.positionS(SystemClock.elapsedRealtime()), "state" to first.state)
            )
            // the first report of the handle is the state right now, it plans the timer
            listenJob = scope.launch {
                try {
                    for (event in opened.events) onReport(opened, event.snapshot)
                } finally {
                    opened.close()
                }
            }
            watchJob = scope.launch {
                delay(WATCH_MAX_MS)
                disarm("watch timeout")
            }
        }
    }

    /**
     * A new request, the stop button, the screen left, the watch timeout: nothing to watch any more.
     * The guard does not call this itself, except for the watch timeout.
     */
    fun disarm(reason: String) {
        synchronized(lock) {
            if (!watching) return
            watching = false
            stopListening()
            _songEnded.value = false
            releaseWakeLock()
            DebugLog.log("guard", "disarmed", mapOf("reason" to reason))
        }
    }

    private fun stopListening() {
        timerJob?.cancel()
        timerJob = null
        watchJob?.cancel()
        watchJob = null
        listenJob?.cancel() // its finally closes the handle
        listenJob = null
        handle = null
    }

    private fun acquireWakeLock() {
        try {
            val lock = wakeLock ?: (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hitsterconnector:endguard").also { wakeLock = it }
            lock.setReferenceCounted(false)
            lock.acquire(WAKE_LOCK_MS)
        } catch (e: Throwable) {
            DebugLog.log("guard", "wake lock failed", mapOf("error" to e))
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
    }

    // ── the reports ───────────────────────────────────────────────────────────────

    /** One report of the session. */
    private fun onReport(source: SessionWatcher.Handle, snapshot: SessionSnapshot?) {
        synchronized(lock) {
            if (!watching || source !== handle) return
            try {
                confirmSwitchPause(snapshot)
                val now = SystemClock.elapsedRealtime()
                val decision = GuardReactor.onSnapshot(ctx, snapshot, now)
                if (decision.endNow && decision.endReason != GuardReactor.END_PAUSED) logEndWithout(snapshot, decision, now)
                ctx = GuardReactor.track(ctx, snapshot, now)
                execute(source, snapshot, decision)
            } catch (e: Throwable) {
                DebugLog.log("guard", "reacting to a report failed", mapOf("error" to e))
            }
        }
    }

    /** The one-shot timer of the end ran out. */
    private fun onTimer(source: SessionWatcher.Handle) {
        synchronized(lock) {
            if (!watching || source !== handle) return
            timerJob = null
            try {
                val snapshot = source.current()
                execute(source, snapshot, GuardReactor.onTimer(ctx, snapshot, SystemClock.elapsedRealtime()))
            } catch (e: Throwable) {
                DebugLog.log("guard", "reacting to the end timer failed", mapOf("error" to e))
            }
        }
    }

    /** The song ended by a switch or a stop: log how far it was, to see how early Amazon moves on. */
    private fun logEndWithout(snapshot: SessionSnapshot?, d: GuardReactor.Decision, now: Long) {
        val position = GuardReactor.extrapolatedS(ctx, now)
        DebugLog.log(
            "guard", d.endReason,
            mapOf("song" to ctx.guardedTitle, "positionS" to position, "durationS" to ctx.lastDurationS,
                "leftS" to ctx.lastDurationS - position, "lastReportAgoMs" to now - ctx.lastAtMs,
                "to" to snapshot?.title, "toMediaId" to snapshot?.mediaId, "state" to snapshot?.state)
        )
    }

    private fun execute(source: SessionWatcher.Handle, snapshot: SessionSnapshot?, d: GuardReactor.Decision) {
        val now = SystemClock.elapsedRealtime()
        timerJob?.cancel()
        timerJob = null
        if (d.clearEnded) {
            ctx = ctx.copy(ended = false)
            _songEnded.value = false
            DebugLog.log(
                "guard", "playhead moved back, not ended any more",
                mapOf("positionS" to snapshot?.positionS(now), "durationS" to snapshot?.durationS)
            )
        }
        val why = d.pause
        if (why != null) {
            try {
                source.controller()?.transportControls?.pause()
            } catch (e: Throwable) {
                DebugLog.log("guard", "pause threw", mapOf("why" to why.name.lowercase(), "error" to e))
            }
            ctx = ctx.copy(pausesSent = ctx.pausesSent + 1)
            if (why == GuardReactor.Why.SWITCH) {
                if (switchPausedAt == null) switchPausedAt = now
                DebugLog.log(
                    "guard", "switch paused",
                    mapOf("from" to ctx.guardedTitle, "fromMediaId" to ctx.guardedId, "to" to snapshot?.title,
                        "toMediaId" to snapshot?.mediaId, "stateBefore" to snapshot?.state, "pauses" to ctx.pausesSent)
                )
            } else {
                DebugLog.log(
                    "guard", if (why == GuardReactor.Why.END) "end paused" else "end zone play paused",
                    mapOf("positionS" to snapshot?.positionS(now), "durationS" to snapshot?.durationS,
                        "leftS" to snapshot?.let { it.durationS - it.positionS(now) }, "pauses" to ctx.pausesSent,
                        "song" to ctx.guardedTitle)
                )
            }
        }
        if (d.endNow) {
            ctx = ctx.copy(ended = true)
            _songEnded.value = true
            _ended.tryEmit(EndEvent(ctx.guardedTitle, d.endReason))
        }
        val delayMs = d.timerMs
        if (delayMs != null) {
            DebugLog.log(
                "guard", "end timer armed",
                mapOf("delayMs" to delayMs, "positionS" to snapshot?.positionS(now), "durationS" to snapshot?.durationS,
                    "zoneS" to EndDecision.END_ZONE_S)
            )
            timerJob = scope.launch {
                delay(delayMs)
                onTimer(source)
            }
        }
    }

    /** Logs how long another item may have been heard, once the report shows that it does not play any more. */
    private fun confirmSwitchPause(snapshot: SessionSnapshot?) {
        val since = switchPausedAt ?: return
        if (snapshot == null || !StopConfirm.isStopped(snapshot.state)) return
        switchPausedAt = null
        DebugLog.log("guard", "switch confirmed", mapOf("pausedMs" to SystemClock.elapsedRealtime() - since, "state" to snapshot.state))
    }
}
