package com.badmintontracker.android.labels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.repo.AnnotationLabelsRepository
import com.badmintontracker.shared.repo.userFacingMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LabelsUiState(
    val labels: List<AnnotationLabel> = emptyList(),
    val expandedId: String? = null,
    val errorMessage: String? = null,
)

class LabelsViewModel(private val labels: AnnotationLabelsRepository) : ViewModel() {

    private val _state = MutableStateFlow(LabelsUiState())
    val state: StateFlow<LabelsUiState> = _state.asStateFlow()

    val palette: List<LabelColor> = LabelColor.PALETTE

    init {
        viewModelScope.launch {
            labels.labels.collect { rows -> _state.value = _state.value.copy(labels = rows) }
        }
        viewModelScope.launch { labels.refresh() }
    }

    /** Passing the id already expanded collapses it, so a row is its own toggle. */
    fun expand(id: String?) {
        _state.value = _state.value.copy(expandedId = if (_state.value.expandedId == id) null else id)
    }

    fun create(name: String) = run { labels.create(name, null) }
    fun rename(id: String, name: String) = run { labels.rename(id, name) }
    fun recolor(id: String, color: LabelColor) = run { labels.recolor(id, color) }
    fun delete(id: String) = run { labels.delete(id) }

    fun errorShown() { _state.value = _state.value.copy(errorMessage = null) }

    /**
     * Every failure goes through userFacingMessage, the same filter the iOS interop
     * wrappers use, so a network stack trace or a multi-line Postgres error never
     * reaches the snackbar. Our own validation messages are single-line and pass
     * through unchanged.
     */
    private fun run(op: suspend () -> Result<*>) {
        viewModelScope.launch {
            op().onFailure { e ->
                _state.value = _state.value.copy(
                    errorMessage = e.userFacingMessage("Couldn't save the label"),
                )
            }
        }
    }
}
