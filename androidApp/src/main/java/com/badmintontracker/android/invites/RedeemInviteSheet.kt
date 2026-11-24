package com.badmintontracker.android.invites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.components.ErrorBanner
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.shared.invites.InviteErrorKind
import com.badmintontracker.shared.invites.PlayerInviteCopy
import com.badmintontracker.shared.invites.RedeemedInvite
import com.badmintontracker.shared.invites.formatInviteCode
import com.badmintontracker.shared.invites.formatInviteInput
import com.badmintontracker.shared.invites.normalizeInviteCode
import com.badmintontracker.shared.invites.parseQrInvitePayload
import kotlinx.coroutines.launch

/**
 * In-app modal sheet for an already signed-in account (Settings "Join another
 * coach" or Unlinked player screen "Enter invite code") to link a coach's
 * player profile via code or QR scan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedeemInviteSheet(
    onRedeem: suspend (code: String) -> Result<RedeemedInvite>,
    onLinked: (RedeemedInvite) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var codeInput by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submitCode(raw: String) {
        if (submitting) return
        val normalized = normalizeInviteCode(raw)
        if (normalized == null) {
            error = PlayerInviteCopy.errorMessage(InviteErrorKind.INVALID_OR_EXPIRED)
            return
        }
        focusManager.clearFocus()
        submitting = true
        error = null
        scope.launch {
            onRedeem(normalized)
                .onSuccess { redeemed ->
                    submitting = false
                    onLinked(redeemed)
                }
                .onFailure { err ->
                    submitting = false
                    error = err.toInviteDisplayMessage()
                }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = PlayerInviteCopy.JOIN_ANOTHER_COACH,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                TextButton(
                    onClick = {
                        focusManager.clearFocus()
                        launchQrCodeScan(
                            context = context,
                            onScanned = { raw ->
                                val parsed = parseQrInvitePayload(raw)
                                if (parsed == null) {
                                    error = PlayerInviteCopy.JOIN_QR_INVALID
                                } else {
                                    codeInput = formatInviteCode(parsed)
                                    submitCode(parsed)
                                }
                            },
                            onUnavailable = { error = PlayerInviteCopy.JOIN_QR_UNAVAILABLE },
                        )
                    },
                    enabled = !submitting,
                ) {
                    Text(PlayerInviteCopy.JOIN_SCAN_QR)
                }
            }
            Spacer(Modifier.height(12.dp))
            InviteCodeField(
                value = codeInput,
                onValueChange = {
                    codeInput = formatInviteInput(it)
                    error = null
                },
                onSubmit = { submitCode(codeInput) },
                enabled = !submitting,
                autoFocus = true,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { msg ->
                Spacer(Modifier.height(12.dp))
                ErrorBanner(msg)
            }
            Spacer(Modifier.height(18.dp))
            ShuttlButton(
                text = if (submitting) PlayerInviteCopy.JOIN_LINKING else PlayerInviteCopy.JOIN_ANOTHER_SUBMIT,
                onClick = { submitCode(codeInput) },
                enabled = codeInput.isNotBlank() && !submitting,
                loading = submitting,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
