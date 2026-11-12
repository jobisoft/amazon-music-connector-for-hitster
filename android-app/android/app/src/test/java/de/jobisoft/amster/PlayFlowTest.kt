package de.jobisoft.amster

import android.media.session.PlaybackState
import de.jobisoft.amster.util.PlayFlow
import de.jobisoft.amster.util.PlayFlow.Item
import de.jobisoft.amster.util.PlayFlow.Next
import de.jobisoft.amster.util.PlayWait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayFlowTest {
    @Test
    fun theWaitsOfTheIdRouteAreFixed() {
        assertEquals(4_000L, PlayFlow.LOAD_MS)
        assertEquals(15_000L, PlayWait.BUFFER_WAIT_MS)
    }

    @Test
    fun theFailedSearchEndsTheRequest() {
        assertEquals(Next.PLAYING, PlayFlow.afterSearch(true))
        assertEquals(Next.FAILED, PlayFlow.afterSearch(false))
    }

    private val playFromOnly = 11_264L // PLAY_FROM_MEDIA_ID, PLAY_FROM_SEARCH, PLAY_FROM_URI

    @Test
    fun aSessionWithoutTitleAndWithoutPlayerIsBare() {
        assertTrue(PlayFlow.isBareSession(false, PlaybackState.STATE_NONE, playFromOnly))
        assertTrue(PlayFlow.isBareSession(false, PlaybackState.STATE_NONE, 0L))
        assertTrue(PlayFlow.isBareSession(false, PlaybackState.STATE_PAUSED, playFromOnly))
        // no title but a player behind it with state: the player controls are there
        assertFalse(PlayFlow.isBareSession(false, PlaybackState.STATE_PAUSED, playFromOnly or PlaybackState.ACTION_PLAY))
        assertFalse(PlayFlow.isBareSession(true, PlaybackState.STATE_NONE, playFromOnly))
        assertFalse(PlayFlow.isBareSession(true, PlaybackState.STATE_PLAYING, 2_899_963L))
    }

    @Test
    fun classifyComparesTheMediaId() {
        assertEquals(Item.OURS, PlayFlow.classify("B1", "B1"))
        assertEquals(Item.FOREIGN, PlayFlow.classify("B2", "B1"))
        assertEquals(Item.NONE, PlayFlow.classify(null, "B1"))
        assertEquals(Item.NONE, PlayFlow.classify("", "B1"))
    }
}
