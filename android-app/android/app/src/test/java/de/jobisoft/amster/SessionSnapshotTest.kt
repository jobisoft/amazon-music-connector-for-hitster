package de.jobisoft.amster

import android.media.session.PlaybackState
import de.jobisoft.amster.util.SessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSnapshotTest {
    private fun snap(state: Int, positionMs: Long = 10_000, updatedAt: Long = 1_000, actions: Long = 0L, duration: Long = 0L) =
        SessionSnapshot(state, positionMs, updatedAt, 1f, actions, "ID", "Title", "Artist", duration)

    @Test
    fun aPlayingPositionIsCarriedOnWithTheClock() {
        assertEquals(12f, snap(PlaybackState.STATE_PLAYING).positionS(3_000), 0.001f)
    }

    @Test
    fun aPausedPositionStandsStill() {
        assertEquals(10f, snap(PlaybackState.STATE_PAUSED).positionS(9_000), 0.001f)
    }

    @Test
    fun theDurationIsInSecondsAndZeroWhenUnknown() {
        assertEquals(200f, snap(PlaybackState.STATE_PAUSED, duration = 200_000).durationS, 0.001f)
        assertEquals(0f, snap(PlaybackState.STATE_PAUSED, duration = -1).durationS, 0.001f)
    }

    @Test
    fun theSeekActionIsRead() {
        assertTrue(snap(PlaybackState.STATE_PAUSED, actions = PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_PLAY).canSeek)
        assertFalse(snap(PlaybackState.STATE_PAUSED, actions = PlaybackState.ACTION_PLAY).canSeek)
    }
}
