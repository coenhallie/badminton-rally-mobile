package com.badmintontracker.android.labels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.FlowRow
import com.badmintontracker.android.ui.components.ShuttlOutlinedTextField
import com.badmintontracker.android.ui.components.SwipeToRemoveRow
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor

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
            NewLabelRow(onCreate = vm::create)
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

            fun commit() {
                val trimmed = name.trim()
                if (trimmed != label.name) onRename(trimmed)
                hadFocus = false
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    palette.forEach { swatch ->
                        ColorSwatch(
                            color = swatch,
                            selected = label.colorKey == swatch.key,
                            onClick = { onRecolor(swatch) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSwatch(color: LabelColor, selected: Boolean, onClick: () -> Unit) {
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
            )
            .clickable(onClick = onClick),
    )
}

@Composable
private fun NewLabelRow(onCreate: (String) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    // See the matching guard in LabelRow: the first onFocusChanged event fires
    // with isFocused == false before the user has touched anything, which would
    // otherwise collapse this row back to the button the instant it opens.
    var hadFocus by remember { mutableStateOf(false) }

    fun commit() {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) onCreate(trimmed)
        creating = false
        name = ""
        hadFocus = false
    }

    if (creating) {
        ShuttlOutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = "Name",
            onDone = { commit() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .onFocusChanged { state ->
                    if (state.isFocused) hadFocus = true else if (hadFocus) commit()
                },
        )
    } else {
        TextButton(
            onClick = { creating = true },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("New label")
        }
    }
}
