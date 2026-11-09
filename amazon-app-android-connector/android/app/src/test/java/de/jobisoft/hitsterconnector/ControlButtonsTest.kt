package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.util.ControlAction
import de.jobisoft.hitsterconnector.util.ControlButtons
import de.jobisoft.hitsterconnector.util.PlayAction
import de.jobisoft.hitsterconnector.util.PlayDecision
import de.jobisoft.hitsterconnector.util.RestartAction
import de.jobisoft.hitsterconnector.util.RestartDecision
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlButtonsTest {

    // ── what the two toggles show ────────────────────────────────────────────

    @Test
    fun whilePlayingTheTogglesOfferPauseAndStop() {
        val b = ControlButtons.from(playing = true, stopped = false, songEnded = false)
        assertEquals(ControlAction.PAUSE, b.pausePlay)
        assertEquals(ControlAction.STOP, b.stopRestart)
    }

    @Test
    fun afterAPauseByHandTheTogglesOfferPlayAndStop() {
        val b = ControlButtons.from(playing = false, stopped = false, songEnded = false)
        assertEquals(ControlAction.PLAY, b.pausePlay)
        assertEquals(ControlAction.STOP, b.stopRestart)
    }

    @Test
    fun afterAStopTheTogglesOfferPlayAndRestart() {
        val b = ControlButtons.from(playing = false, stopped = true, songEnded = false)
        assertEquals(ControlAction.PLAY, b.pausePlay)
        assertEquals(ControlAction.RESTART, b.stopRestart)
    }

    @Test
    fun afterTheEndOfTheSongTheTogglesOfferPlayAndRestartEvenIfThePlayerStillReportsPlaying() {
        // the last second plays in silence, the end is muted
        val b = ControlButtons.from(playing = true, stopped = false, songEnded = true)
        assertEquals(ControlAction.PLAY, b.pausePlay)
        assertEquals(ControlAction.RESTART, b.stopRestart)
    }

    @Test
    fun theWireNamesAreTheActionsOfTheRepository() {
        assertEquals(listOf("pause", "play", "stop", "restart"), ControlAction.entries.map { it.wire })
    }

    // ── what a tap on Play does ──────────────────────────────────────────────

    @Test
    fun playAfterAPauseResumesAtTheSamePosition() {
        assertEquals(PlayAction.RESUME, PlayDecision.forPlay(true, true, sameSong = true, songEnded = false, stopped = false))
    }

    @Test
    fun playAfterAStopSeeksToTheStartOffsetAndPlays() {
        assertEquals(PlayAction.SEEK_AND_PLAY, PlayDecision.forPlay(true, true, sameSong = true, songEnded = false, stopped = true))
    }

    @Test
    fun playAfterTheEndRunsTheWholeSequence() {
        assertEquals(PlayAction.FULL_SEQUENCE, PlayDecision.forPlay(true, true, sameSong = true, songEnded = true, stopped = false))
        assertEquals(PlayAction.FULL_SEQUENCE, PlayDecision.forPlay(true, true, sameSong = true, songEnded = true, stopped = true))
    }

    @Test
    fun playNeverResumesAWrongItem() {
        // the queue of Amazon went on to another song
        assertEquals(PlayAction.FULL_SEQUENCE, PlayDecision.forPlay(true, true, sameSong = false, songEnded = false, stopped = false))
        assertEquals(PlayAction.FULL_SEQUENCE, PlayDecision.forPlay(true, true, sameSong = false, songEnded = false, stopped = true))
        // no session at all
        assertEquals(PlayAction.FULL_SEQUENCE, PlayDecision.forPlay(true, false, sameSong = false, songEnded = false, stopped = false))
    }

    @Test
    fun playWithoutAnyRequestHasNothingToPlay() {
        assertEquals(PlayAction.NOTHING_TO_PLAY, PlayDecision.forPlay(false, true, sameSong = true, songEnded = false, stopped = false))
    }

    @Test
    fun restartAfterTheEndRunsTheWholeSequenceAndOtherwiseSeeks() {
        assertEquals(RestartAction.REPLAY_STORED_SONG, RestartDecision.decide(true, true, sameSong = true, songEnded = true))
        assertEquals(RestartAction.SEEK_AND_PLAY, RestartDecision.decide(true, true, sameSong = true, songEnded = false))
        assertEquals(RestartAction.REPLAY_STORED_SONG, RestartDecision.decide(true, true, sameSong = false, songEnded = false))
    }
}
