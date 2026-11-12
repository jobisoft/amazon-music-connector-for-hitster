package de.jobisoft.amster.data.debug

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Finds the log sink (tools/debug_sink.py) on the local network. The app sends the UDP
 * broadcast [REQUEST] to [PORT], the sink answers "AMSTER-SINK <http port>" to the sender.
 * The address of the answering PC is the address of the sink. No answer means no sink.
 */
object SinkDiscovery {
    const val PORT = 8782
    const val REQUEST = "AMSTER-SINK?"
    const val ANSWER = "AMSTER-SINK"

    /** "http://host:port" from the sender address and the answer text, or null if it is not an answer. */
    fun parseAnswer(host: String, text: String): String? {
        val parts = text.trim().split(' ')
        if (parts.size != 2 || parts[0] != ANSWER) return null
        val port = parts[1].toIntOrNull() ?: return null
        if (port !in 1..65535 || host.isBlank()) return null
        return "http://$host:$port"
    }

    /** Blocking, at most [timeoutMs]. Returns the sink address or null. */
    fun find(timeoutMs: Int = 2000): String? {
        return try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val request = REQUEST.toByteArray()
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName("255.255.255.255"), PORT))
                val end = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(64)
                var found: String? = null
                while (found == null) {
                    val left = (end - System.currentTimeMillis()).toInt()
                    if (left <= 0) break
                    socket.soTimeout = left
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    found = parseAnswer(packet.address.hostAddress ?: "", String(packet.data, 0, packet.length))
                }
                found
            }
        } catch (_: Exception) {
            null
        }
    }
}
