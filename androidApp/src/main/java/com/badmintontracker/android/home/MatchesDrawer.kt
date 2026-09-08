package com.badmintontracker.android.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
    onClose: () -> Unit,
    onLabels: () -> Unit,
    onSignOut: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, top = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Matches",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            // The mock's own close affordance, and the one iOS already had. The
            // hamburger that opened the drawer is behind the scrim once it is
            // open, and the edge swipe back out loses to the system's own back
            // gesture on gesture navigation, so without this the only reliable
            // way out was the back button.
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close matches",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.weight(1f)) { content() }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier.padding(top = 10.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FooterRow("Labels", onLabels)
            FooterRow("Sign out", onSignOut)
            Text(
                text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = ShuttlTheme.extended.textTertiary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp),
            )
        }
    }
}

@Composable
private fun FooterRow(title: String, onClick: () -> Unit) {
    Text(
        text = title,
        // bodyMedium, not titleMedium: the mock's footer is 14px and quiet, and
        // the scale's next step up (15 semibold) shouted next to the version
        // line under it.
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            // 26dp of vertical padding on a 14sp line clears Android's 48dp
            // minimum target on its own; the mock's own 10px is the gap between
            // two labels, not a touch target.
            .padding(horizontal = 24.dp, vertical = 13.dp),
    )
}
