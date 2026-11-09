package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.util.StartJump
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
    fun leakedIntroIsTheFurthestPositionBeforeTheJump() {
        assertEquals(0.8f, StartJump.leakedSeconds(listOf(0f, 0.3f, 0.8f, 20f, 20.2f), 20f), 0.001f)
        assertEquals(0f, StartJump.leakedSeconds(listOf(20f, 20.1f), 20f), 0.001f)
        assertEquals(0f, StartJump.leakedSeconds(emptyList(), 20f), 0.001f)
        // a sample just below the target tolerance is still "before the jump"
        assertEquals(18.4f, StartJump.leakedSeconds(listOf(18.4f, 19f, 20f), 20f), 0.001f)
    }
}
