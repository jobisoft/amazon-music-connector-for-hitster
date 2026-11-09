package de.jobisoft.hitsterconnector.ui.viewmodel

import androidx.lifecycle.ViewModel
import de.jobisoft.hitsterconnector.data.local.CSVFileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CSVPreviewViewModel @Inject constructor(
    val csvFileManager: CSVFileManager
) : ViewModel()
