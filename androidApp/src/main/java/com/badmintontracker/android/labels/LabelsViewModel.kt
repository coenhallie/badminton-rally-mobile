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
    /**
     * True when the most recent [refresh] failed. Lets an empty list distinguish
     * "the account genuinely has no labels" from "the load failed" once the
     * snackbar that reported the failure has already timed out.
     */
    val loadFailed: Boolean = false,
)

class LabelsViewModel(private val labels: AnnotationLabelsRepository) : ViewModel() {

    private val _state = MutableStateFlow(LabelsUiState())
    val state: StateFlow<LabelsUiState> = _state.asStateFlow()

    val palette: List<LabelColor> = LabelColor.PALETTE

    init {
        viewModelScope.launch {
            labels.labels.collect { rows -> _state.value = _state.value.copy(labels = rows) }
        }
        refresh()
    }

    /**
     * Previously a bare viewModelScope.launch { labels.refresh() } that discarded its
     * Result: a failed load (expired session, no network, missing table) then rendered
     * identically to "you have no labels" - an empty list either way, with no way to
     * tell the two apart. This surfaces the failure through the snackbar every other
     * failure uses, and also records it in state so the empty-state copy can tell the
     * two situations apart once the snackbar has timed out. Public so the empty state's
     * retry action can call it again.
     */
    fun refresh() {
        viewModelScope.launch {
            labels.refresh()
                .onSuccess { _state.value = _state.value.copy(loadFailed = false) }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        loadFailed = true,
                        errorMessage = e.userFacingMessage("Couldn't load your labels"),
                    )
                }
        }
    }

    /** Passing the id already expanded collapses it, so a row is its own toggle. */
    fun expand(id: String?) {
        _state.value = _state.value.copy(expandedId = if (_state.value.expandedId == id) null else id)
    }

    fun create(name: String, color: LabelColor) = run { labels.create(name, color) }
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
