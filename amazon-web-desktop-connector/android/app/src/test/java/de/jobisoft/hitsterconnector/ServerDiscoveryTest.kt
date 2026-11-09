package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.data.repository.ConnectorRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDiscoveryTest {
    private val answer = """{"app":"amazon-music-connector-for-hitster","version":"1.0.0","name":"laptop","id":"abc","open":true,"port":8800}"""

    @Test
    fun answerGivesTheServerAtTheSenderAddress() {
        val server = ConnectorRepository.parseAnswer("192.168.1.20", answer)!!
        assertEquals("http://192.168.1.20:8800", server.url)
        assertEquals("laptop", server.name)
        assertEquals("abc", server.id)
        assertEquals("1.0.0", server.version)
        assertTrue(server.open)
    }

    @Test
    fun openDefaultsToFalse() {
        val text = """{"app":"amazon-music-connector-for-hitster","port":8765}"""
        val server = ConnectorRepository.parseAnswer("10.0.0.2", text)!!
        assertFalse(server.open)
        assertEquals("http://10.0.0.2:8765", server.name)
    }

    @Test
    fun otherTextIsNoAnswer() {
        assertNull(ConnectorRepository.parseAnswer("10.0.0.2", "AMC-FIND?"))
        assertNull(ConnectorRepository.parseAnswer("10.0.0.2", """{"app":"other","port":8765}"""))
        assertNull(ConnectorRepository.parseAnswer("10.0.0.2", """{"app":"amazon-music-connector-for-hitster"}"""))
        assertNull(ConnectorRepository.parseAnswer("10.0.0.2", """{"app":"amazon-music-connector-for-hitster","port":0}"""))
        assertNull(ConnectorRepository.parseAnswer("", answer))
    }
}
