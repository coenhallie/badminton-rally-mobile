package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.data.ThemeMode
import com.badmintontracker.android.ui.icons.ShuttlIcons

@Composable
fun ThemeToggleButton(
    mode:     ThemeMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (icon, desc) = when (mode) {
        ThemeMode.LIGHT -> ShuttlIcons.Moon to "Switch to dark mode"
        ThemeMode.DARK  -> ShuttlIcons.Sun  to "Switch to light mode"
    }
    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(onClick = onToggle)
            .semantics {
                role = Role.Button
                contentDescription = desc
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
