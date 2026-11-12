package de.jobisoft.amster

import android.media.session.PlaybackState
import de.jobisoft.amster.util.ColdPreload
import de.jobisoft.amster.util.PlayFlow.Item
import de.jobisoft.amster.util.PlayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColdPreloadTest {
    @Test
    fun onlyAnEmptySessionNeedsThePreload() {
        assertTrue(ColdPreload.needed(null))
        assertTrue(ColdPreload.needed(PlaybackState.STATE_NONE))
        assertFalse(ColdPreload.needed(PlaybackState.STATE_PAUSED))
        assertFalse(ColdPreload.needed(PlaybackState.STATE_PLAYING))
    }

    @Test
    fun theVolumeTimeoutIsLongerThanTheWatch() {
        assertEquals(15_000L, ColdPreload.OBSERVE_MS)
        assertTrue(ColdPreload.DUCK_MAX_MS > ColdPreload.OBSERVE_MS)
    }

    @Test
    fun onlyAnotherSongThatPlaysIsTheBadBoy() {
        assertTrue(ColdPreload.isBadBoy(PlaybackState.STATE_PLAYING, Item.FOREIGN))
        // our own song that plays is ignored on purpose
        assertFalse(ColdPreload.isBadBoy(PlaybackState.STATE_PLAYING, Item.OURS))
        assertFalse(ColdPreload.isBadBoy(PlaybackState.STATE_BUFFERING, Item.FOREIGN))
        assertFalse(ColdPreload.isBadBoy(PlaybackState.STATE_PAUSED, Item.FOREIGN))
        assertFalse(ColdPreload.isBadBoy(PlaybackState.STATE_PLAYING, Item.NONE))
    }

    @Test
    fun theCountdownRunsFromFifteenToOne() {
        assertEquals(15, ColdPreload.secondsLeft(1_000, 1_000))
        assertEquals(15, ColdPreload.secondsLeft(1_000, 1_100))
        assertEquals(14, ColdPreload.secondsLeft(1_000, 2_100))
        assertEquals(1, ColdPreload.secondsLeft(1_000, 15_999))
        assertEquals(0, ColdPreload.secondsLeft(1_000, 16_000))
    }

    @Test
    fun theWatchIsOverAfterFifteenSeconds() {
        assertFalse(ColdPreload.over(1_000, 15_999))
        assertTrue(ColdPreload.over(1_000, 16_000))
    }

    @Test
    fun theStatusLineIsTheWakeWordWithTheSecondsInBrackets() {
        assertEquals("connecting to Amazon Music (15s)", PlayStatus.preload(15))
        assertEquals("connecting to Amazon Music (1s)", PlayStatus.preload(1))
    }
}
