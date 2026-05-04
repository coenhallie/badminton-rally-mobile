package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintontracker.android.data.ThemeMode

@Composable
fun ThemeToggleButton(
    mode: ThemeMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (glyph, desc) = when (mode) {
        ThemeMode.LIGHT -> "☾" to "Switch to dark mode"
        ThemeMode.DARK -> "☀" to "Switch to light mode"
    }
    Box(
        modifier = modifier
            .size(36.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            .clickable(onClick = onToggle)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 16.sp,
        )
    }
}
