package com.badmintontracker.android.players

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.players.Player
import com.badmintontracker.shared.players.PlayersCopy
import com.badmintontracker.shared.prefs.RacketArm
import kotlinx.coroutines.launch

/** What "Add player" inside a picker hands to: PlayersViewModel.create. */
typealias AddPlayer = suspend (name: String, racketArm: RacketArm?) -> PlayerCreation

/**
 * One end's or one side's player: a field-shaped control showing the chosen
 * player (their avatar and name), "Someone else" when [someoneElse] is chosen,
 * or "Choose a player", which opens a menu of [players] with the chosen one
 * ticked, then "Someone else" when [onSomeoneElse] is given, then "Add player".
 * [onClear] adds "Choose a player" at the top of the menu while a player is
 * chosen, to take them off again.
 *
 * "Add player" opens the add dialog over the picker and, once the player is
 * created ([onAddPlayer]), picks them; a failed add stays in the dialog with
 * the name typed, as on the Players screen.
 *
 * Drawn like ShuttlOutlinedTextField's box (the same fill, stroke, radius and
 * padding, the text in the field's style), so a picker and a typed name beside
 * it read as one form; a 24dp avatar keeps the box the field's height. A screen
 * reader hears what is chosen and whether the menu is open.
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
    val shape = RoundedCornerShape(ShuttlRadius.small)
    val shown = when {
        selected != null -> selected.name
        someoneElse -> PlayersCopy.SOMEONE_ELSE
        else -> PlayersCopy.CHOOSE_PLAYER
    }

    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(ShuttlTheme.extended.bgInput, shape)
                .border(1.dp, ShuttlTheme.extended.textTertiary, shape)
                .clickable(enabled = enabled, role = Role.DropdownList) { open = true }
                .semantics {
                    stateDescription = if (open) PlayersCopy.MENU_EXPANDED else PlayersCopy.MENU_COLLAPSED
                    if (enabled) {
                        if (open) collapse { open = false; true } else expand { open = true; true }
                    }
                }
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .alpha(if (enabled) 1f else 0.6f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) {
                PlayerAvatar(selected.name, PickerAvatarSize)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = shown,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected != null || someoneElse) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                ShuttlIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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
                    leadingIcon = { PlayerAvatar(player.name, PickerAvatarSize) },
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
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = { open = false; adding = true },
            )
        }
    }

    if (adding) {
        AddPlayerDialog(
            onAddPlayer = onAddPlayer,
            onAdded = { adding = false; onPick(it) },
            onDismiss = { adding = false },
        )
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
