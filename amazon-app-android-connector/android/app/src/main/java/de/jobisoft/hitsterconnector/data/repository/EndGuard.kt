package de.jobisoft.hitsterconnector.data.repository

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jobisoft.hitsterconnector.data.debug.DebugLog
import de.jobisoft.hitsterconnector.data.media.AmazonMusicListener
import de.jobisoft.hitsterconnector.util.GuardModel
import de.jobisoft.hitsterconnector.util.MuteHold
import de.jobisoft.hitsterconnector.util.MutePhase
import de.jobisoft.hitsterconnector.util.SongIdentity
import de.jobisoft.hitsterconnector.util.StopConfirm
import de.jobisoft.hitsterconnector.util.StreamMute
import de.jobisoft.hitsterconnector.util.SwitchDetector
import de.jobisoft.hitsterconnector.util.Safe
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
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The song reached its last second and its sound was switched off for the end ([reason] "end muted").
 * The quiz mode plays the next card on it. [song] is the title that was guarded.
 */
data class EndEvent(val song: String?, val mediaId: String?, val reason: String)

/**
 * Protects the card song of this app, from its start to the next request, in two ways:
 *
 *  1. **The switch detector** is the guard. It watches the media session of Amazon Music (callback on
 *     the session, skipping, another queue item, and a poll once a second as a fallback). If the session
 *     is on another item than the card song and plays, it pauses at once. This holds for the whole
 *     life of the card song, also after the song has ended: Amazon may go on to the next item of its
 *     queue (build 1242, "Padam Padam" then "The Motto"), and that must not be heard. It is the only
 *     thing that pauses.
 *  2. **Every song ends muted.** One second before the end the music stream is muted (not paused), the
 *     song plays to its true end in silence. The stream stays muted until the next card has jumped
 *     to its start offset (the play path unmutes then), see [StreamMute].
 *
 * The guard never disarms itself. It is disarmed by a new request (a scan, the next quiz card, "Play
 * again"), by the stop button, by leaving the screen, and by a watch timeout of 30 minutes.
 *
 * It does not depend on any screen of the app. It has its own scope, and it runs as long as the
 * process lives, which the bound NotificationListenerService (notification access) keeps up while
 * the app is in the background or the screen is off. A partial wake lock keeps the CPU awake for the
 * ticker (the mute at the end has to come on time, with the screen off as well).
 *
 * Every step is in the debug log with the tag "guard".
 */
@Singleton
class EndGuard @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TICK_MS = 250L
        private const val END_WINDOW_S = 1.0f
        private const val SWITCH_POLL_MS = 1_000L
        private const val HEARTBEAT_MS = 5_000L
        private const val WAKE_LOCK_MS = 35 * 60_000L
        private const val WATCH_MAX_MS = 30 * 60_000L
        private const val PAUSE_CHECK_MS = 300L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + Safe.handler)
    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val switching = Mutex()

    /** What the guard is doing, without Android. Tested. */
    private val model = GuardModel()

    @Volatile private var guardedId: String? = null
    @Volatile private var guardedTitle: String? = null
    @Volatile private var guardedQueueItem: Long = PlaybackState.PLAYBACK_POSITION_UNKNOWN
    @Volatile private var registered: MediaController? = null
    @Volatile private var watchSince = 0L
    @Volatile private var lastSwitchKey: String? = null

    private val _ended = MutableSharedFlow<EndEvent>(extraBufferCapacity = 4)

    /** Emitted when a song is muted for its end. The quiz mode plays the next card on it. */
    val ended: SharedFlow<EndEvent> = _ended.asSharedFlow()

    private val _songEnded = MutableStateFlow(false)

    /** True from the muted end of the song until the next request of the app. The screen may use it. */
    val songEnded: StateFlow<Boolean> = _songEnded.asStateFlow()

    // ── the session ───────────────────────────────────────────────────────────────

    private fun amazonController(): MediaController? {
        val enabled = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context)
        if (!enabled.contains(context.packageName)) return null
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        return try {
            manager.getActiveSessions(ComponentName(context, AmazonMusicListener::class.java))
                .firstOrNull { it.packageName == ConnectorRepository.AMAZON_MUSIC }
        } catch (_: Exception) {
            null
        }
    }

    private fun positionOf(state: PlaybackState?): Float {
        if (state == null) return 0f
        var position = state.position.toFloat()
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            position += (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed
        }
        return (position / 1000f).coerceAtLeast(0f)
    }

    private fun durationOf(meta: MediaMetadata?): Float {
        val ms = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        return if (ms > 0) ms / 1000f else 0f
    }

    // ── the callback: the switch detector ────────────────────────────────────────

    // The callback runs on the main thread: nothing in it may throw, an exception here would end the app.
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            try {
                if (!model.watching) return
                val at = SystemClock.elapsedRealtime()
                scope.launch { switchCheck("metadata changed", at) }
            } catch (e: Throwable) {
                DebugLog.log("guard", "callback onMetadataChanged failed", mapOf("error" to e))
            }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            try {
                if (!model.watching || state == null) return
                val at = SystemClock.elapsedRealtime()
                val skipping = state.state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
                    state.state == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM
                val newItem = guardedQueueItem >= 0 && state.activeQueueItemId >= 0 && state.activeQueueItemId != guardedQueueItem
                // an item may start to play without any metadata change, so every move to PLAYING is looked at
                if (skipping || newItem || StopConfirm.needsStop(state.state)) {
                    val why = if (skipping) "player is skipping" else if (newItem) "another queue item" else "state changed"
                    scope.launch { switchCheck(why, at) }
                }
            } catch (e: Throwable) {
                DebugLog.log("guard", "callback onPlaybackStateChanged failed", mapOf("error" to e))
            }
        }
    }

    private fun ensureRegistered(controller: MediaController) {
        val current = registered
        if (current != null && current.sessionToken == controller.sessionToken) return
        main.post {
            try {
                registered?.unregisterCallback(callback)
            } catch (_: Exception) {
            }
            try {
                controller.registerCallback(callback, main)
                registered = controller
                DebugLog.log("guard", "callback registered")
            } catch (e: Throwable) {
                DebugLog.log("guard", "registering the callback failed", mapOf("error" to e))
            }
        }
    }

    private fun unregister() {
        main.post {
            try {
                registered?.unregisterCallback(callback)
            } catch (_: Exception) {
            }
            registered = null
        }
    }

    // ── arming ────────────────────────────────────────────────────────────────────

    /**
     * Guard the song that plays now. Call after the app started it, and after every move of the playhead
     * (the song is then no longer at its end: an end mute that was still on is undone).
     */
    @Synchronized
    fun arm(reason: String) {
        val controller = amazonController()
        if (controller == null) {
            DebugLog.log("guard", "not armed, Amazon Music has no session", mapOf("reason" to reason))
            return
        }
        val meta = controller.metadata
        guardedId = meta?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        guardedTitle = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        guardedQueueItem = controller.playbackState?.activeQueueItemId ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN
        lastSwitchKey = null
        watchSince = SystemClock.elapsedRealtime()
        val wasWatching = model.watching
        val wasEndMuted = model.arm()
        _songEnded.value = false
        if (wasEndMuted) StreamMute.unmute(context, "playhead moved, the song is not at its end any more")
        acquireWakeLock()
        ensureRegistered(controller)
        DebugLog.log(
            "guard", if (wasWatching) "re-armed" else "armed",
            mapOf("reason" to reason, "song" to guardedTitle, "mediaId" to guardedId,
                "durationS" to durationOf(meta), "positionS" to positionOf(controller.playbackState),
                "queueItem" to guardedQueueItem)
        )
        // always a fresh ticker
        job?.cancel()
        job = scope.launch { ticker() }
    }

    /**
     * A new request, the stop button, the screen left, the watch timeout: nothing to watch any more.
     * The guard does not call this itself. A mute for the end stays, the play path undoes it.
     */
    @Synchronized
    fun disarm(reason: String) {
        if (!model.disarm()) return
        _songEnded.value = false
        job?.cancel()
        job = null
        releaseWakeLock()
        unregister()
        DebugLog.log("guard", "disarmed", mapOf("reason" to reason))
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

    // ── the ticker: the mute at the end, the switch poll, the heartbeat ──────────

    private suspend fun ticker() {
        var lastBeat = 0L
        var lastPoll = 0L
        while (model.watching) {
            delay(TICK_MS)
            if (!model.watching) break
            val now = SystemClock.elapsedRealtime()
            if (now - watchSince > WATCH_MAX_MS) {
                disarm("watch timeout")
                break
            }
            val controller = amazonController()
            val state = controller?.playbackState
            val meta = controller?.metadata
            val duration = durationOf(meta)
            val position = positionOf(state)
            val playing = state?.state == PlaybackState.STATE_PLAYING
            if (controller != null) ensureRegistered(controller)

            if (DebugLog.ENABLED && now - lastBeat >= HEARTBEAT_MS) {
                lastBeat = now
                DebugLog.log(
                    "guard", "heartbeat",
                    mapOf("session" to (controller != null), "playing" to playing, "positionS" to position,
                        "durationS" to duration, "leftS" to if (duration > 0) duration - position else null,
                        "muteAtEndDone" to model.endMuted, "songEnded" to model.songEnded,
                        "callbackRegistered" to (registered != null), "wakeLockHeld" to (wakeLock?.isHeld == true))
                )
            }
            if (controller == null) continue

            // the end: mute (once), no pause
            if (model.onTick(playing, duration, position, END_WINDOW_S)) {
                StreamMute.mute(context, MuteHold.holdMs(MutePhase.END))
                _songEnded.value = true
                DebugLog.log(
                    "guard", "mute at end",
                    mapOf("positionS" to position, "durationS" to duration, "leftS" to duration - position, "song" to guardedTitle)
                )
                _ended.tryEmit(EndEvent(guardedTitle, guardedId, "end muted"))
            }

            // the fallback of the switch detector, in case a callback is missed
            if (now - lastPoll >= SWITCH_POLL_MS) {
                lastPoll = now
                switchCheck("poll", now)
            }
        }
    }

    // ── the switch detector ──────────────────────────────────────────────────────

    /**
     * If the session is on another item than the guarded song and plays: pause, check after 300 ms, pause
     * again if it still plays. Logs `switch detected` once per item with `leakedMs`, the time from the
     * callback (or the poll) to the confirmed pause: how long the other item may have been heard
     * (silent if the stream is muted at that time, as after the end).
     */
    private suspend fun switchCheck(source: String, detectedAt: Long) {
        if (!model.watching) return
        if (!switching.tryLock()) return // another check is on it
        try {
            val controller = amazonController() ?: return
            val meta = controller.metadata
            val state = controller.playbackState?.state
            val id = meta?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            val key = "$id|$title"
            if (!SwitchDetector.shouldPause(model.watching, guardedId, guardedTitle, id, title, state)) {
                // another item that does not play: nothing to do, but worth a line, once
                if ((id != null || title != null) && !SongIdentity.same(guardedId, guardedTitle, id, title) && key != lastSwitchKey) {
                    lastSwitchKey = key
                    DebugLog.log(
                        "guard", "switch detected, not playing, left alone",
                        mapOf("from" to guardedTitle, "to" to title, "toMediaId" to id, "state" to state, "source" to source)
                    )
                }
                return
            }
            var paused = false
            for (attempt in 1..2) {
                try {
                    controller.transportControls.pause()
                } catch (e: Throwable) {
                    DebugLog.log("guard", "pause threw", mapOf("attempt" to attempt, "error" to e))
                }
                val until = SystemClock.elapsedRealtime() + PAUSE_CHECK_MS
                while (SystemClock.elapsedRealtime() < until) {
                    delay(50)
                    val after = (amazonController() ?: controller).playbackState?.state
                    if (!StopConfirm.needsStop(after)) {
                        paused = true
                        break
                    }
                }
                if (paused) break
            }
            val leakedMs = SystemClock.elapsedRealtime() - detectedAt
            lastSwitchKey = key
            DebugLog.log(
                "guard", "switch detected",
                mapOf("from" to guardedTitle, "fromMediaId" to guardedId, "to" to title, "toMediaId" to id,
                    "stateBefore" to state, "source" to source, "paused" to paused, "leakedMs" to leakedMs,
                    "streamMutedByUs" to StreamMute.isMutedByUs())
            )
        } finally {
            switching.unlock()
        }
    }
}
