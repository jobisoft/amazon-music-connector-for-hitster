package de.jobisoft.amster

import de.jobisoft.amster.ui.screens.displayingLine
import de.jobisoft.amster.util.LeaveScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultTextsTest {

    @Test
    fun aKnownCardShowsOnlyItsSetName() {
        assertEquals("Hitster Deutschland Rock", displayingLine("Hitster Deutschland Rock"))
    }

    @Test
    fun withoutASetNameItSaysOnlyDisplayingCard() {
        assertEquals("Displaying card", displayingLine(null))
    }

    @Test
    fun leavingStopsOnlyWhatThisScreenPlayedAndNeedsASession() {
        assertTrue(LeaveScreen.shouldStop(playedHere = true, hasSession = true))
        assertFalse(LeaveScreen.shouldStop(playedHere = false, hasSession = true)) // never scanned: the player is left alone
        assertFalse(LeaveScreen.shouldStop(playedHere = true, hasSession = false))
        assertFalse(LeaveScreen.shouldStop(playedHere = false, hasSession = false))
    }
}
