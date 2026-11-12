package de.jobisoft.hitsterconnector.data.repository

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jobisoft.hitsterconnector.MainActivity
import de.jobisoft.hitsterconnector.data.debug.DebugLog
import de.jobisoft.hitsterconnector.util.AppForeground
import de.jobisoft.hitsterconnector.util.AmazonId
import de.jobisoft.hitsterconnector.util.CrashReporter
import de.jobisoft.hitsterconnector.util.Safe
import de.jobisoft.hitsterconnector.util.LeaveScreen
import de.jobisoft.hitsterconnector.util.PlayAction
import de.jobisoft.hitsterconnector.util.EndDecision
import de.jobisoft.hitsterconnector.util.PlayDecision
import de.jobisoft.hitsterconnector.util.PlayFlow
import de.jobisoft.hitsterconnector.util.PlayReactor
import de.jobisoft.hitsterconnector.util.PlayStatus
import de.jobisoft.hitsterconnector.util.PlayWait
import de.jobisoft.hitsterconnector.util.RestartAction
import de.jobisoft.hitsterconnector.util.RestartDecision
import de.jobisoft.hitsterconnector.util.SessionSnapshot
import de.jobisoft.hitsterconnector.util.SongIdentity
import de.jobisoft.hitsterconnector.util.StartJump
import de.jobisoft.hitsterconnector.util.PreferIdSetting
import de.jobisoft.hitsterconnector.util.StartSetting
import de.jobisoft.hitsterconnector.util.SearchExtras
import de.jobisoft.hitsterconnector.util.SearchQuery
import de.jobisoft.hitsterconnector.util.StateNames
import de.jobisoft.hitsterconnector.util.StopConfirm
import de.jobisoft.hitsterconnector.util.TitleMatch
import de.jobisoft.hitsterconnector.util.StreamGuard
import de.jobisoft.hitsterconnector.util.VolumeDuck
import de.jobisoft.hitsterconnector.data.media.AmazonMusicListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Where the song is, in seconds. */
data class PlayerPosition(val position: Float, val duration: Float, val playing: Boolean, val stopped: Boolean = false)

/**
 * Plays songs in the Amazon Music app of this phone and controls them.
 *
 *  - Start: the media session of Amazon Music gets the track id of the card (playFromUri), or a text
 *    search (playFromSearch). Without a session, Amazon Music is woken by a media button key, or by the
 *    play-from-search intent as the last resort.
 *  - Control: the MediaSession of Amazon Music, found through MediaSessionManager. Android
 *    allows this only for an app with notification access (see [AmazonMusicListener]).
 *
 * The README section "Observed on a phone" lists what was seen on a real phone.
 */
@Singleton
class ConnectorRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val endGuard: EndGuard
) {

    companion object {
        const val AMAZON_MUSIC = "com.amazon.mp3"

        private const val LAUNCH_WAIT_MS = 8_000L
        private const val MEDIA_BUTTON_WAIT_MS = 3_000L
        private const val BRING_BACK_MS = 8_000L
        private const val ASK_ATTEMPTS = 3
        private const val ASK_PAUSE_MS = 1_500L
        private const val SEARCH_STEP_MS = 4_000L

        /** Time for a search result to start playing once the session shows it. */
        private const val SEARCH_START_MS = 4_500L
        private const val STALE_ITEM_MS = 1_500L

        /** Time for the session to show the requested id after the address was handed over. */
        private const val ID_STEP_MS = PlayFlow.LOAD_MS

        /** The top level domain of the address of the track. The Amazon IDs of the card sets are IDs of the German store. */
        private const val STORE_TLD = "de"

        /** The step name of the id route, it is also `PlayAnswer.via` when this step made the song play. */
        const val ID_STEP_NAME = "i-playFromUri"
    }

    /** Set when a card has been scanned, to log the time until the song plays at its start offset. */
    @Volatile
    private var scannedAt = 0L

    fun markScan() {
        scannedAt = SystemClock.elapsedRealtime()
    }

    /**
     * Before a new request: pauses the song that plays and waits until the player says it stopped
     * (PAUSED, STOPPED or NONE), checking every 100 ms. True at once if nothing plays. After
     * [timeoutMs] it gives up with false, the caller goes on anyway so the app never hangs.
     * The end guard is disarmed first, a stopped song needs no guard.
     */
    suspend fun stopAndConfirm(timeoutMs: Long = 3000): Boolean = withContext(Dispatchers.IO) {
        val result = StopConfirm.run(
            readState = { amazonController()?.playbackState?.state },
            pause = {
                endGuard.disarm("stop before a new request")
                try {
                    amazonController()?.transportControls?.pause()
                } catch (e: Throwable) {
                    DebugLog.log("stop", "pause threw", mapOf("error" to e))
                }
            },
            now = { SystemClock.elapsedRealtime() },
            sleep = { delay(it) },
            timeoutMs = timeoutMs
        )
        DebugLog.log(
            "stop", if (result.confirmed) "before new request" else "stop not confirmed, going on anyway",
            mapOf("needed" to result.needed, "confirmed" to result.confirmed,
                "polls" to result.polls, "elapsedMs" to result.elapsedMs)
        )
        result.confirmed
    }

    /** The song of the last successful play request: what "Play again" replays, and what the buttons check against. */
    private data class LastSong(
        val title: String,
        val artist: String,
        val year: Int?,
        val card: String?,
        val mediaId: String?,
        /** The title as the session of Amazon Music shows it. */
        val sessionTitle: String?,
        /** The Amazon track id of the card, played first if there is one. */
        val amazonId: String? = null,
        /** The album of the card set, sent along with a search. */
        val album: String? = null
    )

    @Volatile
    private var lastRequested: LastSong? = null

    /** The user pressed Stop: paused and rewound to the start offset. Cleared by play, restart, seek, +-10 s and a new card. */
    @Volatile
    private var stoppedByUser = false

    /** True from the end of the song until the next request, see [EndGuard.songEnded]. */
    val songEnded get() = endGuard.songEnded

    /**
     * True if the playhead of the player is in the end zone of its song. That is the song that was paused for
     * its end, and also a playhead that somebody put there by hand. Resuming from there would play inside the
     * zone, so "Play" and "Play again" start the card song again from its start offset instead.
     */
    private fun atEndOfSong(controller: MediaController?): Boolean =
        controller != null && EndDecision.inEndZone(positionOf(controller.playbackState), durationOf(controller))

    /** Is the player on the song we asked for? Without a request yet there is nothing to compare, then yes. */
    private fun onRequestedSong(controller: MediaController): Boolean {
        val last = lastRequested ?: return true
        val meta = controller.metadata
        return SongIdentity.same(
            last.mediaId, last.sessionTitle ?: last.title,
            meta?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        )
    }

    /**
     * [via] is the step that made the song play, [shownMediaId] the id the session of Amazon Music shows.
     */
    data class PlayAnswer(
        val ok: Boolean,
        val message: String?,
        val via: String? = null,
        val shownMediaId: String? = null
    )

    // ── set up ────────────────────────────────────────────────────────────────────

    fun isAmazonMusicInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(AMAZON_MUSIC, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    private fun listenerComponent() = ComponentName(context, AmazonMusicListener::class.java)

    private var lastSessionError: String? = null

    /** All active media sessions of the phone (empty without notification access). */
    private fun sessions(): List<MediaController> {
        if (!hasNotificationAccess()) return emptyList()
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        return try {
            manager.getActiveSessions(listenerComponent())
        } catch (e: Exception) {
            if (lastSessionError != e.toString()) {
                lastSessionError = e.toString()
                DebugLog.log("sessions", "getActiveSessions failed", mapOf("error" to e))
            }
            emptyList()
        }
    }

    /** The media session of Amazon Music, if the app has one at the moment. */
    private fun amazonController(): MediaController? = sessions().firstOrNull { it.packageName == AMAZON_MUSIC }

    // ── what the log shows ────────────────────────────────────────────────────────

    private fun stateName(state: Int?): String = StateNames.of(state)

    private val actionNames = listOf(
        PlaybackState.ACTION_STOP to "STOP", PlaybackState.ACTION_PAUSE to "PAUSE",
        PlaybackState.ACTION_PLAY to "PLAY", PlaybackState.ACTION_REWIND to "REWIND",
        PlaybackState.ACTION_SKIP_TO_PREVIOUS to "SKIP_TO_PREVIOUS", PlaybackState.ACTION_SKIP_TO_NEXT to "SKIP_TO_NEXT",
        PlaybackState.ACTION_FAST_FORWARD to "FAST_FORWARD", PlaybackState.ACTION_SET_RATING to "SET_RATING",
        PlaybackState.ACTION_SEEK_TO to "SEEK_TO", PlaybackState.ACTION_PLAY_PAUSE to "PLAY_PAUSE",
        PlaybackState.ACTION_PLAY_FROM_MEDIA_ID to "PLAY_FROM_MEDIA_ID",
        PlaybackState.ACTION_PLAY_FROM_SEARCH to "PLAY_FROM_SEARCH",
        PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM to "SKIP_TO_QUEUE_ITEM", PlaybackState.ACTION_PLAY_FROM_URI to "PLAY_FROM_URI",
        PlaybackState.ACTION_PREPARE to "PREPARE", PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID to "PREPARE_FROM_MEDIA_ID",
        PlaybackState.ACTION_PREPARE_FROM_SEARCH to "PREPARE_FROM_SEARCH",
        PlaybackState.ACTION_PREPARE_FROM_URI to "PREPARE_FROM_URI",
        PlaybackState.ACTION_SET_PLAYBACK_SPEED to "SET_PLAYBACK_SPEED"
    )

    private fun decodeActions(mask: Long): List<String> =
        actionNames.filter { mask and it.first != 0L }.map { it.second }

    private fun describe(controller: MediaController): Map<String, Any?> {
        val state = controller.playbackState
        val meta = controller.metadata
        return mapOf(
            "package" to controller.packageName,
            "state" to stateName(state?.state),
            "positionMs" to state?.position,
            "speed" to state?.playbackSpeed,
            "actionsMask" to state?.actions,
            "actions" to state?.let { decodeActions(it.actions) },
            "error" to state?.errorMessage?.toString(),
            "title" to meta?.getString(MediaMetadata.METADATA_KEY_TITLE),
            "artist" to meta?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            "durationMs" to meta?.getLong(MediaMetadata.METADATA_KEY_DURATION),
            "mediaId" to meta?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        )
    }

    private fun describeSessions(): List<Map<String, Any?>> = sessions().map { describe(it) }

    @Suppress("DEPRECATION") // Bundle.get is the only way to print any kind of extra
    private fun describe(intent: Intent): Map<String, Any?> {
        val extras = intent.extras
        return mapOf(
            "action" to intent.action,
            "package" to intent.`package`,
            "component" to intent.component?.flattenToShortString(),
            "data" to intent.dataString,
            "type" to intent.type,
            "flags" to "0x" + Integer.toHexString(intent.flags),
            "extras" to extras?.keySet()?.associateWith { key -> extras.get(key)?.toString() },
            "uri" to intent.toUri(0)
        )
    }

    /** Which activities answer to the intent (the phone's own view, depends on package visibility). */
    private fun resolution(intent: Intent): Map<String, Any?> {
        val pm = context.packageManager
        return try {
            val resolved = pm.resolveActivity(intent, 0)?.activityInfo
            val all = pm.queryIntentActivities(intent, 0).map {
                "${it.activityInfo.packageName}/${it.activityInfo.name} exported=${it.activityInfo.exported}"
            }
            mapOf(
                "resolveActivity" to resolved?.let { "${it.packageName}/${it.name}" },
                "queryIntentActivities" to all
            )
        } catch (e: Exception) {
            mapOf("error" to e)
        }
    }

    private fun precondition(): Exception? = when {
        !isAmazonMusicInstalled() -> Exception("The Amazon Music app is not installed on this phone.")
        !hasNotificationAccess() -> Exception("Please give this app notification access (Home screen, Setup).")
        else -> null
    }

    // ── reading the player ────────────────────────────────────────────────────────

    /** Position in seconds. While it plays, the last report is carried on with the clock. */
    private fun positionOf(state: PlaybackState?): Float {
        if (state == null) return 0f
        var position = state.position.toFloat()
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            position += (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed
        }
        return (position / 1000f).coerceAtLeast(0f)
    }

    private fun durationOf(controller: MediaController): Float {
        val ms = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        return if (ms > 0) ms / 1000f else 0f
    }

    private fun snapshot(controller: MediaController): PlayerPosition {
        val state = controller.playbackState
        return PlayerPosition(
            position = positionOf(state),
            duration = durationOf(controller),
            playing = state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING,
            stopped = stoppedByUser
        )
    }

    /** Where a song of this length starts, by the start position of the Home screen (see [StartJump.startFor]). */
    private fun startFor(duration: Float): Float = StartJump.startFor(duration, StartSetting.get(context))

    // ── play ──────────────────────────────────────────────────────────────────────

    /** What the load screen says under the sending text while a request runs, null for the default text. */
    private val _playStatus = MutableStateFlow<String?>(null)
    val playStatus: StateFlow<String?> = _playStatus.asStateFlow()


    @Volatile
    private var queueLogged = false

    /**
     * The extras of a search: focus, title, artist, the album if the card set has one, and the query (see [SearchExtras]).
     */
    private fun extrasFor(title: String, artist: String, album: String?) = Bundle().apply {
        for ((name, value) in SearchExtras.fields(title, artist, album)) putString(name, value)
    }

    /** The play-from-search intent, aimed at Amazon Music. */
    private fun searchIntent(title: String, artist: String, album: String?) = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
        setPackage(AMAZON_MUSIC)
        putExtras(extrasFor(title, artist, album))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** True once this play called startActivity for Amazon Music, whose screen may then be in front. */
    @Volatile
    private var uiStarted = false

    private fun startLogged(step: String, intent: Intent): Boolean {
        DebugLog.log(step, "intent", describe(intent))
        DebugLog.log(step, "resolution", resolution(intent))
        return try {
            context.startActivity(intent)
            uiStarted = true
            DebugLog.log(step, "startActivity returned without exception")
            true
        } catch (e: Throwable) {
            DebugLog.log(step, "startActivity threw", mapOf("error" to e))
            false
        }
    }

    /** Hands the search to the session, false if it could not even try. */
    private fun sendSearch(controller: MediaController?, title: String, artist: String, album: String?): Boolean {
        if (controller == null) {
            DebugLog.log("b-playFromSearch", "skipped, Amazon Music has no active session", mapOf("sessions" to describeSessions()))
            return false
        }
        DebugLog.log("b-playFromSearch", "calling transportControls.playFromSearch", mapOf("session" to describe(controller)))
        return try {
            val query = SearchQuery.query(title, artist)
            DebugLog.log("search", "query", mapOf("wantedTitle" to title, "wantedArtist" to artist, "wantedAlbum" to album, "querySent" to query))
            controller.transportControls.playFromSearch(query, extrasFor(title, artist, album))
            true
        } catch (e: Throwable) {
            DebugLog.log("b-playFromSearch", "threw", mapOf("error" to e))
            false
        }
    }

    /** Hands the track id of the card to the session as the web address of the track with `agent=google`. False if it could not even try. */
    private fun sendId(controller: MediaController?, id: String, title: String, artist: String): Boolean {
        if (controller == null) {
            DebugLog.log(ID_STEP_NAME, "skipped, Amazon Music has no active session", mapOf("sessions" to describeSessions()))
            return false
        }
        return try {
            DebugLog.log(
                "search", "query by id",
                mapOf("amazonId" to id, "wantedTitle" to title, "wantedArtist" to artist, "session" to describe(controller))
            )
            controller.transportControls.playFromUri(Uri.parse(AmazonId.playUri(id, STORE_TLD)), Bundle())
            true
        } catch (e: Throwable) {
            DebugLog.log(ID_STEP_NAME, "threw", mapOf("error" to e))
            false
        }
    }

    /** What the session shows after the id did not show up, one event so that unknown or wrong ids are in the log. */
    private fun logIdNotShown(id: String, title: String, shown: SessionSnapshot?) {
        DebugLog.log(
            "play", "id not shown",
            mapOf(
                "amazonId" to id, "wantedTitle" to title, "budgetMs" to ID_STEP_MS,
                "shownId" to shown?.mediaId, "shownTitle" to shown?.title, "state" to StateNames.of(shown?.state)
            )
        )
    }

    private val sessionWatcher by lazy { SessionWatcher(context, listenerComponent(), AMAZON_MUSIC) }

    private val foregroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + Safe.handler)

    private fun ourImportance(): Int =
        ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance

    /**
     * After an Amazon Music screen was opened: bring this app's screen back, again and again
     * (every 400 ms, at most 8 s) until it is really resumed. "startActivity returned" does not
     * mean it worked: Android 10+ limits starts from the background, and the call may come
     * before Amazon's screen is in front. Runs on its own, the result screen does not wait for it.
     */
    private fun launchBringBack(stepsRun: List<String>) {
        val amazonScreenStarted = uiStarted
        foregroundScope.launch {
            var attempt = 0
            val begin = SystemClock.elapsedRealtime()
            if (amazonScreenStarted) {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                while (!AppForeground.resumed && SystemClock.elapsedRealtime() - begin < BRING_BACK_MS) {
                    attempt++
                    val calls = mutableListOf<String>()
                    try {
                        val task = manager.appTasks.firstOrNull()
                        task?.moveToFront()
                        calls += "AppTask.moveToFront(${if (task == null) "no task" else "ok"})"
                    } catch (e: Throwable) {
                        calls += "AppTask.moveToFront threw ${e.javaClass.simpleName}: ${e.message}"
                    }
                    try {
                        context.startActivity(
                            Intent(context, MainActivity::class.java).addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            )
                        )
                        calls += "startActivity(MainActivity) returned"
                    } catch (e: Throwable) {
                        calls += "startActivity threw ${e.javaClass.simpleName}: ${e.message}"
                    }
                    delay(400)
                    DebugLog.log(
                        "foreground", "foreground attempt $attempt",
                        mapOf("calls" to calls, "resumed" to AppForeground.resumed, "importance" to ourImportance(),
                            "afterMs" to SystemClock.elapsedRealtime() - begin)
                    )
                }
            }
            val back = AppForeground.resumed
            DebugLog.log(
                "foreground", "did Amazon Music's screen come to the front?",
                mapOf(
                    "stepsRun" to stepsRun,
                    "startActivityCalled" to amazonScreenStarted,
                    "amazonScreenMayHaveComeToFront" to amazonScreenStarted,
                    "attempts" to attempt,
                    "ourAppBroughtBack" to back,
                    "ourProcessImportance" to ourImportance(),
                    "ourProcessImportanceNote" to "100=foreground, 125=foreground service, 200=visible, 400=cached",
                    "reason" to if (amazonScreenStarted && !back) {
                        "Android did not let the app start its screen from the background (limits since Android 10, " +
                            "only the permission to display over other apps would lift them), or Amazon's screen stays in front"
                    } else null
                )
            )
        }
    }

    /**
     * Wakes Amazon Music up without opening any screen: a media button key sent to its receiver.
     * First a broadcast to its exported MediaButtonReceiver, then the same key through the system
     * (AudioManager.dispatchMediaKeyEvent). PAUSE, so that nothing starts to play by itself. Returns true
     * if a session of Amazon Music appeared within 3 s of one of the two ways, then no activity was started.
     */
    private suspend fun wakeByMediaButton(waitForSession: suspend (String, Long) -> Boolean): Boolean {
        fun key(action: Int) = KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PAUSE)
        // first way: explicit broadcast to the receiver of Amazon Music
        val receiver = ComponentName(AMAZON_MUSIC, "com.amazon.music.media.playback.impl.MediaButtonReceiver")
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            try {
                context.sendBroadcast(
                    Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver).putExtra(Intent.EXTRA_KEY_EVENT, key(action))
                )
                DebugLog.log("a0-mediaButton", "broadcast sent", mapOf("receiver" to receiver.flattenToShortString(), "keyAction" to action))
            } catch (e: Throwable) {
                DebugLog.log("a0-mediaButton", "broadcast threw", mapOf("error" to e))
            }
        }
        if (waitForSession("a0-mediaButton", MEDIA_BUTTON_WAIT_MS)) {
            DebugLog.log("a0-mediaButton", "worked: broadcast to the receiver")
            return true
        }
        // second way: through the system
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.dispatchMediaKeyEvent(key(KeyEvent.ACTION_DOWN))
            audio.dispatchMediaKeyEvent(key(KeyEvent.ACTION_UP))
            DebugLog.log("a0-mediaButton", "AudioManager.dispatchMediaKeyEvent sent")
        } catch (e: Throwable) {
            DebugLog.log("a0-mediaButton", "dispatchMediaKeyEvent threw", mapOf("error" to e))
        }
        if (waitForSession("a0-mediaButton", MEDIA_BUTTON_WAIT_MS)) {
            DebugLog.log("a0-mediaButton", "worked: AudioManager.dispatchMediaKeyEvent")
            return true
        }
        DebugLog.log("a0-mediaButton", "no session appeared, the intent has to wake Amazon Music")
        return false
    }

    /** True once a session of Amazon Music showed the requested title during this play, even if it did not play. */
    @Volatile
    private var titleLoaded = false

    /** True while the track id of the card is asked: the result is not asked again with a text search. */
    @Volatile
    private var byId = false

    /** The media id of the item that failed to start in the id route, so the search does not take it for its own result at once. */
    @Volatile
    private var staleId: String? = null

    /** The debug record of what Amazon loaded, and once per request its queue. Never throws. */
    private fun logSearchResult(title: String, artist: String, controller: MediaController) {
        try {
            val m = controller.metadata
            val shown = m?.getString(MediaMetadata.METADATA_KEY_TITLE)
            DebugLog.log(
                "search", "result",
                mapOf("wantedTitle" to title, "wantedArtist" to artist,
                    "shownTitle" to shown, "shownArtist" to m?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    "mediaId" to m?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                    "durationMs" to m?.getLong(MediaMetadata.METADATA_KEY_DURATION),
                    "viaId" to byId)
            )
            if (!queueLogged) {
                queueLogged = true
                val queue = controller.queue
                DebugLog.log(
                    "search", "queue",
                    mapOf("size" to queue?.size, "first8" to queue?.take(8)?.map { it.description?.title?.toString() })
                )
            }
        } catch (e: Throwable) {
            DebugLog.log("search", "logging failed", mapOf("error" to e))
        }
    }

    /**
     * Plays the song: load it, pause, seek to the start offset, play (see [PlayDrive]). Before anything else
     * the music stream is unmuted if it is muted, and the volume duck starts (see [VolumeDuck]).
     */
    suspend fun play(
        title: String,
        artist: String,
        year: Int?,
        card: String?,
        amazonId: String? = null,
        album: String? = null
    ): Result<PlayAnswer> {
        StreamGuard.ensureAudible(context)
        VolumeDuck.duck(context)
        _playStatus.value = null
        return try {
            // the Options switch "Prefer Amazon ID over search": off, the card is searched like one without an id
            playInner(title, artist, year, card, amazonId.takeIf { PreferIdSetting.useId(PreferIdSetting.isEnabled(context), it) }, album)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // an exception in the play path is an error box with its text, not a crash. It is also kept in
            // last_crash.txt (the Home screen shows it), and the debug log has it
            Log.e("HitsterConnector", "play path threw", e)
            CrashReporter.record(context, "play path", e)
            DebugLog.log("play", "play path threw", mapOf("error" to e))
            Result.failure(Exception("Unexpected error in the play path: ${e.javaClass.simpleName}: ${e.message}"))
        } finally {
            _playStatus.value = null
            // every way out of the request (failure, cancel, exception): the level goes back, a second call finds nothing
            VolumeDuck.restore(context, "request ended")
        }
    }

    private suspend fun playInner(
        title: String,
        artist: String,
        year: Int?,
        card: String?,
        amazonId: String?,
        album: String?
    ): Result<PlayAnswer> = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        // the new song is "another song" for the guard of the old one
        endGuard.disarm("new play request")
        stoppedByUser = false
        DebugLog.log(
            "play", "request",
            mapOf("title" to title, "artist" to artist, "installed" to isAmazonMusicInstalled(),
                "notificationAccess" to hasNotificationAccess(), "sessionsBefore" to describeSessions())
        )
        precondition()?.let {
            DebugLog.log("play", "precondition failed", mapOf("error" to it.message))
            return@withContext Result.failure(it)
        }

        var winner: String? = null

        /** A phase begins: status text and one `phase` event. */
        fun enterPhase(phase: PlayFlow.Phase) {
            _playStatus.value = PlayStatus.forPhase(phase)
            DebugLog.log("play", "phase", mapOf("phase" to phase.name.lowercase(), "sinceRequestMs" to SystemClock.elapsedRealtime() - started))
        }

        uiStarted = false
        titleLoaded = false
        staleId = null
        queueLogged = false
        byId = false
        val stepsRun = mutableListOf<String>()
        val sessionAtStart = amazonController()
        val bareSession = sessionAtStart != null && PlayFlow.isBareSession(
            hasTitle = !sessionAtStart.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrEmpty(),
            state = sessionAtStart.playbackState?.state ?: PlaybackState.STATE_NONE,
            actions = sessionAtStart.playbackState?.actions ?: 0L
        )
        // a bare session (no title, nothing to control) is as good as none: the wake runs and the loaded item settles
        val sessionExisted = sessionAtStart != null && !bareSession
        val coldStart = !sessionExisted
        DebugLog.log("play", "plan", mapOf("amazonSessionExisted" to sessionExisted, "bareSession" to bareSession, "startPosition" to StartSetting.get(context).key))

        /** Waits for a session of Amazon Music to appear. It is the listener of the sessions that answers, no polling. */
        suspend fun waitForSession(label: String, timeoutMs: Long): Boolean {
            val handle = sessionWatcher.open()
            val appeared = try {
                withTimeoutOrNull(timeoutMs) {
                    var found = false
                    for (event in handle.events) {
                        if (event.snapshot != null) {
                            found = true
                            break
                        }
                    }
                    found
                } == true
            } finally {
                handle.close()
            }
            DebugLog.log(
                label, "wait for a session",
                mapOf("sessionAppeared" to appeared, "elapsedMs" to SystemClock.elapsedRealtime() - started,
                    "sessions" to describeSessions())
            )
            return appeared
        }

        /**
         * One load attempt of the id route, see [PlayDrive]. The session listener is open before the request goes
         * out, so no report is missed. Our item is paused at the first change, moved to the start offset and
         * played when it is paused near it. A foreign item that replaced ours ends the attempt.
         */
        suspend fun idAttempt(id: String, reload: Int): PlayDrive.Result {
            val handle = sessionWatcher.open()
            try {
                val baseline = handle.current()?.mediaId
                val ctx = PlayReactor.Ctx(
                    canReload = true, cold = coldStart, position = StartSetting.get(context), baselineMediaId = baseline,
                    limitMs = PlayWait.BUFFER_WAIT_MS
                )
                DebugLog.log(ID_STEP_NAME, "step starts", mapOf("timeoutMs" to ID_STEP_MS, "reload" to reload))
                val drive = PlayDrive(
                    handle, ID_STEP_NAME, ctx, ID_STEP_MS,
                    classify = { s, _ -> PlayFlow.classify(s.mediaId, id) },
                    onOurs = { s, controller, ms ->
                        titleLoaded = true
                        logSearchResult(title, artist, controller)
                        DebugLog.log("play", "id loaded", mapOf("amazonId" to id, "ms" to ms, "shownTitle" to s.title))
                        enterPhase(PlayFlow.Phase.START)
                    },
                    onOursState = { s -> _playStatus.value = PlayStatus.forState(s.state) },
                    onPlaySent = { VolumeDuck.restore(context, "play sent") }
                )
                return drive.run { sendId(handle.controller(), id, title, artist) }
            } finally {
                handle.close()
            }
        }

        /**
         * The id route of a card with an id: load, pause, seek, play (see [PlayDrive]). When a foreign item
         * replaced ours the id is loaded again, see [PlayFlow.afterReplaced]. False when the route fails, the
         * caller goes on with the search.
         */
        suspend fun idRoute(id: String): Boolean {
            byId = true
            val routeBegin = SystemClock.elapsedRealtime()
            var reloads = 0
            try {
                while (true) {
                    enterPhase(PlayFlow.Phase.LOAD)
                    val r = idAttempt(id, reloads)
                    val replaced = r.outcome == PlayDrive.Outcome.REPLACED ||
                        (r.outcome == PlayDrive.Outcome.START_FAILED && r.lastItem == PlayFlow.Item.FOREIGN)
                    when {
                        r.outcome == PlayDrive.Outcome.PLAYING -> {
                            if (winner == null) winner = ID_STEP_NAME
                            return true
                        }
                        replaced -> {
                            val next = PlayFlow.afterReplaced(reloads, SystemClock.elapsedRealtime() - routeBegin)
                            DebugLog.log(
                                "play", "session item replaced",
                                mapOf("wantedId" to id, "foreignTitle" to r.last?.title,
                                    "foreignMediaId" to (r.replacedBy ?: r.last?.mediaId),
                                    "phase" to r.phase, "ms" to SystemClock.elapsedRealtime() - routeBegin,
                                    "reloadsDone" to reloads, "next" to next.name.lowercase())
                            )
                            if (next != PlayFlow.Next.RELOAD) {
                                staleId = id
                                return false
                            }
                            reloads++
                        }
                        r.outcome == PlayDrive.Outcome.START_FAILED -> {
                            val state = r.last?.state ?: PlaybackState.STATE_NONE
                            staleId = id
                            DebugLog.log(
                                "play", "id start failed",
                                mapOf("amazonId" to id, "reason" to (if (PlayWait.isLoading(state)) "buffering" else "not playing"),
                                    "detail" to r.reason, "state" to stateName(state), "sessions" to describeSessions())
                            )
                            return false
                        }
                        else -> {
                            logIdNotShown(id, title, r.last)
                            staleId = null
                            return false
                        }
                    }
                }
            } finally {
                byId = false
            }
        }

        /** One attempt of the text search, see [PlayDrive]. True if the right song plays afterwards. */
        suspend fun searchAttempt(): Boolean {
            val handle = sessionWatcher.open()
            val begin = SystemClock.elapsedRealtime()
            try {
                val ctx = PlayReactor.Ctx(
                    canReload = false, cold = false, position = StartSetting.get(context), baselineMediaId = handle.current()?.mediaId,
                    limitMs = SEARCH_START_MS
                )
                DebugLog.log("b-playFromSearch", "step starts", mapOf("timeoutMs" to SEARCH_STEP_MS))
                val drive = PlayDrive(
                    handle, "b-playFromSearch", ctx, SEARCH_STEP_MS,
                    classify = { s, t ->
                        val stale = staleId
                        when {
                            stale != null && s.mediaId == stale && t - begin < STALE_ITEM_MS -> PlayFlow.Item.NONE
                            !s.hasTitle -> PlayFlow.Item.NONE
                            TitleMatch.same(s.title, title) -> PlayFlow.Item.OURS
                            else -> PlayFlow.Item.FOREIGN
                        }
                    },
                    onOurs = { s, controller, _ ->
                        titleLoaded = true
                        logSearchResult(title, artist, controller)
                        DebugLog.log("search", "final", mapOf("wantedTitle" to title, "finalShown" to s.title))
                    },
                    onOursState = {},
                    onPlaySent = { VolumeDuck.restore(context, "play sent") }
                )
                val r = drive.run { sendSearch(handle.controller(), title, artist, album) }
                val playing = r.outcome == PlayDrive.Outcome.PLAYING
                DebugLog.log(
                    "b-playFromSearch", "step outcome",
                    mapOf("songPlays" to playing, "outcome" to r.outcome.name.lowercase(), "reason" to r.reason,
                        "elapsedMs" to SystemClock.elapsedRealtime() - started, "sessions" to describeSessions())
                )
                if (playing && winner == null) winner = "b-playFromSearch"
                return playing
            } finally {
                handle.close()
            }
        }

        /**
         * The session exists: the id route for a card with an id, a web address of the track with the parameter
         * `agent=google` that the session hands to Amazon Music, so the track is loaded without opening a screen.
         * Then the text search (up to 3 tries, 1.5 s apart).
         */
        suspend fun askSession(): Boolean {
            if (amazonId != null) {
                stepsRun += "i"
                if (idRoute(amazonId)) return true
            }
            enterPhase(PlayFlow.Phase.SEARCH)
            for (n in 1..ASK_ATTEMPTS) {
                stepsRun += "b$n"
                if (PlayFlow.afterSearch(searchAttempt()) == PlayFlow.Next.PLAYING) return true
                if (n < ASK_ATTEMPTS) delay(ASK_PAUSE_MS)
            }
            return false
        }

        if (sessionExisted) {
            // warm path: no screen of Amazon Music
            askSession()
        } else {
            // cold start: first a wake without any screen (a0), only if that fails the intent (a),
            // which opens Amazon's screen and plays nothing by itself
            stepsRun += "a0"
            enterPhase(PlayFlow.Phase.WAKE)
            var session = wakeByMediaButton(::waitForSession)
            if (!session) {
                stepsRun += "a"
                val tried = startLogged("a-intent", searchIntent(title, artist, album))
                session = tried && waitForSession("a-intent", LAUNCH_WAIT_MS)
            }
            if (session) {
                askSession()
            }
        }

        val controller = amazonController()
        if (winner == null || controller == null) {
            DebugLog.log("play", "no step made the song play")
            launchBringBack(stepsRun)
            return@withContext Result.failure(
                Exception(
                    if (titleLoaded) {
                        "Amazon Music loaded the song but does not start it. Try the card again."
                    } else {
                        "Amazon Music did not play the song. It may have ignored the request or started something else."
                    }
                )
            )
        }
        DebugLog.log("play", "song plays", mapOf("step" to winner))

        // the start jump and its correction happened in the reactions to the session reports, this is the record
        try {
            DebugLog.log(
                "play", "position at the start offset",
                mapOf("position" to positionOf(controller.playbackState), "wanted" to startFor(durationOf(controller)))
            )
        } catch (e: Exception) {
            DebugLog.log("play", "reading the position failed", mapOf("error" to e))
        }
        // what "Play again" replays, and what the buttons check the player against
        val shown = controller.metadata
        lastRequested = LastSong(
            title, artist, year, card,
            shown?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), shown?.getString(MediaMetadata.METADATA_KEY_TITLE), amazonId, album
        )
        endGuard.arm("song started")
        // last: if an Amazon Music screen was opened on the way, this app's screen goes back to the front
        launchBringBack(stepsRun)
        val now = SystemClock.elapsedRealtime()
        DebugLog.log(
            "timing", "scan to song playing at its start offset",
            mapOf(
                "scanToPlayingMs" to if (scannedAt > 0) now - scannedAt else null,
                "playRequestToPlayingMs" to now - started,
                "step" to winner,
                "positionSeconds" to positionOf(amazonController()?.playbackState)
            )
        )
        Result.success(PlayAnswer(true, null, winner, shown?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)))
    }

    /**
     * "Scan next card": the song of the card is stopped like by the Stop button (pause, rewind to the start
     * offset) and the guard is disarmed, in this order, before the camera comes back.
     * Takes about as long as the Stop button, at most 2.5 s. A later scan stops again before its own request.
     */
    suspend fun stopForNextCard() = withContext(Dispatchers.IO) {
        val controller = amazonController()
        val stop = LeaveScreen.shouldStop(playedHere = true, hasSession = controller != null)
        DebugLog.log(
            "leave", "next card: stop",
            mapOf("stopping" to stop, "session" to controller?.let { describe(it) }, "songEnded" to endGuard.songEnded.value)
        )
        try {
            if (stop && controller != null) {
                val moved = kotlinx.coroutines.withTimeoutOrNull(2_500L) { pauseAndRewind(controller) }
                stoppedByUser = true
                DebugLog.log("leave", "next card: stopped", mapOf("fromS" to moved?.first, "toS" to moved?.second, "timedOut" to (moved == null)))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            DebugLog.log("leave", "next card: stop failed", mapOf("error" to e))
        }
        endGuard.disarm("next card")
        VolumeDuck.restore(context, "next card")
    }

    /** Calls the controller, and logs the call and what the session says a moment later. */
    private suspend fun ctl(controller: MediaController, name: String, arg: Any?, call: (MediaController) -> Unit) {
        DebugLog.log("ctl", name, mapOf("arg" to arg, "before" to describe(controller)))
        try {
            call(controller)
        } catch (e: Throwable) {
            DebugLog.log("ctl", "$name threw", mapOf("error" to e))
            throw e
        }
        if (DebugLog.ENABLED) {
            delay(400)
            DebugLog.log("ctl", "$name done", mapOf("after" to describe(controller)))
        }
    }

    // ── control ───────────────────────────────────────────────────────────────────

    /**
     * The stop of the Stop button: pause, and if the player is on the requested song, back to the start
     * offset (confirmed). Returns where it was and where it is now (null: not rewound).
     */
    private suspend fun pauseAndRewind(controller: MediaController): Pair<Float, Float?> {
        val now = snapshot(controller)
        ctl(controller, "pause", null) { it.transportControls.pause() }
        var rewoundTo: Float? = null
        if (onRequestedSong(controller) && now.duration > 0) {
            val target = startFor(now.duration)
            ctl(controller, "seekTo", target) { it.transportControls.seekTo((target * 1000).toLong()) }
            val until = SystemClock.elapsedRealtime() + 2000
            while (SystemClock.elapsedRealtime() < until) {
                delay(50)
                val at = amazonController()?.playbackState?.let { positionOf(it) } ?: continue
                if (StartJump.near(at, target)) { rewoundTo = at; break }
            }
        }
        return now.position to rewoundTo
    }

    private val leaveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + Safe.handler)

    /**
     * The screen with the controls was left (Back, the system back, the screen destroyed): the song is
     * stopped like by the Stop button (pause, rewind to the start offset) and the guard is disarmed, in this
     * order. It runs in a scope of the application: the navigation does not wait, and it finishes also after
     * the view model of the screen is gone. A screen that never played something leaves the player alone.
     */
    fun leaveScreen(playedHere: Boolean) {
        leaveScope.launch {
            val controller = amazonController()
            val stop = LeaveScreen.shouldStop(playedHere, controller != null)
            DebugLog.log(
                "leave", "leave screen: stop",
                mapOf("playedHere" to playedHere, "stopping" to stop, "session" to controller?.let { describe(it) },
                    "songEnded" to endGuard.songEnded.value, "stopped" to stoppedByUser)
            )
            try {
                if (stop && controller != null) {
                    val (from, rewoundTo) = pauseAndRewind(controller)
                    stoppedByUser = true
                    DebugLog.log("leave", "stopped", mapOf("fromS" to from, "toS" to rewoundTo))
                }
            } catch (e: Throwable) {
                DebugLog.log("leave", "stop failed", mapOf("error" to e))
            }
            endGuard.disarm("screen left")
            VolumeDuck.restore(context, "screen left")
        }
    }

    /**
     * "Play again". If the player is still on the requested song: null, the caller seeks to the start offset
     * and plays. If its queue went on to another song (or the session is gone): the whole play path for the
     * stored song again (stop, load, pause, seek, play). A failure or "nothing to play" is the answer.
     */
    private suspend fun restart(): Result<PlayerPosition>? {
        val last = lastRequested
        val controller = amazonController()
        val action = RestartDecision.decide(
            hasLastRequest = last != null,
            hasSession = controller != null,
            sameSong = controller != null && onRequestedSong(controller),
            songEnded = endGuard.songEnded.value || atEndOfSong(controller)
        )
        DebugLog.log("control", "restart decides: $action", mapOf("session" to controller?.let { describe(it) }))
        return when (action) {
            RestartAction.SEEK_AND_PLAY -> null
            RestartAction.NOTHING_TO_PLAY -> Result.failure(Exception("Nothing to play again."))
            RestartAction.REPLAY_STORED_SONG -> replayStoredSong(last)
        }
    }

    /** The whole start sequence for the song of the card: stop-confirm, load, pause, seek, play. */
    private suspend fun replayStoredSong(song: LastSong?): Result<PlayerPosition> {
        if (song == null) return Result.failure(Exception("Nothing to play again."))
        stopAndConfirm()
        val result = play(song.title, song.artist, song.year, song.card, song.amazonId, song.album)
        val now = amazonController()
        return if (result.isSuccess && now != null) Result.success(snapshot(now)) else Result.failure(
            result.exceptionOrNull() ?: Exception("Amazon Music did not play the song.")
        )
    }

    /**
     * "Play": after a pause by hand `play()` goes on where it was. After the end of the song, with the
     * player on another item, or without a session, the whole start sequence runs, a wrong item is never
     * resumed. After a stop it seeks to the start offset and plays like restart. Null: the caller goes on.
     */
    private suspend fun playDecision(): Result<PlayerPosition>? {
        val last = lastRequested
        val controller = amazonController()
        val decision = PlayDecision.forPlay(
            hasLastRequest = last != null,
            hasSession = controller != null,
            sameSong = controller != null && onRequestedSong(controller),
            songEnded = endGuard.songEnded.value || atEndOfSong(controller),
            stopped = stoppedByUser
        )
        return when (decision) {
            PlayAction.NOTHING_TO_PLAY -> Result.failure(Exception("Nothing to play."))
            PlayAction.FULL_SEQUENCE -> {
                DebugLog.log("control", "control play (restart sequence)", mapOf("why" to "ended, another item or no session"))
                replayStoredSong(last)
            }
            PlayAction.SEEK_AND_PLAY -> null
            PlayAction.RESUME -> null
        }
    }

    /**
     * Moves the playhead of the song: "back10", "forward10", "restart" (back to the start
     * offset, plays), "stop" (pauses and rewinds to the start offset), "seek" (to a position in seconds) or "state" (changes
     * nothing). Answers where the song is. The pause at the end of the song is done by
     * [EndGuard], which does not depend on this call.
     */
    suspend fun control(action: String, position: Float? = null): Result<PlayerPosition> =
        withContext(Dispatchers.IO) {
            precondition()?.let { return@withContext Result.failure(it) }
            if (action == "restart") {
                val restarted = restart()
                if (restarted != null) return@withContext restarted
            }
            if (action == "play") {
                val decided = playDecision()
                if (decided != null) return@withContext decided
            }
            val controller = amazonController()
                ?: return@withContext Result.failure(Exception("Amazon Music is not playing anything."))
            if (action != "state") DebugLog.log("control", "request $action", mapOf("position" to position))
            // the queue of Amazon may have gone on to another song: then these buttons must not move it
            if ((action == "back10" || action == "forward10" || action == "seek" || action == "state") && !onRequestedSong(controller)) {
                if (action != "state") {
                    DebugLog.log("control", "$action refused, the player is on another song", mapOf("session" to describe(controller)))
                }
                return@withContext Result.failure(Exception("The player is on another song"))
            }
            try {
                val now = snapshot(controller)
                suspend fun seek(seconds: Float) {
                    val max = if (now.duration > 0) (now.duration - 2f).coerceAtLeast(0f) else seconds
                    val target = seconds.coerceIn(0f, max)
                    ctl(controller, "seekTo", target) { it.transportControls.seekTo((target * 1000).toLong()) }
                }
                when (action) {
                    "back10" -> seek(now.position - 10f)
                    "forward10" -> seek(now.position + 10f)
                    "restart" -> {
                        seek(startFor(now.duration))
                        ctl(controller, "play", null) { it.transportControls.play() }
                    }
                    "pause" -> {
                        // by hand: the position stays, the guard stays armed
                        DebugLog.log("control", "control pause", mapOf("positionS" to now.position))
                        ctl(controller, "pause", null) { it.transportControls.pause() }
                    }
                    "play" -> {
                        if (stoppedByUser) {
                            DebugLog.log("control", "control play (restart sequence)", mapOf("why" to "after a stop", "positionS" to now.position))
                            seek(startFor(now.duration))
                        } else {
                            DebugLog.log("control", "control play (resume)", mapOf("positionS" to now.position))
                        }
                        ctl(controller, "play", null) { it.transportControls.play() }
                    }
                    "stop" -> {
                        // pause AND back to the start offset, the guard is disarmed
                        endGuard.disarm("user stop")
                        val (from, rewoundTo) = pauseAndRewind(controller)
                        stoppedByUser = true
                        DebugLog.log(
                            "control", "control stop (paused, rewound)",
                            mapOf("fromS" to from, "toS" to rewoundTo, "durationS" to now.duration)
                        )
                    }
                    "seek" -> seek(position ?: return@withContext Result.failure(Exception("position is required.")))
                    "state" -> Unit // only reads, the pause at the end is done by the guard
                    else -> return@withContext Result.failure(Exception("Unknown action: $action"))
                }
                // the playhead moved or the song plays again: the guard watches the new situation
                val wasStopped = stoppedByUser
                if (action == "back10" || action == "forward10" || action == "restart" || action == "seek" || action == "play") {
                    stoppedByUser = false
                }
                if (action == "back10" || action == "forward10" || action == "restart" || action == "seek" || (action == "play" && wasStopped)) {
                    endGuard.arm("control $action")
                }
                if (action == "state") {
                    val after = snapshot(controller)
                    // the song ended (paused in its last second): nothing plays for the screen
                    return@withContext Result.success(if (endGuard.songEnded.value) after.copy(playing = false) else after)
                }
                // the session reports the new position a moment later
                delay(350)
                Result.success(snapshot(controller).also {
                    DebugLog.log("control", action, mapOf("position" to it.position, "duration" to it.duration, "playing" to it.playing))
                })
            } catch (e: Exception) {
                DebugLog.log("control", "$action failed", mapOf("error" to e))
                Result.failure(Exception("Amazon Music does not react: ${e.message}"))
            }
        }
}
