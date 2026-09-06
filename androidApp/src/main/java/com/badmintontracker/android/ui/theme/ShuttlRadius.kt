package com.badmintontracker.android.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The shape scale, as raw radii.
 *
 * [pill] has no slot in M3's [androidx.compose.material3.Shapes], which is why
 * this object exists alongside ShuttlShapes rather than inside it. Mirrors
 * iosApp's ShuttlRadius.swift number for number.
 */
object ShuttlRadius {
    val extraSmall = 8.dp
    val small      = 12.dp
    val medium     = 16.dp
    val large      = 20.dp
    val extraLarge = 28.dp

    /** Buttons, chips, badges, tab pills. Larger than any surface it clips. */
    val pill       = 999.dp
}
