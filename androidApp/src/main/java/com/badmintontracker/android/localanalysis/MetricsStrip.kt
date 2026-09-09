package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.badmintontracker.analysis.player.MetricKind
import com.badmintontracker.analysis.player.PoseMetrics
import com.badmintontracker.android.ui.components.ShuttlPillTabs
import com.badmintontracker.android.ui.components.ShuttlStatTile
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.local.metricLabel
import com.badmintontracker.shared.local.metricText
import com.badmintontracker.shared.local.visibleKinds
import com.badmintontracker.shared.prefs.RacketArm

/** The page gutter the mock lays every card in. */
private val GUTTER = 24.dp

/** Tiles per page of the collapsed strip: two rows of two. */
private const val TILES_PER_PAGE = 4

private val RACKET_ARMS = listOf(RacketArm.LEFT to "Left arm", RacketArm.RIGHT to "Right arm", null to "Both")

/**
 * The per-frame measurements under the skeleton video, as stat tiles.
 *
 * Two layouts of the same tiles. Collapsed, they are pages of four, two rows
 * of two, swiped a page at a time, so the video, the tiles and the graph fit
 * a phone together and a coach watching one number sees it and the frame at
 * once. Expanded, they are the whole two-column grid, with the racket-arm
 * control, the caption that says once what every angle tile means, and the
 * file's own facts, none of which is read per frame. Either way every measurement is present and a
 * tap selects it for the graph and the overlay; absent is a dash so the
 * layout never jumps and a stale number is never left on screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetricsStrip(
    metrics: PoseMetrics?,
    hasCourt: Boolean,
    racketArm: RacketArm?,
    onRacketArm: (RacketArm?) -> Unit,
    selected: MetricKind,
    onSelect: (MetricKind) -> Unit,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    detail: String,
    modifier: Modifier = Modifier,
) {
    val kinds = visibleKinds(hasCourt, racketArm)
    fun tile(kind: MetricKind, tileModifier: Modifier) = @Composable {
        val text = metricText(kind, metrics?.let { kind.of(it) })
        ShuttlStatTile(
            value = text.value,
            unit = text.unit,
            label = metricLabel(kind, racketArm),
            labelAbove = true,
            selected = kind == selected,
            onClick = { onSelect(kind) },
            modifier = tileModifier,
        )
    }

    if (!expanded) {
        val pages = kinds.chunked(TILES_PER_PAGE)
        val pagerState = rememberPagerState(pageCount = { pages.size })
        // Turn to the selected tile's page when something other than a tap on
        // it selected it - the racket-arm control hiding the chosen kind, or
        // a saved choice on opening - and leave the pager alone otherwise,
        // since a page that turns under a tap is a page that cannot be aimed at.
        LaunchedEffect(selected, kinds) {
            val index = kinds.indexOf(selected)
            if (index < 0) return@LaunchedEffect
            val page = index / TILES_PER_PAGE
            if (pagerState.currentPage != page) pagerState.animateScrollToPage(page)
        }
        Column(modifier = modifier.fillMaxWidth()) {
            // Inside the gutter and clipped to it, not padded through
            // contentPadding: that would show the next page's edge beside the
            // current one, and the dots below already say there is more.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER),
                pageSpacing = 8.dp,
                key = { page -> pages[page].first().name },
            ) { page ->
                TilePage(pages[page]) { kind, tileModifier -> tile(kind, tileModifier)() }
            }
            // The dots say there is more, which the mock's single grid never
            // had to; the chevron opens the whole grid. One row for both, so
            // the strip costs the graph under it as little height as it can.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                if (pages.size > 1) PageDots(count = pages.size, current = pagerState.currentPage)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onExpanded(true) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Show all measurements")
                }
            }
        }
        return
    }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = GUTTER)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "All measurements",
                style = MaterialTheme.typography.bodySmall,
                color = ShuttlTheme.extended.textTertiary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onExpanded(false) }) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Show one row of measurements")
            }
        }
        // Two to a row, each taking half, as the mock lays them out. An odd
        // last tile gets an empty partner so it keeps its half rather than
        // stretching across the whole row and reading as a different kind of
        // tile.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 2,
        ) {
            kinds.forEach { tile(it, Modifier.weight(1f))() }
            if (kinds.size % 2 == 1) Spacer(Modifier.weight(1f))
        }
        // The caption and the control get a row each. Side by side they fit a
        // 412 dp phone only by touching: the caption ends on the pixel the
        // first segment starts.
        Text(
            "Angles as seen by the camera",
            style = MaterialTheme.typography.bodySmall,
            color = ShuttlTheme.extended.textTertiary,
            modifier = Modifier.padding(top = 16.dp),
        )
        ShuttlPillTabs(
            labels = RACKET_ARMS.map { it.second },
            selectedIndex = RACKET_ARMS.indexOfFirst { it.first == racketArm },
            onSelect = { onRacketArm(RACKET_ARMS[it].first) },
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = ShuttlTheme.extended.textTertiary,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * One page of the collapsed strip: two rows of two tiles, always two rows.
 *
 * A short last page is padded with invisible tiles rather than left short,
 * because the pager takes its height from the page on screen: a one-tile
 * page would be half as tall, and the graph under it would jump up on the
 * swipe to it and back down on the swipe away.
 */
@Composable
private fun TilePage(kinds: List<MetricKind>, tile: @Composable (MetricKind, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (0 until TILES_PER_PAGE).chunked(2).forEach { slots ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                slots.forEach { slot ->
                    val kind = kinds.getOrNull(slot)
                    if (kind != null) {
                        tile(kind, Modifier.weight(1f))
                    } else {
                        Box(Modifier.weight(1f).alpha(0f).clearAndSetSemantics { }) {
                            ShuttlStatTile(value = "0", unit = "", label = "", labelAbove = true)
                        }
                    }
                }
            }
        }
    }
}

/** One dot per page, the current one in the accent. */
@Composable
private fun PageDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    ),
            )
        }
    }
}
