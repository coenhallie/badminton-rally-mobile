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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.ui.components.ShuttlOutlinedTextField
import com.badmintontracker.android.ui.components.SwipeToRemoveRow
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.repo.AnnotationLabelsRepositoryImpl

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
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (state.labels.isEmpty()) {
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
                        Text(
                            "No labels yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(state.labels, key = { it.id }) { label ->
                        SwipeToRemoveRow(
                            label = "Delete",
                            onSwiped = { deleteTarget = label; false },
                        ) {
                            LabelRow(
                                label = label,
                                expanded = state.expandedId == label.id,
                                palette = vm.palette,
                                onToggle = { vm.expand(label.id) },
                                onRename = { vm.rename(label.id, it) },
                                onRecolor = { vm.recolor(label.id, it) },
                            )
                        }
                    }
                }
            }
            NewLabelRow(
                palette = vm.palette,
                existingColorKeys = state.labels.map { it.colorKey },
                onCreate = vm::create,
            )
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

            // No reset here, unlike NewLabelRow's commit(): this row does not unmount on
            // commit, so it never had NewLabelRow's double-commit-on-Done problem to solve.
            // Resetting hadFocus after Done would leave the cursor sitting in the field
            // with hadFocus == false - the next blur would then early-return and silently
            // discard whatever the user kept typing after Done.
            fun commit() {
                val trimmed = name.trim()
                if (trimmed != label.name) onRename(trimmed)
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ShuttlOutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Name",
                    onDone = { commit() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { state ->
                            if (state.isFocused) hadFocus = true else if (hadFocus) commit()
                        },
                )
                SwatchGrid(palette = palette, selectedKey = label.colorKey, onSelect = onRecolor)
            }
        }
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
 * The colour picker lives here, at creation time, not only in the edit row reached
 * afterward - so choosing a colour for a new label is one step, not "create, tap,
 * recolour". [existingColorKeys] feeds the same next-unused-swatch logic the
 * repository already uses for name-only creation elsewhere (the Add-note sheet's
 * inline picker, Task 10), so an unchanged default still behaves exactly as before.
 */
@Composable
private fun NewLabelRow(
    palette: List<LabelColor>,
    existingColorKeys: List<String>,
    onCreate: (String, LabelColor) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }

    if (creating) {
        var name by remember { mutableStateOf("") }
        // Keyed on existingColorKeys: the row can open before the label list has
        // finished its first load, in which case an unkeyed remember would compute
        // the default swatch from a stale (often empty) list and never reconsider
        // it. Keying recomputes the default whenever the list's colour keys change,
        // which is what we want while the list is still arriving.
        var selectedColor by remember(existingColorKeys) {
            mutableStateOf(AnnotationLabelsRepositoryImpl.nextUnusedColor(existingColorKeys))
        }
        // See the matching guard in LabelRow: the first onFocusChanged event fires
        // with isFocused == false before the user has touched anything, which would
        // otherwise collapse this row back to the button the instant it opens.
        var hadFocus by remember { mutableStateOf(false) }
        val focusRequester = remember { FocusRequester() }

        fun commit() {
            // Reset before dispatching, not after: the Done action's own focus-loss
            // event can still reach this closure once (the row hasn't finished
            // unmounting yet even though `creating` just flipped false), and without
            // this guard that second call re-submits the same name - which the
            // server correctly rejects as a duplicate of the row just created.
            if (!hadFocus) return
            hadFocus = false
            val trimmed = name.trim()
            if (trimmed.isNotEmpty()) onCreate(trimmed, selectedColor)
            creating = false
        }

        // Auto-focuses the field the moment the row opens. Without this, the row opens
        // with nothing focused: tapping a swatch without ever touching the text field
        // leaves hadFocus false forever, and commit() early-returns on every subsequent
        // tap-away, trapping the row open with no way to close or commit it.
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShuttlOutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = "Name",
                onDone = { commit() },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        if (state.isFocused) hadFocus = true else if (hadFocus) commit()
                    },
            )
            SwatchGrid(
                palette = palette,
                selectedKey = selectedColor.key,
                onSelect = { selectedColor = it },
            )
        }
    } else {
        TextButton(
            onClick = { creating = true },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("New label")
        }
    }
}
