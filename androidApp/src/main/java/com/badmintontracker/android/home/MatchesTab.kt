package com.badmintontracker.android.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.shared.home.HomeCopy

/**
 * The landing tab: its title bar over the match list. The list is the one the
 * drawer used to hold, full width now; everything it does (filters, legend,
 * swipe, menus, pull to refresh, "Shared with me") is unchanged.
 */
@Composable
fun MatchesTab(list: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TabTopBar(HomeCopy.TAB_MATCHES) { BackgroundWorkAction() }
        // weight(1f), not fillMaxSize: this Box is measured inside the outer
        // Column alongside TabTopBar, so fillMaxSize would claim the bar's own
        // height too and let the list overhang past it. AnalyticsTab and
        // SettingsTab measure their own body the same way.
        Box(Modifier.weight(1f).fillMaxWidth()) { list() }
    }
}
