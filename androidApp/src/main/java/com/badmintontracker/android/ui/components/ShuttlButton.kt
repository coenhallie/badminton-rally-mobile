package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.theme.ShuttlRadius
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
    // Defaults to none so every other call site in the app is unaffected.
    // Used today by LabelsScreen's "Add label" and NotesHeader's "Add note",
    // both a leading Icons.Default.Add.
    leadingIcon: ImageVector? = null,
) {
    val isPrimary    = variant == ShuttlButtonVariant.Primary
    val bg           = if (isPrimary) MaterialTheme.colorScheme.primary else ShuttlTheme.extended.bgTertiary
    val fg           = if (isPrimary) ShuttlTheme.extended.onAccent else MaterialTheme.colorScheme.onSurface
    val borderColor  = if (isPrimary) Color.Transparent else MaterialTheme.colorScheme.outline
    val effective    = enabled && !loading
    val shape        = RoundedCornerShape(ShuttlRadius.pill)
    val interaction  = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            // Clickable wraps minimumInteractiveComponentSize so compact buttons
            // keep a 48dp touch hitbox while drawing the compact pill visually,
            // and the ripple stays clipped inside the pill.
            .clickable(
                interactionSource = interaction,
                indication        = null,
                enabled           = effective,
                role              = Role.Button,
                onClick           = onClick,
            )
            .minimumInteractiveComponentSize()
            .alpha(if (effective) 1f else 0.5f)
            .clip(shape)
            .background(bg, shape)
            .border(width = if (isPrimary) 0.dp else 1.dp, color = borderColor, shape = shape)
            .indication(interaction, ripple())
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
        } else if (leadingIcon != null) {
            // Icon's own 24dp default reads oversized beside bodyLarge (16sp)
            // text - iOS's plus glyph inherits titleLarge (16pt). Sized down
            // to sit closer to the text's own cap height.
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(18.dp),
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
            overflow   = TextOverflow.Ellipsis,
        )
    }
}
