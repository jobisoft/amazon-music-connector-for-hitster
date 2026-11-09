package de.jobisoft.hitsterconnector.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jobisoft.hitsterconnector.data.local.ConfigDataStore
import de.jobisoft.hitsterconnector.data.repository.ConnectorRepository
import de.jobisoft.hitsterconnector.domain.model.ConnectorConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConfigViewModel @Inject constructor(
    private val configDataStore: ConfigDataStore,
    private val connectorRepository: ConnectorRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            configDataStore.configFlow.first()?.let {
                _uiState.value = _uiState.value.copy(serverUrl = it.serverUrl, token = it.token)
            }
        }
    }

    fun updateServerUrl(url: String) {
        _uiState.value = _uiState.value.copy(serverUrl = url, isSaved = false, result = null, error = null)
    }

    fun updateToken(token: String) {
        _uiState.value = _uiState.value.copy(token = token, isSaved = false, result = null, error = null)
    }

    private fun currentConfig(): ConnectorConfig? {
        val state = _uiState.value
        if (state.serverUrl.isBlank()) {
            _uiState.value = state.copy(error = "Please fill in the server address.")
            return null
        }
        return ConnectorConfig(
            serverUrl = connectorRepository.normalizeUrl(state.serverUrl),
            token = state.token.trim()
        )
    }

    fun saveConfig() {
        val config = currentConfig() ?: return
        viewModelScope.launch {
            configDataStore.saveConfig(config)
            _uiState.value = _uiState.value.copy(
                serverUrl = config.serverUrl,
                token = config.token,
                isSaved = true,
                error = null
            )
        }
    }

    fun testConnection() {
        val config = currentConfig() ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTesting = true, result = null, error = null)
            connectorRepository.testConnection(config).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isTesting = false,
                        result = "Connected. The helper app of the add-on answers."
                    )
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(isTesting = false, error = it.message)
                }
            )
        }
    }
}

data class ConfigUiState(
    val serverUrl: String = "",
    val token: String = "",
    val isSaved: Boolean = false,
    val isTesting: Boolean = false,
    val result: String? = null,
    val error: String? = null
)
