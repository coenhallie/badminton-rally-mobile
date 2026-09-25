package com.badmintontracker.android.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.home.HomeCopy
import com.badmintontracker.shared.home.HomeTab

/**
 * The bottom bar. Every colour is pinned: Material's defaults are
 * surfaceContainer for the bar and secondaryContainer for the indicator, and
 * this app's scheme maps neither to what the mock draws. The indicator is the
 * same green tint the list's analysis chips use, so "selected" and "has this"
 * read as one family.
 */
@Composable
fun HomeNavigationBar(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    val border = MaterialTheme.colorScheme.outlineVariant
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().drawBehind {
            drawLine(border, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
        },
    ) {
        HomeTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                // No role in this app's type scale is exactly the mock's
                // 12px/500 (ShuttlType.kt's labelMedium is 13sp SemiBold, and
                // nothing else is 12sp Medium either), so the size and weight
                // are overridden here rather than invented as a new global
                // role for one label. The same one-off pattern
                // BackgroundWorkAction uses for its ring's own number.
                label = {
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = ShuttlTheme.extended.analysisChip,
                    selectedIconColor = ShuttlTheme.extended.analysisGlyph,
                    selectedTextColor = MaterialTheme.colorScheme.onBackground,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

private val HomeTab.label: String
    get() = when (this) {
        HomeTab.MATCHES -> HomeCopy.TAB_MATCHES
        HomeTab.ANALYTICS -> HomeCopy.TAB_ANALYTICS
        HomeTab.SETTINGS -> HomeCopy.TAB_SETTINGS
    }

private val HomeTab.icon: ImageVector
    get() = when (this) {
        HomeTab.MATCHES -> ShuttlIcons.Rows
        HomeTab.ANALYTICS -> ShuttlIcons.ChartColumn
        HomeTab.SETTINGS -> ShuttlIcons.SlidersHorizontal
    }
