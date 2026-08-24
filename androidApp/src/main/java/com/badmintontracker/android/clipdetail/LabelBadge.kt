package com.badmintontracker.android.clipdetail

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.badmintontracker.shared.model.LabelColor

/**
 * A label's pill. [colorKey] null, or naming a swatch this build does not know,
 * renders the neutral chip rather than nothing: the name is the information, the
 * colour is decoration.
 */
@Composable
fun LabelBadge(name: String, colorKey: String?, modifier: Modifier = Modifier) {
    val swatch = LabelColor.from(colorKey)
    val container = swatch?.let { Color(it.background.toInt()) }
        ?: MaterialTheme.colorScheme.surfaceVariant
    val onContainer = swatch?.let { Color(it.foreground.toInt()) }
        ?: MaterialTheme.colorScheme.onSurfaceVariant

    Surface(modifier = modifier, shape = RoundedCornerShape(50), color = container) {
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = onContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
