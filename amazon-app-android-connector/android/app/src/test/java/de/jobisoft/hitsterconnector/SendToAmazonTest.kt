package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.reader.SendStatus
import de.jobisoft.hitsterconnector.reader.SendToAmazon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendToAmazonTest {

    @Test
    fun theReasonIsTheFirstThingThatIsMissing() {
        assertNull(SendToAmazon.problem(installed = true, notificationAccess = true))
        assertEquals("The Amazon Music app is not installed.", SendToAmazon.problem(installed = false, notificationAccess = true))
        // not installed says more than no access (without the app there is nothing to give access to)
        assertEquals("The Amazon Music app is not installed.", SendToAmazon.problem(installed = false, notificationAccess = false))
        assertEquals("Notification access is not granted (Home screen, Setup).", SendToAmazon.problem(installed = true, notificationAccess = false))
    }

    @Test
    fun theButtonNeedsACardAndAReadyAmazonAndNoSendUnderWay() {
        assertTrue(SendToAmazon.enabled(hasCard = true, problem = null, status = SendStatus.IDLE))
        assertTrue("again while it plays or after an error", SendToAmazon.enabled(true, null, SendStatus.PLAYING))
        assertTrue(SendToAmazon.enabled(true, null, SendStatus.FAILED))
        assertFalse("no card", SendToAmazon.enabled(false, null, SendStatus.IDLE))
        assertFalse("a reason", SendToAmazon.enabled(true, "The Amazon Music app is not installed.", SendStatus.IDLE))
        assertFalse("sending", SendToAmazon.enabled(true, null, SendStatus.SENDING))
    }

    @Test
    fun theStatusLineSaysWhatThePlayerDoes() {
        assertEquals("", SendToAmazon.statusLine(SendStatus.IDLE, songEnded = false))
        assertEquals("Sending card info to the Amazon Music app ...", SendToAmazon.statusLine(SendStatus.SENDING, false))
        assertEquals(
            "Sending card info to the Amazon Music app ...\nbuffering",
            SendToAmazon.statusLine(SendStatus.SENDING, false, playStatus = "buffering")
        )
        assertEquals("a status after the send is not shown", "Playing", SendToAmazon.statusLine(SendStatus.PLAYING, false, playStatus = "searching"))
        assertEquals("Playing", SendToAmazon.statusLine(SendStatus.PLAYING, false))
        assertTrue(SendToAmazon.statusLine(SendStatus.PLAYING, songEnded = true).startsWith("Song ended"))
        assertEquals("an error is a red box, not a line", "", SendToAmazon.statusLine(SendStatus.FAILED, false))
    }

    @Test
    fun aScanStopsOnlyASongThatTheButtonStarted() {
        val r = de.jobisoft.hitsterconnector.reader.SendToAmazon
        val st = de.jobisoft.hitsterconnector.reader.SendStatus.entries.associateBy { it.name }
        org.junit.Assert.assertTrue(r.stopsOnScan(st.getValue("PLAYING")))
        org.junit.Assert.assertTrue(r.stopsOnScan(st.getValue("SENDING")))
        org.junit.Assert.assertFalse(r.stopsOnScan(st.getValue("IDLE")))
        org.junit.Assert.assertFalse(r.stopsOnScan(st.getValue("FAILED")))
    }
}
