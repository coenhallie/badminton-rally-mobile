package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme

/**
 * One choice among several, as a pill: the app's own filter chip.
 *
 * Unchosen it sits on the raised surface behind a hairline, chosen it fills
 * with the heading colour and letters in the page colour, the same pair the
 * tab switcher uses, so a chosen chip and a chosen tab read as the same
 * state. A caller with its own meaning for the chosen colour, a label's
 * swatch say, passes [selectedContainer] and [selectedContent] together.
 */
@Composable
fun ShuttlChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedContainer: Color? = null,
    selectedContent: Color? = null,
) {
    val shape = RoundedCornerShape(ShuttlRadius.pill)
    val container = when {
        selected -> selectedContainer ?: MaterialTheme.colorScheme.onBackground
        else -> ShuttlTheme.extended.bgTertiary
    }
    val content = when {
        selected -> selectedContent ?: MaterialTheme.colorScheme.background
        else -> MaterialTheme.colorScheme.onSurface
    }
    val border = if (selected) Color.Transparent else MaterialTheme.colorScheme.outline
    val interaction = remember { MutableInteractionSource() }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(container, shape)
            .border(1.dp, border, shape)
            .indication(interaction, ripple())
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}
