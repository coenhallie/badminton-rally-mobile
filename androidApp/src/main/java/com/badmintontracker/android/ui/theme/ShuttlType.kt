package com.badmintontracker.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Default = Typography()

internal val ShuttlTypography = Typography(
    headlineLarge  = Default.headlineLarge.copy (fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,    letterSpacing = (-0.5).sp),
    headlineMedium = Default.headlineMedium.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,    letterSpacing = (-0.4).sp),
    titleLarge     = Default.titleLarge.copy    (fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold),
    titleMedium    = Default.titleMedium.copy   (fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium),
    bodyLarge      = Default.bodyLarge.copy     (fontFamily = FontFamily.Default),
    bodyMedium     = Default.bodyMedium.copy    (fontFamily = FontFamily.Default),
    bodySmall      = Default.bodySmall.copy     (fontFamily = FontFamily.Default),
    // Tiny uppercase tracked label — matches `.field-label` (text-transform: uppercase, letter-spacing: 0.05em).
    labelSmall = TextStyle(
        fontFamily    = FontFamily.Default,
        fontWeight    = FontWeight.Medium,
        fontSize      = 11.sp,
        letterSpacing = 0.55.sp,    // ~0.05em at 11sp
    ),
)
