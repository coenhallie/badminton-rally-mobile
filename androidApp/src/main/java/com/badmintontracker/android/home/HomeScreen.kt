package com.badmintontracker.android.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.cliplist.ClipListScreen
import com.badmintontracker.android.cliplist.ClipListViewModel
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.shared.home.HomeCopy
import com.badmintontracker.shared.matchlist.MatchListItem
import com.badmintontracker.shared.matchpage.MatchPageCopy
import com.badmintontracker.shared.matchpage.MatchPageSection
import com.badmintontracker.shared.matchpage.finishedRunMessage
import com.badmintontracker.shared.matchpage.matchPageItem
import com.badmintontracker.shared.matchpage.sectionAfterRun
import com.badmintontracker.shared.repo.MediaRepository
import com.badmintontracker.shared.repo.SharesRepository
import kotlinx.coroutines.launch

/**
 * The root screen: the match list under its title bar, the Add button, and
 * the one snackbar host. Every other route pushes over the whole of it.
 *
 * Mirrors iosApp's MatchesScreen.swift.
 */
@Composable
fun HomeScreen(
    /** Any sheet or dialog over the list: the add sheet here, or one the list owns. */
    onOverlayChange: (Boolean) -> Unit,
    vm: ClipListViewModel,
    shares: SharesRepository,
    media: MediaRepository,
    /** A row's tap (no section: the page's default), or a finished run's View. */
    onOpenMatch: (MatchListItem, MatchPageSection?) -> Unit,
    onSettings: () -> Unit,
    onNewMatch: () -> Unit,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    onAnalyze: (entryId: String) -> Unit,
    onMarkCourt: (entryId: String) -> Unit,
    onRemoveFromPhone: (entryId: String) -> Unit,
    onLocalResultSeen: (entryId: String) -> Unit,
    onLocalDetailsSaved: (id: String, title: String, description: String) -> Unit,
    autoDetailsEntryId: String?,
    onAutoDetailsShown: () -> Unit,
    intakeError: String?,
    onIntakeErrorShown: () -> Unit,
    /** Runs just announced by AuthGate's watcher, to say on the snackbar; empty when none. */
    announced: Set<String>,
    onAnnounced: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var addSheetOpen by remember { mutableStateOf(false) }
    var listOverlayOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Hoisted: the Add button reads firstVisibleItemIndex to decide whether
    // to show its label, and it is not a descendant of the list.
    val listState = rememberLazyListState()

    LaunchedEffect(addSheetOpen, listOverlayOpen) { onOverlayChange(addSheetOpen || listOverlayOpen) }

    // Record and import failures.
    LaunchedEffect(intakeError) {
        val err = intakeError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err)
        onIntakeErrorShown()
    }

    // A run that landed while the list was in front. The app does not move on
    // its own (opening the match's page would take the screen from under the
    // coach mid-scroll): it says so, and View opens that match on what the
    // run made.
    //
    // The message is built from the list as it is now, and the ids are
    // cleared straight away, so returning to the list later cannot say it
    // again. The snackbar itself is shown in the screen's own scope, not in
    // this effect: clearing the ids changes this effect's key, which would
    // cancel it mid-snackbar, and the message would flash and vanish before
    // View could be tapped. The host queues it behind an intake error, if one
    // is showing.
    LaunchedEffect(announced) {
        if (announced.isEmpty()) return@LaunchedEffect
        val message = finishedRunMessage(announced, state.matchList)
        onAnnounced()
        message ?: return@LaunchedEffect
        val item = message.item
        scope.launch {
            val result = snackbarHostState.showSnackbar(
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
            val now = state.matchList.matchPageItem(item.scoreLogId, item.videoId, item.entryId) ?: item
            onOpenMatch(now, sectionAfterRun(now))
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // The bottom inset only: HomeTopBar draws under the status bar itself,
        // and with no bottom bar the Scaffold must lift the Add button and the
        // snackbar clear of the gesture bar. The side insets are taken back
        // below, as before.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        // The Add button and the snackbar sit outside the content Column
        // below, so its horizontal inset does not reach them: each takes its
        // own, or a landscape cutout or side gesture inset would clip the
        // button's edge and the snackbar's.
        floatingActionButton = {
            val expanded by remember { derivedStateOf { addButtonExpanded(listState.firstVisibleItemIndex) } }
            AddMatchButton(
                expanded = expanded,
                onClick = { addSheetOpen = true },
                modifier = Modifier.windowInsetsPadding(HorizontalSafeDrawing),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState, Modifier.windowInsetsPadding(HorizontalSafeDrawing)) },
    ) { padding ->
        // Room for the Add button: the last row and its menu must scroll clear
        // of it. `padding` is the gesture inset alone, so the button's own
        // footprint goes on top of it.
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding() + ADD_BUTTON_CLEARANCE)
        Column(Modifier.fillMaxSize().windowInsetsPadding(HorizontalSafeDrawing)) {
            HomeTopBar(HomeCopy.TAB_MATCHES) {
                BackgroundWorkAction()
                IconButton(onClick = onSettings) {
                    Icon(
                        ShuttlIcons.Settings,
                        contentDescription = MatchPageCopy.SETTINGS,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        // The video card's 14-unit gear, drawn at 20dp: its
                        // 1.4 stroke lands on 2dp there, the weight of the
                        // app's 24dp Lucide glyphs. At its own 14dp it read
                        // as a speck beside the bar's other icons.
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // weight(1f), not fillMaxSize: the list is measured alongside the
            // title bar, so fillMaxSize would claim the bar's height too and
            // let the list overhang past the bottom of the screen.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ClipListScreen(
                    vm = vm, shares = shares, media = media,
                    onOpenMatch = { onOpenMatch(it, null) },
                    onAnalyze = onAnalyze,
                    onMarkCourt = onMarkCourt,
                    onRemoveFromPhone = onRemoveFromPhone,
                    onLocalResultSeen = onLocalResultSeen,
                    onLocalDetailsSaved = onLocalDetailsSaved,
                    autoDetailsEntryId = autoDetailsEntryId,
                    onAutoDetailsShown = onAutoDetailsShown,
                    listState = listState,
                    snackbarHostState = snackbarHostState,
                    onOverlayChange = { listOverlayOpen = it },
                    heroPaused = addSheetOpen,
                    contentPadding = bottom,
                )
            }
        }
    }

    if (addSheetOpen) {
        AddMatchSheet(
            onNewMatch = { addSheetOpen = false; onNewMatch() },
            onRecord = { addSheetOpen = false; onRecord() },
            onImport = { addSheetOpen = false; onImport() },
            onDismiss = { addSheetOpen = false },
        )
    }
}

/**
 * The side insets the Scaffold's bottom-only contentWindowInsets leaves out,
 * for the three things on this screen that have to take them: content, the
 * Add button and the snackbar.
 */
private val HorizontalSafeDrawing: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)

/**
 * The Add button's footprint above the gesture inset: the 56dp button, its
 * 16dp margin, and 16dp of air above the last row.
 */
private val ADD_BUTTON_CLEARANCE = 88.dp
