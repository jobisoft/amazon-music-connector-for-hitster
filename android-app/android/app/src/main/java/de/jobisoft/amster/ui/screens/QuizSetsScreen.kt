package de.jobisoft.amster.ui.screens

import de.jobisoft.amster.ui.theme.AmsterTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import de.jobisoft.amster.ui.components.GlassCardFull
import de.jobisoft.amster.ui.components.PrimaryButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.jobisoft.amster.ui.components.ScreenScaffold
import de.jobisoft.amster.ui.viewmodel.QuizSetsViewModel
import de.jobisoft.amster.ui.viewmodel.QuizViewModel

/** Pick the card sets for the quiz: a list like the one of Card Config, "All sets" on top, a check box at the end of each line. */
@Composable
fun QuizSetsScreen(
    onStart: (List<String>) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: QuizSetsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    ScreenScaffold(title = "Quiz Mode") {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Pick the card sets for the quiz.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            GlassCardFull(
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        SetRow(label = "All sets", checked = state.allSelected, onToggle = viewModel::toggleAll, bold = true)
                    }
                    items(state.sets, key = { it.file }) { set ->
                        HorizontalDivider(color = AmsterTheme.extras.glassBorder)
                        SetRow(label = set.name, checked = set.file in state.selected, onToggle = { viewModel.toggle(set.file) })
                    }
                }
            }
            PrimaryButton(
                text = "Start",
                onClick = {
                    onStart(if (state.allSelected) listOf(QuizViewModel.ALL) else state.sets.map { it.file }.filter { it in state.selected })
                },
                enabled = state.selected.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )
        }
    }
}

/** One line of the list: the music icon, the name and a check box, the whole line switches it. */
@Composable
private fun SetRow(label: String, checked: Boolean, onToggle: () -> Unit, bold: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.LibraryMusic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
    }
}
