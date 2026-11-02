package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.players.PlayersCopy

/**
 * A field that opens a menu: ShuttlOutlinedTextField's box (the same fill,
 * stroke, radius and padding, the text in the field's style) holding [text]
 * and a chevron, so it reads as one form with the text fields beside it.
 * iosApp's ShuttlMenuField draws the same box. The player picker and the
 * details editor's Format at a large font scale are built on it.
 *
 * The menu ([menu], DropdownMenuItems) opens under the box and is exactly as
 * wide as it, as Material's exposed dropdown is: its rows' text, at the item's
 * 12dp inset, starts where the box's own text does, and the menu never runs
 * past the box towards the screen edge. Rows should carry no leading icon, so
 * every row's text starts at that one x (iOS menu rows carry none either).
 *
 * [placeholder] letters the text in the secondary colour, for a box that says
 * what to choose rather than what is chosen. [leading] sits before the text
 * (the picker's avatar). The text is one line, ellipsized, except at a large
 * font scale ([LARGE_FONT_SCALE]), where it wraps to up to three lines and the
 * box grows, so our own words ("Someone else") are never cut.
 *
 * A screen reader hears the box as a dropdown list, what it shows, and whether
 * the menu is open, and can open and close it. A disabled box dims whole, as a
 * disabled ShuttlOutlinedTextField does.
 */
@Composable
fun ShuttlMenuField(
    text: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    menu: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(ShuttlRadius.small)
    val density = LocalDensity.current
    var boxWidthPx by remember { mutableIntStateOf(0) }
    val large = density.fontScale >= LARGE_FONT_SCALE

    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { boxWidthPx = it.width }
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clip(shape)
                .background(ShuttlTheme.extended.bgInput, shape)
                .border(1.dp, ShuttlTheme.extended.textTertiary, shape)
                .clickable(enabled = enabled, role = Role.DropdownList) { onExpandedChange(true) }
                .semantics {
                    stateDescription = if (expanded) PlayersCopy.MENU_EXPANDED else PlayersCopy.MENU_COLLAPSED
                    if (enabled) {
                        if (expanded) {
                            collapse { onExpandedChange(false); true }
                        } else {
                            expand { onExpandedChange(true); true }
                        }
                    }
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (placeholder) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                maxLines = if (large) LARGE_FONT_MAX_LINES else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                ShuttlIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            // Until the box has been measured, the menu's own width.
            modifier = if (boxWidthPx > 0) Modifier.width(with(density) { boxWidthPx.toDp() }) else Modifier,
            content = menu,
        )
    }
}

/**
 * The font scale from which a control may give its text more lines (or a
 * different shape) rather than cut our words: Android's steps past 1.3 (1.5,
 * 1.8 and 2.0), about where iOS's accessibility sizes begin.
 */
const val LARGE_FONT_SCALE = 1.5f

private const val LARGE_FONT_MAX_LINES = 3

/** A disabled field's alpha: ShuttlOutlinedTextField's own. */
private const val DISABLED_ALPHA = 0.6f
