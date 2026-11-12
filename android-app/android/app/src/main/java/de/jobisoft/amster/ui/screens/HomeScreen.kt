package de.jobisoft.amster.ui.screens

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.jobisoft.amster.ui.ExportShare
import de.jobisoft.amster.ui.components.GlassCardFull
import de.jobisoft.amster.ui.components.PrimaryButton
import de.jobisoft.amster.ui.components.ScreenScaffold
import de.jobisoft.amster.ui.theme.AmsterTheme
import de.jobisoft.amster.ui.theme.Dimens
import de.jobisoft.amster.ui.viewmodel.HomeViewModel
import de.jobisoft.amster.ui.viewmodel.SetupState
import de.jobisoft.amster.util.CrashReporter

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
    val exportCount by viewModel.exportCount.collectAsState()

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

    // "Export" shares the corrections of this phone as a file, the same as the button in Reader Mode did
    val onExport = {
        val corrections = viewModel.corrections()
        if (corrections.isEmpty()) {
            Toast.makeText(appContext, "Nothing to export yet. Use \"Update manually\" on a card first.", Toast.LENGTH_LONG).show()
        } else if (!ExportShare.share(appContext, corrections)) {
            Toast.makeText(appContext, "The export did not work.", Toast.LENGTH_LONG).show()
        }
    }

    val modes: List<Triple<String, ImageVector, Pair<Boolean, () -> Unit>>> = listOf(
        // the three modes, one composable: the same card, size, style and spacing, in this order
        Triple("Play Mode", Icons.Filled.QrCodeScanner, state.ready to onNavigateToScanner),
        Triple("Quiz Mode", Icons.Filled.Quiz, state.ready to onNavigateToQuiz),
        // not tied to the Amazon Music setup: the reader needs none of it
        Triple("Reader Mode", Icons.Filled.DocumentScanner, true to onNavigateToReader)
    )
    // everything else: small glass tiles, two in a row
    val secondary = listOf(
        Triple("Options", Icons.Filled.Settings, onNavigateToOptions),
        Triple("Card Config", Icons.Filled.LibraryMusic, onNavigateToCardConfig),
        Triple("Export ($exportCount)", Icons.Filled.IosShare, onExport),
        Triple("Help", Icons.AutoMirrored.Filled.HelpOutline, onNavigateToHelp),
        Triple("Open Source Licenses", Icons.Filled.Description, onNavigateToLicenses),
        Triple("About", Icons.Filled.Info, onNavigateToAbout)
    )

    ScreenScaffold(title = null, contentMaxWidth = Dimens.WideMaxWidth) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val wide = maxWidth >= Dimens.WideBreakpoint
            val header: @Composable ColumnScope.() -> Unit = {
                Text(
                    text = androidx.compose.ui.res.stringResource(de.jobisoft.amster.R.string.app_name),
                    style = MaterialTheme.typography.displayMedium.merge(TextStyle(brush = AmsterTheme.extras.accentBrush)),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Scan a Hitster card and play the song in the Amazon Music app on this phone",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                SetupCard(state)
            }
            val entries: @Composable ColumnScope.() -> Unit = {
                for ((label, icon, enabledAndAction) in modes) {
                    ModeCard(label, icon, enabled = enabledAndAction.first, onClick = enabledAndAction.second)
                }
                Spacer(modifier = Modifier.height(Dimens.Space8))
                for (pair in secondary.chunked(2)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.Space12)
                    ) {
                        for ((label, icon, action) in pair) {
                            SmallTile(label, icon, action, Modifier.weight(1f))
                        }
                        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
            if (wide) {
                // a tablet or a phone in landscape: the title and the setup on the left, the entries on the right
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Dimens.Space16),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Space24)
                ) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Dimens.Space16),
                        content = header
                    )
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Dimens.Space16),
                        content = entries
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(Dimens.Space16),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Dimens.Space16)
                ) {
                    Spacer(modifier = Modifier.height(Dimens.Space24))
                    header()
                    entries()
                    Spacer(modifier = Modifier.height(Dimens.Space16))
                }
            }
        }
    }
}

/** One line of the setup card: a green tick or a red cross as an icon, then the text. */
@Composable
private fun SetupRow(ok: Boolean, text: String) {
    val extras = AmsterTheme.extras
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = if (ok) "Done" else "Missing",
            tint = if (ok) extras.success else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp)
        )
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The two things this phone needs, with a tick or a cross, and what to do about it. */
@Composable
private fun SetupCard(state: SetupState) {
    val context = LocalContext.current
    val extras = AmsterTheme.extras
    GlassCardFull(
        tint = if (state.ready) extras.successContainer else MaterialTheme.colorScheme.errorContainer,
        borderColor = (if (state.ready) extras.success else MaterialTheme.colorScheme.error).copy(alpha = 0.4f),
        verticalArrangement = Arrangement.spacedBy(Dimens.Space12)
    ) {
        Text("Setup", style = MaterialTheme.typography.titleMedium)
        SetupRow(state.amazonInstalled, "Amazon Music app installed")
        SetupRow(state.notificationAccess, "Notification access granted")
        if (!state.notificationAccess) {
            Text(
                "Amster needs notification access to start, pause and read the position of the song in the Amazon Music app. " +
                    "Android ties this control to that permission. Amster does not read, store or send your notifications. " +
                    "Switch it on for Amster in the next screen.",
                style = MaterialTheme.typography.bodyMedium
            )
            PrimaryButton(
                text = "Open notification access settings",
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (!state.amazonInstalled) {
            Text("Install the Amazon Music app and log in, then come back.", style = MaterialTheme.typography.bodyMedium)
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

/** The card of one of the three modes of the Home screen: full width, a round accent icon, the name, an arrow. */
@Composable
private fun ModeCard(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val extras = AmsterTheme.extras
    GlassCardFull(
        modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
        onClick = if (enabled) onClick else null,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.Space16)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.size(56.dp).clip(CircleShape).background(extras.accentBrush),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = extras.onAccent, modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.width(Dimens.Space16))
            Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = extras.textDim,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/** A small glass tile with an icon and a label, for the less used screens. */
@Composable
private fun SmallTile(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassCardFull(
        modifier = modifier,
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.Space12)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space12)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}
