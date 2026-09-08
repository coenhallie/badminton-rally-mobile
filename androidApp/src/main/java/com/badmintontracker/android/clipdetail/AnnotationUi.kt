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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.components.ShuttlChip
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor
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
    labelName: String?,
    labelColor: String?,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatTimestamp(timestampSeconds),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        labelName?.takeIf { it.isNotBlank() }?.let { name ->
            LabelBadge(name = name, colorKey = labelColor)
            Spacer(Modifier.width(8.dp))
        }
        if (body.isNotBlank()) {
            Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete note")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddAnnotationSheet(
    labels: List<AnnotationLabel>,
    onDismiss: () -> Unit,
    onConfirm: (body: String, label: AnnotationLabel?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var body by remember { mutableStateOf("") }
    var label by remember { mutableStateOf<AnnotationLabel?>(null) }
    val canAdd = label != null || body.isNotBlank()

    fun hideThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Unset, this resolves M3's surfaceContainerLow, which ShuttlColors.kt
        // never sets. See AddMatchSheet for the full reasoning.
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Add note", style = MaterialTheme.typography.headlineMedium)

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                labels.forEach { candidate ->
                    LabelChip(
                        label = candidate,
                        selected = label?.id == candidate.id,
                        onClick = { label = if (label?.id == candidate.id) null else candidate },
                    )
                }
            }

            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                placeholder = { Text("Note (optional)") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ShuttlButton(text = "Cancel", onClick = { hideThen { onDismiss() } }, variant = ShuttlButtonVariant.Secondary)
                Spacer(Modifier.width(8.dp))
                ShuttlButton(text = "Add", onClick = { hideThen { onConfirm(body, label) } }, enabled = canAdd)
            }
        }
    }
}

/** A label to pick, chosen in its own swatch so the chip and the badge it becomes share a colour. */
@Composable
private fun LabelChip(label: AnnotationLabel, selected: Boolean, onClick: () -> Unit) {
    val swatch = LabelColor.from(label.colorKey)
    ShuttlChip(
        text = label.name,
        selected = selected,
        onClick = onClick,
        selectedContainer = swatch?.let { Color(it.background.toInt()) },
        selectedContent = swatch?.let { Color(it.foreground.toInt()) },
    )
}
