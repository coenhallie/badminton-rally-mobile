package com.badmintontracker.shared.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeTabTest {
    @Test fun the_app_opens_on_matches() {
        assertEquals(HomeTab.MATCHES, HomeTab.DEFAULT)
    }

    @Test fun only_the_matches_tab_with_nothing_above_it_is_the_front() {
        assertTrue(isHomeFront(HomeTab.MATCHES, nothingAbove = true))
    }

    @Test fun something_above_the_tabs_is_never_the_front() {
        HomeTab.entries.forEach { assertFalse(isHomeFront(it, nothingAbove = false), "$it") }
    }

    // Review focus 2: a coach in Settings or already in Analytics is not
    // pulled anywhere by a run that lands.
    @Test fun analytics_and_settings_are_never_the_front() {
        assertFalse(isHomeFront(HomeTab.ANALYTICS, nothingAbove = true))
        assertFalse(isHomeFront(HomeTab.SETTINGS, nothingAbove = true))
    }
}
