package com.badmintontracker.android.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The three ways a match starts, behind Home's one "Add new match" button.
 *
 * Mirrors iosApp's AddMatchSheet.swift. The actions are unchanged from the
 * three item menu this replaces; only the way in is different.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMatchSheet(
    onNewMatch: () -> Unit,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Unset, this resolves M3's own surfaceContainerLow default - a
        // platform colour ShuttlColors.kt never sets. Pinned to the token
        // that matches iOS's Shuttl.bgSecondary instead. (The scrim is left
        // at its own default: BottomSheetDefaults.ScrimColor resolves M3's
        // neutral Scrim token, which is black in both schemes already, so
        // there is no platform tint to fix there.)
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row_("New match", onNewMatch)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row_("Record video", onRecord)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row_("Import video", onImport)
        }
    }
}

@Composable
private fun Row_(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
