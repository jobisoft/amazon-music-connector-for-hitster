package de.jobisoft.hitsterconnector.util

import android.content.Context
import android.media.AudioManager
import de.jobisoft.hitsterconnector.data.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sets the volume level of the music stream to 0 when a card is loaded and puts it back when the song plays at
 * its start offset. Nobody hears the first moments of the song then. The
 * mute flag of the stream is not used.
 *
 * The duck needs a start position: with the start position Off the song is not moved, and nothing is ducked.
 * The switch on the Options screen ([isEnabled], [setEnabled], default on) turns the duck off: the level is not
 * changed, nothing on disk, no timer. It exists because the level is sent to Bluetooth headphones with
 * absolute volume, and some headsets may react badly to it. The setting is read at the start of every
 * request. A duck that is still pending from before (the app was killed) is restored with the switch off
 * too.
 *
 * Safety: the level is written to disk (commit) before it is changed. [restore] runs on every way out of a
 * request, once more by the timeout of [LOAD_MAX_MS] ("something went totally wrong"), and at the start of the app if it was killed while
 * the level was 0. A level that the user changed meanwhile is left alone.
 */
object VolumeDuck {
    private const val PREFS = "volume_duck"
    private const val KEY_SAVED = "saved_level"
    private const val KEY_ENABLED = "enabled"

    /** The longest time the level stays at 0 after a load, the timer restores by itself and does nothing else. */
    const val LOAD_MAX_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + Safe.handler)
    private var timeout: Job? = null
    private val state = DuckState()

    private fun audio(context: Context) = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The setting of the Options screen, on by default. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Called at the start of every request for a card, after [StreamGuard.ensureAudible], and again when the load begins.
     * [maxMs] is the timeout that puts the level back by itself, a duck that is still on restarts it.
     */
    @Synchronized
    fun duck(context: Context, maxMs: Long = LOAD_MAX_MS) {
        try {
            val audio = audio(context)
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val noStart = StartSetting.get(context) == StartPosition.OFF
            when (DuckPolicy.onDuck(isEnabled(context), current, state.pending, noStart)) {
                DuckPolicy.OnDuck.OFF -> DebugLog.log("duck", "duck disabled by setting")
                DuckPolicy.OnDuck.NO_START -> DebugLog.log("duck", "no duck, the start position is Off")
                DuckPolicy.OnDuck.SKIP -> DebugLog.log("duck", "level is 0 already, nothing to duck")
                DuckPolicy.OnDuck.KEEP -> {
                    DebugLog.log("duck", "still ducked, the remembered level stays", mapOf("saved" to state.pending))
                    startTimer(context, maxMs)
                }
                DuckPolicy.OnDuck.DUCK -> {
                    state.remember(current)
                    prefs(context).edit().putInt(KEY_SAVED, current).commit() // before the change, so a kill in between leaves it
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    DebugLog.log("duck", "ducked", mapOf("level" to current, "max" to audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)))
                    startTimer(context, maxMs)
                }
            }
        } catch (e: Throwable) {
            DebugLog.log("duck", "ducking failed", mapOf("error" to e))
            forget(context)
        }
    }

    /**
     * Puts the level back, if this app ducked it and it is still 0. Safe to call at any time and any number
     * of times, the second call finds nothing to do.
     */
    @Synchronized
    fun restore(context: Context, reason: String) {
        timeout?.cancel()
        timeout = null
        val saved = state.take() ?: prefs(context).takeIf { it.contains(KEY_SAVED) }?.getInt(KEY_SAVED, 0)
        if (saved == null) return
        try {
            val audio = audio(context)
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (DuckPolicy.shouldRestore(current, saved)) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
                DebugLog.log("duck", "restored", mapOf("level" to saved, "reason" to reason))
            } else {
                DebugLog.log("duck", "level kept, it was changed meanwhile", mapOf("saved" to saved, "now" to current, "reason" to reason))
            }
        } catch (e: Throwable) {
            DebugLog.log("duck", "restoring failed", mapOf("error" to e, "reason" to reason))
        } finally {
            forget(context)
        }
    }

    /** At the start of the app: the app was killed while the level was 0. */
    fun restoreAtStart(context: Context) {
        restore(context, "app start")
    }

    private fun forget(context: Context) {
        state.take()
        prefs(context).edit().remove(KEY_SAVED).commit()
    }

    private fun startTimer(context: Context, maxMs: Long) {
        timeout?.cancel()
        val app = context.applicationContext
        timeout = scope.launch {
            delay(maxMs)
            restore(app, "hard timeout")
        }
    }
}
