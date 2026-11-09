package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.data.debug.SinkDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SinkDiscoveryTest {
    @Test
    fun answerGivesTheSinkAddress() {
        assertEquals("http://10.0.0.7:8781", SinkDiscovery.parseAnswer("10.0.0.7", "HITSTER-SINK 8781"))
        assertEquals("http://10.0.0.7:8781", SinkDiscovery.parseAnswer("10.0.0.7", " HITSTER-SINK 8781\n"))
    }

    @Test
    fun otherTextIsNoAnswer() {
        assertNull(SinkDiscovery.parseAnswer("10.0.0.7", "HITSTER-SINK?"))
        assertNull(SinkDiscovery.parseAnswer("10.0.0.7", "HITSTER-SINK abc"))
        assertNull(SinkDiscovery.parseAnswer("10.0.0.7", "HITSTER-SINK 0"))
        assertNull(SinkDiscovery.parseAnswer("10.0.0.7", "HITSTER-SINK 70000"))
        assertNull(SinkDiscovery.parseAnswer("10.0.0.7", "HELLO 8781"))
        assertNull(SinkDiscovery.parseAnswer("", "HITSTER-SINK 8781"))
    }
}
