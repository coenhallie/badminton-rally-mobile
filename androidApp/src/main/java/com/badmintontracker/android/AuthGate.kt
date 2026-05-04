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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.badmintontracker.android.nav.Route
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
                    Text("SignIn (placeholder — Task 11)")
                }
                composable<Route.ClipList> {
                    Text("ClipList (placeholder — Task 13)")
                }
                composable<Route.ClipDetail> { entry ->
                    val args = entry.toRoute<Route.ClipDetail>()
                    Text("ClipDetail (placeholder — Task 15) clipId=${args.clipId}")
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
