package com.badmintontracker.android.cliplist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.shared.matchlist.MatchListCopy
import com.badmintontracker.shared.matchlist.MatchListFilter
import com.badmintontracker.shared.matchlist.MatchListSort
import com.badmintontracker.shared.matchlist.showsFilters

/**
 * The chips and the sort above the list, and the legend's way in. With fewer
 * matches than [showsFilters] asks for, only the legend button is left, at the
 * end of a slim row: the glyphs still need naming on a short list, where a
 * filter or an order would not earn its chrome.
 */
@Composable
internal fun MatchFilterRow(
    showChips: Boolean,
    selected: MatchListFilter,
    onSelect: (MatchListFilter) -> Unit,
    sort: MatchListSort,
    onSort: (MatchListSort) -> Unit,
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
            val chipScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fadedEdges(chipScroll)
                    // One line at the list's width; at a large font scale it
                    // scrolls rather than wrapping into a second row of chips.
                    .horizontalScroll(chipScroll)
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
            // Outside the chips' scroll, so however far they scroll, and at
            // any font scale, the order stays in sight and nothing slides
            // under it.
            SortButton(sort, onSort)
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

/**
 * Fades the chips out toward an edge they continue past, and only then. A
 * chip cut off square at the sort's edge read as "Not ar Most notes", one
 * run of text; faded, it reads as more to scroll to. The fade is fully clear
 * for its last [ChipFadeGap] (the gradient clamps past its end), so even the
 * faintest letters keep a gap from the sort's name.
 */
private fun Modifier.fadedEdges(scroll: ScrollState): Modifier = this
    // Offscreen, so the DstIn below masks the chips alone and not the list
    // drawn behind them.
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val fade = ChipFade.toPx()
        val gap = ChipFadeGap.toPx()
        if (scroll.canScrollBackward) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = gap, endX = fade),
                blendMode = BlendMode.DstIn,
            )
        }
        if (scroll.canScrollForward) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width - gap),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * The list's order, as its name and a chevron, opening the four orders.
 * Plain text rather than a fourth pill: the pills are one choice of what to
 * show, and an outlined "Newest" among them read as another filter.
 *
 * As wide as its widest name at any choice, so picking an order never moves
 * where the chips' scroll ends; the name sits at the end, by the info button.
 * The touch target is the row's full 48dp, as the pills' is, and the ripple
 * is drawn on the name's own shape.
 */
@Composable
private fun SortButton(sort: MatchListSort, onSort: (MatchListSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(ShuttlRadius.pill)
    // The menu shares a Box with its anchor: DropdownMenu positions itself
    // relative to its parent.
    Box {
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(interactionSource = interactions, indication = null) { open = true }
                // The four stacked names are one button to TalkBack, which
                // hears what it does and the order the list is in.
                .clearAndSetSemantics {
                    contentDescription = MatchListCopy.sortDescription(sort)
                    role = Role.Button
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            MatchListSort.entries.forEach { option ->
                val shown = option == sort
                Row(
                    modifier = Modifier
                        .alpha(if (shown) 1f else 0f)
                        .clip(shape)
                        .then(if (shown) Modifier.indication(interactions, ripple()) else Modifier)
                        .padding(start = SortPaddingStart, end = SortPaddingEnd, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        MatchListCopy.sortLabel(option),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        ShuttlIcons.ChevronDown,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MatchListSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(MatchListCopy.sortLabel(option)) },
                    // The slot is always there, so the names line up whichever
                    // one carries the check.
                    leadingIcon = { if (option == sort) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = { onSort(option); open = false },
                    modifier = Modifier.semantics { selected = option == sort },
                )
            }
        }
    }
}

/*
 * Measured rather than guessed, from Archivo SemiBold's advance widths at 12sp
 * with labelMedium's -0.01em tracking. The pills' labels are 14.2 + 75.0 +
 * 70.4 = 159.5dp; with 6 x 10 padding and two 8dp gaps the pills take 235.5dp.
 * The sort's widest name, "Most rallies", is 61.8, plus the 4dp gap, the 12dp
 * chevron and 6 + 4 padding: 87.8dp. The row runs from the 24dp side margin to
 * 12dp short of the other edge (the info IconButton's own padding backed out),
 * so on a 411dp phone (a Pixel 9a) it is 375dp: 235.5 + 87.8 + the 48dp info
 * button = 371.3, and the pills sit whole at rest. On a 360dp phone, or at a
 * larger font scale, the pills scroll under the sort's edge instead.
 */
private val PillPaddingH = 10.dp
private val PillGap = 8.dp
private val SortPaddingStart = 6.dp
private val SortPaddingEnd = 4.dp
private val ChipFade = 24.dp
private val ChipFadeGap = 6.dp
