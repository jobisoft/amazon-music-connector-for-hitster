package de.jobisoft.amster.ui.screens

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import de.jobisoft.amster.ui.components.GlassCardFull
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.jobisoft.amster.ui.components.ScreenScaffold
import de.jobisoft.amster.ui.viewmodel.OptionsViewModel
import de.jobisoft.amster.util.StartPosition

/** The settings of the app, opened from the Options button of the Home screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsScreen(
    onNavigateBack: () -> Unit,
    viewModel: OptionsViewModel = hiltViewModel()
) {
    val preferId by viewModel.preferId.collectAsState()
    val duckEnabled by viewModel.duckEnabled.collectAsState()
    val startPosition by viewModel.startPosition.collectAsState()
    val hideStatusBar by viewModel.hideStatusBar.collectAsState()

    ScreenScaffold(title = "Options") {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                GlassCardFull { PreferIdSwitch(preferId, viewModel::setPreferId) }
                GlassCardFull { StartPositionDropdown(startPosition, viewModel::setStartPosition) }
                GlassCardFull { DuckSwitch(duckEnabled, startPosition != StartPosition.OFF, viewModel::setDuckEnabled) }
                GlassCardFull { StatusBarSwitch(hideStatusBar, viewModel::setHideStatusBar) }
            }
        }
    }
}

/** Play a card by its Amazon id (default) or always search by title and artist. */
@Composable
private fun PreferIdSwitch(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Prefer Amazon ID over search", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Plays the track by its Amazon ID, which is faster and exact. The IDs were only checked with " +
                    "German Amazon accounts. With accounts of other countries a card may play a wrong song. " +
                    "Turn this off to always search by title and artist.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

/** The small setting for the status bar: some phones show the title of the playing song there. */
@Composable
private fun StatusBarSwitch(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Hide the status bar while playing", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Keeps the song title of the system out of sight. Swipe down from the top to show it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

/** Where a song starts after it loads: Off, 10 s, 20 s, 30 s or the middle of the song. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartPositionDropdown(selected: StartPosition, onSelect: (StartPosition) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Start position", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Where a song starts after it loads.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selected.label,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (position in StartPosition.entries) {
                    DropdownMenuItem(
                        text = { Text(position.label) },
                        onClick = {
                            onSelect(position)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * The small setting for the volume duck: off if Bluetooth headphones react badly to the level change. It needs a
 * start position, so it is dimmed and off while the start position is Off ([usable] false).
 */
@Composable
private fun DuckSwitch(enabled: Boolean, usable: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f).alpha(if (usable) 1f else 0.38f)) {
            Text("Lower volume before playback for a better experience", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (usable) {
                    "Hides the first moment of a song while it jumps to its start. Might not work well with Bluetooth devices."
                } else {
                    "Needs a start position."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = enabled && usable, onCheckedChange = onChange, enabled = usable)
    }
}

