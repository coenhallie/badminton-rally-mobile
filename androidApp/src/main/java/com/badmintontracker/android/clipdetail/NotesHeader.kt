package com.badmintontracker.android.clipdetail

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.theme.ShuttlTheme

/**
 * The line that heads a player's notes: the caption on the left, and, for a
 * viewer allowed to write, the accent "Add note" pill on the right.
 *
 * This replaced a floating action button. Material's FAB took its colour from
 * a tertiary role this palette never sets, so it came out in Material's own
 * purple, the one purple thing in a green and near-black app; and it floated
 * over the notes it added to. A pill in the row that heads those notes says
 * what it does, in the accent, where the notes are.
 */
@Composable
fun NotesHeader(caption: String, onAdd: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            caption,
            style = MaterialTheme.typography.bodySmall,
            color = ShuttlTheme.extended.textTertiary,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        if (onAdd != null) {
            ShuttlButton(
                text = "Add note",
                onClick = onAdd,
                variant = ShuttlButtonVariant.Primary,
                compact = true,
                leadingIcon = Icons.Default.Add,
            )
        }
    }
}
