package de.jobisoft.hitsterconnector

import android.media.session.PlaybackState
import de.jobisoft.hitsterconnector.util.EndDecision
import de.jobisoft.hitsterconnector.util.GuardModel
import de.jobisoft.hitsterconnector.util.MuteHold
import de.jobisoft.hitsterconnector.util.MutePhase
import de.jobisoft.hitsterconnector.util.RestartAction
import de.jobisoft.hitsterconnector.util.RestartDecision
import de.jobisoft.hitsterconnector.util.SwitchDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decisions of the end guard: the mute at the end, the switch detector, and what never happens. */
class EndGuardLogicTest {

    private val playing = PlaybackState.STATE_PLAYING
    private val buffering = PlaybackState.STATE_BUFFERING
    private val paused = PlaybackState.STATE_PAUSED
    private val window = 1.0f

    private val padamId = "B0PADAM"
    private val mottoId = "B09VD84Y43"

    // ── the mute at the end ────────────────────────────────────────────────────

    @Test
    fun theEndIsMutedOneSecondBeforeItWhileThePlayerPlays() {
        assertFalse(EndDecision.shouldMuteAtEnd(false, true, 180f, 100f, window))
        assertFalse(EndDecision.shouldMuteAtEnd(false, true, 180f, 178.9f, window)) // 1.1 s left
        assertTrue(EndDecision.shouldMuteAtEnd(false, true, 180f, 179.0f, window)) // 1.0 s left
        assertTrue(EndDecision.shouldMuteAtEnd(false, true, 180f, 179.8f, window))
    }

    @Test
    fun noMuteWhenPausedOrWhenTheLengthIsUnknownOrTooShort() {
        assertFalse(EndDecision.shouldMuteAtEnd(false, playing = false, durationS = 180f, positionS = 179.5f, windowS = window))
        assertFalse(EndDecision.shouldMuteAtEnd(false, true, 0f, 0f, window)) // no length yet
        assertFalse(EndDecision.shouldMuteAtEnd(false, true, 2f, 1.5f, window)) // too short to be a song, taken as unknown
    }

    @Test
    fun theMuteComesOnceAndNoPauseIsPartOfTheDecision() {
        val guard = GuardModel()
        guard.arm()
        assertFalse(guard.onTick(true, 180f, 120f, window))
        assertTrue("the mute at the end", guard.onTick(true, 180f, 179.2f, window))
        assertFalse("not a second time", guard.onTick(true, 180f, 179.5f, window))
        assertFalse(guard.onTick(true, 180f, 179.9f, window))
        assertTrue(guard.endMuted)
        assertTrue(guard.songEnded)
    }

    @Test
    fun theGuardDoesNotDisarmAfterTheEnd() {
        val guard = GuardModel()
        guard.arm()
        guard.onTick(true, 180f, 179.5f, window) // the end, muted
        assertTrue("still watching: this was the bug of build 1242", guard.watching)
        // the song is over, the ticker looks on and nothing changes
        repeat(100) { guard.onTick(false, 180f, 180f, window) }
        assertTrue(guard.watching)
        assertTrue(guard.songEnded)
        // only a new request disarms
        assertTrue(guard.disarm())
        assertFalse(guard.watching)
        assertFalse(guard.songEnded)
        assertFalse("nothing to disarm twice", guard.disarm())
    }

    @Test
    fun aMoveOfThePlayheadAfterTheEndStartsTheWatchAgainAndTellsThatTheEndMuteIsToBeUndone() {
        val guard = GuardModel()
        assertFalse(guard.arm()) // first arm: nothing was muted
        guard.onTick(true, 180f, 179.5f, window)
        assertTrue("re-arm after the end mute: unmute", guard.arm())
        assertFalse(guard.songEnded)
        // the new position is far from the end: no mute until the end comes again
        assertFalse(guard.onTick(true, 180f, 20f, window))
        assertTrue(guard.onTick(true, 180f, 179.4f, window))
    }

    @Test
    fun aGuardThatIsNotWatchingDoesNotMute() {
        assertFalse(GuardModel().onTick(true, 180f, 179.5f, window))
    }

    // ── the switch detector ─────────────────────────────────────────────────────

    @Test
    fun theDetectorPausesAnotherItemThatPlaysAlsoAfterTheEnd() {
        // "Padam Padam" ended, Amazon went on to "The Motto"
        assertTrue(SwitchDetector.shouldPause(true, padamId, "Padam Padam", mottoId, "The Motto", playing))
        assertTrue(SwitchDetector.shouldPause(true, padamId, "Padam Padam", mottoId, "The Motto", buffering))
        assertTrue(SwitchDetector.shouldPause(true, padamId, "Padam Padam", null, "The Motto", playing))
    }

    @Test
    fun theDetectorLeavesTheCardSongAndAnythingThatDoesNotPlayAlone() {
        // the same song with updated metadata (new id format, a suffix in the title): no pause
        assertFalse(SwitchDetector.shouldPause(true, padamId, "Padam Padam", padamId, "Padam Padam", playing))
        assertFalse(SwitchDetector.shouldPause(true, padamId, "Padam Padam", "OTHER", "Padam Padam (Remastered)", playing))
        // another item that does not play
        assertFalse(SwitchDetector.shouldPause(true, padamId, "Padam Padam", mottoId, "The Motto", paused))
        assertFalse(SwitchDetector.shouldPause(true, padamId, "Padam Padam", mottoId, "The Motto", null))
        // another id without a title yet: the player may be between two songs
        assertFalse(SwitchDetector.shouldPause(true, padamId, "Padam Padam", mottoId, null, playing))
        assertFalse(SwitchDetector.shouldPause(true, padamId, "Padam Padam", null, null, playing))
        // not watching: not our song any more
        assertFalse(SwitchDetector.shouldPause(false, padamId, "Padam Padam", mottoId, "The Motto", playing))
    }

    // ── what is muted for how long, and the restart ──────────────────────────

    @Test
    fun theLoadPhaseHoldsTwelveSecondsAndTheEndThirtyMinutes() {
        assertEquals(12_000L, MuteHold.holdMs(MutePhase.LOAD))
        assertEquals(30 * 60_000L, MuteHold.holdMs(MutePhase.END))
    }

    @Test
    fun playAgainAfterAQueueAdvanceReloadsTheCardSongAndOtherwiseSeeks() {
        assertEquals(RestartAction.REPLAY_STORED_SONG, RestartDecision.decide(true, hasSession = true, sameSong = false))
        assertEquals(RestartAction.SEEK_AND_PLAY, RestartDecision.decide(true, hasSession = true, sameSong = true))
        assertEquals(RestartAction.REPLAY_STORED_SONG, RestartDecision.decide(true, hasSession = false, sameSong = false))
    }
}
