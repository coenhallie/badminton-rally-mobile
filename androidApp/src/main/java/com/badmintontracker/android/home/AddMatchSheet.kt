package com.badmintontracker.android.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.home.HomeCopy

/**
 * The three ways a match starts, behind the "Add match" button: each with
 * its glyph and the one line that tells it from the other two.
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
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Pinned to the token that matches iOS's Shuttl.bgSecondary rather
        // than left to M3's surfaceContainerLow. ShuttlColors.kt now sets that
        // role to the same value, but a sheet's colour is a design decision
        // worth stating at the sheet, not an accident of a role's default.
        // (The scrim is left
        // at its own default: BottomSheetDefaults.ScrimColor resolves M3's
        // neutral Scrim token, which is black in both schemes already, so
        // there is no platform tint to fix there.)
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Row_(ShuttlIcons.CirclePlus, HomeCopy.ADD_NEW_MATCH, HomeCopy.ADD_NEW_MATCH_DETAIL, onNewMatch)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row_(ShuttlIcons.Video, HomeCopy.ADD_RECORD_VIDEO, HomeCopy.ADD_RECORD_VIDEO_DETAIL, onRecord)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row_(ShuttlIcons.Download, HomeCopy.ADD_IMPORT_VIDEO, HomeCopy.ADD_IMPORT_VIDEO_DETAIL, onImport)
        }
    }
}

@Composable
private fun Row_(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = ShuttlTheme.extended.accentDark,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
