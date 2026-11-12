package de.jobisoft.amster.util

import android.media.session.PlaybackState

/**
 * One look at the media session of Amazon Music, taken when the session reported a change. Immutable, so
 * the reactions to it can be decided by a pure function ([PlayReactor.onSnapshot]).
 *
 * [updatedAtMs] is the time of the position report (`SystemClock.elapsedRealtime` clock).
 */
data class SessionSnapshot(
    val state: Int,
    val positionMs: Long,
    val updatedAtMs: Long,
    val speed: Float,
    val actions: Long,
    val mediaId: String?,
    val title: String?,
    val artist: String?,
    val durationMs: Long
) {
    val hasTitle: Boolean get() = !title.isNullOrEmpty()

    val durationS: Float get() = if (durationMs > 0) durationMs / 1000f else 0f

    val canSeek: Boolean get() = actions and PlaybackState.ACTION_SEEK_TO != 0L

    /** Position in seconds. While it plays, the last report is carried on with the clock. */
    fun positionS(nowMs: Long): Float {
        var position = positionMs.toFloat()
        if (state == PlaybackState.STATE_PLAYING && updatedAtMs > 0) {
            position += (nowMs - updatedAtMs) * speed
        }
        return (position / 1000f).coerceAtLeast(0f)
    }
}
