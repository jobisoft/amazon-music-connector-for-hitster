package de.jobisoft.amster.ui.screens

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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.draw.alpha
import de.jobisoft.amster.util.StartPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import de.jobisoft.amster.util.CrashReporter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import de.jobisoft.amster.ui.viewmodel.SetupState
import de.jobisoft.amster.ui.viewmodel.HomeViewModel

@Composable
fun HomeScreen(
    onNavigateToCardConfig: () -> Unit,
    onNavigateToScanner: () -> Unit,
    onNavigateToQuiz: () -> Unit,
    onNavigateToReader: () -> Unit,
    onNavigateToOptions: () -> Unit,
    onNavigateToHelp: () -> Unit,
    onNavigateToLicenses: () -> Unit,
    onNavigateToAbout: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    // the crash of the last run, in every build, so that it can be copied or shared without adb
    val appContext = LocalContext.current
    var crash by remember { mutableStateOf(CrashReporter.read(appContext)) }
    crash?.let { text ->
        CrashDialog(
            text = text,
            onClose = {
                CrashReporter.clear(appContext)
                crash = null
            }
        )
    }

    // the user may have changed the settings or installed the app while this screen was away
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                text = androidx.compose.ui.res.stringResource(de.jobisoft.amster.R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Scan a Hitster card and play the song in the Amazon Music app on this phone",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            SetupCard(state)

            // the three modes, one composable: the same button, size, style and spacing, in this order
            ModeButton("Play Mode", enabled = state.ready, onClick = onNavigateToScanner)
            ModeButton("Quiz Mode", enabled = state.ready, onClick = onNavigateToQuiz)
            // not tied to the Amazon Music setup: the reader needs none of it
            ModeButton("Reader Mode", enabled = true, onClick = onNavigateToReader)

            // the options: a block of its own, the size of the buttons below, the tonal style
            Spacer(modifier = Modifier.height(16.dp))
            FilledTonalButton(
                onClick = onNavigateToOptions,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            ) {
                Text(text = "Options", style = MaterialTheme.typography.bodySmall)
            }

            // the last block: a larger gap above it, the same button style and spacing inside
            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val secondary = listOf(
                    "Card Config" to onNavigateToCardConfig,
                    "Help" to onNavigateToHelp,
                    "Open Source Licenses" to onNavigateToLicenses,
                    "About" to onNavigateToAbout
                )
                for ((label, action) in secondary) {
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
}

/** The two things this phone needs, with a tick or a cross, and what to do about it. */
@Composable
private fun SetupCard(state: SetupState) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state.ready) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Setup", style = MaterialTheme.typography.titleMedium)
            Text(
                (if (state.amazonInstalled) "✓" else "✗") + "  Amazon Music app installed",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                (if (state.notificationAccess) "✓" else "✗") + "  Notification access granted",
                style = MaterialTheme.typography.bodyLarge
            )
            if (!state.notificationAccess) {
                Text(
                    "AmSter needs notification access to start, pause and read the position of the song in the Amazon Music app. " +
                        "Android ties this control to that permission. AmSter does not read, store or send your notifications. " +
                        "Switch it on for AmSter in the next screen.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open notification access settings")
                }
            }
            if (!state.amazonInstalled) {
                Text("Install the Amazon Music app and log in, then come back.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The stack trace of the last crash, with "Copy", "Share" and "Close" (closing deletes it). */
@Composable
private fun CrashDialog(text: String, onClose: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { /* only "Close" removes it: the text should not be lost by a stray tap */ },
        title = { Text("The app crashed last time") },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("crash", text))
                }) { Text("Copy") }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, context.getString(de.jobisoft.amster.R.string.app_name) + ": crash")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(send, "Share the crash").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Share") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    )
}

/** The button of one of the three modes of the Home screen: full width, 72 dp high, the filled primary style. */
@Composable
private fun ModeButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.titleLarge)
    }
}
