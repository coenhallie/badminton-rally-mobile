package com.badmintontracker.android.home

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.badmintontracker.android.cliplist.AnalyzeResultDialog
import com.badmintontracker.android.cliplist.ClipListViewModel
import com.badmintontracker.android.nav.Route
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.matchlist.MatchListItem
import com.badmintontracker.shared.matchpage.MatchPageCopy
import com.badmintontracker.shared.matchpage.MatchPageSection
import com.badmintontracker.shared.matchpage.finishedRunMessage
import com.badmintontracker.shared.matchpage.matchPageItem
import com.badmintontracker.shared.matchpage.sectionAfterRun
import kotlinx.coroutines.launch

/**
 * What tells the coach about work the app did for them, whichever screen is in
 * front. Above the NavHost, beside AutoMatchDetailsHost, so none of it depends
 * on one destination being composed:
 *
 * - a finished run, with View (the runs AuthGate's watcher just announced);
 * - a record or import that failed;
 * - the match list's own failure message ("Couldn't refresh matches..."),
 *   taken off the list view model once posted, so it is said once and never
 *   turns up late on a screen opened afterwards;
 * - the result dialog of a standalone video whose analysis failed.
 *
 * Every message goes to [snackbar], the app's one root host, which the Players
 * screen, All matches and a player's page draw in their own Scaffolds (so it
 * sits above the Add button there). One host, so two messages queue rather
 * than stack. A message posted while none of them is in front waits for one:
 * the watcher only announces runs while one of them is, and the list's
 * failures come from them.
 *
 * The match page draws the list's failure itself (its own Delete and Remove
 * fail through the same view model), so while it is in front that message is
 * left to it.
 *
 * The result dialog is held back while the scoring board is in front: a
 * dialog arriving under a coach's thumb mid-rally would take the point's tap.
 * It shows the moment the board is left. [onOverlayChange] reports it, for the
 * watcher's "nothing above".
 */
@Composable
fun RootAnnouncementsHost(
    nav: NavHostController,
    snackbar: SnackbarHostState,
    /** The factory of the one list view model, which lives on the Route.Players entry. */
    clipListVmFactory: ViewModelProvider.Factory,
    localVideos: LocalVideoRepository,
    intakeError: String?,
    onIntakeErrorShown: () -> Unit,
    /** Runs just announced by AuthGate's watcher; empty when none. */
    announced: Set<String>,
    onAnnounced: () -> Unit,
    /** A finished run's View: the match, on what the run made. */
    onOpenMatch: (MatchListItem, MatchPageSection?) -> Unit,
    /** The result dialog's Retry: AuthGate's analyzeEntry. */
    onAnalyze: (entryId: String) -> Unit,
    onOverlayChange: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val current by nav.currentBackStackEntryAsState()
    val destination = current?.destination
    // The list's one view model, while there is a root entry to hold it
    // (Route.Players for coaches, Route.PlayerHome for players).
    val playersEntry = remember(current) {
        runCatching { nav.getBackStackEntry<Route.Players>() }.getOrNull()
            ?: runCatching { nav.getBackStackEntry<Route.PlayerHome>() }.getOrNull()
    }
    // An entry's view models are out of reach until its lifecycle reaches
    // CREATED, which on a cold start can be after this host's first frame.
    // Read as state, so the host comes back to it once it gets there.
    val playersCreated = playersEntry?.lifecycle?.currentStateAsState()?.value
        ?.isAtLeast(Lifecycle.State.CREATED) == true
    val listVm: ClipListViewModel? =
        playersEntry?.takeIf { playersCreated }?.let { viewModel(it, factory = clipListVmFactory) }

    // Record and import failures. Cleared at once and posted in this scope, so
    // the message is never said twice, and its own effect restarting cannot
    // cancel it mid-snackbar.
    LaunchedEffect(intakeError) {
        val err = intakeError ?: return@LaunchedEffect
        onIntakeErrorShown()
        scope.launch { snackbar.showSnackbar(err) }
    }

    if (listVm != null) {
        val listError by listVm.error.collectAsStateWithLifecycle()
        val onMatchPage = destination?.hasRoute<Route.Match>() == true
        LaunchedEffect(listError, onMatchPage) {
            val err = listError ?: return@LaunchedEffect
            if (onMatchPage) return@LaunchedEffect
            listVm.dismissError()
            scope.launch { snackbar.showSnackbar(err) }
        }
    }

    // A run that landed while a list was in front. The app does not move on
    // its own (opening the match's page would take the screen from under the
    // coach mid-scroll): it says so, and View opens that match on what the
    // run made.
    //
    // The message is built from the list as it is now, and the ids are
    // cleared straight away, so returning to a list later cannot say it
    // again. The snackbar itself is shown in this host's own scope, not in
    // this effect: clearing the ids changes this effect's key, which would
    // cancel it mid-snackbar, and the message would flash and vanish before
    // View could be tapped. The host queues it behind an intake error, if one
    // is showing.
    LaunchedEffect(announced) {
        if (announced.isEmpty()) return@LaunchedEffect
        onAnnounced()
        val list = listVm ?: return@LaunchedEffect
        // Read, not collected: the screens the watcher announces on collect the
        // list themselves, so its value is current whenever this runs.
        val message = finishedRunMessage(announced, list.state.value.matchList) ?: return@LaunchedEffect
        val item = message.item
        scope.launch {
            val result = snackbar.showSnackbar(
                message = message.text,
                actionLabel = item?.let { MatchPageCopy.VIEW },
                duration = if (item != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result != SnackbarResult.ActionPerformed || item == null) return@launch
            // The row as it is at the tap, not as it was when the run landed:
            // the watcher can fire before the list has refreshed (a cloud
            // run's stored track lands after its finish), and the frozen row
            // would then have no Analysis to open on. Falls back to the frozen
            // row if the match has left the list; the page says it is gone.
            val now = list.state.value.matchList
                .matchPageItem(item.scoreLogId, item.videoId, item.entryId) ?: item
            onOpenMatch(now, sectionAfterRun(now))
        }
    }

    // Auto-show the result dialog once per failure. `resultSeen` is persisted
    // on the entry, so dismissing it survives navigation and relaunch; a later
    // retry that fails again resets the flag (in AnalyzeCoordinator.fail) and
    // shows it anew. Standalone videos only: a match's own video fails on its
    // match's row and page.
    val entries by localVideos.entries.collectAsStateWithLifecycle()
    var resultEntry by remember { mutableStateOf<LocalVideoEntry?>(null) }
    LaunchedEffect(entries) {
        if (resultEntry == null) {
            resultEntry = entries.firstOrNull {
                it.scoreLogId == null && it.stage == AnalyzeStage.FAILED && !it.resultSeen
            }
        }
    }
    val onBoard = destination?.hasRoute<Route.Scoring>() == true
    val shown = resultEntry?.takeIf { listVm != null && !onBoard }
    LaunchedEffect(shown != null) { onOverlayChange(shown != null) }

    shown?.let { entry ->
        fun seen() {
            resultEntry = null
            localVideos.update(entry.id) { it.copy(resultSeen = true) }
        }
        AnalyzeResultDialog(
            entry = entry,
            onRetry = { seen(); onAnalyze(entry.id) },
            onDismiss = ::seen,
        )
    }
}
