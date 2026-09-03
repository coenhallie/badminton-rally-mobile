package com.badmintontracker.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

/**
 * The rounded scale from the badminton-design mock.
 *
 * This replaced an all-0dp scale ported from the web app's flat aesthetic. The
 * two are not reconcilable and the mock wins on mobile, so the web tokens and
 * these have deliberately forked. Radii live in [ShuttlRadius]; this maps them
 * onto M3's slots.
 */
internal val ShuttlShapes = Shapes(
    extraSmall = RoundedCornerShape(ShuttlRadius.extraSmall),
    small      = RoundedCornerShape(ShuttlRadius.small),
    medium     = RoundedCornerShape(ShuttlRadius.medium),
    large      = RoundedCornerShape(ShuttlRadius.large),
    extraLarge = RoundedCornerShape(ShuttlRadius.extraLarge),
)
