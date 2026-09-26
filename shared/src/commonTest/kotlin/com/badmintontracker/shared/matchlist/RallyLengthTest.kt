package com.badmintontracker.shared.matchlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RallyLengthTest {
    @Test fun a_length_is_seconds_to_one_decimal() {
        assertEquals("25.2s", rallyLengthText(25.1727))
        assertEquals("10.9s", rallyLengthText(10.8689))
    }

    @Test fun a_whole_second_keeps_its_decimal() {
        assertEquals("7.0s", rallyLengthText(7.0))
    }

    @Test fun it_rounds_half_up() {
        assertEquals("7.5s", rallyLengthText(7.45000001))
        assertEquals("60.0s", rallyLengthText(59.96))
    }

    @Test fun the_float_the_server_stores_prints_the_same() {
        assertEquals("25.2s", rallyLengthText(25.1727f.toDouble()))
    }

    @Test fun a_length_that_is_not_one_prints_nothing() {
        assertNull(rallyLengthText(0.0))
        assertNull(rallyLengthText(0.04))
        assertNull(rallyLengthText(-3.0))
        assertNull(rallyLengthText(Double.NaN))
        assertNull(rallyLengthText(Double.POSITIVE_INFINITY))
    }
}
