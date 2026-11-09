package de.jobisoft.hitsterconnector.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.jobisoft.hitsterconnector.data.local.CSVFileManager
import de.jobisoft.hitsterconnector.data.local.ConfigDataStore
import de.jobisoft.hitsterconnector.data.repository.CSVRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@HiltViewModel
class CardConfigViewModel @Inject constructor(
    private val csvFileManager: CSVFileManager,
    private val configDataStore: ConfigDataStore,
    private val csvRepository: CSVRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(CardConfigUiState())
    val uiState: StateFlow<CardConfigUiState> = _uiState.asStateFlow()
    
    init {
        loadCSVFiles()
    }
    
    private fun loadCSVFiles() {
        viewModelScope.launch {
            try {
                val csvUrls = configDataStore.getCsvUrls()
                val files = csvFileManager.getAllLocalFiles()
                val fileNames = files.map { it.name }.toList()
                _uiState.value = _uiState.value.copy(csvFiles = fileNames)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Error loading CSV files: ${e.message}")
            }
        }
    }
    
    fun updateDownloadUrl(url: String) {
        _uiState.value = _uiState.value.copy(downloadUrl = url, downloadError = null)
    }
    
    fun downloadCSV(url: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDownloading = true, downloadError = null)
            try {
                val result = csvFileManager.downloadAndSave(url)
                result.fold(
                    onSuccess = {
                        // Add to CSV URLs in ConfigDataStore
                        val currentUrls = configDataStore.getCsvUrls().toMutableSet()
                        currentUrls.add(url)
                        configDataStore.saveCSVUrls(currentUrls)
                        
                        _uiState.value = _uiState.value.copy(
                            isDownloading = false,
                            downloadUrl = "",
                            downloadError = null
                        )
                        loadCSVFiles()
                    },
                    onFailure = { exception ->
                        _uiState.value = _uiState.value.copy(
                            isDownloading = false,
                            downloadError = exception.message ?: "Download failed"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDownloading = false,
                    downloadError = e.message ?: "Download failed"
                )
            }
        }
    }
    
    fun importAllSongseekerPlaylists() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isBulkImporting = true,
                bulkImportProgressCurrent = 0,
                bulkImportProgressTotal = 0,
                bulkImportResultMessage = null,
                error = null
            )
            try {
                val filenames = csvFileManager.fetchPlaylistIndexFilenames().getOrElse { e ->
                    _uiState.value = _uiState.value.copy(
                        isBulkImporting = false,
                        bulkImportResultMessage = e.message ?: "Failed to load playlist index"
                    )
                    return@launch
                }
                if (filenames.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isBulkImporting = false,
                        bulkImportResultMessage = "No CSV files listed in index"
                    )
                    return@launch
                }
                val currentUrls = configDataStore.getCsvUrls().toMutableSet()
                val failures = mutableListOf<String>()
                filenames.forEachIndexed { index, name ->
                    _uiState.value = _uiState.value.copy(
                        bulkImportProgressCurrent = index + 1,
                        bulkImportProgressTotal = filenames.size
                    )
                    csvFileManager.downloadAndSave(name).fold(
                        onSuccess = { currentUrls.add(name) },
                        onFailure = { ex ->
                            failures.add("$name: ${ex.message ?: "unknown error"}")
                        }
                    )
                }
                configDataStore.saveCSVUrls(currentUrls)
                loadCSVFiles()
                val message = if (failures.isEmpty()) {
                    "Imported ${filenames.size} CSV file(s) from songseeker-hitster-playlists."
                } else {
                    buildString {
                        val ok = filenames.size - failures.size
                        append("Imported $ok/${filenames.size}. Failed:\n")
                        append(failures.joinToString("\n"))
                    }
                }
                _uiState.value = _uiState.value.copy(
                    isBulkImporting = false,
                    bulkImportProgressCurrent = 0,
                    bulkImportProgressTotal = 0,
                    bulkImportResultMessage = message
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isBulkImporting = false,
                    bulkImportResultMessage = e.message ?: "Import failed"
                )
            }
        }
    }

    fun clearBulkImportResult() {
        _uiState.value = _uiState.value.copy(bulkImportResultMessage = null)
    }

    fun addLocalCSV(context: Context, uri: Uri, fileName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAddingFile = true, error = null)
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val csvDir = File(context.filesDir, "csv_files").apply {
                            if (!exists()) mkdirs()
                        }
                        val file = File(csvDir, fileName)
                        FileOutputStream(file).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    } ?: throw Exception("Could not open file")
                }
                
                // Add to CSV URLs in ConfigDataStore
                val currentUrls = configDataStore.getCsvUrls().toMutableSet()
                currentUrls.add(fileName)
                configDataStore.saveCSVUrls(currentUrls)
                
                _uiState.value = _uiState.value.copy(isAddingFile = false, error = null)
                loadCSVFiles()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isAddingFile = false,
                    error = "Error adding file: ${e.message}"
                )
            }
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
    val downloadUrl: String = "",
    val isDownloading: Boolean = false,
    val downloadError: String? = null,
    val isAddingFile: Boolean = false,
    val isBulkImporting: Boolean = false,
    val bulkImportProgressCurrent: Int = 0,
    val bulkImportProgressTotal: Int = 0,
    val bulkImportResultMessage: String? = null,
    val error: String? = null,
    val csvFiles: List<String> = emptyList(),
)