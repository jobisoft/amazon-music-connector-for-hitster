package de.jobisoft.hitsterconnector

import android.media.session.PlaybackState
import de.jobisoft.hitsterconnector.util.PlayWait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayWaitTest {
    @Test
    fun theLimitIsFifteenSeconds() {
        assertEquals(15_000L, PlayWait.BUFFER_WAIT_MS)
    }

    @Test
    fun loadingStates() {
        assertTrue(PlayWait.isLoading(PlaybackState.STATE_BUFFERING))
        assertTrue(PlayWait.isLoading(PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM))
        assertFalse(PlayWait.isLoading(PlaybackState.STATE_PAUSED))
        assertFalse(PlayWait.isLoading(PlaybackState.STATE_PLAYING))
    }
}
