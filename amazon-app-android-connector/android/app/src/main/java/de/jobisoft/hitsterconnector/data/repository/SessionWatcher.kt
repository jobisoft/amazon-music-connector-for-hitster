package de.jobisoft.hitsterconnector.data.repository

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import de.jobisoft.hitsterconnector.data.debug.DebugLog
import de.jobisoft.hitsterconnector.util.SessionSnapshot
import kotlinx.coroutines.channels.Channel

/**
 * The reports of the media session of one app as a stream of [SessionSnapshot]. It listens to the list of
 * active sessions (the session appears or goes away) and, once there is one, to the changes of its playback
 * state and its metadata. Nothing is polled. The callbacks arrive on a thread of their own, so a busy main
 * thread does not delay them.
 *
 * Needs the notification access of the app, like [MediaSessionManager.getActiveSessions].
 */
class SessionWatcher(
    private val context: Context,
    private val listenerComponent: ComponentName,
    private val packageName: String
) {
    /** One report: the snapshot, or null when the app has no session (any more). */
    data class Event(val snapshot: SessionSnapshot?)

    /** An open subscription. [close] unregisters everything, call it in a `finally`. */
    inner class Handle internal constructor() {
        val events = Channel<Event>(Channel.UNLIMITED)

        private val lock = Any()
        private val thread = HandlerThread("session-events").apply { start() }
        private val handler = Handler(thread.looper)
        private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

        @Volatile
        private var controller: MediaController? = null
        private var state: PlaybackState? = null
        private var metadata: MediaMetadata? = null
        private var closed = false

        private val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                synchronized(lock) { this@Handle.state = state }
                emit()
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                synchronized(lock) { this@Handle.metadata = metadata }
                emit()
            }

            override fun onSessionDestroyed() {
                detach()
                emit()
            }
        }

        private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list) }

        init {
            try {
                manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
                attach(manager.getActiveSessions(listenerComponent), force = true)
            } catch (e: Throwable) {
                DebugLog.log("watch", "could not listen to the sessions", mapOf("error" to e))
                events.trySend(Event(null))
            }
        }

        /** The controller of the session, to send pause, seekTo and play to. Null without a session. */
        fun controller(): MediaController? = controller

        /** The state right now, for the moment a deadline passed without a report. */
        fun current(): SessionSnapshot? = synchronized(lock) {
            val c = controller ?: return null
            val live = try {
                c.playbackState to c.metadata
            } catch (_: Throwable) {
                state to metadata
            }
            state = live.first
            metadata = live.second
            snapshotOf()
        }

        private fun attach(list: List<MediaController>?, force: Boolean = false) {
            val found = list?.firstOrNull { it.packageName == packageName }
            synchronized(lock) {
                if (closed) return
                if (!force && found?.sessionToken == controller?.sessionToken) return
                controller?.let { runCatching { it.unregisterCallback(callback) } }
                controller = found
                state = found?.playbackState
                metadata = found?.metadata
                found?.registerCallback(callback, handler)
            }
            emit()
        }

        private fun detach() {
            synchronized(lock) {
                controller?.let { runCatching { it.unregisterCallback(callback) } }
                controller = null
                state = null
                metadata = null
            }
        }

        private fun snapshotOf(): SessionSnapshot? {
            controller ?: return null
            val st = state
            val md = metadata
            return SessionSnapshot(
                state = st?.state ?: PlaybackState.STATE_NONE,
                positionMs = st?.position ?: 0L,
                updatedAtMs = st?.lastPositionUpdateTime ?: 0L,
                speed = st?.playbackSpeed ?: 0f,
                actions = st?.actions ?: 0L,
                mediaId = md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            )
        }

        private fun emit() {
            val snapshot = synchronized(lock) { if (closed) return else snapshotOf() }
            events.trySend(Event(snapshot))
        }

        fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                controller?.let { runCatching { it.unregisterCallback(callback) } }
                controller = null
            }
            runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
            events.close()
            thread.quitSafely()
        }
    }

    /** Starts listening. The first event is the state right now. */
    fun open(): Handle = Handle()
}
