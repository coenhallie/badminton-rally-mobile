package com.badmintontracker.android.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.badmintontracker.android.players.AddPlayer
import com.badmintontracker.android.cliplist.ClipListScreen
import com.badmintontracker.android.cliplist.ClipListViewModel
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.shared.matchlist.MatchListItem
import com.badmintontracker.shared.players.PlayersCopy
import com.badmintontracker.shared.repo.MediaRepository
import com.badmintontracker.shared.repo.SharesRepository

/**
 * All matches: the match list, every match whether a player holds it or not,
 * with its filters, sort and "Shared with me". Pushed from the Players screen,
 * which holds the gear; this is the list under a back bar, with the same Add
 * button Players and a player's page carry. Its empty state says to score,
 * record or import, and a page that says so has to offer the way to.
 * It draws the app's one root snackbar host ([snackbarHostState]), as Players
 * does, so a finished run or a failed refresh is said here too.
 *
 * Mirrors iosApp's MatchesScreen.swift.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllMatchesScreen(
    vm: ClipListViewModel,
    shares: SharesRepository,
    media: MediaRepository,
    /** A row's tap: the match page on its default section. */
    onOpenMatch: (MatchListItem) -> Unit,
    onBack: () -> Unit,
    onAnalyze: (entryId: String) -> Unit,
    onMarkCourt: (entryId: String) -> Unit,
    onRemoveFromPhone: (entryId: String) -> Unit,
    /** "Add player" in the row's details sheet: PlayersViewModel.create. */
    onAddPlayer: AddPlayer,
    /** The Add match sheet's three ways in, as on Players: none of them pre-fills a player. */
    onNewMatch: () -> Unit,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    /** AuthGate's one root host: see RootAnnouncementsHost. */
    snackbarHostState: SnackbarHostState,
    /** Any sheet or dialog the list has open: see AuthGate's finished-run watcher. */
    onOverlayChange: (Boolean) -> Unit,
) {
    val listState = rememberLazyListState()
    var addSheetOpen by rememberSaveable { mutableStateOf(false) }
    var listOverlayOpen by remember { mutableStateOf(false) }
    val overlayOpen = addSheetOpen || listOverlayOpen
    LaunchedEffect(overlayOpen) { onOverlayChange(overlayOpen) }
    // Leaving the screen takes its overlays with it, whatever was last said.
    DisposableEffect(Unit) { onDispose { onOverlayChange(false) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // The bar takes the status bar itself; the list takes the bottom
        // inset as content padding, so its last row scrolls clear of the
        // gesture bar rather than stopping short of it.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        topBar = {
            TopAppBar(
                title = { Text(PlayersCopy.ALL_MATCHES) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { BackgroundWorkAction() },
                // Pinned to what the other pushed screens' bars draw, as
                // Settings does, so this bar cannot drift from theirs.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        },
        floatingActionButton = {
            // Labelled at the top of the list, folded to "+" once it scrolls, as on Players.
            val expanded by remember { derivedStateOf { addButtonExpanded(listState.firstVisibleItemIndex) } }
            AddMatchButton(
                expanded = expanded,
                onClick = { addSheetOpen = true },
                modifier = Modifier.windowInsetsPadding(HorizontalSafeDrawing),
            )
        },
        // The shape set here, not left to the theme's extraSmall (8dp): the
        // iOS toast is ShuttlRadius.medium, and the two are one message.
        snackbarHost = {
            SnackbarHost(snackbarHostState, Modifier.windowInsetsPadding(HorizontalSafeDrawing)) { data ->
                Snackbar(data, shape = RoundedCornerShape(ShuttlRadius.medium))
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .windowInsetsPadding(HorizontalSafeDrawing),
        ) {
            ClipListScreen(
                vm = vm, shares = shares, media = media,
                onOpenMatch = onOpenMatch,
                onAnalyze = onAnalyze,
                onMarkCourt = onMarkCourt,
                onRemoveFromPhone = onRemoveFromPhone,
                onAddPlayer = onAddPlayer,
                listState = listState,
                onOverlayChange = { listOverlayOpen = it },
                // The last row and its menu scroll clear of the Add button too.
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + ADD_BUTTON_CLEARANCE),
            )
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
