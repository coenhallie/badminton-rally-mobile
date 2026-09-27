package com.badmintontracker.shared.matchlist

import kotlin.math.roundToLong

/**
 * A rally's length as the rally rows print it: seconds to one decimal, "25.2s".
 *
 * Seconds rather than a clock ("0:25") because a rally is a few seconds to a
 * minute long and the tenths are what tell two short ones apart; the match
 * list's clock is for whole recordings. One decimal because that is what the
 * device clip rows already printed, and the cloud rows' design is
 * "<duration>s · <n> notes" (2026-05-04 Android UI design), which printed the
 * stored float as it came: "25.1727S".
 *
 * Null for a length that is not one (zero, negative, not a number): a clip
 * recovered without its bounds, or a row the server has not measured. "0.0s"
 * would read as a broken clip rather than as a missing number.
 *
 * Shared so both platforms print the same thing: the device and cloud rally
 * rows on Android and iOS all call it.
 */
fun rallyLengthText(seconds: Double): String? {
    if (seconds.isNaN() || seconds <= 0.0 || seconds.isInfinite()) return null
    val tenths = (seconds * 10).roundToLong()
    if (tenths == 0L) return null
    return "${tenths / 10}.${tenths % 10}s"
}
