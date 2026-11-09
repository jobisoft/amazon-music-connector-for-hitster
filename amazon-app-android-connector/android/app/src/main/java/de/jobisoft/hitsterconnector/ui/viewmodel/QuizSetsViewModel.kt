package de.jobisoft.hitsterconnector.ui.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jobisoft.hitsterconnector.data.local.CSVFileManager
import de.jobisoft.hitsterconnector.data.local.CardSetNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class QuizSet(val file: String, val name: String)

data class QuizSetsState(
    val sets: List<QuizSet> = emptyList(),
    val selected: Set<String> = emptySet()
) {
    val allSelected: Boolean get() = sets.isNotEmpty() && selected.size == sets.size
}

/** The list of local card sets with display names, and which of them are ticked. */
@HiltViewModel
class QuizSetsViewModel @Inject constructor(
    csvFileManager: CSVFileManager,
    cardSetNames: CardSetNames
) : ViewModel() {

    private val _state = MutableStateFlow(
        QuizSetsState(
            sets = csvFileManager.getAllLocalFiles()
                .map { QuizSet(it.name, cardSetNames.displayName(it.name)) }
                .sortedBy { it.name.lowercase() }
        )
    )
    val state: StateFlow<QuizSetsState> = _state.asStateFlow()

    fun toggle(file: String) {
        val current = _state.value
        _state.value = current.copy(
            selected = if (file in current.selected) current.selected - file else current.selected + file
        )
    }

    fun toggleAll() {
        val current = _state.value
        _state.value = current.copy(selected = if (current.allSelected) emptySet() else current.sets.map { it.file }.toSet())
    }
}
