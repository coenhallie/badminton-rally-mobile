package com.badmintontracker.android.labels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlOutlinedTextField
import com.badmintontracker.android.ui.components.SwipeToRemoveRow
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.model.LabelUsage
import com.badmintontracker.shared.repo.AnnotationLabelsRepositoryImpl
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelsScreen(vm: LabelsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var deleteTarget by remember { mutableStateOf<AnnotationLabel?>(null) }

    LaunchedEffect(state.errorMessage) {
        val err = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err)
        vm.errorShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "LABELS",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 14.sp),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = vm::startCreating) {
                        Icon(Icons.Default.Add, contentDescription = "New label")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().imePadding()) {
            val creatingNew = state.expanded == LabelEditTarget.New
            if (state.labels.isEmpty() && !creatingNew) {
                // A failed load and a genuinely empty account both leave the list empty,
                // and the snackbar above that reports a failure times out after a few
                // seconds - so the empty state itself must keep telling them apart, or a
                // failed load quietly reads as "you have no labels" once it's gone.
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.loadFailed) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Couldn't load your labels",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = vm::refresh) { Text("Retry") }
                        }
                    } else {
                        // With the in-list "add" row gone, an empty screen must teach
                        // the action itself rather than leave a first-time user staring
                        // at nothing but a small "+" in the corner.
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                "No labels yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ShuttlButton(
                                text = "Add label",
                                onClick = vm::startCreating,
                                compact = true,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    if (creatingNew) {
                        item(key = "new-label-draft") {
                            DraftLabelRow(
                                palette = vm.palette,
                                existingColorKeys = state.labels.map { it.colorKey },
                                onCreate = vm::create,
                            )
                        }
                    }
                    items(state.labels, key = { it.id }) { label ->
                        SwipeToRemoveRow(
                            label = "Delete",
                            onSwiped = { deleteTarget = label; false },
                        ) {
                            LabelRow(
                                label = label,
                                expanded = state.expanded == LabelEditTarget.Existing(label.id),
                                palette = vm.palette,
                                onToggle = { vm.expand(label.id) },
                                onRename = { vm.rename(label.id, it) },
                                onRecolor = { vm.recolor(label.id, it) },
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { label ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete label?") },
            text = {
                Text(
                    "\"${label.name}\" leaves the picker. Notes already tagged with it keep their badge.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(label.id); deleteTarget = null }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun LabelRow(
    label: AnnotationLabel,
    expanded: Boolean,
    palette: List<LabelColor>,
    onToggle: () -> Unit,
    onRename: (String) -> Unit,
    onRecolor: (LabelColor) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onToggle)
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dotColor = label.color?.let { Color(it.background.toInt()) }
                ?: MaterialTheme.colorScheme.surfaceVariant
            Spacer(
                Modifier
                    .size(12.dp)
                    .background(dotColor, CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                label.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        if (expanded) {
            var name by remember(label.id) { mutableStateOf(label.name) }
            // Guards against the spurious "unfocused" event Compose delivers on first
            // composition: without it, a freshly expanded row would commit (a no-op
            // here, since trimmed == label.name) before the user ever touches the field.
            var hadFocus by remember(label.id) { mutableStateOf(false) }

            // commit() deliberately does NOT clear hadFocus. onDone here does not clear
            // focus (ShuttlOutlinedTextField never calls defaultKeyboardAction), so after
            // Done the cursor is still in the field; clearing the flag would make the next
            // blur early-return and silently discard whatever was typed after Done.
            // Re-committing the same text is harmless: the guard below is the name itself,
            // and renaming a label to its current name is a no-op. DraftLabelRow has the
            // same requirement and solves it with CommitGuard, which keys on the committed
            // text rather than on focus, for exactly this reason.
            fun commit() {
                val trimmed = name.trim()
                if (trimmed != label.name) onRename(trimmed)
            }

            LabelEditorFields(
                name = name,
                onNameChange = { name = it },
                onDone = ::commit,
                onFocusChanged = { state -> if (state.isFocused) hadFocus = true else if (hadFocus) commit() },
                palette = palette,
                selectedColorKey = label.colorKey,
                onSelectColor = onRecolor,
            )
        }
    }
}

/**
 * The name field and swatch grid shared by an expanded [LabelRow] and by
 * [DraftLabelRow] - the same in-place editor either way. What differs between
 * the two callers is only the wiring around it: an existing row persists a
 * rename or a recolour immediately, since the label already exists; the
 * not-yet-created draft can only hold its typed name and chosen colour
 * locally until both are submitted together as one [LabelsViewModel.create]
 * call.
 */
@Composable
private fun LabelEditorFields(
    name: String,
    onNameChange: (String) -> Unit,
    onDone: () -> Unit,
    onFocusChanged: (FocusState) -> Unit,
    palette: List<LabelColor>,
    selectedColorKey: String?,
    onSelectColor: (LabelColor) -> Unit,
    focusRequester: FocusRequester? = null,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShuttlOutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = "Name",
            onDone = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .onFocusChanged(onFocusChanged),
        )
        SwatchGrid(palette = palette, selectedKey = selectedColorKey, onSelect = onSelectColor)
    }
}

/**
 * A deliberate 5x2 grid rather than a wrapping FlowRow: with exactly ten swatches, a
 * flow layout stripes 9 + 1 at common phone widths, stranding one circle alone on its
 * own row. Fixed columns keep both rows full regardless of screen width.
 */
@Composable
private fun SwatchGrid(palette: List<LabelColor>, selectedKey: String?, onSelect: (LabelColor) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        palette.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { swatch ->
                    ColorSwatch(
                        color = swatch,
                        selected = swatch.key == selectedKey,
                        onClick = { onSelect(swatch) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorSwatch(color: LabelColor, selected: Boolean, onClick: () -> Unit) {
    val colorName = color.key.replaceFirstChar { it.uppercase() }
    // The touch target is enlarged to the 48.dp minimum without growing the swatch
    // itself: the visible 28.dp circle sits centered inside a 48.dp clickable Box.
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                onClickLabel = colorName,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = colorName },
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            modifier = Modifier
                .size(28.dp)
                .background(Color(color.background.toInt()), CircleShape)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

/**
 * Guards [DraftLabelRow]'s commit against dispatching the same not-yet-created
 * label twice, and - the case that matters more - against silently swallowing
 * a legitimate retry.
 *
 * `ShuttlOutlinedTextField`'s `onDone` never calls `defaultKeyboardAction`, so
 * pressing Done does not clear focus: the field's own commit fires from Done,
 * and then fires again from whatever focus loss eventually follows (a swatch
 * tap, the keyboard dismissing, tapping away). A guard keyed only on focus -
 * this row's earlier `hadFocus` flag - cannot tell those two calls apart from
 * "the user retyped after a rejected create", because focus is false in both
 * cases. Keying on the committed text instead does: the second call from the
 * same Done press repeats the text that was just armed and is rejected here;
 * a genuine retry after [failed] rolls the guard back is not.
 */
internal class CommitGuard {
    var lastCommitted: String = ""
        private set

    /** True when this commit should actually dispatch, arming the guard if so. */
    fun begin(trimmed: String): Boolean {
        if (trimmed.isEmpty() || trimmed == lastCommitted) return false
        lastCommitted = trimmed
        return true
    }

    /**
     * Restores the guard to what it was before the [begin] call that armed it,
     * so that the same text is eligible to commit again. A rejected create
     * (a duplicate name, most commonly) leaves the draft row open with its
     * typed name unchanged so the user can fix it in place - without this
     * rollback, retyping that exact name after resolving the conflict would
     * read as an indistinguishable repeat of the commit that already failed,
     * and be dropped with no error and nothing created.
     */
    fun failed(previous: String) {
        lastCommitted = previous
    }
}

/**
 * The not-yet-created label row, opened by the toolbar "+" (or the empty
 * state's action button) and shown at the top of the list while
 * [LabelsUiState.expanded] is [LabelEditTarget.New]. The colour picker lives
 * here, at creation time, not only in the edit row reached afterward - so
 * choosing a colour for a new label is one step, not "create, tap, recolour".
 * [existingColorKeys] feeds the same next-unused-swatch logic the repository
 * already uses for name-only creation elsewhere (the Add-note sheet's inline
 * picker, Task 10), so an unchanged default still behaves exactly as before.
 *
 * Name and colour are held locally, not persisted, until the name field
 * commits: there is no id yet to rename or recolour against, so unlike an
 * expanded [LabelRow] this row cannot write through on every swatch tap.
 * Once [onCreate] succeeds, [LabelsViewModel.create] moves the expansion
 * target to the new label's own id, and this composable stops being shown -
 * the *same* [LabelEditorFields] then keeps rendering, bound to the real row.
 */
@Composable
private fun DraftLabelRow(
    palette: List<LabelColor>,
    existingColorKeys: List<String>,
    onCreate: suspend (String, LabelColor, LabelUsage) -> Boolean,
) {
    var name by remember { mutableStateOf("") }
    // Keyed on existingColorKeys: the row can open before the label list has
    // finished its first load, in which case an unkeyed remember would compute
    // the default swatch from a stale (often empty) list and never reconsider
    // it. Keying recomputes the default whenever the list's colour keys change,
    // which is what we want while the list is still arriving.
    var selectedColor by remember(existingColorKeys) {
        mutableStateOf(AnnotationLabelsRepositoryImpl.nextUnusedColor(existingColorKeys))
    }
    val commitGuard = remember { CommitGuard() }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    fun commit() {
        val trimmed = name.trim()
        val previous = commitGuard.lastCommitted
        // The first onFocusChanged event fires with isFocused == false before
        // the user has touched anything - see the matching comment in
        // LabelRow. Here that spurious call carries an empty trimmed name, so
        // CommitGuard.begin's own emptiness check rejects it without needing
        // a separate flag for "has this row ever been focused".
        if (!commitGuard.begin(trimmed)) return
        scope.launch {
            // Task 6 replaces BOTH with the owner's choice.
            val succeeded = onCreate(trimmed, selectedColor, LabelUsage.BOTH)
            // A failed create leaves this row mounted (LabelsViewModel.create
            // only changes the expansion target on success) with its typed
            // name and chosen colour untouched, so rolling the guard back
            // here is what lets the user's corrected retry actually fire
            // instead of looking like a repeat of the commit that failed.
            if (!succeeded) commitGuard.failed(previous)
        }
    }

    // Auto-focuses the field the moment the row opens, so the keyboard is up
    // without the user having to tap the field first.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LabelEditorFields(
        name = name,
        onNameChange = { name = it },
        onDone = ::commit,
        onFocusChanged = { state -> if (!state.isFocused) commit() },
        focusRequester = focusRequester,
        palette = palette,
        selectedColorKey = selectedColor.key,
        onSelectColor = { selectedColor = it },
    )
}
