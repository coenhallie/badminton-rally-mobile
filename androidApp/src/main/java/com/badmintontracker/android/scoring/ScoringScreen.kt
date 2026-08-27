package com.badmintontracker.android.scoring

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.scoring.MatchState
import com.badmintontracker.shared.scoring.PairPlayer
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ServiceCourt
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.scoring.rightCourtPlayer

/**
 * The courtside board. One tap on a side scores the rally it just won; a second tap
 * on a label tags it.
 *
 * Everything on it is read from [MatchState], which is folded from the log - the
 * serve, the service court, who stands where in a doubles pair, and which end each
 * side is on. Nothing here computes a rule.
 */
@Composable
fun ScoringScreen(vm: ScoringViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    KeepScreenOn()

    val log = state.log
    val match = state.match
    if (log == null || match == null) {
        // Swiped away in the match list while this screen was open.
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("This match is no longer on this phone.")
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
        return
    }

    var confirming by remember { mutableStateOf<Confirmation?>(null) }
    var noteOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        StatusStrip(
            log = log,
            match = match,
            onBack = onBack,
            onResetGame = { confirming = Confirmation.ResetGame },
            onFinish = { confirming = Confirmation.Finish },
        )

        // The end each side is playing from, not which side it is. The board turns
        // over with the players so it still matches the court the coach is watching.
        val homeOnLeft = match.endsSwapCount % 2 == 0
        val left = if (homeOnLeft) Side.HOME else Side.AWAY

        Row(Modifier.fillMaxWidth().weight(1f)) {
            SideZone(
                side = left,
                log = log,
                match = match,
                enabled = state.canScore,
                onScore = { vm.score(left) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            SideZone(
                side = left.other,
                log = log,
                match = match,
                enabled = state.canScore,
                onScore = { vm.score(left.other) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }

        ControlBar(
            match = match,
            labels = state.labels,
            pendingTagOrdinal = state.pendingTagOrdinal,
            canUndo = state.canUndo,
            noteOpen = noteOpen,
            onToggleTag = { ordinal, label -> vm.toggleTag(ordinal, label) },
            onSetComment = { ordinal, text -> vm.setComment(ordinal, text) },
            onToggleNote = { noteOpen = !noteOpen },
            onUndo = { vm.undo() },
            onDone = onBack,
        )
    }

    confirming?.let { pending ->
        val dismiss = { confirming = null }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(pending.title) },
            text = { Text(pending.body) },
            confirmButton = {
                TextButton(onClick = {
                    when (pending) {
                        Confirmation.ResetGame -> vm.resetCurrentGame()
                        Confirmation.Finish -> vm.finish()
                    }
                    dismiss()
                }) { Text(pending.confirm) }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )
    }
}

/** The two irreversible controls, both behind a confirm. Undo is not one of them. */
private enum class Confirmation(val title: String, val body: String, val confirm: String) {
    ResetGame(
        title = "Reset this game?",
        body = "Every point of the game being played is removed. Games already finished are left alone.",
        confirm = "Reset game",
    ),
    Finish(
        title = "Finish this match?",
        body = "The match is closed at the current score. You can still undo afterwards.",
        confirm = "Finish match",
    ),
}

/**
 * The one line the coach glances up at: what just happened, or where the match is.
 * An announcement outranks the running summary, because "match point" is the only
 * thing on this strip worth looking away from the court for.
 */
@Composable
private fun StatusStrip(
    log: ScoreLog,
    match: MatchState,
    onBack: () -> Unit,
    onResetGame: () -> Unit,
    onFinish: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val announcement = announcementFor(log, match)

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = announcement ?: runningSummary(log, match),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (announcement != null) FontWeight.Bold else FontWeight.Normal,
                color =
                    if (announcement != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Match options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Reset game") },
                        enabled = !match.isOver,
                        onClick = { menuOpen = false; onResetGame() },
                    )
                    DropdownMenuItem(
                        text = { Text("Finish match") },
                        enabled = !match.isOver,
                        onClick = { menuOpen = false; onFinish() },
                    )
                }
            }
        }
    }
}

/**
 * One half of the board, and the whole scoring control: the tap target is the half
 * itself rather than a button on it, because a button courtside is a thing to aim at.
 */
@Composable
private fun SideZone(
    side: Side,
    log: ScoreLog,
    match: MatchState,
    enabled: Boolean,
    onScore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val background =
        if (side == Side.HOME) ShuttlTheme.extended.sideHome else ShuttlTheme.extended.sideAway
    val players = if (side == Side.HOME) log.homePlayers else log.awayPlayers
    val serving = match.server == side

    BoxWithConstraints(
        modifier = modifier
            .background(background)
            .clickable(enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onScore()
            },
    ) {
        // Sized off the half it has to fill, because "readable at arm's length" is
        // the one hard requirement on this layout and a fixed sp cannot meet it on
        // both a tall portrait half and a wide landscape one. The width factor
        // budgets for two digits at every score, so 9 and 30 are drawn the same size.
        // Converted through the density rather than used as a raw sp value: the
        // container is measured in dp and does not grow with the reader's font
        // setting, so an sp numeral would overflow the half it was sized to fit.
        // minSdk is 26, and scaling only stopped being linear for large text in
        // API 34. Every other string on this screen still scales normally.
        val numeral = with(LocalDensity.current) {
            minOf(maxHeight.value * 0.40f, maxWidth.value * 0.75f).dp.toSp()
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = players.joinToString(" / "),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                if (match.setup.doubles) {
                    PlayerChips(side = side, players = players, match = match)
                } else {
                    // Held rather than hidden on the receiving side, so the games
                    // and the score below line up across the two halves instead of
                    // stepping down on whichever side happens to be serving.
                    ServePill(
                        court = match.serviceCourt,
                        modifier = Modifier.alpha(if (serving) 1f else 0f),
                    )
                }

                if (match.rules.gamesToWin > 1) {
                    GamesWonBox(match.gamesWon.of(side))
                }
            }

            // The score takes whatever the names left, centred in it. Anchoring the
            // names to the top instead of centring the whole stack keeps the numeral
            // in the same place all match, however long the names are.
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = "${match.currentGame.of(side)}",
                    color = Color.White,
                    fontSize = numeral,
                    lineHeight = numeral * 1.05f,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Both names of a pair, each marked with the service court it is standing in, and
 * the one about to serve filled in. All three facts come from the fold.
 */
@Composable
private fun PlayerChips(side: Side, players: List<String>, match: MatchState) {
    val rightCourt = match.rightCourtPlayer(side)
    val serving = if (match.server == side) match.servingPlayer else null

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(PairPlayer.FIRST, PairPlayer.SECOND).forEachIndexed { index, member ->
            val name = players.getOrNull(index) ?: return@forEachIndexed
            val court = when (rightCourt) {
                null -> null
                member -> ServiceCourt.RIGHT
                else -> ServiceCourt.LEFT
            }
            PlayerChip(name = name, court = court, isServing = member == serving)
        }
    }
}

@Composable
private fun PlayerChip(name: String, court: ServiceCourt?, isServing: Boolean) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isServing) Color.White else Color.White.copy(alpha = 0.14f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (court != null) {
                Text(
                    text = if (court == ServiceCourt.RIGHT) "R" else "L",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isServing) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.6f),
                )
            }
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isServing) Color.Black else Color.White,
            )
        }
    }
}

/** Singles has no player to mark, so the side itself carries the serve and its court. */
@Composable
private fun ServePill(court: ServiceCourt?, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(6.dp), color = Color.White) {
        Text(
            text = "SERVE" + when (court) {
                ServiceCourt.RIGHT -> " R"
                ServiceCourt.LEFT -> " L"
                null -> ""
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
        )
    }
}

@Composable
private fun GamesWonBox(games: Int) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color.White.copy(alpha = 0.16f),
        modifier = Modifier.size(width = 40.dp, height = 34.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "$games",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * The tag row and Undo. The row is on screen before the rally it will tag, which is
 * the whole reason tagging costs the coach one tap rather than a dialog.
 */
@Composable
private fun ControlBar(
    match: MatchState,
    labels: List<AnnotationLabel>,
    pendingTagOrdinal: Int?,
    canUndo: Boolean,
    noteOpen: Boolean,
    onToggleTag: (Int, AnnotationLabel) -> Unit,
    onSetComment: (Int, String) -> Unit,
    onToggleNote: () -> Unit,
    onUndo: () -> Unit,
    onDone: () -> Unit,
) {
    val point = pendingTagOrdinal?.let { match.points.getOrNull(it) }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                labels.forEach { label ->
                    TagChip(
                        label = label,
                        selected = point?.tags?.any { it.labelName == label.name } == true,
                        enabled = point != null,
                        onClick = { pendingTagOrdinal?.let { onToggleTag(it, label) } },
                    )
                }
                if (labels.isEmpty()) {
                    Text(
                        "No labels yet - add them on the labels screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onToggleNote, enabled = point != null) { Text("Note") }
                Spacer(Modifier.width(4.dp))
            }

            if (noteOpen && point != null) {
                val ordinal = point.ordinal
                val original = remember(ordinal) { point.comment.orEmpty() }
                var draft by remember(ordinal) { mutableStateOf(original) }
                // Committed once, when the field goes away - the note moves to
                // another rally, the row closes, or the screen leaves. Committing
                // per keystroke would append one log entry per character, and undo
                // is defined as dropping the last entry, so it would then take a
                // note back a letter at a time instead of taking back the rally.
                DisposableEffect(ordinal) {
                    onDispose { if (draft != original) onSetComment(ordinal, draft) }
                }
                // Focused as it opens. Opening the note is already a deliberate
                // detour from scoring; making the coach tap twice to start typing
                // is the kind of thing that gets the feature abandoned.
                val focus = remember(ordinal) { FocusRequester() }
                LaunchedEffect(ordinal) { focus.requestFocus() }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    singleLine = true,
                    placeholder = { Text("Note on this rally") },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onUndo, enabled = canUndo) { Text("Undo") }
                Text(
                    text = tagRowCaption(match, pendingTagOrdinal),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                // Only once the match is actually over. While it is live, finishing
                // lives in the overflow behind a confirm: a call to action sitting
                // beside a board being tapped every rally is a match ended by accident.
                if (match.isOver) {
                    Button(onClick = onDone) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun TagChip(
    label: AnnotationLabel,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val swatch = LabelColor.from(label.colorKey)
    val container = swatch?.let { Color(it.background.toInt()) }
        ?: MaterialTheme.colorScheme.surfaceVariant
    val onContainer = swatch?.let { Color(it.foreground.toInt()) }
        ?: MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(50),
        // Greyed and inert until a rally exists to put it on, rather than hidden:
        // the row has to occupy its space before the point is scored, or it would
        // shove the board around every time one is.
        color = if (enabled) container else container.copy(alpha = 0.30f),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = if (selected) "✓ ${label.name}" else label.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (enabled) onContainer else onContainer.copy(alpha = 0.45f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            maxLines = 1,
        )
    }
}

/** What the tag row is pointing at, so a tap on a chip is never a guess. */
private fun tagRowCaption(match: MatchState, pendingTagOrdinal: Int?): String {
    if (match.isOver) return "Match over"
    val point = pendingTagOrdinal?.let { match.points.getOrNull(it) } ?: return "Tap a side to score"
    return "Point ${point.ordinal + 1}: ${point.scoreAfter.home}-${point.scoreAfter.away}"
}

/**
 * The announcement the point just played earned, or null. Order is by how much it
 * matters courtside, and match point outranks game point because it is one.
 */
private fun announcementFor(log: ScoreLog, match: MatchState): String? {
    val name = { side: Side ->
        (if (side == Side.HOME) log.homePlayers else log.awayPlayers).joinToString(" / ")
    }
    val both = { flags: com.badmintontracker.shared.scoring.SideFlags, what: String ->
        when {
            flags.home && flags.away -> "$what - both"
            flags.home -> "$what - ${name(Side.HOME)}"
            else -> "$what - ${name(Side.AWAY)}"
        }
    }
    return when {
        match.winner != null -> "${name(match.winner!!)} won"
        match.matchPoint.any -> both(match.matchPoint, "Match point")
        match.gamePoint.any -> both(match.gamePoint, "Game point")
        match.isChangeEndsPoint -> "Change ends"
        match.isIntervalPoint -> "Interval"
        else -> null
    }
}

/** Games already finished, or the match's name while the first one is still on. */
private fun runningSummary(log: ScoreLog, match: MatchState): String =
    if (match.completedGames.isEmpty()) log.title
    else match.completedGames.joinToString(", ") { "${it.home}-${it.away}" }

/**
 * Keeps the display up for as long as the board is on screen.
 *
 * The view's own flag rather than the window flag [com.badmintontracker.android.MainActivity]
 * sets for uploads: they are independent, so scoring through an upload and then
 * leaving cannot clear the flag the upload is relying on.
 */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
