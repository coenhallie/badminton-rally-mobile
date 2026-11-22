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

    @Test fun a_rally_row_says_its_length_and_its_notes() {
        assertEquals("15.6s · 3 notes", rallyRowMeta(15.6, noteCount = 3))
        assertEquals("15.6s · 1 note", rallyRowMeta(15.6, noteCount = 1))
    }

    @Test fun a_rally_row_with_no_notes_says_only_its_length() {
        assertEquals("15.6s", rallyRowMeta(15.6, noteCount = 0))
    }

    @Test fun a_rally_row_without_a_length_says_only_its_notes() {
        assertEquals("2 notes", rallyRowMeta(0.0, noteCount = 2))
        assertNull(rallyRowMeta(0.0, noteCount = 0))
    }
}
