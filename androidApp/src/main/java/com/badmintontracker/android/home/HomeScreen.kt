package com.badmintontracker.android.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.cliplist.ClipListScreen
import com.badmintontracker.android.cliplist.ClipListViewModel
import com.badmintontracker.shared.home.HomeTab
import com.badmintontracker.shared.prefs.ThemePreferenceRepository
import com.badmintontracker.shared.repo.MediaRepository
import com.badmintontracker.shared.repo.SharesRepository

/**
 * The tab host: the bottom bar, the Add button, and the one snackbar host,
 * over whichever tab is selected.
 *
 * It is the root destination (Route.Home) and the bar lives INSIDE it, so every
 * other route still pushes over the whole screen, bar included: the scoring
 * board needs no code to hide it. The selected tab is owned by AuthGate, above
 * the NavHost, because the finished-run watcher there needs to read it.
 *
 * Mirrors iosApp's HomeView.swift.
 */
@Composable
fun HomeScreen(
    selectedTab: HomeTab,
    onSelectTab: (HomeTab) -> Unit,
    /** Any sheet or dialog over the tabs: the add sheet here, or one the list owns. */
    onOverlayChange: (Boolean) -> Unit,
    vm: ClipListViewModel,
    shares: SharesRepository,
    media: MediaRepository,
    themePrefs: ThemePreferenceRepository,
    email: String?,
    /**
     * The Analytics tab's content, given the bottom padding. A slot built by
     * AuthGate so the progress flows it collects are read only while that tab
     * is composed: collected here, every progress tick would recompose the
     * whole tab host, on Matches and Settings too.
     */
    analyticsContent: @Composable (PaddingValues) -> Unit,
    onMatchClick: (videoId: String) -> Unit,
    onScoreMatchClick: (scoreLogId: String) -> Unit,
    onNewMatch: () -> Unit,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    onLabels: () -> Unit,
    onOpenHeatmap: (heatmapId: String) -> Unit,
    onOpenLocalClips: (entryId: String) -> Unit,
    onLocalClick: (entryId: String) -> Unit,
    onAnalyze: (entryId: String) -> Unit,
    onMarkCourt: (entryId: String) -> Unit,
    onRemoveFromPhone: (entryId: String) -> Unit,
    onLocalResultSeen: (entryId: String) -> Unit,
    onLocalDetailsSaved: (id: String, title: String, description: String) -> Unit,
    autoDetailsEntryId: String?,
    onAutoDetailsShown: () -> Unit,
    intakeError: String?,
    onIntakeErrorShown: () -> Unit,
) {
    val themeMode by themePrefs.mode.collectAsStateWithLifecycle()
    var addSheetOpen by remember { mutableStateOf(false) }
    var listOverlayOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    // Hoisted here, not inside the list: rememberLazyListState is itself
    // saveable, so SaveableStateProvider below would keep it fine either way,
    // but the Add button (in floatingActionButton, a sibling of the
    // SaveableStateProvider content, not a descendant of it) reads
    // firstVisibleItemIndex to decide whether to show its label, and can only
    // do that if the state lives somewhere it can reach.
    val listState = rememberLazyListState()
    val tabs = rememberSaveableStateHolder()

    LaunchedEffect(addSheetOpen, listOverlayOpen) { onOverlayChange(addSheetOpen || listOverlayOpen) }

    // Leaving Matches disposes ClipListScreen outright (the `when` below only
    // composes the selected tab), so a sheet or dialog it had open can never
    // call onOverlayChange(false) itself on the way out. Without this,
    // listOverlayOpen would stick true - the tab cannot be switched by hand
    // while one is open, but this covers the reset for whatever gets it
    // there anyway, now or later.
    LaunchedEffect(selectedTab) { if (selectedTab != HomeTab.MATCHES) listOverlayOpen = false }

    // Back on Analytics or Settings returns to Matches; only Back on Matches
    // leaves the app. Without this, Back from any tab fell through to the
    // NavHost, which has nothing under Route.Home, and closed the app.
    BackHandler(enabled = selectedTab != HomeTab.MATCHES) { onSelectTab(HomeTab.MATCHES) }

    // Opening Analytics pulls, as every visit to Route.Analytics used to (it
    // built a fresh ClipListViewModel, whose init refreshes). iOS's Analytics
    // `.task` refreshes on each appearance for the same reason.
    LaunchedEffect(selectedTab) { if (selectedTab == HomeTab.ANALYTICS) vm.refresh() }

    // Record and import failures. Add is only on the Matches tab, so this is
    // where they come from.
    LaunchedEffect(intakeError) {
        val err = intakeError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err)
        onIntakeErrorShown()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Each tab draws its own title bar under the status bar (TabTopBar), so
        // the Scaffold hands content the bottom inset only; the bar's own
        // insets are NavigationBar's.
        contentWindowInsets = WindowInsets(0),
        bottomBar = { HomeNavigationBar(selected = selectedTab, onSelect = onSelectTab) },
        // The Add button and the snackbar sit outside the content Box below, so
        // its horizontal inset does not reach them: each takes its own, or a
        // landscape cutout or side gesture inset would clip the button's edge
        // and the snackbar's.
        floatingActionButton = {
            if (selectedTab == HomeTab.MATCHES) {
                val expanded by remember { derivedStateOf { addButtonExpanded(listState.firstVisibleItemIndex) } }
                AddMatchButton(
                    expanded = expanded,
                    onClick = { addSheetOpen = true },
                    modifier = Modifier.windowInsetsPadding(HorizontalSafeDrawing),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState, Modifier.windowInsetsPadding(HorizontalSafeDrawing)) },
    ) { padding ->
        // Room for the Add button above the bar: the last row and its menu must
        // scroll clear of both. The Scaffold's own `padding` from
        // contentWindowInsets = WindowInsets(0) already includes the bar's
        // height, so this only adds the button's own footprint on top of it.
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding() + ADD_BUTTON_CLEARANCE)
        Box(
            // contentWindowInsets = WindowInsets(0) above only drops the insets
            // TabTopBar/NavigationBar already handle (top/bottom); it also zeroes
            // the horizontal ones, so a landscape cutout or gesture inset would
            // otherwise cut into content on that side.
            Modifier.fillMaxSize().windowInsetsPadding(HorizontalSafeDrawing),
        ) {
            tabs.SaveableStateProvider(selectedTab.name) {
                when (selectedTab) {
                    HomeTab.MATCHES -> MatchesTab {
                        ClipListScreen(
                            vm = vm, shares = shares, media = media,
                            onMatchClick = onMatchClick,
                            onScoreMatchClick = onScoreMatchClick,
                            onLocalClick = onLocalClick,
                            onAnalyze = onAnalyze,
                            onMarkCourt = onMarkCourt,
                            onOpenHeatmap = onOpenHeatmap,
                            onOpenLocalClips = onOpenLocalClips,
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
                    HomeTab.ANALYTICS -> analyticsContent(PaddingValues(bottom = padding.calculateBottomPadding()))
                    HomeTab.SETTINGS -> SettingsTab(
                        themeMode = themeMode,
                        onToggleTheme = themePrefs::toggle,
                        email = email,
                        onLabels = onLabels,
                        onSignOut = vm::signOut,
                        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
                    )
                }
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
 * The side insets contentWindowInsets = WindowInsets(0) drops, for the three
 * things in the tab host that have to take them back: content, the Add button
 * and the snackbar.
 */
private val HorizontalSafeDrawing: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)

/** The 56dp button, its 16dp margin, and 16dp of air above the last row. */
private val ADD_BUTTON_CLEARANCE = 88.dp
