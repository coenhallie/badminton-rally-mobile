package com.badmintontracker.shared.util

import platform.Foundation.NSDate

internal actual fun nowEpochMs(): Long {
    // NSDate reference is Jan 1, 2001, Unix epoch is Jan 1, 1970
    // Offset: 978307200 seconds = 978307200000 milliseconds
    return (NSDate().timeIntervalSinceReferenceDate * 1000).toLong() + 978307200000L
}
