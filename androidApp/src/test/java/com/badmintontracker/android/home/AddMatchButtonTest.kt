package com.badmintontracker.android.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddMatchButtonTest {
    // The filter row is always item 0, so the label shows until the first
    // match row has scrolled under the top bar.
    @Test fun labelled_at_the_top_of_the_list() {
        assertTrue(addButtonExpanded(firstVisibleItemIndex = 0))
    }

    @Test fun collapses_once_the_list_is_scrolled() {
        assertFalse(addButtonExpanded(firstVisibleItemIndex = 1))
        assertFalse(addButtonExpanded(firstVisibleItemIndex = 12))
    }
}
