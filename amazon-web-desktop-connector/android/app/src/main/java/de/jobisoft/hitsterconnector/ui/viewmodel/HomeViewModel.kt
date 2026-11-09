package de.jobisoft.hitsterconnector.ui.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jobisoft.hitsterconnector.data.repository.ConnectionManager
import de.jobisoft.hitsterconnector.data.repository.ConnectorRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Starts the search for the computer when the app opens, and shows how it goes. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val connectionManager: ConnectionManager
) : ViewModel() {

    val state: StateFlow<de.jobisoft.hitsterconnector.data.repository.ConnectionState> = connectionManager.state

    init {
        connectionManager.connect()
    }

    fun searchAgain() = connectionManager.reconnect()

    fun choose(server: ConnectorRepository.Server) = connectionManager.choose(server)
}
