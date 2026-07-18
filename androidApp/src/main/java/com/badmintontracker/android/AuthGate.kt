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
import com.badmintontracker.android.cliplist.MatchClipsScreen
import com.badmintontracker.shared.prefs.ThemePreferenceRepository
import com.badmintontracker.android.localvideo.LocalPlayerScreen
import com.badmintontracker.android.localvideo.LocalPlayerViewModel
import com.badmintontracker.android.localvideo.LocalVideoListViewModel
import com.badmintontracker.android.localvideo.court.CourtMarkingScreen
import com.badmintontracker.android.localvideo.court.CourtMarkingViewModel
import com.badmintontracker.android.localvideo.court.loadFirstFrame
import com.badmintontracker.android.localvideo.rememberVideoIntake
import com.badmintontracker.android.nav.Route
import com.badmintontracker.android.signin.SignInScreen
import com.badmintontracker.android.signin.SignInViewModel
import com.badmintontracker.shared.localvideo.AnalyzeCoordinator
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.RallyApp
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
                            initializer { ClipListViewModel(rally.clips, rally.auth, rally.shares) }
                        }
                    )
                    val localVm: LocalVideoListViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { LocalVideoListViewModel(localVideos, coordinator, localAnnotations) }
                        }
                    )
                    val localRows by localVm.rows.collectAsStateWithLifecycle()
                    var intakeError by remember { mutableStateOf<String?>(null) }
                    val intake = rememberVideoIntake(
                        onAdded = localVideos::add,
                        onError = { intakeError = it },
                    )
                    ClipListScreen(
                        vm = clipListVm,
                        media = rally.media,
                        shares = rally.shares,
                        themePrefs = themePrefs,
                        onMatchClick = { nav.navigate(Route.MatchClips(it.videoId)) },
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
                        onRecord = intake.record,
                        onImport = intake.import,
                    )
                }
                composable<Route.MatchClips> { entry ->
                    val args = entry.toRoute<Route.MatchClips>()
                    val clipListVm: ClipListViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { ClipListViewModel(rally.clips, rally.auth, rally.shares) }
                        }
                    )
                    MatchClipsScreen(
                        vm = clipListVm,
                        media = rally.media,
                        shares = rally.shares,
                        videoId = args.videoId,
                        themePrefs = themePrefs,
                        onBack = { nav.popBackStack() },
                        onClipClick = { nav.navigate(Route.ClipDetail(it.id)) },
                    )
                }
                composable<Route.ClipDetail> { entry ->
                    val args = entry.toRoute<Route.ClipDetail>()
                    val vm: ClipDetailViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                ClipDetailViewModel(args.clipId, rally.clips, rally.annotations, rally.media, rally.auth)
                            }
                        }
                    )
                    ClipDetailScreen(vm = vm, onBack = { nav.popBackStack() })
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
                                initializer { LocalPlayerViewModel(args.entryId, localAnnotations) }
                            }
                        )
                        LocalPlayerScreen(
                            vm = playerVm,
                            entry = e,
                            canAnalyze = e.stage == AnalyzeStage.LOCAL || e.stage == AnalyzeStage.FAILED,
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
                            nav.popBackStack(Route.ClipList, inclusive = false)
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
