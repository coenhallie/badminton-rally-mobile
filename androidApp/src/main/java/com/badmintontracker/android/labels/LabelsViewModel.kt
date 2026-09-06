package com.badmintontracker.android.labels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.model.LabelUsage
import com.badmintontracker.shared.repo.AnnotationLabelsRepository
import com.badmintontracker.shared.repo.userFacingMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one piece of expansion state for the whole screen: which row currently
 * shows its in-place editor. [New] is the not-yet-created draft row opened by
 * the toolbar "+"; [Existing] is a real label being renamed or recoloured.
 * Both live in the same field, so it is structurally impossible for two
 * editors to be open at once.
 */
sealed interface LabelEditTarget {
    data class Existing(val id: String) : LabelEditTarget
    data object New : LabelEditTarget
}

data class LabelsUiState(
    val labels: List<AnnotationLabel> = emptyList(),
    val expanded: LabelEditTarget? = null,
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

    /** Tapping the already-expanded row collapses it, so a row is its own toggle. */
    fun expand(id: String) {
        val target = LabelEditTarget.Existing(id)
        _state.value = _state.value.copy(expanded = if (_state.value.expanded == target) null else target)
    }

    /**
     * Opens (or, tapped again, closes) the draft row the toolbar "+" and the
     * empty-state action button both call. Nothing is created yet - [create]
     * below does that once the draft's name is committed - so backing out of
     * the draft without typing anything never touches the server.
     */
    fun startCreating() {
        val target = LabelEditTarget.New
        _state.value = _state.value.copy(expanded = if (_state.value.expanded == target) null else target)
    }

    /**
     * Unlike [rename]/[recolor]/[delete], a failed create leaves the draft row
     * open (its typed name and chosen colour survive) rather than collapsing
     * it - a rejected duplicate name is recoverable in place instead of
     * forcing the user to reopen the row and retype. A successful create
     * hands the new row's own id to [LabelEditTarget.Existing], so the same
     * expanded editor keeps showing, now bound to the real, persisted label.
     *
     * Suspends and reports whether the create succeeded, the same way
     * [rename] reports through its own `Result`, so [DraftLabelRow]'s commit
     * guard can tell a rejected create from a successful one and roll itself
     * back on failure - otherwise a corrected retry of the same name would
     * look, to a guard keyed only on "did the text change", identical to the
     * commit that already failed, and be silently dropped.
     *
     * The network call itself still runs under [viewModelScope], not the
     * caller's - launched with [kotlinx.coroutines.async] and only awaited
     * here. [DraftLabelRow] launches this from a `rememberCoroutineScope()`
     * tied to its own composition, which is cancelled the instant the row
     * unmounts (tapping "+" again, expanding another row, backing out). If
     * that awaiting call were the one actually running the insert, tapping
     * away right after Done would cancel a request that may already have
     * reached the server - creating the label there while the app's local
     * state and cache never learn about it until the next refresh. Running it
     * on [viewModelScope] means it survives that unmount; only the await -
     * and with it, the commit guard's rollback - is abandoned, which is
     * harmless since the row that would have shown the rollback is gone.
     */
    suspend fun create(name: String, color: LabelColor, usage: LabelUsage): Boolean =
        viewModelScope.async {
            labels.create(name, color, usage)
                .onSuccess { created ->
                    _state.value = _state.value.copy(expanded = LabelEditTarget.Existing(created.id))
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        errorMessage = e.userFacingMessage("Couldn't save the label"),
                    )
                }
                .isSuccess
        }.await()

    fun rename(id: String, name: String) = run { labels.rename(id, name) }
    fun recolor(id: String, color: LabelColor) = run { labels.recolor(id, color) }
    fun setUsage(id: String, usage: LabelUsage) = run { labels.setUsage(id, usage) }
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
