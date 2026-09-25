package com.badmintontracker.android.cliplist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.shared.matchlist.MatchListCopy
import com.badmintontracker.shared.matchlist.MatchListFilter
import com.badmintontracker.shared.matchlist.showsFilters

/**
 * The chips above the list, and the legend's way in. With fewer matches than
 * [showsFilters] asks for, only the legend button is left, at the end of a
 * slim row: the glyphs still need naming on a short list.
 */
@Composable
internal fun MatchFilterRow(
    showChips: Boolean,
    selected: MatchListFilter,
    onSelect: (MatchListFilter) -> Unit,
    onLegend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The end inset backs out the IconButton's own 12dp, as the row
            // menu does, so the glyph lines up with the kebabs below it.
            .padding(start = DrawerList.sideMargin, end = DrawerList.sideMargin - 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showChips) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    // One line at the drawer's width; at a large font scale it
                    // scrolls rather than wrapping into a second row of chips.
                    .horizontalScroll(rememberScrollState())
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterPill(MatchListCopy.FILTER_ALL, selected == MatchListFilter.ALL) { onSelect(MatchListFilter.ALL) }
                FilterPill(MatchListCopy.FILTER_ON_THIS_PHONE, selected == MatchListFilter.ON_THIS_PHONE) {
                    onSelect(MatchListFilter.ON_THIS_PHONE)
                }
                FilterPill(MatchListCopy.FILTER_NOT_ANALYZED, selected == MatchListFilter.NOT_ANALYZED) {
                    onSelect(MatchListFilter.NOT_ANALYZED)
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        IconButton(onClick = onLegend) {
            Icon(
                ShuttlIcons.Info,
                contentDescription = MatchListCopy.LEGEND_BUTTON,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One filter, as a pill. Not ShuttlChip: that one sits on a filled raised
 * surface and reads as a button, where these are three radio choices on the
 * panel itself, outlined until chosen.
 */
@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(ShuttlRadius.pill)
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
        color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.onBackground, shape)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape),
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
