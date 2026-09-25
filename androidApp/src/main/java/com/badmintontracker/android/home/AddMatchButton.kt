package com.badmintontracker.android.home

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.shared.home.HomeCopy

/**
 * Labelled while the list is at its top, "+" once it is scrolled, so the
 * button covers less of the rows (and their menus) while the coach is reading
 * them. Item 0 is always the filter row.
 */
fun addButtonExpanded(firstVisibleItemIndex: Int): Boolean = firstVisibleItemIndex == 0

/**
 * The Matches tab's bottom-right "Add match". Opens the same three-option
 * sheet the Home pill used to.
 *
 * Every colour and the shape are set here rather than left to Material: the
 * default FAB is a 16dp-cornered primaryContainer square, and the mock's is an
 * accent pill with the near-black onAccent text the rest of the app's primary
 * buttons carry.
 */
@Composable
fun AddMatchButton(expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ExtendedFloatingActionButton(
        text = { Text(HomeCopy.ADD_MATCH, style = MaterialTheme.typography.titleMedium) },
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        expanded = expanded,
        onClick = onClick,
        shape = RoundedCornerShape(ShuttlRadius.pill),
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier,
    )
}
