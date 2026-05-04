package com.badmintontracker.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.badmintontracker.shared.RallyApp

@Composable
fun rememberRallyApp(): RallyApp {
    val ctx = LocalContext.current.applicationContext
    return remember(ctx) { (ctx as RallyAndroidApp).rally }
}
