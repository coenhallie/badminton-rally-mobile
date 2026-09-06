package com.badmintontracker.android.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoreMatchCard
import com.badmintontracker.shared.scoring.ScoreTagSummary
import com.badmintontracker.shared.scoring.ScoredPoint
import com.badmintontracker.shared.scoring.Side

/**
 * The points half of a match: the score header, the tag tally and one row per
 * point, newest first. A `LazyListScope` extension rather than a screen, for the
 * same reason [com.badmintontracker.android.match.ralliesFacet] is - the match page
 * renders whichever facet is selected inside one shared scroll container.
 *
 * Lifted from `ScoreMatchScreen` unchanged: same header, same tally gate, same
 * newest-first point list. The Score/Resume button, not [onScore] itself, is
 * omitted once there is nothing left to score - absent rather than disabled,
 * because undo lives on the board itself.
 */
fun LazyListScope.pointsFacet(
    log: ScoreLog,
    card: ScoreMatchCard?,
    tally: ScoreTagSummary,
    points: List<ScoredPoint>,
    onScore: () -> Unit,
) {
    item(key = "points-header") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = card?.scoreLine.orEmpty(),
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(card?.playersLine.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${card?.statusLine.orEmpty()} · ${log.rules.summary()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Absent rather than disabled on a finished match: there is nothing
            // left to score, and undo lives on the board itself.
            if (log.status == ScoreLogStatus.LIVE) {
                Button(onClick = onScore, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (points.isEmpty()) "Score" else "Resume scoring")
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

    if (points.isEmpty()) {
        item(key = "no-points") {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No points scored yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        // Newest first: courtside, the rally you care about is the last one.
        items(points.reversed(), key = { "point-${it.ordinal}" }) { point ->
            PointRow(
                point = point,
                homeName = log.homePlayers.joinToString(" / "),
                awayName = log.awayPlayers.joinToString(" / "),
            )
            HorizontalDivider()
        }
    }
}

/**
 * How the match was tagged courtside, in the rally page's visual language so the
 * two summaries read as the same thing. Absent rather than empty when nothing was
 * tagged: the point list below already says the match has barely started.
 */
@Composable
private fun TagTally(tally: ScoreTagSummary) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (tally.taggedPointCount == 1) "1 rally tagged"
                   else "${tally.taggedPointCount} rallies tagged",
            style = MaterialTheme.typography.titleSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            tally.labels.forEach { LabelCountChip(it) }
        }
    }
}

@Composable
private fun PointRow(point: ScoredPoint, homeName: String, awayName: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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
                text = if (point.wonBy == Side.HOME) homeName else awayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (point.tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                point.tags.forEach { LabelBadge(name = it.labelName, colorKey = it.labelColor) }
            }
        }
        point.comment?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** "21 points, best of 3". Rules are shared; this sentence is Android copy. */
private fun com.badmintontracker.shared.scoring.ScoringRules.summary(): String {
    val games = if (gamesToWin == 1) "single game" else "best of ${gamesToWin * 2 - 1}"
    return "$pointsToWin points, $games"
}
