package com.badmintontracker.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.badmintontracker.android.nav.Route
import com.badmintontracker.android.signin.SignInScreen
import com.badmintontracker.android.signin.SignInViewModel
import com.badmintontracker.shared.RallyApp
import io.github.jan.supabase.auth.status.SessionStatus

@Composable
fun AuthGate(rally: RallyApp) {
    val session by rally.auth.sessionFlow.collectAsStateWithLifecycle(initialValue = null)

    when (val s = session) {
        null -> Splash()
        else -> {
            val nav = rememberNavController()
            val start: Route = if (s is SessionStatus.Authenticated) Route.ClipList else Route.SignIn

            LaunchedEffect(s) {
                if (s is SessionStatus.NotAuthenticated &&
                    nav.currentDestination?.route?.contains("ClipList") == true
                ) {
                    nav.navigate(Route.SignIn) {
                        popUpTo(Route.ClipList) { inclusive = true }
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
                            initializer { ClipListViewModel(rally.clips, rally.auth) }
                        }
                    )
                    ClipListScreen(
                        vm = clipListVm,
                        media = rally.media,
                        onMatchClick = { nav.navigate(Route.MatchClips(it.videoId)) },
                    )
                }
                composable<Route.MatchClips> { entry ->
                    val args = entry.toRoute<Route.MatchClips>()
                    val clipListVm: ClipListViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { ClipListViewModel(rally.clips, rally.auth) }
                        }
                    )
                    MatchClipsScreen(
                        vm = clipListVm,
                        media = rally.media,
                        videoId = args.videoId,
                        onBack = { nav.popBackStack() },
                        onClipClick = { nav.navigate(Route.ClipDetail(it.id)) },
                    )
                }
                composable<Route.ClipDetail> { entry ->
                    val args = entry.toRoute<Route.ClipDetail>()
                    val vm: ClipDetailViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                ClipDetailViewModel(args.clipId, rally.clips, rally.annotations, rally.media)
                            }
                        }
                    )
                    ClipDetailScreen(vm = vm, onBack = { nav.popBackStack() })
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
