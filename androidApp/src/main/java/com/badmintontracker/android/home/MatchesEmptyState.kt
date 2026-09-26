package com.badmintontracker.android.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.shared.home.HomeCopy

/**
 * An empty Matches tab: the old Home hero, then one line saying what the Add
 * button does. It still tells a new coach what the app is for, and gets out of
 * the way once there is a match to list.
 *
 * No button of its own: the Add button is on screen, and two controls doing
 * one thing is noise.
 */
@Composable
fun MatchesEmptyState(isPaused: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(72.dp))
        HeroTickerView(isPaused = isPaused)
        Spacer(Modifier.height(22.dp))
        Text(
            HomeCopy.EMPTY_BODY,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp),
        )
    }
}
