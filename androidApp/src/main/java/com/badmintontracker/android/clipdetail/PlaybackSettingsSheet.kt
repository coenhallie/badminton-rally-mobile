package com.badmintontracker.android.clipdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.components.FieldLabel
import com.badmintontracker.android.ui.components.ShuttlChip
import com.badmintontracker.shared.prefs.PlaybackOptions

/**
 * The speed and the skip interval, app-wide, as two rows of pill chips.
 *
 * Reached by the speed chip on the video card and by holding a skip pill on
 * the transport. The skip buttons are ours rather than Media3's because
 * ExoPlayer bakes its seek increments in at Builder time: a preference change
 * could only reach them by rebuilding the player and losing the playhead.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlaybackSettingsSheet(
    skipSeconds: Int,
    speed: Float,
    onSkipSeconds: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Playback", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 4.dp))

            FieldLabel("Speed")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaybackOptions.speedOptions.forEach { option ->
                    ShuttlChip(text = PlaybackOptions.formatSpeed(option), selected = option == speed, onClick = { onSpeed(option) })
                }
            }

            FieldLabel("Skip interval", modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaybackOptions.skipSecondsOptions.forEach { option ->
                    ShuttlChip(text = "${option}s", selected = option == skipSeconds, onClick = { onSkipSeconds(option) })
                }
            }
        }
    }
}
