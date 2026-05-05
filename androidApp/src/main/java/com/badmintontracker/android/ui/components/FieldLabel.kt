package com.badmintontracker.android.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.util.Locale

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text     = text.uppercase(Locale.ROOT),
        style    = MaterialTheme.typography.labelSmall,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
