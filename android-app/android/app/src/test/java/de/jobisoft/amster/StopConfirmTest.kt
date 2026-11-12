package de.jobisoft.amster

import android.media.session.PlaybackState
import de.jobisoft.amster.util.StopConfirm
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopConfirmTest {
    @Test
    fun playingAndBufferingNeedAStop() {
        assertTrue(StopConfirm.needsStop(PlaybackState.STATE_PLAYING))
        assertTrue(StopConfirm.needsStop(PlaybackState.STATE_BUFFERING))
        assertFalse(StopConfirm.needsStop(PlaybackState.STATE_PAUSED))
        assertFalse(StopConfirm.needsStop(null))
    }

    @Test
    fun pausedStoppedNoneAndNoSessionAreStopped() {
        for (state in listOf(null, PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE)) {
            assertTrue(StopConfirm.isStopped(state))
        }
        assertFalse(StopConfirm.isStopped(PlaybackState.STATE_PLAYING))
        assertFalse(StopConfirm.isStopped(PlaybackState.STATE_BUFFERING))
    }
}
