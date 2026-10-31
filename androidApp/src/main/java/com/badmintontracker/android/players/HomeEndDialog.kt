package com.badmintontracker.android.players

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
 * Drawn as an alert dialog (its shape, padding and type) holding the app's
 * buttons: Near and Far, the two equal answers, side by side, then Skip, the
 * way past, under them in the secondary style. Skip leaves the end unsaid, for
 * the details editor to set later. A tap outside is Skip, so the coach is
 * never left without the court marking that follows any answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeEndDialog(
    title: String,
    onNear: () -> Unit,
    onFar: () -> Unit,
    onSkip: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onSkip) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = ShuttlTheme.extended.bgDialog,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            // AlertDialog's own 24dp inset, and its title-to-text gap.
            Column(Modifier.padding(24.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    PlayersCopy.ATTACH_SUBTITLE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShuttlButton(text = PlayersCopy.NEAR, onClick = onNear, modifier = Modifier.weight(1f))
                    ShuttlButton(text = PlayersCopy.FAR, onClick = onFar, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                ShuttlButton(
                    text = PlayersCopy.ATTACH_SKIP,
                    onClick = onSkip,
                    variant = ShuttlButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
