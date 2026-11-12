package de.jobisoft.amster.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import de.jobisoft.amster.ui.components.BackButton
import de.jobisoft.amster.ui.viewmodel.QuizSetsViewModel
import de.jobisoft.amster.ui.viewmodel.QuizViewModel

/** Pick the card sets for the quiz: every set on the phone, and "All sets" on top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizSetsScreen(
    onStart: (List<String>) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: QuizSetsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Quiz Mode") }) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Text(
                "Pick the card sets. A random card of them plays, again and again.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    CheckRow(label = "All sets", checked = state.allSelected, onToggle = viewModel::toggleAll)
                }
                items(state.sets, key = { it.file }) { set ->
                    CheckRow(label = set.name, checked = set.file in state.selected, onToggle = { viewModel.toggle(set.file) })
                }
            }
            Button(
                onClick = {
                    onStart(if (state.allSelected) listOf(QuizViewModel.ALL) else state.sets.map { it.file }.filter { it in state.selected })
                },
                enabled = state.selected.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(56.dp)
            ) {
                Text("Start")
            }
            BackButton(onClick = onNavigateBack, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
