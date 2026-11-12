package de.jobisoft.amster.ui.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jobisoft.amster.data.local.CSVFileManager
import de.jobisoft.amster.data.local.CardOverrides
import de.jobisoft.amster.data.local.CardSetNames
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
    cardSetNames: CardSetNames,
    overrides: CardOverrides
) : ViewModel() {

    private val _state = MutableStateFlow(
        QuizSetsState(
            // the sets with a file, and the sets that only exist as corrections (cards read by the reader)
            sets = (csvFileManager.getAllLocalFiles().map { it.name } + overrides.sets())
                .distinct()
                .map { QuizSet(it, cardSetNames.displayName(it)) }
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
