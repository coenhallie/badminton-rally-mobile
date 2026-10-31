package com.badmintontracker.android.scoring

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.badmintontracker.android.players.AddPlayer
import com.badmintontracker.android.players.DetailsPlayersDraftSaver
import com.badmintontracker.android.players.PlayerPicker
import com.badmintontracker.android.players.PlayersNote
import com.badmintontracker.android.players.WhoIsPlayingSection
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlOutlinedTextField
import com.badmintontracker.android.ui.components.ShuttlPillTabs
import com.badmintontracker.shared.localvideo.MAX_MATCH_DESCRIPTION_LENGTH
import com.badmintontracker.shared.players.DetailsPlayersDraft
import com.badmintontracker.shared.players.MatchPlayers
import com.badmintontracker.shared.players.Player
import com.badmintontracker.shared.players.PlayersCopy
import com.badmintontracker.shared.players.SidesDraft
import com.badmintontracker.shared.players.matchPlayersProblem
import com.badmintontracker.shared.players.offersPairs
import com.badmintontracker.shared.players.showsName
import com.badmintontracker.shared.players.toAssignment
import com.badmintontracker.shared.players.withFormat
import com.badmintontracker.shared.players.withName
import com.badmintontracker.shared.players.withPlayer
import com.badmintontracker.shared.players.withSomeoneElse
import com.badmintontracker.shared.scoring.MAX_MATCH_TITLE
import com.badmintontracker.shared.scoring.MAX_PLAYER_NAME
import com.badmintontracker.shared.scoring.MatchFormat
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.scoring.VIDEO_MATCH_TITLE_CAP
import com.badmintontracker.shared.scoring.matchDetailsProblem
import com.badmintontracker.shared.scoring.playersPerSide

/**
 * What the editor hands back: the name a match was given, what kind of match it
 * was, who played and which side was nearest the camera. [description] is null for
 * a record that has no such field (a score log). [players] is what is saved, from
 * the shared draft (DetailsPlayersDraft.toAssignment): cut to the format, a profile
 * side with no names.
 */
data class MatchDetailsDraft(
    val title: String,
    val description: String?,
    val format: MatchFormat?,
    val players: MatchPlayers,
    val nearSide: Side?,
)

/**
 * Everything about a match that the new match form deliberately does not ask for:
 * who played and what kind of match it was, plus the name it was given. All of it is
 * optional but the name, and none of it changes the score.
 *
 * One editor for both kinds of match. A scored match keeps these on its score log
 * and a filmed one on its videos row, which is the caller's business: the fields
 * and the rules are the same, except for the name cap, which is the record's own
 * ([MAX_MATCH_TITLE] against [VIDEO_MATCH_TITLE_CAP]), the description, which
 * only a video has ([initialDescription] null for a log), and who played
 * ([initialPlayers]: scoreLogPlayersDraft or videoPlayersDraft).
 *
 * Who played:
 * - A score log: Home and Away, each a picker of the players, "Someone else" (the
 *   name field) and "Add player"; "Home was at the" Near | Far | Not set when
 *   [showsEnds], a video being attached.
 * - A match born as a video: the Who's playing section, by court end.
 * - Doubles or mixed, either kind: today's typed names, two a side, no pickers.
 *   Doubles and Mixed are not offered while anyone on the match is a profile, and a
 *   line says to remove the players first.
 *
 * [uploading] is the video mid-upload: the ends row, or the whole section, shows
 * read-only with the sentence saying it can change once the upload finishes.
 *
 * A full-screen dialog rather than a bottom sheet or a route. Not a sheet, because a
 * sheet is drawn on surfaceVariant, which is also the pill track's colour, so the
 * format picker would lose its track; and because the fields and a keyboard do not
 * fit in one. Not a route, because both the match page and the board open it, and a
 * dialog needs no navigation from either.
 *
 * Every rule comes from shared: [matchDetailsProblem] for the name and the typed
 * names, matchPlayersProblem for who played, and DetailsPlayersDraft (with the
 * SidesDraft and EndsDraft it holds) for what is saved, what is offered and what a
 * format switch carries. This only renders the draft and hands taps back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMatchDetailsDialog(
    initialTitle: String,
    initialDescription: String?,
    initialFormat: MatchFormat?,
    initialPlayers: DetailsPlayersDraft,
    titleCap: Int,
    players: List<Player>,
    onAddPlayer: AddPlayer,
    onDismiss: () -> Unit,
    onSave: (MatchDetailsDraft) -> Unit,
    showsEnds: Boolean = false,
    uploading: Boolean = false,
) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var description by rememberSaveable { mutableStateOf(initialDescription.orEmpty()) }
    // An index rather than the enum so it saves without a Saver. 0 is "not set".
    var formatIndex by rememberSaveable { mutableStateOf(initialFormat?.let { it.ordinal + 1 } ?: 0) }
    var who by rememberSaveable(stateSaver = DetailsPlayersDraftSaver) { mutableStateOf(initialPlayers) }

    val format = MatchFormat.entries.getOrNull(formatIndex - 1)
    val pairs = playersPerSide(format) == 2
    val (assignment, nearSide) = who.toAssignment(format)
    val offersPairs = who.offersPairs(format)
    val problem = matchDetailsProblem(title, assignment.homePlayers, assignment.awayPlayers, titleCap)
        ?: matchPlayersProblem(assignment, format)
    val sides = who.sides

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        StatusBarIconsFollowTheme()
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("Match details") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close without saving")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(4.dp))

                ShuttlOutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = "Match name",
                    maxLength = titleCap,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Only a video carries one: it was typed at upload, and a name that
                // can be changed beside a note that cannot would be a stuck field.
                if (initialDescription != null) {
                    ShuttlOutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = "Description",
                        singleLine = false,
                        minLines = 3,
                        maxLength = MAX_MATCH_DESCRIPTION_LENGTH,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SectionLabel("Format")
                ShuttlPillTabs(
                    labels = listOf("Not set") + MatchFormat.entries.map { it.shortLabel },
                    selectedIndex = formatIndex,
                    onSelect = { index ->
                        formatIndex = index
                        who = who.withFormat(MatchFormat.entries.getOrNull(index - 1))
                    },
                    disabledIndices = if (offersPairs) emptySet() else PAIRS_FORMAT_INDICES,
                )
                if (!offersPairs) PlayersNote(PlayersCopy.DOUBLES_NOTE)

                val ends = who.ends
                when {
                    // Today's names, two a side: no pickers outside singles.
                    pairs -> DoublesFields(sides) { side, index, name -> who = who.copy(sides = sides.withName(side, index, name)) }
                    who.byEnds -> if (ends != null) {
                        WhoIsPlayingSection(
                            draft = ends,
                            players = players,
                            onChange = { who = who.copy(ends = it) },
                            onAddPlayer = onAddPlayer,
                            uploading = uploading,
                        )
                    }
                    else -> {
                        SideRow(Side.HOME, sides, players, onAddPlayer) { who = who.copy(sides = it) }
                        SideRow(Side.AWAY, sides, players, onAddPlayer) { who = who.copy(sides = it) }
                    }
                }

                if (!who.byEnds && showsEnds) {
                    SectionLabel(PlayersCopy.HOME_WAS_AT_THE)
                    ShuttlPillTabs(
                        labels = END_OPTIONS.map(::endLabel),
                        selectedIndex = END_OPTIONS.indexOf(sides.nearSide),
                        onSelect = { who = who.copy(sides = sides.copy(nearSide = END_OPTIONS[it])) },
                        groupLabel = PlayersCopy.HOME_WAS_AT_THE,
                        enabled = !uploading,
                    )
                    if (uploading) PlayersNote(PlayersCopy.UPLOAD_IN_PROGRESS)
                }

                problem?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                ShuttlButton(
                    text = "Save",
                    onClick = {
                        onSave(
                            MatchDetailsDraft(
                                title = title,
                                description = initialDescription?.let { description },
                                format = format,
                                players = assignment,
                                nearSide = nearSide,
                            ),
                        )
                    },
                    enabled = problem == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** The ends row's pills, in order: Near (Home nearest the camera), Far (Away nearest), Not set. */
private val END_OPTIONS: List<Side?> = listOf(Side.HOME, Side.AWAY, null)

private fun endLabel(nearSide: Side?): String = when (nearSide) {
    Side.HOME -> PlayersCopy.NEAR
    Side.AWAY -> PlayersCopy.FAR
    null -> PlayersCopy.NEAR_FAR_NOT_SET
}

/** The format pills that take two players a side, by their index in the row (0 is "Not set"). */
private val PAIRS_FORMAT_INDICES: Set<Int> =
    MatchFormat.entries.filter { it.playersPerSide == 2 }.map { it.ordinal + 1 }.toSet()

/**
 * One side of a scored match in singles: its label, a picker of the players
 * (the other side's player left out), "Someone else" and "Add player", and
 * today's name field while the side reads "Someone else" (SidesDraft.showsName).
 */
@Composable
private fun SideRow(
    side: Side,
    sides: SidesDraft,
    players: List<Player>,
    onAddPlayer: AddPlayer,
    onChange: (SidesDraft) -> Unit,
) {
    val home = side == Side.HOME
    val playerId = if (home) sides.homePlayerId else sides.awayPlayerId
    val otherId = if (home) sides.awayPlayerId else sides.homePlayerId
    val name = (if (home) sides.homeNames else sides.awayNames).getOrElse(0) { "" }
    val typed = sides.showsName(side)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(if (home) PlayersCopy.SIDE_HOME else PlayersCopy.SIDE_AWAY)
        PlayerPicker(
            selectedId = playerId,
            players = players.filter { it.id != otherId },
            onPick = { onChange(sides.withPlayer(side, it.id)) },
            onAddPlayer = onAddPlayer,
            someoneElse = typed,
            onSomeoneElse = { onChange(sides.withSomeoneElse(side)) },
            modifier = Modifier.fillMaxWidth(),
        )
        // Animated rather than instant, as the partner fields are: a field
        // appearing under the coach's thumb without motion reads as a jump.
        AnimatedVisibility(visible = typed) {
            PlayerField(name, if (home) "Home player" else "Away player") { onChange(sides.withName(side, 0, it)) }
        }
    }
}

/** A doubles or mixed match's names: today's four fields, no pickers. */
@Composable
private fun DoublesFields(sides: SidesDraft, onName: (Side, Int, String) -> Unit) {
    // No heading over the names: each field is already labelled with its
    // side, and a "HOME" directly above "HOME PLAYER" says it twice.
    PlayerField(sides.homeNames.getOrElse(0) { "" }, "Home player 1") { onName(Side.HOME, 0, it) }
    PlayerField(sides.homeNames.getOrElse(1) { "" }, "Home player 2") { onName(Side.HOME, 1, it) }
    PlayerField(sides.awayNames.getOrElse(0) { "" }, "Away player 1") { onName(Side.AWAY, 0, it) }
    PlayerField(sides.awayNames.getOrElse(1) { "" }, "Away player 2") { onName(Side.AWAY, 1, it) }
}

@Composable
private fun PlayerField(value: String, label: String, onChange: (String) -> Unit) {
    ShuttlOutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = label,
        maxLength = MAX_PLAYER_NAME,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A dialog is its own window, and a new window draws light status bar icons until
 * told otherwise - invisible on this app's light background. MainActivity sets this
 * for the activity's window; this does the same for the dialog's, read off the theme
 * actually in force rather than passed down.
 */
@Composable
private fun StatusBarIconsFollowTheme() {
    val view = LocalView.current
    val lightBackground = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightBackground
            isAppearanceLightNavigationBars = lightBackground
        }
    }
}
