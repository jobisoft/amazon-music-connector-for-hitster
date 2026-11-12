package de.jobisoft.amster

import de.jobisoft.amster.util.StartJump
import de.jobisoft.amster.util.StartPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartJumpTest {

    @Test
    fun theHeadIsNearWithinAboutOneAndAHalfSeconds() {
        assertTrue(StartJump.near(20.0f, 20f))
        assertTrue(StartJump.near(18.6f, 20f))
        assertTrue(StartJump.near(21.4f, 20f))
        assertFalse(StartJump.near(0f, 20f)) // the player briefly reports 0 while it jumps
        assertFalse(StartJump.near(18.4f, 20f))
    }

    @Test
    fun songsStartAtTheOffsetAndShortSongsAtAQuarter() {
        assertEquals(20f, StartJump.startFor(200f, 20f), 0.001f)
        assertEquals(20f, StartJump.startFor(50f, 20f), 0.001f)
        assertEquals(12f, StartJump.startFor(49f, 20f), 0.001f) // under offset + 30 s: a quarter, rounded down
        assertEquals(20f, StartJump.startFor(0f, 20f), 0.001f) // unknown length
        assertEquals(10f, StartJump.startFor(200f, 10f), 0.001f)
    }

    @Test
    fun offDoesNotMoveTheSong() {
        assertEquals(0f, StartJump.startFor(200f, StartPosition.OFF), 0.001f)
        assertEquals(0f, StartJump.startFor(0f, StartPosition.OFF), 0.001f)
        assertEquals(0f, StartJump.startFor(10f, StartPosition.OFF), 0.001f)
    }

    @Test
    fun theFixedPositionsFollowTheOffsetAndTheShortSongRule() {
        assertEquals(10f, StartJump.startFor(200f, StartPosition.S10), 0.001f)
        assertEquals(20f, StartJump.startFor(200f, StartPosition.S20), 0.001f)
        assertEquals(30f, StartJump.startFor(200f, StartPosition.S30), 0.001f)
        // under offset + 30 s a quarter of the length, rounded down
        assertEquals(9f, StartJump.startFor(39f, StartPosition.S10), 0.001f)
        assertEquals(10f, StartJump.startFor(40f, StartPosition.S10), 0.001f)
        assertEquals(14f, StartJump.startFor(59f, StartPosition.S30), 0.001f)
        assertEquals(30f, StartJump.startFor(60f, StartPosition.S30), 0.001f)
        // the length is not known yet: the offset, the reactor waits for the length
        assertEquals(30f, StartJump.startFor(0f, StartPosition.S30), 0.001f)
    }

    @Test
    fun theMiddleIsHalfTheLengthAndNothingWithoutALength() {
        assertEquals(100f, StartJump.startFor(200f, StartPosition.MIDDLE), 0.001f)
        assertEquals(15f, StartJump.startFor(30f, StartPosition.MIDDLE), 0.001f)
        assertEquals(0f, StartJump.startFor(0f, StartPosition.MIDDLE), 0.001f)
    }

    @Test
    fun theMiddleStaysInFrontOfTheEndZone() {
        for (length in listOf(3f, 4f, 10f, 200f)) {
            val target = StartJump.startFor(length, StartPosition.MIDDLE)
            assertTrue("length $length", target < length - de.jobisoft.amster.util.EndDecision.END_ZONE_S)
        }
    }
}
