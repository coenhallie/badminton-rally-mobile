package com.badmintontracker.android

import com.badmintontracker.android.localanalysis.BackgroundWorkMonitor
import com.badmintontracker.android.localanalysis.LocalBackgroundWork
import com.badmintontracker.android.localanalysis.LocalBackgroundWorkClick
import com.badmintontracker.shared.local.DeviceThroughputRepository
import com.badmintontracker.android.localanalysis.LocalAnalysisRunner
import com.badmintontracker.android.localanalysis.AnalysisTarget
import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.home.HomeTopBar
import com.badmintontracker.android.ui.theme.ShuttlTypeExtras
import com.badmintontracker.shared.signin.SignInCopy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.badmintontracker.android.clipdetail.ClipDetailScreen
import com.badmintontracker.android.clipdetail.ClipDetailViewModel
import com.badmintontracker.android.cliplist.ClipListViewModel
import com.badmintontracker.android.cliplist.MatchSummaryViewModel
import com.badmintontracker.android.cliplist.StoredResults
import com.badmintontracker.android.cliplist.StoredResultsReader
import com.badmintontracker.android.home.AllMatchesScreen
import com.badmintontracker.android.home.RootAnnouncementsHost
import com.badmintontracker.android.home.finishedRuns
import com.badmintontracker.android.home.runSnackbarUpdate
import com.badmintontracker.android.settings.SettingsScreen
import com.badmintontracker.android.match.MatchScreen
import com.badmintontracker.android.match.MatchViewModel
import com.badmintontracker.shared.prefs.ThemePreferenceRepository
import com.badmintontracker.android.localvideo.AutoMatchDetailsHost
import com.badmintontracker.android.localvideo.LocalPlayerViewModel
import com.badmintontracker.android.localvideo.LocalVideoListViewModel
import com.badmintontracker.android.localvideo.MatchTarget
import com.badmintontracker.android.localvideo.court.CourtMarkingScreen
import com.badmintontracker.android.localvideo.court.CourtMarkingViewModel
import com.badmintontracker.android.localvideo.court.DefaultCourtFinderPort
import com.badmintontracker.android.localvideo.court.loadFirstFrame
import com.badmintontracker.android.localvideo.rememberVideoIntake
import com.badmintontracker.android.labels.LabelsScreen
import com.badmintontracker.android.labels.LabelsViewModel
import com.badmintontracker.android.players.PlayerScreen
import com.badmintontracker.android.players.HomeEndDialog
import com.badmintontracker.android.players.PlayersScreen
import com.badmintontracker.android.players.PlayersViewModel
import com.badmintontracker.android.nav.AuthRedirect
import com.badmintontracker.android.nav.Route
import com.badmintontracker.android.nav.authRedirect
import com.badmintontracker.android.nav.authStartDestination
import com.badmintontracker.android.scoring.AndroidPlaceNames
import com.badmintontracker.android.scoring.AttachIntent
import com.badmintontracker.android.scoring.NewMatchScreen
import com.badmintontracker.android.scoring.NewMatchViewModel
import com.badmintontracker.android.scoring.ScoringScreen
import com.badmintontracker.android.scoring.ScoringViewModel
import com.badmintontracker.android.signin.SignInScreen
import com.badmintontracker.android.signin.SignInViewModel
import com.badmintontracker.shared.localvideo.AnalyzeCoordinator
import com.badmintontracker.shared.localvideo.canResumeFailedAnalysis
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.localvideo.setKeypoints
import com.badmintontracker.shared.localvideo.setMatchPlayers
import com.badmintontracker.shared.analytics.courtSideNames
import com.badmintontracker.shared.players.MatchPlayers
import com.badmintontracker.shared.players.asksForHomeEnd
import com.badmintontracker.shared.players.homeEndTitle
import com.badmintontracker.shared.players.saveEntryMatchDetailsOrMessage
import com.badmintontracker.shared.RallyApp
import com.badmintontracker.shared.repo.AuthState
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.android.match.AnalysisSection
import com.badmintontracker.android.match.DeviceRalliesSection
import com.badmintontracker.android.match.MatchVideoSection
import com.badmintontracker.shared.matchlist.MatchListItem
import com.badmintontracker.shared.matchpage.MatchPageSection
import com.badmintontracker.shared.matchpage.matchPageItem

@Composable
fun AuthGate(
    rally: RallyApp,
    themePrefs: ThemePreferenceRepository,
    localVideos: LocalVideoRepository,
    coordinator: AnalyzeCoordinator,
    localAnalysis: LocalAnalysisRunner,
    backgroundWork: BackgroundWorkMonitor,
    throughput: DeviceThroughputRepository,
    localAnnotations: LocalAnnotationsRepository,
) {
    // The shared projection rather than the raw SessionStatus, so both phones
    // agree on what counts as signed in. Reading the status here used to file
    // Initializing and RefreshFailure under "signed out": SignIn flashed on every
    // cold start, and a token refresh with no signal replaced a live scoring
    // board with the sign-in screen.
    val auth by rally.authState.collectAsStateWithLifecycle(initialValue = AuthState.LOADING)

    when (auth) {
        AuthState.LOADING -> Splash()
        else -> {
            val nav = rememberNavController()
            // Remembered: see authStartDestination. Later changes of session are
            // navigations, below, never a new graph.
            val start: Route = remember { authStartDestination(auth) }

            // Any sheet or dialog over the Players screen, All matches or a
            // player's page, each reported by its screen, and the root host's
            // result dialog. Part of "nothing above": see the finished-run
            // watcher below. One per screen, so one leaving cannot clear what
            // another just said.
            var playersOverlayOpen by remember { mutableStateOf(false) }
            var allMatchesOverlayOpen by remember { mutableStateOf(false) }
            var playerOverlayOpen by remember { mutableStateOf(false) }
            var resultDialogOpen by remember { mutableStateOf(false) }
            // Runs the watcher below has just announced, for RootAnnouncementsHost
            // to say on the snackbar; it clears them once it has read them.
            var announced by remember { mutableStateOf(emptySet<String>()) }
            // The app's one root snackbar host: Players, All matches and a
            // player's page draw it; RootAnnouncementsHost and the two player
            // screens post to it.
            val rootSnackbar = remember { SnackbarHostState() }

            LaunchedEffect(auth) {
                val onSignIn = nav.currentDestination?.hasRoute<Route.SignIn>() == true
                when (authRedirect(auth, onSignIn)) {
                    // Session ended anywhere in the app (sign-out, token revocation):
                    // clear the whole back stack and land on SignIn.
                    AuthRedirect.TO_SIGN_IN -> nav.navigate(Route.SignIn) {
                        popUpTo(nav.graph.id) { inclusive = true }
                    }
                    // Session arrived while on SignIn (e.g. a session restored via
                    // deep link, so SignInViewModel never sees the completed sign-in).
                    AuthRedirect.TO_HOME -> nav.navigate(Route.Players) {
                        popUpTo(Route.SignIn) { inclusive = true }
                    }
                    null -> Unit
                }
            }

            var intakeError by remember { mutableStateOf<String?>(null) }
            // Persist first, then hand the id on so the details sheet can open over
            // an entry that already exists. Held until that sheet closes, not
            // just until it opens: see the finished-run watcher's "nothing above".
            var autoDetailsEntryId by remember { mutableStateOf<String?>(null) }
            // Signals a player's page to switch to its Matches tab after a
            // standalone video is imported/recorded for that player.
            var playerMatchesSignal by remember { mutableStateOf<Pair<String, Int>?>(null) }
            // A video just added to a scored match with a profile on it, waiting
            // on "Which end was Home on?" before court marking. Saveable, so a
            // recreation under the dialog still asks, and still goes on to mark.
            var homeEndEntryId by rememberSaveable { mutableStateOf<String?>(null) }
            // For the sheet's save, which outlives the sheet: it may still be
            // waiting on the server when the sheet has gone.
            val rootScope = rememberCoroutineScope()
            // Hoisted above the NavHost: the Players screen (record/import with
            // no match), a player's page (for that player) and the match
            // destination (record/import for this match) drive the same picker,
            // so there can only be one implementation of "getting a video into
            // the app".
            val intake = rememberVideoIntake(
                onAdded = { entry ->
                    localVideos.add(entry)
                    // A video picked for a match already carries that match's name,
                    // and videos.title is insert-only, so there is nothing to ask
                    // and nowhere to change it later.
                    if (entry.scoreLogId == null) {
                        // The naming sheet is drawn above the NavHost
                        // (AutoMatchDetailsHost), so there is nothing to
                        // select: the coach comes back from the camera or
                        // picker to whatever screen they left, and the sheet
                        // opens over it. If started from a player's page,
                        // switch that page to its Matches tab so the new match
                        // is immediately visible once the sheet closes.
                        entry.homePlayerId?.let { pid ->
                            playerMatchesSignal = pid to ((playerMatchesSignal?.second ?: 0) + 1)
                        }
                        autoDetailsEntryId = entry.id
                    } else {
                        // The coach already said he wants this video analysed;
                        // making him find an Analyze button afterwards is a second
                        // decision for a question he answered. A match with a
                        // profile on it first asks which end Home was at
                        // (HomeEndDialog, below), then marks the court.
                        val log = entry.scoreLogId?.let(rally.scoreLogs::get)
                        if (log != null && asksForHomeEnd(log)) homeEndEntryId = entry.id
                        else nav.navigate(Route.CourtMarking(entry.id))
                    }
                },
                onError = { intakeError = it },
            )

            // One reader for the one ClipListViewModel below, which the
            // Players screen, All matches and the match page all read: each
            // shows what earlier runs left on disk, and must probe it the same
            // way.
            val storedResults = remember(localAnalysis) { storedResultsReader(localAnalysis) }

            // One factory for the one list view model, scoped to the Route.Players
            // entry: the root, under every other destination. All matches, a
            // player's page, Settings and the match page read that entry's
            // instance rather than building their own: the match page finds its
            // match in that list (matchPageItem), and a second instance would be
            // a second, later copy of the same list.
            val clipListVmFactory = remember(storedResults) {
                viewModelFactory {
                    initializer {
                        ClipListViewModel(
                            rally.clips, rally.auth, rally.shares, rally.videos, rally.scoreLogs,
                            localVideos, coordinator, localAnnotations, rally.players,
                            deviceStates = localAnalysis.state,
                            storedResults = storedResults,
                        )
                    }
                }
            }

            // Scoped to the Route.Players entry, like the list's: a player's
            // page reads the same instance through that entry.
            val playersVmFactory = remember {
                viewModelFactory {
                    initializer {
                        PlayersViewModel(rally.players, rally.scoreLogs, localVideos, rally.auth::currentUserId)
                    }
                }
            }

            // Resume a failed cloud run whose court points are saved, else mark
            // the court. The one decision behind every Analyze and Retry: the
            // list's rows, the match page's button under the player, its
            // status banner and its "Analyze again". A cloud run that failed at
            // TRIGGER already has this video's four court corners saved, and
            // sending the coach back to mark them again both wastes the marking
            // and means the coordinator's resume-from-the-failed-step is never
            // reached.
            //
            // It reads correctly for a device failure too, and not by
            // accident: a device run never moves entry.stage, so a row
            // showing "Retry" because LocalAnalysisState is Failed falls
            // through to court marking, which is where a device re-run
            // has to start (LocalAnalysisRunner.start takes keypoints
            // from the screen, not from the entry).
            val analyzeEntry: (String) -> Unit = remember(nav) {
                { entryId ->
                    localVideos.get(entryId)?.let { entry ->
                        if (canResumeFailedAnalysis(entry)) coordinator.retry(entry.id)
                        else nav.navigate(Route.CourtMarking(entry.id))
                    }
                }
            }

            // Every way into a match: a row's tap, and a finished run's View on
            // the list's snackbar. All three ids ride along; the page finds its
            // row by whichever the list still has.
            val openMatch: (MatchListItem, MatchPageSection?) -> Unit = remember(nav) {
                { item, section ->
                    nav.navigate(
                        Route.Match(
                            scoreLogId = item.scoreLogId, videoId = item.videoId,
                            entryId = item.entryId, section = section?.name,
                        )
                    )
                }
            }

            // The activity's, not a destination's: read above the NavHost, so it
            // goes below RESUMED while the camera or the photo picker (another
            // activity) is in front of ours. Read inside the watcher below
            // rather than keyed on, since restarting that effect would reset
            // its seen-set.
            val activityLifecycle = LocalLifecycleOwner.current.lifecycle

            // A run that lands while Players, All matches or a player's page is
            // in front is announced there: RootAnnouncementsHost says so on the
            // root snackbar, with a View that opens the match. Nothing navigates
            // on its own.
            //
            // Above the NavHost on purpose. announceFinishedRuns updates its
            // seen-set on every tick whether or not it announces, and that is
            // what stops a run which finished while the coach was three screens
            // away from ambushing them the next time they land back on
            // Route.Players. Observed from inside Route.Players it could not tell
            // those apart: Route.Players leaves composition on every trip out and
            // comes back looking like a fresh arrival.
            //
            // The seen-set lives in the coroutine rather than in a
            // `remember`ed state: nothing draws it, and a snapshot write would
            // be a second invalidation of this scope for no one's benefit. It
            // survives exactly as long as a `remember` would, the effect being
            // scoped to this composition. Rotation does not cost it: the
            // activity declares configChanges for orientation. Process death
            // does not either, because the runner comes back new and holds no
            // finished run to re-announce.
            //
            // One recreation does reset it, and is left alone deliberately: a
            // SYSTEM dark-mode flip, uiMode not being in that configChanges
            // list. A run that landed while the coach was away would then
            // announce itself on the next visit to Players. It needs the
            // system theme to change inside that window - the app's own
            // toggle does not go through uiMode - and the cost of being wrong
            // is one late snackbar about a run the coach asked for.
            //
            // Collected here rather than read as composition state on purpose.
            // `collectAsStateWithLifecycle` would put a snapshot read in THIS
            // scope, which is the one that calls NavHost, so every progress
            // tick - several a second while a run is going - would invalidate
            // the host of the whole nav graph. Keying the effect cannot help
            // with that; the recomposition happens before the effect is
            // consulted. Mapping to the finished set and dropping duplicates
            // upstream means the body below runs twice per run instead.
            LaunchedEffect(localAnalysis, coordinator, nav) {
                var seen = emptySet<String>()
                // Both pipelines, not just the device one. A cloud run keeps its
                // entry, and its end showed on the list only as the row's status
                // line changing - nothing saying the result was ready or where
                // to find it. Which runs count, and why a device run that found
                // nothing does not: see finishedRuns (home/RunSnackbarUpdate.kt).
                combine(localAnalysis.state, coordinator.finishedRuns, ::finishedRuns)
                    .distinctUntilChanged()
                    .collect { finished ->
                        // "Nothing above" is more than the back stack and the
                        // screen's own overlays. The back-stack entry is still
                        // the list's while the camera or picker is open, and this
                        // collector keeps running with our activity stopped, so
                        // without the RESUMED check a run landing then was
                        // announced out of sight, its snackbar timing out before
                        // the coach came back. And a video just added with no
                        // match has its naming sheet up (autoDetailsEntryId)
                        // until the coach closes it: they are about to type a
                        // name, and a View offered then pulls the other way.
                        //
                        // The seen-set rule does not change: a run that lands
                        // while the app is in the background is marked seen and
                        // does not announce itself later, as for any run that
                        // lands while the coach is elsewhere.
                        val front = nav.currentBackStackEntry?.destination
                        val onAList = when {
                            front?.hasRoute<Route.Players>() == true -> !playersOverlayOpen
                            front?.hasRoute<Route.AllMatches>() == true -> !allMatchesOverlayOpen
                            front?.hasRoute<Route.Player>() == true -> !playerOverlayOpen
                            else -> false
                        }
                        val nothingAbove = activityLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            autoDetailsEntryId == null &&
                            homeEndEntryId == null &&
                            !resultDialogOpen &&
                            onAList
                        // Merged with the runs still waiting to be shown: see
                        // runSnackbarUpdate.
                        val update = runSnackbarUpdate(
                            finished = finished,
                            seen = seen,
                            onHome = nothingAbove,
                            waiting = announced,
                        )
                        seen = update.seen
                        update.announced?.let { announced = it }
                    }
            }
            // Provided around the NavHost so every destination's bar can show it
            // without the state appearing in any screen's signature. Providing the
            // StateFlow itself keeps progress ticks scoped to BackgroundWorkAction.
            CompositionLocalProvider(
                LocalBackgroundWork provides backgroundWork.work,
                // remembered: this local is static, so a new lambda identity
                // would invalidate the whole NavHost subtree.
                LocalBackgroundWorkClick provides remember(nav) {
                    {
                        // "Go to matches": the run it points at is a row on
                        // All matches, over Players. Back to the All matches
                        // already on the stack when there is one, so its filter,
                        // sort and scroll survive; a fresh one over Players
                        // otherwise. Never a second one: the indicator is
                        // reachable from any screen.
                        val onAllMatches =
                            nav.currentBackStackEntry?.destination?.hasRoute<Route.AllMatches>() == true
                        if (!onAllMatches && !nav.popBackStack<Route.AllMatches>(inclusive = false)) {
                            nav.navigate(Route.AllMatches) { popUpTo<Route.Players>() }
                        }
                    }
                },
            ) {
            NavHost(
                navController = nav,
                startDestination = start,
                enterTransition = {
                    fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                        slideInHorizontally(animationSpec = tween(220, easing = FastOutSlowInEasing)) { it / 12 }
                },
                exitTransition = {
                    fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                        slideOutHorizontally(animationSpec = tween(180, easing = FastOutSlowInEasing)) { -it / 24 }
                },
                popEnterTransition = {
                    fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                        slideInHorizontally(animationSpec = tween(220, easing = FastOutSlowInEasing)) { -it / 24 }
                },
                popExitTransition = {
                    fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                        slideOutHorizontally(animationSpec = tween(180, easing = FastOutSlowInEasing)) { it / 12 }
                },
            ) {
                composable<Route.SignIn> {
                    val signInVm: SignInViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { SignInViewModel(rally.auth) }
                        }
                    )
                    SignInScreen(
                        vm = signInVm,
                        themePrefs = themePrefs,
                        onSignedIn = {
                            nav.navigate(Route.Players) {
                                popUpTo(Route.SignIn) { inclusive = true }
                            }
                        },
                    )
                }
                composable<Route.Players> {
                    // Created here, the root: every other destination that
                    // reads either one fetches this entry's instance.
                    val clipListVm: ClipListViewModel = viewModel(factory = clipListVmFactory)
                    val playersVm: PlayersViewModel = viewModel(factory = playersVmFactory)
                    PlayersScreen(
                        vm = playersVm,
                        clipListVm = clipListVm,
                        onOverlayChange = { playersOverlayOpen = it },
                        onOpenPlayer = { id -> nav.navigate(Route.Player(id)) },
                        onAllMatches = { nav.navigate(Route.AllMatches) },
                        onSettings = { nav.navigate(Route.Settings) },
                        onNewMatch = { nav.navigate(Route.NewMatch()) },
                        onRecord = { intake.record(null, null) },
                        onImport = { intake.import(null, null) },
                        snackbarHostState = rootSnackbar,
                    )
                }
                composable<Route.AllMatches> { entry ->
                    val playersEntry = remember(entry) { nav.getBackStackEntry<Route.Players>() }
                    val clipListVm: ClipListViewModel = viewModel(playersEntry, factory = clipListVmFactory)
                    val playersVm: PlayersViewModel = viewModel(playersEntry, factory = playersVmFactory)
                    val localVm: LocalVideoListViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { LocalVideoListViewModel(localVideos, coordinator, localAnnotations) }
                        }
                    )
                    AllMatchesScreen(
                        vm = clipListVm,
                        shares = rally.shares,
                        media = rally.media,
                        onOpenMatch = { openMatch(it, null) },
                        onBack = { nav.popBackStack() },
                        onAnalyze = analyzeEntry,
                        // By the row's own entry id: see ClipListScreen.act.
                        onMarkCourt = { entryId -> nav.navigate(Route.CourtMarking(entryId)) },
                        onRemoveFromPhone = localVm::remove,
                        onAddPlayer = playersVm::create,
                        onNewMatch = { nav.navigate(Route.NewMatch()) },
                        onRecord = { intake.record(null, null) },
                        onImport = { intake.import(null, null) },
                        snackbarHostState = rootSnackbar,
                        onOverlayChange = { allMatchesOverlayOpen = it },
                    )
                }
                composable<Route.Player> { entry ->
                    val args = entry.toRoute<Route.Player>()
                    // Both of the Players entry's view models, never a second
                    // instance: the tiles, the Matches tab and the Players row
                    // read one list and one set of players.
                    val playersEntry = remember(entry) { nav.getBackStackEntry<Route.Players>() }
                    val clipListVm: ClipListViewModel = viewModel(playersEntry, factory = clipListVmFactory)
                    val playersVm: PlayersViewModel = viewModel(playersEntry, factory = playersVmFactory)
                    val localVm: LocalVideoListViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { LocalVideoListViewModel(localVideos, coordinator, localAnnotations) }
                        }
                    )
                    PlayerScreen(
                        playerId = args.playerId,
                        vm = playersVm,
                        clipListVm = clipListVm,
                        shares = rally.shares,
                        media = rally.media,
                        onBack = { nav.popBackStack() },
                        // Replaces this page rather than stacking on it, so
                        // Back from any player goes to Players. By type: the
                        // one Player entry, whichever player it holds.
                        onSwitchPlayer = { id ->
                            nav.navigate(Route.Player(id)) { popUpTo<Route.Player> { inclusive = true } }
                        },
                        onGone = { nav.popBackStack<Route.Players>(inclusive = false) },
                        onOpenMatch = { openMatch(it, null) },
                        onAnalyze = analyzeEntry,
                        // By the row's own entry id: see ClipListScreen.act.
                        onMarkCourt = { entryId -> nav.navigate(Route.CourtMarking(entryId)) },
                        onRemoveFromPhone = localVm::remove,
                        onNewMatch = { nav.navigate(Route.NewMatch(args.playerId)) },
                        onRecord = { intake.record(null, args.playerId) },
                        onImport = { intake.import(null, args.playerId) },
                        snackbarHostState = rootSnackbar,
                        onOverlayChange = { playerOverlayOpen = it },
                        showMatchesTrigger = if (playerMatchesSignal?.first == args.playerId) {
                            playerMatchesSignal?.second ?: 0
                        } else {
                            0
                        },
                    )
                }
                composable<Route.Settings> { entry ->
                    // Sign out goes through the list's view model (vm::signOut),
                    // as it always has, rather than a view model of this page's
                    // own for one call: the Players entry's instance, fetched the
                    // way the match page fetches it.
                    val playersEntry = remember(entry) { nav.getBackStackEntry<Route.Players>() }
                    val homeVm: ClipListViewModel = viewModel(playersEntry, factory = clipListVmFactory)
                    val themeMode by themePrefs.mode.collectAsStateWithLifecycle()
                    SettingsScreen(
                        themeMode = themeMode,
                        onToggleTheme = themePrefs::toggle,
                        email = rally.auth.currentUserEmail(),
                        onLabels = { nav.navigate(Route.Labels) },
                        onSignOut = homeVm::signOut,
                        onBack = { nav.popBackStack() },
                    )
                }
                composable<Route.NewMatch> { entry ->
                    val args = entry.toRoute<Route.NewMatch>()
                    val appContext = LocalContext.current.applicationContext
                    val vm: NewMatchViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                // A player's page started it: the match is theirs, at home.
                                NewMatchViewModel(rally.scoreLogs, AndroidPlaceNames(appContext), args.playerId)
                            }
                        }
                    )
                    NewMatchScreen(
                        vm = vm,
                        // Straight to the board, not back to the list and not to
                        // the record: creating a match courtside means being about
                        // to score it.
                        onCreated = { id ->
                            nav.navigate(Route.Scoring(id)) {
                                // By type: see Route.NewMatch.
                                popUpTo<Route.NewMatch> { inclusive = true }
                            }
                        },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable<Route.Scoring> { entry ->
                    val args = entry.toRoute<Route.Scoring>()
                    // The Players entry's, as every picker's "Add player" is.
                    val playersEntry = remember(entry) { nav.getBackStackEntry<Route.Players>() }
                    val playersVm: PlayersViewModel = viewModel(playersEntry, factory = playersVmFactory)
                    val vm: ScoringViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                ScoringViewModel(rally.scoreLogs, rally.labels, rally.players, args.scoreLogId)
                            }
                        }
                    )
                    ScoringScreen(
                        vm = vm,
                        onBack = { nav.popBackStack() },
                        onAddPlayer = playersVm::create,
                        // Lands on the match page rather than popping back, because
                        // a match just created and scored in one sitting (NewMatch
                        // -> Scoring, no Match page underneath yet) has nothing to
                        // pop back to. The chosen intent (if any) rides along so
                        // the match page can act on it once.
                        //
                        // The board replaces itself, and the match page it was
                        // opened from when there is one, so a single Match entry
                        // stays on the stack either way and Back goes to the list
                        // the coach started from (Players, All matches). By type,
                        // the topmost Match: see Route.Match on reconstructing one.
                        onFinished = { intent ->
                            val fromMatchPage =
                                nav.previousBackStackEntry?.destination?.hasRoute<Route.Match>() == true
                            nav.navigate(
                                Route.Match(scoreLogId = args.scoreLogId, attach = intent?.name)
                            ) {
                                if (fromMatchPage) popUpTo<Route.Match> { inclusive = true }
                                else popUpTo<Route.Scoring> { inclusive = true }
                            }
                        },
                    )
                }
                composable<Route.Labels> {
                    val vm: LabelsViewModel = viewModel(
                        factory = viewModelFactory { initializer { LabelsViewModel(rally.labels) } }
                    )
                    LabelsScreen(vm = vm, onBack = { nav.popBackStack() })
                }
                composable<Route.Match> { entry ->
                    val args = entry.toRoute<Route.Match>()
                    // Route.Players is always under a match page (the start
                    // destination, and every push is over it), so its entry is
                    // there to scope the shared list view model to.
                    val playersEntry = remember(entry) { nav.getBackStackEntry<Route.Players>() }
                    val clipListVm: ClipListViewModel = viewModel(playersEntry, factory = clipListVmFactory)
                    val matchPlayersVm: PlayersViewModel = viewModel(playersEntry, factory = playersVmFactory)
                    val listState by clipListVm.state.collectAsStateWithLifecycle()
                    val logs by rally.scoreLogs.logs.collectAsStateWithLifecycle()
                    val logVideoId = logs.firstOrNull { it.id == args.scoreLogId }?.videoId
                    val item = listState.matchList.matchPageItem(args.scoreLogId, args.videoId ?: logVideoId, args.entryId)
                    // The row's video first: a video only on this phone gains
                    // one when a cloud run creates it, and the page must start
                    // showing its rallies without a re-entry. Then the route's,
                    // then the score log's, for a match bound after it opened.
                    val effectiveVideoId = item?.videoId ?: args.videoId ?: logVideoId
                    val summaryVm: MatchSummaryViewModel? = effectiveVideoId?.let { vid ->
                        viewModel(
                            key = vid,
                            factory = viewModelFactory {
                                initializer { MatchSummaryViewModel(rally.clips, rally.annotations, vid) }
                            }
                        )
                    }
                    val matchVm: MatchViewModel = viewModel(
                        key = args.scoreLogId,
                        factory = viewModelFactory {
                            initializer {
                                MatchViewModel(
                                    rally.scoreLogs, localVideos, rally.clips,
                                    rally.videos, localAnnotations, rally.players, args.scoreLogId,
                                )
                            }
                        }
                    )
                    // rally_clips, for the Analysis section's rally windows: the
                    // rally truth for a cloud-analysed video, and the only source
                    // of rally bounds on a phone that cut no clips itself.
                    // Remembered: observeClips returns a new wrapper per call,
                    // and this destination recomposes whenever the list does,
                    // so an unremembered flow restarted its collector each time.
                    val cloudClipsFlow = remember(rally) { rally.clips.observeClips() }
                    val cloudClips by cloudClipsFlow.collectAsStateWithLifecycle(emptyList())
                    // Who stands at each end, for the Analysis toggle and a
                    // profile's hand. The roster is collected so a list still
                    // loading names the players once it lands.
                    val roster by rally.players.players.collectAsStateWithLifecycle()
                    val sideNames = remember(item, roster) { item?.let { courtSideNames(it, roster) } }
                    MatchScreen(
                        vm = clipListVm,
                        matchVm = matchVm,
                        summaryVm = summaryVm,
                        media = rally.media,
                        shares = rally.shares,
                        scoreLogId = args.scoreLogId,
                        videoId = effectiveVideoId,
                        entryId = args.entryId,
                        entryExistsLocally = args.entryId?.let { localVideos.get(it) != null } == true,
                        initialSection = MatchPageSection.entries.firstOrNull { it.name == args.section },
                        attach = args.attach,
                        onBack = { nav.popBackStack() },
                        onClipClick = { nav.navigate(Route.ClipDetail(it.id)) },
                        onScore = {
                            args.scoreLogId?.let { nav.navigate(Route.Scoring(it)) }
                        },
                        onAddVideo = { intent ->
                            val log = rally.scoreLogs.get(args.scoreLogId ?: return@MatchScreen)
                                ?: return@MatchScreen
                            // A match acquires a video only once it is closed:
                            // attaching to a log still marked live would leave a
                            // bound match advertising "Resume scoring".
                            if (log.status == ScoreLogStatus.LIVE) rally.scoreLogs.finish(log.id)
                            val target = MatchTarget(log.id, log.title)
                            when (intent) {
                                // For the match, not a player: its log says who played.
                                AttachIntent.Import -> intake.import(target, null)
                                AttachIntent.Record -> intake.record(target, null)
                            }
                        },
                        // By the row's own entry, the page's status banner being
                        // the row's: see MatchScreen's StatusBanner. Its Retry and
                        // Analyze go through analyzeEntry below.
                        onMarkCourt = { entryId -> nav.navigate(Route.CourtMarking(entryId)) },
                        onAnalyzeEntry = analyzeEntry,
                        onAddPlayer = matchPlayersVm::create,
                        matchSection = { e, fullscreen, onFullscreenChange, snackbar, analyzeLabel ->
                            val playerVm: LocalPlayerViewModel = viewModel(
                                key = "player-${e.id}",
                                factory = viewModelFactory {
                                    initializer { LocalPlayerViewModel(e.id, localAnnotations, rally.labels) }
                                },
                            )
                            MatchVideoSection(
                                vm = playerVm, entry = e, playbackPrefs = rally.playbackPrefs,
                                fullscreen = fullscreen, onFullscreenChange = onFullscreenChange,
                                snackbar = snackbar, analyzeLabel = analyzeLabel,
                                onAnalyze = { analyzeEntry(e.id) },
                            )
                        },
                        analysisSection = { id ->
                            AnalysisSection(
                                analysisId = id, localAnalysis = localAnalysis, localVideos = localVideos,
                                playbackPrefs = rally.playbackPrefs, racketArmPrefs = rally.racketArmPrefs,
                                cloudClips = cloudClips, sideNames = sideNames,
                            )
                        },
                        deviceRalliesSection = { id -> DeviceRalliesSection(entryId = id, localAnalysis = localAnalysis) },
                    )
                }
                composable<Route.ClipDetail> { entry ->
                    val args = entry.toRoute<Route.ClipDetail>()
                    val vm: ClipDetailViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                ClipDetailViewModel(
                                    args.clipId, rally.clips, rally.annotations, rally.media, rally.auth, rally.labels,
                                )
                            }
                        }
                    )
                    ClipDetailScreen(
                        vm = vm,
                        playbackPrefs = rally.playbackPrefs,
                        onBack = { nav.popBackStack() },
                    )
                }
                composable<Route.CourtMarking> { entry ->
                    val args = entry.toRoute<Route.CourtMarking>()
                    val localVideo = remember(args.entryId) { localVideos.get(args.entryId) }
                    if (localVideo == null) {
                        LaunchedEffect(args.entryId) { nav.popBackStack() }
                        return@composable
                    }
                    val appCtx = LocalContext.current.applicationContext
                    val vm: CourtMarkingViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                CourtMarkingViewModel(
                                    entryId = args.entryId,
                                    savedKeypoints = localVideo.keypoints,
                                    loadFrame = { loadFirstFrame(appCtx, Uri.parse(localVideo.uri)) },
                                    finder = DefaultCourtFinderPort,
                                )
                            }
                        }
                    )
                    val deviceThroughput by throughput.throughput.collectAsStateWithLifecycle()
                    CourtMarkingScreen(
                        vm = vm,
                        throughput = deviceThroughput,
                        onStartAnalysis = { keypoints, target, metrics ->
                            when (target) {
                                AnalysisTarget.Cloud ->
                                    coordinator.startAnalysis(args.entryId, keypoints)
                                AnalysisTarget.Device -> {
                                    // The keypoints are persisted either way, so
                                    // a device run can be followed by a cloud run
                                    // over the same markings without re-marking.
                                    localVideos.setKeypoints(args.entryId, keypoints)
                                    localVideos.get(args.entryId)?.uri?.let { uri ->
                                        localAnalysis.start(args.entryId, uri, keypoints, metrics)
                                    }
                                }
                            }
                            // A single pop, not popBackStack(Route.X, ...): typed-route
                            // popping matches on the serialized route, and the Match
                            // instance on the stack carries `attach` and `section`
                            // arguments this one would not. Naming a destination is also
                            // wrong because CourtMarking has callers besides All matches:
                            // the Match page's Analyze under the player, its "Analyze
                            // again", onMarkCourt and onRetry all push it directly too,
                            // and popping to a named Route.AllMatches would skip past
                            // whichever of those the coach actually came from.
                            // Returning to the caller is the same answer for every
                            // entry point, so there is one branch fewer.
                            //
                            // Safe against a second run being started from the screen
                            // the coach lands back on. Both pipelines refuse re-entry at
                            // their own door: LocalAnalysisRunner.start returns for an
                            // entry already in `running`, and AnalyzeCoordinator's
                            // launchPipeline returns unless `active.add(entryId)` is the
                            // first. The UPLOADING stage also hides the button, but it is
                            // written inside the coroutine, so the set is the guard that
                            // actually holds in the window right after the pop.
                            nav.popBackStack()
                        },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            // Above the NavHost: the sheet opens over whichever screen the coach
            // took the video from and comes back to.
            AutoMatchDetailsHost(
                entryId = autoDetailsEntryId,
                localVideos = localVideos,
                nav = nav,
                playersVmFactory = playersVmFactory,
                // Its sentence, if any, on the root snackbar with the intake's own.
                onSave = { id, title, description, players, nearSide ->
                    rootScope.launch {
                        saveEntryMatchDetailsOrMessage(
                            rally.scoreLogs, rally.videos, localVideos, id, title, description, players, nearSide,
                        )?.let { intakeError = it }
                    }
                },
                onClose = { autoDetailsEntryId = null },
            )
            homeEndEntryId?.let { id ->
                val entry = localVideos.get(id)
                val log = entry?.scoreLogId?.let(rally.scoreLogs::get)
                // Near is Home at the near end; Far is Home at the far end, so
                // Away is nearest the camera. Any answer, Skip included, goes
                // on to court marking.
                fun answer(nearSide: Side?) {
                    homeEndEntryId = null
                    if (nearSide != null) localVideos.setMatchPlayers(id, MatchPlayers.NONE, nearSide)
                    if (entry != null) nav.navigate(Route.CourtMarking(id))
                }
                if (log == null) {
                    // The video or its match went before the question was
                    // answered: nothing to ask about.
                    LaunchedEffect(id) { answer(null) }
                } else {
                    // Collected, so a roster still loading names Home once it lands.
                    val roster by rally.players.players.collectAsStateWithLifecycle()
                    HomeEndDialog(
                        title = homeEndTitle(log, roster),
                        onNear = { answer(Side.HOME) },
                        onFar = { answer(Side.AWAY) },
                        onSkip = { answer(null) },
                    )
                }
            }
            RootAnnouncementsHost(
                nav = nav,
                snackbar = rootSnackbar,
                clipListVmFactory = clipListVmFactory,
                localVideos = localVideos,
                intakeError = intakeError,
                onIntakeErrorShown = { intakeError = null },
                announced = announced,
                onAnnounced = { announced = emptySet() },
                onOpenMatch = openMatch,
                onAnalyze = analyzeEntry,
                onOverlayChange = { resultDialogOpen = it },
            )
            }
        }
    }
}

/**
 * What earlier runs left on disk for a set of ids, as the match list reads it.
 * `hasStoredTrack` and `hasStoredSkeleton` stat one file each and never parse
 * one; `storedClips` parses a sidecar, or lists a directory when there is none.
 */
private fun storedResultsReader(localAnalysis: LocalAnalysisRunner) = StoredResultsReader { ids ->
    StoredResults(
        trackIds = ids.filterTo(mutableSetOf(), localAnalysis::hasStoredTrack),
        skeletonIds = ids.filterTo(mutableSetOf(), localAnalysis::hasStoredSkeleton),
        clipCounts = ids.associateWith { localAnalysis.storedClips(it).size }.filterValues { it > 0 },
    )
}

/**
 * What shows while the stored session is read. The same title bar as the
 * sign-in screen and the Players screen, with the wordmark in the title's
 * slot, so the handover moves nothing: to sign-in the wordmark holds still, to
 * Players "Players" takes its place. A small indicator in the space below, drawn like
 * ShuttlButton's own (a 2dp stroke), not M3's 40dp default.
 */
@Composable
private fun Splash() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        HomeTopBar(SignInCopy.WORDMARK, titleStyle = ShuttlTypeExtras.wordmark)
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                color       = MaterialTheme.colorScheme.onSurfaceVariant,
                strokeWidth = 2.dp,
                modifier    = Modifier.size(24.dp),
            )
        }
    }
}
