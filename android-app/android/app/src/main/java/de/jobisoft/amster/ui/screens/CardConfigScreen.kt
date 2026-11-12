package de.jobisoft.amster.ui.screens

import de.jobisoft.amster.ui.theme.AmsterTheme
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.filled.Description
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import de.jobisoft.amster.ui.components.GlassCardFull
import de.jobisoft.amster.ui.components.SecondaryButton
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import de.jobisoft.amster.ui.components.ScreenScaffold
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.jobisoft.amster.ui.viewmodel.CardConfigUiState
import de.jobisoft.amster.ui.viewmodel.CardConfigViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardConfigScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPreview: (String) -> Unit,
    viewModel: CardConfigViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        viewModel.addLocalCSVs(context, uris.map { it to (getFileNameFromUri(context, it) ?: "imported_file.csv") })
    }
    
    ScreenScaffold(title = "Card Config") {
        CardConfigContent(
            uiState = uiState,
            onImport = {
                // Wildcard MIME type: CSV files come with many types from many sources
                filePickerLauncher.launch("*/*")
            },
            onCheckOnline = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, "https://github.com/jobisoft/amster".toUri())
                )
            },
            onPreview = onNavigateToPreview,
            onDelete = { viewModel.removeCSV(it) }
        )
    }
}

/** The body of the screen, stateless: the intro, the buttons, the messages and the list of files. */
@Composable
internal fun CardConfigContent(
    uiState: CardConfigUiState,
    onImport: () -> Unit,
    onCheckOnline: () -> Unit,
    onPreview: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "A card set tells Amster which song belongs to which card number. The sets that come with the app are " +
                "listed below. You can add more as CSV files with the Card#, Title, Artist and Year columns, " +
                "or check our GitHub Repository for available set files.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (uiState.isAddingFile) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
            SecondaryButton(
                text = "Import CSV files",
                onClick = onImport,
                enabled = !uiState.isAddingFile,
                modifier = Modifier.weight(1f)
            )
            SecondaryButton(
                text = "Check online",
                onClick = onCheckOnline,
                modifier = Modifier.weight(1f)
            )
        }
        uiState.importMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Error message
        uiState.error?.let { error ->
            GlassCardFull(tint = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // CSV Files List: one panel that looks like a folder listing, one line per file
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Configured CSV Files",
            style = MaterialTheme.typography.titleMedium
        )

        if (uiState.csvFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No CSV files configured",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            GlassCardFull(
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(uiState.csvFiles) { index, fileName ->
                        if (index > 0) HorizontalDivider(color = AmsterTheme.extras.glassBorder)
                        CSVFileItem(
                            fileName = fileName,
                            onPreview = { onPreview(fileName) },
                            onDelete = { onDelete(fileName) }
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** One line of the file list: a file icon, the name in a fixed width font, and the buttons to look into the file and to remove it. */
@Composable
fun CSVFileItem(
    fileName: String,
    onPreview: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = fileName,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onPreview) {
            Icon(imageVector = Icons.Default.Search, contentDescription = "Preview")
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

private fun getFileNameFromUri(context: android.content.Context, uri: Uri): String? {
    var fileName: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                fileName = cursor.getString(nameIndex)
            }
        }
    }
    return fileName ?: uri.lastPathSegment
}
