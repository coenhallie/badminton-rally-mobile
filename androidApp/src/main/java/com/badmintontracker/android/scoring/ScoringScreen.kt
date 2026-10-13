package com.badmintontracker.android.scoring

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.launch
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.scoring.BoardTarget
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
 * on a label tags it. Before the first rally, or whenever else the row has no rally
 * to tag, a label tap logs a moment instead.
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

    // Keyed on the notice rather than its words, so a second notice with the same
    // words still gets its full time.
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(NOTICE_MILLIS)
            vm.dismissNotice()
        }
    }
    // A rally note belongs to a rally. When the row stops pointing at one, the
    // field closes rather than reopening on the next rally scored.
    LaunchedEffect(state.target is BoardTarget.Moments) {
        if (state.target is BoardTarget.Moments) noteOpen = false
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        StatusStrip(
            log = log,
            match = match,
            notice = state.noticeMessage,
            noticeAction = state.noticeAction,
            onNoticeAction = vm::reverseNotice,
            canUndo = state.canUndo,
            onUndo = vm::undo,
            canRemoveMoment = state.canRemoveMoment,
            onRemoveMoment = vm::removeLastMoment,
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
            state = state,
            noteOpen = noteOpen,
            onToggleTag = vm::toggleTag,
            onMomentLabel = vm::tapMomentLabel,
            onSetComment = vm::setComment,
            onNote = {
                when {
                    state.target is BoardTarget.Rally -> noteOpen = !noteOpen
                    state.momentDraft == null -> vm.openMomentDraft()
                    else -> vm.commitMomentDraft()
                }
            },
            onDraftText = vm::setMomentDraftText,
            onDraftClose = vm::commitMomentDraft,
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
 * How long a notice and its action stay up: "Point to Home undone" with Redo, and
 * "Moment added" or "Moment removed" with Undo. Long enough to notice a mis-tap
 * from the court and reach back for it, short enough that the strip is back to
 * calling game point before the next rally is over.
 */
private const val NOTICE_MILLIS = 5_000L

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
 * A notice outranks both for a few seconds: an undo, or a moment added or removed.
 * Each is something the coach did that the board cannot show on its own - the
 * number going down does not say whose rally went, and a moment changes nothing
 * else on the board - and it is where the action that reverses it lives, Redo or
 * Undo, clear of the halves that score.
 */
@Composable
private fun StatusStrip(
    log: ScoreLog,
    match: MatchState,
    notice: String?,
    noticeAction: String?,
    onNoticeAction: () -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    canRemoveMoment: Boolean,
    onRemoveMoment: () -> Unit,
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
                    // Read out as it changes, so a screen reader hears the
                    // notices (and the game point calls) without moving focus.
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
            if (notice != null && noticeAction != null) TextButton(onClick = onNoticeAction) { Text(noticeAction) }
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
                    // Only when there is one. Beside Undo last rally rather than
                    // replacing it: after Reset game the row logs moments while
                    // earlier games still have rallies to take back.
                    if (canRemoveMoment) {
                        DropdownMenuItem(
                            text = { Text("Remove last moment") },
                            onClick = { menuOpen = false; onRemoveMoment() },
                        )
                    }
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
 * With no rally to tag, the same chips log moments: one tap logs one, and Note
 * opens a composer whose chips check labels onto a single moment instead.
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
    state: ScoringUiState,
    noteOpen: Boolean,
    onToggleTag: (Int, AnnotationLabel) -> Unit,
    onMomentLabel: (AnnotationLabel) -> Unit,
    onSetComment: (Int, String) -> Unit,
    onNote: () -> Unit,
    onDraftText: (String) -> Unit,
    onDraftClose: () -> Unit,
    onEditScore: () -> Unit,
    onDone: () -> Unit,
) {
    val match = state.match ?: return
    val target = state.target ?: return
    val draft = state.momentDraft
    val point = (target as? BoardTarget.Rally)?.let { match.points.getOrNull(it.ordinal) }
    val haptics = LocalHapticFeedback.current

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.labels.isEmpty()) {
                Text(
                    // Two different problems, and one sentence used to cover
                    // both: an account with no labels at all, and an account
                    // whose labels are all scoped to clips. The second would
                    // otherwise claim there are no labels while the Labels
                    // screen plainly shows several.
                    text =
                        if (state.hasAnyLabels) "No board labels - choose them on the labels screen."
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
                    state.labels.forEach { label ->
                        val quick = target == BoardTarget.Moments && draft == null
                        // Null for a quick moment: that tap is a one-shot action
                        // with nothing to tick, not a toggle that is off.
                        val selected = when {
                            draft != null -> draft.tags.any { it.labelName == label.name }
                            point != null -> point.tags.any { it.labelName == label.name }
                            else -> null
                        }
                        TagChip(
                            label = label,
                            selected = selected,
                            flashOnTap = quick,
                            onClickLabel = when {
                                point != null -> if (selected == true) "Remove tag" else "Tag this rally"
                                draft != null -> if (selected == true) "Remove from this moment" else "Add to this moment"
                                else -> "Log a moment"
                            },
                            onClick = {
                                if (point != null) {
                                    onToggleTag(point.ordinal, label)
                                } else {
                                    // A quick moment changes nothing else on the board,
                                    // so it is felt as well as seen.
                                    if (quick) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onMomentLabel(label)
                                }
                            },
                        )
                    }
                }
            }

            if (draft != null) {
                key(draft.openedAt) {
                    MomentNoteField(onTextChange = onDraftText, onClose = onDraftClose)
                }
            } else if (noteOpen && point != null) {
                val ordinal = point.ordinal
                val original = remember(ordinal) { point.comment.orEmpty() }
                var text by remember(ordinal) { mutableStateOf(original) }
                // Committed once, when the field goes away - the note moves to
                // another rally, the row closes, or the screen leaves. Committing
                // per keystroke would append one log entry per character to a log
                // that is stored and synced whole.
                DisposableEffect(ordinal) {
                    onDispose { if (text != original) onSetComment(ordinal, text) }
                }
                // Focused as it opens. Opening the note is already a deliberate
                // detour from scoring; making the coach tap twice to start typing
                // is the kind of thing that gets the feature abandoned.
                val focus = remember(ordinal) { FocusRequester() }
                LaunchedEffect(ordinal) { focus.requestFocus() }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
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
                    enabled = state.canEditScore,
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
                    text = state.caption,
                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Two lines: the opening hint does not fit beside the Edit
                    // score button on a narrow phone, and cut short it loses "server".
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = onNote,
                    modifier = Modifier.align(Alignment.CenterVertically),
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

/**
 * The composer's note. Its text is pushed to the view model as it is typed, so a
 * score tap can save the moment, but the field reads its own copy: reading text
 * back through a flow is how a cursor ends up jumping.
 */
@Composable
private fun MomentNoteField(onTextChange: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    // Saved once, when the field goes away for any reason.
    DisposableEffect(Unit) { onDispose { onClose() } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onTextChange(it) },
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        singleLine = true,
        placeholder = { Text("Note on this moment") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onClose() }),
    )
}

/**
 * One label on the board. Live whatever the row targets: it tags the rally, checks
 * the label onto the open moment, or logs a moment of its own.
 *
 * The click comes after the clip, so its ripple stays inside the pill; the
 * Surface's own clip comes after anything passed in as its modifier. A quick
 * moment flashes, a press-in and a white wash, and neither changes the chip's
 * size, so the row never reflows under the coach's finger. With animations
 * removed system-wide the press-in goes; the wash and the haptic stay.
 */
@Composable
private fun TagChip(
    label: AnnotationLabel,
    /** Null when the tap is not a toggle, so TalkBack does not call the chip "Not selected". */
    selected: Boolean?,
    flashOnTap: Boolean,
    onClickLabel: String,
    onClick: () -> Unit,
) {
    val swatch = LabelColor.from(label.colorKey)
    val container = swatch?.let { Color(it.background.toInt()) } ?: MaterialTheme.colorScheme.surfaceVariant
    val onContainer = swatch?.let { Color(it.foreground.toInt()) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    val scale = remember { Animatable(1f) }
    val wash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Android's Reduce Motion. Read once, as HeroTickerView reads it: a system
    // setting rather than UI state.
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    val shape = RoundedCornerShape(50)

    Surface(
        shape = shape,
        color = container,
        modifier = Modifier
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(shape)
            .clickable(onClickLabel = onClickLabel) {
                onClick()
                if (flashOnTap) {
                    scope.launch {
                        if (!reduceMotion) {
                            launch { scale.animateTo(0.94f, tween(80)); scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 600f)) }
                        }
                        // The wash is the confirmation that stays, so it keeps its
                        // own time: at a system scale of 0 it would end on the
                        // frame it started and never be seen.
                        launch(if (reduceMotion) FullSpeed else EmptyCoroutineContext) {
                            wash.snapTo(0.32f); wash.animateTo(0f, tween(260))
                        }
                    }
                }
            }
            .then(if (selected == null) Modifier else Modifier.semantics { this.selected = selected }),
    ) {
        Text(
            text = if (selected == true) "✓ ${label.name}" else label.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected == true) FontWeight.Bold else FontWeight.Normal,
            color = onContainer,
            modifier = Modifier
                .drawWithContent {
                    drawContent()
                    drawRect(Color.White.copy(alpha = wash.value))
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            maxLines = 1,
        )
    }
}

/** Animations at their own speed, whatever the system's animator scale is. */
private object FullSpeed : MotionDurationScale {
    override val scaleFactor = 1f
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
