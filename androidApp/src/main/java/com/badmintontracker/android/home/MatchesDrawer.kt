package com.badmintontracker.android.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.BuildConfig
import com.badmintontracker.android.ui.theme.ShuttlTheme

/**
 * The matches drawer's contents: the list that used to be the app's front door,
 * plus an account footer.
 *
 * Mirrors iosApp's MatchesDrawer.swift. The shell differs by platform - Compose
 * has ModalNavigationDrawer, SwiftUI has nothing and needs a hand built overlay
 * - so this is only the contents.
 */
@Composable
fun MatchesDrawerContent(
    onLabels: () -> Unit,
    onSignOut: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxHeight()) {
        Text(
            text = "Matches",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 16.dp),
        )
        Box(Modifier.weight(1f)) { content() }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FooterRow("Labels", onLabels)
        FooterRow("Sign out", onSignOut)
        Text(
            text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = ShuttlTheme.extended.textTertiary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
        )
    }
}

@Composable
private fun FooterRow(title: String, onClick: () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    )
}
