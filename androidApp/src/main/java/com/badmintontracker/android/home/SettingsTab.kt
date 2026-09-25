package com.badmintontracker.android.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.BuildConfig
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.home.HomeCopy
import com.badmintontracker.shared.prefs.ThemeMode

/**
 * Everything Home's overflow menu and the drawer's footer used to hold, in one
 * place: the version line used to be drawn twice, from two string templates.
 * The sign-in screen keeps its own theme toggle; it is outside the tabs.
 */
@Composable
fun SettingsTab(
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    email: String?,
    onLabels: () -> Unit,
    onSignOut: () -> Unit,
    contentPadding: PaddingValues,
) {
    Column(Modifier.fillMaxSize()) {
        // The indicator on every tab's bar, as on every other screen's.
        TabTopBar(HomeCopy.TAB_SETTINGS) { BackgroundWorkAction() }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 16.dp + contentPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCard {
                SettingsRow(onClick = onLabels) {
                    Column(Modifier.weight(1f)) {
                        Text(HomeCopy.SETTINGS_LABELS, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                        Text(HomeCopy.SETTINGS_LABELS_DETAIL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(ShuttlIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(onClick = onToggleTheme) {
                    Text(HomeCopy.SETTINGS_DARK_THEME, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                    Switch(
                        checked = themeMode == ThemeMode.DARK,
                        // The row is the target; a second handler on the switch
                        // would toggle twice on a tap that lands on it.
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                            uncheckedTrackColor = ShuttlTheme.extended.bgTertiary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                    )
                }
            }
            SettingsCard {
                if (email != null) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
                        Text(HomeCopy.SETTINGS_SIGNED_IN_AS, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(email, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                SettingsRow(onClick = onSignOut) {
                    Text(HomeCopy.SETTINGS_SIGN_OUT, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                HomeCopy.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodySmall,
                color = ShuttlTheme.extended.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

/** bgSecondary on the page's bg: never bgInput on bgTertiary, which are one colour in light. */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ShuttlRadius.large))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) { content() }
}

@Composable
private fun SettingsRow(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
