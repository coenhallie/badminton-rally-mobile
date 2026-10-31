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
import com.badmintontracker.android.players.EndsDraftSaver
import com.badmintontracker.android.players.PlayerPicker
import com.badmintontracker.android.players.WhoIsPlayingSection
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlOutlinedTextField
import com.badmintontracker.android.ui.components.ShuttlPillTabs
import com.badmintontracker.shared.localvideo.MAX_MATCH_DESCRIPTION_LENGTH
import com.badmintontracker.shared.players.MatchPlayers
import com.badmintontracker.shared.players.Player
import com.badmintontracker.shared.players.PlayersCopy
import com.badmintontracker.shared.players.SidesDraft
import com.badmintontracker.shared.players.endsDraft
import com.badmintontracker.shared.players.matchPlayers
import com.badmintontracker.shared.players.matchPlayersProblem
import com.badmintontracker.shared.players.toAssignment
import com.badmintontracker.shared.players.toMatchPlayers
import com.badmintontracker.shared.scoring.MAX_MATCH_TITLE
import com.badmintontracker.shared.scoring.MAX_PLAYER_NAME
import com.badmintontracker.shared.scoring.MatchFormat
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.scoring.VIDEO_MATCH_TITLE_CAP
import com.badmintontracker.shared.scoring.matchDetailsProblem
import com.badmintontracker.shared.scoring.playersPerSide

/**
 * What the editor is opened with and what it hands back: the name a match was
 * given, what kind of match it was, who played, and which side was nearest the
 * camera. [description] is null for a record that has no such field (a score
 * log), and the field's text, empty included, for one that does (a video); the
 * editor shows the field only when it exists to write into. [players] is what
 * is saved: cut to the format and cleaned by the shared drafts it is built from.
 */
data class MatchDetailsDraft(
    val title: String,
    val description: String?,
    val format: MatchFormat?,
    val players: MatchPlayers,
    val nearSide: Side?,
)

/** A scored match's details, with the end its attached video says Home was at (null: not said, or no video). */
fun ScoreLog.detailsDraft(nearSide: Side?) =
    MatchDetailsDraft(title, description = null, format, matchPlayers, nearSide)

/** How the editor draws who played, by the record it writes to. */
enum class PlayersLayout {
    /**
     * A score log: Home and Away, each a player picker whose last two items are
     * "Someone else" (the name fields) and "Add player"; the ends row when a
     * video is attached.
     */
    SIDES,

    /** A match born as a video: the Who's playing section, by court end. */
    ENDS,
}

/**
 * Everything about a match that the new match form deliberately does not ask for:
 * who played and what kind of match it was, plus the name it was given. All of it is
 * optional but the name, and none of it changes the score.
 *
 * One editor for both kinds of match. A scored match keeps these on its score log
 * and a filmed one on its videos row, which is the caller's business: the fields
 * and the rules are the same, except for the name cap, which is the record's own
 * ([MAX_MATCH_TITLE] against [VIDEO_MATCH_TITLE_CAP]), the description, which
 * only a video has, and who played ([layout]).
 *
 * Who played is singles-only as a profile: while a side ([PlayersLayout.SIDES])
 * or an end ([PlayersLayout.ENDS]) holds one, Doubles and Mixed are not offered
 * and a line says to remove the players first. A doubles or mixed match keeps
 * today's typed names, two per side, and no pickers; a video-born one never
 * builds the singles-only ends draft while it is doubles.
 *
 * [showsEnds] (SIDES) adds "Home was at the" Near | Far | Not set, for a scored
 * match with a video. [uploading] is that video mid-upload: the ends row (SIDES)
 * or the whole Who's playing section (ENDS) shows read-only, with the sentence
 * saying it can change once the upload finishes.
 *
 * A full-screen dialog rather than a bottom sheet or a route. Not a sheet, because a
 * sheet is drawn on surfaceVariant, which is also the pill track's colour, so the
 * format picker would lose its track; and because the fields and a keyboard do not
 * fit in one. Not a route, because both the match page and the board open it, and a
 * dialog needs no navigation from either.
 *
 * Every rule comes from shared: [matchDetailsProblem] for the name and the typed
 * names, matchPlayersProblem for who played, and the drafts (SidesDraft, EndsDraft)
 * for what a side or an end saves. This only renders them and hands taps back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMatchDetailsDialog(
    initial: MatchDetailsDraft,
    titleCap: Int,
    layout: PlayersLayout,
    players: List<Player>,
    onAddPlayer: AddPlayer,
    onDismiss: () -> Unit,
    onSave: (MatchDetailsDraft) -> Unit,
    showsEnds: Boolean = false,
    uploading: Boolean = false,
) {
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var description by rememberSaveable { mutableStateOf(initial.description.orEmpty()) }
    // An index rather than the enum so it saves without a Saver. 0 is "not set".
    var formatIndex by rememberSaveable { mutableStateOf(initial.format?.let { it.ordinal + 1 } ?: 0) }
    // The names typed for each side: the score log's side rows, and either
    // layout's doubles rows. A side holding a profile keeps what was typed
    // here (hidden, and not saved), so taking the profile off brings it back.
    var home by rememberSaveable { mutableStateOf(initial.players.homePlayers.getOrElse(0) { "" }) }
    var homePartner by rememberSaveable { mutableStateOf(initial.players.homePlayers.getOrElse(1) { "" }) }
    var away by rememberSaveable { mutableStateOf(initial.players.awayPlayers.getOrElse(0) { "" }) }
    var awayPartner by rememberSaveable { mutableStateOf(initial.players.awayPlayers.getOrElse(1) { "" }) }
    // SIDES: each side's profile, and whether "Someone else" was chosen for it:
    // a side that opens with a typed name and no profile already is, and typing
    // chooses it, so clearing the field to retype never takes the field away.
    var homeId by rememberSaveable { mutableStateOf(initial.players.homePlayerId) }
    var awayId by rememberSaveable { mutableStateOf(initial.players.awayPlayerId) }
    var homeSomeoneElse by rememberSaveable {
        mutableStateOf(initial.players.homePlayerId == null && initial.players.homePlayers.any { it.isNotBlank() })
    }
    var awaySomeoneElse by rememberSaveable {
        mutableStateOf(initial.players.awayPlayerId == null && initial.players.awayPlayers.any { it.isNotBlank() })
    }
    var nearSideIndex by rememberSaveable { mutableStateOf(END_OPTIONS.indexOf(initial.nearSide)) }
    // ENDS: the singles section's draft; null while the match is doubles.
    var ends by rememberSaveable(stateSaver = EndsDraftSaver) {
        mutableStateOf(
            if (layout == PlayersLayout.ENDS && playersPerSide(initial.format) == 1) {
                endsDraft(initial.players, initial.nearSide, null)
            } else {
                null
            },
        )
    }

    val format = MatchFormat.entries.getOrNull(formatIndex - 1)
    val pairs = playersPerSide(format) == 2
    val sides = SidesDraft(
        homePlayerId = homeId,
        awayPlayerId = awayId,
        homeNames = listOf(home, homePartner),
        awayNames = listOf(away, awayPartner),
        nearSide = END_OPTIONS[nearSideIndex],
    )
    val currentEnds = ends
    val (assignment, assignedNearSide) = when {
        layout == PlayersLayout.SIDES -> sides.toMatchPlayers(format) to sides.nearSide
        // A video-born match in doubles: typed names only, and the end it had.
        pairs || currentEnds == null ->
            sides.copy(homePlayerId = null, awayPlayerId = null).toMatchPlayers(format) to initial.nearSide
        else -> currentEnds.toAssignment()
    }
    val holdsProfile = assignment.homePlayerId != null || assignment.awayPlayerId != null
    val problem = matchDetailsProblem(title, assignment.homePlayers, assignment.awayPlayers, titleCap)
        ?: matchPlayersProblem(assignment, format)

    /** Moves the typed names between the two shapes when the format crosses singles and doubles. */
    fun selectFormat(index: Int) {
        val nextPairs = playersPerSide(MatchFormat.entries.getOrNull(index - 1)) == 2
        if (layout == PlayersLayout.ENDS && nextPairs != pairs) {
            val current = ends
            if (nextPairs && current != null) {
                // Only reachable with no profile on either end: Doubles is off while one is.
                val (typed, _) = current.toAssignment()
                home = typed.homePlayers.firstOrNull().orEmpty()
                away = typed.awayPlayers.firstOrNull().orEmpty()
                ends = null
            } else if (!nextPairs) {
                ends = endsDraft(MatchPlayers(null, null, listOf(home), listOf(away)), current?.nearSide ?: initial.nearSide, null)
            }
        }
        formatIndex = index
    }

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
                    .verticalScroll(rememberScrollState())
                    .imePadding()
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
                if (initial.description != null) {
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
                    onSelect = ::selectFormat,
                    disabledIndices = if (holdsProfile) PAIRS_FORMAT_INDICES else emptySet(),
                )
                if (holdsProfile) Note(PlayersCopy.DOUBLES_NOTE)

                when {
                    // Today's names, two a side: no pickers outside singles.
                    pairs -> DoublesFields(
                        home = home, homePartner = homePartner, away = away, awayPartner = awayPartner,
                        onHome = { home = it }, onHomePartner = { homePartner = it },
                        onAway = { away = it }, onAwayPartner = { awayPartner = it },
                    )
                    layout == PlayersLayout.ENDS -> ends?.let { current ->
                        WhoIsPlayingSection(
                            draft = current,
                            players = players,
                            onChange = { ends = it },
                            onAddPlayer = onAddPlayer,
                            uploading = uploading,
                        )
                    }
                    else -> {
                        SideRow(
                            label = PlayersCopy.SIDE_HOME,
                            fieldLabel = "Home player",
                            playerId = homeId,
                            otherId = awayId,
                            someoneElse = homeSomeoneElse,
                            name = home,
                            players = players,
                            onPick = { homeId = it },
                            onSomeoneElse = { homeId = null; homeSomeoneElse = true },
                            onName = { home = it; homeSomeoneElse = true },
                            onAddPlayer = onAddPlayer,
                        )
                        SideRow(
                            label = PlayersCopy.SIDE_AWAY,
                            fieldLabel = "Away player",
                            playerId = awayId,
                            otherId = homeId,
                            someoneElse = awaySomeoneElse,
                            name = away,
                            players = players,
                            onPick = { awayId = it },
                            onSomeoneElse = { awayId = null; awaySomeoneElse = true },
                            onName = { away = it; awaySomeoneElse = true },
                            onAddPlayer = onAddPlayer,
                        )
                    }
                }

                if (layout == PlayersLayout.SIDES && showsEnds) {
                    SectionLabel(PlayersCopy.HOME_WAS_AT_THE)
                    ShuttlPillTabs(
                        labels = END_OPTIONS.map(::endLabel),
                        selectedIndex = nearSideIndex,
                        onSelect = { nearSideIndex = it },
                        groupLabel = PlayersCopy.HOME_WAS_AT_THE,
                        enabled = !uploading,
                    )
                    if (uploading) Note(PlayersCopy.UPLOAD_IN_PROGRESS)
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
                                description = initial.description?.let { description },
                                format = format,
                                players = assignment,
                                nearSide = assignedNearSide,
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
 * (the other side's player left out), "Someone else" and "Add player", and,
 * for "Someone else" or a side that has a typed name and no player, today's
 * name field.
 */
@Composable
private fun SideRow(
    label: String,
    fieldLabel: String,
    playerId: String?,
    otherId: String?,
    someoneElse: Boolean,
    name: String,
    players: List<Player>,
    onPick: (String) -> Unit,
    onSomeoneElse: () -> Unit,
    onName: (String) -> Unit,
    onAddPlayer: AddPlayer,
) {
    val typed = playerId == null && (someoneElse || name.isNotBlank())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(label)
        PlayerPicker(
            selectedId = playerId,
            players = players.filter { it.id != otherId },
            onPick = { onPick(it.id) },
            onAddPlayer = onAddPlayer,
            someoneElse = typed,
            onSomeoneElse = onSomeoneElse,
            modifier = Modifier.fillMaxWidth(),
        )
        // Animated rather than instant, as the partner fields are: a field
        // appearing under the coach's thumb without motion reads as a jump.
        AnimatedVisibility(visible = typed) {
            PlayerField(name, fieldLabel, onName)
        }
    }
}

/** A doubles or mixed match's names: today's four fields, no pickers. */
@Composable
private fun DoublesFields(
    home: String,
    homePartner: String,
    away: String,
    awayPartner: String,
    onHome: (String) -> Unit,
    onHomePartner: (String) -> Unit,
    onAway: (String) -> Unit,
    onAwayPartner: (String) -> Unit,
) {
    // No heading over the names: each field is already labelled with its
    // side, and a "HOME" directly above "HOME PLAYER" says it twice.
    PlayerField(home, "Home player 1", onHome)
    PlayerField(homePartner, "Home player 2", onHomePartner)
    PlayerField(away, "Away player 1", onAway)
    PlayerField(awayPartner, "Away player 2", onAwayPartner)
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

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
