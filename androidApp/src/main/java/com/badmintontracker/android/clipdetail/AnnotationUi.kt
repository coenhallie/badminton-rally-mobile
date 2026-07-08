package com.badmintontracker.android.clipdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.badmintontracker.shared.model.AnnotationKind
import kotlinx.coroutines.launch

internal fun formatTimestamp(seconds: Float): String {
    val total = kotlin.math.round(seconds).toInt().coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

@Composable
internal fun AnnotationRow(
    timestampSeconds: Float,
    body: String,
    kind: AnnotationKind?,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatTimestamp(timestampSeconds),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        kind?.let { k ->
            val s = k.style()
            Surface(shape = RoundedCornerShape(50), color = s.container) {
                Text(
                    s.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = s.onContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        if (body.isNotBlank()) {
            Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete annotation")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddAnnotationSheet(
    onDismiss: () -> Unit,
    onConfirm: (body: String, kind: AnnotationKind?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var body by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<AnnotationKind?>(null) }
    val canAdd = kind != null || body.isNotBlank()

    fun hideThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) action()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Add annotation", style = MaterialTheme.typography.titleLarge)

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KindChip("Good shot",      AnnotationKind.GOOD_SHOT,      kind) { kind = if (kind == it) null else it }
                KindChip("Forced error",   AnnotationKind.FORCED_ERROR,   kind) { kind = if (kind == it) null else it }
                KindChip("Unforced error", AnnotationKind.UNFORCED_ERROR, kind) { kind = if (kind == it) null else it }
            }

            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                placeholder = { Text("Note (optional)") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { hideThen { onDismiss() } }) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(enabled = canAdd, onClick = { hideThen { onConfirm(body, kind) } }) { Text("Add") }
            }
        }
    }
}

@Composable
private fun KindChip(
    label: String,
    target: AnnotationKind,
    selected: AnnotationKind?,
    onClick: (AnnotationKind) -> Unit,
) {
    val s = target.style()
    FilterChip(
        selected = selected == target,
        onClick = { onClick(target) },
        label = { Text(label) },
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = s.container,
            selectedLabelColor = s.onContainer,
        ),
    )
}
