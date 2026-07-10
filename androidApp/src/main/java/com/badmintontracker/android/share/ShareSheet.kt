package com.badmintontracker.android.share

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text("Share match", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.email,
                onValueChange = vm::onEmailChange,
                label = { Text("Email") },
                singleLine = true,
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
            if (state.recipients.isEmpty()) {
                Text(
                    "No one yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.recipients.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(r.email ?: "Unknown user", modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.onUnshare(r.sharedWithUserId) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove access")
                        }
                    }
                }
            }
        }
    }
}
