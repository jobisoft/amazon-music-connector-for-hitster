package de.jobisoft.hitsterconnector.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import de.jobisoft.hitsterconnector.ui.components.BackButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.jobisoft.hitsterconnector.ui.viewmodel.CardConfigViewModel

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
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val fileName = getFileNameFromUri(context, it) ?: "imported_file.csv"
            viewModel.addLocalCSV(context, it, fileName)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Card Config") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Download section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = uiState.downloadUrl,
                        onValueChange = viewModel::updateDownloadUrl,
                        label = { Text("CSV File URL") },
                        placeholder = { Text("https://...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !uiState.isDownloading
                    )
                    
                    Button(
                        onClick = {
                            if (uiState.downloadUrl.isNotBlank()) {
                                viewModel.downloadCSV(uiState.downloadUrl)
                            }
                        },
                        enabled = !uiState.isDownloading && uiState.downloadUrl.isNotBlank()
                    ) {
                        if (uiState.isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Download CSV")
                        }
                    }
                }
                
                // Download error
                uiState.downloadError?.let { error ->
                    Text(
                        text = "Download error: $error",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                
                // Download success (shown briefly)
                if (uiState.downloadError == null && !uiState.isDownloading && uiState.downloadUrl.isEmpty()) {
                    // URL was cleared, download likely succeeded
                }
            }
            
            HorizontalDivider()
            
            // Add local file button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                OutlinedButton(
                    onClick = {
                        // Use wildcard MIME type to allow CSV files from any source (Downloads, etc.)
                        filePickerLauncher.launch("*/*")
                    },
                    enabled = !uiState.isAddingFile
                ) {
                    if (uiState.isAddingFile) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Add Local CSV File")
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.importAllSongseekerPlaylists() },
                    enabled = !uiState.isDownloading &&
                        !uiState.isAddingFile &&
                        !uiState.isBulkImporting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.isBulkImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = "Add all CSV-Files from songseeker-hitster-playlists repository",
                        textAlign = TextAlign.Center,
                        maxLines = 3
                    )
                }
                if (uiState.isBulkImporting && uiState.bulkImportProgressTotal > 0) {
                    LinearProgressIndicator(
                        progress = {
                            uiState.bulkImportProgressCurrent.toFloat() /
                                uiState.bulkImportProgressTotal.toFloat()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "${uiState.bulkImportProgressCurrent} / ${uiState.bulkImportProgressTotal}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                uiState.bulkImportResultMessage?.let { msg ->
                    val isErrorStyle = msg.contains("Failed:") ||
                        msg.contains("Failed to", ignoreCase = true) ||
                        msg.startsWith("No CSV", ignoreCase = true)
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isErrorStyle) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { viewModel.clearBulkImportResult() }) {
                        Text("Dismiss")
                    }
                }
            }

            HorizontalDivider()
            
            // Error message
            uiState.error?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            
            // CSV Files List
            Text(
                text = "Configured CSV Files",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            
            if (uiState.csvFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No CSV files configured",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.csvFiles) { fileName ->
                        CSVFileItem(
                            fileName = fileName,
                            onPreview = { onNavigateToPreview(fileName) },
                            onDelete = { viewModel.removeCSV(fileName) }
                        )
                    }
                }
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
fun CSVFileItem(
    fileName: String,
    onPreview: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onPreview) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Preview"
                        )
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
