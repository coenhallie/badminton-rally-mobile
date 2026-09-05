package com.badmintontracker.android.localvideo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.components.ShuttlOutlinedTextField
import com.badmintontracker.shared.localvideo.MAX_MATCH_DESCRIPTION_LENGTH
import com.badmintontracker.shared.localvideo.MAX_MATCH_TITLE_LENGTH
import kotlinx.coroutines.launch

/**
 * Name and describe a match before it is analyzed.
 *
 * Both values ride along on the videos INSERT and the database grants no UPDATE
 * on either column, so this sheet is only reachable while the entry is still
 * LOCAL - see canEditLocalVideoDetails.
 *
 * [autoOpened] only changes the dismiss label: straight after an import or a
 * recording the sheet is something to get past ("Skip"), while from the row menu
 * it is a deliberate edit ("Cancel"). Dismissing never discards the video, which
 * is already saved by the time this opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchDetailsSheet(
    initialTitle: String?,
    initialDescription: String?,
    autoOpened: Boolean,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(initialTitle.orEmpty()) }
    var description by remember { mutableStateOf(initialDescription.orEmpty()) }

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
            Text("Match details", style = MaterialTheme.typography.titleLarge)

            ShuttlOutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = "Match name",
                maxLength = MAX_MATCH_TITLE_LENGTH,
            )

            ShuttlOutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = "Description",
                singleLine = false,
                minLines = 3,
                maxLength = MAX_MATCH_DESCRIPTION_LENGTH,
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { hideThen(onDismiss) }) {
                    Text(if (autoOpened) "Skip" else "Cancel")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { hideThen { onSave(title, description) } }) { Text("Save") }
            }
        }
    }
}
