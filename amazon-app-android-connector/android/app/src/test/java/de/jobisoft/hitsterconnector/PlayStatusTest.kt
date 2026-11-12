package de.jobisoft.hitsterconnector

import android.media.session.PlaybackState
import de.jobisoft.hitsterconnector.util.PlayFlow
import de.jobisoft.hitsterconnector.util.PlayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayStatusTest {
    @Test
    fun stateTexts() {
        assertEquals("buffering", PlayStatus.forState(PlaybackState.STATE_BUFFERING))
        assertEquals("connecting", PlayStatus.forState(PlaybackState.STATE_CONNECTING))
        assertEquals("preparing", PlayStatus.forState(PlaybackState.STATE_PAUSED))
        assertEquals("preparing", PlayStatus.forState(PlaybackState.STATE_STOPPED))
        assertEquals("waiting", PlayStatus.forState(null))
        assertEquals("waiting", PlayStatus.forState(PlaybackState.STATE_NONE))
        assertEquals("loading", PlayStatus.forState(PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM))
        assertEquals("loading", PlayStatus.forState(PlaybackState.STATE_SKIPPING_TO_NEXT))
        assertEquals("loading", PlayStatus.forState(PlaybackState.STATE_SKIPPING_TO_PREVIOUS))
        assertEquals("positioning", PlayStatus.forState(PlaybackState.STATE_FAST_FORWARDING))
        assertEquals("positioning", PlayStatus.forState(PlaybackState.STATE_REWINDING))
        assertEquals("error", PlayStatus.forState(PlaybackState.STATE_ERROR))
        assertEquals("playing", PlayStatus.forState(PlaybackState.STATE_PLAYING))
        assertEquals("unknown", PlayStatus.forState(99))
    }

    @Test
    fun phaseTexts() {
        assertEquals("connecting to Amazon Music", PlayStatus.forPhase(PlayFlow.Phase.WAKE))
        assertEquals("loading", PlayStatus.forPhase(PlayFlow.Phase.LOAD))
        assertEquals("loading", PlayStatus.forPhase(PlayFlow.Phase.START))
        assertEquals("searching", PlayStatus.forPhase(PlayFlow.Phase.SEARCH))
    }

    @Test
    fun everyTextIsOneLine() {
        val all = PlayFlow.Phase.values().map { PlayStatus.forPhase(it) } +
            listOf(PlaybackState.STATE_BUFFERING, PlaybackState.STATE_ERROR, 42).map { PlayStatus.forState(it) }
        all.forEach { assertFalse(it.contains('\n')) }
    }
}
