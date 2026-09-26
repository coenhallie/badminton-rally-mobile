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
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlOutlinedTextField
import com.badmintontracker.android.ui.components.ShuttlPillTabs
import com.badmintontracker.shared.localvideo.MAX_MATCH_DESCRIPTION_LENGTH
import com.badmintontracker.shared.scoring.MAX_MATCH_TITLE
import com.badmintontracker.shared.scoring.MAX_PLAYER_NAME
import com.badmintontracker.shared.scoring.MatchFormat
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.VIDEO_MATCH_TITLE_CAP
import com.badmintontracker.shared.scoring.matchDetailsProblem
import com.badmintontracker.shared.scoring.playersPerSide

/**
 * What the editor is opened with and what it hands back: the name a match was
 * given, who played and what kind of match it was. [description] is null for a
 * record that has no such field (a score log), and the field's text, empty
 * included, for one that does (a video); the editor shows the field only when it
 * exists to write into.
 */
data class MatchDetailsDraft(
    val title: String,
    val description: String?,
    val format: MatchFormat?,
    val homePlayers: List<String>,
    val awayPlayers: List<String>,
)

/** A scored match's details. */
fun ScoreLog.detailsDraft() =
    MatchDetailsDraft(title, description = null, format, homePlayers, awayPlayers)

/**
 * Everything about a match that the new match form deliberately does not ask for:
 * who played and what kind of match it was, plus the name it was given. All of it is
 * optional but the name, and none of it changes the score.
 *
 * One editor for both kinds of match. A scored match keeps these on its score log
 * and a filmed one on its videos row, which is the caller's business: the fields
 * and the rules are the same, except for the name cap, which is the record's own
 * ([MAX_MATCH_TITLE] against [VIDEO_MATCH_TITLE_CAP]), and the description, which
 * only a video has.
 *
 * A full-screen dialog rather than a bottom sheet or a route. Not a sheet, because a
 * sheet is drawn on surfaceVariant, which is also the pill track's colour, so the
 * format picker would lose its track; and because five fields and a keyboard do not
 * fit in one. Not a route, because both the match page and the board open it, and a
 * dialog needs no navigation from either.
 *
 * Every rule comes from [matchDetailsProblem], which the iOS sheet calls too, and the
 * names are normalised by the repository on save - so what is typed here is handed
 * over as it stands, partner fields included.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMatchDetailsDialog(
    initial: MatchDetailsDraft,
    titleCap: Int,
    onDismiss: () -> Unit,
    onSave: (MatchDetailsDraft) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var description by rememberSaveable { mutableStateOf(initial.description.orEmpty()) }
    // An index rather than the enum so it saves without a Saver. 0 is "not set".
    var formatIndex by rememberSaveable { mutableStateOf(initial.format?.let { it.ordinal + 1 } ?: 0) }
    var home by rememberSaveable { mutableStateOf(initial.homePlayers.getOrElse(0) { "" }) }
    var homePartner by rememberSaveable { mutableStateOf(initial.homePlayers.getOrElse(1) { "" }) }
    var away by rememberSaveable { mutableStateOf(initial.awayPlayers.getOrElse(0) { "" }) }
    var awayPartner by rememberSaveable { mutableStateOf(initial.awayPlayers.getOrElse(1) { "" }) }

    val format = MatchFormat.entries.getOrNull(formatIndex - 1)
    val pairs = playersPerSide(format) == 2
    // Only the fields on screen are judged: a partner left over from a doubles
    // match that is now singles is not stored, so it must not block the save.
    val homePlayers = if (pairs) listOf(home, homePartner) else listOf(home)
    val awayPlayers = if (pairs) listOf(away, awayPartner) else listOf(away)
    val problem = matchDetailsProblem(title, homePlayers, awayPlayers, titleCap)

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
                    onSelect = { formatIndex = it },
                )

                // No heading over the names: each field is already labelled with its
                // side, and a "HOME" directly above "HOME PLAYER" says it twice.
                PlayerField(home, if (pairs) "Home player 1" else "Home player") { home = it }
                // Animated rather than instant: the field below it is already on screen,
                // and a row appearing under the user's thumb without motion reads as a jump.
                AnimatedVisibility(visible = pairs) {
                    PlayerField(homePartner, "Home player 2") { homePartner = it }
                }

                PlayerField(away, if (pairs) "Away player 1" else "Away player") { away = it }
                AnimatedVisibility(visible = pairs) {
                    PlayerField(awayPartner, "Away player 2") { awayPartner = it }
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
                                homePlayers = homePlayers,
                                awayPlayers = awayPlayers,
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
