package com.badmintontracker.android

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.badmintontracker.android.cliplist.ClipListScreen
import com.badmintontracker.android.cliplist.ClipListViewModel
import com.badmintontracker.android.cliplist.MatchSummaryViewModel
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
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.RallyApp
import com.badmintontracker.shared.scoring.ScoreLogStatus
import io.github.jan.supabase.auth.status.SessionStatus

@Composable
fun AuthGate(
    rally: RallyApp,
    themePrefs: ThemePreferenceRepository,
    localVideos: LocalVideoRepository,
    coordinator: AnalyzeCoordinator,
    localAnnotations: LocalAnnotationsRepository,
) {
    val session by rally.auth.sessionFlow.collectAsStateWithLifecycle(initialValue = null)

    when (val s = session) {
        null -> Splash()
        else -> {
            val nav = rememberNavController()
            val start: Route = if (s is SessionStatus.Authenticated) Route.ClipList else Route.SignIn

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
                        nav.navigate(Route.ClipList) {
                            popUpTo(Route.SignIn) { inclusive = true }
                        }
                    }
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
                            nav.navigate(Route.ClipList) {
                                popUpTo(Route.SignIn) { inclusive = true }
                            }
                        },
                    )
                }
                composable<Route.ClipList> {
                    val clipListVm: ClipListViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { ClipListViewModel(rally.clips, rally.auth, rally.shares, rally.videos, rally.scoreLogs, localVideos, coordinator) }
                        }
                    )
                    val localVm: LocalVideoListViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { LocalVideoListViewModel(localVideos, coordinator, localAnnotations) }
                        }
                    )
                    val localRows by localVm.rows.collectAsStateWithLifecycle()
                    ClipListScreen(
                        vm = clipListVm,
                        media = rally.media,
                        shares = rally.shares,
                        themePrefs = themePrefs,
                        onMatchClick = { nav.navigate(Route.Match(videoId = it.videoId)) },
                        onScoreMatchClick = { nav.navigate(Route.Match(scoreLogId = it.scoreLogId)) },
                        onNewMatch = { nav.navigate(Route.NewMatch) },
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
                        // pop back to. Collapsing to ClipList first keeps a single
                        // Match entry on the stack either way. The chosen intent (if
                        // any) rides along so the match page can act on it once.
                        onFinished = { intent ->
                            nav.navigate(
                                Route.Match(scoreLogId = args.scoreLogId, attach = intent?.name)
                            ) {
                                popUpTo(Route.ClipList) { inclusive = false }
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
                            initializer { ClipListViewModel(rally.clips, rally.auth, rally.shares, rally.videos, rally.scoreLogs, localVideos, coordinator) }
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
                            initializer { MatchViewModel(rally.scoreLogs, args.scoreLogId) }
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
                    CourtMarkingScreen(
                        vm = vm,
                        onStartAnalysis = { keypoints ->
                            coordinator.startAnalysis(args.entryId, keypoints)
                            if (localVideos.get(args.entryId)?.scoreLogId != null) {
                                // A single pop, not popBackStack(Route.Match(...)):
                                // typed-route popping matches on the serialized
                                // route, and the instance on the stack carries the
                                // `attach` argument this one would not. The match
                                // page is directly below court marking anyway.
                                nav.popBackStack()
                            } else {
                                // Video-first can arrive here from LocalPlayer as
                                // well as from the list, so this one still names its
                                // destination.
                                nav.popBackStack(Route.ClipList, inclusive = false)
                            }
                        },
                        onBack = { nav.popBackStack() },
                    )
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
