package de.jobisoft.hitsterconnector.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jobisoft.hitsterconnector.util.HideStatusBarSetting
import de.jobisoft.hitsterconnector.util.PreferIdSetting
import de.jobisoft.hitsterconnector.util.StartPosition
import de.jobisoft.hitsterconnector.util.StartSetting
import de.jobisoft.hitsterconnector.util.VolumeDuck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** The settings of the Options screen, each one kept in its own preferences. */
@HiltViewModel
class OptionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _preferId = MutableStateFlow(PreferIdSetting.isEnabled(context))

    /** The switch "Prefer Amazon ID over search". */
    val preferId: StateFlow<Boolean> = _preferId.asStateFlow()

    fun setPreferId(enabled: Boolean) {
        PreferIdSetting.setEnabled(context, enabled)
        _preferId.value = enabled
    }

    private val _duckEnabled = MutableStateFlow(VolumeDuck.isEnabled(context))

    /** The switch "Lower volume before playback for a better experience". */
    val duckEnabled: StateFlow<Boolean> = _duckEnabled.asStateFlow()

    fun setDuckEnabled(enabled: Boolean) {
        VolumeDuck.setEnabled(context, enabled)
        _duckEnabled.value = enabled
    }

    private val _startPosition = MutableStateFlow(StartSetting.get(context))

    /** The dropdown "Start position". */
    val startPosition: StateFlow<StartPosition> = _startPosition.asStateFlow()

    fun setStartPosition(position: StartPosition) {
        StartSetting.set(context, position)
        _startPosition.value = position
    }

    private val _hideStatusBar = MutableStateFlow(HideStatusBarSetting.isEnabled(context))

    /** The switch "Hide the status bar while playing". */
    val hideStatusBar: StateFlow<Boolean> = _hideStatusBar.asStateFlow()

    fun setHideStatusBar(enabled: Boolean) {
        HideStatusBarSetting.setEnabled(context, enabled)
        _hideStatusBar.value = enabled
    }
}
