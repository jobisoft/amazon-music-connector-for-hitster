package de.jobisoft.amster

import de.jobisoft.amster.util.RestartAction
import de.jobisoft.amster.util.RestartDecision
import de.jobisoft.amster.util.SongIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongIdentityTest {

    // the song ended and Amazon went on to the next item of its queue
    private val padamId = "B0PADAM"
    private val motto = "The Motto" to "B09VD84Y43"

    @Test
    fun sameMediaIdOrAboutTheSameTitleIsTheSameSong() {
        assertTrue(SongIdentity.same(padamId, "Padam Padam", padamId, "Padam Padam"))
        assertTrue(SongIdentity.same(padamId, "Padam Padam", "OTHERID", "Padam Padam (Remastered)")) // the id may change, the title says the same
        assertTrue(SongIdentity.same(padamId, "Blue Monday", padamId, "something else")) // same id wins
        assertFalse(SongIdentity.same(padamId, "Padam Padam", motto.second, motto.first))
    }

    @Test
    fun noTitleYetMeansThePlayerIsBetweenTwoSongsNotOnAnotherOne() {
        assertTrue(SongIdentity.same(padamId, "Padam Padam", null, null))
    }

    @Test
    fun restartSeeksAndPlaysIfThePlayerIsStillOnTheRequestedSong() {
        assertEquals(RestartAction.SEEK_AND_PLAY, RestartDecision.decide(hasLastRequest = true, hasSession = true, sameSong = true))
    }

    @Test
    fun restartReplaysTheStoredSongIfTheQueueWentOnOrTheSessionIsGone() {
        assertEquals(RestartAction.REPLAY_STORED_SONG, RestartDecision.decide(true, hasSession = true, sameSong = false)) // "The Motto"
        assertEquals(RestartAction.REPLAY_STORED_SONG, RestartDecision.decide(true, hasSession = false, sameSong = false))
        assertEquals(RestartAction.REPLAY_STORED_SONG, RestartDecision.decide(true, hasSession = false, sameSong = true))
    }

    @Test
    fun restartWithoutAnyRequestHasNothingToPlay() {
        assertEquals(RestartAction.NOTHING_TO_PLAY, RestartDecision.decide(false, hasSession = true, sameSong = true))
        assertEquals(RestartAction.NOTHING_TO_PLAY, RestartDecision.decide(false, hasSession = false, sameSong = false))
    }
}
