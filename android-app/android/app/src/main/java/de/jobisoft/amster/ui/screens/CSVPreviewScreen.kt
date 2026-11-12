package de.jobisoft.amster.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.jobisoft.amster.ui.components.BackButton
import de.jobisoft.amster.ui.viewmodel.CSVPreviewViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CSVPreviewScreen(
    fileName: String,
    onNavigateBack: () -> Unit
) {
    val viewModel: CSVPreviewViewModel = hiltViewModel()
    val csvFileManager = viewModel.csvFileManager
    val scrollState = rememberScrollState()
    var content by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(fileName) {
        try {
            val file = csvFileManager.getLocalFile(fileName)
            content = file?.readText() ?: "File not found"
            isLoading = false
        } catch (e: Exception) {
            error = e.message ?: "Failed to load file"
            isLoading = false
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preview: $fileName") }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                error != null -> {
                    Text(
                        text = "Error: $error",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {
                    content?.let {
                        Column(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(scrollState)
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium
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
            }
        }
    }
}
