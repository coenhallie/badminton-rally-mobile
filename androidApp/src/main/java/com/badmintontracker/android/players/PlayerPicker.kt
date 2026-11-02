package com.badmintontracker.android.players

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.components.ShuttlMenuField
import com.badmintontracker.shared.players.Player
import com.badmintontracker.shared.players.PlayersCopy
import com.badmintontracker.shared.prefs.RacketArm
import kotlinx.coroutines.launch

/** What "Add player" inside a picker hands to: PlayersViewModel.create. */
typealias AddPlayer = suspend (name: String, racketArm: RacketArm?) -> PlayerCreation

/**
 * One end's or one side's player: a ShuttlMenuField showing the chosen player
 * (their avatar and name), "Someone else" when [someoneElse] is chosen, or
 * "Choose a player", which opens a menu of [players] with the chosen one
 * ticked, then "Someone else" when [onSomeoneElse] is given, then "Add player".
 * [onClear] adds "Choose a player" at the top of the menu while a player is
 * chosen, to take them off again.
 *
 * The menu's rows are text alone: no avatar on a player and no "+" on "Add
 * player", so every row's text starts at one x, under the box's own text, as
 * on iOS (whose menu rows draw no avatar). With a leading icon on some rows
 * only, the menu read as two columns of text.
 *
 * "Add player" opens the add dialog and, once the player is created
 * ([onAddPlayer]), picks them; a failed add stays in the dialog with the name
 * typed, as on the Players screen. The dialog is composed in the nearest
 * [AddPlayerDialogHost] rather than here: see there for why.
 */
@Composable
internal fun PlayerPicker(
    selectedId: String?,
    players: List<Player>,
    onPick: (Player) -> Unit,
    onAddPlayer: AddPlayer,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClear: (() -> Unit)? = null,
    someoneElse: Boolean = false,
    onSomeoneElse: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val selected = selectedId?.let { id -> players.firstOrNull { it.id == id } }
    val shown = when {
        selected != null -> selected.name
        someoneElse -> PlayersCopy.SOMEONE_ELSE
        else -> PlayersCopy.CHOOSE_PLAYER
    }

    ShuttlMenuField(
        text = shown,
        expanded = open,
        onExpandedChange = { open = it },
        modifier = modifier,
        enabled = enabled,
        placeholder = selected == null && !someoneElse,
        leading = if (selected != null) {
            { PlayerAvatar(selected.name, PickerAvatarSize) }
        } else {
            null
        },
    ) {
        if (onClear != null && selected != null) {
            DropdownMenuItem(
                text = { Text(PlayersCopy.CHOOSE_PLAYER) },
                onClick = { open = false; onClear() },
            )
            HorizontalDivider()
        }
        players.forEach { player ->
            DropdownMenuItem(
                text = { Text(player.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = if (player.id == selectedId) {
                    { Icon(Icons.Default.Check, contentDescription = null) }
                } else {
                    null
                },
                onClick = { open = false; onPick(player) },
            )
        }
        if (players.isNotEmpty()) HorizontalDivider()
        if (onSomeoneElse != null) {
            DropdownMenuItem(
                text = { Text(PlayersCopy.SOMEONE_ELSE) },
                trailingIcon = if (someoneElse && selected == null) {
                    { Icon(Icons.Default.Check, contentDescription = null) }
                } else {
                    null
                },
                onClick = { open = false; onSomeoneElse() },
            )
        }
        DropdownMenuItem(
            text = { Text(PlayersCopy.PICKER_ADD_PLAYER) },
            onClick = { open = false; adding = true },
        )
    }

    if (adding) {
        AddPlayerDialogRequest(
            onAddPlayer = onAddPlayer,
            onAdded = { adding = false; onPick(it) },
            onDismiss = { adding = false },
        )
    }
}

/**
 * Where the pickers' add dialogs are composed: wrap the app's content in it,
 * inside the theme (MainActivity does), and every PlayerPicker under it opens
 * its "Add player" dialog here.
 *
 * A dialog takes its window theme from the window it is composed in. A
 * ModalBottomSheet's window is full-screen and not floating (Material3 1.3's
 * EdgeToEdgeFloatingDialogWindowTheme sets windowIsFloating false), so an
 * AlertDialog composed inside the Match details sheet spanned the screen edge
 * to edge, while the same dialog from Players was inset. Composed here, in the
 * activity's own window, the dialog is the Players one wherever its picker is:
 * in a sheet, in the details editor's full-screen dialog, or on a page. Its
 * window is opened after the one the picker is in, so it stands above it.
 *
 * Only the dialog moves: the picker keeps its own (saveable) "adding" state and
 * hands its callbacks here while it is set, so the dialog closes when the
 * picker goes, and comes back, with the name typed, after the activity is
 * recreated.
 */
@Composable
fun AddPlayerDialogHost(content: @Composable () -> Unit) {
    val host = remember { AddPlayerDialogHostState() }
    CompositionLocalProvider(LocalAddPlayerDialogHost provides host, content = content)
    host.request?.let { request ->
        AddPlayerDialog(
            onAddPlayer = request.onAddPlayer,
            onAdded = request.onAdded,
            onDismiss = request.onDismiss,
        )
    }
}

/** The add dialog one picker wants shown, by the callbacks it is to call. */
private class AddPlayerRequest(
    val onAddPlayer: AddPlayer,
    val onAdded: (Player) -> Unit,
    val onDismiss: () -> Unit,
)

@Stable
private class AddPlayerDialogHostState {
    var request by mutableStateOf<AddPlayerRequest?>(null)
}

private val LocalAddPlayerDialogHost = staticCompositionLocalOf<AddPlayerDialogHostState?> { null }

/**
 * Shows the add dialog in the [AddPlayerDialogHost] for as long as this is in
 * composition, calling the latest of the callbacks given. With no host (a
 * preview), the dialog is composed here instead.
 */
@Composable
private fun AddPlayerDialogRequest(
    onAddPlayer: AddPlayer,
    onAdded: (Player) -> Unit,
    onDismiss: () -> Unit,
) {
    val host = LocalAddPlayerDialogHost.current
    if (host == null) {
        AddPlayerDialog(onAddPlayer, onAdded, onDismiss)
        return
    }
    val latestAdd by rememberUpdatedState(onAddPlayer)
    val latestAdded by rememberUpdatedState(onAdded)
    val latestDismiss by rememberUpdatedState(onDismiss)
    val request = remember {
        AddPlayerRequest(
            onAddPlayer = { name, arm -> latestAdd(name, arm) },
            onAdded = { latestAdded(it) },
            onDismiss = { latestDismiss() },
        )
    }
    DisposableEffect(host, request) {
        host.request = request
        onDispose { if (host.request === request) host.request = null }
    }
}

/**
 * The add dialog a picker opens: PlayerNameDialog with the playing hand, held
 * open while the player is created and through a failure, which it says under
 * the name. The wait runs in the dialog's own scope, so closing the dialog
 * abandons it; the insert itself is the view model's and is not (see
 * PlayersViewModel.create).
 */
@Composable
private fun AddPlayerDialog(
    onAddPlayer: AddPlayer,
    onAdded: (Player) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    PlayerNameDialog(
        title = PlayersCopy.ADD_DIALOG_TITLE,
        initialName = "",
        initialArm = null,
        showArm = true,
        onDismiss = onDismiss,
        onSave = { name, arm ->
            saving = true
            error = null
            scope.launch {
                when (val result = onAddPlayer(name, arm)) {
                    is PlayerCreation.Created -> onAdded(result.player)
                    is PlayerCreation.Failed -> {
                        saving = false
                        error = result.message
                    }
                }
            }
        },
        error = error,
        saving = saving,
    )
}

/** The avatar in a picker and its menu: the switcher's size less 2dp, so the box keeps a text field's height. */
private val PickerAvatarSize = 24.dp

