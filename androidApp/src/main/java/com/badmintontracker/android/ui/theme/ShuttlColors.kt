package com.badmintontracker.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Design tokens -> Compose colors. See ShuttlPalette.kt for the raw values.

private fun Long.toColor(): Color = Color(0xFF000000L or this)

private val ShuttlPalette.Tone.lightColor: Color get() = light.toColor()
private val ShuttlPalette.Tone.darkColor: Color get() = dark.toColor()

internal val ShuttlLightColorScheme = lightColorScheme(
    primary          = ShuttlPalette.accent.lightColor,
    onPrimary        = ShuttlPalette.onAccent.lightColor,
    background       = ShuttlPalette.bg.lightColor,
    onBackground     = ShuttlPalette.textHeading.lightColor,
    surface          = ShuttlPalette.bg.lightColor,
    onSurface        = ShuttlPalette.text.lightColor,
    surfaceVariant   = ShuttlPalette.bgSecondary.lightColor,
    onSurfaceVariant = ShuttlPalette.textSecondary.lightColor,
    outline          = ShuttlPalette.borderSecondary.lightColor,
    outlineVariant   = ShuttlPalette.border.lightColor,
    error            = ShuttlPalette.error.lightColor,
    onError          = ShuttlPalette.onError.lightColor,
)

internal val ShuttlDarkColorScheme = darkColorScheme(
    primary          = ShuttlPalette.accent.darkColor,
    onPrimary        = ShuttlPalette.onAccent.darkColor,
    background       = ShuttlPalette.bg.darkColor,
    onBackground     = ShuttlPalette.textHeading.darkColor,
    surface          = ShuttlPalette.bg.darkColor,
    onSurface        = ShuttlPalette.text.darkColor,
    surfaceVariant   = ShuttlPalette.bgSecondary.darkColor,
    onSurfaceVariant = ShuttlPalette.textSecondary.darkColor,
    outline          = ShuttlPalette.borderSecondary.darkColor,
    outlineVariant   = ShuttlPalette.border.darkColor,
    error            = ShuttlPalette.error.darkColor,
    onError          = ShuttlPalette.onError.darkColor,
)

/** Extended palette beyond M3's ColorScheme. */
@Immutable
data class ShuttlExtendedColors(
    val accentDark:   Color,
    val onAccent:     Color,
    val bgInput:      Color,
    val bgTertiary:   Color,
    val textTertiary: Color,
    /** Display sizes only. See ShuttlPalette.textMuted. */
    val textMuted:    Color,
    val warning:      Color,
    val info:         Color,
    /** The home half of the scoreboard. Identifies the side, never the end. */
    val sideHome:     Color,
    val sideAway:     Color,
)

internal val ShuttlLightExtended = ShuttlExtendedColors(
    accentDark   = ShuttlPalette.accentDark.lightColor,
    onAccent     = ShuttlPalette.onAccent.lightColor,
    bgInput      = ShuttlPalette.bgInput.lightColor,
    bgTertiary   = ShuttlPalette.bgTertiary.lightColor,
    textTertiary = ShuttlPalette.textTertiary.lightColor,
    textMuted    = ShuttlPalette.textMuted.lightColor,
    warning      = ShuttlPalette.warning.lightColor,
    info         = ShuttlPalette.info.lightColor,
    sideHome     = ShuttlPalette.sideHome.lightColor,
    sideAway     = ShuttlPalette.sideAway.lightColor,
)

internal val ShuttlDarkExtended = ShuttlExtendedColors(
    accentDark   = ShuttlPalette.accentDark.darkColor,
    onAccent     = ShuttlPalette.onAccent.darkColor,
    bgInput      = ShuttlPalette.bgInput.darkColor,
    bgTertiary   = ShuttlPalette.bgTertiary.darkColor,
    textTertiary = ShuttlPalette.textTertiary.darkColor,
    textMuted    = ShuttlPalette.textMuted.darkColor,
    warning      = ShuttlPalette.warning.darkColor,
    info         = ShuttlPalette.info.darkColor,
    sideHome     = ShuttlPalette.sideHome.darkColor,
    sideAway     = ShuttlPalette.sideAway.darkColor,
)

val LocalShuttlColors = staticCompositionLocalOf { ShuttlLightExtended }

/**
 * What sits on top of a video, in both themes.
 *
 * Deliberately not themed: these draw over the frame, not over the page, and
 * the frame is whatever the camera saw. A light-theme scrim would be a pale
 * chip over a purple court, so the dark palette's values are used in both
 * themes, the way the scoreboard halves are fixed for their own reasons. Text
 * on the scrim is the dark theme's text, and accent text on it is the dark
 * theme's [ShuttlPalette.accentDark], following the rule that the accent is
 * a fill and never a text colour.
 */
object ShuttlVideoOverlay {
    /** 70% of the dark page colour, the mock's own `rgba(11,12,13,.7)`. */
    val scrim: Color = ShuttlPalette.bg.darkColor.copy(alpha = 0.7f)
    val text: Color = ShuttlPalette.text.darkColor
    val accentText: Color = ShuttlPalette.accentDark.darkColor
    /** The unfilled part of the progress bar: the mock's `rgba(255,255,255,.12)`. */
    val track: Color = Color.White.copy(alpha = 0.12f)
    val progress: Color = ShuttlPalette.accent.darkColor
}

object ShuttlTheme {
    val extended: ShuttlExtendedColors
        @Composable @ReadOnlyComposable
        get() = LocalShuttlColors.current
}
