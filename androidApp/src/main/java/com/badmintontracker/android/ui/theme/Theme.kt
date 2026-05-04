package com.badmintontracker.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun RallyTheme(
    darkTheme: Boolean = false,           // Default LIGHT — matches web.
    content:   @Composable () -> Unit,
) {
    val colors    = if (darkTheme) ShuttlDarkColorScheme else ShuttlLightColorScheme
    val extended  = if (darkTheme) ShuttlDarkExtended    else ShuttlLightExtended

    CompositionLocalProvider(LocalShuttlColors provides extended) {
        MaterialTheme(
            colorScheme = colors,
            typography  = ShuttlTypography,
            shapes      = ShuttlShapes,
            content     = content,
        )
    }
}
