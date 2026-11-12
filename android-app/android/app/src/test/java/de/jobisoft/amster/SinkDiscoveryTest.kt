package de.jobisoft.amster

import de.jobisoft.amster.data.debug.SinkDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SinkDiscoveryTest {
    @Test
    fun answerGivesTheSinkAddress() {
        assertEquals("http://10.0.0.7:8781", SinkDiscovery.parseAnswer("10.0.0.7", "AMSTER-SINK 8781"))
        assertEquals("http://10.0.0.7:8781", SinkDiscovery.parseAnswer("10.0.0.7", " AMSTER-SINK 8781\n"))
    }

    @Test
    fun otherTextIsNoAnswer() {
        assertNull(SinkDiscovery.parseAnswer("10.0.0.7", "AMSTER-SINK?"))
        assertNull(SinkDiscovery.parseAnswer("10.0.0.7", "AMSTER-SINK abc"))
        assertNull(SinkDiscovery.parseAnswer("10.0.0.7", "AMSTER-SINK 0"))
        assertNull(SinkDiscovery.parseAnswer("10.0.0.7", "AMSTER-SINK 70000"))
        assertNull(SinkDiscovery.parseAnswer("10.0.0.7", "HELLO 8781"))
        assertNull(SinkDiscovery.parseAnswer("", "AMSTER-SINK 8781"))
    }
}
