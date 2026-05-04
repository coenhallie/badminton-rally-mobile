package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.theme.ShuttlTheme

@Composable
fun DividerWithText(text: String = "or", modifier: Modifier = Modifier) {
    Row(
        modifier              = modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Text(
            text     = text.uppercase(),
            color    = ShuttlTheme.extended.textTertiary,
            style    = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}
