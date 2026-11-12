package de.jobisoft.hitsterconnector

import android.media.session.PlaybackState
import de.jobisoft.hitsterconnector.util.PlayWait
import de.jobisoft.hitsterconnector.util.PlayWait.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayWaitTest {
    private val never = Long.MAX_VALUE

    @Test
    fun playingIsDone() {
        assertEquals(Action.DONE, PlayWait.decide(PlaybackState.STATE_PLAYING, 500, never))
        assertEquals(Action.DONE, PlayWait.decide(PlaybackState.STATE_PLAYING, 20_000, 0))
    }

    @Test
    fun pausedGetsPlayAtOnceThenAfterTheDelay() {
        assertEquals(Action.REPLAY, PlayWait.decide(PlaybackState.STATE_PAUSED, 0, never))
        assertEquals(Action.WAIT, PlayWait.decide(PlaybackState.STATE_PAUSED, 800, 800))
        assertEquals(Action.REPLAY, PlayWait.decide(PlaybackState.STATE_PAUSED, 1_600, 1_600))
        assertEquals(Action.REPLAY, PlayWait.decide(PlaybackState.STATE_STOPPED, 3_000, 2_000))
    }

    @Test
    fun bufferingWaitsAndNeverSendsPlayAgain() {
        for (state in listOf(
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM, PlaybackState.STATE_SKIPPING_TO_NEXT
        )) {
            assertEquals(Action.WAIT, PlayWait.decide(state, 5_000, never))
            assertEquals(Action.WAIT, PlayWait.decide(state, 14_999, never))
        }
    }

    @Test
    fun givesUpAfterTheLimitOrOnError() {
        assertEquals(Action.GIVE_UP, PlayWait.decide(PlaybackState.STATE_BUFFERING, PlayWait.BUFFER_WAIT_MS, never))
        assertEquals(Action.GIVE_UP, PlayWait.decide(PlaybackState.STATE_PAUSED, 16_000, never))
        assertEquals(Action.GIVE_UP, PlayWait.decide(PlaybackState.STATE_ERROR, 100, never))
    }

    @Test
    fun loadingStates() {
        assertTrue(PlayWait.isLoading(PlaybackState.STATE_BUFFERING))
        assertTrue(PlayWait.isLoading(PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM))
        assertFalse(PlayWait.isLoading(PlaybackState.STATE_PAUSED))
        assertFalse(PlayWait.isLoading(PlaybackState.STATE_PLAYING))
    }

    @Test
    fun theLimitCanBeShorter() {
        assertEquals(Action.GIVE_UP, PlayWait.decide(PlaybackState.STATE_BUFFERING, 4_500, never, 4_500))
        assertEquals(Action.WAIT, PlayWait.decide(PlaybackState.STATE_BUFFERING, 4_499, never, 4_500))
    }
}
