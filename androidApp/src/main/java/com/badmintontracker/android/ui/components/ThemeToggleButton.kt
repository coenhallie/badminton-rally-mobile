package com.badmintontracker.android.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.shared.prefs.ThemeMode
import com.badmintontracker.shared.signin.SignInCopy

/**
 * The sign-in screen's theme switch, in the title bar. Drawn the way the
 * list's Settings gear is (HomeScreen): a bare glyph in a 48dp IconButton,
 * 20dp, onSurfaceVariant. It used to sit in a bordered square, a box nothing
 * else in the app draws.
 *
 * The glyph is the theme a tap switches to: a moon while light.
 */
@Composable
fun ThemeToggleButton(
    mode:     ThemeMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = when (mode) {
        ThemeMode.LIGHT -> ShuttlIcons.Moon
        ThemeMode.DARK  -> ShuttlIcons.Sun
    }
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            imageVector        = icon,
            contentDescription = SignInCopy.themeToggle(mode),
            tint               = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier           = Modifier.size(20.dp),
        )
    }
}
