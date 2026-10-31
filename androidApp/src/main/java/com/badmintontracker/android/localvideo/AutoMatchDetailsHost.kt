package com.badmintontracker.android.localvideo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.badmintontracker.android.nav.Route
import com.badmintontracker.android.players.PlayerCreation
import com.badmintontracker.android.players.PlayersViewModel
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.localvideo.canEditLocalVideoDetails
import com.badmintontracker.shared.players.MatchPlayers
import com.badmintontracker.shared.players.PlayersCopy
import com.badmintontracker.shared.players.PlayersTarget
import com.badmintontracker.shared.players.endsDraft
import com.badmintontracker.shared.players.matchPlayers
import com.badmintontracker.shared.players.playersTarget
import com.badmintontracker.shared.scoring.Side

/**
 * The "Match details" sheet that opens on its own over a video just recorded or
 * imported with no match: whatever screen the coach came back to (Players, a
 * player's page, All matches), so it sits above the NavHost rather than in any
 * one destination.
 *
 * [entryId] is the entry to name, held by the caller until [onClose]: the
 * finished-run watcher reads it as "something is above", so a run's View is
 * not offered over a half-typed name. The entry is persisted before this is
 * asked to open, so a dismissed sheet, a backgrounded app or a crash never
 * costs the coach the video. An entry no longer on the phone closes the sheet
 * rather than leaving the caller waiting on it.
 *
 * Who's playing starts from the entry: a video taken from a player's page
 * already holds that player at the near end (VideoIntake's forPlayer). The
 * players and "Add player" are the Players entry's view model's, as everywhere
 * else ([playersVmFactory]); [onSave] is the shared sheet save, which AuthGate
 * runs with the repositories it holds.
 *
 * Dismissing is "Skip" (autoOpened): straight after taking the video this is
 * something to get past, and the entry keeps what it already says.
 */
@Composable
fun AutoMatchDetailsHost(
    entryId: String?,
    localVideos: LocalVideoRepository,
    nav: NavHostController,
    playersVmFactory: ViewModelProvider.Factory,
    onSave: (entryId: String, title: String, description: String, players: MatchPlayers, nearSide: Side?) -> Unit,
    onClose: () -> Unit,
) {
    entryId ?: return
    val entries by localVideos.entries.collectAsStateWithLifecycle()
    val entry = entries.firstOrNull { it.id == entryId }
    if (entry == null) {
        LaunchedEffect(entryId) { onClose() }
        return
    }
    val playersVm = rootPlayersViewModel(nav, playersVmFactory)
    val roster = playersVm?.state?.collectAsStateWithLifecycle()?.value?.players.orEmpty()
    // Keyed on the entry, so the fields always start from this entry's own.
    key(entry.id) {
        MatchDetailsSheet(
            initialTitle = entry.title,
            initialDescription = entry.description,
            detailsEditable = canEditLocalVideoDetails(entry.stage),
            playersDraft = remember { endsDraft(entry.matchPlayers, entry.nearSide, null) },
            playersEditable = playersTarget(null, entry, null) != PlayersTarget.UPLOADING,
            roster = roster,
            onAddPlayer = { name, arm ->
                playersVm?.create(name, arm) ?: PlayerCreation.Failed(PlayersCopy.SAVE_FAILED)
            },
            autoOpened = true,
            onDismiss = onClose,
            onSave = { title, description, players, nearSide ->
                onSave(entry.id, title, description, players, nearSide)
                onClose()
            },
        )
    }
}

/**
 * The Players entry's PlayersViewModel, from above the NavHost, while there is
 * a Players entry to hold it (someone is signed in) and it has reached CREATED;
 * null otherwise. Looked up again as the stack moves, as RootAnnouncementsHost
 * does for the list's.
 */
@Composable
private fun rootPlayersViewModel(nav: NavHostController, factory: ViewModelProvider.Factory): PlayersViewModel? {
    val current by nav.currentBackStackEntryAsState()
    val playersEntry = remember(current) {
        runCatching { nav.getBackStackEntry<Route.Players>() }.getOrNull()
    }
    val created = playersEntry?.lifecycle?.currentStateAsState()?.value
        ?.isAtLeast(Lifecycle.State.CREATED) == true
    return playersEntry?.takeIf { created }?.let { viewModel(it, factory = factory) }
}
