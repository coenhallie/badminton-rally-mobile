package com.badmintontracker.android.cliplist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
                    // One line at the tab's width; at a large font scale it
                    // scrolls rather than wrapping into a second row of chips.
                    .horizontalScroll(rememberScrollState())
                    // One named group, not three unlabelled choices: without
                    // the name TalkBack reads the three pills with nothing to
                    // say they are one control choosing one filter. The same
                    // label iOS gives its group.
                    .selectableGroup()
                    .semantics { contentDescription = MatchListCopy.FILTER_GROUP },
                horizontalArrangement = Arrangement.spacedBy(PillGap),
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
                ShuttlIcons.InfoCircle,
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
 *
 * The drawn pill is about 30dp tall; the touch target around it is the full
 * 48dp of the row, taken by a transparent box with the pill centred in it.
 * The ripple is drawn on the pill itself, through a shared interaction
 * source, so a press still lights up the pill's shape and not a rectangle.
 */
@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(ShuttlRadius.pill)
    val interactions = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                interactionSource = interactions,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .clip(shape)
                .then(
                    if (selected) Modifier.background(MaterialTheme.colorScheme.onBackground, shape)
                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape),
                )
                .indication(interactions, ripple())
                .padding(horizontal = PillPaddingH, vertical = 7.dp),
        )
    }
}

/*
 * Sized to fit the drawer's former 330dp panel at the default font scale,
 * measured rather than guessed - comfortably inside the wider row the
 * full-width Matches tab list gives it now. The row runs from the 24dp side
 * margin to 12dp short of the other edge (the info IconButton's own padding
 * backed out): 294dp. The three labels in Archivo SemiBold 12sp with
 * labelMedium's -0.01em tracking measure 14.2 + 75.0 + 70.4 = 159.5dp (from
 * the font's advance widths). Pills: 159.5 + 6 x 10 padding = 219.5, two 8dp
 * gaps = 235.5, the 48dp button = 283.5, which leaves about 10dp. At 12.5sp
 * and 12dp padding it came to 298 and the last pill ran under the button. A
 * larger font scale still scrolls.
 */
private val PillPaddingH = 10.dp
private val PillGap = 8.dp
