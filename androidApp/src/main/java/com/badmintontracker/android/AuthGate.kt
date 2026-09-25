package com.badmintontracker.android

import com.badmintontracker.android.localanalysis.BackgroundWorkMonitor
import com.badmintontracker.android.localanalysis.LocalBackgroundWork
import com.badmintontracker.android.localanalysis.LocalBackgroundWorkClick
import com.badmintontracker.shared.local.DeviceThroughputRepository
import com.badmintontracker.android.localanalysis.LocalAnalysisRunner
import com.badmintontracker.android.localanalysis.AnalysisTarget
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.badmintontracker.android.analytics.AnalyticsDetailScreen
import com.badmintontracker.android.analytics.VideoTitle
import com.badmintontracker.android.analytics.AnalyticsTab
import com.badmintontracker.android.analytics.buildAnalyticsRows
import com.badmintontracker.android.cliplist.ClipListViewModel
import com.badmintontracker.android.cliplist.MatchSummaryViewModel
import com.badmintontracker.android.cliplist.StoredResults
import com.badmintontracker.android.cliplist.StoredResultsReader
import com.badmintontracker.android.home.HomeScreen
import com.badmintontracker.shared.home.HomeTab
import com.badmintontracker.shared.home.isHomeFront
import com.badmintontracker.android.match.MatchScreen
import com.badmintontracker.android.match.MatchViewModel
import com.badmintontracker.shared.prefs.ThemePreferenceRepository
import com.badmintontracker.android.localvideo.LocalPlayerScreen
import com.badmintontracker.android.localvideo.LocalPlayerViewModel
import com.badmintontracker.android.localvideo.LocalVideoListViewModel
import com.badmintontracker.android.localvideo.MatchTarget
import com.badmintontracker.android.localvideo.court.CourtMarkingScreen
import com.badmintontracker.android.localvideo.court.CourtMarkingViewModel
import com.badmintontracker.android.localvideo.court.loadFirstFrame
import com.badmintontracker.android.localvideo.rememberVideoIntake
import com.badmintontracker.android.labels.LabelsScreen
import com.badmintontracker.android.labels.LabelsViewModel
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
import com.badmintontracker.shared.localvideo.announceFinishedRuns
import com.badmintontracker.shared.localvideo.AnalyzeCoordinator
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.canResumeFailedAnalysis
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.RallyApp
import com.badmintontracker.shared.repo.AuthState
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.android.localanalysis.LocalAnalysisState
import com.badmintontracker.android.localanalysis.isDeviceRunInFlight
import com.badmintontracker.android.localanalysis.HeatmapPanel
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import com.badmintontracker.android.localanalysis.LocalClipPlayerDialog
import com.badmintontracker.android.localanalysis.ClipCutter

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

            // The selected tab, owned here rather than by HomeScreen: the
            // finished-run watcher below reads it, and the background-work
            // indicator sets it from any screen. Saveable, so rotation and process
            // death keep it; reset on arrival below (Review focus 5), so a fresh
            // sign-in lands on Matches rather than wherever a previous coach left
            // it.
            var homeTab by rememberSaveable { mutableStateOf(HomeTab.DEFAULT) }
            // Any sheet or dialog over the tabs, reported by HomeScreen. Part of
            // "nothing above": see isHomeFront.
            var homeOverlayOpen by remember { mutableStateOf(false) }

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
                    // The reset lives here, on arrival, rather than on the way out
                    // through TO_SIGN_IN above: resetting there would recompose the
                    // fading Home screen onto Matches mid crossfade, and it also
                    // misses process death while on Settings followed by an invalid
                    // session - the start destination is SignIn in that case, so
                    // TO_SIGN_IN never fires at all.
                    AuthRedirect.TO_HOME -> {
                        homeTab = HomeTab.DEFAULT
                        nav.navigate(Route.Home) {
                            popUpTo(Route.SignIn) { inclusive = true }
                        }
                    }
                    null -> Unit
                }
            }

            var intakeError by remember { mutableStateOf<String?>(null) }
            // Persist first, then hand the id on so the details sheet can open over
            // an entry that already exists.
            var autoDetailsEntryId by remember { mutableStateOf<String?>(null) }
            // Hoisted above the NavHost: both the list destination (record/import
            // with no match) and the match destination (record/import for this
            // match) drive the same picker, so there can only be one implementation
            // of "getting a video into the app".
            val intake = rememberVideoIntake(
                onAdded = { entry ->
                    localVideos.add(entry)
                    // A video picked for a match already carries that match's name,
                    // and videos.title is insert-only, so there is nothing to ask
                    // and nowhere to change it later.
                    if (entry.scoreLogId == null) {
                        autoDetailsEntryId = entry.id
                    } else {
                        // The coach already said he wants this video analysed;
                        // making him find an Analyze button afterwards is a second
                        // decision for a question he answered.
                        nav.navigate(Route.CourtMarking(entry.id))
                    }
                },
                onError = { intakeError = it },
            )

            // One reader for every screen that builds a ClipListViewModel: the
            // Matches tab, the Analytics tab and the match page all show what
            // earlier runs left on disk, and must probe it the same way.
            val storedResults = remember(localAnalysis) { storedResultsReader(localAnalysis) }

            val work by backgroundWork.work.collectAsStateWithLifecycle()

            // A device run that lands switches the coach to the Analytics tab.
            //
            // Above the NavHost on purpose. announceFinishedRuns updates its
            // seen-set on every tick whether or not it switches, and that is
            // what stops a run which finished while the coach was three screens
            // away from ambushing them the next time they land back on Route.Home.
            // Observed from inside Route.Home it could not tell those apart:
            // Route.Home leaves composition on every trip out and comes back
            // looking like a fresh arrival.
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
            // announce itself on the next visit to Home. It needs the system
            // theme to change inside that window - the app's own toggle does
            // not go through uiMode - and the cost of being wrong is one
            // screen the coach was heading for anyway.
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
                // Both pipelines, not just the device one. A cloud run ends by
                // retiring its entry, so the coach watching Home saw the row
                // vanish and nothing else - no result, and nothing saying where
                // it had gone. The two sets are unioned rather than announced
                // separately so one rule decides, and a video analysed both
                // ways announces once.
                combine(
                    localAnalysis.state
                        .map { states -> states.filterValues { it is LocalAnalysisState.Done }.keys },
                    coordinator.finishedRuns,
                ) { device, cloud -> device + cloud }
                    .distinctUntilChanged()
                    .collect { finished ->
                        val announcement = announceFinishedRuns(
                            finished = finished,
                            seen = seen,
                            onHome = isHomeFront(
                                tab = homeTab,
                                nothingAbove = !homeOverlayOpen && nav.currentBackStackEntry
                                    ?.destination?.hasRoute<Route.Home>() == true,
                            ),
                        )
                        seen = announcement.seen
                        if (announcement.openAnalytics) homeTab = HomeTab.ANALYTICS
                    }
            }
            // Provided around the NavHost so every destination's bar can show it
            // without the state appearing in any screen's signature.
            CompositionLocalProvider(
                LocalBackgroundWork provides work,
                // remembered: this local is static, so a new lambda identity
                // would invalidate the whole NavHost subtree, and AuthGate
                // recomposes on every progress tick because it reads `work`.
                LocalBackgroundWorkClick provides remember(nav) {
                    {
                        // popUpTo plus singleTop: the indicator is reachable from
                        // any screen, and without both, tapping it repeatedly
                        // would stack Home screens. The run it points at is a row
                        // on the Matches tab, so arriving selects that tab too.
                        nav.navigate(Route.Home) {
                            popUpTo(Route.Home)
                            launchSingleTop = true
                        }
                        homeTab = HomeTab.MATCHES
                    }
                },
            ) {
            NavHost(navController = nav, startDestination = start) {
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
                            // Reset here too (see the TO_HOME branch above for why
                            // arrival, not TO_SIGN_IN's way-out, is where this
                            // belongs): whichever of the two fires first for this
                            // sign-in, the tab is already Matches by the time
                            // either lands on Route.Home.
                            homeTab = HomeTab.DEFAULT
                            nav.navigate(Route.Home) {
                                popUpTo(Route.SignIn) { inclusive = true }
                            }
                        },
                    )
                }
                composable<Route.Home> {
                    val clipListVm: ClipListViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                ClipListViewModel(
                                    rally.clips, rally.auth, rally.shares, rally.videos, rally.scoreLogs,
                                    localVideos, coordinator, localAnnotations,
                                    deviceStates = localAnalysis.state,
                                    storedResults = storedResults,
                                )
                            }
                        }
                    )
                    val localVm: LocalVideoListViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { LocalVideoListViewModel(localVideos, coordinator, localAnnotations) }
                        }
                    )
                    // Both the list's Analyze and the Analytics tab's Analyze go
                    // through this one decision: resume a failed cloud run whose
                    // court points are saved, otherwise mark the court. The same
                    // guard MatchScreen's onRetry keeps its own copy of, keyed by
                    // score log rather than by entry: a cloud run that failed at
                    // TRIGGER already has this video's four court corners saved,
                    // and sending the coach back to mark them again both wastes
                    // the marking and means the coordinator's
                    // resume-from-the-failed-step is never reached.
                    //
                    // It reads correctly for a device failure too, and not by
                    // accident: a device run never moves entry.stage, so a row
                    // showing "Retry" because LocalAnalysisState is Failed falls
                    // through to court marking, which is where a device re-run
                    // has to start (LocalAnalysisRunner.start takes keypoints
                    // from the screen, not from the entry).
                    fun analyze(entryId: String) {
                        localVideos.get(entryId)?.let { entry ->
                            if (canResumeFailedAnalysis(entry)) localVm.retry(entry.id)
                            else nav.navigate(Route.CourtMarking(entry.id))
                        }
                    }
                    HomeScreen(
                        selectedTab = homeTab,
                        onSelectTab = { homeTab = it },
                        onOverlayChange = { homeOverlayOpen = it },
                        vm = clipListVm,
                        shares = rally.shares,
                        media = rally.media,
                        themePrefs = themePrefs,
                        email = rally.auth.currentUserEmail(),
                        analyticsContent = { bottom ->
                            // Collected inside the slot, so only while the tab is
                            // on screen: see HomeScreen's analyticsContent.
                            val clipListState by clipListVm.state.collectAsStateWithLifecycle()
                            val liveAnalysisStates by localAnalysis.state.collectAsStateWithLifecycle()
                            val cloudProgress by coordinator.progress.collectAsStateWithLifecycle()
                            AnalyticsTab(
                                rows = buildAnalyticsRows(
                                    matchList = clipListState.matchList,
                                    localEntries = clipListState.localEntries,
                                    liveAnalysisStates = liveAnalysisStates,
                                    progressByEntryId = cloudProgress,
                                ),
                                onOpenDetail = { row -> row.entryId?.let { nav.navigate(Route.AnalyticsDetail(it, row.title)) } },
                                onAnalyse = { row -> row.entryId?.let(::analyze) },
                                contentPadding = bottom,
                            )
                        },
                        onMatchClick = { nav.navigate(Route.Match(videoId = it)) },
                        onScoreMatchClick = { nav.navigate(Route.Match(scoreLogId = it)) },
                        onNewMatch = { nav.navigate(Route.NewMatch) },
                        onRecord = { intake.record(null) },
                        onImport = { intake.import(null) },
                        onLabels = { nav.navigate(Route.Labels) },
                        onOpenHeatmap = { nav.navigate(Route.Heatmap(it)) },
                        onOpenLocalClips = { nav.navigate(Route.LocalClips(it)) },
                        onLocalClick = { nav.navigate(Route.LocalPlayer(it)) },
                        onAnalyze = ::analyze,
                        // By the row's own entry id: see ClipListScreen.act.
                        onMarkCourt = { entryId -> nav.navigate(Route.CourtMarking(entryId)) },
                        onRemoveFromPhone = localVm::remove,
                        onLocalResultSeen = localVm::acknowledgeResult,
                        onLocalDetailsSaved = localVm::setDetails,
                        autoDetailsEntryId = autoDetailsEntryId,
                        onAutoDetailsShown = { autoDetailsEntryId = null },
                        intakeError = intakeError,
                        onIntakeErrorShown = { intakeError = null },
                    )
                }
                composable<Route.NewMatch> {
                    val appContext = LocalContext.current.applicationContext
                    val vm: NewMatchViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { NewMatchViewModel(rally.scoreLogs, AndroidPlaceNames(appContext)) }
                        }
                    )
                    NewMatchScreen(
                        vm = vm,
                        // Straight to the board, not back to the list and not to
                        // the record: creating a match courtside means being about
                        // to score it.
                        onCreated = { id ->
                            nav.navigate(Route.Scoring(id)) {
                                popUpTo(Route.NewMatch) { inclusive = true }
                            }
                        },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable<Route.Scoring> { entry ->
                    val args = entry.toRoute<Route.Scoring>()
                    val vm: ScoringViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                ScoringViewModel(rally.scoreLogs, rally.labels, args.scoreLogId)
                            }
                        }
                    )
                    ScoringScreen(
                        vm = vm,
                        onBack = { nav.popBackStack() },
                        // Lands on the match page rather than popping back, because
                        // a match just created and scored in one sitting (NewMatch
                        // -> Scoring, no Match page underneath yet) has nothing to
                        // pop back to. Collapsing to Home first keeps a single
                        // Match entry on the stack either way. The chosen intent (if
                        // any) rides along so the match page can act on it once.
                        onFinished = { intent ->
                            nav.navigate(
                                Route.Match(scoreLogId = args.scoreLogId, attach = intent?.name)
                            ) {
                                popUpTo(Route.Home) { inclusive = false }
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
                    val clipListVm: ClipListViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                ClipListViewModel(
                                    rally.clips, rally.auth, rally.shares, rally.videos, rally.scoreLogs,
                                    localVideos, coordinator, localAnnotations,
                                    deviceStates = localAnalysis.state,
                                    storedResults = storedResults,
                                )
                            }
                        }
                    )
                    val logs by rally.scoreLogs.logs.collectAsStateWithLifecycle()
                    // The score log is authoritative: a match bound after this page
                    // was opened must start showing its rallies without a re-entry.
                    val effectiveVideoId = args.videoId
                        ?: logs.firstOrNull { it.id == args.scoreLogId }?.videoId
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
                                    rally.scoreLogs, localVideos, coordinator, rally.clips,
                                    rally.videos, localAnnotations, args.scoreLogId,
                                )
                            }
                        }
                    )
                    MatchScreen(
                        vm = clipListVm,
                        matchVm = matchVm,
                        summaryVm = summaryVm,
                        media = rally.media,
                        shares = rally.shares,
                        themePrefs = themePrefs,
                        scoreLogId = args.scoreLogId,
                        videoId = effectiveVideoId,
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
                                AttachIntent.Import -> intake.import(target)
                                AttachIntent.Record -> intake.record(target)
                            }
                        },
                        // The match page's own lookups, by score log id: it has no
                        // row entry to go by. It shows the same status as the
                        // Matches tab's row and must offer the same two actions on it.
                        onMarkCourt = {
                            localVideos.entries.value
                                .firstOrNull { it.scoreLogId == args.scoreLogId }
                                ?.let { nav.navigate(Route.CourtMarking(it.id)) }
                        },
                        onRetry = {
                            localVideos.entries.value
                                .firstOrNull { it.scoreLogId == args.scoreLogId }
                                ?.let { entry ->
                                    if (canResumeFailedAnalysis(entry)) {
                                        coordinator.retry(entry.id)
                                    } else {
                                        nav.navigate(Route.CourtMarking(entry.id))
                                    }
                                }
                        },
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
                composable<Route.LocalPlayer> { entry ->
                    val args = entry.toRoute<Route.LocalPlayer>()
                    val entries by localVideos.entries.collectAsStateWithLifecycle()
                    val e = entries.firstOrNull { it.id == args.entryId }
                    if (e == null) {
                        LaunchedEffect(Unit) { nav.popBackStack() }
                    } else {
                        val playerVm: LocalPlayerViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer { LocalPlayerViewModel(args.entryId, localAnnotations, rally.labels) }
                            }
                        )
                        // The stage rules out a cloud run in flight, but a device run
                        // never moves the stage, so it has to be asked separately or
                        // this button sits there live over the run it already started.
                        val deviceState by localAnalysis.state.collectAsStateWithLifecycle()
                        LocalPlayerScreen(
                            vm = playerVm,
                            entry = e,
                            canAnalyze = (e.stage == AnalyzeStage.LOCAL || e.stage == AnalyzeStage.FAILED) &&
                                !isDeviceRunInFlight(deviceState[e.id] ?: LocalAnalysisState.Idle),
                            playbackPrefs = rally.playbackPrefs,
                            // The same rule as every other Analyze affordance. Without it
                            // the "Re-analyze" label here lies: it promises the resume that
                            // analyzeButtonLabel documents, and delivers court marking.
                            onAnalyze = {
                                if (canResumeFailedAnalysis(e)) {
                                    coordinator.retry(e.id)
                                } else {
                                    nav.navigate(Route.CourtMarking(e.id))
                                }
                            },
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
                @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                composable<Route.LocalClips> { entry ->
                    val args = entry.toRoute<Route.LocalClips>()
                    val clips = remember(args.entryId) { localAnalysis.storedClips(args.entryId) }
                    var playing by remember { mutableStateOf<ClipCutter.Clip?>(null) }
                    playing?.let { LocalClipPlayerDialog(clip = it, onDismiss = { playing = null }) }
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text("Clips on this phone") },
                                navigationIcon = {
                                    IconButton(onClick = { nav.popBackStack() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                },
                                actions = { BackgroundWorkAction() },
                            )
                        },
                    ) { padding ->
                        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                            if (clips.isEmpty()) {
                                Text(
                                    "No clips are stored for this video.",
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                            clips.forEach { clip ->
                                TextButton(onClick = { playing = clip }) {
                                    // Bounds are unknown for clips recovered by
                                    // filename, and a fabricated "0.0s - 0.0s"
                                    // would read as a broken clip rather than
                                    // as missing bookkeeping.
                                    Text(
                                        if (clip.endSeconds > clip.startSeconds) {
                                            "Rally ${clip.index}  " +
                                                "${"%.1f".format(clip.startSeconds)}s - " +
                                                "${"%.1f".format(clip.endSeconds)}s  " +
                                                "(${"%.1f".format(clip.endSeconds - clip.startSeconds)}s)"
                                        } else {
                                            "Rally ${clip.index}"
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // The heatmap on its own, which is what a row's "Player heatmap"
                // menu item opens. Kept alongside Route.AnalyticsDetail rather
                // than folded into it: the two are reached from two different
                // taps on the same row (this one from the menu item,
                // AnalyticsDetail from a READY row's own tap), and folding one
                // into the other to tidy up is exactly how that path breaks
                // unnoticed. Both render HeatmapPanel, so there is one
                // resolution to keep right rather than two.
                @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                composable<Route.Heatmap> { entry ->
                    val args = entry.toRoute<Route.Heatmap>()
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = {
                                    // The same two lines the Analytics detail heads
                                    // itself with, so the two routes to one heatmap
                                    // do not read as two screens.
                                    VideoTitle(entry = localVideos.get(args.entryId), fallback = "Player heatmap")
                                },
                                navigationIcon = {
                                    IconButton(onClick = { nav.popBackStack() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                },
                                actions = { BackgroundWorkAction() },
                            )
                        },
                    ) { padding ->
                        HeatmapPanel(
                            entryId = args.entryId,
                            runner = localAnalysis,
                            modifier = Modifier.padding(padding),
                        )
                    }
                }

                composable<Route.AnalyticsDetail> { entry ->
                    val args = entry.toRoute<Route.AnalyticsDetail>()
                    // rally_clips, which is the rally truth for a cloud-analysed
                    // video and the only source of rally bounds on a phone that
                    // cut no clips itself.
                    val cloudClips by rally.clips.observeClips().collectAsStateWithLifecycle(emptyList())
                    AnalyticsDetailScreen(
                        entryId = args.entryId,
                        localAnalysis = localAnalysis,
                        localVideos = localVideos,
                        playbackPrefs = rally.playbackPrefs,
                        racketArmPrefs = rally.racketArmPrefs,
                        cloudClips = cloudClips,
                        fallbackTitle = args.title,
                        onBack = { nav.popBackStack() },
                    )
                }

                composable<Route.CourtMarking> { entry ->
                    val args = entry.toRoute<Route.CourtMarking>()
                    val appCtx = LocalContext.current.applicationContext
                    val vm: CourtMarkingViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                val e = localVideos.get(args.entryId) ?: error("Local video not found")
                                CourtMarkingViewModel(args.entryId, e.keypoints) {
                                    loadFirstFrame(appCtx, Uri.parse(e.uri))
                                }
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
                                    localVideos.update(args.entryId) { it.copy(keypoints = keypoints) }
                                    localVideos.get(args.entryId)?.uri?.let { uri ->
                                        localAnalysis.start(args.entryId, uri, keypoints, metrics)
                                    }
                                }
                            }
                            // A single pop, not popBackStack(Route.X, ...): typed-route
                            // popping matches on the serialized route, and the Match
                            // instance on the stack carries an `attach` argument this
                            // one would not. Naming a destination is also wrong because
                            // CourtMarking has callers besides Route.Home: LocalPlayer's
                            // own Analyze/Re-analyze and the Match page's onMarkCourt and
                            // onRetry all push it directly too, and popping to a named
                            // Route.Home would skip past whichever of those the coach
                            // actually came from. Returning to the caller is the same
                            // answer for every entry point, so there is one branch fewer.
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

@Composable
private fun Splash() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Shuttl")
        CircularProgressIndicator()
    }
}
