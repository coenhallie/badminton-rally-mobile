package com.badmintontracker.android.scoring

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.scoring.FinishPrompt
import com.badmintontracker.shared.scoring.MAX_MATCH_TITLE
import com.badmintontracker.shared.scoring.MatchState
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ServiceCourt
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.scoring.isPlayable
import com.badmintontracker.shared.scoring.sideName

/**
 * The courtside board. One tap on a side scores the rally it just won; a second tap
 * on a label tags it.
 *
 * Everything on it is read from [MatchState], which is folded from the log - the
 * serve, the service court, and which end each side is on. Nothing here computes a rule.
 */
@Composable
fun ScoringScreen(
    vm: ScoringViewModel,
    onBack: () -> Unit,
    onFinished: (AttachIntent?) -> Unit = { },
) {
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
    var editingDetails by remember { mutableStateOf(false) }
    var editingScore by remember { mutableStateOf(false) }

    // Fires on the transition to un-scoreable, from either exit: the last point
    // the rules allow, and "Finish match" in the overflow. The rule is shared
    // with iOS; see FinishPrompt for why a board opened on a match that is
    // ALREADY over must not ask.
    val finishPrompt = remember { FinishPrompt(playableOnEntry = log.isPlayable()) }
    var addVideoOpen by remember { mutableStateOf(false) }
    LaunchedEffect(match.isOver, log.status) {
        if (finishPrompt.shouldAsk(log.isPlayable())) addVideoOpen = true
    }

    // Keyed on the notice rather than on its words, so a second undo of the same
    // side's rally gets the full time to be put back too.
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(UNDO_NOTICE_MILLIS)
            vm.dismissNotice()
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        StatusStrip(
            log = log,
            match = match,
            notice = state.noticeMessage,
            noticeAction = state.noticeAction,
            canUndo = state.canUndo,
            onUndo = vm::undo,
            onRedo = vm::reverseNotice,
            onBack = onBack,
            onEditDetails = { editingDetails = true },
            onResetGame = { confirming = Confirmation.ResetGame },
            onFinish = { confirming = Confirmation.Finish },
        )

        // The end each side is playing from, not which side it is. The board turns
        // over with the players so it still matches the court the coach is watching.
        val homeOnLeft = match.endsSwapCount % 2 == 0
        val left = if (homeOnLeft) Side.HOME else Side.AWAY
        val switchServer: (() -> Unit)? = if (state.canSwitchFirstServer) vm::switchFirstServer else null

        Row(Modifier.fillMaxWidth().weight(1f)) {
            SideZone(
                side = left,
                name = log.sideName(left),
                match = match,
                enabled = state.canScore,
                onScore = { vm.score(left) },
                onSwitchServer = switchServer,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            SideZone(
                side = left.other,
                name = log.sideName(left.other),
                match = match,
                enabled = state.canScore,
                onScore = { vm.score(left.other) },
                onSwitchServer = switchServer,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }

        ControlBar(
            match = match,
            labels = state.labels,
            hasAnyLabels = state.hasAnyLabels,
            pendingTagOrdinal = state.pendingTagOrdinal,
            canEditScore = state.canEditScore,
            noteOpen = noteOpen,
            onToggleTag = { ordinal, label -> vm.toggleTag(ordinal, label) },
            onSetComment = { ordinal, text -> vm.setComment(ordinal, text) },
            onToggleNote = { noteOpen = !noteOpen },
            onEditScore = { editingScore = true },
            onDone = { onFinished(null) },
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

    if (addVideoOpen) {
        AlertDialog(
            onDismissRequest = { addVideoOpen = false; onFinished(null) },
            title = { Text("Add the video?") },
            text = {
                Text(
                    "Import or record the video of this match and Shuttl will cut it into " +
                        "one clip per rally. You can also do this later from the match itself.",
                )
            },
            confirmButton = {
                TextButton(onClick = { addVideoOpen = false; onFinished(AttachIntent.Import) }) {
                    Text("Import video")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { addVideoOpen = false; onFinished(AttachIntent.Record) }) {
                        Text("Record")
                    }
                    TextButton(onClick = { addVideoOpen = false; onFinished(null) }) { Text("Not now") }
                }
            },
        )
    }

    if (editingScore) {
        ScoreEditSheet(
            log = log,
            match = match,
            onSave = { home, away, server ->
                vm.editScore(home, away, server)
                editingScore = false
            },
            onDismiss = { editingScore = false },
        )
    }

    if (editingDetails) {
        EditMatchDetailsDialog(
            initial = log.detailsDraft(),
            titleCap = MAX_MATCH_TITLE,
            onDismiss = { editingDetails = false },
            onSave = { draft ->
                vm.updateDetails(draft.title, draft.format, draft.homePlayers, draft.awayPlayers)
                editingDetails = false
            },
        )
    }
}

/**
 * How long "Point to Home undone" and its Redo stay up. Long enough to notice a
 * mis-tapped undo from the court and reach back for it, short enough that the
 * strip is back to calling game point before the next rally is over.
 */
private const val UNDO_NOTICE_MILLIS = 5_000L

/** Where finishing a match can take the coach next, chosen on the "add the video?" prompt. */
enum class AttachIntent { Import, Record }

/** The two irreversible controls, both behind a confirm. Undo and the score editor are not among them. */
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
 *
 * An undo outranks both for a few seconds. It is the one thing the coach did that
 * the board cannot show on its own - the number going down does not say whose
 * rally went - and it is where Redo lives, clear of the halves that score.
 */
@Composable
private fun StatusStrip(
    log: ScoreLog,
    match: MatchState,
    notice: String?,
    noticeAction: String?,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBack: () -> Unit,
    onEditDetails: () -> Unit,
    onResetGame: () -> Unit,
    onFinish: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val announcement = notice ?: announcementFor(log, match)

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
                modifier = Modifier
                    .weight(1f)
                    // Read out as it changes, so a screen reader hears the undo
                    // notice (and the game point calls) without moving focus.
                    .semantics { liveRegion = LiveRegionMode.Polite },
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (announcement != null) FontWeight.Bold else FontWeight.Normal,
                color =
                    if (announcement != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (notice != null) {
                TextButton(onClick = onRedo) { Text(noticeAction.orEmpty()) }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Match options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // Here rather than on the bar since the score editor took its
                    // place: the editor only reaches the game being played, and a
                    // mis-tap that ends a game has already moved the board on to
                    // the next one. Its notice and Redo still come up in the strip.
                    DropdownMenuItem(
                        text = { Text("Undo last rally") },
                        enabled = canUndo,
                        onClick = { menuOpen = false; onUndo() },
                    )
                    // Names and format are not asked for when the match is created,
                    // so courtside, between games, is one of the places they get
                    // filled in. Never disabled: it does not touch the score.
                    DropdownMenuItem(
                        text = { Text("Edit details") },
                        onClick = { menuOpen = false; onEditDetails() },
                    )
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
    /** The side's players, or "Home" / "Away" while nobody has been named. */
    name: String,
    match: MatchState,
    enabled: Boolean,
    onScore: () -> Unit,
    /** Null once the first serve is settled. */
    onSwitchServer: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val background =
        if (side == Side.HOME) ShuttlTheme.extended.sideHome else ShuttlTheme.extended.sideAway
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
                    text = name,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                // Held rather than hidden on the receiving side, so the games and
                // the score below line up across the two halves instead of stepping
                // down on whichever side happens to be serving.
                ServePill(
                    court = match.serviceCourt,
                    // Only the visible pill: the receiving side's is drawn at zero
                    // alpha to hold the layout, and must not take taps it cannot show.
                    onSwitch = onSwitchServer.takeIf { serving },
                    modifier = Modifier.alpha(if (serving) 1f else 0f),
                )

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
 * The side itself carries the serve and its court, there being no player to mark.
 *
 * [onSwitch] is non-null only until the first point is scored. The form does not ask
 * who won the toss, so a new match opens with home serving, and this is where that
 * is corrected: the pill grows a swap icon and hands the serve across when tapped.
 * It sits inside the half that scores on a tap, so its own tap has to be claimed
 * here or switching the serve would also score a point.
 */
@Composable
private fun ServePill(court: ServiceCourt?, onSwitch: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .then(
                if (onSwitch == null) Modifier
                else Modifier.clickable(onClickLabel = "Switch who serves first", onClick = onSwitch)
            ),
        shape = RoundedCornerShape(ShuttlRadius.extraSmall),
        color = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onSwitch != null) {
                // Decorative: the click label on the pill already says what it does.
                Icon(ShuttlIcons.Swap, contentDescription = null, tint = Color.Black, modifier = Modifier.size(12.dp))
            }
            Text(
                text = "SERVE" + when (court) {
                    ServiceCourt.RIGHT -> " R"
                    ServiceCourt.LEFT -> " L"
                    null -> ""
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
            )
        }
    }
}

@Composable
private fun GamesWonBox(games: Int) {
    Surface(
        shape = RoundedCornerShape(ShuttlRadius.extraSmall),
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
 * The tag row, the note field, and the fixed action row. The tag row is on
 * screen before the rally it will tag, which is the whole reason tagging costs
 * the coach one tap rather than a dialog.
 *
 * The chips wrap rather than scroll. Only labels scoped to the board reach here
 * (see LabelUsage), so the set is small by construction - and when the coach
 * scopes one more than fits, the cost is a line of board height he can see,
 * not a chip hidden off the right edge. Note sits on the action row below,
 * outside the wrapping area: it used to be the last chip in a scrolling row,
 * which is exactly why it became unreachable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlBar(
    match: MatchState,
    labels: List<AnnotationLabel>,
    hasAnyLabels: Boolean,
    pendingTagOrdinal: Int?,
    canEditScore: Boolean,
    noteOpen: Boolean,
    onToggleTag: (Int, AnnotationLabel) -> Unit,
    onSetComment: (Int, String) -> Unit,
    onToggleNote: () -> Unit,
    onEditScore: () -> Unit,
    onDone: () -> Unit,
) {
    val point = pendingTagOrdinal?.let { match.points.getOrNull(it) }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (labels.isEmpty()) {
                Text(
                    // Two different problems, and one sentence used to cover
                    // both: an account with no labels at all, and an account
                    // whose labels are all scoped to clips. The second would
                    // otherwise claim there are no labels while the Labels
                    // screen plainly shows several.
                    text =
                        if (hasAnyLabels) "No board labels - choose them on the labels screen."
                        else "No labels yet - add them on the labels screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    labels.forEach { label ->
                        TagChip(
                            label = label,
                            selected = point?.tags?.any { it.labelName == label.name } == true,
                            enabled = point != null,
                            onClick = { pendingTagOrdinal?.let { onToggleTag(it, label) } },
                        )
                    }
                }
            }

            if (noteOpen && point != null) {
                val ordinal = point.ordinal
                val original = remember(ordinal) { point.comment.orEmpty() }
                var draft by remember(ordinal) { mutableStateOf(original) }
                // Committed once, when the field goes away - the note moves to
                // another rally, the row closes, or the screen leaves. Committing
                // per keystroke would append one log entry per character to a log
                // that is stored and synced whole.
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

            // Wraps rather than squeezes. At the largest font a finished match
            // has Edit score, Note and Done on this row, and together they are
            // wider than the phone: in a Row, Done was the one squeezed, down to
            // "Don / e". Here it drops to a line of its own, at the right.
            //
            // Each item is centred on its line by its own `align`, not by
            // FlowRow's `itemVerticalAlignment`: that parameter arrived in
            // Compose Foundation 1.8, and the release build resolves the BOM's
            // 1.7.6 (only the debug classpath is pulled up to 1.9), so it broke
            // every release build.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Outlined and filled rather than a text button like Note: this is
                // the control reached for in a hurry after a mis-tap or a run of
                // unscored rallies, so it has to read as a button at a glance.
                OutlinedButton(
                    onClick = onEditScore,
                    modifier = Modifier.align(Alignment.CenterVertically),
                    enabled = canEditScore,
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    // Filled like ShuttlButton's Secondary: the outline alone all
                    // but disappears against the dark theme's bar.
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = ShuttlTheme.extended.bgTertiary,
                        disabledContainerColor = ShuttlTheme.extended.bgTertiary.copy(alpha = 0.5f),
                    ),
                ) {
                    Icon(
                        ShuttlIcons.Pencil,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Edit score")
                }
                Text(
                    text = tagRowCaption(match, pendingTagOrdinal),
                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Two lines: the opening hint does not fit beside the Edit
                    // score button on a narrow phone, and cut short it loses "server".
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = onToggleNote,
                    modifier = Modifier.align(Alignment.CenterVertically),
                    enabled = point != null,
                ) { Text("Note") }
                // Only once the match is actually over. While it is live, finishing
                // lives in the overflow behind a confirm: a call to action sitting
                // beside a board being tapped every rally is a match ended by accident.
                if (match.isOver) {
                    Button(onClick = onDone, modifier = Modifier.align(Alignment.CenterVertically)) { Text("Done") }
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
    val point = pendingTagOrdinal?.let { match.points.getOrNull(it) }
        ?: return if (match.points.isEmpty()) "Tap a side to score, or SERVE to switch server" else "Tap a side to score"
    return "Point ${point.ordinal + 1}: ${point.scoreAfter.home}-${point.scoreAfter.away}"
}

/**
 * The announcement the point just played earned, or null. Order is by how much it
 * matters courtside, and match point outranks game point because it is one.
 */
private fun announcementFor(log: ScoreLog, match: MatchState): String? {
    val name = { side: Side -> log.sideName(side) }
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
