package com.badmintontracker.android.localvideo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.players.AddPlayer
import com.badmintontracker.android.players.EndsDraftSaver
import com.badmintontracker.android.players.WhoIsPlayingSection
import com.badmintontracker.android.ui.components.ShuttlOutlinedTextField
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.localvideo.MAX_MATCH_DESCRIPTION_LENGTH
import com.badmintontracker.shared.localvideo.MAX_MATCH_TITLE_LENGTH
import com.badmintontracker.shared.players.EndsDraft
import com.badmintontracker.shared.players.MatchPlayers
import com.badmintontracker.shared.players.Player
import com.badmintontracker.shared.players.matchPlayersProblem
import com.badmintontracker.shared.players.toAssignment
import com.badmintontracker.shared.scoring.Side
import kotlinx.coroutines.launch

/**
 * Who's playing, and the name and description, of a match only its local
 * entry speaks for: straight after a recording or an import, from the list
 * row's "Edit details", and from the match page of a video with no score log
 * and no clips yet.
 *
 * The Who's playing section ([playersDraft]) comes first, above the name, and
 * shows on any of them; [playersEditable] false shows it read-only, during the
 * upload. [onSave] hands back what to save, the draft's assignment and near side
 * (EndsDraft.toAssignment), so no caller re-derives it. The name and description show only while [detailsEditable]: both
 * ride along on the videos INSERT and the database grants no UPDATE on either
 * column, so they can change only while the entry is still LOCAL (see
 * canEditLocalVideoDetails). Hidden, they are handed back as they came.
 *
 * [autoOpened] only changes the dismiss label: straight after an import or a
 * recording the sheet is something to get past ("Skip"), while from the row menu
 * it is a deliberate edit ("Cancel"). Dismissing never discards the video, which
 * is already saved by the time this opens.
 *
 * Drawn on bgDialog rather than the sheet's usual surfaceVariant, which is the
 * pill track's own colour: on it, One player | Both players would have no track.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchDetailsSheet(
    initialTitle: String?,
    initialDescription: String?,
    detailsEditable: Boolean,
    playersDraft: EndsDraft,
    playersEditable: Boolean,
    roster: List<Player>,
    onAddPlayer: AddPlayer,
    autoOpened: Boolean,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String, players: MatchPlayers, nearSide: Side?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf(initialTitle.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(initialDescription.orEmpty()) }
    var draft by rememberSaveable(stateSaver = EndsDraftSaver) { mutableStateOf<EndsDraft?>(playersDraft) }
    val current = draft ?: playersDraft
    val (players, nearSide) = current.toAssignment()

    val problem = if (playersEditable) matchPlayersProblem(players, null) else null
    val canSave = (detailsEditable || playersEditable) && problem == null

    fun hideThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ShuttlTheme.extended.bgDialog,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Before the scroll, so the scrolled area ends at the keyboard
                // and a focused Description scrolls clear of it.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Match details", style = MaterialTheme.typography.titleLarge)

            WhoIsPlayingSection(
                draft = current,
                players = roster,
                onChange = { draft = it },
                onAddPlayer = onAddPlayer,
                uploading = !playersEditable,
            )

            if (detailsEditable) {
                ShuttlOutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = "Match name",
                    maxLength = MAX_MATCH_TITLE_LENGTH,
                )

                ShuttlOutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = "Description",
                    singleLine = false,
                    minLines = 3,
                    maxLength = MAX_MATCH_DESCRIPTION_LENGTH,
                )
            }

            problem?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { hideThen(onDismiss) }) {
                    Text(if (autoOpened) "Skip" else "Cancel")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { hideThen { onSave(title, description, players, nearSide) } },
                    enabled = canSave,
                ) { Text("Save") }
            }
        }
    }
}
