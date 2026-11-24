package com.badmintontracker.android.invites

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.players.PlayerAvatar
import com.badmintontracker.android.players.PlayerAvatarRowSize
import com.badmintontracker.android.ui.components.ErrorBanner
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.components.ShuttlFieldType
import com.badmintontracker.android.ui.components.ShuttlOutlinedTextField
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.invites.InvitePreview
import com.badmintontracker.shared.invites.PlayerInviteCopy
import com.badmintontracker.shared.players.PlayersCopy

/**
 * Signed-out invite onboarding screen: enter or scan a coach's 8-character
 * invite code, confirm the verified profile preview, and either create a
 * player account or sign into an existing Shuttl account to link it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinInviteScreen(
    vm: JoinInviteViewModel,
    onCompleted: () -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        vm.events.collect { if (it is JoinInviteEvent.Completed) onCompleted() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(PlayerInviteCopy.JOIN_TITLE) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            focusManager.clearFocus()
                            launchQrCodeScan(
                                context = context,
                                onScanned = vm::onQrScanned,
                                onUnavailable = vm::onQrUnavailable,
                            )
                        },
                        enabled = !state.isCheckingCode && !state.isSubmitting,
                    ) {
                        Text(PlayerInviteCopy.JOIN_SCAN_QR)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 24.dp),
            ) {
                InviteCodeField(
                    value = state.codeInput,
                    onValueChange = vm::onCodeChange,
                    onSubmit = vm::verifyCode,
                    enabled = !state.isCheckingCode && !state.isSubmitting,
                    autoFocus = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (state.preview == null) {
                    ErrorRegion(
                        error = state.error,
                        offerSignInSwitch = false,
                        onSwitchToSignIn = {},
                    )
                    ShuttlButton(
                        text = if (state.isCheckingCode) PlayerInviteCopy.JOIN_VERIFYING else PlayerInviteCopy.JOIN_VERIFY,
                        onClick = {
                            focusManager.clearFocus()
                            vm.verifyCode()
                        },
                        enabled = state.codeInput.isNotBlank() && !state.isCheckingCode,
                        loading = state.isCheckingCode,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Spacer(Modifier.height(16.dp))
                    VerifiedPreviewCard(preview = state.preview!!)
                    Spacer(Modifier.height(20.dp))

                    ShuttlOutlinedTextField(
                        value = state.email,
                        onValueChange = vm::onEmailChange,
                        label = PlayerInviteCopy.JOIN_EMAIL_LABEL,
                        type = ShuttlFieldType.Email,
                        enabled = !state.isSubmitting,
                        imeNext = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    ShuttlOutlinedTextField(
                        value = state.password,
                        onValueChange = vm::onPasswordChange,
                        label = if (state.authMode == JoinAuthMode.CREATE_ACCOUNT) {
                            PlayerInviteCopy.JOIN_PASSWORD_LABEL
                        } else {
                            PlayerInviteCopy.JOIN_SIGN_IN_PASSWORD_LABEL
                        },
                        type = ShuttlFieldType.Password,
                        enabled = !state.isSubmitting,
                        onDone = {
                            focusManager.clearFocus()
                            vm.submit()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    ErrorRegion(
                        error = state.error,
                        offerSignInSwitch = state.offerSignInSwitch,
                        onSwitchToSignIn = { vm.switchAuthMode(JoinAuthMode.SIGN_IN) },
                    )

                    val submitText = when (state.authMode) {
                        JoinAuthMode.CREATE_ACCOUNT ->
                            if (state.isSubmitting) PlayerInviteCopy.JOIN_CREATING else PlayerInviteCopy.JOIN_CREATE_SUBMIT
                        JoinAuthMode.SIGN_IN ->
                            if (state.isSubmitting) PlayerInviteCopy.JOIN_LINKING else PlayerInviteCopy.JOIN_LINK_SUBMIT
                    }
                    ShuttlButton(
                        text = submitText,
                        onClick = {
                            focusManager.clearFocus()
                            vm.submit()
                        },
                        enabled = !state.isSubmitting,
                        loading = state.isSubmitting,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(12.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(
                            onClick = {
                                val next = if (state.authMode == JoinAuthMode.CREATE_ACCOUNT) {
                                    JoinAuthMode.SIGN_IN
                                } else {
                                    JoinAuthMode.CREATE_ACCOUNT
                                }
                                vm.switchAuthMode(next)
                            },
                            enabled = !state.isSubmitting,
                        ) {
                            Text(
                                text = if (state.authMode == JoinAuthMode.CREATE_ACCOUNT) {
                                    PlayerInviteCopy.JOIN_SWITCH_TO_SIGN_IN
                                } else {
                                    PlayerInviteCopy.JOIN_SWITCH_TO_CREATE
                                },
                                color = ShuttlTheme.extended.accentDark,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorRegion(
    error: String?,
    offerSignInSwitch: Boolean,
    onSwitchToSignIn: () -> Unit,
) {
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        if (error != null) {
            Spacer(Modifier.height(14.dp))
            ErrorBanner(error)
            if (offerSignInSwitch) {
                Spacer(Modifier.height(8.dp))
                ShuttlButton(
                    text = PlayerInviteCopy.JOIN_SWITCH_TO_SIGN_IN,
                    onClick = onSwitchToSignIn,
                    variant = ShuttlButtonVariant.Secondary,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun VerifiedPreviewCard(preview: InvitePreview) {
    val shape = RoundedCornerShape(ShuttlRadius.large)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerAvatar(preview.playerName, PlayerAvatarRowSize)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = preview.playerName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(ShuttlRadius.pill))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = PlayerInviteCopy.JOIN_VERIFIED_BADGE,
                        style = MaterialTheme.typography.labelSmall,
                        color = ShuttlTheme.extended.accentDark,
                    )
                }
            }
            PlayersCopy.handLine(preview.racketArm)?.let { hand ->
                Text(
                    text = hand,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = PlayerInviteCopy.invitedBy(preview.coachEmail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
