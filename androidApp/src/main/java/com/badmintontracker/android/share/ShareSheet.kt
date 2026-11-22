package com.badmintontracker.android.share

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import com.badmintontracker.shared.model.MatchShare
import com.badmintontracker.shared.repo.SharesRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    videoId: String,
    sharesRepository: SharesRepository,
    onDismiss: () -> Unit,
) {
    val vm: ShareSheetViewModel = viewModel(
        key = "share-$videoId",
        factory = viewModelFactory {
            initializer { ShareSheetViewModel(videoId, sharesRepository) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var revokeTarget by remember { mutableStateOf<MatchShare?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Pinned rather than left to surfaceContainerLow (the same bgSecondary
        // value today): see AddMatchSheet for the full reasoning.
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Text("Share match", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.email,
                onValueChange = vm::onEmailChange,
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (!state.isBusy && state.email.isNotBlank()) vm.onShareClicked()
                    },
                ),
                isError = state.error != null,
                supportingText = { state.error?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = vm::onShareClicked,
                enabled = !state.isBusy && state.email.isNotBlank(),
                modifier = Modifier.align(Alignment.End),
            ) { Text("Share") }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Text(
                "People with access",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            when {
                state.isLoading -> {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                state.loadError != null && state.recipients.isEmpty() -> {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            state.loadError!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = vm::refresh) { Text("Retry") }
                    }
                }
                state.recipients.isEmpty() -> {
                    Text(
                        "No one yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    state.recipients.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                r.email ?: "Unknown user",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { revokeTarget = r },
                                enabled = !state.isBusy,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove access")
                            }
                        }
                    }
                }
            }
        }
    }

    revokeTarget?.let { target ->
        val who = target.email ?: "this person"
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Remove access?") },
            text = { Text("$who will no longer be able to view this match.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val userId = target.sharedWithUserId
                        revokeTarget = null
                        vm.onUnshare(userId)
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { revokeTarget = null }) { Text("Cancel") }
            },
        )
    }
}
