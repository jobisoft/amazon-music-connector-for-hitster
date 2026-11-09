package de.jobisoft.hitsterconnector.data.repository

import android.app.ActivityManager
import android.app.SearchManager
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
import de.jobisoft.hitsterconnector.util.CrashReporter
import de.jobisoft.hitsterconnector.util.Safe
import de.jobisoft.hitsterconnector.util.LeaveScreen
import de.jobisoft.hitsterconnector.util.PlayAction
import de.jobisoft.hitsterconnector.util.PlayDecision
import de.jobisoft.hitsterconnector.util.RestartAction
import de.jobisoft.hitsterconnector.util.RestartDecision
import de.jobisoft.hitsterconnector.util.SongIdentity
import de.jobisoft.hitsterconnector.util.StartJump
import de.jobisoft.hitsterconnector.util.SearchQuery
import de.jobisoft.hitsterconnector.util.StopConfirm
import de.jobisoft.hitsterconnector.util.VersionCheck
import de.jobisoft.hitsterconnector.util.StreamMute
import de.jobisoft.hitsterconnector.data.media.AmazonMusicListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/** Where the song is, in seconds. */
data class PlayerPosition(val position: Float, val duration: Float, val playing: Boolean, val stopped: Boolean = false)

/**
 * Plays songs in the Amazon Music app of this phone and controls them.
 *
 *  - Start: the Android "play from search" intent (the one Google Assistant uses), sent to the
 *    package of Amazon Music with title and artist.
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

        /** Songs start here. */
        private const val START_AT = 20f
        private const val LAUNCH_WAIT_MS = 8_000L
        private const val MEDIA_BUTTON_WAIT_MS = 3_000L
        private const val BRING_BACK_MS = 8_000L
        private const val ASK_ATTEMPTS = 3
        private const val ASK_PAUSE_MS = 1_500L
        private const val TITLE_POLL_MS = 50L
        private const val RETRY_WAIT_MS = 3_000L
        private const val DURATION_WAIT_MS = 1_500L
        private const val SEEK_ATTEMPTS = 4
        private const val SEEK_WAIT_MS = 1_200L
        private const val PLAY_RETRY_MS = 1_500L
        private const val PLAY_WAIT_MS = 3_000L
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
        val sessionTitle: String?
    )

    @Volatile
    private var lastRequested: LastSong? = null

    /** The user pressed Stop: paused and rewound to the start offset. Cleared by play, restart, seek, +-10 s and a new card. */
    @Volatile
    private var stoppedByUser = false

    /** True from the end of the song until the next request, see [EndGuard.songEnded]. */
    val songEnded get() = endGuard.songEnded

    /** Is the player on the song we asked for? Without a request yet there is nothing to compare, then yes. */
    private fun onRequestedSong(controller: MediaController): Boolean {
        val last = lastRequested ?: return true
        val meta = controller.metadata
        return SongIdentity.same(
            last.mediaId, last.sessionTitle ?: last.title,
            meta?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        )
    }

    /** Answer to a play request. */
    /** [versionNote] is set if Amazon played another version than the card (remix, live ...), shown as a small line. */
    data class PlayAnswer(val ok: Boolean, val message: String?, val versionNote: String? = null)

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

    private fun stateName(state: Int?): String = when (state) {
        null -> "none"
        PlaybackState.STATE_NONE -> "NONE"
        PlaybackState.STATE_STOPPED -> "STOPPED"
        PlaybackState.STATE_PAUSED -> "PAUSED"
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
        PlaybackState.STATE_REWINDING -> "REWINDING"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        PlaybackState.STATE_ERROR -> "ERROR"
        PlaybackState.STATE_CONNECTING -> "CONNECTING"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_TO_PREVIOUS"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_TO_NEXT"
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING_TO_QUEUE_ITEM"
        else -> "UNKNOWN($state)"
    }

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

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ") // "(Remastered)", "[Live]"
            .replace(Regex("[^a-z0-9]+"), "")

    private fun sameSong(shown: String?, wanted: String): Boolean {
        val a = normalize(shown ?: return false)
        val b = normalize(wanted)
        return a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a))
    }

    /** A song shorter than the start offset plus 30 s starts at a quarter of its length (rounded down), any other song at the start offset. */
    private fun startFor(duration: Float, wanted: Float? = null): Float {
        val start = wanted ?: START_AT
        return if (duration > 0 && duration < start + 30) kotlin.math.floor(duration / 4f) else start
    }

    // ── play ──────────────────────────────────────────────────────────────────────

    /** True for the second attempt of a request: the classic query without additions. */
    @Volatile
    private var plainQuery = false

    @Volatile
    private var versionRetried = false

    /** The version note of the current request, null if the version looks right. */
    @Volatile
    private var versionNote: String? = null

    @Volatile
    private var queueLogged = false

    /** The (corrected) year of the card of the current request, for the first query. */
    @Volatile
    private var requestYear: Int? = null

    private fun queryFor(title: String, artist: String) =
        if (plainQuery) SearchQuery.fallback(title, artist) else SearchQuery.primary(title, artist, requestYear)

    private fun titleExtraFor(title: String) = SearchQuery.titleExtra(title, requestYear, plainQuery)

    private fun extrasFor(title: String, artist: String) = Bundle().apply {
        putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
        putString(MediaStore.EXTRA_MEDIA_TITLE, titleExtraFor(title))
        putString(MediaStore.EXTRA_MEDIA_ARTIST, artist)
        putString(SearchManager.QUERY, queryFor(title, artist))
    }

    /** The play-from-search intent, aimed at Amazon Music. */
    private fun searchIntent(title: String, artist: String) = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
        setPackage(AMAZON_MUSIC)
        putExtras(extrasFor(title, artist))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** The web address of the search, opened in the Amazon Music app. */
    private fun webIntent(title: String, artist: String): Intent {
        val tld = when (Locale.getDefault().country) {
            "DE", "AT", "CH" -> "de"
            "GB" -> "co.uk"
            "FR" -> "fr"
            "ES" -> "es"
            "IT" -> "it"
            "JP" -> "co.jp"
            else -> "com"
        }
        val query = Uri.encode("$title $artist")
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://music.amazon.$tld/search/$query")).apply {
            setPackage(AMAZON_MUSIC)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
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

    /** One way to get Amazon Music to play the song. [run] returns false if it could not even try. */
    private inner class Step(val name: String, val timeoutMs: Long, val run: suspend () -> Boolean)

    private fun stepB(title: String, artist: String) = Step("b-playFromSearch", 4_000) {
        val controller = amazonController()
        if (controller == null) {
            DebugLog.log("b-playFromSearch", "skipped, Amazon Music has no active session", mapOf("sessions" to describeSessions()))
            false
        } else {
            DebugLog.log("b-playFromSearch", "calling transportControls.playFromSearch", mapOf("session" to describe(controller)))
            try {
                val query = queryFor(title, artist)
                DebugLog.log("search", "query", mapOf("attempt" to (if (plainQuery) 2 else 1), "year" to requestYear, "wantedTitle" to title, "titleExtra" to titleExtraFor(title), "wantedArtist" to artist, "querySent" to query))
                controller.transportControls.playFromSearch(query, extrasFor(title, artist))
                true
            } catch (e: Throwable) {
                DebugLog.log("b-playFromSearch", "threw", mapOf("error" to e))
                false
            }
        }
    }

    private fun stepE(title: String, artist: String) = Step("e-webLink", 10_000) { startLogged("e-webLink", webIntent(title, artist)) }

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

    private fun controllerShows(title: String): MediaController? =
        amazonController()?.takeIf { sameSong(it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE), title) }

    /**
     * Waits for the requested title to show in the session (every 50 ms, the state does not matter:
     * the player starts to play at once, from the beginning). At that moment [jumpAndPlay] takes over.
     * [timeoutMs] is the time for the title to appear.
     */
    private suspend fun waitForStart(tag: String, title: String, artist: String, timeoutMs: Long): Boolean {
        var end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            var controller = controllerShows(title)
            if (controller != null) {
                titleLoaded = true
                var shown = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                var suspicious = VersionCheck.suspicious(title, shown)
                logSearchResult(title, artist, controller, suspicious)
                if (suspicious && !versionRetried) {
                    versionRetried = true
                    val firstId = controller.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                    controller = retryClassic(controller, title, artist, firstId) ?: controller
                    shown = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    suspicious = VersionCheck.suspicious(title, shown)
                    end = SystemClock.elapsedRealtime() + timeoutMs
                }
                        versionNote = if (suspicious) "Amazon played a different version: $shown" else null
                DebugLog.log("search", "final", mapOf("wantedTitle" to title, "finalShown" to shown, "retried" to versionRetried, "note" to versionNote))
                return jumpAndPlay(tag, controller, title)
            }
            delay(TITLE_POLL_MS)
        }
        return false
    }

    /**
     * The first result was another version: pause it (it started to play, the stream is muted), ask again with
     * the classic query and wait up to [RETRY_WAIT_MS] for a different item. Returns the controller showing it,
     * or null if nothing else came (then the first one is used).
     */
    private suspend fun retryClassic(first: MediaController, title: String, artist: String, firstId: String?): MediaController? {
        try { first.transportControls.pause() } catch (e: Throwable) { DebugLog.log("search", "pause threw", mapOf("error" to e)) }
        plainQuery = true
        val query = queryFor(title, artist)
        DebugLog.log(
            "search", "query",
            mapOf("attempt" to 2, "year" to requestYear, "wantedTitle" to title, "titleExtra" to titleExtraFor(title),
                "wantedArtist" to artist, "querySent" to query)
        )
        try {
            first.transportControls.playFromSearch(query, extrasFor(title, artist))
        } catch (e: Throwable) {
            DebugLog.log("search", "retry threw", mapOf("error" to e))
            return null
        }
        val end = SystemClock.elapsedRealtime() + RETRY_WAIT_MS
        while (SystemClock.elapsedRealtime() < end) {
            val c = controllerShows(title)
            val id = c?.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            if (c != null && id != null && id != firstId) {
                logSearchResult(title, artist, c, VersionCheck.suspicious(title, c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)))
                return c
            }
            delay(TITLE_POLL_MS)
        }
        DebugLog.log("search", "retry gave the same item", mapOf("firstId" to firstId))
        return null
    }

    /** The debug record of what Amazon loaded, and once per request its queue. Never throws. */
    private fun logSearchResult(title: String, artist: String, controller: MediaController, suspicious: Boolean) {
        try {
            val m = controller.metadata
            val shown = m?.getString(MediaMetadata.METADATA_KEY_TITLE)
            DebugLog.log(
                "search", "result",
                mapOf("wantedTitle" to title, "wantedArtist" to artist,
                    "shownTitle" to shown, "shownArtist" to m?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    "mediaId" to m?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                    "durationMs" to m?.getLong(MediaMetadata.METADATA_KEY_DURATION),
                    "attempt" to (if (plainQuery) 2 else 1), "suspicious" to suspicious, "markers" to VersionCheck.found(title, shown))
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
     * The new title shows: pause at once, seek to the start offset, wait until the position is really
     * there (the title must still be the new one), then play() and wait for PLAYING. The music stream
     * is muted meanwhile, so the part of the intro that gets out before the pause is not heard.
     * The `start` event has the numbers: title to pause, to the confirmed seek, to PLAYING, and the
     * furthest position seen before the jump.
     */
    private suspend fun jumpAndPlay(tag: String, first: MediaController, title: String): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        fun since() = SystemClock.elapsedRealtime() - t0
        val seen = mutableListOf(positionOf(first.playbackState))
        fun sample() { controllerShows(title)?.let { seen += positionOf(it.playbackState) } }

        // 1. pause at once
        try {
            first.transportControls.pause()
        } catch (e: Throwable) {
            DebugLog.log(tag, "pause threw", mapOf("error" to e))
        }
        val pauseSentMs = since()

        // 2. the length of the song may take a moment to show, the target depends on it
        var duration = durationOf(first)
        while (duration <= 0f && since() < DURATION_WAIT_MS) {
            delay(TITLE_POLL_MS)
            sample()
            duration = controllerShows(title)?.let { durationOf(it) } ?: 0f
        }
        val target = if (duration > 0f) startFor(duration) else 0f

        // 3. seek, and wait until the position is near the target, repeat if it does not take
        var seekConfirmedMs: Long? = null
        var seekCalls = 0
        var repausedBecausePlaying = 0
        if (target > 0f) {
            for (attempt in 1..SEEK_ATTEMPTS) {
                val c = controllerShows(title) ?: break
                seekCalls++
                try {
                    c.transportControls.seekTo((target * 1000).toLong())
                } catch (e: Throwable) {
                    DebugLog.log(tag, "seekTo threw", mapOf("attempt" to attempt, "error" to e))
                }
                val until = SystemClock.elapsedRealtime() + SEEK_WAIT_MS
                while (SystemClock.elapsedRealtime() < until) {
                    delay(TITLE_POLL_MS)
                    val now = controllerShows(title) ?: continue
                    sample()
                    val position = positionOf(now.playbackState)
                    val playing = now.playbackState?.state == PlaybackState.STATE_PLAYING
                    if (StartJump.near(position, target)) {
                        seekConfirmedMs = since()
                        break
                    }
                    if (playing && position < target - StartJump.NEAR_S) {
                        // the pause did not take, or the player started again at 0
                        repausedBecausePlaying++
                        try { now.transportControls.pause() } catch (_: Throwable) { }
                    }
                }
                if (seekConfirmedMs != null) break
            }
        }

        // 4. play, and wait for PLAYING (a freshly started Amazon player may need a second play())
        var playingMs: Long? = null
        for (call in 1..2) {
            val c = controllerShows(title) ?: break
            DebugLog.log(
                tag, "loaded but paused, calling play()",
                mapOf("call" to call, "positionS" to positionOf(c.playbackState), "targetS" to target,
                    "session" to describe(c))
            )
            try {
                c.transportControls.play()
            } catch (e: Throwable) {
                DebugLog.log(tag, "play() threw", mapOf("call" to call, "error" to e))
                break
            }
            val until = SystemClock.elapsedRealtime() + if (call == 1) PLAY_RETRY_MS else PLAY_WAIT_MS
            while (SystemClock.elapsedRealtime() < until) {
                delay(TITLE_POLL_MS)
                if (controllerShows(title)?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    playingMs = since()
                    break
                }
            }
            if (playingMs != null) break
            DebugLog.log(tag, "still not playing after play()", mapOf("call" to call))
        }

        DebugLog.log(
            "start", "load, pause, seek, play",
            mapOf(
                "step" to tag,
                "titleToPauseMs" to pauseSentMs,
                "titleToSeekConfirmedMs" to seekConfirmedMs,
                "titleToPlayingMs" to playingMs,
                "durationS" to duration,
                "targetS" to target,
                "seekCalls" to seekCalls,
                "pausedAgainBecausePlaying" to repausedBecausePlaying,
                "maxPositionSeenS" to seen.maxOrNull(),
                "leakedIntroS" to StartJump.leakedSeconds(seen, target)
            )
        )
        return playingMs != null
    }

    /** Every 500 ms the state of all media sessions, as long as the job runs. */
    private suspend fun dumpSessions(step: String) {
        if (!DebugLog.ENABLED) return
        var n = 0
        while (true) {
            try {
                DebugLog.log("sessions", "tick $step", mapOf("n" to n++, "sessions" to describeSessions()))
            } catch (e: Throwable) {
                DebugLog.log("sessions", "tick failed", mapOf("error" to e))
            }
            delay(500)
        }
    }

    /** Mutes the music stream for the load of the next song. The view models call it right after the stop is confirmed. */
    fun muteForNewSong() = StreamMute.mute(context)

    /** Undoes the mute at once, for a request that fails before or without play(). */
    fun unmuteNow(reason: String) = StreamMute.unmute(context, reason)

    /**
     * Plays the song: load it, pause, seek to the start offset, play (see [jumpAndPlay]). The stream
     * is muted from before the request until the song plays at its start offset, in a `finally` it is
     * always unmuted again. A failure unmutes at once.
     */
    suspend fun play(
        title: String,
        artist: String,
        year: Int?,
        card: String?
    ): Result<PlayAnswer> {
        StreamMute.mute(context) // already done by the view model after the stop, this covers the other callers
        return try {
            playInner(title, artist, year, card)
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
            StreamMute.unmute(context, "play finished")
        }
    }

    private suspend fun playInner(
        title: String,
        artist: String,
        year: Int?,
        card: String?
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

        /** Runs one step with the session log running beside it, true if the right song plays afterwards. */
        suspend fun attempt(step: Step): Boolean {
            val ok = coroutineScope {
                val dumper = launch { dumpSessions(step.name) }
                DebugLog.log(step.name, "step starts", mapOf("timeoutMs" to step.timeoutMs))
                val tried = try {
                    step.run()
                } catch (e: Throwable) {
                    DebugLog.log(step.name, "step threw", mapOf("error" to e))
                    false
                }
                val playing = try {
                    if (tried) waitForStart(step.name, title, artist, step.timeoutMs) else false
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    DebugLog.log(step.name, "waiting for the song threw", mapOf("error" to e))
                    CrashReporter.record(context, "play step ${step.name}", e)
                    false
                }
                dumper.cancel()
                DebugLog.log(
                    step.name, "step outcome",
                    mapOf("tried" to tried, "songPlays" to playing,
                        "elapsedMs" to SystemClock.elapsedRealtime() - started, "sessions" to describeSessions())
                )
                playing
            }
            if (ok && winner == null) winner = step.name
            return ok
        }

        uiStarted = false
        titleLoaded = false
        plainQuery = false
        versionRetried = false
        requestYear = year
        queueLogged = false
        versionNote = null
        val stepsRun = mutableListOf<String>()
        val sessionExisted = amazonController() != null
        DebugLog.log("play", "plan", mapOf("amazonSessionExisted" to sessionExisted))

        /** Waits for a session of Amazon Music with the session log running beside it. */
        suspend fun waitForSession(label: String, timeoutMs: Long): Boolean = coroutineScope {
            val dumper = launch { dumpSessions(label) }
            val end = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < end && amazonController() == null) delay(250)
            dumper.cancel()
            val appeared = amazonController() != null
            DebugLog.log(
                label, "wait for a session",
                mapOf("sessionAppeared" to appeared, "elapsedMs" to SystemClock.elapsedRealtime() - started,
                    "sessions" to describeSessions())
            )
            appeared
        }

        /** The session exists but may not be ready for playFromSearch yet: up to 3 tries, 1.5 s apart. */
        suspend fun askSession(): Boolean {
            for (n in 1..ASK_ATTEMPTS) {
                stepsRun += "b$n"
                if (attempt(stepB(title, artist))) return true
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
            var session = wakeByMediaButton(::waitForSession)
            if (!session) {
                stepsRun += "a"
                val tried = startLogged("a-intent", searchIntent(title, artist))
                session = tried && waitForSession("a-intent", LAUNCH_WAIT_MS)
            }
            if (session) {
                askSession()
            }
        }

        // the very last fallback, it opens Amazon Music's screen (again). Not while the right title is
        // loaded: then the player only has to be started, and a second screen would not help
        if (winner == null) {
            if (titleLoaded) {
                DebugLog.log("e-webLink", "skipped, the right title is loaded but does not play", mapOf("sessions" to describeSessions()))
            } else {
                stepsRun += "e"
                attempt(stepE(title, artist))
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

        // the jump was done before play(). Look once more: the player may put the playhead back while it starts up
        try {
            var wanted = startFor(durationOf(controller))
            if (wanted > 0) {
                for (attempt in 1..4) {
                    val at = positionOf(controller.playbackState)
                    DebugLog.log("play", "position at the start offset", mapOf("attempt" to attempt, "position" to at, "wanted" to wanted))
                    if (at >= wanted - 3) break
                    ctl(controller, "seekTo(again)", wanted) { it.transportControls.seekTo((wanted * 1000).toLong()) }
                    delay(700)
                    wanted = startFor(durationOf(controller))
                }
            }
        } catch (e: Exception) {
            DebugLog.log("play", "jumping ahead failed", mapOf("error" to e))
        }
        // the song plays at its start offset: sound back on
        StreamMute.unmute(context, "song plays at its start offset")
        // what "Play again" replays, and what the buttons check the player against
        val shown = controller.metadata
        lastRequested = LastSong(
            title, artist, year, card,
            shown?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), shown?.getString(MediaMetadata.METADATA_KEY_TITLE)
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
        Result.success(PlayAnswer(true, null, versionNote))
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
     * stopped like by the Stop button (pause, rewind to the start offset), the guard is disarmed, the
     * mute undone, in this order. It runs in a scope of the application: the navigation does not wait,
     * and it finishes also after the view model of the screen is gone. A screen that never played
     * something leaves the player alone, but still undoes a mute of ours.
     */
    fun leaveScreen(playedHere: Boolean) {
        leaveScope.launch {
            val controller = amazonController()
            val stop = LeaveScreen.shouldStop(playedHere, controller != null)
            DebugLog.log(
                "leave", "leave screen: stop + unmute",
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
            StreamMute.unmute(context, "screen left")
        }
    }

    /**
     * "Play again". If the player is still on the requested song: null, the caller seeks to the start offset
     * and plays. If its queue went on to another song (or the session is gone): the whole play path for the
     * stored song again (stop, mute, load, pause, seek, play, unmute). A failure or "nothing to play" is the answer.
     */
    private suspend fun restart(): Result<PlayerPosition>? {
        val last = lastRequested
        val controller = amazonController()
        val action = RestartDecision.decide(
            hasLastRequest = last != null,
            hasSession = controller != null,
            sameSong = controller != null && onRequestedSong(controller),
            songEnded = endGuard.songEnded.value
        )
        DebugLog.log("control", "restart decides: $action", mapOf("session" to controller?.let { describe(it) }))
        return when (action) {
            RestartAction.SEEK_AND_PLAY -> null
            RestartAction.NOTHING_TO_PLAY -> Result.failure(Exception("Nothing to play again."))
            RestartAction.REPLAY_STORED_SONG -> replayStoredSong(last)
        }
    }

    /** The whole start sequence for the song of the card: stop-confirm, mute, load, pause, seek, play, unmute. */
    private suspend fun replayStoredSong(song: LastSong?): Result<PlayerPosition> {
        if (song == null) return Result.failure(Exception("Nothing to play again."))
        stopAndConfirm()
        muteForNewSong()
        val result = play(song.title, song.artist, song.year, song.card)
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
            songEnded = endGuard.songEnded.value,
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
     * offset, plays), "stop" (pauses), "seek" (to a position in seconds) or "state" (changes
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
                        // by hand: the position stays, the guard stays armed, nothing is unmuted
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
                        // pause AND back to the start offset, the guard is disarmed, an end mute of ours is undone
                        endGuard.disarm("user stop")
                        val (from, rewoundTo) = pauseAndRewind(controller)
                        StreamMute.unmute(context, "user stop")
                        stoppedByUser = true
                        DebugLog.log(
                            "control", "control stop (paused, rewound)",
                            mapOf("fromS" to from, "toS" to rewoundTo, "durationS" to now.duration)
                        )
                    }
                    "seek" -> seek(position ?: return@withContext Result.failure(Exception("position is required.")))
                    "state" -> Unit // only reads, the mute at the end is done by the guard
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
                    // the song ended (its sound is off since its last second): nothing plays for the screen
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

    // ── diagnostics ───────────────────────────────────────────────────────────────

    /**
     * One run of everything the debug log can show, for a fixed test song: the Amazon packages,
     * the components of Amazon Music that handle media actions, and the play probe.
     */
    suspend fun diagnostics() = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        DebugLog.log("diag", "start", mapOf("notificationAccess" to hasNotificationAccess()))
        try {
            val amazon = pm.getInstalledPackages(0).filter { it.packageName.contains("amazon", ignoreCase = true) }
                .map { mapOf("package" to it.packageName, "versionName" to it.versionName) }
            DebugLog.log("diag", "installed packages with amazon in the name", mapOf("packages" to amazon))
        } catch (e: Exception) {
            DebugLog.log("diag", "package list failed", mapOf("error" to e))
        }
        try {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(
                AMAZON_MUSIC,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS
            )
            fun chunks(kind: String, items: List<String>) =
                items.chunked(40).forEachIndexed { i, part ->
                    DebugLog.log("diag", "exported $kind (${i + 1})", mapOf("items" to part))
                }
            chunks("activities", info.activities.orEmpty().filter { it.exported }.map { "${it.name} enabled=${it.enabled}" })
            chunks("services", info.services.orEmpty().filter { it.exported }.map { "${it.name} enabled=${it.enabled} permission=${it.permission}" })
            chunks("receivers", info.receivers.orEmpty().filter { it.exported }.map { "${it.name} enabled=${it.enabled}" })
        } catch (e: Exception) {
            DebugLog.log("diag", "components of $AMAZON_MUSIC failed", mapOf("error" to e))
        }
        val actions = listOf(
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, "android.media.action.MEDIA_SEARCH",
            Intent.ACTION_SEARCH, Intent.ACTION_MEDIA_BUTTON,
            Intent.ACTION_VIEW, Intent.ACTION_MAIN, "com.google.android.gms.actions.SEARCH_ACTION"
        )
        for (action in actions) {
            val intent = Intent(action).setPackage(AMAZON_MUSIC)
            if (action == Intent.ACTION_VIEW) intent.data = Uri.parse("https://music.amazon.com/search/test")
            if (action == Intent.ACTION_MAIN) intent.addCategory(Intent.CATEGORY_LAUNCHER)
            try {
                DebugLog.log(
                    "diag", "who handles $action in $AMAZON_MUSIC",
                    mapOf(
                        "activities" to pm.queryIntentActivities(intent, 0).map { "${it.activityInfo.name} exported=${it.activityInfo.exported}" },
                        "services" to pm.queryIntentServices(intent, 0).map { "${it.serviceInfo.name} exported=${it.serviceInfo.exported}" },
                        "receivers" to pm.queryBroadcastReceivers(intent, 0).map { "${it.activityInfo.name} exported=${it.activityInfo.exported}" }
                    )
                )
            } catch (e: Exception) {
                DebugLog.log("diag", "query for $action failed", mapOf("error" to e))
            }
        }
        DebugLog.log("diag", "sessions now", mapOf("sessions" to describeSessions()))
        val result = play("Blue Monday", "New Order", null, null)
        DebugLog.log("diag", "play probe finished", mapOf("ok" to result.isSuccess, "error" to result.exceptionOrNull()?.message))
        if (result.isSuccess) {
            delay(3000)
            control("stop")
        }
        DebugLog.log("diag", "done")
        DebugLog.flush()
    }
}
