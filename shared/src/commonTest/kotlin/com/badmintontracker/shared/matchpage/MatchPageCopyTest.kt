package com.badmintontracker.shared.matchpage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MatchPageCopyTest {
    @Test fun the_sections_are_named_as_the_spec_names_them() {
        assertEquals(listOf("Points", "Match", "Rallies", "Analysis"), MatchPageSection.entries.map { it.label })
    }

    @Test fun no_em_dash_anywhere() {
        listOf(
            MatchPageCopy.SECTIONS, MatchPageCopy.VIEW, MatchPageCopy.SORT, MatchPageCopy.SORT_RALLY_ORDER,
            MatchPageCopy.SORT_MOST_NOTES, MatchPageCopy.SETTINGS, MatchPageCopy.DEVICE_CLIPS_EMPTY,
            MatchPageCopy.MISSING, MatchPageCopy.ADD_NOTE, MatchPageCopy.analyzed("A"), MatchPageCopy.analyzedCount(2),
        ).forEach { assertFalse('\u2014' in it, it) }
    }
}
