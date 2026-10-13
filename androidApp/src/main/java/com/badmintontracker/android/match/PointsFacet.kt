package com.badmintontracker.android.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintontracker.android.clipdetail.LabelBadge
import com.badmintontracker.android.cliplist.LabelCountChip
import com.badmintontracker.android.ui.components.ShuttlEmptyState
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.shared.scoring.LoggedMoment
import com.badmintontracker.shared.scoring.MatchState
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoreMatchCard
import com.badmintontracker.shared.scoring.ScoreTagSummary
import com.badmintontracker.shared.scoring.ScoredPoint
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.scoring.TimelineEntry
import com.badmintontracker.shared.scoring.clockTime
import com.badmintontracker.shared.scoring.scoreButtonLabel
import com.badmintontracker.shared.scoring.sideName

/**
 * The points half of a match: the score header, the tag tally and one row per
 * rally or moment, newest first. A `LazyListScope` extension rather than a screen, for the
 * same reason [com.badmintontracker.android.match.ralliesFacet] is - the match
 * page's Points section renders it inside a list the page owns.
 *
 * Lifted from `ScoreMatchScreen` with one change: the list is the match's
 * timeline, so a moment sits among the rallies by when it was logged. The
 * Score/Resume button, not [onScore] itself, is omitted once there is nothing
 * left to score - absent rather than disabled, because undo lives on the board
 * itself.
 */
fun LazyListScope.pointsFacet(
    log: ScoreLog,
    card: ScoreMatchCard?,
    tally: ScoreTagSummary,
    timeline: List<TimelineEntry>,
    match: MatchState?,
    onScore: () -> Unit,
) {
    item(key = "points-header") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = MatchPageGutter, end = MatchPageGutter, top = MatchRowInset, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = card?.scoreLine.orEmpty(),
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
            )
            // The legend for the score above it: which number is whose. Always drawn,
            // unlike the list row's players line, so it falls back to Home vs Away.
            Text(
                "${log.sideName(Side.HOME)} vs ${log.sideName(Side.AWAY)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = listOfNotNull(card?.statusLine, log.format?.label, log.rules.summary()).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Absent rather than disabled on a finished match: there is nothing
            // left to score, and undo lives on the board itself.
            if (log.status == ScoreLogStatus.LIVE) {
                Button(onClick = onScore, modifier = Modifier.padding(top = 8.dp)) {
                    Text(match?.let(::scoreButtonLabel) ?: "Score")
                }
            }
        }
        HorizontalDivider()
    }

    if (!tally.isEmpty) {
        item(key = "points-tag-tally") {
            TagTally(tally)
            HorizontalDivider()
        }
    }

    if (timeline.isEmpty()) {
        item(key = "no-points") {
            // No action of its own: the Score button in the header above is
            // the one that fills this list.
            ShuttlEmptyState(
                icon = ShuttlIcons.ListOrdered,
                title = "Nothing logged yet",
                body = "Points you score and moments you log are listed here.",
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
        }
    } else {
        // Newest first: courtside, the rally you care about is the last one.
        items(timeline.asReversed(), key = {
            when (it) {
                is TimelineEntry.Rally -> "point-${it.point.ordinal}"
                is TimelineEntry.Moment -> "moment-${it.moment.id}"
            }
        }) { entry ->
            when (entry) {
                is TimelineEntry.Rally -> PointRow(entry.point, winner = log.sideName(entry.point.wonBy))
                is TimelineEntry.Moment -> MomentRow(entry.moment)
            }
            HorizontalDivider()
        }
    }
}

/**
 * How the match was tagged courtside, in the rally page's visual language so the
 * two summaries read as the same thing. The caller leaves it out when no rally
 * was tagged and no moment logged, rather than drawing a count of nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagTally(tally: ScoreTagSummary) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MatchPageGutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = tally.headline,
            style = MaterialTheme.typography.titleSmall,
        )
        // A match with only note moments has a headline and no labels: an empty
        // row would add a gap under it.
        if (tally.labels.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tally.labels.forEach { LabelCountChip(it) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PointRow(point: ScoredPoint, winner: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MatchPageGutter, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${point.scoreAfter.home}-${point.scoreAfter.away}",
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = winner,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (point.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                point.tags.forEach { LabelBadge(name = it.labelName, colorKey = it.labelColor) }
            }
        }
        point.comment?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * A moment, shaped like a rally's row so the two read as one list: the time where
 * a rally has its score, and "Moment" where it has who won it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MomentRow(moment: LoggedMoment) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MatchPageGutter, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = clockTime(moment.loggedAt), fontWeight = FontWeight.Medium)
            Text(text = "Moment", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (moment.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                moment.tags.forEach { LabelBadge(name = it.labelName, colorKey = it.labelColor) }
            }
        }
        moment.comment?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** "21 points, best of 3". Rules are shared; this sentence is Android copy. */
private fun com.badmintontracker.shared.scoring.ScoringRules.summary(): String {
    val games = if (gamesToWin == 1) "single game" else "best of ${gamesToWin * 2 - 1}"
    return "$pointsToWin points, $games"
}
