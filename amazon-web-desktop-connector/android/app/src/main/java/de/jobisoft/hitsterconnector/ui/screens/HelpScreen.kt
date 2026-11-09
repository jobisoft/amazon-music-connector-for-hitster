package de.jobisoft.hitsterconnector.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.jobisoft.hitsterconnector.ui.components.BackButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Help") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Section(
                    title = "Purpose",
                    content = "This app lets you play Hitster with Amazon Music. You scan a card with the phone, and the song plays in your browser on the computer."
                )

                Section(
                    title = "How it works",
                    content = """
                        1. The app reads the QR code of the card and looks up title and artist in a CSV file.
                        2. It sends both to the helper app of the Firefox add-on "Amazon Music Connector for Hitster" on your computer.
                        3. The add-on searches the song in an open Amazon Music tab and plays it.
                    """.trimIndent()
                )

                Section(
                    title = "Setup",
                    content = """
                        1. Install the Firefox add-on and its helper app on your computer. The options page of the add-on guides you through it. It also shows a QR code to install this app.
                        2. Open this app in the same Wi-Fi. It searches for your computer by itself.
                        3. The options page of the add-on opens on the computer. Tap Allow there. This is needed only once.
                        4. Open Amazon Music in Firefox on the computer and log in. The card sets are already in the app.
                        If the search finds nothing, "Manual setup" takes the server address and the token from the options page.
                    """.trimIndent()
                )

                Section(
                    title = "Playing",
                    content = """
                        • Tap "Scan QR Code" and point the camera at a card.
                        • The song starts on the computer. The app does not show it, to keep the game fair.
                        • The buttons jump 10 seconds back or forward, stop the song and play it again from the start offset.
                    """.trimIndent()
                )

                Section(
                    title = "Requirements",
                    content = """
                        • Android 7.0 or newer with a camera
                        • A computer with Firefox, the add-on, its helper app and an Amazon Music account
                        • Phone and computer in the same network
                    """.trimIndent()
                )
            }

            // Back button at the bottom
            BackButton(
                onClick = onNavigateBack,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: String
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
