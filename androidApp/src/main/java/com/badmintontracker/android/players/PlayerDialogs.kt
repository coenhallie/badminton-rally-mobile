package com.badmintontracker.android.players

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.components.FieldLabel
import com.badmintontracker.android.ui.components.ShuttlOutlinedTextField
import com.badmintontracker.android.ui.components.ShuttlPillTabs
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.players.PlayersCopy
import com.badmintontracker.shared.players.playerNameProblem
import com.badmintontracker.shared.prefs.RacketArm
import com.badmintontracker.shared.scoring.MAX_PLAYER_NAME

/** The playing-hand pills, in the order they are drawn: Right, Left, Not set. */
internal val HAND_OPTIONS: List<RacketArm?> = listOf(RacketArm.RIGHT, RacketArm.LEFT, null)

/**
 * Add a player ([showArm], with the playing-hand pills) or rename one (the name
 * alone). Save is offered only while the name can be saved, by the shared
 * playerNameProblem; the problem is said under the field once the coach has
 * typed, not on a dialog just opened.
 *
 * [onSave] hands over the name as typed (the repository trims it) and the hand,
 * which is [initialArm] when the pills are not shown. The caller closes the
 * dialog once the save has landed; while it is in flight it passes [saving],
 * and if it fails it passes the sentence as [error] and leaves the dialog up,
 * so the coach keeps what they typed. Typing again clears a shown error.
 */
@Composable
fun PlayerNameDialog(
    title: String,
    initialName: String,
    initialArm: RacketArm?,
    showArm: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, RacketArm?) -> Unit,
    error: String? = null,
    saving: Boolean = false,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var edited by rememberSaveable { mutableStateOf(false) }
    var armIndex by rememberSaveable { mutableIntStateOf(HAND_OPTIONS.indexOf(initialArm)) }
    // The name the caller's error is about: typing past it hides the error,
    // which no longer describes what is in the field.
    var erroredName by remember(error) { mutableStateOf(name) }
    val problem = playerNameProblem(name)
    val focus = remember { FocusRequester() }

    val canSave = problem == null && !saving
    val shownError = error?.takeIf { name == erroredName } ?: problem?.takeIf { edited }

    fun save() {
        if (canSave) onSave(name, HAND_OPTIONS[armIndex])
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Not the dialog default: in light that is the pill track's own value.
        containerColor = ShuttlTheme.extended.bgDialog,
        title = { Text(title) },
        text = {
            // Here, in the dialog window's own composition, not beside the
            // dialog: the field's focus node is attached when this window
            // first lays out, and a request from outside can run before that
            // and throw.
            LaunchedEffect(Unit) { focus.requestFocus() }
            Column {
                ShuttlOutlinedTextField(
                    value = name,
                    onValueChange = { name = it; edited = true },
                    label = PlayersCopy.NAME_FIELD,
                    maxLength = MAX_PLAYER_NAME,
                    onDone = ::save,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (shownError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = shownError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (showArm) {
                    Spacer(Modifier.height(16.dp))
                    FieldLabel(PlayersCopy.MENU_PLAYING_HAND)
                    Spacer(Modifier.height(6.dp))
                    ShuttlPillTabs(
                        labels = HAND_OPTIONS.map(PlayersCopy::handOption),
                        selectedIndex = armIndex,
                        onSelect = { armIndex = it },
                        groupLabel = PlayersCopy.MENU_PLAYING_HAND,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::save, enabled = canSave) { Text(PlayersCopy.SAVE) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(PlayersCopy.CANCEL) } },
    )
}
