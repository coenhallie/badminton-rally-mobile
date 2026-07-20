package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.theme.ShuttlTheme

enum class ShuttlButtonVariant { Primary, Secondary }

@Composable
fun ShuttlButton(
    text:     String,
    onClick:  () -> Unit,
    modifier: Modifier = Modifier,
    variant:  ShuttlButtonVariant = ShuttlButtonVariant.Primary,
    enabled:  Boolean = true,
    loading:  Boolean = false,
    compact:  Boolean = false,
) {
    val isPrimary    = variant == ShuttlButtonVariant.Primary
    val bg           = if (isPrimary) MaterialTheme.colorScheme.primary else ShuttlTheme.extended.bgTertiary
    val fg           = if (isPrimary) Color.Black else MaterialTheme.colorScheme.onSurface
    val borderColor  = if (isPrimary) Color.Transparent else MaterialTheme.colorScheme.outline
    val effective    = enabled && !loading

    Row(
        modifier = modifier
            .alpha(if (effective) 1f else 0.5f)
            .background(bg)
            .border(width = if (isPrimary) 0.dp else 1.dp, color = borderColor)
            .clickable(enabled = effective, onClick = onClick)
            .padding(
                horizontal = if (compact) 12.dp else 24.dp,
                vertical   = if (compact) 6.dp else 12.dp,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color       = fg,
                strokeWidth = 2.dp,
                modifier    = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text       = text,
            color      = fg,
            fontWeight = FontWeight.SemiBold,
            style      = if (compact) MaterialTheme.typography.labelMedium
                         else MaterialTheme.typography.bodyLarge,
            maxLines   = 1,
            softWrap   = false,
        )
    }
}
