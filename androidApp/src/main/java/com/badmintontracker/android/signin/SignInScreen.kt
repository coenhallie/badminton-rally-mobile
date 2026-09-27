package com.badmintontracker.android.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.BuildConfig
import com.badmintontracker.android.home.HomeTopBar
import com.badmintontracker.android.ui.components.ErrorBanner
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlFieldType
import com.badmintontracker.android.ui.components.ShuttlOutlinedTextField
import com.badmintontracker.android.ui.components.ThemeToggleButton
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.android.ui.theme.ShuttlTypeExtras
import com.badmintontracker.shared.home.HomeCopy
import com.badmintontracker.shared.prefs.ThemePreferenceRepository
import com.badmintontracker.shared.signin.SignInCopy

/**
 * Laid out the way the list it hands over to is: the list's own title bar
 * (HomeTopBar) with the wordmark in the title's slot and the theme switch
 * where the Settings gear sits, then the page's heading and the form at the
 * 24dp gutter, top-anchored. No card around the form: the app uses cards to
 * group rows (Settings), not to frame a page's only content.
 *
 * The theme switch stays here, outside Settings, because a signed-out coach
 * cannot reach Settings (SettingsScreen's doc comment).
 */
@Composable
fun SignInScreen(
    vm:          SignInViewModel,
    themePrefs:  ThemePreferenceRepository,
    onSignedIn:  () -> Unit,
) {
    val state        by vm.state.collectAsStateWithLifecycle()
    val themeMode    by themePrefs.mode.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    // Submitting drops the keyboard, as iOS's Go key does, so the error
    // banner that may follow is not left under it.
    val submit = {
        focusManager.clearFocus()
        vm.submitEmail()
    }

    LaunchedEffect(Unit) {
        vm.events.collect { if (it is SignInEvent.SignedIn) onSignedIn() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Side insets (a landscape cutout, a side navigation bar) the
            // way HomeScreen takes them; the bar takes the status bar itself.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        HomeTopBar(SignInCopy.WORDMARK, titleStyle = ShuttlTypeExtras.wordmark) {
            ThemeToggleButton(
                mode               = themeMode,
                contentDescription = SignInCopy.themeToggle(themeMode),
                onToggle           = themePrefs::toggle,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // The navigation bar, and the keyboard while it is up: the
                // form scrolls above it rather than under it.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    // The gutter first and the cap inside it, so on a phone
                    // the heading lines up with the wordmark at exactly 24;
                    // capping first left a 400dp column centred on a 411dp
                    // screen, 29.5dp in.
                    .padding(horizontal = SignInGutter)
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 24.dp),
            ) {
                Text(
                    SignInCopy.TITLE,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(24.dp))
                ShuttlOutlinedTextField(
                    value         = state.email,
                    onValueChange = vm::onEmailChange,
                    label         = SignInCopy.EMAIL,
                    type          = ShuttlFieldType.Email,
                    enabled       = !state.isSubmitting,
                    imeNext       = true,
                    modifier      = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                ShuttlOutlinedTextField(
                    value         = state.password,
                    onValueChange = vm::onPasswordChange,
                    label         = SignInCopy.PASSWORD,
                    type          = ShuttlFieldType.Password,
                    enabled       = !state.isSubmitting,
                    onDone        = submit,
                    modifier      = Modifier.fillMaxWidth(),
                )
                // Above the button, beside the fields it is about, so it is
                // still on screen with the keyboard up.
                //
                // Read out when it appears: a failed sign-in changes nothing
                // else a screen reader would land on. The live region is this
                // always-composed container, not the banner, so an error
                // appearing is a content change of a node TalkBack already
                // knows rather than a new node it may not announce. The gap
                // above the button lives inside it so the container is never
                // zero-sized: Compose leaves a node with no area out of what
                // it reports to accessibility services, which would make the
                // container a new node again the moment the error arrives.
                Column(
                    Modifier.semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                    },
                ) {
                    state.error?.let { error ->
                        Spacer(Modifier.height(16.dp))
                        ErrorBanner(error)
                    }
                    Spacer(Modifier.height(24.dp))
                }
                ShuttlButton(
                    text     = if (state.isSubmitting) SignInCopy.SUBMITTING else SignInCopy.SUBMIT,
                    onClick  = submit,
                    enabled  = !state.isSubmitting,
                    loading  = state.isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text      = SignInCopy.FOOTNOTE,
                    color     = ShuttlTheme.extended.textTertiary,
                    style     = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text      = HomeCopy.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString()),
                    color     = ShuttlTheme.extended.textTertiary,
                    style     = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The page gutter: HomeTopBar's start padding, MatchScreen's and Settings'. */
private val SignInGutter = 24.dp
