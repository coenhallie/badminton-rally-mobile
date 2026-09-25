package com.badmintontracker.android.home

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A tab's own title bar: the tab's name at headline size, as the drawer's
 * header drew "Matches", and the actions (the background-work indicator) at
 * the end. Not a TopAppBar: a tab root has no navigation icon, and a 28sp
 * headline does not fit TopAppBar's title slot.
 *
 * Draws under the status bar itself because the tab host's Scaffold hands its
 * content no top inset (see HomeScreen).
 */
@Composable
fun TabTopBar(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}
