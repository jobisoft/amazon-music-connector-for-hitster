package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.util.StartPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartPositionTest {
    @Test
    fun theDefaultIsTwentySeconds() {
        assertEquals(StartPosition.S20, StartPosition.DEFAULT)
    }

    @Test
    fun everyPositionIsFoundByItsKey() {
        for (position in StartPosition.entries) assertEquals(position, StartPosition.fromKey(position.key))
    }

    @Test
    fun anUnknownOrMissingKeyGivesTheDefault() {
        assertEquals(StartPosition.DEFAULT, StartPosition.fromKey(null))
        assertEquals(StartPosition.DEFAULT, StartPosition.fromKey("45"))
        assertEquals(StartPosition.DEFAULT, StartPosition.fromKey(""))
    }

    @Test
    fun theKeysAreDistinctAndTheLabelsAreWhatTheScreenShows() {
        assertEquals(StartPosition.entries.size, StartPosition.entries.map { it.key }.toSet().size)
        assertEquals(
            listOf("Off", "10 s", "20 s", "30 s", "Middle of the song"),
            StartPosition.entries.map { it.label }
        )
        assertTrue(StartPosition.entries.first().key == "off")
    }
}
