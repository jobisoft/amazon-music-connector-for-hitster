package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.ui.screens.displayingLines
import de.jobisoft.hitsterconnector.util.LeaveScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultTextsTest {

    @Test
    fun aKnownCardNamesItsSetOnTheSecondLine() {
        assertEquals("Displaying card from" to "Hitster Deutschland Rock", displayingLines("Hitster Deutschland Rock"))
    }

    @Test
    fun withoutASetNameItSaysOnlyDisplayingCard() {
        val lines = displayingLines(null)
        assertEquals("Displaying card", lines.first)
        assertNull(lines.second)
    }

    @Test
    fun theTextsDoNotDependOnThePlayer() {
        // the function has no player argument: pause, stop and the end of the song cannot change the box
        val all = listOf(displayingLines("Set"), displayingLines(null))
        assertTrue(all.all { it.first.startsWith("Displaying") })
    }

    @Test
    fun leavingStopsOnlyWhatThisScreenPlayedAndNeedsASession() {
        assertTrue(LeaveScreen.shouldStop(playedHere = true, hasSession = true))
        assertFalse(LeaveScreen.shouldStop(playedHere = false, hasSession = true)) // never scanned: the player is left alone
        assertFalse(LeaveScreen.shouldStop(playedHere = true, hasSession = false))
        assertFalse(LeaveScreen.shouldStop(playedHere = false, hasSession = false))
    }
}
