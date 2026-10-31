package com.badmintontracker.android.players

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.players.PlayersCopy

/**
 * "Which end was {home} on?", asked once when a video joins a scored match
 * that has a profile on either side, before court marking (section 4.4a of
 * the player profiles design). [title] is the shared homeEndTitle.
 *
 * Near and Far are the two equal answers, side by side; Skip leaves the end
 * unsaid, for the details editor to set later. A tap outside is Skip, so the
 * coach is never left without the court marking that follows any answer.
 */
@Composable
fun HomeEndDialog(
    title: String,
    onNear: () -> Unit,
    onFar: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onSkip,
        containerColor = ShuttlTheme.extended.bgDialog,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    PlayersCopy.ATTACH_SUBTITLE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShuttlButton(
                        text = PlayersCopy.NEAR,
                        onClick = onNear,
                        variant = ShuttlButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    ShuttlButton(
                        text = PlayersCopy.FAR,
                        onClick = onFar,
                        variant = ShuttlButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onSkip) { Text(PlayersCopy.ATTACH_SKIP) } },
    )
}
