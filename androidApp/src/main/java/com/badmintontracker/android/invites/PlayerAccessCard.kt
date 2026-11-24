package com.badmintontracker.android.invites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.components.ConfirmDialog
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.invites.PlayerAccessState
import com.badmintontracker.shared.invites.PlayerAccessStatus
import com.badmintontracker.shared.invites.PlayerInviteCopy
import com.badmintontracker.shared.invites.formatInviteCode

/**
 * Coach-side "Player access" card rendered on a player's Profile tab directly
 * below the 3 stat tiles. Displays UNINVITED, PENDING, or LINKED state.
 */
@Composable
fun PlayerAccessCard(
    playerName: String,
    accessState: PlayerAccessState?,
    busy: Boolean,
    onOpenInvite: () -> Unit,
    onCancelInvite: () -> Unit,
    onRemoveAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    val status = accessState?.status ?: PlayerAccessStatus.UNINVITED
    val shape = RoundedCornerShape(ShuttlRadius.large)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = PlayerInviteCopy.ACCESS_SECTION_TITLE.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant, shape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (status) {
                PlayerAccessStatus.UNINVITED -> {
                    Text(
                        text = PlayerInviteCopy.inviteCardHeading(playerName),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = PlayerInviteCopy.inviteCardBody(playerName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ShuttlButton(
                        text = PlayerInviteCopy.inviteButton(playerName),
                        onClick = onOpenInvite,
                        loading = busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                PlayerAccessStatus.PENDING -> {
                    val code = accessState?.pendingCode.orEmpty()
                    val expiresAt = accessState?.pendingExpiresAt
                    StatusBadge(
                        label = PlayerInviteCopy.ACCESS_BADGE_PENDING,
                        dotColor = ShuttlTheme.extended.warning,
                    )
                    if (code.isNotBlank()) {
                        Text(
                            text = formatInviteCode(code),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    if (expiresAt != null) {
                        Text(
                            text = PlayerInviteCopy.expiryCaption(expiresAt, System.currentTimeMillis()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ShuttlButton(
                            text = PlayerInviteCopy.ACCESS_SHOW_CODE,
                            onClick = onOpenInvite,
                            loading = busy,
                            compact = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = onCancelInvite,
                            enabled = !busy,
                        ) {
                            Text(
                                text = PlayerInviteCopy.ACCESS_CANCEL,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                PlayerAccessStatus.LINKED -> {
                    StatusBadge(
                        label = PlayerInviteCopy.ACCESS_BADGE_CONNECTED,
                        dotColor = MaterialTheme.colorScheme.primary,
                    )
                    accessState?.linkedEmail?.takeIf { it.isNotBlank() }?.let { email ->
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    accessState?.linkedAt?.let { linkedAt ->
                        Text(
                            text = PlayerInviteCopy.linkedDateLabel(linkedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    ShuttlButton(
                        text = PlayerInviteCopy.ACCESS_REMOVE,
                        onClick = { confirmRemove = true },
                        variant = ShuttlButtonVariant.Secondary,
                        loading = busy,
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = PlayerInviteCopy.removeAccessTitle(playerName),
            message = PlayerInviteCopy.removeAccessBody(playerName),
            confirmLabel = PlayerInviteCopy.ACCESS_REMOVE_CONFIRM,
            onConfirm = {
                confirmRemove = false
                onRemoveAccess()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun StatusBadge(label: String, dotColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
