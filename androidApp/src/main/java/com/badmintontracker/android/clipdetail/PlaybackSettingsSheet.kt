package com.badmintontracker.android.clipdetail

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.components.FieldLabel
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.prefs.PlaybackOptions

/**
 * The speed and the skip interval, app-wide, as two rows of pill chips.
 *
 * Reached by the gear on the video card, by the speed readout at the end of the
 * transport row, and by holding a skip pill. The skip buttons are ours rather than Media3's because
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
        // Pinned rather than left to surfaceContainerLow (the same bgSecondary
        // value today): see AddMatchSheet for the full reasoning.
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Playback", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 4.dp))

            FieldLabel("Speed")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                PlaybackOptions.speedOptions.forEach { option ->
                    SettingsOptionChip(
                        text = PlaybackOptions.formatSpeed(option),
                        selected = option == speed,
                        onClick = { onSpeed(option) },
                    )
                }
            }

            FieldLabel("Skip interval", modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                PlaybackOptions.skipSecondsOptions.forEach { option ->
                    SettingsOptionChip(
                        text = "${option}s",
                        selected = option == skipSeconds,
                        onClick = { onSkipSeconds(option) },
                    )
                }
            }
        }
    }
}

/**
 * ShuttlChip styling inside a 48dp minimum touch target, so compact option pills
 * remain easy to tap without inflating their visual height.
 */
@Composable
private fun SettingsOptionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(ShuttlRadius.pill)
    val container = if (selected) MaterialTheme.colorScheme.onBackground else ShuttlTheme.extended.bgTertiary
    val content = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface
    val border = if (selected) Color.Transparent else MaterialTheme.colorScheme.outline
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            modifier = Modifier
                .clip(shape)
                .background(container, shape)
                .border(1.dp, border, shape)
                .indication(interactionSource, LocalIndication.current)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}
