package com.badmintontracker.android.scoring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.clipdetail.LabelBadge
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoredPoint
import com.badmintontracker.shared.scoring.Side

/**
 * One scored match: who played, how it went, and every point in order.
 *
 * The record rather than the board: this is where a match is read back afterwards.
 * "No points scored yet" is a real state, not a loading one - a match created and
 * not yet started looks exactly like this, and its primary action says "Score".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreMatchScreen(vm: ScoreMatchViewModel, onBack: () -> Unit, onScore: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val log = state.log

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(log?.title ?: "Match", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (log == null) {
            // The match was removed from the list while this page was open.
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("This match is no longer on this phone.")
            }
            return@Scaffold
        }

        val points = state.match?.points.orEmpty()
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = state.card?.scoreLine.orEmpty(),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(state.card?.playersLine.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "${state.card?.statusLine.orEmpty()} · ${log.rules.summary()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Absent rather than disabled on a finished match: there is
                    // nothing left to score, and undo lives on the board itself.
                    if (log.status == ScoreLogStatus.LIVE) {
                        Button(onClick = onScore, modifier = Modifier.padding(top = 8.dp)) {
                            Text(if (points.isEmpty()) "Score" else "Resume scoring")
                        }
                    }
                }
                HorizontalDivider()
            }

            if (points.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No points scored yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                // Newest first: courtside, the rally you care about is the last one.
                items(points.reversed(), key = { it.ordinal }) { point ->
                    PointRow(point = point, homeName = log.homePlayers.joinToString(" / "),
                        awayName = log.awayPlayers.joinToString(" / "))
                    HorizontalDivider()
                }
            }
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
