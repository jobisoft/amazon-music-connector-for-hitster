package de.jobisoft.hitsterconnector.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.jobisoft.hitsterconnector.data.local.CSVFileManager
import de.jobisoft.hitsterconnector.data.local.ConfigDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@HiltViewModel
class CardConfigViewModel @Inject constructor(
    private val csvFileManager: CSVFileManager,
    private val configDataStore: ConfigDataStore
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(CardConfigUiState())
    val uiState: StateFlow<CardConfigUiState> = _uiState.asStateFlow()
    
    init {
        loadCSVFiles()
    }
    
    private fun loadCSVFiles() {
        viewModelScope.launch {
            try {
                val files = csvFileManager.getAllLocalFiles()
                val fileNames = files.map { it.name }.toList()
                _uiState.value = _uiState.value.copy(csvFiles = fileNames)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Error loading CSV files: ${e.message}")
            }
        }
    }
    
    /** Imports the chosen files one by one. The result line names every file that failed. */
    fun addLocalCSVs(context: Context, files: List<Pair<Uri, String>>) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAddingFile = true, error = null, importMessage = null)
            val failures = mutableListOf<String>()
            var added = 0
            for ((uri, rawName) in files) {
                val fileName = rawName.substringAfterLast('/').ifBlank { "imported_file.csv" }
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { inputStream ->
                            val csvDir = File(context.filesDir, "csv_files").apply {
                                if (!exists()) mkdirs()
                            }
                            FileOutputStream(File(csvDir, fileName)).use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        } ?: throw Exception("Could not open file")
                    }
                    val currentUrls = configDataStore.getCsvUrls().toMutableSet()
                    currentUrls.add(fileName)
                    configDataStore.saveCSVUrls(currentUrls)
                    added++
                } catch (e: Exception) {
                    failures.add("Error adding file $fileName: ${e.message}")
                }
            }
            _uiState.value = _uiState.value.copy(
                isAddingFile = false,
                error = failures.joinToString("\n").ifEmpty { null },
                importMessage = if (added > 0) "Imported $added file(s)." else null
            )
            loadCSVFiles()
        }
    }

    fun removeCSV(fileName: String) {
        viewModelScope.launch {
            try {
                val result = csvFileManager.delete(fileName)
                result.fold(
                    onSuccess = {
                        // Remove from CSV URLs in ConfigDataStore
                        val currentUrls = configDataStore.getCsvUrls().toMutableSet()
                        currentUrls.remove(fileName)
                        configDataStore.saveCSVUrls(currentUrls)
                        
                        loadCSVFiles()
                    },
                    onFailure = { exception ->
                        _uiState.value = _uiState.value.copy(
                            error = "Error removing file: ${exception.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Error removing file: ${e.message}")
            }
        }
    }
}

data class CardConfigUiState(
    val isAddingFile: Boolean = false,
    val importMessage: String? = null,
    val error: String? = null,
    val csvFiles: List<String> = emptyList(),
)
