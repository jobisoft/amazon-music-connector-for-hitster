package de.jobisoft.hitsterconnector.util

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import de.jobisoft.hitsterconnector.data.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mutes the music stream while a new song loads and jumps to its start offset, so that nobody hears
 * the first seconds. It uses ADJUST_MUTE / ADJUST_UNMUTE, which keep the volume level of the user,
 * and it unmutes only if it was this app that muted.
 *
 * Safety: a hard timeout unmutes by itself, [unmute] is called in a `finally` of the play path, and
 * the next start of the app unmutes if the app was killed while muted (the flag is on disk).
 */
object StreamMute {
    private const val PREFS = "stream_mute"
    private const val KEY_WE_MUTED = "we_muted"

    /** The hold of the load phase: a cold start of Amazon Music takes long, but nothing may stay muted for ever. */
    const val HARD_TIMEOUT_MS = MuteHold.LOAD_MS

    /** The hold of the end of a song, until the next card has jumped to its start offset. */
    const val END_HOLD_MS = MuteHold.END_MS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + Safe.handler)
    private var timeout: Job? = null

    @Volatile
    private var weMuted = false
    private var since = 0L

    private fun audio(context: Context) = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun remember(context: Context, muted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WE_MUTED, muted).commit()
    }

    /** True while the music stream is muted by this app. */
    fun isMutedByUs(): Boolean = weMuted

    /**
     * Mutes the music stream, unless it is muted already by the user (then it stays theirs). If this
     * app muted it already, only the timer starts again with [holdMs]: the load phase of the next card
     * gets its 12 s again, in place of the long hold of the end of the song. No unmute in between.
     */
    @Synchronized
    fun mute(context: Context, holdMs: Long = MuteHold.LOAD_MS) {
        if (weMuted) {
            restartTimer(context, holdMs)
            DebugLog.log("mute", "already muted by us, hold restarted", mapOf("holdMs" to holdMs))
            return
        }
        try {
            val audio = audio(context)
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
                DebugLog.log("mute", "not muted by us, the music stream was muted already")
                return
            }
            remember(context, true) // before the mute, so a kill in between leaves the flag
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            weMuted = true
            since = SystemClock.elapsedRealtime()
            DebugLog.log("mute", "music stream muted", mapOf("holdMs" to holdMs))
            restartTimer(context, holdMs)
        } catch (e: Throwable) {
            remember(context, false)
            DebugLog.log("mute", "muting failed", mapOf("error" to e))
        }
    }

    private fun restartTimer(context: Context, holdMs: Long) {
        timeout?.cancel()
        val app = context.applicationContext
        timeout = scope.launch {
            delay(holdMs)
            unmute(app, "hard timeout after ${holdMs / 1000} s")
        }
    }

    /** Undoes our mute, at once. Does nothing if we did not mute. */
    @Synchronized
    fun unmute(context: Context, reason: String) {
        timeout?.cancel()
        timeout = null
        if (!weMuted) return
        try {
            audio(context).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        } catch (e: Throwable) {
            DebugLog.log("unmute", "unmuting failed", mapOf("error" to e))
        }
        weMuted = false
        remember(context, false)
        DebugLog.log("unmute", "music stream unmuted", mapOf("reason" to reason, "mutedForMs" to SystemClock.elapsedRealtime() - since))
    }

    /** At the start of the app: an earlier run may have been killed while it had muted the stream. */
    @Synchronized
    fun unmuteLeftover(context: Context) {
        if (weMuted) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_WE_MUTED, false)) return
        try {
            val audio = audio(context)
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                DebugLog.log("unmute", "music stream unmuted at app start, an earlier run left it muted")
            }
        } catch (e: Throwable) {
            DebugLog.log("unmute", "unmuting at app start failed", mapOf("error" to e))
        }
        prefs.edit().putBoolean(KEY_WE_MUTED, false).commit()
    }
}
