package de.jobisoft.hitsterconnector.data.repository

import android.os.Build
import de.jobisoft.hitsterconnector.data.local.ConfigDataStore
import de.jobisoft.hitsterconnector.domain.model.ConnectorConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What the app knows about its connection to the helper app. */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Searching : ConnectionState
    data class Choose(val servers: List<ConnectorRepository.Server>) : ConnectionState
    data class WaitingForApproval(val serverName: String) : ConnectionState
    data class Connected(val serverName: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

/**
 * Finds the helper app in the network and pairs with it, without any setup by the
 * user. The user only confirms on the computer that the phone may connect.
 *
 * The order is: use what is saved. If the saved address does not answer, search the
 * network and recognise the same computer by its id. Only if there is none, ask the
 * computer for permission.
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val configDataStore: ConfigDataStore,
    private val connectorRepository: ConnectorRepository
) {
    companion object {
        private const val PAIRING_TIMEOUT_MS = 120_000L
        private const val POLL_INTERVAL_MS = 1_500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    private var job: Job? = null

    /** Connect, unless this is already going on. */
    fun connect() {
        if (job?.isActive == true) return
        job = scope.launch { _state.value = run(allowPairing = true) }
    }

    /** Start from scratch, for the "search again" button. */
    fun reconnect() {
        job?.cancel()
        job = scope.launch { _state.value = run(allowPairing = true) }
    }

    /** The user picked one of several computers. */
    fun choose(server: ConnectorRepository.Server) {
        job?.cancel()
        job = scope.launch { _state.value = pair(server) }
    }

    /**
     * For a request that failed because the computer did not answer. Looks for it
     * again, and never asks for permission. Returns the new settings, or null.
     */
    suspend fun findAgain(): ConnectorConfig? {
        val result = run(allowPairing = false)
        _state.value = result
        return if (result is ConnectionState.Connected) configDataStore.configFlow.first() else null
    }

    private suspend fun run(allowPairing: Boolean): ConnectionState {
        val saved = configDataStore.configFlow.first()
        if (saved != null && connectorRepository.testConnection(saved).isSuccess) {
            return ConnectionState.Connected(saved.serverName ?: saved.serverUrl)
        }

        _state.value = ConnectionState.Searching
        val servers = connectorRepository.findServers()

        // The same computer under a new address, or any computer that accepts the saved token
        if (saved != null) {
            for (server in servers.sortedBy { if (it.id == saved.serverId) 0 else 1 }) {
                val candidate = saved.copy(serverUrl = server.url, serverId = server.id, serverName = server.name)
                if (connectorRepository.testConnection(candidate).isSuccess) {
                    configDataStore.saveConfig(candidate)
                    return ConnectionState.Connected(server.name)
                }
            }
        }

        // A computer that takes requests without pairing, nothing to ask there
        val open = servers.filter { it.open }
        if (open.size == 1 && servers.size == 1) return connectOpen(open.first())

        if (!allowPairing) return ConnectionState.Failed("The computer was not found in this network.")
        return when {
            servers.isEmpty() -> ConnectionState.Failed(
                "No computer found. Is the Firefox add-on running, are phone and computer in the same Wi-Fi, and does the firewall of the computer allow UDP port 8765?"
            )
            servers.size == 1 -> pair(servers.first())
            else -> ConnectionState.Choose(servers)
        }
    }

    private suspend fun connectOpen(server: ConnectorRepository.Server): ConnectionState {
        configDataStore.saveConfig(ConnectorConfig(server.url, "", server.id, server.name))
        return ConnectionState.Connected(server.name)
    }

    private suspend fun pair(server: ConnectorRepository.Server): ConnectionState {
        if (server.open) return connectOpen(server)
        _state.value = ConnectionState.WaitingForApproval(server.name)
        val id = connectorRepository.startPairing(server.url, deviceName()).getOrElse {
            return ConnectionState.Failed(it.message ?: "Pairing failed.")
        }
        var waited = 0L
        while (waited < PAIRING_TIMEOUT_MS) {
            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
            val answer = connectorRepository.pollPairing(server.url, id).getOrNull() ?: continue
            when (answer.state) {
                "approved" -> {
                    val token = answer.token ?: return ConnectionState.Failed("The computer sent no token.")
                    configDataStore.saveConfig(
                        ConnectorConfig(server.url, token, server.id, answer.serverName ?: server.name)
                    )
                    return ConnectionState.Connected(answer.serverName ?: server.name)
                }
                "denied" -> return ConnectionState.Failed("The computer refused the connection.")
                "expired" -> return ConnectionState.Failed("The request ran out of time. Please try again.")
            }
        }
        return ConnectionState.Failed("Nobody confirmed on the computer. Please try again.")
    }

    private fun deviceName(): String {
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
        return if (model.startsWith(Build.MANUFACTURER ?: "", ignoreCase = true)) model else "${Build.MANUFACTURER} $model"
    }
}
