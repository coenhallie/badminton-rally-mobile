package com.badmintontracker.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

/**
 * The rounded scale from the badminton-design mock.
 *
 * Radii live in [ShuttlRadius]; this maps them onto M3's slots.
 */
internal val ShuttlShapes = Shapes(
    extraSmall = RoundedCornerShape(ShuttlRadius.extraSmall),
    small      = RoundedCornerShape(ShuttlRadius.small),
    medium     = RoundedCornerShape(ShuttlRadius.medium),
    large      = RoundedCornerShape(ShuttlRadius.large),
    extraLarge = RoundedCornerShape(ShuttlRadius.extraLarge),
)
