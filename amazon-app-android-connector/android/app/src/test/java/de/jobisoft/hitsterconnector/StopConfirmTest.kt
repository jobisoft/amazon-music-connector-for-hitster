package de.jobisoft.hitsterconnector

import android.media.session.PlaybackState
import de.jobisoft.hitsterconnector.util.StopConfirm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopConfirmTest {

    private val playing = PlaybackState.STATE_PLAYING
    private val buffering = PlaybackState.STATE_BUFFERING
    private val paused = PlaybackState.STATE_PAUSED

    /** A fake player: it answers the next state of [states] at every look, the clock only moves when [sleep] is called. */
    private class Fake(val states: List<Int?>) {
        var clock = 0L
        var looks = 0
        var pauses = 0
        fun read(): Int? = states[minOf(looks++, states.size - 1)]
    }

    private fun run(fake: Fake, timeoutMs: Long = 3000) = runBlocking {
        StopConfirm.run(
            readState = fake::read,
            pause = { fake.pauses++ },
            now = { fake.clock },
            sleep = { fake.clock += it },
            timeoutMs = timeoutMs
        )
    }

    @Test
    fun nothingPlayingMeansConfirmedAtOnceAndNoPause() {
        for (state in listOf(null, paused, PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE)) {
            val fake = Fake(listOf(state))
            val result = run(fake)
            assertTrue(result.confirmed)
            assertFalse(result.needed)
            assertEquals(0, fake.pauses)
            assertEquals(0L, result.elapsedMs)
        }
    }

    @Test
    fun playingSongIsPausedAndConfirmedWhenThePlayerSaysSo() {
        // first look (needs stop), then still playing twice, then paused
        val fake = Fake(listOf(playing, playing, playing, paused))
        val result = run(fake)
        assertTrue(result.confirmed)
        assertTrue(result.needed)
        assertEquals(1, fake.pauses)
        assertEquals(3, result.polls)
        assertEquals(200L, result.elapsedMs) // two sleeps of 100 ms
    }

    @Test
    fun bufferingCountsAsPlayingAndNoSessionAfterThePauseConfirms() {
        val fake = Fake(listOf(buffering, buffering, null))
        val result = run(fake)
        assertTrue(result.confirmed)
        assertEquals(1, fake.pauses)
    }

    @Test
    fun aPlayerThatKeepsPlayingIsGivenUpAfterTheTimeout() {
        val fake = Fake(listOf(playing))
        val result = run(fake, timeoutMs = 3000)
        assertFalse(result.confirmed)
        assertTrue(result.needed)
        assertEquals(1, fake.pauses) // paused once, not again and again
        assertEquals(3000L, result.elapsedMs)
    }

    @Test
    fun confirmationRightAtTheLastLookStillCounts() {
        // 100 ms steps, the 31st look (at 3000 ms) says paused
        val fake = Fake(List(31) { playing } + listOf(paused))
        val result = run(fake, timeoutMs = 3000)
        assertTrue(result.confirmed)
    }
}
