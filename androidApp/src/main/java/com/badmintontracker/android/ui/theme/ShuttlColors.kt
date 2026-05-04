package com.badmintontracker.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Web tokens (badminton-tracker/src/app.css) → Compose colors.
private val LightBg              = Color(0xFFFFFFFF)
private val LightBgSecondary     = Color(0xFFF8F9FA)
private val LightBgTertiary      = Color(0xFFF0F1F3)
private val LightBgInput         = Color(0xFFF0F1F3)
private val LightBorder          = Color(0xFFE0E0E0)
private val LightBorderSecondary = Color(0xFFD0D0D0)
private val LightTextHeading     = Color(0xFF0D0D0D)
private val LightText            = Color(0xFF1A1A2E)
private val LightTextSecondary   = Color(0xFF555555)
private val LightTextTertiary    = Color(0xFF777777)
private val LightAccent          = Color(0xFF16A34A)
private val LightAccentDark      = Color(0xFF166534)

private val DarkBg               = Color(0xFF0D0D0D)
private val DarkBgSecondary      = Color(0xFF141414)
private val DarkBgTertiary       = Color(0xFF1A1A1A)
private val DarkBgInput          = Color(0xFF111111)
private val DarkBorder           = Color(0xFF222222)
private val DarkBorderSecondary  = Color(0xFF333333)
private val DarkTextHeading      = Color(0xFFFFFFFF)
private val DarkText             = Color(0xFFE2E8F0)
private val DarkTextSecondary    = Color(0xFF888888)
private val DarkTextTertiary     = Color(0xFF666666)
private val DarkAccent           = Color(0xFF22C55E)
private val DarkAccentDark       = Color(0xFF16A34A)

private val Error   = Color(0xFFEF4444)
private val Warning = Color(0xFFF59E0B)
private val Info    = Color(0xFF3B82F6)

internal val ShuttlLightColorScheme = lightColorScheme(
    primary          = LightAccent,
    onPrimary        = Color.Black,
    background       = LightBg,
    onBackground     = LightTextHeading,
    surface          = LightBg,
    onSurface        = LightText,
    surfaceVariant   = LightBgSecondary,
    onSurfaceVariant = LightTextSecondary,
    outline          = LightBorderSecondary,
    outlineVariant   = LightBorder,
    error            = Error,
    onError          = Color.White,
)

internal val ShuttlDarkColorScheme = darkColorScheme(
    primary          = DarkAccent,
    onPrimary        = Color.Black,
    background       = DarkBg,
    onBackground     = DarkTextHeading,
    surface          = DarkBg,
    onSurface        = DarkText,
    surfaceVariant   = DarkBgSecondary,
    onSurfaceVariant = DarkTextSecondary,
    outline          = DarkBorderSecondary,
    outlineVariant   = DarkBorder,
    error            = Error,
    onError          = Color.White,
)

/** Extended palette beyond M3's ColorScheme. */
@Immutable
data class ShuttlExtendedColors(
    val accentDark:      Color,
    val bgInput:         Color,
    val bgTertiary:      Color,
    val textTertiary:    Color,
    val warning:         Color,
    val info:            Color,
)

internal val ShuttlLightExtended = ShuttlExtendedColors(
    accentDark   = LightAccentDark,
    bgInput      = LightBgInput,
    bgTertiary   = LightBgTertiary,
    textTertiary = LightTextTertiary,
    warning      = Warning,
    info         = Info,
)

internal val ShuttlDarkExtended = ShuttlExtendedColors(
    accentDark   = DarkAccentDark,
    bgInput      = DarkBgInput,
    bgTertiary   = DarkBgTertiary,
    textTertiary = DarkTextTertiary,
    warning      = Warning,
    info         = Info,
)

val LocalShuttlColors = staticCompositionLocalOf { ShuttlLightExtended }

object ShuttlTheme {
    val extended: ShuttlExtendedColors
        @Composable @ReadOnlyComposable
        get() = LocalShuttlColors.current
}
