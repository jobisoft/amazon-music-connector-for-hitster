package de.jobisoft.hitsterconnector.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.jobisoft.hitsterconnector.data.repository.ConnectionState
import de.jobisoft.hitsterconnector.ui.viewmodel.HomeViewModel

@Composable
fun HomeScreen(
    onNavigateToConfig: () -> Unit,
    onNavigateToCardConfig: () -> Unit,
    onNavigateToScanner: () -> Unit,
    onNavigateToHelp: () -> Unit,
    onNavigateToLicenses: () -> Unit,
    onNavigateToAbout: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Hitster Amazon Desktop Connector",
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Scan a Hitster card, play the song on Amazon Music in your browser",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            ConnectionCard(
                state = state,
                onSearchAgain = viewModel::searchAgain,
                onChoose = viewModel::choose,
                onManual = onNavigateToConfig
            )

            Button(
                onClick = onNavigateToScanner,
                enabled = state is ConnectionState.Connected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            ) {
                Text(text = "Scan QR Code", style = MaterialTheme.typography.titleLarge)
            }

            OutlinedButton(
                onClick = onNavigateToCardConfig,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Text(text = "Card Config", style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(modifier = Modifier.height(8.dp))

            for ((label, action) in listOf(
                "Manual setup" to onNavigateToConfig,
                "Help" to onNavigateToHelp,
                "Open Source Licenses" to onNavigateToLicenses,
                "About" to onNavigateToAbout
            )) {
                OutlinedButton(
                    onClick = action,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                ) {
                    Text(text = label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Shows how the search for the computer goes, and what the user can do about it. */
@Composable
private fun ConnectionCard(
    state: ConnectionState,
    onSearchAgain: () -> Unit,
    onChoose: (de.jobisoft.hitsterconnector.data.repository.ConnectorRepository.Server) -> Unit,
    onManual: () -> Unit
) {
    val isGood = state is ConnectionState.Connected
    val isBad = state is ConnectionState.Failed
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isGood -> MaterialTheme.colorScheme.primaryContainer
                isBad -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (state) {
                ConnectionState.Idle, ConnectionState.Searching -> {
                    CircularProgressIndicator()
                    Text("Searching for your computer ...", style = MaterialTheme.typography.titleMedium)
                }

                is ConnectionState.Choose -> {
                    Text("More than one computer found. Which one?", style = MaterialTheme.typography.titleMedium)
                    for (server in state.servers) {
                        Button(onClick = { onChoose(server) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${server.name} (${server.url.removePrefix("http://")})")
                        }
                    }
                }

                is ConnectionState.WaitingForApproval -> {
                    CircularProgressIndicator()
                    Text("Waiting for your OK on ${state.serverName}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The options page of the Firefox add-on has opened there. Tap Allow.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }

                is ConnectionState.Connected -> {
                    Text("Connected to ${state.serverName}", style = MaterialTheme.typography.titleMedium)
                }

                is ConnectionState.Failed -> {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Button(onClick = onSearchAgain, modifier = Modifier.fillMaxWidth()) {
                        Text("Search again")
                    }
                    OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) {
                        Text("Enter manually")
                    }
                }
            }
        }
    }
}
