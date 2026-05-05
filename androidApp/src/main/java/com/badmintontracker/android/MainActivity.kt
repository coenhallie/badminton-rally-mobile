package com.badmintontracker.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.data.ThemeMode
import com.badmintontracker.android.ui.theme.RallyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val app = application as RallyAndroidApp
        setContent {
            val mode by app.themePrefs.mode.collectAsStateWithLifecycle()
            RallyTheme(darkTheme = mode == ThemeMode.DARK) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AuthGate(rally = app.rally, themePrefs = app.themePrefs)
                }
            }
        }
    }
}
