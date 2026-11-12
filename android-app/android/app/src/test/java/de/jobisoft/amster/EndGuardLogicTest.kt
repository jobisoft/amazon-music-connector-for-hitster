package de.jobisoft.amster

import android.media.session.PlaybackState
import de.jobisoft.amster.util.RestartAction
import de.jobisoft.amster.util.RestartDecision
import de.jobisoft.amster.util.SwitchDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The switch detector of the end guard and the restart decision. The end rules are in [GuardReactorTest]. */
class EndGuardLogicTest {

    private val playing = PlaybackState.STATE_PLAYING
    private val buffering = PlaybackState.STATE_BUFFERING
    private val paused = PlaybackState.STATE_PAUSED

    private val padamId = "B0PADAM"
    private val mottoId = "B09VD84Y43"

    // ── the switch detector ─────────────────────────────────────────────────────

    @Test
    fun theDetectorPausesAnotherItemThatPlaysAlsoAfterTheEnd() {
        // "Padam Padam" ended, Amazon went on to "The Motto"
        assertTrue(SwitchDetector.shouldPause(padamId, "Padam Padam", mottoId, "The Motto", playing))
        assertTrue(SwitchDetector.shouldPause(padamId, "Padam Padam", mottoId, "The Motto", buffering))
        assertTrue(SwitchDetector.shouldPause(padamId, "Padam Padam", null, "The Motto", playing))
    }

    @Test
    fun theDetectorLeavesTheCardSongAndAnythingThatDoesNotPlayAlone() {
        // the same song with updated metadata (new id format, a suffix in the title): no pause
        assertFalse(SwitchDetector.shouldPause(padamId, "Padam Padam", padamId, "Padam Padam", playing))
        assertFalse(SwitchDetector.shouldPause(padamId, "Padam Padam", "OTHER", "Padam Padam (Remastered)", playing))
        // another item that does not play
        assertFalse(SwitchDetector.shouldPause(padamId, "Padam Padam", mottoId, "The Motto", paused))
        assertFalse(SwitchDetector.shouldPause(padamId, "Padam Padam", mottoId, "The Motto", null))
        // another id without a title yet: the player may be between two songs
        assertFalse(SwitchDetector.shouldPause(padamId, "Padam Padam", mottoId, null, playing))
        assertFalse(SwitchDetector.shouldPause(padamId, "Padam Padam", null, null, playing))
    }

    // ── the restart ───────────────────────────────────────────────────────────────

    @Test
    fun playAgainAfterAQueueAdvanceReloadsTheCardSongAndOtherwiseSeeks() {
        assertEquals(RestartAction.REPLAY_STORED_SONG, RestartDecision.decide(true, hasSession = true, sameSong = false))
        assertEquals(RestartAction.SEEK_AND_PLAY, RestartDecision.decide(true, hasSession = true, sameSong = true))
        assertEquals(RestartAction.REPLAY_STORED_SONG, RestartDecision.decide(true, hasSession = false, sameSong = false))
    }
}
