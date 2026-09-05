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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.badmintontracker.android.clipdetail.ClipDetailScreen
import com.badmintontracker.android.clipdetail.ClipDetailViewModel
import com.badmintontracker.android.analytics.AnalyticsScreen
import com.badmintontracker.android.analytics.buildAnalyticsRows
import com.badmintontracker.android.cliplist.ClipListViewModel
import com.badmintontracker.android.cliplist.MatchSummaryViewModel
import com.badmintontracker.android.home.HomeScreen
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
import com.badmintontracker.android.nav.Route
import com.badmintontracker.android.scoring.AttachIntent
import com.badmintontracker.android.scoring.NewMatchScreen
import com.badmintontracker.android.scoring.NewMatchViewModel
import com.badmintontracker.android.scoring.ScoringScreen
import com.badmintontracker.android.scoring.ScoringViewModel
import com.badmintontracker.android.signin.SignInScreen
import com.badmintontracker.android.signin.SignInViewModel
import com.badmintontracker.shared.localvideo.AnalyzeCoordinator
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.RallyApp
import com.badmintontracker.shared.scoring.ScoreLogStatus
import io.github.jan.supabase.auth.status.SessionStatus
import com.badmintontracker.android.localanalysis.LocalAnalysisState
import com.badmintontracker.android.localanalysis.CourtHeatmapView
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
    val session by rally.auth.sessionFlow.collectAsStateWithLifecycle(initialValue = null)

    when (val s = session) {
        null -> Splash()
        else -> {
            val nav = rememberNavController()
            val start: Route = if (s is SessionStatus.Authenticated) Route.Home else Route.SignIn

            LaunchedEffect(s) {
                val onSignIn = nav.currentDestination?.route?.contains("SignIn") == true
                when {
                    // Session ended anywhere in the app (sign-out, token revocation):
                    // clear the whole back stack and land on SignIn.
                    s is SessionStatus.NotAuthenticated && !onSignIn -> {
                        nav.navigate(Route.SignIn) {
                            popUpTo(nav.graph.id) { inclusive = true }
                        }
                    }
                    // Session arrived while on SignIn (e.g. a session restored via
                    // deep link, so SignInViewModel never sees the completed sign-in).
                    s is SessionStatus.Authenticated && onSignIn -> {
                        nav.navigate(Route.Home) {
                            popUpTo(Route.SignIn) { inclusive = true }
                        }
                    }
                }
            }

            var intakeError by remember { mutableStateOf<String?>(null) }
            // Persist first, then hand the id on so the details sheet can open over
            // an entry that already exists.
            var autoDetailsEntryId by remember { mutableStateOf<String?>(null) }
            // Set by the background-work indicator (below) and consumed by
            // HomeScreen once it has opened the drawer. Hoisted here, above the
            // NavHost, because Home is the start destination and is never
            // recreated for an authenticated session - the indicator's click
            // handler needs a channel into an instance that already exists.
            var pendingDrawerOpen by remember { mutableStateOf(false) }
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

            val work by backgroundWork.work.collectAsStateWithLifecycle()
            // Provided around the NavHost so every destination's bar can show it
            // without the state appearing in any screen's signature.
            CompositionLocalProvider(
                LocalBackgroundWork provides work,
                // remembered: this local is static, so a new lambda identity
                // would invalidate the whole NavHost subtree, and AuthGate
                // recomposes on every progress tick because it reads `work`.
                LocalBackgroundWorkClick provides remember(nav) {
                    {
                        // singleTop plus popUpTo: the indicator is reachable
                        // from anywhere, so without both, tapping it repeatedly
                        // would stack Home screens. The run it points at now
                        // lives in the drawer's list, not on Home itself, so
                        // arriving is not enough - the drawer must open too.
                        nav.navigate(Route.Home) {
                            popUpTo(Route.Home)
                            launchSingleTop = true
                        }
                        pendingDrawerOpen = true
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
                    val clipListVm: ClipListViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { ClipListViewModel(rally.clips, rally.auth, rally.shares, rally.videos, rally.scoreLogs, localVideos, coordinator, localAnnotations) }
                        }
                    )
                    val localVm: LocalVideoListViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { LocalVideoListViewModel(localVideos, coordinator, localAnnotations) }
                        }
                    )
                    val localRows by localVm.rows.collectAsStateWithLifecycle()
                    HomeScreen(
                        vm = clipListVm,
                        media = rally.media,
                        shares = rally.shares,
                        themePrefs = themePrefs,
                        localAnalysis = localAnalysis,
                        onMatchClick = { nav.navigate(Route.Match(videoId = it.videoId)) },
                        onScoreMatchClick = { nav.navigate(Route.Match(scoreLogId = it.scoreLogId)) },
                        onNewMatch = { nav.navigate(Route.NewMatch) },
                        onOpenHeatmap = { nav.navigate(Route.Heatmap(it.id)) },
                        hasHeatmap = { localAnalysis.storedTrack(it.id) != null },
                        onOpenLocalClips = { nav.navigate(Route.LocalClips(it.id)) },
                        localClipCount = { localAnalysis.storedClips(it.id).size },
                        localRows = localRows,
                        intakeError = intakeError,
                        onIntakeErrorShown = { intakeError = null },
                        onLocalClick = { nav.navigate(Route.LocalPlayer(it.id)) },
                        onLocalAnalyze = { row ->
                            if (row.entry.stage == AnalyzeStage.FAILED && row.entry.keypoints != null) {
                                localVm.retry(row.entry.id)   // resume; court points already saved
                            } else {
                                nav.navigate(Route.CourtMarking(row.entry.id))
                            }
                        },
                        onLocalRemove = { localVm.remove(it.id) },
                        onLocalResultSeen = { localVm.acknowledgeResult(it.id) },
                        onLocalDetailsSaved = localVm::setDetails,
                        autoDetailsEntryId = autoDetailsEntryId,
                        onAutoDetailsShown = { autoDetailsEntryId = null },
                        onRecord = { intake.record(null) },
                        onImport = { intake.import(null) },
                        onLabels = { nav.navigate(Route.Labels) },
                        onOpenAnalytics = { nav.navigate(Route.Analytics) },
                        onAttachedMarkCourt = { scoreLogId ->
                            localVideos.entries.value
                                .firstOrNull { it.scoreLogId == scoreLogId }
                                ?.let { nav.navigate(Route.CourtMarking(it.id)) }
                        },
                        onAttachedRetry = { scoreLogId ->
                            localVideos.entries.value
                                .firstOrNull { it.scoreLogId == scoreLogId }
                                ?.let { entry ->
                                    // Same guard as onLocalAnalyze: a FAILED entry with no
                                    // saved court points has nothing to resume - retrying it
                                    // directly just re-fails instantly with "No court points
                                    // saved" (AnalyzeCoordinator.runPipeline). Send it back to
                                    // court marking instead.
                                    //
                                    // The `else` below is defensive, not reachable today: keypoints
                                    // are written by startAnalysis before the entry's first
                                    // launchPipeline call, and fail() only ever runs from inside
                                    // runPipeline after that, so a FAILED entry always already has
                                    // keypoints. Do not simplify this guard away on that basis - it
                                    // is what stops the ScoreMatchRow "Retry" button from lying if
                                    // that invariant ever stops holding.
                                    if (entry.stage == AnalyzeStage.FAILED && entry.keypoints != null) {
                                        localVm.retry(entry.id)
                                    } else {
                                        nav.navigate(Route.CourtMarking(entry.id))
                                    }
                                }
                        },
                        onOpenHeatmapFromBanner = { nav.navigate(Route.Heatmap(it)) },
                        openDrawerRequested = pendingDrawerOpen,
                        onDrawerOpenConsumed = { pendingDrawerOpen = false },
                    )
                }
                composable<Route.Analytics> {
                    // Built from the same view models Route.Home uses, not the
                    // same instances - Navigation Compose scopes a viewModel() to
                    // its own back stack entry, and every other route in this
                    // graph that also wants ClipListViewModel's state (there are
                    // none today, but the pattern is Route.Home's own) creates
                    // its own instance from the same factory rather than reaching
                    // across routes for one.
                    val clipListVm: ClipListViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { ClipListViewModel(rally.clips, rally.auth, rally.shares, rally.videos, rally.scoreLogs, localVideos, coordinator, localAnnotations) }
                        }
                    )
                    val localVm: LocalVideoListViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { LocalVideoListViewModel(localVideos, coordinator, localAnnotations) }
                        }
                    )
                    val clipListState by clipListVm.state.collectAsStateWithLifecycle()
                    val localRows by localVm.rows.collectAsStateWithLifecycle()
                    val localEntries by localVideos.entries.collectAsStateWithLifecycle()
                    val liveAnalysisStates by localAnalysis.state.collectAsStateWithLifecycle()

                    val standaloneLocalRows = localRows.filter { it.entry.scoreLogId == null }
                    // Which videos have a track, asked the cheap way: hasStoredTrack
                    // stats one file per entry and never parses one, so opening
                    // Analytics with fifteen analysed videos on the phone costs
                    // fifteen stats rather than fifteen full track reads.
                    //
                    // Still keyed on which runs have *finished* rather than on the
                    // live states themselves: a run in progress ticks
                    // `liveAnalysisStates` several times a second (start reports
                    // fractional progress), and only a run settling into Done can
                    // change the answer.
                    val doneEntryIds = liveAnalysisStates.filterValues { it is LocalAnalysisState.Done }.keys
                    val storedTrackIds = remember(localEntries, doneEntryIds) {
                        localEntries.mapNotNull { it.id.takeIf { id -> localAnalysis.hasStoredTrack(id) } }.toSet()
                    }
                    val rows = buildAnalyticsRows(
                        standaloneLocalRows = standaloneLocalRows,
                        ownedRows = clipListState.ownedRows,
                        sharedMatches = clipListState.sharedMatches,
                        localEntries = localEntries,
                        liveAnalysisStates = liveAnalysisStates,
                        storedTrackIds = storedTrackIds,
                    )

                    AnalyticsScreen(
                        rows = rows,
                        onOpenDetail = { row -> row.entryId?.let { nav.navigate(Route.Heatmap(it)) } },
                        onAnalyse = { row ->
                            row.entryId
                                ?.let { id -> localEntries.firstOrNull { it.id == id } }
                                ?.let { entry ->
                                    // The same guard as onLocalAnalyze, onAttachedRetry and
                                    // MatchScreen's onRetry: a cloud run that failed at
                                    // TRIGGER already has this video's four court corners
                                    // saved, and sending the coach back to mark them again
                                    // both wastes the marking and means the coordinator's
                                    // resume-from-the-failed-step is never reached.
                                    //
                                    // It reads correctly for a device failure too, and not by
                                    // accident: a device run never moves entry.stage, so a row
                                    // showing "Retry" because LocalAnalysisState is Failed falls
                                    // through to court marking, which is where a device re-run
                                    // has to start (LocalAnalysisRunner.start takes keypoints
                                    // from the screen, not from the entry).
                                    if (entry.stage == AnalyzeStage.FAILED && entry.keypoints != null) {
                                        localVm.retry(entry.id)
                                    } else {
                                        nav.navigate(Route.CourtMarking(entry.id))
                                    }
                                }
                        },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable<Route.NewMatch> {
                    val vm: NewMatchViewModel = viewModel(
                        factory = viewModelFactory { initializer { NewMatchViewModel(rally.scoreLogs) } }
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
                            initializer { ClipListViewModel(rally.clips, rally.auth, rally.shares, rally.videos, rally.scoreLogs, localVideos, coordinator, localAnnotations) }
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
                        // Same lookups as ClipListScreen's onAttachedMarkCourt/
                        // onAttachedRetry: the match page shows the same status as
                        // the list row and must offer the same two actions on it.
                        onMarkCourt = {
                            localVideos.entries.value
                                .firstOrNull { it.scoreLogId == args.scoreLogId }
                                ?.let { nav.navigate(Route.CourtMarking(it.id)) }
                        },
                        onRetry = {
                            localVideos.entries.value
                                .firstOrNull { it.scoreLogId == args.scoreLogId }
                                ?.let { entry ->
                                    if (entry.stage == AnalyzeStage.FAILED && entry.keypoints != null) {
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
                        LocalPlayerScreen(
                            vm = playerVm,
                            entry = e,
                            canAnalyze = e.stage == AnalyzeStage.LOCAL || e.stage == AnalyzeStage.FAILED,
                            playbackPrefs = rally.playbackPrefs,
                            onAnalyze = { nav.navigate(Route.CourtMarking(e.id)) },
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
                                title = { Text("CLIPS ON THIS PHONE") },
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

                @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                composable<Route.Heatmap> { entry ->
                    val args = entry.toRoute<Route.Heatmap>()
                    // In memory if the run is still loaded, from disk otherwise.
                    // A pose run costs half an hour, so losing its result to a
                    // process death and asking for another one is not an option.
                    val done = localAnalysis.stateFor(args.entryId) as? LocalAnalysisState.Done
                    val stored = remember(args.entryId) {
                        if (done != null) null else localAnalysis.storedTrack(args.entryId)
                    }
                    val track = done?.playerTrack ?: stored?.track
                    val trackFps = done?.fps ?: stored?.fps ?: 0.0
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text("PLAYER HEATMAP") },
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
                            if (track == null) {
                                // A run's state lives in memory, so it is gone
                                // after a process death. Said plainly rather
                                // than drawing an empty court, which would read
                                // as a player who never moved.
                                Text(
                                    "This analysis is no longer loaded. Run it again to see the heatmap.",
                                    modifier = Modifier.padding(16.dp),
                                )
                            } else {
                                CourtHeatmapView(track = track, fps = trackFps)
                            }
                        }
                    }
                }

                composable<Route.CourtMarking> { entry ->
                    val args = entry.toRoute<Route.CourtMarking>()
                    val appCtx = LocalContext.current.applicationContext
                    val vm: CourtMarkingViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                val e = localVideos.get(args.entryId) ?: error("Local video not found")
                                CourtMarkingViewModel(args.entryId) {
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
                            // one would not. Naming a destination is also wrong for a
                            // video-first run now that there are three ways in - the
                            // drawer, LocalPlayer and the Analytics list - and popping
                            // to Home threw away whichever list the coach was working
                            // through. Returning to the caller is the same answer for
                            // both kinds of entry, so there is one branch fewer.
                            //
                            // Safe against a second run being started from the screen
                            // the coach lands back on: LocalAnalysisRunner.start returns
                            // immediately for an entry already running, and the cloud
                            // path moves the entry to UPLOADING, which hides the button.
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

@Composable
private fun Splash() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Rally Clips")
        CircularProgressIndicator()
    }
}
