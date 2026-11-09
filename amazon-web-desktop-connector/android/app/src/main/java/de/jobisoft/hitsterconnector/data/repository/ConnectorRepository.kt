package de.jobisoft.hitsterconnector.data.repository

import android.content.Context
import android.net.ConnectivityManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jobisoft.hitsterconnector.domain.model.ConnectorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talks to the helper app of the Firefox add-on "Amazon Music Connector for Hitster".
 *
 * API of the helper app (see the README of the add-on):
 *   UDP "AMC-FIND?"       broadcast to port 8765, every helper answers with the JSON of /ping plus its port
 *   GET  /ping            who is there. Needs no token.
 *   POST /pair            ask to be allowed. The user decides on the options page.
 *   GET  /pair/<id>       the answer, with the token once it is allowed
 *   GET  /status          with "Authorization: Bearer token"
 *   POST /play            with a JSON body { title, artist, year?, card? }
 */
@Singleton
class ConnectorRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
    @param:ApplicationContext private val context: Context
) {

    companion object {
        const val DEFAULT_PORT = 8765
        private const val APP_ID = "amazon-music-connector-for-hitster"
        const val DISCOVERY_PORT = 8765
        const val DISCOVERY_REQUEST = "AMC-FIND?"
        private const val DISCOVERY_WAIT_MS = 1500
        private const val DISCOVERY_MORE_MS = 300

        /** A helper from its answer to the broadcast and the address it came from, or null if it is no answer. */
        fun parseAnswer(host: String, text: String): Server? {
            return try {
                val json = JsonParser.parseString(text).asJsonObject
                if (json.get("app")?.asString != APP_ID) return null
                val port = json.get("port")?.asInt ?: return null
                if (port !in 1..65535 || host.isBlank()) return null
                val url = "http://$host:$port"
                Server(
                    url = url,
                    name = json.get("name")?.asString ?: url,
                    id = json.get("id")?.asString ?: url,
                    version = json.get("version")?.asString ?: "",
                    open = json.get("open")?.asBoolean ?: false
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Answer of the connector to a play request. */
    data class PlayAnswer(val ok: Boolean, val message: String?)

    /** A helper app found in the network. */
    data class Server(
        val url: String,
        val name: String,
        val id: String,
        val version: String,
        /** True if the computer takes requests without a token. */
        val open: Boolean = false
    )

    /** State of a pairing request. [token] is set once it is allowed. */
    data class PairingAnswer(val state: String, val token: String?, val serverName: String?)

    /** Make "192.168.1.5:8765" and "http://192.168.1.5:8765/" both usable. */
    fun normalizeUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    /** A computer in open mode needs no token, the others do. */
    private fun Request.Builder.withToken(config: ConnectorConfig): Request.Builder =
        if (config.token.isNotBlank()) header("Authorization", "Bearer ${config.token}") else this

    // ── finding the helper app ────────────────────────────────────────────────────

    /**
     * Looks for helper apps in the network of the phone. One UDP broadcast, every helper
     * answers with its data, the sender address of the answer is the address of the computer.
     * Waits at most 1.5 s, and 0.3 s after the first answer for more computers.
     */
    suspend fun findServers(): List<Server> = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                // use the Wi-Fi when the phone has mobile data as well
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                manager.activeNetwork?.let { runCatching { it.bindSocket(socket) } }
                socket.broadcast = true
                val request = DISCOVERY_REQUEST.toByteArray()
                socket.send(
                    DatagramPacket(request, request.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT)
                )
                val found = LinkedHashMap<String, Server>()
                var end = System.currentTimeMillis() + DISCOVERY_WAIT_MS
                val buffer = ByteArray(1024)
                while (true) {
                    val left = (end - System.currentTimeMillis()).toInt()
                    if (left <= 0) break
                    socket.soTimeout = left
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    val server = parseAnswer(packet.address.hostAddress ?: "", String(packet.data, 0, packet.length))
                        ?: continue
                    if (found.isEmpty()) end = minOf(end, System.currentTimeMillis() + DISCOVERY_MORE_MS)
                    found[server.id] = server
                }
                found.values.toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── pairing ───────────────────────────────────────────────────────────────────

    suspend fun startPairing(baseUrl: String, deviceName: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = JsonObject().apply { addProperty("name", deviceName) }
            val request = Request.Builder()
                .url("$baseUrl/pair")
                .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val json = JsonParser.parseString(response.body.string()).asJsonObject
                val id = json.get("id")?.asString
                if (response.isSuccessful && id != null) {
                    Result.success(id)
                } else {
                    Result.failure(Exception(json.get("error")?.asString ?: "HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("Cannot reach the server: ${e.message}"))
        }
    }

    suspend fun pollPairing(baseUrl: String, id: String): Result<PairingAnswer> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/pair/$id").build()
            okHttpClient.newCall(request).execute().use { response ->
                val json = JsonParser.parseString(response.body.string()).asJsonObject
                Result.success(
                    PairingAnswer(
                        state = json.get("state")?.asString ?: "expired",
                        token = json.get("token")?.asString,
                        serverName = json.get("name")?.asString
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(Exception("Cannot reach the server: ${e.message}"))
        }
    }

    // ── using the helper app ──────────────────────────────────────────────────────

    suspend fun testConnection(config: ConnectorConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(normalizeUrl(config.serverUrl) + "/status")
                .withToken(config)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Result.success(Unit)
                    response.code == 401 -> Result.failure(Exception("The token is wrong."))
                    else -> Result.failure(Exception("The server answered with HTTP ${response.code}."))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("Cannot reach the server: ${e.message}"))
        }
    }

    suspend fun play(
        config: ConnectorConfig,
        title: String,
        artist: String,
        year: Int?,
        card: String?
    ): Result<PlayAnswer> = withContext(Dispatchers.IO) {
        try {
            val body = JsonObject().apply {
                addProperty("title", title)
                addProperty("artist", artist)
                year?.let { addProperty("year", it) }
                card?.let { addProperty("card", it) }
            }
            val request = Request.Builder()
                .url(normalizeUrl(config.serverUrl) + "/play")
                .withToken(config)
                .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val text = response.body.string()
                val json = try {
                    JsonParser.parseString(text).asJsonObject
                } catch (_: Exception) {
                    null
                }
                val ok = json?.get("ok")?.asBoolean ?: false
                val detail = json?.get("message")?.asString ?: json?.get("error")?.asString
                when {
                    response.code == 401 -> Result.failure(Exception("The token is wrong."))
                    ok -> Result.success(PlayAnswer(true, detail))
                    else -> Result.failure(Exception(detail ?: "The server answered with HTTP ${response.code}."))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("Cannot reach the server: ${e.message}"))
        }
    }

    /**
     * Moves the playhead of the song on the computer: "back10", "forward10",
     * "restart" (back to the start offset, plays), "stop" (pauses), "seek" (to a position in
     * seconds) or "state" (changes nothing). Answers where the song is.
     */
    suspend fun control(
        config: ConnectorConfig,
        action: String,
        position: Float? = null
    ): Result<PlayerPosition> = withContext(Dispatchers.IO) {
        try {
            val body = JsonObject().apply {
                addProperty("action", action)
                position?.let { addProperty("position", it) }
            }
            val request = Request.Builder()
                .url(normalizeUrl(config.serverUrl) + "/control")
                .withToken(config)
                .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val json = try {
                    JsonParser.parseString(response.body.string()).asJsonObject
                } catch (_: Exception) {
                    null
                }
                when {
                    response.code == 401 -> Result.failure(Exception("The token is wrong."))
                    json?.get("ok")?.asBoolean == true -> Result.success(
                        PlayerPosition(
                            position = json.get("position")?.asFloat ?: 0f,
                            duration = json.get("duration")?.asFloat ?: 0f,
                            playing = json.get("playing")?.asBoolean ?: true
                        )
                    )
                    else -> Result.failure(
                        Exception(json?.get("error")?.asString ?: "The server answered with HTTP ${response.code}.")
                    )
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("Cannot reach the server: ${e.message}"))
        }
    }
}

/** Where the song on the computer is, in seconds. */
data class PlayerPosition(val position: Float, val duration: Float, val playing: Boolean)
