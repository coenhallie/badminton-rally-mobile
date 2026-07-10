package com.badmintontracker.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdsTest {
    @Test
    fun randomUuid_is_36_chars_and_unique() {
        val a = randomUuid()
        val b = randomUuid()
        assertEquals(36, a.length)
        assertNotEquals(a, b)
    }

    @Test
    fun nowEpochMs_is_plausible() {
        // Any moment after 2020-01-01 and monotonic-ish.
        assertTrue(nowEpochMs() > 1_577_836_800_000)
    }
}
