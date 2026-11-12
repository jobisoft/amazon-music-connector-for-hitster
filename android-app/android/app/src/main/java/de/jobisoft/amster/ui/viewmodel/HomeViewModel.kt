package de.jobisoft.amster.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jobisoft.amster.data.local.CardOverrides
import de.jobisoft.amster.data.repository.ConnectorRepository
import de.jobisoft.amster.util.CardOverride
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
    private val connectorRepository: ConnectorRepository,
    private val overrides: CardOverrides,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private fun read() = SetupState(
        amazonInstalled = connectorRepository.isAmazonMusicInstalled(),
        notificationAccess = connectorRepository.hasNotificationAccess()
    )

    /** How many cards the export would hold (the latest correction per card). */
    private val _exportCount = MutableStateFlow(overrides.all().size)
    val exportCount: StateFlow<Int> = _exportCount.asStateFlow()

    /** Look again, for example after the user came back from the settings. */
    fun refresh() {
        _state.value = read()
        _exportCount.value = overrides.all().size
    }

    /** The corrections on this phone, for the export. */
    fun corrections(): List<CardOverride> = overrides.all()
}
