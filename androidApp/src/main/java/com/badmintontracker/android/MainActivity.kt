package com.badmintontracker.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.shared.prefs.ThemeMode
import com.badmintontracker.android.players.AddPlayerDialogHost
import com.badmintontracker.android.ui.theme.RallyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val app = application as RallyAndroidApp

        setContent {
            val mode by app.themePrefs.mode.collectAsStateWithLifecycle()

            // Don't let the device doze off mid-upload (foreground-only uploads).
            val uploading by app.analyzeCoordinator.hasActiveUpload.collectAsStateWithLifecycle()
            LaunchedEffect(uploading) {
                if (uploading) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            val darkTheme = mode == ThemeMode.DARK
            // Status and navigation bar icons are drawn by the system, not by
            // the theme, and defaulted to light on both themes: white on white,
            // 1.00:1, literally invisible on every light screen.
            //
            // Safe to drive straight from the theme because the only surfaces
            // that put black behind the system bars are the fullscreen video
            // ones, and FullscreenEffect HIDES the system bars rather than
            // recolouring them, so there is no screen left where dark icons
            // would sit on a dark backdrop.
            LaunchedEffect(darkTheme) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }

            RallyTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // The player pickers' "Add player" dialogs open here, in
                    // this window, wherever their picker is (see the host).
                    AddPlayerDialogHost {
                        AuthGate(
                            rally = app.rally,
                            themePrefs = app.themePrefs,
                            localVideos = app.localVideos,
                            coordinator = app.analyzeCoordinator,
                            localAnalysis = app.localAnalysis,
                            backgroundWork = app.backgroundWork,
                            throughput = app.throughput,
                            localAnnotations = app.localAnnotations,
                            installCloudPose = app.installCloudPose,
                        )
                    }
                }
            }
        }
    }
}
