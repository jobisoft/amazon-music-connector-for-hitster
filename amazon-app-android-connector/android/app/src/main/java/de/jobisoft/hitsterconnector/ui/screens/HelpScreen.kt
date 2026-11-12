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
                    content = "Scan a Hitster card and the song plays in the Amazon Music app on this phone."
                )

                Section(
                    title = "Setup",
                    content = """
                        1. Install the Amazon Music app and log in.
                        2. On the home screen tap "Open notification access settings" and switch this app on. Android needs this to let one app control the player of another. The app reads no notifications.
                        3. Back on the home screen both setup lines show a tick.
                    """.trimIndent()
                )

                Section(
                    title = "Modes",
                    content = """
                        1. Play Mode: scan a card. The song starts at the start position set in the Options, 20 seconds by default (short songs earlier). Title, artist and year stay hidden, tap the blurred box to show them. The buttons pause, stop and jump 10 seconds.
                        2. Quiz Mode: pick card sets. Random cards play one after another, none twice until all have played.
                        3. Reader Mode: scan a card to see its artist, year and title. "Send to Amazon Music app" plays it.
                    """.trimIndent()
                )

                Section(
                    title = "Notes",
                    content = """
                        • A card is wrong? Tap "Report as wrong", change the values and save. The correction stays on this phone. "Export" in Reader Mode shares them as a file.
                        • A song is paused one second before its end, so Amazon should not go on with another song.
                        • Amazon may play another version of a song, for example a remix. The app then says so.
                        • If the Amazon Music app stays on top, go back to this app.
                        • "Card Config" imports more card sets from CSV files on the phone. A file needs the columns Card#, Title, Artist and Year.
                    """.trimIndent()
                )

                Section(
                    title = "Requirements",
                    content = """
                        • Android 7.0 or newer with a camera
                        • The Amazon Music app with an account
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
