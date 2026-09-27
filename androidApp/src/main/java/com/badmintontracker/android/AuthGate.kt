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
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
import com.badmintontracker.android.home.HomeScreen
import com.badmintontracker.android.settings.SettingsScreen
import com.badmintontracker.android.match.MatchScreen
import com.badmintontracker.android.match.MatchViewModel
import com.badmintontracker.shared.prefs.ThemePreferenceRepository
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
import com.badmintontracker.shared.localvideo.canResumeFailedAnalysis
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.localvideo.setKeypoints
import com.badmintontracker.shared.RallyApp
import com.badmintontracker.shared.repo.AuthState
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.android.localanalysis.LocalAnalysisState
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

            // Any sheet or dialog over the list, reported by HomeScreen. Part of
            // "nothing above": see the finished-run watcher below.
            var homeOverlayOpen by remember { mutableStateOf(false) }
            // Runs the watcher below has just announced, for HomeScreen to say
            // on its snackbar; HomeScreen clears it once it has read it.
            var announced by remember { mutableStateOf(emptySet<String>()) }

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
                    AuthRedirect.TO_HOME -> nav.navigate(Route.Home) {
                        popUpTo(Route.SignIn) { inclusive = true }
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
                        // The naming sheet is drawn by the list, which is the
                        // root, so there is nothing to select: the coach comes
                        // back from the camera or picker to the list, and the
                        // sheet opens over it.
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

            // One reader for the one ClipListViewModel below, which the list
            // and the match page both read: each shows what earlier runs left
            // on disk, and must probe it the same way.
            val storedResults = remember(localAnalysis) { storedResultsReader(localAnalysis) }

            // One factory for the one list view model. The match page reads the
            // Route.Home entry's instance rather than building its own: it finds
            // its match in that list (matchPageItem), and a second instance
            // would be a second, later copy of the same list.
            val clipListVmFactory = remember(storedResults) {
                viewModelFactory {
                    initializer {
                        ClipListViewModel(
                            rally.clips, rally.auth, rally.shares, rally.videos, rally.scoreLogs,
                            localVideos, coordinator, localAnnotations,
                            deviceStates = localAnalysis.state,
                            storedResults = storedResults,
                        )
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

            val work by backgroundWork.work.collectAsStateWithLifecycle()

            // The activity's, not a destination's: read above the NavHost, so it
            // goes below RESUMED while the camera or the photo picker (another
            // activity) is in front of ours. Read inside the watcher below
            // rather than keyed on, since restarting that effect would reset
            // its seen-set.
            val activityLifecycle = LocalLifecycleOwner.current.lifecycle

            // A run that lands while the list is in front is announced there:
            // HomeScreen says so on its snackbar, with a View that opens the
            // match. Nothing navigates on its own.
            //
            // Above the NavHost on purpose. announceFinishedRuns updates its
            // seen-set on every tick whether or not it announces, and that is
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
            // announce itself on the next visit to the list. It needs the
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
                // to find it. The two sets are unioned rather than announced
                // separately so one rule decides, and a video analysed both
                // ways announces once.
                //
                // A device run that found nothing (no rally, no track, no
                // skeleton) is left out here, from its own outcome: it is not
                // "analyzed", and its row still offers Analyze. Not decided
                // from the list later, which can lag a run and would lose one
                // for good once its id is marked seen. Cloud runs are not
                // filtered: one that finds no rallies fails instead of
                // finishing, and its results land after the finish.
                combine(
                    localAnalysis.state
                        .map { states ->
                            states.filterValues { it is LocalAnalysisState.Done && it.foundSomething }.keys
                        },
                    coordinator.finishedRuns,
                ) { device, cloud -> device + cloud }
                    .distinctUntilChanged()
                    .collect { finished ->
                        // "Nothing above" is more than the back stack and the
                        // list's own overlays. The back-stack entry is still
                        // Route.Home while the camera or picker is open, and this
                        // collector keeps running with our activity stopped, so
                        // without the RESUMED check a run landing then was
                        // announced out of sight, its snackbar timing out before
                        // the coach came back. And a video just added with no
                        // match has its naming sheet pending (autoDetailsEntryId)
                        // until the list opens it: the coach is about to type a
                        // name, and a View offered then pulls the other way.
                        //
                        // The seen-set rule does not change: a run that lands
                        // while the app is in the background is marked seen and
                        // does not announce itself later, as for any run that
                        // lands while the coach is elsewhere.
                        val nothingAbove = activityLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            autoDetailsEntryId == null &&
                            !homeOverlayOpen &&
                            nav.currentBackStackEntry?.destination?.hasRoute<Route.Home>() == true
                        val announcement = announceFinishedRuns(
                            finished = finished,
                            seen = seen,
                            onHome = nothingAbove,
                        )
                        seen = announcement.seen
                        // Merged, not replaced: two runs can land on consecutive
                        // collections before HomeScreen's effect has read and
                        // cleared the first, and a plain assignment dropped it.
                        if (announcement.announce.isNotEmpty()) announced = announced + announcement.announce
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
                        // on the list, which is Route.Home itself.
                        nav.navigate(Route.Home) {
                            popUpTo(Route.Home)
                            launchSingleTop = true
                        }
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
                            nav.navigate(Route.Home) {
                                popUpTo(Route.SignIn) { inclusive = true }
                            }
                        },
                    )
                }
                composable<Route.Home> {
                    val clipListVm: ClipListViewModel = viewModel(factory = clipListVmFactory)
                    val localVm: LocalVideoListViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { LocalVideoListViewModel(localVideos, coordinator, localAnnotations) }
                        }
                    )
                    HomeScreen(
                        onOverlayChange = { homeOverlayOpen = it },
                        vm = clipListVm,
                        shares = rally.shares,
                        media = rally.media,
                        onOpenMatch = openMatch,
                        onSettings = { nav.navigate(Route.Settings) },
                        onNewMatch = { nav.navigate(Route.NewMatch) },
                        onRecord = { intake.record(null) },
                        onImport = { intake.import(null) },
                        onAnalyze = analyzeEntry,
                        // By the row's own entry id: see ClipListScreen.act.
                        onMarkCourt = { entryId -> nav.navigate(Route.CourtMarking(entryId)) },
                        onRemoveFromPhone = localVm::remove,
                        onLocalResultSeen = localVm::acknowledgeResult,
                        onLocalDetailsSaved = localVm::setDetails,
                        autoDetailsEntryId = autoDetailsEntryId,
                        onAutoDetailsShown = { autoDetailsEntryId = null },
                        intakeError = intakeError,
                        onIntakeErrorShown = { intakeError = null },
                        announced = announced,
                        onAnnounced = { announced = emptySet() },
                    )
                }
                composable<Route.Settings> { entry ->
                    // Sign out goes through the list's view model (vm::signOut),
                    // as it always has, rather than a view model of this page's
                    // own for one call: the Home entry's instance, fetched the
                    // way the match page fetches it.
                    val homeEntry = remember(entry) { nav.getBackStackEntry<Route.Home>() }
                    val homeVm: ClipListViewModel = viewModel(homeEntry, factory = clipListVmFactory)
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
                    // Route.Home is always under a match page (the start
                    // destination, and every push is over it), so its entry is
                    // there to scope the shared list view model to.
                    val homeEntry = remember(entry) { nav.getBackStackEntry<Route.Home>() }
                    val clipListVm: ClipListViewModel = viewModel(homeEntry, factory = clipListVmFactory)
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
                                    rally.videos, localAnnotations, args.scoreLogId,
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
                    MatchScreen(
                        vm = clipListVm,
                        matchVm = matchVm,
                        summaryVm = summaryVm,
                        media = rally.media,
                        shares = rally.shares,
                        scoreLogId = args.scoreLogId,
                        videoId = effectiveVideoId,
                        entryId = args.entryId,
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
                                AttachIntent.Import -> intake.import(target)
                                AttachIntent.Record -> intake.record(target)
                            }
                        },
                        // By the row's own entry, the page's status banner being
                        // the row's: see MatchScreen's StatusBanner. Its Retry and
                        // Analyze go through analyzeEntry below.
                        onMarkCourt = { entryId -> nav.navigate(Route.CourtMarking(entryId)) },
                        onAnalyzeEntry = analyzeEntry,
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
                                cloudClips = cloudClips,
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
                            // wrong because CourtMarking has callers besides Route.Home:
                            // the Match page's Analyze under the player, its "Analyze
                            // again", onMarkCourt and onRetry all push it directly too,
                            // and popping to a named Route.Home would skip past
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
