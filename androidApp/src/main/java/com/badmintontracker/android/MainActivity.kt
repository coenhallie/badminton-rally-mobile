package com.badmintontracker.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.shared.prefs.ThemeMode
import com.badmintontracker.android.ui.theme.RallyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val app = application as RallyAndroidApp

        // Android 13+ hides the on-device analysis notification without this.
        // The foreground service still runs if it is denied - only the progress
        // notification is lost - so this asks and does not insist.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

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

            RallyTheme(darkTheme = mode == ThemeMode.DARK) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AuthGate(
                        rally = app.rally,
                        themePrefs = app.themePrefs,
                        localVideos = app.localVideos,
                        coordinator = app.analyzeCoordinator,
                        localAnalysis = app.localAnalysis,
                        localAnnotations = app.localAnnotations,
                    )
                }
            }
        }
    }
}
