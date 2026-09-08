package com.badmintontracker.android.cliplist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.components.SwipeToRemoveRow

/**
 * The drawer list's shared metrics, from the `Rally Drawer` mock.
 *
 * The list is an inset one now, not a full-bleed one: rows are cards sitting on
 * the panel with a margin either side, separated by a gap rather than by a
 * divider. Everything that renders a row in the drawer - the "On this phone"
 * card in LocalVideoSection.kt and both match rows in ClipListScreen.kt - reads
 * its numbers from here, so the two files cannot drift a dp apart.
 *
 * Mirrors iosApp's DrawerList.swift number for number.
 */
internal object DrawerList {
    /** The panel's own gutter. The mock's `margin: … 24px`. */
    val sideMargin = 24.dp
    val rowPaddingH = 16.dp
    val rowPaddingV = 14.dp
    /** Thumbnail to text, and text to the trailing control. */
    val gap = 12.dp
    /** Between two rows. Replaces the divider the old full-bleed list drew. */
    val rowGap = 6.dp
    /** Above a section label. [rowGap] adds to this between two sections. */
    val sectionGap = 22.dp
    /** A section label to its first row. [rowGap] adds to this. */
    val labelGap = 4.dp
    /** The list's own top inset, so the first label clears the header by 26dp. */
    val listTopInset = 4.dp
    val thumbWidth = 64.dp
    val thumbHeight = 40.dp
}

/**
 * A drawer section's label: 12sp, secondary, sentence case.
 *
 * Deliberately not the uppercase tracked `labelSmall` the app's older lists
 * use: the mocks for this redesign carry no uppercase and no positive tracking
 * anywhere. AnalyticsScreen.kt's SectionHeader has since moved to this form and
 * states its own spacing, so it draws the label itself rather than calling this;
 * the two want one shared component.
 */
@Composable
internal fun DrawerSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = DrawerList.sideMargin,
            end = DrawerList.sideMargin,
            // Carried by the label rather than by the list, so the gap above a
            // section is the same whether it is the first one or the third.
            // The list's own top inset makes up the difference for the first;
            // for the rest the [DrawerList.rowGap] between items adds to this.
            top = DrawerList.sectionGap,
            bottom = DrawerList.labelGap,
        ),
    )
}

/**
 * The inset, rounded box every drawer row sits in.
 *
 * Exists because a rounded inset row and [SwipeToRemoveRow] have to agree on
 * three things at once, and getting any of them wrong is visible: the margin
 * (or the red reveal bleeds past the card to the panel edge), the shape (or
 * square corners poke out from behind the rounded silhouette mid-swipe), and
 * the fill behind the content (or the reveal shows through the row before the
 * user has swiped). Stating them once, here, is what keeps a swipeable row and
 * a non-swipeable one identical.
 *
 * [swipeLabel] null renders the same box without the gesture - the mid-pipeline
 * rows that must not be removable.
 */
@Composable
internal fun DrawerRowContainer(
    shape: Shape,
    fill: Color,
    swipeLabel: String? = null,
    onSwiped: () -> Boolean = { false },
    content: @Composable () -> Unit,
) {
    val inset = Modifier.padding(horizontal = DrawerList.sideMargin)
    if (swipeLabel != null) {
        SwipeToRemoveRow(
            label = swipeLabel,
            onSwiped = onSwiped,
            modifier = inset,
            shape = shape,
            contentBackground = fill,
            content = content,
        )
    } else {
        Box(inset.clip(shape).background(fill)) { content() }
    }
}
