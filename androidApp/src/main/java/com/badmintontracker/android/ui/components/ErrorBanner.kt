package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius

/**
 * A failure the coach needs to read: a solid [error] fill with [onError]
 * text and glyph, the treatment iosApp's ErrorBanner draws too.
 *
 * Solid rather than the 8% error tint this used to be: #EF4444 as 12sp text
 * on that tint measured 3.17:1 in light, under the 4.5:1 body text needs.
 * onError on error is 5.07:1 in both themes (ShuttlPalette.onError).
 *
 * Draws no margin of its own. A caller places it at the page gutter or inside
 * a padded slot; a rounded fill running into the screen edges reads as a
 * mistake.
 */
@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    val fg    = MaterialTheme.colorScheme.onError
    val style = MaterialTheme.typography.bodySmall
    // The glyph sits in a box one text line tall, so it centres on the first
    // line at any font scale; a top-aligned 14dp glyph beside a 17sp line sat
    // visibly high.
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(ShuttlRadius.small))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.Top,
    ) {
        Box(Modifier.height(lineHeight), contentAlignment = Alignment.Center) {
            Icon(
                imageVector        = ShuttlIcons.Info,
                contentDescription = null,
                tint               = fg,
                modifier           = Modifier.size(14.dp),
            )
        }
        Text(
            text  = message,
            color = fg,
            style = style,
        )
    }
}
