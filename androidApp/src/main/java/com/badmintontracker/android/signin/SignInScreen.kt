package com.badmintontracker.android.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.BuildConfig
import com.badmintontracker.android.data.ThemePreferenceRepository
import com.badmintontracker.android.ui.components.ErrorBanner
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlCard
import com.badmintontracker.android.ui.components.ShuttlFieldType
import com.badmintontracker.android.ui.components.ShuttlOutlinedTextField
import com.badmintontracker.android.ui.components.ThemeToggleButton
import com.badmintontracker.android.ui.theme.ShuttlTheme

@Composable
fun SignInScreen(
    vm:          SignInViewModel,
    themePrefs:  ThemePreferenceRepository,
    onSignedIn:  () -> Unit,
) {
    val state     by vm.state.collectAsStateWithLifecycle()
    val themeMode by themePrefs.mode.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.events.collect { if (it is SignInEvent.SignedIn) onSignedIn() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 32.dp),
    ) {
        ThemeToggleButton(
            mode     = themeMode,
            onToggle = themePrefs::toggle,
            modifier = Modifier.align(Alignment.TopEnd),
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 400.dp)
                .padding(top = 56.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Brand()
            Spacer(Modifier.height(8.dp))
            Text(
                "Sign in to continue",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))

            ShuttlCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ShuttlOutlinedTextField(
                        value         = state.email,
                        onValueChange = vm::onEmailChange,
                        label         = "Email",
                        type          = ShuttlFieldType.Email,
                        enabled       = !state.isSubmitting,
                        modifier      = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    ShuttlOutlinedTextField(
                        value         = state.password,
                        onValueChange = vm::onPasswordChange,
                        label         = "Password",
                        type          = ShuttlFieldType.Password,
                        enabled       = !state.isSubmitting,
                        modifier      = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    ShuttlButton(
                        text     = if (state.isSubmitting) "Signing in…" else "Sign in",
                        onClick  = vm::submitEmail,
                        enabled  = !state.isSubmitting,
                        loading  = state.isSubmitting,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (state.error != null) {
                        Spacer(Modifier.height(16.dp))
                        ErrorBanner(state.error!!)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text  = "Registration is closed. Contact the admin if you need an account.",
                color = ShuttlTheme.extended.textTertiary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier  = Modifier.widthIn(max = 320.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = ShuttlTheme.extended.textTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "SHUTTL.",
            color         = MaterialTheme.colorScheme.onBackground,
            fontWeight    = FontWeight.Bold,
            fontSize      = 24.sp,
            letterSpacing = (-0.24).sp,
        )
        Spacer(Modifier.padding(start = 8.dp))
        AlphaBadge()
    }
}

@Composable
private fun AlphaBadge() {
    val gradient = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            ShuttlTheme.extended.accentDark,
        ),
    )
    Box(
        modifier = Modifier
            .background(gradient)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text          = "BETA 2.0",
            color         = Color.Black,
            fontWeight    = FontWeight.SemiBold,
            fontSize      = 9.sp,
            letterSpacing = 0.5.sp,
        )
    }
}
