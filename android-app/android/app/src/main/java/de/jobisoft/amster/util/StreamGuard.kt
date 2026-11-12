package de.jobisoft.amster.util

import android.content.Context
import android.media.AudioManager
import de.jobisoft.amster.data.debug.DebugLog

/** Makes sure the music stream is not muted when a request starts. */
object StreamGuard {
    /**
     * Called at the start of every request for a card. If the music stream is muted, it is unmuted once.
     * It cannot tell who muted the stream, so a mute by the user is undone as well. A volume turned down
     * to zero is not a mute and stays as it is.
     */
    fun ensureAudible(context: Context) {
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (!audio.isStreamMute(AudioManager.STREAM_MUSIC)) return
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            DebugLog.log("mute", "stream was muted, unmuted before the request")
        } catch (e: Throwable) {
            DebugLog.log("mute", "checking the music stream failed", mapOf("error" to e))
        }
    }
}
