package de.jobisoft.hitsterconnector.ui.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.viewModelScope
import de.jobisoft.hitsterconnector.data.debug.DebugLog
import de.jobisoft.hitsterconnector.data.repository.ConnectorRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** What this phone needs before it can play: the Amazon Music app and the notification access. */
data class SetupState(val amazonInstalled: Boolean, val notificationAccess: Boolean) {
    val ready: Boolean get() = amazonInstalled && notificationAccess
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val connectorRepository: ConnectorRepository
) : ViewModel() {

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private fun read() = SetupState(
        amazonInstalled = connectorRepository.isAmazonMusicInstalled(),
        notificationAccess = connectorRepository.hasNotificationAccess()
    )

    private val _diagnostics = MutableStateFlow<String?>(null)

    /** Null before the first run, then what the run did, for the screen. */
    val diagnostics: StateFlow<String?> = _diagnostics.asStateFlow()

    /** Runs the probe once for a fixed test song and posts everything to the debug sink. */
    fun sendDiagnostics() {
        if (_diagnostics.value == "running ...") return
        _diagnostics.value = "running ..."
        val before = DebugLog.sent.value
        DebugLog.searchAgain()
        viewModelScope.launch {
            connectorRepository.diagnostics()
            val sent = DebugLog.sent.value - before
            val failed = DebugLog.failed.value
            _diagnostics.value = "sent $sent events" + if (failed > 0) ", $failed could not be delivered (is the sink running?)" else ""
        }
    }

    /** Look again, for example after the user came back from the settings. */
    fun refresh() {
        _state.value = read()
    }
}
