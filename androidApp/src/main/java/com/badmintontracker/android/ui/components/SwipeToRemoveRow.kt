package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * End-to-start swipe that reveals a red delete affordance. [onSwiped] must
 * return true only when the row synchronously leaves the list; return false
 * to snap back (e.g. while a confirmation dialog or network call is pending).
 *
 * [shape] clips the reveal AND the row together, so an inset rounded row does
 * not show square corners from behind its own silhouette while it slides.
 * [contentBackground] is the opaque backing that keeps the red hidden until the
 * swipe: it has to be the colour the row itself sits on, which for a filled
 * card is the card's fill and not the page. Callers go through
 * `DrawerRowContainer`, which states all three together.
 */
@Composable
fun SwipeToRemoveRow(
    label: String,
    onSwiped: () -> Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    contentBackground: Color = MaterialTheme.colorScheme.background,
    content: @Composable () -> Unit,
) {
    // The state is remembered once per row, and its confirmValueChange with it:
    // passed onSwiped directly, a row went on calling the callback of its first
    // composition, closed over whatever that composition knew. A row on the
    // match list is first composed before the stored results are read,
    // so its swipe opened "Remove video?" for the row as it was then, without
    // the rallies and the heatmap it was by then drawing. Read through
    // rememberUpdatedState, the swipe always calls the latest callback.
    val currentOnSwiped by rememberUpdatedState(onSwiped)
    val haptics = LocalHapticFeedback.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) currentOnSwiped() else false
        },
    )
    // Fire a subtle haptic tick once each time the swipe crosses into the
    // delete threshold so the coach feels when releasing will trigger removal.
    LaunchedEffect(dismissState) {
        snapshotFlow { dismissState.targetValue == SwipeToDismissBoxValue.EndToStart }
            .distinctUntilChanged()
            .collect { crossed ->
                if (crossed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
    }
    SwipeToDismissBox(
        state = dismissState,
        // After the caller's own modifier, so an inset row clips inside its
        // margin rather than rounding the margin itself. Both layers need it:
        // without it the row slides out past the margin as it is swiped, and
        // the reveal keeps square corners behind a rounded row.
        modifier = modifier
            .clip(shape)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(label = label) {
                        currentOnSwiped()
                        true
                    },
                )
            },
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            // Drawn only while a swipe is actually under way. The clip above
            // antialiases this layer and then the opaque backing over it
            // against the same partly covered edge pixels, and the two blends
            // do not cancel: with the reveal always present, a rounded row wore
            // a permanent faint pink outline. Nothing is behind the row at rest
            // now, so there is no second edge to blend; mid-swipe the seam sits
            // on the reveal that is already red.
            if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.error),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = label,
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.padding(end = 24.dp),
                    )
                }
            }
        },
    ) {
        // Opaque backing so the red background stays hidden until the swipe.
        // Shaped as well as clipped: the clip contains the row as it slides,
        // but a square backing inside a rounded clip still let the red show
        // through the corners mid-swipe.
        Box(Modifier.background(contentBackground, shape)) { content() }
    }
}
